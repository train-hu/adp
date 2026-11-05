package com.ops.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.audit.AuditLog;
import com.ops.deploy.audit.AuditRepository;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成冒烟测试（Task 9）。
 *
 * <p>真实 PostgreSQL（Testcontainers，V1 含 JSONB，H2 跑不了）+ 完整 Spring 上下文
 * （@SpringBootTest RANDOM_PORT，走真实 HTTP 栈），打通：
 * <ol>
 *   <li>多角色种子账号（admin/ops）→ 登录取 JWT；</li>
 *   <li>登录 / 建服务器（AES-256-GCM 凭证加密落库）/ 建项目 / 上传部署包（规则层识别）
 *       / 发起部署（PENDING 异步触发）；</li>
 *   <li>审计 AOP：写操作落库 audit_log（操作人 + 角色 + action）；</li>
 *   <li>RBAC 切面：OPS 调 ADMIN 端点 → 403 且<b>不落审计</b>（RBAC 在最外层，
 *       拒绝时审计 proceed() 未执行）。</li>
 * </ol>
 *
 * <p>部署为异步执行（PENDING 落库后 {@code DeploymentRunner} 经 SSH 连真实目标机），
 * 本测试用不可达 IP 指向，故只断言"已落库且非终态"与审计，不断言部署跑到 SUCCESS
 * （那需要内嵌 SshServer，属更重的端到端用例）。
 *
 * <p><b>Docker 依赖</b>：Testcontainers 需要 Docker/Containerd。无 Docker 的环境
 * （如 Windows 本地）{@code dockerAvailable()} 返回 false，本类<b>整体优雅跳过</b>，
 * 不破坏默认 {@code mvn test} 基线。标注 {@code @Tag("integration")} 便于后续分组排除。
 * 在具备 Docker 的环境（CI/Linux/WSL2）下本类实际执行并断言。
 */
