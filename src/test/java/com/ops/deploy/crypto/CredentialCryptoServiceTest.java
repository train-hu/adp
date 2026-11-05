package com.ops.deploy.crypto;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-256-GCM 凭证加解密单测（纯 JDK javax.crypto，不连库）。
 *
 * <p>覆盖：roundtrip、随机 IV、密文/IV 篡改、截断、任意长度 KEK（SHA-256 派生）。
 */
class CredentialCryptoServiceTest {

    private CredentialCryptoService crypto;

    @BeforeEach
    void setUp() {
        crypto = new CredentialCryptoService("some-kek");
    }

    @Test
    void roundtrip_shouldRestorePlaintext() {
        String pt = "P@ssw0rd!";

        byte[] ct = crypto.encrypt(pt);
        String out = crypto.decrypt(ct);

        assertThat(out).isEqualTo(pt);
    }

    @Test
    void samePlaintextTwice_shouldProduceDifferentCiphertexts() {
        String pt = "same-plaintext";

        byte[] c1 = crypto.encrypt(pt);
        byte[] c2 = crypto.encrypt(pt);

        // 随机 IV 不复用 → 两次密文不同
        assertThat(c1).isNotEqualTo(c2);
        // 前 12 字节为随机 IV：两次不同
        assertThat(Arrays.copyOfRange(c1, 0, 12)).isNotEqualTo(Arrays.copyOfRange(c2, 0, 12));
        // 但都能解回同一明文
        assertThat(crypto.decrypt(c1)).isEqualTo(pt);
        assertThat(crypto.decrypt(c2)).isEqualTo(pt);
    }

    @Test
    void encryptedOutput_shouldBeAtLeast28Bytes() {
        // 输出 = IV(12B) || ciphertext + tag(16B)；12 + 16 = 28 字节下限
        byte[] ct = crypto.encrypt("abc");
        assertThat(ct.length).isGreaterThanOrEqualTo(28);
    }

    @Test
    void tamperedCiphertext_shouldThrowCredentialError() {
        byte[] ct = crypto.encrypt("top-secret");

        // 篡改密文任意 1 字节（取中间位置，避开 IV）
        ct[ct.length / 2] ^= 0x01;

        assertThatThrownBy(() -> crypto.decrypt(ct))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_ERROR));
    }

    @Test
    void tamperedIv_shouldThrowCredentialError() {
        byte[] ct = crypto.encrypt("top-secret");

        // 篡改 IV（前 12 字节）
        ct[0] ^= 0x01;

        assertThatThrownBy(() -> crypto.decrypt(ct))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_ERROR));
    }

    @Test
    void truncatedBlob_shouldThrowCredentialError() {
        // 长度 < 28（IV 12 + tag 16）→ 非法
        byte[] tooShort = new byte[20];
        assertThatThrownBy(() -> crypto.decrypt(tooShort))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_ERROR));
    }

    @Test
    void differentKeys_shouldProduceIncompatibleCiphertexts() {
        CredentialCryptoService other = new CredentialCryptoService("another-kek");
        byte[] ct = crypto.encrypt("top-secret");

        assertThatThrownBy(() -> other.decrypt(ct))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_ERROR));
    }

    @Test
    void arbitraryLengthKek_shouldBeUsable() {
        // SHA-256 派生 → 任意长度 KEK 都可用（占位值 35 字节、短 KEK 均覆盖）
        CredentialCryptoService longKek = new CredentialCryptoService("dev-only-32-byte-kek-please-change!");
        byte[] ct = longKek.encrypt("secret");
        assertThat(longKek.decrypt(ct)).isEqualTo("secret");

        CredentialCryptoService shortKek = new CredentialCryptoService("31-bytes-0123456789abcdef012345678");
        byte[] ct2 = shortKek.encrypt("secret");
        assertThat(shortKek.decrypt(ct2)).isEqualTo("secret");
    }

    @Test
    void unicodePlaintext_shouldRoundtrip() {
        String pt = "密码-pässwörd-🔐";
        byte[] ct = crypto.encrypt(pt);
        assertThat(crypto.decrypt(ct)).isEqualTo(pt);
        // 确认 UTF-8 字节处理一致
        assertThat(pt.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(pt.length());
    }
}
