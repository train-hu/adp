package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 分片上传服务：大文件（浏览器直传 2GB 受 multipart 限制/网络不稳）按固定大小切片上传，
 * 支持断点续传（重传前查询已传分片，跳过）。
 *
 * <p>流程：
 * <ol>
 *   <li>{@link #init}：创建会话（UUID id），分片元数据落 upload_session 表；</li>
 *   <li>{@link #uploadChunk}：逐片存 {@code {data-dir}/.uploads/{sessionId}/{index}.part}；</li>
 *   <li>{@link #progress}：返回已传分片索引（断点续传跳过判断）；</li>
 *   <li>{@link #complete}：全片到齐后流式合并 + 实算 SHA-256，与前端声明值比对，
 *       通过后复用 {@link ProjectService#uploadMergedPackage} 标准落库链路，
 *       成功删分片，失败保留分片（可重传 / 重新 complete）。</li>
 * </ol>
 *
 * <p>完整性以服务端合并后实算的 SHA-256 为准：前端声明值不匹配 → BAD_REQUEST，
 * 不依赖客户端计算正确性。
 */
@Service
public class ChunkUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);

    /** 最小分片大小 1MB（过小片数过多）。 */
    private static final long MIN_CHUNK_SIZE = 1L * 1024 * 1024;
    /** 最大分片大小 128MB（单片请求不宜过大，兼顾断点续传粒度）。 */
    private static final long MAX_CHUNK_SIZE = 128L * 1024 * 1024;
    /** 最多分片数（兜底防止超大 totalChunks 导致合并循环失控）。 */
    private static final int MAX_CHUNKS = 5000;

    private final UploadSessionRepository sessionRepository;
    private final ProjectAccessService accessService;
    private final PackageStorage storage;
    private final ProjectService projectService;
    private final ProjectSubServiceManager projectSubServiceManager;
    private final long maxUploadSize;

    public ChunkUploadService(UploadSessionRepository sessionRepository,
                              ProjectAccessService accessService,
                              PackageStorage storage,
                              ProjectService projectService,
                              ProjectSubServiceManager projectSubServiceManager,
                              @Value("${deploy.max-upload-size:2147483648}") long maxUploadSize) {
        this.sessionRepository = sessionRepository;
        this.accessService = accessService;
        this.storage = storage;
        this.projectService = projectService;
        this.projectSubServiceManager = projectSubServiceManager;
        this.maxUploadSize = maxUploadSize;
    }

    /**
     * 创建分片上传会话。fileName 原样保留（落库前统一 sanitize），仅要求非空。
     *
     * @param serviceId 目标子服务 id；null/0 = 项目默认子服务（落库时解析为实际 id）
     */
    @Transactional
    public String init(Long projectId, Long serviceId, String fileName, long totalSize,
                       long chunkSize, String declaredSha256, AuthUser user) {
        accessService.assertOperate(projectId, user);
        // 解析子服务（null/0 → 默认），落库实际 id，complete 时按此 id 落版本
        Long resolvedServiceId = projectSubServiceManager.resolve(projectId, serviceId).getId();
        if (fileName == null || fileName.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "fileName is required");
        }
        if (totalSize <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "totalSize must be positive");
        }
        if (totalSize > maxUploadSize) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "file too large (max " + maxUploadSize + " bytes)");
        }
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "chunkSize out of range [" + MIN_CHUNK_SIZE + ", " + MAX_CHUNK_SIZE + "]");
        }
        int totalChunks = (int) ((totalSize + chunkSize - 1) / chunkSize);
        if (totalChunks > MAX_CHUNKS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "too many chunks (max " + MAX_CHUNKS + ")");
        }

        UploadSessionEntity session = new UploadSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setProjectId(projectId);
        session.setServiceId(resolvedServiceId);
        session.setFilename(fileName);
        session.setTotalSize(totalSize);
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setSha256(normalizeSha(declaredSha256));
        session.setStatus("UPLOADING");
        LocalDateTime now = LocalDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionRepository.save(session);
        return session.getId();
    }

    /** 上传单个分片（幂等：同一 index 重传覆盖）。 */
    public void uploadChunk(Long projectId, String sessionId, int index,
                            MultipartFile file, AuthUser user) throws IOException {
        UploadSessionEntity session = requireSession(projectId, sessionId, "UPLOADING");
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "chunk file is empty");
        }
        if (index < 0 || index >= session.getTotalChunks()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "chunk index out of range [0, " + (session.getTotalChunks() - 1) + "]");
        }
        // 末片允许不足 chunkSize；其余片要求恰好等于 chunkSize（多退少补由前端切片保证）
        long expectedLast = session.getTotalSize() - (long) (session.getTotalChunks() - 1) * session.getChunkSize();
        long expectedSize = index == session.getTotalChunks() - 1 ? expectedLast : session.getChunkSize();
        if (file.getSize() != expectedSize) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "chunk size mismatch (expected " + expectedSize + ", got " + file.getSize() + ")");
        }
        try (InputStream in = file.getInputStream()) {
            storage.storeChunk(sessionId, index, in);
        }
    }

    /** 已传分片索引（升序），供前端断点续传跳过。会话不存在/已结束 → 空列表。 */
    public List<Integer> progress(Long projectId, String sessionId, AuthUser user) {
        return sessionRepository.findByIdAndProjectId(sessionId, projectId)
                .filter(s -> "UPLOADING".equals(s.getStatus()))
                .map(s -> storage.uploadedChunkIndexes(sessionId))
                .orElse(List.of());
    }

    /**
     * 完成合并：校验全片到齐 → 流式合并 + 实算 sha → 与声明值比对 →
     * 复用标准落库链路（去重/识别/两阶段落盘）。
     *
     * <p>失败处理：分片与合并临时文件均保留——内容错误时客户端修正后可重新 complete；
     * 落库阶段（uploadMergedPackage）的临时文件由该方法自行清理。
     */
    @Transactional
    public PackageVersionVO complete(Long projectId, String sessionId, String declaredSha256,
                                     String versionNote, AuthUser user) throws IOException {
        UploadSessionEntity session = requireSession(projectId, sessionId, "UPLOADING");
        List<Integer> indexes = storage.uploadedChunkIndexes(sessionId);
        if (indexes.size() < session.getTotalChunks()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "chunks incomplete (" + indexes.size() + "/" + session.getTotalChunks() + ")");
        }

        // 全部 0..n-1 必须到齐（防 index 复用造成内容错乱：如 [0,1,3] 凑够 3 片但缺 2）
        for (int i = 0; i < session.getTotalChunks(); i++) {
            if (!indexes.contains(i)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "chunk " + i + " missing");
            }
        }

        List<Integer> ordered = new java.util.ArrayList<>(session.getTotalChunks());
        for (int i = 0; i < session.getTotalChunks(); i++) {
            ordered.add(i);
        }
        PackageStorage.TempFile temp =
                storage.mergeChunks(sessionId, ordered, session.getFilename());
        try {
            String expected = normalizeSha(session.getSha256() != null
                    ? session.getSha256() : declaredSha256);
            if (expected != null && !expected.equals(temp.getSha256())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "sha256 mismatch (merged package differs from declared value)");
            }
            PackageVersionVO vo = projectService.uploadMergedPackage(
                    projectId, session.getServiceId(), temp, session.getFilename(),
                    versionNote, user);
            markStatus(session, "COMPLETED");
            storage.deleteChunkSession(sessionId);
            return vo;
        } catch (RuntimeException | IOException e) {
            // 落库失败：清理合并临时文件，保留分片供重传
            storage.deleteTemp(temp);
            markStatus(session, "FAILED");
            log.warn("chunk upload complete failed for session {}: {}", sessionId, e.getMessage());
            throw e;
        }
    }

    private void markStatus(UploadSessionEntity session, String status) {
        session.setStatus(status);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    private UploadSessionEntity requireSession(Long projectId, String sessionId, String expectedStatus) {
        UploadSessionEntity session = sessionRepository.findByIdAndProjectId(sessionId, projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "upload session not found"));
        if (!expectedStatus.equals(session.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "upload session is " + session.getStatus() + ", expected " + expectedStatus);
        }
        return session;
    }

    /** 归一化 sha（小写 trim）；空 → null（不做比对）。 */
    private static String normalizeSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return null;
        }
        return sha.trim().toLowerCase();
    }
}
