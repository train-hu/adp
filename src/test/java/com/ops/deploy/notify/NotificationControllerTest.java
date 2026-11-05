package com.ops.deploy.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.GlobalExceptionHandler;
import com.ops.deploy.common.Result;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUserArgumentResolver;
import com.ops.deploy.security.JwtAuthFilter;
import com.ops.deploy.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NotificationController 单测：standalone MockMvc + mock NotificationService。
 *
 * <p>四个端点均无 @RequireRole（任何已登录角色可用），仅验证参数透传与 Result 包装。
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService service;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        NotificationController raw = new NotificationController(service);
        // 与 DeploymentControllerTest 同款模板：织入 RBAC 切面（本 controller 无 @RequireRole，仅保持环境一致）
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("requireRoleAspect", new com.ops.deploy.security.RequireRoleAspect());
        AnnotationAwareAspectJAutoProxyCreator registrar = new AnnotationAwareAspectJAutoProxyCreator();
        registrar.setBeanFactory(beanFactory);
        NotificationController proxied =
                (NotificationController) registrar.postProcessAfterInitialization(raw, "notificationController");
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

    private static final AuthUser OPS = new AuthUser(8L, "ops", Role.OPS.name());

    private NotificationVO vo(long id, long projectId, String level) {
        NotificationVO vo = new NotificationVO();
        vo.setId(id);
        vo.setLevel(level);
        vo.setTitle("t" + id);
        vo.setMessage("m" + id);
        vo.setProjectId(projectId);
        vo.setInstanceId(5L);
        vo.setCreatedAt(LocalDateTime.of(2026, 10, 28, 12, 0));
        return vo;
    }

    @Test
    void list_shouldReturn200WithVos() throws Exception {
        when(service.list(any(AuthUser.class))).thenReturn(List.of(vo(1L, 1L, "WARN")));
        mockMvc.perform(get("/api/notifications").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].level").value("WARN"))
                .andExpect(jsonPath("$.data[0].projectId").value(1));
    }

    @Test
    void unreadCount_shouldReturnCount() throws Exception {
        when(service.unreadCount(any(AuthUser.class))).thenReturn(3L);
        mockMvc.perform(get("/api/notifications/unread-count").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    void readAll_shouldReturnAffected() throws Exception {
        when(service.readAll(any(AuthUser.class))).thenReturn(7);
        mockMvc.perform(post("/api/notifications/read-all").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.affected").value(7));
        verify(service).readAll(OPS);
    }

    @Test
    void read_shouldReturn200AndPassId() throws Exception {
        mockMvc.perform(post("/api/notifications/42/read").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(service).read(42L, OPS);
    }
}
