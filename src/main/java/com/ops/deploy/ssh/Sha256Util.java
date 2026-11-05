package com.ops.deploy.ssh;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * SHA-256 工具：SFTP 上传校验用。
 * 输出小写 hex——与 Linux 远端 {@code sha256sum} 输出格式一致，可直接比对。
 */
public final class Sha256Util {

    /** 64 位小写 hex 摘要（SHA-256）。 */
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private Sha256Util() {
    }

    /** 计算字节数组的 SHA-256，返回 64 位小写 hex。 */
    public static String hex(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 必备算法，不可能缺失
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 忽略大小写比较两个 hex 摘要。 */
    public static boolean equalsHex(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    /**
     * 从 {@code sha256sum} 输出（"hash  filename"）提取 hash 部分。
     * 解析失败返回 null。
     */
    public static String extractFromSha256sumOutput(String sha256sumOutput) {
        if (sha256sumOutput == null) {
            return null;
        }
        String firstLine = sha256sumOutput.strip().split("\\R", 2)[0].strip();
        int sep = firstLine.indexOf(' ');
        if (sep <= 0) {
            return null;
        }
        String candidate = firstLine.substring(0, sep).strip();
        if (!HEX64.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }
}
