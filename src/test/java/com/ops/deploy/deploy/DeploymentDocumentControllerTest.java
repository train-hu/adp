package com.ops.deploy.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.GlobalExceptionHandler;
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
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DeploymentDocumentController 单测：standalone MockMvc + mock DeploymentDocumentService。
 *
 * <p>启用 RBAC 切面（同 DeploymentControllerTest 模板）：
 * 查看/下载 无角色限制（任何已登录可读）；regenerate 需 OPS。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentDocumentControllerTest {

    @Mock
    private DeploymentDocumentService documentService;
    // 新增：部署仓库 mock，controller 的私有 requireProjectId 会查 deployment → projectId
    @Mock
    private DeploymentRepository deploymentRepository;
    // 新增：项目访问控制 mock，void 断言方法默认 no-op，鉴权天然放行
    @Mock
    private ProjectAccessService accessService;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        DeploymentDocumentController raw = new DeploymentDocumentController(documentService, deploymentRepository, accessService);
        // 启用 RBAC 切面（standalone MockMvc 默认不启用 AOP）
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("requireRoleAspect", new com.ops.deploy.security.RequireRoleAspect());
        AnnotationAwareAspectJAutoProxyCreator registrar = new AnnotationAwareAspectJAutoProxyCreator();
        registrar.setBeanFactory(beanFactory);
        DeploymentDocumentController proxied =
                (DeploymentDocumentController) registrar.postProcessAfterInitialization(raw, "deploymentDocumentController");
        Validator validator = new LocalValidatorFactoryBean();
        mockMvc = MockMvcBuilders.standaloneSetup(proxied)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
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

    /** controller 私有 requireProjectId 需要 deployment 存在且能取到 projectId。 */
    private void stubDeploymentWithProject(Long deploymentId, Long projectId) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(deploymentId);
        d.setProjectId(projectId);
        org.mockito.Mockito.lenient().when(deploymentRepository.findById(deploymentId))
                .thenReturn(Optional.of(d));
    }

    @Test
    void view_deploy_shouldReturnMarkdown() throws Exception {
        stubDeploymentWithProject(1L, 1L);
        when(documentService.get(1L, "deploy")).thenReturn("# 部署文档 — demo");

        mockMvc.perform(get("/api/deployments/1/documents").param("type", "deploy").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("# 部署文档 — demo"));
        verify(documentService).get(1L, "deploy");
    }

    @Test
    void view_invalidType_shouldReturn400() throws Exception {
        stubDeploymentWithProject(1L, 1L);
        when(documentService.get(1L, "bogus"))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "invalid document type"));

        mockMvc.perform(get("/api/deployments/1/documents").param("type", "bogus").with(user(OPS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void view_notGenerated_shouldReturn404() throws Exception {
        stubDeploymentWithProject(1L, 1L);
        when(documentService.get(1L, "deploy"))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "文档尚未生成，请先重新生成"));

        mockMvc.perform(get("/api/deployments/1/documents").param("type", "deploy").with(user(OPS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void download_shouldSetContentDisposition() throws Exception {
        stubDeploymentWithProject(1L, 1L);
        when(documentService.get(1L, "maintain")).thenReturn("# 维护文档 — demo");
        when(documentService.projectNameOf(1L)).thenReturn("demo");

        mockMvc.perform(get("/api/deployments/1/documents/download").param("type", "maintain").with(user(OPS)))
                .andExpect(status().isOk())
                .andExpect(content().string("# 维护文档 — demo"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"demo-maintain-doc.md\""));
        verify(documentService).get(1L, "maintain");
        verify(documentService).projectNameOf(1L);
    }

    @Test
    void regenerate_admin_shouldReturn200AndCallGenerate() throws Exception {
        stubDeploymentWithProject(1L, 1L);
        when(documentService.generate(1L)).thenReturn(docVo());

        mockMvc.perform(post("/api/deployments/1/documents/regenerate").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deploymentId").value(1));
        verify(documentService).generate(1L);
    }

    private static DeploymentDocumentVO docVo() {
        DeploymentDocumentVO vo = new DeploymentDocumentVO();
        vo.setDeploymentId(1L);
        vo.setType("deploy");
        vo.setContent("# 部署文档 — demo");
        vo.setUpdatedAt(java.time.LocalDateTime.now());
        return vo;
    }
}
