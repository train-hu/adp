package com.ops.deploy.crypto;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 服务器凭证（SSH 密码）AES-256-GCM 加解密服务。
 *
 * <p>密钥派生：{@code SHA-256(kekString)} 取 32 字节作为 AES-256 密钥。
 * 这样 KEK 任意长度都可用（如 application.yml 中 35 字节的开发占位值），统一 256 位。
 *
 * <p>密文布局：{@code IV(12B) || ciphertext + GCM tag(16B)}。每次加密用
 * {@link SecureRandom} 生成 12 字节随机 IV，不复用。
 *
 * <p>解密时长度不足（&lt; 12+16=28）或 GCM 校验失败（密文/IV 被篡改、密钥不符）
 * 均抛出 {@link BizException}（{@link ErrorCode#CREDENTIAL_ERROR}）。
 */
@Service
public class CredentialCryptoService {

    private static final Logger log = LoggerFactory.getLogger(CredentialCryptoService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MIN_BLOB_BYTES = GCM_IV_BYTES + 16; // IV + tag

    /** 已知开发占位 KEK（application.yml 默认值）。生产必须用 CREDENTIAL_KEK 覆盖。 */
    static final String DEV_PLACEHOLDER_KEK = "dev-only-32-byte-kek-please-change!";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * KEK 来自配置 {@code deploy.credential-kek}（Spring 注入解析 @Value；
     * 单测直接以字面量调用，无需 Spring 上下文）。
     */
    public CredentialCryptoService(@Value("${deploy.credential-kek}") String kek) {
        this.key = deriveKey(kek == null ? "" : kek);
        if (DEV_PLACEHOLDER_KEK.equals(kek)) {
            log.warn("deploy.credential-kek 仍是开发占位值！生产环境必须通过 CREDENTIAL_KEK "
                    + "环境变量设置为强密钥，否则凭证密文可被还原。");
        }
    }

    /** SHA-256(kek) → 32 字节 AES-256 密钥（任意长度 KEK 可用）。 */
    private static SecretKeySpec deriveKey(String kek) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(kek.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 key derivation failed", e);
        }
    }

    /**
     * 加密明文，返回 {@code IV(12B) || ciphertext+tag}。
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            throw new BizException(ErrorCode.CREDENTIAL_ERROR, "plaintext is null");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[GCM_IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES);
            System.arraycopy(ct, 0, out, GCM_IV_BYTES, ct.length);
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.CREDENTIAL_ERROR, "encrypt failed: " + e.getMessage());
        }
    }

    /**
     * 解密 {@code IV(12B) || ciphertext+tag}，返回明文字符串。
     *
     * @throws BizException CREDENTIAL_ERROR：长度不足或 GCM 校验失败
     */
    public String decrypt(byte[] cipherBlob) {
        if (cipherBlob == null || cipherBlob.length < MIN_BLOB_BYTES) {
            throw new BizException(ErrorCode.CREDENTIAL_ERROR, "cipher blob too short");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(cipherBlob, 0, iv, 0, GCM_IV_BYTES);
            byte[] ct = new byte[cipherBlob.length - GCM_IV_BYTES];
            System.arraycopy(cipherBlob, GCM_IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // GCM AEADBadTagException 等校验失败统一为凭证错误，不泄露细节
            throw new BizException(ErrorCode.CREDENTIAL_ERROR, "decrypt failed");
        }
    }
}
