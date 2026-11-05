package com.ops.deploy.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.GlobalExceptionHandler;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.JwtAuthFilter;
import com.ops.deploy.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebhookController 单测：standalone MockMvc + RBAC 切面（@RequireRole(ADMIN)）+ GlobalExceptionHandler。
 *
 * <p>覆盖：CRUD 路由与 Result 包装、type 非法 400、配置不存在 404、删除成功、
 * 非 ADMIN 角色（OPS）全部端点 403。
 */
@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    @Mock
    private WebhookService service;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        WebhookController raw = new WebhookController(service);
        // 启用 RBAC 切面（standalone MockMvc 默认不启用 AOP）：
        // 将真实 RequireRoleAspect 注册进 BeanFactory，由 AutoProxyCreator 对 controller 织入
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("requireRoleAspect", new com.ops.deploy.security.RequireRoleAspect());
        AnnotationAwareAspectJAutoProxyCreator registrar = new AnnotationAwareAspectJAutoProxyCreator();
        registrar.setBeanFactory(beanFactory);
        WebhookController proxied =
                (WebhookController) registrar.postProcessAfterInitialization(raw, "webhookController");
        Validator validator = new LocalValidatorFactoryBean();
        mockMvc = MockMvcBuilders.standaloneSetup(proxied)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
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

    private static WebhookVO vo(Long id, String type) {
        WebhookVO v = new WebhookVO();
        v.setId(id);
        v.setName("bot-" + id);
        v.setType(type);
        v.setUrl("https://example.com/hook/" + id);
        v.setEnabled(true);
        v.setCreatedAt(LocalDateTime.of(2026, 11, 1, 10, 0));
        v.setUpdatedAt(null);
        return v;
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("GET /api/webhooks：ADMIN 返回全部配置列表")
    void list_admin_returnsAll() throws Exception {
        when(service.list()).thenReturn(List.of(vo(1L, "FEISHU"), vo(2L, "DINGTALK")));

        mockMvc.perform(get("/api/webhooks").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("bot-1"))
                .andExpect(jsonPath("$.data[1].type").value("DINGTALK"));
    }

    // ---------- 创建 ----------

    @Test
    @DisplayName("POST /api/webhooks：ADMIN 创建成功返回 201 + data")
    void create_admin_returns201() throws Exception {
        when(service.create(any(WebhookCreateRequest.class))).thenReturn(vo(3L, "FEISHU"));

        mockMvc.perform(post("/api/webhooks")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ops-bot\",\"type\":\"feishu\",\"url\":\"https://example.com/hook\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.type").value("FEISHU"));
        // type 大小写不敏感：按大写存储
        verify(service).create(any(WebhookCreateRequest.class));
    }

    @Test
    @DisplayName("POST /api/webhooks：type 非法 → 400")
    void create_invalidType_returns400() throws Exception {
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.BAD_REQUEST, "invalid webhook type: WECHAT"))
                .when(service).create(any(WebhookCreateRequest.class));

        mockMvc.perform(post("/api/webhooks")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"WECHAT\",\"url\":\"https://example.com/hook\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/webhooks：name 缺失（@Valid 校验）→ 400")
    void create_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/webhooks")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FEISHU\",\"url\":\"https://example.com/hook\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verify(service, never()).create(any());
    }

    // ---------- 更新 ----------

    @Test
    @DisplayName("PUT /api/webhooks/{id}：ADMIN 部分更新成功（type 大小写归一化、enabled 透传）")
    void update_admin_returnsOk() throws Exception {
        when(service.update(eq(1L), any(WebhookUpdateRequest.class))).thenReturn(vo(1L, "DINGTALK"));

        mockMvc.perform(put("/api/webhooks/1")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"type\":\"dingtalk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.type").value("DINGTALK"));

        // 请求透传：enabled=false、type=dingtalk（service 内部归一化）
        org.mockito.ArgumentCaptor<WebhookUpdateRequest> req =
                org.mockito.ArgumentCaptor.forClass(WebhookUpdateRequest.class);
        verify(service).update(eq(1L), req.capture());
        assertThat(req.getValue().getEnabled()).isFalse();
        assertThat(req.getValue().getType()).isEqualTo("dingtalk");
        assertThat(req.getValue().getUrl()).isNull();
    }

    @Test
    @DisplayName("PUT /api/webhooks/{id}：配置不存在 → 404")
    void update_notFound_returns404() throws Exception {
        doThrow(new BizException(ErrorCode.NOT_FOUND, "webhook config not found"))
                .when(service).update(eq(99L), any(WebhookUpdateRequest.class));

        mockMvc.perform(put("/api/webhooks/99")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("DELETE /api/webhooks/{id}：ADMIN 删除成功")
    void delete_admin_returnsOk() throws Exception {
        mockMvc.perform(delete("/api/webhooks/1").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(service).delete(1L);
    }

    @Test
    @DisplayName("DELETE /api/webhooks/{id}：配置不存在 → 404")
    void delete_notFound_returns404() throws Exception {
        doThrow(new BizException(ErrorCode.NOT_FOUND, "webhook config not found"))
                .when(service).delete(99L);

        mockMvc.perform(delete("/api/webhooks/99").with(user(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ---------- RBAC：非 ADMIN 拒绝 ----------

    @Test
    @DisplayName("RBAC：OPS 调 GET/POST/PUT/DELETE 均 403")
    void nonAdmin_allEndpoints_forbidden() throws Exception {
        mockMvc.perform(get("/api/webhooks").with(user(OPS)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/webhooks").with(user(OPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"FEISHU\",\"url\":\"https://e.com/hook\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/webhooks/1").with(user(OPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/webhooks/1").with(user(OPS)))
                .andExpect(status().isForbidden());
        // 拒绝时不应触达 service
        verify(service, never()).list();
        verify(service, never()).create(any());
        verify(service, never()).update(any(), any());
        verify(service, never()).delete(any());
    }
}
