package com.ops.deploy.deploy;

import com.ops.deploy.notify.NotificationEntity;
import com.ops.deploy.notify.NotificationRepository;
import com.ops.deploy.notify.WebhookNotifier;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康探针 {@link ServiceHealthProbe} 单测（mock 仓库 + SshService + 通知仓库）。
 *
 * <p>覆盖：探测命令构造（PROCESS/DOCKER/NGINX + shell 转义 + 缺字段跳过）、
 * interpret 判定（UP/DOWN/不可识别）、状态翻转落库 + 通知写入（DOWN=WARN / UP=INFO）、
 * 状态一致不重复通知、SSH 异常不翻转、单实例失败不影响其他实例。
 */
@ExtendWith(MockitoExtension.class)
class ServiceHealthProbeTest {

    @Mock
    private ServiceInstanceRepository instanceRepository;
    @Mock
    private SshService sshService;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private WebhookNotifier webhookNotifier;

    @Captor
    private ArgumentCaptor<ServiceInstanceEntity> instanceCaptor;
    @Captor
    private ArgumentCaptor<NotificationEntity> notifyCaptor;
    @Captor
    private ArgumentCaptor<String> webhookTitleCaptor;
    @Captor
    private ArgumentCaptor<String> webhookMsgCaptor;

    private ServiceHealthProbe probe;

    @BeforeEach
    void setUp() {
        probe = new ServiceHealthProbe(instanceRepository, sshService,
                notificationRepository, webhookNotifier);
    }

    // ---------- 探测命令构造（纯函数） ----------

    @Test
    @DisplayName("PROCESS 探测命令：systemctl is-active 静默判定 + echo UP/DOWN")
    void processProbeCommand() {
        assertThat(ServiceHealthProbe.buildProcessProbeCommand("deploy-demo.service"))
                .isEqualTo("systemctl is-active --quiet deploy-demo.service && echo UP || echo DOWN");
    }

    @Test
    @DisplayName("PROCESS 探测命令：unitName 含特殊字符时经 shell 转义")
    void processProbeCommandEscapesUnitName() {
        assertThat(ServiceHealthProbe.buildProcessProbeCommand("deploy-odd name.service"))
                .isEqualTo("systemctl is-active --quiet 'deploy-odd name.service' && echo UP || echo DOWN");
    }

    @Test
    @DisplayName("DOCKER 探测命令：compose ps --status running --services")
    void dockerProbeCommand() {
        assertThat(ServiceHealthProbe.buildDockerProbeCommand("/opt/apps/demo/v1"))
                .isEqualTo("docker-compose -f /opt/apps/demo/v1/docker-compose.yml ps --status running --services");
    }

    @Test
    @DisplayName("NGINX 探测命令：curl 本机端口+健康路径输出状态码")
    void nginxProbeCommand() {
        assertThat(ServiceHealthProbe.buildNginxProbeCommand(8080, "/health"))
                .isEqualTo("curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/health");
        // 健康路径为空时兜底 /
        assertThat(ServiceHealthProbe.buildNginxProbeCommand(80, null))
                .isEqualTo("curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:80/");
    }

    @Test
    @DisplayName("probeCommand 按 mode 分支；PROCESS 缺 unitName / NGINX 缺 port 返回 null（跳过）")
    void probeCommandBranchesByMode() {
        ServiceInstanceEntity process = instance("PROCESS", "deploy-demo.service", null, null);
        assertThat(ServiceHealthProbe.probeCommand(process))
                .isEqualTo("systemctl is-active --quiet deploy-demo.service && echo UP || echo DOWN");

        ServiceInstanceEntity docker = instance("DOCKER", null, "/opt/apps/demo/v1", null);
        assertThat(ServiceHealthProbe.probeCommand(docker))
                .isEqualTo("docker-compose -f /opt/apps/demo/v1/docker-compose.yml ps --status running --services");

        ServiceInstanceEntity nginx = instance("NGINX", null, null, 8080);
        nginx.setHealthPath("/healthz");
        assertThat(ServiceHealthProbe.probeCommand(nginx))
                .isEqualTo("curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/healthz");

        // 缺必要字段：跳过
        ServiceInstanceEntity noUnit = instance("PROCESS", null, null, null);
        assertThat(ServiceHealthProbe.probeCommand(noUnit)).isNull();
        ServiceInstanceEntity noPort = instance("NGINX", null, null, null);
        assertThat(ServiceHealthProbe.probeCommand(noPort)).isNull();
        // mode=null 视为 PROCESS
        ServiceInstanceEntity nullMode = instance(null, "deploy-demo.service", null, null);
        assertThat(ServiceHealthProbe.probeCommand(nullMode)).isNotNull();
    }

