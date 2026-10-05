package cn.minims.minidocs.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全局安全响应头。
 *
 * <p>SVG 代理响应会在 ShareAssetController 中覆盖为更严格的 CSP。</p>
 *
 * <p>{@code cdn.jsdmirror.com} 这一个口子是为分享页的「正文字体」开的：思源黑体、思源宋体与霞鹜文楷的
 * webfont 只有那边有按 unicode-range 分片的版本，{@code readerFont.ts} 选中对应字体时才注入它的
 * 样式表。放开的是 {@code style-src} 与 {@code font-src} 两处，{@code script-src} 仍只认 'self'，
 * 所以 CDN 上即使出问题也执行不了脚本。想彻底不依赖外部域名，把字体分片自托管到
 * {@code front/public/fonts/}、改 {@code readerFont.ts} 里的 {@code hrefs}，再把这两处域名去掉即可。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeaderFilter extends OncePerRequestFilter {

    public static final String DEFAULT_CSP =
            "default-src 'self'; img-src 'self' data: blob:; "
                    + "style-src 'self' 'unsafe-inline' https://cdn.jsdmirror.com; "
                    + "script-src 'self'; font-src 'self' data: https://cdn.jsdmirror.com; "
                    + "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        if (response.getHeader("Content-Security-Policy") == null) {
            response.setHeader("Content-Security-Policy", DEFAULT_CSP);
        }
        chain.doFilter(request, response);
    }
}
