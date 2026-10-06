package cn.minims.minidocs.site.support;

import cn.minims.minidocs.common.exception.BizException;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 站点基址的校验与归一化。
 *
 * <p>「站点基址」是要拼进分享链接给人看的一段地址，写错的后果不是报错而是链接打不开，
 * 而且往往要到有人把链接发出去之后才发现。因此保存前一律在这里过一遍，
 * 宁可当场拒掉，也不放一个拼错的地址进库。</p>
 *
 * <p>无状态纯函数，不碰数据库与请求：这样归一化规则能单独验证，
 * 也便于在「读出来算链接」与「写进去」两侧用同一套口径。</p>
 */
public final class SiteBaseUrls {

    /** 基址长度上限。域名 + 路径，给 200 字符足够宽松，又能挡住误粘整段 URL 的情况。 */
    public static final int MAX_LENGTH = 200;

    /** 形如 {@code scheme://} 的前缀。用于判断是否需要补协议。 */
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://");

    private static final String DEFAULT_SCHEME = "https://";

    private SiteBaseUrls() {
    }

    /**
     * 归一化：补协议、去尾斜杠。
     *
     * <p>补协议是有意为之：管理员在设置框里十有八九只填域名或「域名/路径」，
     * 少了协议就成了 {@code docs.example.com/share/xxx} 这种浏览器无法识别的地址。
     * 与其报错让人猜格式，不如按 {@code https} 补上——公网部署本来就该是 https。</p>
     *
     * @return 归一化后的基址（无尾斜杠）；入参为空白时返回空串，含义是「未配置」
     */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!HAS_SCHEME.matcher(value).find()) {
            value = DEFAULT_SCHEME + value;
        }
        return trimTrailingSlash(value);
    }

    /**
     * 校验并归一化，非法即抛 {@link BizException}（参数错误）。
     *
     * <p>校验放在归一化之后：补完协议再解析，才能给出「协议只支持 http/https」这种
     * 针对最终值的提示，而不是针对用户没写协议这件事的无效抱怨。</p>
     */
    public static String require(String raw) {
        String value = normalize(raw);
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > MAX_LENGTH) {
            throw BizException.param("站点基址不能超过 " + MAX_LENGTH + " 个字符");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw BizException.param("站点基址格式不合法：" + value);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw BizException.param("站点基址只支持 http 或 https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            // 不说「缺少主机名」：走到这里的输入往往主机名是有的，只是被当成了协议或端口
            // （例如 javascript:alert(1)、https://），说「缺主机名」会让人以为自己漏填了域名。
            throw BizException.param("站点基址格式不合法，请填写域名，如 docs.example.com");
        }
        return value;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
