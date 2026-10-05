package cn.minims.minidocs.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 可逆加密工具（AES-256/GCM）：用于落库的第三方凭证，目前是 Git 访问令牌。
 *
 * <p>这类凭证必须能被服务端原样取回才能拿去访问远程仓库，所以不能像密码那样只存哈希。
 * 密钥由 {@code minidocs.jwt-secret} 派生（SHA-256），因此<b>换掉 jwt-secret 会让已存的令牌
 * 解不开</b>——那时同步会明确报「凭证解密失败」，需要重新填写，而不是静默失败。</p>
 *
 * <p>密文自带格式标记与随机 IV：{@code v1:<iv>:<ciphertext>}，两段均为 URL 安全的 Base64。
 * 每次加密都用新的 IV，同一令牌两次加密的密文不同。</p>
 */
public final class CryptoUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String PREFIX = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CryptoUtil() {
    }

    /** 加密；入参为空时返回 null（调用方据此把「没填令牌」存成 NULL 而不是空密文）。 */
    public static String encrypt(String plain, String secret) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keyOf(secret), new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + ":" + ENCODER.encodeToString(iv) + ":" + ENCODER.encodeToString(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @throws IllegalStateException 密文格式不识别或密钥不匹配 —— 两者都只可能是「密钥换了」或
     *                               「数据被改过」，都属于需要人介入的状态，不静默吞掉
     */
    public static String decrypt(String stored, String secret) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        int first = stored.indexOf(':');
        int second = first < 0 ? -1 : stored.indexOf(':', first + 1);
        if (first < 0 || second < 0 || !PREFIX.equals(stored.substring(0, first))) {
            throw new IllegalStateException("凭证密文格式无法识别");
        }
        try {
            byte[] iv = DECODER.decode(stored.substring(first + 1, second));
            byte[] cipherText = DECODER.decode(stored.substring(second + 1));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keyOf(secret), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭证解密失败，可能是加密密钥已变更", e);
        }
    }

    private static SecretKeySpec keyOf(String secret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
}