    // ---------- interpret 判定 ----------

    @Test
    @DisplayName("interpret：PROCESS 看 stdout UP/DOWN；DOCKER 看非空；NGINX 看 2xx/3xx")
    void interpretByMode() {
        assertThat(ServiceHealthProbe.interpret(instance("PROCESS", null, null, null),
                CommandResult.ok("UP", 0))).isTrue();
        assertThat(ServiceHealthProbe.interpret(instance("PROCESS", null, null, null),
                CommandResult.ok("DOWN", 0))).isFalse();
        assertThat(ServiceHealthProbe.interpret(instance("DOCKER", null, "/w", null),
                CommandResult.ok("api\nweb", 0))).isTrue();
        assertThat(ServiceHealthProbe.interpret(instance("DOCKER", null, "/w", null),
                CommandResult.ok("", 0))).isFalse();
        assertThat(ServiceHealthProbe.interpret(instance("NGINX", null, null, 80),
                CommandResult.ok("200", 0))).isTrue();
        assertThat(ServiceHealthProbe.interpret(instance("NGINX", null, null, 80),
                CommandResult.ok("301", 0))).isTrue();
        assertThat(ServiceHealthProbe.interpret(instance("NGINX", null, null, 80),
                CommandResult.ok("502", 0))).isFalse();
        // 超时 / 非 0 退出 / 输出不可识别 → null（不翻转）
        assertThat(ServiceHealthProbe.interpret(instance("PROCESS", null, null, null),
                CommandResult.timeout(10_000))).isNull();
        assertThat(ServiceHealthProbe.interpret(instance("PROCESS", null, null, null),
                CommandResult.fail(1, "", "boom"))).isNull();
    }

    // ---------- probe 状态翻转 ----------