@Testcontainers
@Tag("integration")
@EnabledIf("dockerAvailable")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "deploy.jwt-secret-enforce-strong=false",
                "deploy.credential-kek=integration-test-kek-do-not-use-in-prod-000111",
                "deploy.data-dir=target/integration-data",
                "logging.level.root=warn",
                "spring.jpa.open-in-view=false"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullChainIntegrationTest {

    /**
     * 无 Docker → 整类跳过。类级 @EnabledIf 在 JUnit 发现阶段判定，
     * 条件为 false 时整类 abort（含 @Testcontainers 扩展的 beforeAll 都不会执行），
     * 不会去起 PostgreSQL 容器。
     */
    static boolean dockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            // 部分 Windows 环境 PATH 含 \\?\ 长路径前缀，Testcontainers 探测会抛 InvalidPathException
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private ServerRepository serverRepository;

    @Autowired
    private CredentialCryptoService credentialCryptoService;

    @LocalServerPort
    private int port;

    private final ObjectMapper json = new ObjectMapper();
    private RestTemplate rt;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 默认 RestTemplate 对 4xx/5xx 抛 HttpClientErrorException，导致负例（403/401/400）
        // 拿不到 body 而直接异常。这里配置 no-throw 的 ResponseErrorHandler，
        // 使 4xx/5xx 也正常返回 ResponseEntity，断言可同时校验 HTTP 状态码与 body code。
        rt = new RestTemplate();
        rt.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
                // no-op：不把错误当异常抛
            }
        });
    }

    // ---- 测试辅助 ---------------------------------------------------------

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String login(String username, String password) throws Exception {
        String body = json.writeValueAsString(Map.of("username", username, "password", password));
        ResponseEntity<String> resp = rt.postForEntity(
                base() + "/api/auth/login",
                new HttpEntity<>(body, jsonHeaders()),
                String.class);
        assertThat(resp.getStatusCode().value()).as("login status").isEqualTo(200);
        JsonNode root = json.readTree(resp.getBody());
        assertThat(root.get("code").asInt()).isEqualTo(0);
        return root.get("data").get("token").asText();
    }

    private JsonNode postJson(String path, String token, Map<String, Object> body) throws Exception {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        String resp = rt.postForObject(base() + path,
                new HttpEntity<>(json.writeValueAsString(body), h), String.class);
        return json.readTree(resp);
    }

    private JsonNode postMultipart(String path, String token, String filename, byte[] content)
            throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token);
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        org.springframework.util.MultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", resource);
        ResponseEntity<String> resp = rt.postForEntity(
                base() + path, new HttpEntity<>(form, h), String.class);
        return json.readTree(resp.getBody());
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** 构造仅含 index.html 的 zip 包（规则层应识别为 STATIC_WEB）。 */
    private static byte[] staticWebZip() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] html = "<!doctype html><html><head><title>demo</title></head><body>ok</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("index.html"));
            zos.write(html);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private ServerEntity findServerByName(String name) {
        return serverRepository.findByName(name).orElseThrow(
                () -> new AssertionError("server not found in db: " + name));
    }

    private boolean hasAudit(String action, String username) {
        return auditRepository.findAll().stream()
                .anyMatch(a -> action.equals(a.getAction()) && username.equals(a.getUsername()));
    }

    private AuditLog findAudit(String action, String username, String detailContains) {
        return auditRepository.findAll().stream()
                .filter(a -> action.equals(a.getAction()) && username.equals(a.getUsername()))
                .filter(a -> detailContains == null
                        || (a.getDetail() != null && a.getDetail().contains(detailContains)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no audit for " + action + "/" + username + " containing " + detailContains));
    }

    // ---- 用例 -------------------------------------------------------------

    /**
     * 核心冒烟：admin 登录 → 建服务器 → 建项目 → 上传包（STATIC_WEB 识别）→ 发起部署。
     * 断言全链路成功 + 审计全部落库（操作人/角色/action 正确）。
     */
    @Test
    @Order(1)
    void fullChain_loginToDeploy_withAudit() throws Exception {
        String token = login("admin", "admin123");
        String serverName = "chainit-server-" + System.nanoTime();
        String projectName = "chainit-proj-" + System.nanoTime();

        // 1. 建服务器（ADMIN）
        JsonNode server = postJson("/api/servers", token, Map.of(
                "name", serverName, "ip", "203.0.113.10",
                "sshUser", "deploy", "password", "S3cret-Pw", "sshPort", 2222));
        assertThat(server.get("code").asInt()).isEqualTo(0);
        long serverId = server.get("data").get("id").asLong();
        assertThat(serverId).isPositive();

        // 2. 建项目（ADMIN）
        JsonNode project = postJson("/api/projects", token,
                Map.of("name", projectName, "description", "chain smoke"));
        assertThat(project.get("code").asInt()).isEqualTo(0);
        long projectId = project.get("data").get("id").asLong();

        // 3. 上传部署包（OPS）→ 识别 STATIC_WEB
        JsonNode pkg = postMultipart(
                "/api/projects/" + projectId + "/packages", token, "site.zip", staticWebZip());
        assertThat(pkg.get("code").asInt()).isEqualTo(0);
        long versionId = pkg.get("data").get("id").asLong();
        assertThat(pkg.get("data").get("detectedType").asText()).isEqualTo("STATIC_WEB");

        // 4. 发起部署（OPS）：STATIC_WEB → NGINX 模式（平台托管 nginx，无需启动命令）
        JsonNode deploy = postJson("/api/deployments", token, Map.of(
                "versionId", versionId, "serverId", serverId, "port", 80, "healthPath", "/"));
        assertThat(deploy.get("code").asInt()).isEqualTo(0);
        long deploymentId = deploy.get("data").get("id").asLong();
        // create 在事务提交后异步触发，响应同步返回落库时的状态（PENDING），确定性断言
        assertThat(deploy.get("data").get("status").asText()).isEqualTo("PENDING");
        assertThat(deploy.get("data").get("mode").asText()).isEqualTo("NGINX");
        assertThat(deploymentId).isPositive();

        // 5. 审计全部落库，操作人/角色正确
        AuditLog loginAudit = findAudit("AUTH_LOGIN", "admin", null);
        assertThat(loginAudit.getRole()).isEqualTo("ADMIN");
        assertThat(findAudit("SERVER_CREATE", "admin", serverName).getTargetType()).isEqualTo("SERVER");
        assertThat(findAudit("PROJECT_CREATE", "admin", projectName).getTargetType()).isEqualTo("PROJECT");
        assertThat(findAudit("PACKAGE_UPLOAD", "admin", "site.zip").getTargetType()).isEqualTo("PROJECT");
        // DEPLOY_CREATE 的 detail 为 "create args=[...]"（请求对象无 toString），只断言落库+操作人
        assertThat(hasAudit("DEPLOY_CREATE", "admin")).isTrue();
    }

    /**
     * OPS 角色权限边界：
     * <ul>
     *   <li>可执行 OPS 级写操作（PROJECT_CREATE / PACKAGE_UPLOAD / DEPLOY_CREATE，
     *       均 @RequireRole(OPS)）；自建项目创建人自动成为项目成员；</li>
     *   <li>被拒于 ADMIN 级端点（SERVER_CREATE / USER_CREATE，@RequireRole(ADMIN)）→ 403，
     *       且被拒不落审计。</li>
     * </ul>
     */
    @Test
    @Order(2)
    void opsUser_allowedOnOpsEndpoints_deniedOnAdminEndpoints() throws Exception {
        // admin 提供前置资源（服务器/项目均 ADMIN 级）
        String adminToken = login("admin", "admin123");
        String serverName = "chainit-ops-" + System.nanoTime();
        String projectName = "chainit-ops-proj-" + System.nanoTime();

        JsonNode server = postJson("/api/servers", adminToken, Map.of(
                "name", serverName, "ip", "203.0.113.11",
                "sshUser", "deploy", "password", "S3cret-Pw", "sshPort", 2222));
        assertThat(server.get("code").asInt()).isEqualTo(0);
        long serverId = server.get("data").get("id").asLong();

        // OPS 登录（项目由 OPS 自建，创建人自动成为项目成员）
        String opsToken = login("ops", "admin123");

        JsonNode project = postJson("/api/projects", opsToken, Map.of("name", projectName));
        assertThat(project.get("code").asInt()).as("OPS can create project").isEqualTo(0);
        long projectId = project.get("data").get("id").asLong();

        // OPS 允许：上传（OPS 级）→ 识别 STATIC_WEB
        JsonNode pkg = postMultipart(
                "/api/projects/" + projectId + "/packages", opsToken, "site.zip", staticWebZip());
        assertThat(pkg.get("code").asInt()).as("OPS can upload package").isEqualTo(0);
        long versionId = pkg.get("data").get("id").asLong();
        assertThat(pkg.get("data").get("detectedType").asText()).isEqualTo("STATIC_WEB");

        // OPS 允许：发起部署（OPS 级）
        JsonNode deploy = postJson("/api/deployments", opsToken, Map.of(
                "versionId", versionId, "serverId", serverId,
                "startCmd", "python3 -m http.server 8081", "port", 8081, "healthPath", "/"));
        assertThat(deploy.get("code").asInt()).as("OPS can create deployment").isEqualTo(0);
        assertThat(deploy.get("data").get("status").asText()).isEqualTo("PENDING");

        // OPS 被拒：建服务器（ADMIN 级）→ 403
        long serverCreateAuditsBefore = auditRepository.findAll().stream()
                .filter(a -> "SERVER_CREATE".equals(a.getAction()))
                .count();
        JsonNode deniedServer = postJson("/api/servers", opsToken, Map.of(
                "name", "chainit-ops-denied-" + System.nanoTime(), "ip", "203.0.113.11",
                "sshUser", "deploy", "password", "x", "sshPort", 22));
        assertThat(deniedServer.get("code").asInt()).as("OPS denied on ADMIN endpoint").isEqualTo(403);

        // OPS 被拒：建用户（ADMIN 级）→ 403
        JsonNode deniedUser = postJson("/api/users", opsToken, Map.of(
                "username", "chainit-ops-denied-user-" + System.nanoTime(),
                "password", "secret123", "role", "OPS"));
        assertThat(deniedUser.get("code").asInt()).as("OPS denied on ADMIN endpoint").isEqualTo(403);

        // OPS 的 OPS 级写操作落审计
        assertThat(hasAudit("PACKAGE_UPLOAD", "ops")).isTrue();
        assertThat(hasAudit("DEPLOY_CREATE", "ops")).isTrue();
        assertThat(findAudit("AUTH_LOGIN", "ops", null).getRole()).isEqualTo("OPS");
        // 被拒的 ADMIN 级操作不落审计
        assertThat(hasAudit("USER_CREATE", "ops")).as(
                "denied USER_CREATE must not be audited").isFalse();
        long serverCreateAuditsAfter = auditRepository.findAll().stream()
                .filter(a -> "SERVER_CREATE".equals(a.getAction()))
                .count();
        assertThat(serverCreateAuditsAfter)
                .as("denied SERVER_CREATE must not be audited").isEqualTo(serverCreateAuditsBefore);
    }

    /**
     * 认证负例：错误密码登录 → 401（登录失败不落审计）。
     */
    @Test
    @Order(3)
    void wrongPassword_loginDenied() throws Exception {
        ResponseEntity<String> bad = rt.postForEntity(
                base() + "/api/auth/login",
                new HttpEntity<>(json.writeValueAsString(
                        Map.of("username", "admin", "password", "wrong")), jsonHeaders()),
                String.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
    }

    /**
     * 凭证加密：服务器 SSH 密码以 AES-256-GCM 密文落库，可解密还原，
     * 密文（字节与解码后）均不含明文密码。
     */
    @Test
    @Order(4)
    void serverCredential_isEncryptedAtRest() throws Exception {
        String token = login("admin", "admin123");
        String serverName = "chainit-cred-" + System.nanoTime();
        String plainPassword = "S3cret-Pw-For-Cred-Test";

        JsonNode server = postJson("/api/servers", token, Map.of(
                "name", serverName, "ip", "203.0.113.13",
                "sshUser", "deploy", "password", plainPassword, "sshPort", 22));
        assertThat(server.get("code").asInt()).isEqualTo(0);

        ServerEntity entity = findServerByName(serverName);
        byte[] cipher = entity.getCredentialCipher();
        assertThat(cipher).as("credential cipher should be present").isNotEmpty();

        // 密文不泄露明文（字节层面 + 解码后字符串层面）
        String cipherAsText = new String(cipher, StandardCharsets.UTF_8);
        assertThat(cipherAsText).doesNotContain(plainPassword);
        assertThat(Arrays.toString(cipher)).doesNotContain(plainPassword);

        // 可解密还原
        assertThat(credentialCryptoService.decrypt(Arrays.copyOf(cipher, cipher.length)))
                .isEqualTo(plainPassword);
    }

    /**
     * STATIC_WEB 包无建议启动命令、项目无默认、请求未显式 startCmd →
     * 以 NGINX 模式（平台托管 nginx）创建部署成功，无需启动命令。
     */
    @Test
    @Order(5)
    void deploy_withoutStartCmd_forStaticWeb_nginxMode() throws Exception {
        String token = login("admin", "admin123");
        String serverName = "chainit-nosrcmd-" + System.nanoTime();
        String projectName = "chainit-nosrcmd-proj-" + System.nanoTime();

        JsonNode server = postJson("/api/servers", token, Map.of(
                "name", serverName, "ip", "203.0.113.14",
                "sshUser", "deploy", "password", "S3cret-Pw", "sshPort", 22));
        assertThat(server.get("code").asInt()).isEqualTo(0);
        long serverId = server.get("data").get("id").asLong();

        JsonNode project = postJson("/api/projects", token, Map.of("name", projectName));
        assertThat(project.get("code").asInt()).isEqualTo(0);
        long projectId = project.get("data").get("id").asLong();

        JsonNode pkg = postMultipart(
                "/api/projects/" + projectId + "/packages", token, "site.zip", staticWebZip());
        assertThat(pkg.get("code").asInt()).isEqualTo(0);
        long versionId = pkg.get("data").get("id").asLong();
        assertThat(pkg.get("data").get("detectedType").asText()).isEqualTo("STATIC_WEB");

        // 不带 startCmd：STATIC_WEB → NGINX 模式，免启动命令，创建成功
        JsonNode deploy = postJson("/api/deployments", token,
                Map.of("versionId", versionId, "serverId", serverId, "port", 80, "healthPath", "/"));
        assertThat(deploy.get("code").asInt()).isEqualTo(0);
        assertThat(deploy.get("data").get("status").asText()).isEqualTo("PENDING");
        assertThat(deploy.get("data").get("mode").asText()).isEqualTo("NGINX");
        assertThat(deploy.get("data").get("startCmd").isNull()).isTrue();
    }
}
