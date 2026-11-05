package com.ops.deploy.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.GlobalExceptionHandler;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUserArgumentResolver;
import com.ops.deploy.security.JwtAuthFilter;
import com.ops.deploy.ssh.SshExecutor;
import com.ops.deploy.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ServiceInstanceController 单测：standalone MockMvc + mock ServiceInstanceService。
 *
 * <p>启用 RBAC 切面（同 DeploymentControllerTest 模板）验证 @RequireRole：
 * start/stop/restart 需 OPS；list/get 无角色限制。
 * logs 端点用同步 Executor（Runnable::run）直接调方法验证 SseEmitter 与 streamLogs 透传。
 */
@ExtendWith(MockitoExtension.class)
class ServiceInstanceControllerTest {

    @Mock
    private ServiceInstanceService service;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        // 同步 Executor：log-sse 提交的任务立即在当前线程执行（避免 async 抖动）
        ServiceInstanceController raw = new ServiceInstanceController(service, Runnable::run);
        // 启用 RBAC 切面（standalone MockMvc 默认不启用 AOP）：
        // 将真实 RequireRoleAspect 注册进 BeanFactory，由 AutoProxyCreator 对 controller 织入
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("requireRoleAspect", new com.ops.deploy.security.RequireRoleAspect());
        AnnotationAwareAspectJAutoProxyCreator registrar = new AnnotationAwareAspectJAutoProxyCreator();
        registrar.setBeanFactory(beanFactory);
        ServiceInstanceController proxied =
                (ServiceInstanceController) registrar.postProcessAfterInitialization(raw, "serviceInstanceController");
        Validator validator = new LocalValidatorFactoryBean();
        mockMvc = MockMvcBuilders.standaloneSetup(proxied)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .build();
    }

    /** 把已认证用户放入 request attribute（模拟 JwtAuthFilter 注入）。 */
    private static RequestPostProcessor user(AuthUser u) {
        return request -> {
            request.setAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE, u);
            return request;
        };
    }

    private static final AuthUser ADMIN = new AuthUser(9L, "admin", Role.ADMIN.name());
    private static final AuthUser OPS = new AuthUser(8L, "ops", Role.OPS.name());

    private static ServiceInstanceVO vo(Long id, String status) {
        ServiceInstanceVO vo = new ServiceInstanceVO();
        vo.setId(id);
        vo.setName("demo-2-10");
        vo.setProjectId(1L);
        vo.setProjectName("demo");
        vo.setServerId(2L);
        vo.setDeploymentId(1L);
        vo.setUnitName("deploy-demo.service");
        vo.setPort(8080);
        vo.setHealthPath("/health");
        vo.setStatus(status);
        vo.setOnline(Boolean.TRUE);
        return vo;
    }

    // ---------- list / get ----------

    @Test
    void list_shouldReturnArray() throws Exception {
        when(service.list(any(), any(), any(), any(AuthUser.class))).thenReturn(java.util.List.of(vo(1L, "RUNNING"), vo(2L, "STOPPED")));

        mockMvc.perform(get("/api/service-instances").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[1].status").value("STOPPED"));
    }

    @Test
    void get_shouldReturn200() throws Exception {
        when(service.get(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "RUNNING"));

        mockMvc.perform(get("/api/service-instances/1").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    // ---------- start / stop / restart RBAC ----------

    @Test
    void start_adminShouldReturn200() throws Exception {
        when(service.start(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "RUNNING"));

        mockMvc.perform(post("/api/service-instances/1/start").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        verify(service).start(eq(1L), any(AuthUser.class));
    }

    @Test
    void start_opsShouldReturn200() throws Exception {
        when(service.start(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "RUNNING"));

        mockMvc.perform(post("/api/service-instances/1/start").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        verify(service).start(eq(1L), any(AuthUser.class));
    }

    @Test
    void stop_adminShouldReturn200() throws Exception {
        when(service.stop(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "STOPPED"));

        mockMvc.perform(post("/api/service-instances/1/stop").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("STOPPED"));
        verify(service).stop(eq(1L), any(AuthUser.class));
    }

    @Test
    void stop_opsShouldReturn200() throws Exception {
        when(service.stop(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "STOPPED"));

        mockMvc.perform(post("/api/service-instances/1/stop").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("STOPPED"));
        verify(service).stop(eq(1L), any(AuthUser.class));
    }

    @Test
    void restart_adminShouldReturn200() throws Exception {
        when(service.restart(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "RUNNING"));

        mockMvc.perform(post("/api/service-instances/1/restart").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        verify(service).restart(eq(1L), any(AuthUser.class));
    }

    @Test
    void restart_opsShouldReturn200() throws Exception {
        when(service.restart(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "RUNNING"));

        mockMvc.perform(post("/api/service-instances/1/restart").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        verify(service).restart(eq(1L), any(AuthUser.class));
    }

    // ---------- logs（SSE） ----------

    @Test
    void logs_returnsSseEmitterAndInvokesStreamLogs() {
        // 同步 Executor（Runnable::run）→ streamLogs 在调用线程立即执行，无 async 抖动
        SshExecutor.StreamHandle handle = new SshExecutor.StreamHandle() {
            @Override
            public void cancel() {
            }
        };
        // 签名变更：streamLogs(id, user, lineConsumer, onComplete)，user 位于 id 之后
        when(service.streamLogs(eq(1L), any(AuthUser.class), any(), any())).thenReturn(handle);

        ServiceInstanceController controller = new ServiceInstanceController(service, Runnable::run);
        SseEmitter emitter = controller.logs(1L, OPS);

        assertThat(emitter).isNotNull();
        verify(service).streamLogs(eq(1L), any(AuthUser.class), any(), any());
    }

    /**
     * SSE 回调接线核心：客户端断开（emitter 已完成）后再推日志 → line 回调捕获 send 异常 → 触发 cancel。
     * 这是"客户端断开后停止远端 journalctl -f"的唯一主动路径，必须被测试锁定。
     */
    @Test
    void logs_lineSendFailsAfterDisconnect_triggersCancel() {
        ServiceInstanceController controller = new ServiceInstanceController(service, Runnable::run);
        SshExecutor.StreamHandle mockHandle = mock(SshExecutor.StreamHandle.class);
        when(service.streamLogs(eq(1L), any(AuthUser.class), any(), any())).thenReturn(mockHandle);
        SseEmitter emitter = controller.logs(1L, OPS);
        emitter.complete(); // 模拟客户端断开/响应完成

        // 捕获 line 回调：对已完成的 emitter 再 send 必抛异常，回调应捕获并触发 cancel
        ArgumentCaptor<Consumer<String>> lineCaptor = ArgumentCaptor.captor();
        verify(service).streamLogs(eq(1L), any(AuthUser.class), lineCaptor.capture(), any());
        lineCaptor.getValue().accept("boom");

        verify(mockHandle).cancel();
    }

    /**
     * SSE 回调接线核心：远端命令结束（onComplete）→ emitter.complete()。
     */
    @Test
    void logs_onCompleteCompletesEmitter() {
        ServiceInstanceController controller = new ServiceInstanceController(service, Runnable::run);
        SseEmitter emitter = controller.logs(1L, OPS);

        ArgumentCaptor<Consumer<Integer>> completeCaptor = ArgumentCaptor.captor();
        verify(service).streamLogs(eq(1L), any(AuthUser.class), any(), completeCaptor.capture());

        completeCaptor.getValue().accept(0); // 模拟远端 journalctl 退出

        assertThatThrownBy(() -> emitter.send(SseEmitter.event().data("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already completed");
    }
}
