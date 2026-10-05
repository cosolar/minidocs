package cn.minims.minidocs.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 访问日志，统一记到 {@code minidocs.access} logger（见 logback-spring.xml）：
 * 该 logger 只挂 ACCESS_FILE 与 CONSOLE_WARN，于是全量请求进访问文件、终端只在 4xx/5xx 时露头。
 * 2xx 必须记在 INFO —— 压在 debug 上，访问文件默认门槛是 INFO，等于没记。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("minidocs.access");

    private static final String[] SKIP_PREFIXES =
            {"/share/static/", "/admin/assets/", "/favicon.ico", "/actuator"};

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String prefix : SKIP_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            int status = response.getStatus();
            String line = "{} {} -> {} ({}ms) ip={}";
            Object[] args = {request.getMethod(), request.getRequestURI(), status, cost, clientIp(request)};
            if (status >= 500) {
                ACCESS_LOG.error(line, args);
            } else if (status >= 400) {
                ACCESS_LOG.warn(line, args);
            } else {
                ACCESS_LOG.info(line, args);
            }
        }
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