    @Test
    @DisplayName("PROCESS 探针 DOWN：RUNNING→STOPPED 落库 + WARN 通知")
    void downFlipWritesWarnNotification() {
        ServiceInstanceEntity inst = instance("PROCESS", "deploy-demo.service", null, null);
        inst.setId(7L);
        inst.setStatus("RUNNING");
        when(sshService.execute(eq(2L), anyString(), anyInt())).thenReturn(CommandResult.ok("DOWN", 0));

        Boolean up = probe.probe(inst);

        assertThat(up).isFalse();
        verify(instanceRepository).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getStatus()).isEqualTo("STOPPED");
        assertThat(instanceCaptor.getValue().getUpdatedAt()).isNotNull();
        verify(notificationRepository).save(notifyCaptor.capture());
        NotificationEntity n = notifyCaptor.getValue();
        assertThat(n.getLevel()).isEqualTo("WARN");
        assertThat(n.getTitle()).isEqualTo("服务已停止");
        assertThat(n.getProjectId()).isEqualTo(1L);
        assertThat(n.getInstanceId()).isEqualTo(7L);
        // DOWN 翻转额外推 webhook（WARN + 与站内通知同 title/message）
        verify(webhookNotifier).notify(eq("WARN"),
                webhookTitleCaptor.capture(), webhookMsgCaptor.capture());
        assertThat(webhookTitleCaptor.getValue()).isEqualTo("服务已停止");
        assertThat(webhookMsgCaptor.getValue()).isEqualTo(n.getMessage());
        assertThat(webhookMsgCaptor.getValue()).contains("实例 demo-2-1");
    }

    @Test
    @DisplayName("PROCESS 探针 UP：STOPPED→RUNNING 落库 + INFO 通知")
    void upFlipWritesInfoNotification() {
        ServiceInstanceEntity inst = instance("PROCESS", "deploy-demo.service", null, null);
        inst.setId(7L);
        inst.setStatus("STOPPED");
        when(sshService.execute(eq(2L), anyString(), anyInt())).thenReturn(CommandResult.ok("UP", 0));

        Boolean up = probe.probe(inst);

        assertThat(up).isTrue();
        verify(instanceRepository).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getStatus()).isEqualTo("RUNNING");
        verify(notificationRepository).save(notifyCaptor.capture());
        assertThat(notifyCaptor.getValue().getLevel()).isEqualTo("INFO");
        assertThat(notifyCaptor.getValue().getTitle()).isEqualTo("服务恢复运行");
        // UP 恢复仅站内通知，不推 webhook
        verify(webhookNotifier, never()).notify(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("状态一致：不 save、不写通知")
    void noFlipNoSideEffects() {
        ServiceInstanceEntity inst = instance("PROCESS", "deploy-demo.service", null, null);
        inst.setStatus("RUNNING");
        when(sshService.execute(eq(2L), anyString(), anyInt())).thenReturn(CommandResult.ok("UP", 0));

        assertThat(probe.probe(inst)).isTrue();

        verify(instanceRepository, never()).save(any(ServiceInstanceEntity.class));
        verify(notificationRepository, never()).save(any(NotificationEntity.class));
    }

    @Test
    @DisplayName("SSH 异常（服务器不可达）：不翻转状态、不写通知、返回 null")
    void sshFailureDoesNotFlip() {
        ServiceInstanceEntity inst = instance("PROCESS", "deploy-demo.service", null, null);
        inst.setStatus("RUNNING");
        when(sshService.execute(eq(2L), anyString(), anyInt()))
                .thenThrow(new RuntimeException("connect timeout"));

        assertThat(probe.probe(inst)).isNull();
        assertThat(inst.getStatus()).isEqualTo("RUNNING");
        verify(instanceRepository, never()).save(any(ServiceInstanceEntity.class));
        verify(notificationRepository, never()).save(any(NotificationEntity.class));
    }

    @Test
    @DisplayName("缺必要字段（PROCESS 无 unitName）：跳过探测，不发 SSH")
    void missingFieldsSkipsProbe() {
        ServiceInstanceEntity inst = instance("PROCESS", null, null, null);
        inst.setStatus("RUNNING");

        assertThat(probe.probe(inst)).isNull();
        assertThat(inst.getStatus()).isEqualTo("RUNNING");
        verify(sshService, never()).execute(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("probeAll：遍历在线实例；单实例 SSH 异常不影响其他实例")
    void probeAllIsolatesFailures() {
        ServiceInstanceEntity a = instance("PROCESS", "deploy-a.service", null, null);
        a.setId(1L);
        a.setProjectId(1L);
        a.setServerId(2L);
        a.setStatus("RUNNING");
        ServiceInstanceEntity b = instance("PROCESS", "deploy-b.service", null, null);
        b.setId(2L);
        b.setProjectId(1L);
        b.setServerId(2L);
        b.setStatus("RUNNING");
        when(instanceRepository.findAllByOnlineTrue()).thenReturn(List.of(a, b));
        when(sshService.execute(eq(2L), anyString(), anyInt()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(CommandResult.ok("DOWN", 0));

        probe.probeAll();

        // a 抛异常被吞，b 正常探测并翻转
        assertThat(a.getStatus()).isEqualTo("RUNNING");
        assertThat(b.getStatus()).isEqualTo("STOPPED");
        verify(notificationRepository).save(any(NotificationEntity.class));
    }

    /** 构造在线实例（project=1 server=2）。 */
    private static ServiceInstanceEntity instance(String mode, String unitName, String workdir, Integer port) {
        ServiceInstanceEntity e = new ServiceInstanceEntity();
        e.setName("demo-2-1");
        e.setProjectId(1L);
        e.setServerId(2L);
        e.setMode(mode == null ? "PROCESS" : mode);
        e.setUnitName(unitName);
        e.setWorkdir(workdir);
        e.setPort(port);
        e.setOnline(Boolean.TRUE);
        return e;
    }
}
