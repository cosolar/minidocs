package cn.minims.minidocs.auth.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 当请求未携带 Authorization 头，但存在登录 Cookie 时，把 Cookie 值注入请求头，
 * 使 Sa-Token 正常识别登录态（用于 &lt;img&gt; / &lt;a&gt; 等无法设置请求头的场景）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
public class TokenCookieBridgeFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final TokenCookieSupport tokenCookieSupport;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            chain.doFilter(request, response);
            return;
        }
        String token = tokenCookieSupport.read(request);
        if (token == null || token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new AuthorizationHeaderRequest(request, BEARER + token), response);
    }

    /** 仅覆盖 Authorization 头的请求包装。 */
    private static final class AuthorizationHeaderRequest extends HttpServletRequestWrapper {

        private final String authorization;

        private AuthorizationHeaderRequest(HttpServletRequest request, String authorization) {
            super(request);
            this.authorization = authorization;
        }

        @Override
        public String getHeader(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return authorization;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(authorization));
            }
            return super.getHeaders(name);
        }
    }
}
