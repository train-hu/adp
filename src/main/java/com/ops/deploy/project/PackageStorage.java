package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 部署包文件存储：
 * <ul>
 *   <li>根目录 {@code deploy.data-dir}（如 {@code data}）下的
 *       {@code packages/{projectId}/{versionId}-{sha12}/{fileName}} 布局</li>
 *   <li>{@link #storeTemp}：流式写临时文件（.part）+ 流式 SHA-256，返回临时文件信息</li>
 *   <li>{@link #moveIntoPlace}：获得版本 id 后 rename 到正式路径，返回相对根目录的路径（落库）</li>
 *   <li>{@link #open}：流式读取（供下载）</li>
 *   <li>{@link #delete}：删除版本文件及其版本目录</li>
 *   <li>{@link #sanitizeFileName}：只保留 basename，替换非法字符（防路径穿越）</li>
 * </ul>
 *
 * <p>两阶段设计（先临时存储再 rename）：因为正式路径需要 versionId（数据库自增 id），
 * 只有 save 后才能获得。
 */
@Component
public class PackageStorage {

    private static final Logger log = LoggerFactory.getLogger(PackageStorage.class);

    private final Path rootDir;

    /** Spring 注入：{@code deploy.data-dir}（如 {@code data}）作为存储根目录。 */
    @Autowired
    public PackageStorage(@Value("${deploy.data-dir:data}") String dataDir) {
        this(Paths.get(dataDir));
    }

    /** 供单测直接指定临时根目录。 */
    public PackageStorage(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data dir: " + rootDir, e);
        }
    }

    /** 临时文件信息（storeTemp 返回）。 */
    public static class TempFile {
        private Path tempPath;
        private String sha256;
        private long sizeBytes;
        private String fileName;

        public Path getTempPath() {
            return tempPath;
        }

        public void setTempPath(Path tempPath) {
            this.tempPath = tempPath;
        }

        public String getSha256() {
            return sha256;
        }

        public void setSha256(String sha256) {
            this.sha256 = sha256;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }
    }

    /**
     * 流式写入临时文件并计算 SHA-256。
     *
     * <p>临时文件写到 {@code {rootDir}/.tmp/{uuid}.part}，避免与正式路径冲突。
     * 文件名 sanitize 后用于后续 moveIntoPlace。
     */
    public TempFile storeTemp(String originalFileName, InputStream in)
            throws IOException {
        String safeName = sanitizeFileName(originalFileName);
        Path tmpDir = rootDir.resolve(".tmp");
        Files.createDirectories(tmpDir);
        Path tempPath = Files.createTempFile(tmpDir, "pkg-", ".part");

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long written = 0;
            byte[] buf = new byte[8192];
            try (OutputStream out = Files.newOutputStream(tempPath)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    written += n;
                }
            }
            String sha = hex(md.digest());

            TempFile tf = new TempFile();
            tf.setTempPath(tempPath);
            tf.setSha256(sha);
            tf.setSizeBytes(written);
            tf.setFileName(safeName);
            return tf;
        } catch (NoSuchAlgorithmException e) {
            Files.deleteIfExists(tempPath);
            throw new IOException("SHA-256 not available", e);
        } catch (IOException e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }
    }

    /**
     * 计算正式路径 {@code packages/{projectId}/{versionId}-{sha12}/{fileName}}（不移动文件）。
     * 供上传流程在首次落库前预填 storagePath（该列 NOT NULL，move 只能发生在拿到
     * versionId 之后，而 id 又依赖首次落库）。
     */
    public String buildRelativePath(long projectId, long versionId, String sha256, String fileName) {
        String safeName = sanitizeFileName(fileName);
        String sha12 = sha256.substring(0, 12);
        return "packages/" + projectId + "/" + versionId + "-" + sha12 + "/" + safeName;
    }

    /**
     * 把临时文件 move 到正式路径 {@code packages/{projectId}/{versionId}-{sha12}/{fileName}}。
     * 返回相对根目录的 POSIX 路径字符串（落库用）。
     */
    public String moveIntoPlace(TempFile temp, long projectId, long versionId, String fileName)
            throws IOException {
        String relPath = buildRelativePath(projectId, versionId, temp.getSha256(), fileName);
        Path absPath = rootDir.resolve(relPath);

        Files.createDirectories(absPath.getParent());
        Files.move(temp.getTempPath(), absPath, StandardCopyOption.REPLACE_EXISTING);

        log.debug("stored package: {}", relPath);
        return relPath;
    }

    /**
     * 流式打开版本文件（供下载）。
     *
     * @param relPath 相对根目录的路径（落库值）
     * @return 输入流
     */
    public InputStream open(String relPath) throws IOException {
        Path absPath = resolve(relPath);
        if (!Files.exists(absPath)) {
            throw new BizException(ErrorCode.NOT_FOUND, "package file not found: " + relPath);
        }
        return Files.newInputStream(absPath);
    }

    /**
     * 删除版本文件及其版本目录（空目录才删，不递归）。
     */
    public void delete(String relPath) {
        try {
            Path absPath = resolve(relPath);
            Files.deleteIfExists(absPath);
            // 尝试删除空版本目录
            Path versionDir = absPath.getParent();
            if (versionDir != null && Files.isDirectory(versionDir) && isEmptyDir(versionDir)) {
                Files.deleteIfExists(versionDir);
            }
            // 尝试删除空 project 目录
            Path projectDir = versionDir != null ? versionDir.getParent() : null;
            if (projectDir != null && Files.isDirectory(projectDir) && isEmptyDir(projectDir)) {
                Files.deleteIfExists(projectDir);
            }
            log.debug("deleted package: {}", relPath);
        } catch (IOException e) {
            log.warn("failed to delete package file: {}", relPath, e);
        }
    }

    /** 删除临时文件（上传失败/冲突时清理）。 */
    public void deleteTemp(TempFile temp) {
        if (temp == null || temp.getTempPath() == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp.getTempPath());
        } catch (IOException e) {
            log.warn("failed to delete temp file: {}", temp.getTempPath(), e);
        }
    }

    /**
     * 文件名 sanitize：只保留 basename（去掉路径前缀），非法字符替换为下划线。
     * 空/全非法 → "package"。
     */
    public String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "package";
        }
        // 统一正斜杠，取最后一段（basename）
        String normalized = name.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String base = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

        // 替换非法字符
        StringBuilder sb = new StringBuilder(base.length());
        for (char c : base.toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString();
        if (result.isEmpty() || result.equals(".") || result.equals("..")) {
            return "package";
        }
        return result;
    }

    // ---------- 分片上传 ----------

    /** 分片目录：{@code {rootDir}/.uploads/{sessionId}}（sessionId 由服务端生成，UUID 无需 sanitize）。 */
    public Path chunkDir(String sessionId) {
        return rootDir.resolve(".uploads").resolve(sessionId);
    }

    /**
     * 存一个分片到 {@code .uploads/{sessionId}/{index}.part}，返回该分片绝对路径。
     * index 越界校验由调用方（ChunkUploadService）负责。
     */
    public Path storeChunk(String sessionId, int index, InputStream in) throws IOException {
        Path dir = chunkDir(sessionId);
        Files.createDirectories(dir);
        Path part = dir.resolve(index + ".part");
        Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        return part;
    }

    /** 会话已传分片索引（断点续传跳过判断用）。 */
    public List<Integer> uploadedChunkIndexes(String sessionId) {
        Path dir = chunkDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".part"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return Integer.parseInt(name.substring(0, name.length() - ".part".length()));
                    })
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 按 index 顺序把所有分片流式合并到 {@code .tmp/pkg-*.part}，流式计算 SHA-256。
     *
     * @param sessionId 会话 id
     * @param indexes   全部分片索引（调用方保证连续 0..totalChunks-1 且均存在）
     * @param fileName  原始文件名（TempFile.fileName，sanitize 后）
     * @return 合并后的临时文件信息（路径 + sha + 总大小）
     */
    public TempFile mergeChunks(String sessionId, List<Integer> indexes, String fileName)
            throws IOException {
        String safeName = sanitizeFileName(fileName);
        Path tmpDir = rootDir.resolve(".tmp");
        Files.createDirectories(tmpDir);
        Path merged = Files.createTempFile(tmpDir, "pkg-", ".part");

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long written = 0;
            try (OutputStream out = Files.newOutputStream(merged)) {
                for (Integer index : indexes) {
                    Path part = chunkDir(sessionId).resolve(index + ".part");
                    try (InputStream in = Files.newInputStream(part)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            md.update(buf, 0, n);
                            written += n;
                        }
                    }
                }
            }
            TempFile tf = new TempFile();
            tf.setTempPath(merged);
            tf.setSha256(hex(md.digest()));
            tf.setSizeBytes(written);
            tf.setFileName(safeName);
            return tf;
        } catch (NoSuchAlgorithmException e) {
            Files.deleteIfExists(merged);
            throw new IOException("SHA-256 not available", e);
        } catch (IOException e) {
            Files.deleteIfExists(merged);
            throw e;
        }
    }

    /** 删除整个会话分片目录（递归；删除失败仅告警）。 */
    public void deleteChunkSession(String sessionId) {
        Path dir = chunkDir(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("failed to delete chunk file: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("failed to walk chunk dir: {}", dir, e);
        }
    }

    /**
     * 删除会话分片目录 {@code .uploads/{sessionId}}（递归）。
     *
     * <p>幂等：目录不存在直接跳过；删除失败仅 log.warn 不抛出（定时清理调用，
     * 残留目录由下一轮清理重试）。与 {@link #chunkDir} 同一目录规则。
     */
    public void deleteUploadSessionDir(String sessionId) {
        deleteChunkSession(sessionId);
    }

    // ---------- 内部工具 ----------

    private Path resolve(String relPath) {
        Path p = rootDir.resolve(relPath).normalize();
        if (!p.startsWith(rootDir)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid storage path: " + relPath);
        }
        return p;
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return !s.findFirst().isPresent();
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
