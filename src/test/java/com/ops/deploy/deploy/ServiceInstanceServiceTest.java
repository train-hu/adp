package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshExecutor;
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
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务实例维护 {@link ServiceInstanceService} 单测（mock 仓库 + SshService）。
 *
 * <p>覆盖：命令构造器（shell 转义）、start/stop/restart 状态流转与失败抛错、
 * get/list 查询、streamLogs 命令串与透传；以及既有 onSuccess / onRolledBack。
 */
@ExtendWith(MockitoExtension.class)
class ServiceInstanceServiceTest {

    @Mock
    private ServiceInstanceRepository instanceRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SshService sshService;
    // 新增：项目访问控制 mock，void 断言方法默认 no-op，鉴权天然放行
    @Mock
    private ProjectAccessService accessService;
    // 新增：子服务解析 mock（pathKey 解析；serviceId 为 0/默认服务时不触发）
    @Mock
    private com.ops.deploy.project.ProjectSubServiceManager subServiceManager;

    // 新增：模拟已登录运维用户（start/stop/restart/delete/get/list/streamLogs 现均需 AuthUser）
    private static final AuthUser OPS = new AuthUser(8L, "ops", "OPS");

    @Captor
    private ArgumentCaptor<ServiceInstanceEntity> saveCaptor;

    private ServiceInstanceService service;

    @BeforeEach
    void setUp() {
        service = new ServiceInstanceService(instanceRepository, projectRepository, sshService, accessService, subServiceManager);
        // 可能被后续测试覆盖的桩用 lenient()，避免 UnnecessaryStubbingException
        lenient().when(instanceRepository.save(any(ServiceInstanceEntity.class)))
                .thenAnswer(inv -> {
                    ServiceInstanceEntity e = inv.getArgument(0);
                    if (e.getId() == null) {
                        e.setId(100L);
                    }
                    return e;
                });
    }

    // ---------- 命令构造器（shell 转义） ----------

    @Test
    @DisplayName("命令构造器：普通 unit 名 quote 后原样")
    void commandBuilders_plainUnitNameUnquoted() {
        String unit = "deploy-demo.service";
        assertThat(ServiceInstanceService.buildStartCommand(unit))
                .isEqualTo("systemctl start deploy-demo.service");
        assertThat(ServiceInstanceService.buildStopCommand(unit))
                .isEqualTo("systemctl stop deploy-demo.service");
        assertThat(ServiceInstanceService.buildRestartCommand(unit))
                .isEqualTo("systemctl restart deploy-demo.service");
        assertThat(ServiceInstanceService.buildJournalCommand(unit))
                .isEqualTo("journalctl -u deploy-demo.service -f --no-pager");
    }

    @Test
    @DisplayName("命令构造器：含特殊字符的 unit 名被单引号包裹")
    void commandBuilders_specialUnitNameQuoted() {
        String unit = "bad name";
        assertThat(ServiceInstanceService.buildStartCommand(unit))
                .isEqualTo("systemctl start 'bad name'");
        assertThat(ServiceInstanceService.buildStopCommand(unit))
                .isEqualTo("systemctl stop 'bad name'");
        assertThat(ServiceInstanceService.buildRestartCommand(unit))
                .isEqualTo("systemctl restart 'bad name'");
        assertThat(ServiceInstanceService.buildJournalCommand(unit))
                .isEqualTo("journalctl -u 'bad name' -f --no-pager");
    }

    @Test
    @DisplayName("unitName：deploy-{projectName}.service")
    void unitName_format() {
        assertThat(ServiceInstanceService.unitName("demo")).isEqualTo("deploy-demo.service");
    }

    // ---------- start / stop / restart ----------

    @Test
    @DisplayName("start：exit 0 → RUNNING/online，save 被调，返回 VO")
    void start_successSetsRunningAndSaves() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(sshService.execute(2L, "systemctl start deploy-demo.service"))
                .thenReturn(CommandResult.ok("ok", 0));

