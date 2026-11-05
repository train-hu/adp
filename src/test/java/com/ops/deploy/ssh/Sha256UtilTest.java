package com.ops.deploy.ssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sha256Util 纯逻辑单测：已知向量校验 + 常量大小写一致性（与远端 sha256sum 输出对比依赖小写 hex）。
 */
class Sha256UtilTest {

    /** 标准测试向量：SHA-256("abc") */
    private static final String ABC_HEX =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void hexOfKnownVector_matches() {
        assertThat(Sha256Util.hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(ABC_HEX);
    }

    @Test
    void hexOfEmptyInput_matchesKnownVector() {
        // SHA-256("")
        assertThat(Sha256Util.hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hexIsLowercaseAndFixedLength() {
        String h = Sha256Util.hex("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(h).hasSize(64).doesNotContain("A", "B", "C", "D", "E", "F");
    }

    @Test
    void equalsHexCaseInsensitive_isTrueForSameDigest() {
        assertThat(Sha256Util.equalsHex(ABC_HEX, ABC_HEX.toUpperCase())).isTrue();
        assertThat(Sha256Util.equalsHex(ABC_HEX, "deadbeef")).isFalse();
    }

    @Test
    void extractFromSha256sumOutput_parsesHashPart() {
        // 真实 sha256sum 输出格式："hash  filename"（两个空格）
        assertThat(Sha256Util.extractFromSha256sumOutput(ABC_HEX + "  /data/app.jar.uploading"))
                .isEqualTo(ABC_HEX);
        // 单空格 / 多行也兼容
        assertThat(Sha256Util.extractFromSha256sumOutput(ABC_HEX + " /data/a\nMORE")).isEqualTo(ABC_HEX);
    }

    @Test
    void extractFromSha256sumOutput_invalidInput_returnsNull() {
        assertThat(Sha256Util.extractFromSha256sumOutput(null)).isNull();
        assertThat(Sha256Util.extractFromSha256sumOutput("")).isNull();
        assertThat(Sha256Util.extractFromSha256sumOutput("no-space-here")).isNull();
        assertThat(Sha256Util.extractFromSha256sumOutput("short  file")).isNull();
    }
}
