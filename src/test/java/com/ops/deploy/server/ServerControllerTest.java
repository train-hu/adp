package com.ops.deploy.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.GlobalExceptionHandler;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUserArgumentResolver;
import com.ops.deploy.security.JwtAuthFilter;
import com.ops.deploy.security.ResourceAccessService;
import com.ops.deploy.security.ResourceGrantVO;
import com.ops.deploy.user.Role;
import com.ops.deploy.user.UserService;
import com.ops.deploy.user.UserVO;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ServerController 单测：standalone MockMvc + mock ServerService。
 *
 * <p>与改造前不同，此处额外启用 RBAC 切面（AnnotationAwareAspectJAutoProxyCreator 代理
 * controller）与 CurrentUserArgumentResolver，以验证 @RequireRole 的角色拒绝（低权限/无 token → 403）
 * 与 @CurrentUser 注入；mock 的 Service 方法为带用户上下文的 For 版本（listFor/viewFor/...），
 * 可见性隔离逻辑本身由 ResourceAccessService 负责（另测）。
 */
@ExtendWith(MockitoExtension.class)
class ServerControllerTest {

    @Mock
    private ServerService serverService;

    @Mock
    private ResourceAccessService accessService;

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    private static final AuthUser ADMIN = new AuthUser(9L, "admin", Role.ADMIN.name());
    private static final AuthUser OPS = new AuthUser(8L, "ops", Role.OPS.name());

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ServerController raw = new ServerController(serverService, accessService, userService);
        // 启用 RBAC 切面（standalone MockMvc 默认不启用 AOP）
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("requireRoleAspect", new com.ops.deploy.security.RequireRoleAspect());
        AnnotationAwareAspectJAutoProxyCreator registrar = new AnnotationAwareAspectJAutoProxyCreator();
        registrar.setBeanFactory(beanFactory);
        ServerController proxied =
                (ServerController) registrar.postProcessAfterInitialization(raw, "serverController");
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

    private static String createJson(String password) {
        return "{\"name\":\"web-01\",\"ip\":\"10.0.0.1\",\"sshPort\":22,"
                + "\"sshUser\":\"deploy\",\"authType\":\"PASSWORD\","
                + "\"password\":\"" + password + "\",\"envTag\":\"dev\"}";
    }

    private ServerVO vo(Long id) {
        ServerVO vo = new ServerVO();
        vo.setId(id);
        vo.setName("web-01");
        vo.setIp("10.0.0.1");
        vo.setSshPort(22);
        vo.setSshUser("deploy");
        vo.setAuthType("PASSWORD");
        vo.setEnvTag("dev");
        vo.setStatus("UNKNOWN");
        vo.setCreatedAt(LocalDateTime.now());
        return vo;
    }

    @Test
    void create_shouldReturn201AndNotLeakPassword() throws Exception {
        when(serverService.create(any(ServerCreateRequest.class), any())).thenReturn(vo(1L));

        MvcResult result = mockMvc.perform(post("/api/servers/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("TopSecret123"))
                        .with(user(OPS)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("web-01"))
                // 响应结构上不存在 password 字段
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 明文密码值绝不出现（authType 的值 "PASSWORD" 是认证方式，非凭证）
        assertThat(body).doesNotContain("TopSecret123");
    }

    @Test
    void create_requiresOpsRole_noToken_should401() throws Exception {
        // create 现为 @RequireRole(OPS)：无 token 时 CurrentUserArgumentResolver 抛 401（未认证）
        mockMvc.perform(post("/api/servers/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("TopSecret123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void create_missingRequiredField_returns400() throws Exception {
        // 缺少必填 name（@NotBlank）：应返回 HTTP 400 + body code=400，而不是 200/500
        String body = createJson("TopSecret123")
                .replace("\"name\":\"web-01\",", "");

        mockMvc.perform(post("/api/servers/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(OPS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("name")));
    }

    @Test
    void list_shouldReturnArray() throws Exception {
        when(serverService.listFor(any(AuthUser.class))).thenReturn(List.of(vo(1L), vo(2L)));

        mockMvc.perform(get("/api/servers/")
                        .with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("web-01"));
    }

    @Test
    void getById_notFound_shouldReturn404() throws Exception {
        when(serverService.viewFor(anyLong(), any(AuthUser.class)))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "server not found"));

        mockMvc.perform(get("/api/servers/99")
                        .with(user(OPS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void test_shouldReturnReachableField() throws Exception {
        ServerTestResultVO res = new ServerTestResultVO();
        res.setReachable(true);
        res.setLatencyMs(3L);
        res.setStatus("ONLINE");
        when(serverService.testConnectionFor(eq(1L), any(AuthUser.class))).thenReturn(res);

        mockMvc.perform(post("/api/servers/1/test")
                        .with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reachable").value(true))
                .andExpect(jsonPath("$.data.status").value("ONLINE"));
    }

    @Test
    void update_shouldReturn200() throws Exception {
        when(serverService.updateFor(anyLong(), any(ServerUpdateRequest.class), any(AuthUser.class)))
                .thenReturn(vo(1L));

        mockMvc.perform(put("/api/servers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"web-01b\"}")
                        .with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("web-01"));
    }

    @Test
    void delete_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/servers/1")
                        .with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- 资源分配端点（仅 ADMIN） ----------

    @Test
    void listGrants_adminShouldReturnArray() throws Exception {
        ResourceGrantVO g = new ResourceGrantVO();
        g.setResourceType("SERVER");
        g.setResourceId(1L);
        g.setUserId(8L);
        g.setUsername("ops");
        g.setUserRole(Role.OPS.name());
        g.setGrantedAt(LocalDateTime.now());
        when(accessService.listServerGrants(1L)).thenReturn(List.of(g));

        mockMvc.perform(get("/api/servers/1/grants")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("ops"));
    }

    @Test
    void listGrants_opsShould403() throws Exception {
        // grants 端点 @RequireRole(ADMIN)：OPS 低权限 → 403
        mockMvc.perform(get("/api/servers/1/grants")
                        .with(user(OPS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void grantCandidates_adminShouldReturnUsers() throws Exception {
        UserVO u = new UserVO();
        u.setId(8L);
        u.setUsername("ops");
        u.setRole(Role.OPS.name());
        u.setEnabled(true);
        when(userService.list()).thenReturn(List.of(u));

        mockMvc.perform(get("/api/servers/grant-candidates")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("ops"))
                // 不回传 projectIds
                .andExpect(jsonPath("$.data[0].projectIds").doesNotExist());
    }

    @Test
    void addGrant_adminShouldReturn200() throws Exception {
        ResourceGrantVO g = new ResourceGrantVO();
        g.setResourceType("SERVER");
        g.setResourceId(1L);
        g.setUserId(8L);
        g.setUsername("ops");
        g.setUserRole(Role.OPS.name());
        g.setGrantedAt(LocalDateTime.now());
        when(accessService.addServerGrant(anyLong(), anyLong(), any(AuthUser.class))).thenReturn(g);

        mockMvc.perform(post("/api/servers/1/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":8}")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(8))
                .andExpect(jsonPath("$.data.username").value("ops"));
    }

    @Test
    void removeGrant_adminShouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/servers/1/grants/8")
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void removeGrant_opsShould403() throws Exception {
        mockMvc.perform(delete("/api/servers/1/grants/8")
                        .with(user(OPS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