        ServiceInstanceVO vo = service.start(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("RUNNING");
        assertThat(inst.getOnline()).isTrue();
        assertThat(inst.getUpdatedAt()).isNotNull();
        verify(instanceRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).isSameAs(inst);
        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("start：非 0 退出 → 抛 SSH_ERROR，save 未调用")
    void start_nonZeroExitThrowsAndDoesNotSave() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(sshService.execute(2L, "systemctl start deploy-demo.service"))
                .thenReturn(CommandResult.fail(1, "", "Unit not found."));

        assertThatThrownBy(() -> service.start(5L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SSH_ERROR);
                    assertThat(be.getMessage()).contains("systemctl start failed");
                    assertThat(be.getMessage()).contains("(exit 1)");
                });
        verify(instanceRepository, never()).save(any(ServiceInstanceEntity.class));
    }

    @Test
    @DisplayName("start：实例不存在 → 抛 NOT_FOUND")
    void start_notFoundThrows() {
        when(instanceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(99L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("stop：exit 0 → STOPPED，online 保持原值")
    void stop_setsStoppedAndKeepsOnline() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        inst.setOnline(Boolean.TRUE);
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(sshService.execute(2L, "systemctl stop deploy-demo.service"))
                .thenReturn(CommandResult.ok("ok", 0));

        ServiceInstanceVO vo = service.stop(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("STOPPED");
        assertThat(inst.getOnline()).isTrue();
        verify(instanceRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).isSameAs(inst);
        assertThat(vo.getStatus()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("restart：exit 0 → RUNNING/online")
    void restart_setsRunningAndOnline() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        inst.setOnline(Boolean.FALSE);
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(sshService.execute(2L, "systemctl restart deploy-demo.service"))
                .thenReturn(CommandResult.ok("ok", 0));

        ServiceInstanceVO vo = service.restart(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("RUNNING");
        assertThat(inst.getOnline()).isTrue();
        verify(instanceRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).isSameAs(inst);
        assertThat(vo.getStatus()).isEqualTo("RUNNING");
    }

    // ---------- NGINX 模式 ----------

    @Test
    @DisplayName("NGINX start：软链指向该版本 extract + nginx -t && reload → RUNNING")
    void nginxStart_symlinkAndReload() {
        ServiceInstanceEntity inst = nginxInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("site")));
        String cmd = "ln -sfn /opt/deploy/site/10/extract /opt/deploy/site/current && nginx -t && nginx -s reload";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        ServiceInstanceVO vo = service.start(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("RUNNING");
        assertThat(inst.getOnline()).isTrue();
        assertThat(vo.getMode()).isEqualTo("NGINX");
    }

    @Test
    @DisplayName("NGINX restart：同样软链+reload → RUNNING")
    void nginxRestart_symlinkAndReload() {
        ServiceInstanceEntity inst = nginxInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("site")));
        String cmd = "ln -sfn /opt/deploy/site/10/extract /opt/deploy/site/current && nginx -t && nginx -s reload";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        service.restart(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("NGINX stop：删版本软链 + reload → STOPPED，online 保持原值")
    void nginxStop_removesSymlinkAndReloads() {
        ServiceInstanceEntity inst = nginxInstance();
        inst.setOnline(Boolean.TRUE);
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("site")));
        String cmd = "rm -f /opt/deploy/site/current && nginx -t && nginx -s reload";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        ServiceInstanceVO vo = service.stop(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("STOPPED");
        assertThat(inst.getOnline()).isTrue();
        assertThat(vo.getStatus()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("NGINX streamLogs：无站点专属日志文件 → 抛 BAD_REQUEST")
    void nginxStreamLogs_throwsBadRequest() {
        ServiceInstanceEntity inst = nginxInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));

        assertThatThrownBy(() -> service.streamLogs(5L, OPS, s -> {
        }, i -> {
        }))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    // ---------- DOCKER 模式 ----------

    @Test
    @DisplayName("DOCKER start：compose up -d → RUNNING")
    void dockerStart_upDetached() {
        ServiceInstanceEntity inst = dockerInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        String cmd = "docker-compose -f /opt/deploy/svc/10/docker-compose.yml up -d";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        ServiceInstanceVO vo = service.start(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("RUNNING");
        assertThat(vo.getMode()).isEqualTo("DOCKER");
    }

    @Test
    @DisplayName("DOCKER stop：compose down → STOPPED")
    void dockerStop_down() {
        ServiceInstanceEntity inst = dockerInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        String cmd = "docker-compose -f /opt/deploy/svc/10/docker-compose.yml down";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        service.stop(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("DOCKER restart：compose restart → RUNNING")
    void dockerRestart_composeRestart() {
        ServiceInstanceEntity inst = dockerInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        String cmd = "docker-compose -f /opt/deploy/svc/10/docker-compose.yml restart";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        service.restart(5L, OPS);

        assertThat(inst.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("DOCKER streamLogs：compose logs -f 命令透传 + 返回 handle")
    void dockerStreamLogs_composeLogsF() {
        ServiceInstanceEntity inst = dockerInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        SshExecutor.StreamHandle handle = new SshExecutor.StreamHandle() {
            @Override
            public void cancel() {
            }
        };
        String cmd = "docker-compose -f /opt/deploy/svc/10/docker-compose.yml logs -f --no-color";
        when(sshService.stream(eq(2L), eq(cmd), any(Consumer.class), any(Consumer.class))).thenReturn(handle);

        SshExecutor.StreamHandle result = service.streamLogs(5L, OPS, s -> {
        }, i -> {
        });

        assertThat(result).isSameAs(handle);
        verify(sshService).stream(eq(2L), eq(cmd), any(Consumer.class), any(Consumer.class));
    }

    // ---------- get / list ----------

    @Test
    @DisplayName("get：存在 → VO（含 projectName 查库）")
    void get_existingReturnsVo() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo")));

        ServiceInstanceVO vo = service.get(5L, OPS);

        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getProjectName()).isEqualTo("demo");
        assertThat(vo.getUnitName()).isEqualTo("deploy-demo.service");
    }

    @Test
    @DisplayName("get：不存在 → 抛 NOT_FOUND")
    void get_notFoundThrows() {
        when(instanceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("list：仅 projectId → findByProjectIdOrderByDeployedAtDesc")
    void list_byProjectId() {
        // 显式返回 null = 全量可见（Mockito 对 List 返回类型默认是空列表，会触发"无可见项目"短路）
        when(accessService.visibleProjectIds(OPS)).thenReturn(null);
        when(instanceRepository.findByProjectIdOrderByDeployedAtDesc(1L))
                .thenReturn(List.of(instance(5L, "demo-2-10")));

        List<ServiceInstanceVO> vos = service.list(1L, null, null, OPS);

        assertThat(vos).hasSize(1);
        verify(instanceRepository).findByProjectIdOrderByDeployedAtDesc(1L);
    }

    @Test
    @DisplayName("list：仅 serverId → findByServerIdOrderByDeployedAtDesc")
    void list_byServerId() {
        when(accessService.visibleProjectIds(OPS)).thenReturn(null);
        when(instanceRepository.findByServerIdOrderByDeployedAtDesc(2L))
                .thenReturn(List.of(instance(5L, "demo-2-10")));

        List<ServiceInstanceVO> vos = service.list(null, 2L, null, OPS);

        assertThat(vos).hasSize(1);
        verify(instanceRepository).findByServerIdOrderByDeployedAtDesc(2L);
    }

    @Test
    @DisplayName("list：project + server 都有 → findByProjectIdAndServerIdOrderByDeployedAtDesc")
    void list_byBoth() {
        when(accessService.visibleProjectIds(OPS)).thenReturn(null);
        when(instanceRepository.findByProjectIdAndServerIdOrderByDeployedAtDesc(1L, 2L))
                .thenReturn(List.of(instance(5L, "demo-2-10")));

        List<ServiceInstanceVO> vos = service.list(1L, 2L, null, OPS);

        assertThat(vos).hasSize(1);
        verify(instanceRepository).findByProjectIdAndServerIdOrderByDeployedAtDesc(1L, 2L);
    }

    @Test
    @DisplayName("list：都无 → findAllByOrderByDeployedAtDesc")
    void list_noFilter() {
        when(accessService.visibleProjectIds(OPS)).thenReturn(null);
        when(instanceRepository.findAllByOrderByDeployedAtDesc())
                .thenReturn(List.of(instance(5L, "demo-2-10"), instance(6L, "demo-3-11")));

        List<ServiceInstanceVO> vos = service.list(null, null, null, OPS);

        assertThat(vos).hasSize(2);
        verify(instanceRepository).findAllByOrderByDeployedAtDesc();
    }

    // ---------- streamLogs ----------

    @Test
    @DisplayName("streamLogs：journalctl 命令 + serverId + 回调透传 + 返回 handle")
    void streamLogs_buildsJournalCommandAndPassesThrough() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        SshExecutor.StreamHandle handle = new SshExecutor.StreamHandle() {
            @Override
            public void cancel() {
            }
        };
        when(sshService.stream(eq(2L), eq("journalctl -u deploy-demo.service -f --no-pager"),
                any(Consumer.class), any(Consumer.class))).thenReturn(handle);

        Consumer<String> lineConsumer = s -> {
        };
        Consumer<Integer> onComplete = i -> {
        };

        SshExecutor.StreamHandle result = service.streamLogs(5L, OPS, lineConsumer, onComplete);

        assertThat(result).isSameAs(handle);
        ArgumentCaptor<Consumer<String>> lineCaptor = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<Integer>> doneCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(sshService).stream(eq(2L),
                eq("journalctl -u deploy-demo.service -f --no-pager"),
                lineCaptor.capture(), doneCaptor.capture());
        assertThat(lineCaptor.getValue()).isSameAs(lineConsumer);
        assertThat(doneCaptor.getValue()).isSameAs(onComplete);
    }

    @Test
    @DisplayName("streamLogs：含特殊字符 unit 名 → 命令串被单引号包裹")
    void streamLogs_specialUnitNameQuoted() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        inst.setUnitName("bad name");
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        SshExecutor.StreamHandle handle = new SshExecutor.StreamHandle() {
            @Override
            public void cancel() {
            }
        };
        when(sshService.stream(eq(2L), eq("journalctl -u 'bad name' -f --no-pager"),
                any(Consumer.class), any(Consumer.class))).thenReturn(handle);

        service.streamLogs(5L, OPS, s -> {
        }, i -> {
        });

        verify(sshService).stream(eq(2L), eq("journalctl -u 'bad name' -f --no-pager"),
                any(Consumer.class), any(Consumer.class));
    }

    @Test
    @DisplayName("命令构造器：PROCESS 删除 = 停 unit（容错）+ disable + 删 unit 文件 + daemon-reload")
    void commandBuilders_processDelete() {
        String unit = "deploy-demo.service";
        assertThat(ServiceInstanceService.buildProcessDeleteCommand(unit))
                .isEqualTo("systemctl stop deploy-demo.service || true"
                        + "; systemctl disable deploy-demo.service || true"
                        + "; rm -f /etc/systemd/system/deploy-demo.service"
                        + "; systemctl daemon-reload");
    }

    @Test
    @DisplayName("命令构造器：NGINX 删除 = 删站点 conf + 软链 + 校验 reload（容错）")
    void commandBuilders_nginxDelete() {
        assertThat(ServiceInstanceService.buildNginxDeleteCommand("site"))
                .isEqualTo("rm -f /etc/nginx/conf.d/deploy-site.conf /opt/deploy/site/current && (nginx -t && nginx -s reload || true)");
    }

    // ---------- delete ----------

    @Test
    @DisplayName("delete PROCESS 在线：清理 unit（SSH 成功）→ 删 DB 记录，不 save")
    void delete_processOnline_cleansUnitAndDeletesRecord() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        String cmd = "systemctl stop deploy-demo.service || true"
                + "; systemctl disable deploy-demo.service || true"
                + "; rm -f /etc/systemd/system/deploy-demo.service"
                + "; systemctl daemon-reload";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        service.delete(5L, OPS);

        verify(sshService).execute(2L, cmd);
        verify(instanceRepository).delete(inst);
        verify(instanceRepository, never()).save(any(ServiceInstanceEntity.class));
    }

    @Test
    @DisplayName("delete PROCESS 非在线：共享 unit 不动，仅删 DB 记录（不 SSH）")
    void delete_processOffline_onlyDeletesRecord() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        inst.setOnline(Boolean.FALSE);
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));

        service.delete(5L, OPS);

        verify(sshService, never()).execute(anyLong(), anyString());
        verify(instanceRepository).delete(inst);
    }

    @Test
    @DisplayName("delete NGINX 在线：删站点 conf + 软链 + reload → 删 DB 记录")
    void delete_nginxOnline_cleansSiteAndDeletesRecord() {
        ServiceInstanceEntity inst = nginxInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("site")));
        String cmd = "rm -f /etc/nginx/conf.d/deploy-site.conf /opt/deploy/site/current && (nginx -t && nginx -s reload || true)";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        service.delete(5L, OPS);

        verify(sshService).execute(2L, cmd);
        verify(instanceRepository).delete(inst);
    }

    @Test
    @DisplayName("delete NGINX 非在线：站点共享资源不动，仅删 DB 记录")
    void delete_nginxOffline_onlyDeletesRecord() {
        ServiceInstanceEntity inst = nginxInstance();
        inst.setOnline(Boolean.FALSE);
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));

        service.delete(5L, OPS);

        verify(sshService, never()).execute(anyLong(), anyString());
        verify(instanceRepository).delete(inst);
    }

    @Test
    @DisplayName("delete DOCKER：compose 按 workdir 隔离，在线与否都 compose down")
    void delete_docker_downRegardlessOfOnline() {
        ServiceInstanceEntity inst = dockerInstance();
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        String cmd = "docker-compose -f /opt/deploy/svc/10/docker-compose.yml down";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.ok("ok", 0));

        service.delete(5L, OPS);

        verify(sshService).execute(2L, cmd);
        verify(instanceRepository).delete(inst);
    }

    @Test
    @DisplayName("delete：SSH 非 0 退出 → 抛 SSH_ERROR，DB 记录不删")
    void delete_sshFailure_throwsAndKeepsRecord() {
        ServiceInstanceEntity inst = instance(5L, "demo-2-10");
        when(instanceRepository.findById(5L)).thenReturn(Optional.of(inst));
        String cmd = "systemctl stop deploy-demo.service || true"
                + "; systemctl disable deploy-demo.service || true"
                + "; rm -f /etc/systemd/system/deploy-demo.service"
                + "; systemctl daemon-reload";
        when(sshService.execute(2L, cmd)).thenReturn(CommandResult.fail(3, "", "boom"));

        assertThatThrownBy(() -> service.delete(5L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.SSH_ERROR);
                    assertThat(be.getMessage()).contains("delete systemd unit failed");
                    assertThat(be.getMessage()).contains("(exit 3)");
                });
        verify(instanceRepository, never()).delete(any(ServiceInstanceEntity.class));
    }

    @Test
    @DisplayName("delete：实例不存在 → 抛 NOT_FOUND")
    void delete_notFoundThrows() {
        when(instanceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- 既有：onSuccess / onRolledBack ----------

    @Test
    @DisplayName("onSuccess：新实例上线（RUNNING/online），旧在线实例下线（STOPPED/offline）")
    void onSuccess_offlinesOldInstance() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo")));
        ServiceInstanceEntity old = instance(7L, "demo-2-7");
        old.setOnline(Boolean.TRUE);
        old.setStatus("RUNNING");
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L))
                .thenReturn(Optional.of(old));
        when(instanceRepository.findByName("demo-2-10")).thenReturn(Optional.empty());

        service.onSuccess(deployment(10L, null));

        verify(instanceRepository, times(2)).save(saveCaptor.capture());
        ServiceInstanceEntity fresh = saveCaptor.getAllValues().get(0);
        assertThat(fresh.getName()).isEqualTo("demo-2-10");
        assertThat(fresh.getStatus()).isEqualTo("RUNNING");
        assertThat(fresh.getOnline()).isTrue();
        assertThat(fresh.getUnitName()).isEqualTo("deploy-demo.service");
        assertThat(fresh.getDeploymentId()).isEqualTo(1L);
        assertThat(fresh.getPort()).isEqualTo(8080);
        assertThat(fresh.getHealthPath()).isEqualTo("/health");
        assertThat(saveCaptor.getAllValues().get(1)).isSameAs(old);
        assertThat(old.getOnline()).isFalse();
        assertThat(old.getStatus()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("onRolledBack：恢复指向 oldVersionId 的旧实例为在线")
    void onRolledBack_restoresOldInstance() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo")));
        ServiceInstanceEntity old = instance(7L, "demo-2-7");
        old.setOnline(Boolean.FALSE);
        old.setStatus("STOPPED");
        when(instanceRepository.findByName("demo-2-7")).thenReturn(Optional.of(old));

        service.onRolledBack(deployment(10L, 7L));

        assertThat(old.getOnline()).isTrue();
        assertThat(old.getStatus()).isEqualTo("RUNNING");
        assertThat(old.getUpdatedAt()).isNotNull();
        verify(instanceRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).isSameAs(old);
    }

    @Test
    @DisplayName("onSuccess：首次部署无旧在线实例 → 仅 save 新实例")
    void onSuccess_firstDeploymentNoOldInstance() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo")));
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L))
                .thenReturn(Optional.empty());
        when(instanceRepository.findByName("demo-2-10")).thenReturn(Optional.empty());

        service.onSuccess(deployment(10L, null));

        verify(instanceRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getName()).isEqualTo("demo-2-10");
        assertThat(saveCaptor.getValue().getOnline()).isTrue();
    }

    // ---------- 辅助 ----------

    private static ProjectEntity project(String name) {
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setName(name);
        return p;
    }

    private static ServiceInstanceEntity instance(Long id, String name) {
        ServiceInstanceEntity e = new ServiceInstanceEntity();
        e.setId(id);
        e.setName(name);
        e.setProjectId(1L);
        e.setServerId(2L);
        e.setUnitName("deploy-demo.service");
        e.setPort(8080);
        e.setHealthPath("/health");
        e.setStatus("RUNNING");
        e.setOnline(Boolean.TRUE);
        return e;
    }

    private static ServiceInstanceEntity nginxInstance() {
        ServiceInstanceEntity e = instance(5L, "site-2-10");
        e.setMode("NGINX");
        e.setUnitName(null);
        e.setWorkdir("/opt/deploy/site/10");
        e.setPort(80);
        return e;
    }

    private static ServiceInstanceEntity dockerInstance() {
        ServiceInstanceEntity e = instance(5L, "svc-2-10");
        e.setMode("DOCKER");
        e.setUnitName(null);
        e.setWorkdir("/opt/deploy/svc/10");
        e.setPort(null);
        return e;
    }

    private static DeploymentEntity deployment(long versionId, Long oldVersionId) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(1L);
        d.setProjectId(1L);
        d.setServerId(2L);
        d.setVersionId(versionId);
        d.setOldVersionId(oldVersionId);
        d.setPort(8080);
        d.setHealthPath("/health");
        d.setStartCmd("java -jar app.jar");
        d.setWorkdir("/opt/deploy/demo/" + versionId);
        return d;
    }
}
