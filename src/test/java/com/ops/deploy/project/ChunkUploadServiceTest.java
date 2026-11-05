package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.security.AuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChunkUploadService 单测：mock 仓库/鉴权/ProjectService，PackageStorage 用真实实例
 * （@TempDir），覆盖 init 校验、分片存取、断点续传进度、合并 sha 比对、
 * 落库失败保留分片等路径。
 */
@ExtendWith(MockitoExtension.class)
class ChunkUploadServiceTest {

    private static final long MAX_UPLOAD = 2L * 1024 * 1024 * 1024;
    private static final long CHUNK = 10L * 1024 * 1024; // 10MB（≥1MB 下限）

    @Mock
    private UploadSessionRepository sessionRepository;

    @Mock
    private ProjectAccessService accessService;

    @Mock
    private ProjectService projectService;

    // 新增：子服务解析 mock（init 解析 serviceId → 默认子服务）
    @Mock
    private ProjectSubServiceManager projectSubServiceManager;

    private PackageStorage storage;

    private ChunkUploadService service;

    private final AuthUser ops = new AuthUser(8L, "ops", "OPS");

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        storage = new PackageStorage(tempDir);
        service = new ChunkUploadService(sessionRepository, accessService, storage,
                projectService, projectSubServiceManager, MAX_UPLOAD);
        // init 先解析子服务（null/0 → 默认）再做参数校验：返回默认子服务（id=1，pathKey=null）
        org.mockito.Mockito.lenient()
                .when(projectSubServiceManager.resolve(any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    ProjectSubServiceEntity e = new ProjectSubServiceEntity();
                    e.setId(1L);
                    e.setProjectId(1L);
                    e.setName("p1");
                    e.setPathKey(null);
                    return e;
                });
    }

    private UploadSessionEntity session(int chunks, long totalSize, String status) {
        UploadSessionEntity s = new UploadSessionEntity();
        s.setId("sess-1");
        s.setProjectId(1L);
        s.setFilename("app.zip");
        s.setTotalSize(totalSize);
        s.setChunkSize(CHUNK);
        s.setTotalChunks(chunks);
        s.setSha256(null);
        s.setStatus(status);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    private static byte[] chunkContent(int index) {
        // 每片内容唯一且恰好 CHUNK 字节
        byte[] buf = new byte[(int) CHUNK];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) (index + i % 17);
        }
        return buf;
    }

    private static String sha256(byte[]... parts) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (byte[] p : parts) {
            md.update(p);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ---------- init ----------

    @Test
    void init_success_shouldPersistSessionWithComputedChunkCount() {
        when(sessionRepository.save(any(UploadSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String id = service.init(1L, null, "app.zip", CHUNK * 2 + 512, CHUNK, null, ops);

        assertThat(id).isNotBlank();
        ArgumentCaptor<UploadSessionEntity> captor = ArgumentCaptor.forClass(UploadSessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        UploadSessionEntity saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo(1L);
        assertThat(saved.getFilename()).isEqualTo("app.zip");
        assertThat(saved.getTotalSize()).isEqualTo(CHUNK * 2 + 512);
        assertThat(saved.getChunkSize()).isEqualTo(CHUNK);
        assertThat(saved.getTotalChunks()).isEqualTo(3); // ceil(2.05)
        assertThat(saved.getStatus()).isEqualTo("UPLOADING");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void init_invalidParams_shouldReject() {
        // fileName 空
        assertThatThrownBy(() -> service.init(1L, null, "  ", CHUNK, CHUNK, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // totalSize 非正
        assertThatThrownBy(() -> service.init(1L, null, "a.zip", 0, CHUNK, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // chunkSize 超范围
        assertThatThrownBy(() -> service.init(1L, null, "a.zip", 1024, 512, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // 超过 maxUploadSize
        assertThatThrownBy(() -> service.init(1L, null, "a.zip", MAX_UPLOAD + 1, CHUNK, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(sessionRepository, never()).save(any(UploadSessionEntity.class));
    }

    // ---------- uploadChunk ----------

    @Test
    void uploadChunk_success_shouldStorePart() throws Exception {
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L))
                .thenReturn(Optional.of(session(3, CHUNK * 2 + 512, "UPLOADING")));
        MockMultipartFile file = new MockMultipartFile("file", "chunk",
                "application/octet-stream", chunkContent(1));

        service.uploadChunk(1L, "sess-1", 1, file, ops);

        Path part = storage.chunkDir("sess-1").resolve("1.part");
        assertThat(part).exists();
        assertThat(java.nio.file.Files.size(part)).isEqualTo(CHUNK);
    }

    @Test
    void uploadChunk_badIndexOrSize_shouldReject() throws Exception {
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L))
                .thenReturn(Optional.of(session(3, CHUNK * 2 + 512, "UPLOADING")));
        // index 越界
        assertThatThrownBy(() -> service.uploadChunk(1L, "sess-1", 3,
                new MockMultipartFile("file", "c", "application/octet-stream", chunkContent(0)), ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // 非末片大小不等于 chunkSize
        byte[] small = new byte[100];
        assertThatThrownBy(() -> service.uploadChunk(1L, "sess-1", 0,
                new MockMultipartFile("file", "c", "application/octet-stream", small), ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // 空文件
        assertThatThrownBy(() -> service.uploadChunk(1L, "sess-1", 0,
                new MockMultipartFile("file", "c", "application/octet-stream", new byte[0]), ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void uploadChunk_sessionNotFoundOrNotUploading_shouldReject() {
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "c", "application/octet-stream",
                chunkContent(0));
        assertThatThrownBy(() -> service.uploadChunk(1L, "sess-1", 0, file, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));

        when(sessionRepository.findByIdAndProjectId("sess-1", 1L))
                .thenReturn(Optional.of(session(3, CHUNK * 3, "COMPLETED")));
        assertThatThrownBy(() -> service.uploadChunk(1L, "sess-1", 0, file, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    // ---------- progress ----------

    @Test
    void progress_shouldReturnUploadedIndexes() throws Exception {
        // 真实落盘 0/2 两片（跳过 1，模拟断点续传场景）
        storage.storeChunk("sess-1", 0, new ByteArrayInputStream(chunkContent(0)));
        storage.storeChunk("sess-1", 2, new ByteArrayInputStream(new byte[512]));
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L))
                .thenReturn(Optional.of(session(3, CHUNK * 2 + 512, "UPLOADING")));

        assertThat(service.progress(1L, "sess-1", ops)).containsExactly(0, 2);
    }

    @Test
    void progress_sessionFinishedOrMissing_shouldReturnEmpty() {
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L))
                .thenReturn(Optional.of(session(3, CHUNK * 3, "COMPLETED")));
        assertThat(service.progress(1L, "sess-1", ops)).isEmpty();

        when(sessionRepository.findByIdAndProjectId("nope", 1L)).thenReturn(Optional.empty());
        assertThat(service.progress(1L, "nope", ops)).isEmpty();
    }

    // ---------- complete ----------

    @Test
    void complete_success_shouldMergeVerifyShaAndUpload() throws Exception {
        byte[] c0 = chunkContent(0);
        byte[] c1 = chunkContent(1);
        byte[] c2 = new byte[512];
        String fullSha = sha256(c0, c1, c2);
        UploadSessionEntity s = session(3, CHUNK * 2 + 512, "UPLOADING");
        s.setSha256(fullSha.toUpperCase()); // 大写声明值也应比对通过
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L)).thenReturn(Optional.of(s));
        for (int i = 0; i < 3; i++) {
            byte[] content = i == 2 ? c2 : chunkContent(i);
            storage.storeChunk("sess-1", i, new ByteArrayInputStream(content));
        }
        PackageStorage.TempFile merged = new PackageStorage.TempFile();
        merged.setSha256(fullSha);
        merged.setSizeBytes(CHUNK * 2 + 512);
        merged.setFileName("app.zip");
        merged.setTempPath(Path.of("/tmp/merged.part"));
        when(projectService.uploadMergedPackage(eq(1L), eq(0L), any(PackageStorage.TempFile.class),
                eq("app.zip"), eq("v1"), any(AuthUser.class)))
                .thenReturn(new PackageVersionVO());

        service.complete(1L, "sess-1", null, "v1", ops);

        ArgumentCaptor<PackageStorage.TempFile> tempCaptor =
                ArgumentCaptor.forClass(PackageStorage.TempFile.class);
        verify(projectService).uploadMergedPackage(eq(1L), eq(0L), tempCaptor.capture(), eq("app.zip"),
                eq("v1"), any(AuthUser.class));
        assertThat(tempCaptor.getValue().getSha256()).isEqualTo(fullSha);
        assertThat(tempCaptor.getValue().getSizeBytes()).isEqualTo(CHUNK * 2 + 512);
        // 会话标记 COMPLETED 且分片目录已清理
        ArgumentCaptor<UploadSessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(UploadSessionEntity.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(storage.chunkDir("sess-1")).doesNotExist();
    }

    @Test
    void complete_shaMismatch_shouldFailAndKeepChunks() throws Exception {
        byte[] c0 = chunkContent(0);
        byte[] c1 = chunkContent(1);
        byte[] c2 = new byte[512];
        UploadSessionEntity s = session(3, CHUNK * 2 + 512, "UPLOADING");
        s.setSha256("0".repeat(64)); // 声明值与真实内容不符
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L)).thenReturn(Optional.of(s));
        for (int i = 0; i < 3; i++) {
            byte[] content = i == 2 ? c2 : chunkContent(i);
            storage.storeChunk("sess-1", i, new ByteArrayInputStream(content));
        }

        assertThatThrownBy(() -> service.complete(1L, "sess-1", null, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        verify(projectService, never()).uploadMergedPackage(any(Long.class), any(Long.class), any(), anyString(), any(),
                any(AuthUser.class));
        // 分片保留（可修正声明后重试）；状态 FAILED
        assertThat(storage.chunkDir("sess-1").resolve("0.part")).exists();
        assertThat(storage.chunkDir("sess-1").resolve("2.part")).exists();
        ArgumentCaptor<UploadSessionEntity> captor = ArgumentCaptor.forClass(UploadSessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void complete_persistFailure_shouldDeleteTempKeepChunksAndMarkFailed() throws Exception {
        byte[] c0 = chunkContent(0);
        byte[] c1 = chunkContent(1);
        byte[] c2 = new byte[512];
        UploadSessionEntity s = session(3, CHUNK * 2 + 512, "UPLOADING");
        s.setSha256(null); // 未声明 → 跳过比对
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L)).thenReturn(Optional.of(s));
        for (int i = 0; i < 3; i++) {
            byte[] content = i == 2 ? c2 : chunkContent(i);
            storage.storeChunk("sess-1", i, new ByteArrayInputStream(content));
        }
        when(projectService.uploadMergedPackage(any(), any(Long.class), any(PackageStorage.TempFile.class), anyString(),
                any(), any(AuthUser.class)))
                .thenThrow(new BizException(ErrorCode.CONFLICT, "package with same content already exists"));

        assertThatThrownBy(() -> service.complete(1L, "sess-1", null, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT));

        // 分片保留；状态 FAILED（合并临时文件由 deleteTemp 清理，不残留 .tmp 之外的产物）
        assertThat(storage.chunkDir("sess-1").resolve("1.part")).exists();
        ArgumentCaptor<UploadSessionEntity> captor = ArgumentCaptor.forClass(UploadSessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void complete_incompleteChunks_shouldRejectWithoutMerge() throws Exception {
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L))
                .thenReturn(Optional.of(session(3, CHUNK * 3, "UPLOADING")));
        storage.storeChunk("sess-1", 0, new ByteArrayInputStream(chunkContent(0)));
        // 只传了 1/3 片（但 index 集合恰好凑不齐 0..2）

        assertThatThrownBy(() -> service.complete(1L, "sess-1", null, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(projectService, never()).uploadMergedPackage(any(Long.class), any(Long.class), any(), anyString(), any(),
                any(AuthUser.class));
    }

    @Test
    void complete_missingMiddleChunk_shouldReject() throws Exception {
        // [0,2] 缺中间片 1 → 分片不齐，complete 前即拒绝（不进入合并）
        when(sessionRepository.findByIdAndProjectId("sess-1", 1L))
                .thenReturn(Optional.of(session(3, CHUNK * 2 + 512, "UPLOADING")));
        storage.storeChunk("sess-1", 0, new ByteArrayInputStream(chunkContent(0)));
        storage.storeChunk("sess-1", 2, new ByteArrayInputStream(new byte[512]));

        assertThatThrownBy(() -> service.complete(1L, "sess-1", null, null, ops))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(be.getMessage()).contains("chunks incomplete");
                });
        verify(projectService, never()).uploadMergedPackage(any(Long.class), any(Long.class), any(), anyString(), any(),
                any(AuthUser.class));
    }
}
