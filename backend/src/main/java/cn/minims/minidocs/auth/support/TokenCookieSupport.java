package cn.minims.minidocs.auth.support;

import cn.minims.minidocs.config.properties.MiniDocsProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录态 Cookie 桥接。
 *
 * <p>管理后台的 {@code <img>} / {@code <a>} 无法携带 Authorization 头，
 * 因此登录时额外下发 HttpOnly Cookie；配合 {@link TokenCookieBridgeFilter} 注入请求头，
 * 使资源代理与下载接口无需改动即可工作。</p>
 */
@Component
@RequiredArgsConstructor
public class TokenCookieSupport {

    public static final String COOKIE_NAME = "minidocs_token";

    private static final Pattern DURATION = Pattern.compile("^(\\d+)([smhdSMHD])?$");

    private final MiniDocsProperties properties;

    public void write(HttpServletResponse response, HttpServletRequest request, String token) {
        long maxAge = toSeconds(properties.getJwtExpiresIn(), 7 * 24 * 3600L);
        ResponseCookie cookie = base(token, Duration.ofSeconds(maxAge))
                .secure(request.isSecure())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clear(HttpServletResponse response, HttpServletRequest request) {
        ResponseCookie cookie = base("", Duration.ZERO)
                .secure(request.isSecure())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAge);
    }

    static long toSeconds(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Matcher matcher = DURATION.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return fallback;
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        if (unit == null) {
            return amount;
        }
        return switch (unit) {
            case "s" -> amount;
            case "m" -> amount * 60;
            case "h" -> amount * 3600;
            default -> amount * 86400;
        };
    }
}
