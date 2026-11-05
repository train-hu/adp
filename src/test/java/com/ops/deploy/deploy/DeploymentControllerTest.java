package com.ops.deploy.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.GlobalExceptionHandler;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUserArgumentResolver;
import com.ops.deploy.security.JwtAuthFilter;
import com.ops.deploy.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DeploymentController 单测：standalone MockMvc + mock DeploymentService。
 *
 * <p>启用 RBAC 切面（同 ProjectControllerTest 模板）验证 @RequireRole：
 * create/cancel 需 OPS；list/get 无角色限制（任何已登录角色可读）。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentControllerTest {

    @Mock
    private DeploymentService deploymentService;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        DeploymentController raw = new DeploymentController(deploymentService);
        // 启用 RBAC 切面（standalone MockMvc 默认不启用 AOP）：
        // 将真实 RequireRoleAspect 注册进 BeanFactory，由 AutoProxyCreator 对 controller 织入
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("requireRoleAspect", new com.ops.deploy.security.RequireRoleAspect());
        AnnotationAwareAspectJAutoProxyCreator registrar = new AnnotationAwareAspectJAutoProxyCreator();
        registrar.setBeanFactory(beanFactory);
        DeploymentController proxied =
                (DeploymentController) registrar.postProcessAfterInitialization(raw, "deploymentController");
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

    private static String createJson() {
        return "{\"versionId\":10,\"serverId\":2,\"startCmd\":\"java -jar app.jar\"}";
    }

    private static DeploymentVO vo(Long id, String status) {
        DeploymentVO vo = new DeploymentVO();
        vo.setId(id);
        vo.setProjectId(1L);
        vo.setVersionId(10L);
        vo.setServerId(2L);
        vo.setStatus(status);
        vo.setMode("PROCESS");
        vo.setStartCmd("java -jar app.jar");
        vo.setPort(8080);
        vo.setHealthPath("/health");
        vo.setWorkdir("/opt/deploy/demo/10");
        vo.setOperator(8L);
        vo.setCreatedAt(LocalDateTime.now());
        return vo;
    }

    @Test
    void create_admin_shouldReturn201() throws Exception {
        when(deploymentService.create(any(DeploymentCreateRequest.class), any(AuthUser.class)))
                .thenReturn(vo(1L, "PENDING"));

        mockMvc.perform(post("/api/deployments")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(deploymentService).create(any(DeploymentCreateRequest.class), any(AuthUser.class));
    }

    @Test
    void list_shouldReturnArray() throws Exception {
        when(deploymentService.list(any(AuthUser.class))).thenReturn(List.of(vo(1L, "SUCCESS"), vo(2L, "PENDING")));

        mockMvc.perform(get("/api/deployments").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[1].status").value("PENDING"));
    }

    @Test
    void get_notFound_shouldReturn404() throws Exception {
        when(deploymentService.get(eq(99L), any(AuthUser.class))).thenThrow(
                new BizException(ErrorCode.NOT_FOUND, "deployment not found"));

        mockMvc.perform(get("/api/deployments/99").with(user(OPS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void rollback_ops_shouldReturn200() throws Exception {
        when(deploymentService.rollback(eq(1L), any(AuthUser.class))).thenReturn(vo(2L, "PENDING"));

        mockMvc.perform(post("/api/deployments/1/rollback").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(2));
        verify(deploymentService).rollback(eq(1L), any(AuthUser.class));
    }

    @Test
    void cancel_ops_shouldReturn200() throws Exception {
        when(deploymentService.cancel(eq(1L), any(AuthUser.class))).thenReturn(vo(1L, "CANCELLED"));

        mockMvc.perform(post("/api/deployments/1/cancel").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        verify(deploymentService).cancel(eq(1L), any(AuthUser.class));
    }
}
