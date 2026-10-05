package cn.minims.minidocs.common.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 相对路径的 URL 编解码：逐段编码，保留 {@code /} 分隔符。
 */
public final class PathEncoder {

    private PathEncoder() {
    }

    public static String encodePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String[] segments = path.split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return builder.toString();
    }

    public static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /**
     * 取原始 URI 中第一个 {@code marker}（资源代理用 {@code /asset/}）之后的相对路径并解码。
     *
     * <p>不用「控制器自己拼前缀 + 按前缀长度截断」：{@code getRequestURI()} 是未解码的原始串，
     * 而 {@code @PathVariable} 是解码过的，中文组织名或库名会让两种长度对不上，截出来的
     * 就是半个百分号转义。marker 出现在路由里、不可能出现在 slug 里，因此取第一个即可，
     * 而最后一个可能是文档目录的真名（{@code a/b/asset/}）。</p>
     */
    public static String tailAfter(String uri, String marker) {
        int index = uri.indexOf(marker);
        return index < 0 ? "" : decode(uri.substring(index + marker.length()));
    }
}
