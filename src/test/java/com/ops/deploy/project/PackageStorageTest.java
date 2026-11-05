package com.ops.deploy.project;

import com.ops.deploy.ssh.Sha256Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PackageStorage 单测：真实临时目录 + 真实文件 I/O（无 Spring）。
 * 覆盖：流式 store 后 sha256 正确、确定性路径（相同内容+版本同路径可覆盖）、
 * 非法 fileName sanitize、open 读取字节一致、delete 删干净。
 */
class PackageStorageTest {

    @TempDir
    Path tempRoot;

    private PackageStorage storage;

    @BeforeEach
    void setUp() {
        storage = new PackageStorage(tempRoot);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void storeTemp_shouldStreamAndComputeCorrectSha256() throws Exception {
        byte[] payload = "hello storage".getBytes(StandardCharsets.UTF_8);

        PackageStorage.TempFile temp = storage.storeTemp("app.zip", new ByteArrayInputStream(payload));

        assertThat(temp.getSha256()).isEqualTo(Sha256Util.hex(payload));
        assertThat(temp.getSizeBytes()).isEqualTo(payload.length);
        assertThat(temp.getFileName()).isEqualTo("app.zip");
        assertThat(Files.exists(temp.getTempPath())).isTrue();

        // 流式落盘内容一致
        try (InputStream in = Files.newInputStream(temp.getTempPath())) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
        // 注意：storeTemp 阶段临时文件（.part）本就存在，moveIntoPlace 测试会验证其被移走
    }

    @Test
    void moveIntoPlace_shouldProduceDeterministicPathAndOpenReadsConsistentBytes() throws Exception {
        byte[] payload = bytes("deterministic-content-123");
        PackageStorage.TempFile temp = storage.storeTemp("app.zip", new ByteArrayInputStream(payload));
        String sha = temp.getSha256();

        String path = storage.moveIntoPlace(temp, 7L, 42L, "app.zip");

        // 布局：packages/{projectId}/{versionId}-{sha12}/{fileName}
        String expected = "packages/7/42-" + sha.substring(0, 12) + "/app.zip";
        assertThat(path).isEqualTo(expected);

        // 绝对路径存在
        Path abs = tempRoot.resolve(path);
        assertThat(Files.exists(abs)).isTrue();

        try (InputStream in = storage.open(path)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
        // 临时文件已被移走
        assertThat(Files.exists(temp.getTempPath())).isFalse();
    }

    @Test
    void moveIntoPlace_sameContentSameVersion_shouldReuseSamePathAndOverwrite() throws Exception {
        byte[] payload = bytes("same-content");

        PackageStorage.TempFile t1 = storage.storeTemp("pkg.bin", new ByteArrayInputStream(payload));
        String p1 = storage.moveIntoPlace(t1, 1L, 1L, "pkg.bin");

        PackageStorage.TempFile t2 = storage.storeTemp("pkg.bin", new ByteArrayInputStream(payload));
        String p2 = storage.moveIntoPlace(t2, 1L, 1L, "pkg.bin");

        // 相同内容 → 相同 sha → 相同 versionId 下路径一致（覆盖语义）
        assertThat(p1).isEqualTo(p2);
        assertThat(Files.exists(tempRoot.resolve(p1))).isTrue();
        try (InputStream in = storage.open(p1)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void sanitizeFileName_shouldKeepBasenameAndStripIllegalChars() {
        assertThat(storage.sanitizeFileName("../../etc/passwd")).isNotBlank();
        // 只保留 basename，非法字符替换
        String s = storage.sanitizeFileName("../../weird name?.zip");
        assertThat(s).doesNotContain("..");
        assertThat(s).doesNotContain("/");
        assertThat(s).doesNotContain("\\");
        assertThat(s).doesNotContain("?");
        assertThat(s).endsWith(".zip");

        // 合法名原样保留
        assertThat(storage.sanitizeFileName("app-v1.2.3.tar.gz")).isEqualTo("app-v1.2.3.tar.gz");

        // 全非法 → 兜底
        assertThat(storage.sanitizeFileName("///")).isNotBlank();
    }

    @Test
    void storeTemp_illegalFileName_shouldSanitize() throws Exception {
        byte[] payload = bytes("x");
        PackageStorage.TempFile temp = storage.storeTemp("..\\..\\evil.sh", new ByteArrayInputStream(payload));

        assertThat(temp.getFileName()).doesNotContain("..");
        assertThat(temp.getFileName()).doesNotContain("/");
    }

    @Test
    void open_missingPath_shouldThrowNotFoundLike() {
        assertThatThrownBy(() -> storage.open("packages/1/1-abc/app.zip").close())
                .isInstanceOf(com.ops.deploy.common.BizException.class)
                .satisfies(e -> assertThat(((com.ops.deploy.common.BizException) e).getCode())
                        .isEqualTo(com.ops.deploy.common.ErrorCode.NOT_FOUND));
    }

    @Test
    void delete_shouldRemoveFileAndDirectory() throws Exception {
        byte[] payload = bytes("to-delete");
        PackageStorage.TempFile temp = storage.storeTemp("app.zip", new ByteArrayInputStream(payload));
        String path = storage.moveIntoPlace(temp, 3L, 9L, "app.zip");
        Path abs = tempRoot.resolve(path);
        assertThat(Files.exists(abs)).isTrue();

        storage.delete(path);

        assertThat(Files.exists(abs)).isFalse();
        // 空版本目录也清掉
        Path versionDir = abs.getParent();
        assertThat(Files.exists(versionDir)).isFalse();
    }

    @Test
    void storeTemp_differentContent_shouldProduceDifferentSha() throws Exception {
        PackageStorage.TempFile a = storage.storeTemp("f.bin", new ByteArrayInputStream(bytes("aaa")));
        PackageStorage.TempFile b = storage.storeTemp("f.bin", new ByteArrayInputStream(bytes("bbb")));

        assertThat(a.getSha256()).isNotEqualTo(b.getSha256());
    }
}
