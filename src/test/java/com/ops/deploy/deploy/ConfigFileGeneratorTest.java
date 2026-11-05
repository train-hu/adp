package com.ops.deploy.deploy;

import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.llm.LlmChatClient;
import com.ops.deploy.llm.LlmModelConfigEntity;
import com.ops.deploy.llm.LlmModelConfigRepository;
import com.ops.deploy.project.DetectedResult;
import com.ops.deploy.project.ProjectConfigFileRequest;
import com.ops.deploy.project.ProjectConfigFileService;
import com.ops.deploy.project.ProjectConfigFileVO;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.PackageStorage;
import com.ops.deploy.project.PackageVersionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ConfigFileGenerator 单测：
 * 已有配置不生成、包内无 Dockerfile 时顺带生成 Dockerfile+compose（规则模板）、
 * 无模型走规则模板（build）、AI 成功采用、AI 产出校验不过/调用异常兜底、
 * stripCodeFence 围栏剥离、ensureNginx 复用默认模板。
 */
@ExtendWith(MockitoExtension.class)
class ConfigFileGeneratorTest {

    @Mock
    private ProjectConfigFileService configFileService;
    @Mock
    private LlmModelConfigRepository llmModelRepository;
    @Mock
    private LlmChatClient llmClient;
    @Mock
    private CredentialCryptoService crypto;
    @Mock
    private PackageStorage packageStorage;

