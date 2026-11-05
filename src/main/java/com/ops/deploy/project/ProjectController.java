package com.ops.deploy.project;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.user.Role;
import com.ops.deploy.user.UserService;
import com.ops.deploy.user.UserVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目管理接口（/api/projects）。JWT 认证由 JwtAuthFilter 统一拦截，
 * 项目级隔离与角色由 {@link ProjectAccessService} 在 Service 层校验
 * （非成员一律 404，不泄露项目存在性），全局角色下限由 @RequireRole 切面兜底，
 * 写操作由 @Auditable 审计。
 *
 * <p>角色语义（见 ProjectAccessService）：
 * - list/get/版本查看/下载：任何登录用户，但仅限其成员项目（ADMIN 全部）；
 * - 上传包/部署：成员且全局角色 &ge; OPS；
 * - 创建/更新/删除项目、删版本、改配置文件、成员管理：ADMIN 或项目成员（且 &ge; OPS）。
 * 下载为二进制流（Content-Disposition 用原 fileName，UTF-8 URL 编码兜底非 ASCII 名）。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final PackageStorage packageStorage;
    private final ProjectConfigFileService configFileService;
    private final ProjectAccessService accessService;
    private final UserService userService;
    private final ChunkUploadService chunkUploadService;
    private final com.ops.deploy.deploy.ConfigFileGenerator configFileGenerator;
    private final ProjectSubServiceManager subServiceManager;

    public ProjectController(ProjectService projectService, PackageStorage packageStorage,
                             ProjectConfigFileService configFileService,
                             ProjectAccessService accessService,
                             UserService userService,
                             ChunkUploadService chunkUploadService,
                             com.ops.deploy.deploy.ConfigFileGenerator configFileGenerator,
                             ProjectSubServiceManager subServiceManager) {
        this.projectService = projectService;
        this.packageStorage = packageStorage;
        this.configFileService = configFileService;
        this.accessService = accessService;
        this.userService = userService;
        this.chunkUploadService = chunkUploadService;
        this.configFileGenerator = configFileGenerator;
        this.subServiceManager = subServiceManager;
    }

    @PostMapping
    @RequireRole(Role.OPS)
    @Auditable(action = "PROJECT_CREATE", targetType = "PROJECT")
    public ResponseEntity<Result<ProjectVO>> create(@Valid @RequestBody ProjectCreateRequest req,
                                                    @CurrentUser AuthUser user) {
        return ResponseEntity.status(201).body(Result.ok(projectService.create(req, user)));
    }

    @GetMapping
    public Result<List<ProjectVO>> list(@CurrentUser AuthUser user) {
        return Result.ok(projectService.list(user));
    }

    @GetMapping("/{id}")
    public Result<ProjectVO> get(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(projectService.get(id, user));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.OPS)
    @Auditable(action = "PROJECT_UPDATE", targetType = "PROJECT")
    public Result<ProjectVO> update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest req,
                                    @CurrentUser AuthUser user) {
        return Result.ok(projectService.update(id, req, user));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.OPS)
    @Auditable(action = "PROJECT_DELETE", targetType = "PROJECT")
    public Result<Void> delete(@PathVariable Long id, @CurrentUser AuthUser user) {
        projectService.delete(id, user);
        return Result.<Void>ok(null);
    }

    @PostMapping("/{id}/packages")
    @RequireRole(Role.OPS)
    @Auditable(action = "PACKAGE_UPLOAD", targetType = "PROJECT")
    public Result<PackageVersionVO> upload(@PathVariable Long id,
                                           @RequestParam(value = "file", required = false)
                                           MultipartFile file,
                                           @RequestParam(value = "serviceId", required = false)
                                           Long serviceId,
                                           @Validated @RequestParam(value = "versionNote", required = false)
                                           @Size(max = 255) String versionNote,
                                           @Valid
                                           @ModelAttribute("gitSource")
                                           PackageGitSource gitSource,
                                           @CurrentUser AuthUser user) throws IOException {
        return Result.ok(projectService.uploadPackage(id, serviceId, file, versionNote, gitSource, user));
    }

    // ---------- 分片上传 + 断点续传（大文件切片直传） ----------

    /** 创建分片上传会话，返回 sessionId（前端持有，断点续传凭它查进度）。 */
    @PostMapping("/{id}/upload-init")
    @RequireRole(Role.OPS)
    @Auditable(action = "PACKAGE_UPLOAD_INIT", targetType = "PROJECT")
    public Result<UploadInitVO> uploadInit(@PathVariable Long id,
                                           @Valid @RequestBody UploadInitRequest req,
                                           @CurrentUser AuthUser user) {
        String sessionId = chunkUploadService.init(id, req.getServiceId(), req.getFileName(),
                req.getTotalSize(), req.getChunkSize(), req.getSha256(), user);
        UploadInitVO vo = new UploadInitVO();
        vo.setSessionId(sessionId);
        return Result.ok(vo);
    }

    /** 上传单个分片（幂等，同一 index 重传覆盖）。 */
    @PostMapping("/{id}/upload-chunk")
    @RequireRole(Role.OPS)
    @Auditable(action = "PACKAGE_UPLOAD_CHUNK", targetType = "PROJECT")
    public Result<Void> uploadChunk(@PathVariable Long id,
                                    @RequestParam String sessionId,
                                    @RequestParam int index,
                                    @RequestParam("file") MultipartFile file,
                                    @CurrentUser AuthUser user) throws IOException {
        chunkUploadService.uploadChunk(id, sessionId, index, file, user);
        return Result.<Void>ok(null);
    }

    /** 已传分片索引（升序）：断点续传时前端跳过已传分片。 */
    @GetMapping("/{id}/upload-progress")
    public Result<List<Integer>> uploadProgress(@PathVariable Long id,
                                                @RequestParam String sessionId,
                                                @CurrentUser AuthUser user) {
        return Result.ok(chunkUploadService.progress(id, sessionId, user));
    }

    /** 合并完成并落库（走标准上传链路：去重/类型识别/两阶段落盘），成功返回版本信息。 */
    @PostMapping("/{id}/upload-complete")
    @RequireRole(Role.OPS)
    @Auditable(action = "PACKAGE_UPLOAD", targetType = "PROJECT")
    public Result<PackageVersionVO> uploadComplete(@PathVariable Long id,
                                                   @Valid @RequestBody UploadCompleteRequest req,
                                                   @CurrentUser AuthUser user) throws IOException {
        return Result.ok(chunkUploadService.complete(id, req.getSessionId(),
                req.getSha256(), req.getVersionNote(), user));
    }

    @GetMapping("/{id}/packages")
    public Result<List<PackageVersionVO>> listVersions(@PathVariable Long id,
                                                       @RequestParam(value = "serviceId", required = false)
                                                       Long serviceId,
                                                       @CurrentUser AuthUser user) {
        return Result.ok(projectService.listVersions(id, serviceId, user));
    }

    @GetMapping("/packages/{versionId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long versionId,
                                                        @CurrentUser AuthUser user)
            throws IOException {
        PackageVersionEntity entity = projectService.getVersionEntity(versionId, user);
        InputStream in = packageStorage.open(entity.getStoragePath());
        String disposition = ContentDisposition.attachment()
                .filename(entity.getFileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(in));
    }

    @DeleteMapping("/packages/{versionId}")
    @RequireRole(Role.OPS)
    @Auditable(action = "PACKAGE_DELETE", targetType = "PROJECT")
    public Result<Void> deleteVersion(@PathVariable Long versionId, @CurrentUser AuthUser user) {
        projectService.deleteVersion(versionId, user);
        return Result.<Void>ok(null);
    }

    // ---------- 项目成员管理（ADMIN 或项目成员） ----------

    @GetMapping("/{id}/members")
    public Result<List<ProjectMemberVO>> listMembers(@PathVariable Long id, @CurrentUser AuthUser user) {
        accessService.assertView(id, user);
        return Result.ok(accessService.listMembers(id));
    }

    /** 添加成员候选用户列表（可管理项目者可见）：仅返回 id + 用户名（含全局角色供展示），
     *  不回传 projectIds（避免泄露候选人在其他项目的成员关系）。 */
    @GetMapping("/{id}/member-candidates")
    public Result<List<UserVO>> memberCandidates(@PathVariable Long id, @CurrentUser AuthUser user) {
        accessService.assertManageProject(id, user);
        List<UserVO> candidates = userService.list().stream()
                .map(v -> {
                    UserVO vo = new UserVO();
                    vo.setId(v.getId());
                    vo.setUsername(v.getUsername());
                    vo.setRole(v.getRole());
                    vo.setEnabled(v.isEnabled());
                    vo.setCreatedAt(v.getCreatedAt());
                    return vo;
                })
                .collect(Collectors.toList());
        return Result.ok(candidates);
    }

    @PostMapping("/{id}/members")
    @RequireRole(Role.OPS)
    @Auditable(action = "PROJECT_MEMBER_ADD", targetType = "PROJECT")
    public Result<ProjectMemberVO> addMember(@PathVariable Long id,
                                             @Valid @RequestBody ProjectMemberRequest req,
                                             @CurrentUser AuthUser user) {
        return Result.ok(accessService.addMember(id, req.getUserId(), user));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @RequireRole(Role.OPS)
    @Auditable(action = "PROJECT_MEMBER_REMOVE", targetType = "PROJECT")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId,
                                     @CurrentUser AuthUser user) {
        accessService.removeMember(id, userId, user);
        return Result.<Void>ok(null);
    }

    // ---------- 项目配置文件（nginx / compose / env 等，页面可编辑，部署时下发） ----------

    @GetMapping("/{id}/config-files")
    public Result<List<ProjectConfigFileVO>> listConfigFiles(@PathVariable Long id,
                                                             @RequestParam(value = "serviceId", required = false)
                                                             Long serviceId,
                                                             @CurrentUser AuthUser user) {
        accessService.assertView(id, user);
        return Result.ok(configFileService.list(id, serviceId));
    }

    @PutMapping("/{id}/config-files")
    @RequireRole(Role.OPS)
    @Auditable(action = "CONFIG_FILE_UPSERT", targetType = "PROJECT")
    public Result<ProjectConfigFileVO> upsertConfigFile(@PathVariable Long id,
                                                        @RequestParam(value = "serviceId", required = false)
                                                        Long serviceId,
                                                        @Valid @RequestBody ProjectConfigFileRequest req,
                                                        @CurrentUser AuthUser user) {
        accessService.assertManageProject(id, user);
        // 解析为实际子服务 id（null/0 → 默认），upsert 按服务作用域落库
        Long resolvedServiceId = subServiceManager.resolve(id, serviceId).getId();
        return Result.ok(configFileService.upsert(resolvedServiceId, req));
    }

    @DeleteMapping("/{id}/config-files/{fileKey}")
    @RequireRole(Role.OPS)
    @Auditable(action = "CONFIG_FILE_DELETE", targetType = "PROJECT")
    public Result<Void> deleteConfigFile(@PathVariable Long id,
                                         @RequestParam(value = "serviceId", required = false)
                                         Long serviceId,
                                         @PathVariable String fileKey,
                                         @CurrentUser AuthUser user) {
        accessService.assertManageProject(id, user);
        Long resolvedServiceId = subServiceManager.resolve(id, serviceId).getId();
        configFileService.delete(resolvedServiceId, fileKey);
        return Result.<Void>ok(null);
    }

    // ---------- 子服务管理（1 项目 N 服务：包/配置/部署按服务作用域） ----------

    /** 子服务列表（默认服务排前）。任何登录成员可见。 */
    @GetMapping("/{id}/sub-services")
    public Result<List<ProjectSubServiceVO>> listSubServices(@PathVariable Long id,
                                                             @CurrentUser AuthUser user) {
        accessService.assertView(id, user);
        return Result.ok(subServiceManager.listByProject(id, user).stream()
                .map(ProjectSubServiceVO::from)
                .collect(Collectors.toList()));
    }

    /** 新建子服务（同项目内 name 唯一；pathKey 由 name slug 化自动推导）。 */
    @PostMapping("/{id}/sub-services")
    @RequireRole(Role.OPS)
    @Auditable(action = "SUB_SERVICE_CREATE", targetType = "PROJECT")
    public ResponseEntity<Result<ProjectSubServiceVO>> createSubService(
            @PathVariable Long id,
            @Valid @RequestBody SubServiceCreateRequest req,
            @CurrentUser AuthUser user) {
        ProjectSubServiceEntity created = subServiceManager.create(
                id, req.getName(), req.getStartCmd(), req.getPort(), user);
        return ResponseEntity.status(201).body(Result.ok(ProjectSubServiceVO.from(created)));
    }

    /** 删除子服务（默认服务不可删；级联清理其包/配置/部署/实例记录）。 */
    @DeleteMapping("/{id}/sub-services/{serviceId}")
    @RequireRole(Role.OPS)
    @Auditable(action = "SUB_SERVICE_DELETE", targetType = "PROJECT")
    public Result<Void> deleteSubService(@PathVariable Long id,
                                         @PathVariable Long serviceId,
                                         @CurrentUser AuthUser user) {
        subServiceManager.delete(id, serviceId, user);
        return Result.<Void>ok(null);
    }

    // ---------- 部署包内文件树 / 单文件读取（方案 C：前端目录树 + 从包导入配置） ----------

    /** 列出部署包内文件/目录相对路径清单（正斜杠，目录带 '/' 尾缀），供前端渲染目录树。 */
    @GetMapping("/packages/{versionId}/tree")
    public Result<List<String>> packageTree(@PathVariable Long versionId,
                                            @CurrentUser AuthUser user) {
        PackageVersionEntity entity = projectService.getVersionEntity(versionId, user);
        return Result.ok(configFileGenerator.packageTree(entity));
    }

    /** 读取部署包内单个文件内容（relPath 与 /tree 返回的相对路径一致），供"从包导入配置"预填编辑框。 */
    @GetMapping("/packages/{versionId}/file")
    public Result<java.util.Map<String, String>> packageFile(@PathVariable Long versionId,
                                                             @RequestParam String relPath,
                                                             @CurrentUser AuthUser user) {
        PackageVersionEntity entity = projectService.getVersionEntity(versionId, user);
        String content = configFileGenerator.readPackageFile(entity, relPath);
        if (content == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "package file not found or unreadable: " + relPath);
        }
        return Result.ok(java.util.Map.of("relPath", relPath, "content", content));
    }
}
