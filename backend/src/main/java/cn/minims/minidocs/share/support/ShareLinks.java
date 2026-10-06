package cn.minims.minidocs.share.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 分享链接的拼装工具：把「基址」和「路径」拼成一条能直接发给人点的绝对地址。
 *
 * <p>凡是要给人看、给人复制、放进邮件或聊天里的绝对地址（分享链接、管理台里那条 URL），
 * 都必须由这里拼，不允许各写一份 {@code window.location} 或 {@code fromCurrentContextPath}：
 * 同一件事出现两个来源，部署到反向代理后面时必然对不上 —— 一处拿到内网 host，一处拿到外网域名。</p>
 *
 * <p><b>基址从哪来不归这里管</b>：取值优先级（站点设置页 &gt; {@code PAGE_BASE_URL} &gt; 按请求推导）
 * 集中在 {@code SiteBaseUrlResolver}，本类只负责「拿到基址之后怎么拼」，不再去读任何配置 ——
 * 否则取值规则会被复制到多处，改一处漏一处。</p>
 *
 * <p>{@link #normalize(String)} 与 {@link #fromRequest(HttpServletRequest)} 的结果都无尾斜杠：
 * 调用方直接拼 {@code /share/{token}}。</p>
 */
public final class ShareLinks {

    private ShareLinks() {
    }

    /**
     * 按当前请求推导站点基址（协议 + 主机 + 端口）。
     *
     * <p>部署在反向代理之后时，必须开启 {@code server.forward-headers-strategy=framework}，
     * 否则这里拿到的是内网 host 与 http，链接发出去别人打不开。</p>
     */
    public static String fromRequest(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        // replacePath(null)：只要 scheme://host[:port]；context-path 由调用方按需拼 ——
        // 页面地址里可能压根没有它（前端伺服在站点根时就是没有）
        return trimTrailingSlash(ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null).replaceQuery(null).build().toUriString());
    }

    /** 归一化一个已给定的基址：去首尾空白与尾斜杠。 */
    public static String normalize(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String value = baseUrl.trim();
        return value.isEmpty() ? "" : trimTrailingSlash(value);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
