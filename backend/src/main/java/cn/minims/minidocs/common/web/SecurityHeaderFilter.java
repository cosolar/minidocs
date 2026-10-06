package cn.minims.minidocs.common.web;

import cn.minims.minidocs.config.properties.MiniDocsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeaderFilter extends OncePerRequestFilter {

    /**
     * 基础 CSP：不放开任何外部图片。
     *
     * <p>{@code img-src} 只有 'self' 时，markdown 里的外链图片（自建图床、{@code img.shields.io}
     * 徽章这类）会被浏览器按「已屏蔽：csp」丢掉 —— 页面看上去就是图片位置一片空白，
     * 而服务端日志与网络面板里那条请求的状态是 200，非常容易被误判成「跨域被拦」，
     * 实际两者完全是两回事（外链图片本不受 CORS 约束）。</p>
     */
    public static final String BASE_CSP =
            "default-src 'self'; img-src %s; "
                    + "style-src 'self' 'unsafe-inline' https://cdn.jsdmirror.com; "
                    + "script-src 'self'; font-src 'self' data: https://cdn.jsdmirror.com; "
                    + "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'";

    /** 允许外链图片时的 img-src：任意 https 图床。图片是惰性内容，script-src 仍是 'self'，拿不到执行能力。 */
    public static final String IMG_SRC_EXTERNAL = "'self' data: blob: https:";

    /** 不允许外链图片时的 img-src。 */
    public static final String IMG_SRC_SELF_ONLY = "'self' data: blob:";

    private final MiniDocsProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        if (response.getHeader("Content-Security-Policy") == null) {
            response.setHeader("Content-Security-Policy", csp());
        }
        chain.doFilter(request, response);
    }

    /**
     * 启用外链图片时额外发一条 {@code img-src https:}，这样即便更外层（Nginx）又发了一条更严的
     * CSP，两条指令<b>并存取交集</b>仍会把图片挡掉。
     *
     * <p>问题出在多道代理各发一份 CSP 时，子级的 {@code add_header} 会整体替换父级而不是合并，
     * 于是「Nginx 补的那份」与「后端发的」哪条更严完全取决于部署形态，而作者改配置也无从得知。
     * 所以前端与接口两侧必须同源同值 —— {@code deploy/nginx.conf} 里那份要和本方法保持一致，
     * 并记得同步收窄。</p>
     */
    private String csp() {
        String imgSrc = properties.isCspAllowExternalImages() ? IMG_SRC_EXTERNAL : IMG_SRC_SELF_ONLY;
        return String.format(BASE_CSP, imgSrc);
    }
}