    private ConfigFileGenerator generator;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        generator = new ConfigFileGenerator(configFileService, llmModelRepository, llmClient, crypto,
                packageStorage);
        project = new ProjectEntity();
        project.setId(1L);
        project.setName("MyApp");
    }

    private static DetectedResult detected(String type, Integer port) {
        DetectedResult d = new DetectedResult();
        d.setDetectedType(type);
        d.setSuggestedPort(port);
        return d;
    }

    // 默认子服务版本：serviceId=0（默认服务）；storagePath=null → packageTree 返回空
    private static PackageVersionEntity version() {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(9L);
        v.setProjectId(1L);
        v.setServiceId(0L);
        return v;
    }

    private static ProjectConfigFileVO vo(String fileKey, String content) {
        ProjectConfigFileVO v = new ProjectConfigFileVO();
        v.setFileKey(fileKey);
        v.setContent(content);
        return v;
    }

    private LlmModelConfigEntity enabledModel() {
        LlmModelConfigEntity m = new LlmModelConfigEntity();
        m.setName("gpt");
        m.setBaseUrl("http://llm/v1");
        m.setModel("gpt-4o-mini");
        m.setApiKeyCipher(new byte[]{1, 2, 3});
        m.setEnabled(true);
        return m;
    }

    private ProjectConfigFileRequest capturedUpsert() {
        ArgumentCaptor<ProjectConfigFileRequest> cap = ArgumentCaptor.forClass(ProjectConfigFileRequest.class);
        verify(configFileService).upsert(eq(0L), cap.capture());
        return cap.getValue();
    }

    // ---------- 已有配置：沿用不覆盖 ----------

    @Test
    void ensureCompose_existingCompose_shouldSkipGeneration() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of(vo("compose", "services: {}")));

        String key = generator.ensureCompose(project, detected("DOCKER", 8080), version());

        assertThat(key).isEqualTo("compose");
        verify(configFileService, never()).upsert(anyLong(), any());
        verifyNoInteractions(llmClient);
    }

    @Test
    void ensureNginx_existingNginx_shouldSkipGeneration() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of(vo("nginx", "server {}")));

        String key = generator.ensureNginx(project, detected("NGINX", 80), version());

        assertThat(key).isEqualTo("nginx");
        verify(configFileService, never()).upsert(anyLong(), any());
    }

    // ---------- 无 LLM 模型：规则模板兜底 ----------

    @Test
    void ensureCompose_noModel_dockerDetected_shouldUseBuildTemplate() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of());

        String key = generator.ensureCompose(project, detected("DOCKER", 9000), version());

        assertThat(key).isEqualTo("compose");
        String content = capturedUpsert().getContent();
        assertThat(content).contains("services:");
        assertThat(content).contains("build:");
        assertThat(content).contains("context: ./extract");
        assertThat(content).contains("\"9000:9000\"");
        assertThat(content).doesNotContain("image:");
    }

    @Test
    void ensureCompose_noModel_nonDockerDetected_shouldGenerateDockerfileAndCompose() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of());

        String key = generator.ensureCompose(project, detected("JAVA_MAVEN", null), version());

        // 包内无 Dockerfile：先补生成 Dockerfile（规则模板 JAVA→temurin），再生成 build 型 compose
        assertThat(key).isEqualTo("compose");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProjectConfigFileRequest> cap = ArgumentCaptor.forClass(ProjectConfigFileRequest.class);
        verify(configFileService, times(2)).upsert(eq(0L), cap.capture());
        assertThat(cap.getAllValues()).extracting(ProjectConfigFileRequest::getFileKey)
                .containsExactly("dockerfile", "compose");
        String dockerfile = cap.getAllValues().get(0).getContent();
        assertThat(dockerfile).contains("FROM eclipse-temurin");
        String compose = cap.getAllValues().get(1).getContent();
        assertThat(compose).contains("build:");
        assertThat(compose).contains("context: ./extract");
        verifyNoInteractions(llmClient);
    }

    @Test
    void ensureCompose_noModel_jarArtifact_shouldUseWorkdirRootContext() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of());

        // jar 包安装后被 mv 到 workdir 根（非 extract），构建上下文必须是根目录
        DetectedResult d = detected("JAVA_MAVEN", null);
        d.setArtifactKind("JAR");
        String key = generator.ensureCompose(project, d, version());

        assertThat(key).isEqualTo("compose");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProjectConfigFileRequest> cap = ArgumentCaptor.forClass(ProjectConfigFileRequest.class);
        verify(configFileService, times(2)).upsert(eq(0L), cap.capture());
        assertThat(cap.getAllValues()).extracting(ProjectConfigFileRequest::getFileKey)
                .containsExactly("dockerfile", "compose");
        String dockerfile = cap.getAllValues().get(0).getContent();
        assertThat(dockerfile).contains("FROM eclipse-temurin");
        // COPY 不得引用构建上下文外路径（../app.jar 为非法语法），jar 位于上下文根
        assertThat(dockerfile).contains("COPY app.jar app.jar");
        assertThat(dockerfile).doesNotContain("../");
        String compose = cap.getAllValues().get(1).getContent();
        assertThat(compose).contains("build:");
        // 上下文为 workdir 根（jar 所在处），Dockerfile 仍写入 extract/ 下引用
        assertThat(compose).contains("context: .\n");
        assertThat(compose).contains("dockerfile: extract/Dockerfile");
        assertThat(compose).doesNotContain("./extract");
        verifyNoInteractions(llmClient);
    }

    @Test
    void ensureCompose_unknownType_shouldGenerateDockerfileAndCompose() {
        project.setDefaultPort(7070);
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of());

        String key = generator.ensureCompose(project, detected(null, 9999), version());

        assertThat(key).isEqualTo("compose");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProjectConfigFileRequest> cap = ArgumentCaptor.forClass(ProjectConfigFileRequest.class);
        verify(configFileService, times(2)).upsert(eq(0L), cap.capture());
        assertThat(cap.getAllValues()).extracting(ProjectConfigFileRequest::getFileKey)
                .containsExactly("dockerfile", "compose");
        // 未知类型 Dockerfile 兜底静态托管，端口取项目默认 7070
        assertThat(cap.getAllValues().get(0).getContent()).contains("FROM nginx:alpine");
        assertThat(cap.getAllValues().get(1).getContent()).contains("\"7070:7070\"");
    }

    // ---------- AI 生成 ----------

    @Test
    void ensureCompose_aiSuccess_shouldUseAiContent() {
        String aiCompose = "services:\n  ai-svc:\n    image: foo:1\n    ports:\n      - \"8080:8080\"\n";
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString())).thenReturn(aiCompose);

        generator.ensureCompose(project, detected("DOCKER", 8080), version());

        // stripCodeFence 会 trim 首尾空白
        assertThat(capturedUpsert().getContent()).isEqualTo(aiCompose.stripTrailing());
    }

    @Test
    void ensureCompose_aiReplyWithCodeFence_shouldStripFence() {
        String fenced = "```yaml\nservices:\n  a:\n    image: x\n```\n";
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString())).thenReturn(fenced);

        generator.ensureCompose(project, detected("DOCKER", 8080), version());

        assertThat(capturedUpsert().getContent()).isEqualTo("services:\n  a:\n    image: x");
    }

    @Test
    void ensureCompose_aiOutputFailsValidation_shouldFallbackToRuleTemplate() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("这不是合法的 YAML [");

        generator.ensureCompose(project, detected("DOCKER", 8080), version());

        String content = capturedUpsert().getContent();
        assertThat(content).contains("build:"); // 规则模板兜底
        assertThat(content).doesNotContain("这不是合法");
    }

    @Test
    void ensureCompose_aiCallThrows_shouldFallbackToRuleTemplate() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("timeout"));

        generator.ensureCompose(project, detected("DOCKER", 8080), version());

        assertThat(capturedUpsert().getContent()).contains("build:");
    }

    @Test
    void ensureCompose_onlyDisabledModel_shouldFallbackToRuleTemplate() {
        LlmModelConfigEntity off = enabledModel();
        off.setEnabled(false);
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(off));

        generator.ensureCompose(project, detected("DOCKER", 8080), version());

        verifyNoInteractions(llmClient);
        assertThat(capturedUpsert().getContent()).contains("build:");
    }

    @Test
    void ensureCompose_aiDockerfileWithExtractPrefix_shouldFallbackToRuleDockerfile() {
        // 部署 15 实锤缺陷：AI 生成 COPY extract/ /app/ → 校验拦截 → 回落规则模板
        String aiDockerfile = "FROM python:3.11-slim\nCOPY extract/ /app/\n";
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(aiDockerfile)
                .thenReturn("services:\n  a:\n    image: x\n");

        generator.ensureCompose(project, detected("PYTHON", 8080), version());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProjectConfigFileRequest> cap = ArgumentCaptor.forClass(ProjectConfigFileRequest.class);
        verify(configFileService, times(2)).upsert(eq(0L), cap.capture());
        String dockerfile = cap.getAllValues().get(0).getContent();
        // 校验未通过，落库的是规则模板（PYTHON → python 镜像，COPY . .）而非 AI 的 COPY extract/
        assertThat(dockerfile).contains("FROM python");
        assertThat(dockerfile).doesNotContain("extract/");
    }

    // ---------- 包文件树 + AI 入口探测 ----------

    /** 构造真实 zip 字节流（entry 名列表）。 */
    private static byte[] zipOf(String... names) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String n : names) {
                zos.putNextEntry(new ZipEntry(n));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private PackageVersionEntity zipVersion(String... entries) throws java.io.IOException {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(9L);
        v.setFileName("pkg.zip");
        v.setStoragePath("packages/1/9-abc/pkg.zip");
        when(packageStorage.open(v.getStoragePath()))
                .thenReturn(new ByteArrayInputStream(zipOf(entries)));
        return v;
    }

    @Test
    void packageTree_zip_shouldListEntriesWithSingleRootStripped() throws java.io.IOException {
        PackageVersionEntity v = zipVersion("app-main/app/main.py", "app-main/app/__init__.py", "app-main/requirements.txt");
        assertThat(generator.packageTree(v))
                .containsExactlyInAnyOrder("app/main.py", "app/__init__.py", "requirements.txt");
    }

    @Test
    void packageTree_versionNull_shouldReturnEmpty() {
        assertThat(generator.packageTree(null)).isEmpty();
    }

    @Test
    void ensureCompose_aiDockerfileEntryNotInPackage_shouldFallbackToRuleDockerfile() throws java.io.IOException {
        // 入口探测：包内真实入口是 app/main.py（FastAPI），AI 臆造 app.py → 校验拦截 → 回落规则模板
        PackageVersionEntity v = zipVersion("app/main.py", "app/__init__.py", "requirements.txt");
        String aiDockerfile = "FROM python:3.11-slim\nCOPY . .\nCMD [\"python\", \"app.py\"]\n";
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(aiDockerfile)
                .thenReturn("services:\n  a:\n    image: x\n");

        generator.ensureCompose(project, detected("PYTHON", 8080), v);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProjectConfigFileRequest> cap = ArgumentCaptor.forClass(ProjectConfigFileRequest.class);
        verify(configFileService, times(2)).upsert(eq(0L), cap.capture());
        String dockerfile = cap.getAllValues().get(0).getContent();
        // 落库的是规则模板而非 AI 的 CMD ["python","app.py"]（app.py 不在包内）
        assertThat(dockerfile).contains("FROM python");
        assertThat(dockerfile).doesNotContain("app.py");
    }

    @Test
    void ensureCompose_packageTreeShouldAppearInPrompt() throws java.io.IOException {
        PackageVersionEntity v = zipVersion("app/main.py", "app/__init__.py", "requirements.txt");
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("FROM python:3.11-slim\nCOPY . .\nCMD [\"uvicorn\", \"app.main:app\", \"--port\", \"8080\"]\n")
                .thenReturn("services:\n  a:\n    image: x\n");

        generator.ensureCompose(project, detected("PYTHON", 8080), v);

        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).chat(anyString(), anyString(), anyString(), promptCap.capture());
        String dockerfilePrompt = promptCap.getAllValues().get(0);
        assertThat(dockerfilePrompt).contains("部署包内文件清单");
        assertThat(dockerfilePrompt).contains("app/main.py");
        assertThat(dockerfilePrompt).contains("requirements.txt");
        assertThat(dockerfilePrompt).contains("uvicorn");
        // compose prompt 同样带清单
        assertThat(promptCap.getAllValues().get(1)).contains("app/main.py");
    }

    // ---------- ensureNginx ----------

    @Test
    void ensureNginx_noModel_shouldUseDefaultServerBlock() {
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of());

        String key = generator.ensureNginx(project, detected("STATIC_WEB", 80), version());

        assertThat(key).isEqualTo("nginx");
        String content = capturedUpsert().getContent();
        assertThat(content).contains("server {");
        assertThat(content).contains("listen 80;");
        assertThat(content).contains("root /opt/deploy/MyApp/current;");
        assertThat(content).contains("try_files");
    }

    @Test
    void ensureNginx_aiSuccess_shouldUseAiContent() {
        String aiNginx = "server {\n  listen 80;\n  root /opt/deploy/MyApp/current;\n  index index.html;\n  location / {\n    try_files $uri $uri/ =404;\n  }\n}\n";
        when(configFileService.list(1L, 0L)).thenReturn(List.of());
        when(llmModelRepository.findAll()).thenReturn(List.of(enabledModel()));
        when(crypto.decrypt(new byte[]{1, 2, 3})).thenReturn("sk-123");
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString())).thenReturn(aiNginx);

        generator.ensureNginx(project, detected("STATIC_WEB", 80), version());

        // stripCodeFence 会 trim 首尾空白
        assertThat(capturedUpsert().getContent()).isEqualTo(aiNginx.stripTrailing());
    }

    // ---------- stripCodeFence ----------

    @Test
    void stripCodeFence_shouldHandleFencedAndPlain() {
        assertThat(ConfigFileGenerator.stripCodeFence("```\nabc\n```\n")).isEqualTo("abc");
        assertThat(ConfigFileGenerator.stripCodeFence("```yaml\nabc\n```")).isEqualTo("abc");
        assertThat(ConfigFileGenerator.stripCodeFence("abc")).isEqualTo("abc");
        assertThat(ConfigFileGenerator.stripCodeFence("  abc  ")).isEqualTo("abc");
        assertThat(ConfigFileGenerator.stripCodeFence(null)).isNull();
    }
}
