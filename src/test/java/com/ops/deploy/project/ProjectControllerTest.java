package com.ops.deploy.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProjectController 单测：standalone MockMvc + mock ProjectService/PackageStorage。
 *
 * <p>与 ServerControllerTest 不同，此处额外启用 RBAC 切面（AnnotationAwareAspectJAutoProxyCreator
 * 代理 controller），以验证 @RequireRole 的角色拒绝（低权限 403 / 无 token 403）。
 * 注册 CurrentUserArgumentResolver 使 @CurrentUser AuthUser 从 request attribute 解析。
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private PackageStorage packageStorage;

    @Mock
    private ProjectConfigFileService configFileService;
    // 新增：项目访问控制 mock（controller 依赖），void 断言方法默认 no-op，鉴权天然放行
    @Mock
    private ProjectAccessService accessService;
    // 新增：用户服务 mock（controller 依赖，成员管理接口使用）
    @Mock
    private com.ops.deploy.user.UserService userService;
    // 新增：分片上传服务 mock（controller 依赖，upload-* 端点使用）
    @Mock
    private ChunkUploadService chunkUploadService;
    // 新增：配置文件生成器 mock（controller 依赖，包内文件树/单文件读取端点使用）
    @Mock
    private com.ops.deploy.deploy.ConfigFileGenerator configFileGenerator;
    // 新增：子服务解析 mock（controller 依赖，upload/config-files 端点解析 serviceId 用）
    @Mock
    private ProjectSubServiceManager subServiceManager;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        ProjectController raw = new ProjectController(projectService, packageStorage, configFileService, accessService, userService, chunkUploadService, configFileGenerator, subServiceManager);
        // 启用 RBAC 切面（standalone MockMvc 默认不启用 AOP）：
        // 将真实 RequireRoleAspect 注册进 BeanFactory，由 AutoProxyCreator 对 controller 织入
        // （代理在 postProcessAfterInitialization 阶段创建）
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("requireRoleAspect", new com.ops.deploy.security.RequireRoleAspect());
        AnnotationAwareAspectJAutoProxyCreator registrar = new AnnotationAwareAspectJAutoProxyCreator();
        registrar.setBeanFactory(beanFactory);
        ProjectController proxied =
                (ProjectController) registrar.postProcessAfterInitialization(raw, "projectController");
        Validator validator = new LocalValidatorFactoryBean();
        mockMvc = MockMvcBuilders.standaloneSetup(proxied)
                .setControllerAdvice(new GlobalExceptionHandler())
                // 下载端点返回 InputStreamResource，需 ResourceHttpMessageConverter 才能按 octet-stream 输出
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper),
                        new ResourceHttpMessageConverter())
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
        return "{\"name\":\"demo\",\"description\":\"desc\"}";
    }

    private ProjectVO projectVo(Long id, String name, String type) {
        ProjectVO vo = new ProjectVO();
        vo.setId(id);
        vo.setName(name);
        vo.setDescription("desc");
        vo.setDetectedType(type);
        vo.setDefaultStartCmd("java -jar app.jar");
        vo.setDefaultPort(8080);
        vo.setDefaultHealthPath("/health");
        vo.setCreatedAt(LocalDateTime.now());
        return vo;
    }

    // ---------- create / list / get / update / delete ----------

    @Test
    void create_admin_shouldReturn201() throws Exception {
        when(projectService.create(any(ProjectCreateRequest.class), any(AuthUser.class)))
                .thenReturn(projectVo(1L, "demo", null));

        mockMvc.perform(post("/api/projects/")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("demo"));
    }

    @Test
    void create_missingName_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/projects/")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"no-name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void list_shouldReturnArray_forLoggedInUser() throws Exception {
        when(projectService.list(any(AuthUser.class))).thenReturn(List.of(projectVo(1L, "demo", "NODE")));

        // list 需登录（@CurrentUser 无 token → 401），无角色限制（任何已登录角色可见其成员项目）
        mockMvc.perform(get("/api/projects/").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("demo"));
    }

    @Test
    void get_notFound_shouldReturn404() throws Exception {
        org.mockito.Mockito.doThrow(new com.ops.deploy.common.BizException(
                com.ops.deploy.common.ErrorCode.NOT_FOUND, "project not found"))
                .when(projectService).get(eq(99L), any(AuthUser.class));

        mockMvc.perform(get("/api/projects/99").with(user(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void update_admin_shouldReturn200() throws Exception {
        when(projectService.update(eq(1L), any(ProjectUpdateRequest.class), any(AuthUser.class)))
                .thenReturn(projectVo(1L, "demo", "NODE"));

        mockMvc.perform(put("/api/projects/1")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"new\",\"detectedType\":\"NODE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.description").value("desc"));
    }

    @Test
    void delete_admin_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/projects/1").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- upload ----------

    @Test
    void upload_ops_shouldReturn200() throws Exception {
        PackageVersionVO vo = new PackageVersionVO();
        vo.setId(10L);
        vo.setProjectId(1L);
        vo.setFileName("app.zip");
        vo.setSha256("a".repeat(64));
        vo.setSizeBytes(5L);
        vo.setDetectedType("JAVA_MAVEN");
        when(projectService.uploadPackage(eq(1L), any(), any(), eq("v1"), any(), any(AuthUser.class))).thenReturn(vo);

        MockMultipartFile file = new MockMultipartFile("file", "app.zip", "application/zip",
                "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/projects/1/packages")
                        .file(file)
                        .param("versionNote", "v1")
                        .with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.fileName").value("app.zip"))
                .andExpect(jsonPath("$.data.detectedType").value("JAVA_MAVEN"));
    }

    @Test
    void upload_emptyFile_shouldReturn400() throws Exception {
        // 空文件：service 抛 BAD_REQUEST
        when(projectService.uploadPackage(eq(1L), any(), any(), any(), any(), any(AuthUser.class)))
                .thenThrow(new com.ops.deploy.common.BizException(
                        com.ops.deploy.common.ErrorCode.BAD_REQUEST, "empty file"));

        MockMultipartFile empty = new MockMultipartFile("file", "e.zip", "application/zip", new byte[0]);

        mockMvc.perform(multipart("/api/projects/1/packages")
                        .file(empty)
                        .with(user(OPS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void upload_tooLarge_shouldReturn400() throws Exception {
        when(projectService.uploadPackage(eq(1L), any(), any(), any(), any(), any(AuthUser.class)))
                .thenThrow(new com.ops.deploy.common.BizException(
                        com.ops.deploy.common.ErrorCode.BAD_REQUEST, "file too large"));

        // 超过 2GB 上限的字节数组无法构造，这里用 service 抛出的异常模拟（真实 2GB 由 multipart 配置兜底）
        byte[] big = new byte[16 * 1024 * 1024];
        MockMultipartFile bigFile = new MockMultipartFile("file", "big.zip", "application/zip", big);

        mockMvc.perform(multipart("/api/projects/1/packages")
                        .file(bigFile)
                        .with(user(OPS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- list versions / get version ----------

    @Test
    void listVersions_ops_shouldReturnDesc() throws Exception {
        PackageVersionVO v1 = versionVo(1L, 1L);
        PackageVersionVO v2 = versionVo(2L, 1L);
        when(projectService.listVersions(eq(1L), any(), any(AuthUser.class))).thenReturn(List.of(v2, v1));

        // 版本查看属运维读操作：OPS 即可（与 upload/download 一致）
        mockMvc.perform(get("/api/projects/1/packages").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(2));
    }

    @Test
    void listVersions_anyLoggedRoleShouldBeAllowed200() throws Exception {
        // listVersions 无 @RequireRole：项目级隔离由 Service 层按成员可见性控制（非成员 404），
        // 任何已登录角色均可请求（service mock 直接放行）
        when(projectService.listVersions(eq(1L), any(), any(AuthUser.class))).thenReturn(List.of(versionVo(1L, 1L)));

        mockMvc.perform(get("/api/projects/1/packages").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ---------- download ----------

    @Test
    void download_ops_shouldStreamBytesWithDisposition() throws Exception {
        PackageVersionEntity entity = new PackageVersionEntity();
        entity.setId(1L);
        entity.setFileName("app.zip");
        entity.setStoragePath("packages/1/1-x/app.zip");
        entity.setSizeBytes(5L);
        when(projectService.getVersionEntity(eq(1L), any(AuthUser.class))).thenReturn(entity);
        when(packageStorage.open("packages/1/1-x/app.zip"))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        MvcResult result = mockMvc.perform(get("/api/projects/packages/1/download").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("app.zip");
        assertThat(result.getResponse().getContentAsByteArray())
                .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void download_noToken_shouldReturn401() throws Exception {
        // download 需登录：无 token 时 @CurrentUser 解析失败 → 401 UNAUTHORIZED
        mockMvc.perform(get("/api/projects/packages/1/download"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- delete version ----------

    @Test
    void deleteVersion_admin_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/projects/packages/1").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private PackageVersionVO versionVo(Long id, Long projectId) {
        PackageVersionVO vo = new PackageVersionVO();
        vo.setId(id);
        vo.setProjectId(projectId);
        vo.setFileName("app-" + id + ".zip");
        vo.setSha256("a".repeat(64));
        vo.setSizeBytes(5L);
        return vo;
    }

    // ---------- 分片上传 + 断点续传 ----------

    @Test
    void uploadInit_ops_shouldReturnSessionId() throws Exception {
        when(chunkUploadService.init(eq(1L), any(), eq("app.zip"), eq(21981824L), eq(10485760L),
                any(), any(AuthUser.class))).thenReturn("sess-1");

        mockMvc.perform(post("/api/projects/1/upload-init")
                        .with(user(OPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"app.zip\",\"totalSize\":21981824,"
                                + "\"chunkSize\":10485760,\"sha256\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value("sess-1"));
    }

    @Test
    void uploadInit_invalidSha256_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/projects/1/upload-init")
                        .with(user(OPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"app.zip\",\"totalSize\":10000000,"
                                + "\"chunkSize\":10485760,\"sha256\":\"zzz\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void uploadChunk_ops_shouldReturn200() throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", "0",
                "application/octet-stream", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/projects/1/upload-chunk")
                        .file(part)
                        .param("sessionId", "sess-1")
                        .param("index", "0")
                        .with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(chunkUploadService).uploadChunk(eq(1L), eq("sess-1"), eq(0), any(), any(AuthUser.class));
    }

    @Test
    void uploadProgress_shouldReturnIndexes() throws Exception {
        when(chunkUploadService.progress(eq(1L), eq("sess-1"), any(AuthUser.class)))
                .thenReturn(List.of(0, 2));

        mockMvc.perform(get("/api/projects/1/upload-progress")
                        .param("sessionId", "sess-1")
                        .with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0]").value(0))
                .andExpect(jsonPath("$.data[1]").value(2));
    }

    @Test
    void uploadComplete_ops_shouldReturnVersionVo() throws Exception {
        when(chunkUploadService.complete(eq(1L), eq("sess-1"), any(), eq("v1"), any(AuthUser.class)))
                .thenReturn(versionVo(10L, 1L));

        mockMvc.perform(post("/api/projects/1/upload-complete")
                        .with(user(OPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-1\",\"versionNote\":\"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.fileName").value("app-10.zip"));
    }

    @Test
    void uploadInit_lowRole_shouldReturn403() throws Exception {
        // @RequireRole(OPS) 切面：低于 OPS 的角色拒绝
        AuthUser viewer = new AuthUser(7L, "viewer", "VIEWER");
        mockMvc.perform(post("/api/projects/1/upload-init")
                        .with(user(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"app.zip\",\"totalSize\":10000000,"
                                + "\"chunkSize\":10485760}"))
                .andExpect(status().isForbidden());
    }
}
