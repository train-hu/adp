package com.ops.deploy.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * 自实现 HMAC-SHA256 JWT（HS256）。
 *
 * <p>零新增依赖：签名用 JDK 原生 {@link javax.crypto.Mac} + {@link Base64}（URL-safe 无填充），
 * payload 解析复用 Spring Web 已传递引入的 Jackson。
 *
 * <p>token 结构：{@code base64url(header).base64url(payload).base64url(signature)}，
 * payload 字段：uid(用户 id)、sub(用户名)、role(角色名)、iat(签发秒)、exp(过期秒)。
 *
 * <p>密钥始终取自配置 {@code deploy.jwt-secret}（不再"为空才回退"）。
 * 开关 {@code deploy.jwt-secret-enforce-strong}（默认 true）开启时，若密钥为空、
 * 长度 &lt; 32 字节、或等于公开的占位值 {@link #DEFAULT_SECRET}，则抛出
 * {@link IllegalStateException} 拒绝初始化（fail-fast），避免生产漏配弱密钥导致 token 可被伪造。
 * 本地/测试可用 {@code deploy.jwt-secret-enforce-strong=false} 关闭强校验。
 */
@Service
public class JwtTokenService {

    private static final String ALG = "HS256";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    /**
     * 32 字节公开占位密钥。生产必须改用强 secret（否则任何能看到代码的人都可伪造 token）。
     * 保留此常量用于：1) 被识别为"弱密钥"（强校验下拒绝）；2) 无参构造 / enforce=false 场景兜底。
     */
    static final String DEFAULT_SECRET = "auto-deploy-dev-jwt-secret-key!!";
    static final long DEFAULT_EXPIRE_HOURS = 24L;
    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;
    private final long defaultExpirySeconds;

    /**
     * 无参构造：使用占位密钥与 24h 过期，强校验关闭（enforce=false）。
     * 供单元测试与本地开发使用——单测均 standalone 构造，不加载 application.yml。
     */
    public JwtTokenService() {
        this(DEFAULT_SECRET, DEFAULT_EXPIRE_HOURS, false);
    }

    @Autowired
    public JwtTokenService(@Value("${deploy.jwt-secret:}") String secret,
                           @Value("${deploy.jwt-expire-hours:24}") long expireHours,
                           @Value("${deploy.jwt-secret-enforce-strong:true}") boolean enforceStrong) {
        // 始终使用配置值（不再"为空才回退"到占位密钥）
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        this.defaultExpirySeconds = expireHours * 3600L;
        if (enforceStrong && isWeakSecret(secret)) {
            throw new IllegalStateException(
                    "deploy.jwt-secret must be a strong secret (>= 32 bytes and not the public "
                            + "placeholder default); set deploy.jwt-secret to a strong value, or set "
                            + "deploy.jwt-secret-enforce-strong=false for local development only");
        }
    }

    /**
     * 弱密钥判定：为空、长度 &lt; 32 字节、或等于公开占位值 {@link #DEFAULT_SECRET}。
     * 仅在 {@code enforce-strong=true} 时用于 fail-fast。
     */
    static boolean isWeakSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return true;
        }
        if (DEFAULT_SECRET.equals(secret)) {
            return true;
        }
        return secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES;
    }

    /** 签发：默认过期时间为当前时间 + deploy.jwt-expire-hours（默认 24h）。 */
    public String issue(Long userId, String username, String role) {
        return issue(userId, username, role, Instant.now().plusSeconds(defaultExpirySeconds));
    }

    /**
     * 签发：显式指定过期时间点。
     *
     * @param expiry token 的 exp（过期）绝对时间，便于测试构造已过期 token
     */
    public String issue(Long userId, String username, String role, Instant expiry) {
        long now = Instant.now().getEpochSecond();
        long exp = expiry.getEpochSecond();
        String header = b64url("{\"alg\":\"" + ALG + "\",\"typ\":\"JWT\"}");
        String payload = b64url("{\"uid\":" + userId
                + ",\"sub\":\"" + username + "\""
                + ",\"role\":\"" + role + "\""
                + ",\"iat\":" + now
                + ",\"exp\":" + exp + "}");
        String signingInput = header + "." + payload;
        String signature = b64url(hmac(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }

    /**
     * 解析并校验 token：签名 + 过期。
     *
     * @throws BizException UNAUTHORIZED(401) 签名无效 / 已过期 / 格式非法
     */
    public AuthUser parse(String token) {
        if (token == null || token.isEmpty()) {
            throw unauthorized();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized();
        }
        String headerB64 = parts[0];
        String payloadB64 = parts[1];
        String signatureB64 = parts[2];

        // 1) 校验签名（先校验，避免解析被篡改/伪造的 payload）
        byte[] expectedSig = hmac((headerB64 + "." + payloadB64).getBytes(StandardCharsets.UTF_8));
        byte[] actualSig;
        try {
            actualSig = URL_DECODER.decode(signatureB64);
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) {
            throw unauthorized();
        }

        // 2) 解码 payload
        String payloadJson;
        try {
            payloadJson = new String(URL_DECODER.decode(payloadB64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }

        // 3) 解析 claims 并校验过期
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            long exp = node.get("exp").asLong();
            if (Instant.now().getEpochSecond() >= exp) {
                throw unauthorized();
            }
            Long uid = node.hasNonNull("uid") ? node.get("uid").asLong() : null;
            String username = node.get("sub").asText();
            String role = node.get("role").asText();
            return new AuthUser(uid, username, role);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized();
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 init failed", e);
        }
    }

    private static String b64url(String s) {
        return URL_ENCODER.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64url(byte[] bytes) {
        return URL_ENCODER.encodeToString(bytes);
    }

    private static BizException unauthorized() {
        return new BizException(ErrorCode.UNAUTHORIZED, "invalid or expired token");
    }
}
