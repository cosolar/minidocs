package cn.minims.minidocs.share.support;

import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.share.entity.Share;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 分享访问 Cookie：与分享 token 强绑定的 HMAC 签名令牌。
 *
 * <table>
 *   <tr><td>名称</td><td>share_{token}</td></tr>
 *   <tr><td>Path</td><td>/</td></tr>
 *   <tr><td>HttpOnly / SameSite</td><td>是 / Lax</td></tr>
 *   <tr><td>有效期</td><td>SHARE_COOKIE_HOURS（默认 12 小时）</td></tr>
 * </table>
 *
 * <p>作用域取 {@code /} 而非 {@code /share/{token}}：用户侧 SPA 的取数接口位于
 * {@code /api/share/{token}}、资源代理位于 {@code /share/{token}/asset/**}，
 * 与页面路径 {@code /share/{token}} 三者并不同前缀，只有放宽到根路径才能同时带上。
 * 令牌本身以分享 token 命名并签名，越界并无额外泄露面。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShareAccessSupport {

    private static final String HMAC = "HmacSHA256";

    private final MiniDocsProperties properties;

    public String cookieName(String token) {
        return "share_" + token;
    }

    public String createToken(Share share) {
        long expiresAt = Instant.now().plus(Duration.ofHours(properties.getShareCookieHours())).getEpochSecond();
        String payload = share.getToken() + "|" + expiresAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(payload);
    }

    public boolean verify(Share share, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot == value.length() - 1) {
            return false;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(value.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!constantTimeEquals(sign(payload), value.substring(dot + 1))) {
            return false;
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 2 || !parts[0].equals(share.getToken())) {
            return false;
        }
        try {
            return Long.parseLong(parts[1]) >= Instant.now().getEpochSecond();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean isVerified(Share share, HttpServletRequest request) {
        if (!share.encrypted()) {
            return true;
        }
        return verify(share, readCookie(request, cookieName(share.getToken())));
    }

    public void grant(Share share, HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName(share.getToken()), createToken(share))
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ofHours(properties.getShareCookieHours()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), HMAC));
            byte[] result = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(result.length * 2);
            for (byte b : result) {
                builder.append(Character.forDigit((b >> 4) & 0x0F, 16)).append(Character.forDigit(b & 0x0F, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("分享访问令牌签名失败", e);
        }
    }

    private String secret() {
        String secret = properties.getJwtSecret();
        return secret == null || secret.isBlank() ? "minidocs-default-share-secret" : secret;
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
