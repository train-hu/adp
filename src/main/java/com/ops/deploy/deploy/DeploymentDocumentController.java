package com.ops.deploy.deploy;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.Result;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.user.Role;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部署文档接口（/api/deployments/{id}/documents，Task 8）。
 *
 * <p>查看 / 下载 无角色限制（任何已登录角色可读文档，但须为项目成员或 ADMIN，否则 404）；
 * 重新生成需 OPS（运维写操作，经 {@link Auditable} 审计，且须具备项目操作权）。
 *
 * <ul>
 *   <li>GET ?type=deploy|maintain：在线查看，返回 Markdown 全文（{@code Result<String>}）；</li>
 *   <li>GET /download ?type=：下载 {@code .md}（{@code text/markdown; charset=UTF-8} +
 *       {@code Content-Disposition: attachment; filename="{projectName}-{type}-doc.md"}）；</li>
 *   <li>POST /regenerate：重新生成两份文档（OPS 角色）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/deployments")
public class DeploymentDocumentController {

    private final DeploymentDocumentService documentService;
    private final DeploymentRepository deploymentRepository;
    private final ProjectAccessService accessService;

    public DeploymentDocumentController(DeploymentDocumentService documentService,
                                        DeploymentRepository deploymentRepository,
                                        ProjectAccessService accessService) {
        this.documentService = documentService;
        this.deploymentRepository = deploymentRepository;
        this.accessService = accessService;
    }

    /** 在线查看文档（Markdown 全文）。type ∈ {deploy, maintain}。 */
    @GetMapping("/{id}/documents")
    public Result<String> view(@PathVariable Long id, @RequestParam String type,
                               @CurrentUser AuthUser user) {
        assertView(id, user);
        return Result.ok(documentService.get(id, type));
    }

    /** 下载文档为 .md 文件（text/markdown + Content-Disposition 附件）。 */
    @GetMapping("/{id}/documents/download")
    public ResponseEntity<String> download(@PathVariable Long id, @RequestParam String type,
                                           @CurrentUser AuthUser user) {
        assertView(id, user);
        String markdown = documentService.get(id, type);
        String projectName = documentService.projectNameOf(id);
        String t = type == null ? "" : type.toLowerCase();
        // safeName 已剔除 CR/LF 与引号/反斜杠，手动拼接不会引入响应头注入 / 响应拆分。
        String filename = safeName(projectName) + "-" + t + "-doc.md";
        String disposition = "attachment; filename=\"" + filename + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(markdown);
    }

    /** 重新生成部署/维护两份文档（OPS）。 */
    @PostMapping("/{id}/documents/regenerate")
    @RequireRole(Role.OPS)
    @Auditable(action = "DOC_REGENERATE", targetType = "DEPLOYMENT")
    public Result<DeploymentDocumentVO> regenerate(@PathVariable Long id, @CurrentUser AuthUser user) {
        accessService.assertOperate(requireProjectId(id), user);
        return Result.ok(documentService.generate(id));
    }

    /** 部署 → 所属项目 id（部署不存在 → 404）。 */
    private Long requireProjectId(Long deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .map(DeploymentEntity::getProjectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "deployment not found: " + deploymentId));
    }

    /** 文档读取的项目隔离校验：非成员视同部署不存在（404）。 */
    private void assertView(Long deploymentId, AuthUser user) {
        accessService.assertView(requireProjectId(deploymentId), user);
    }

    /** 项目名用于文件名：去引号/反斜杠并剔除 CR/LF，防文件名注入与响应头注入（正常项目名原样）。 */
    private static String safeName(String name) {
        String n = name == null ? "project" : name;
        return n.replace('"', '_').replace('\\', '_')
                .replace("\r", "").replace("\n", "");
    }
}
