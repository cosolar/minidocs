package cn.minims.minidocs.share.support;

import cn.minims.minidocs.config.properties.MiniDocsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 站点基址（协议 + 主机 + 端口）的<b>唯一</b>出口。
 *
 * <p>凡是要给人看、给人复制、放进邮件或聊天里的绝对地址（分享链接、管理台里那条 URL），
 * 都必须由这里算，不允许各写一份 {@code window.location} 或 {@code fromCurrentContextPath}：
 * 同一件事出现两个来源，部署到反向代理后面时必然对不上 —— 一处拿到内网 host，一处拿到外网域名。</p>
 *
 * <p>取值顺序：</p>
 * <ol>
 *   <li>{@code minidocs.page-base-url}：显式配置的对外地址，<b>推荐</b>。
 *       前后端分离部署（前端由 Nginx 伺服在站点根、后端挂在 context-path 下）时必须配 ——
 *       此时页面地址里没有 context-path，从请求推导会指向错的一层。</li>
 *   <li>没配则按当前请求推导（协议 + 主机 + 端口）：适合同源部署，但要正确识别反代传来的
 *       {@code X-Forwarded-Proto} / {@code X-Forwarded-Host}，需开启
 *       {@code server.forward-headers-strategy=framework}。</li>
 * </ol>
 *
 * <p>返回值无尾斜杠：调用方直接拼 {@code /share/{token}}。</p>
 */
public final class ShareLinks {

    private ShareLinks() {
    }

    public static String baseUrl(HttpServletRequest request, MiniDocsProperties properties) {
        String configured = properties == null ? null : properties.getPageBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured.trim());
        }
        if (request == null) {
            return "";
        }
        // replacePath(null)：只要 scheme://host[:port]；context-path 由调用方按需拼 ——
        // 页面地址里可能压根没有它（前端伺服在站点根时就是没有）
        return trimTrailingSlash(ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null).replaceQuery(null).build().toUriString());
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
