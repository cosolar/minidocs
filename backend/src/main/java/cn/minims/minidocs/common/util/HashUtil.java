package cn.minims.minidocs.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具：ETag / 访客指纹。
 */
public final class HashUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtil() {
    }

    public static String sha256Hex(String text) {
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        return sha256Hex(bytes);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /** SHA-256 前 length 位十六进制。 */
    public static String sha256Prefix(String text, int length) {
        String hex = sha256Hex(text);
        return hex.substring(0, Math.min(length, hex.length()));
    }

    public static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
        return builder.toString();
    }
}
