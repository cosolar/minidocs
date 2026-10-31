package cn.minims.minidocs.markdown;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * 渲染结果二次白名单过滤，彻底阻断 XSS。
 *
 * <p>flexmark 现在会解析原生 HTML（与工作区预览保持一致），所以本类是标签 / 属性的
 * 唯一闸门；URL 方案校验仍由 {@code MarkdownService#enforceSafeUrls} 负责。
 * 白名单刻意不放行 {@code style} 属性：作者的对齐需求由 {@code MarkdownService}
 * 归一成 {@code md-align-*} 类名，其它内联样式一律丢弃。</p>
 */
@Component
public class MarkdownSanitizer {

    private final Safelist safelist;

    public MarkdownSanitizer() {
        // 注意：不使用 addProtocols 做 URL 方案校验。
        // jsoup 的协议白名单对「根路径 / 相对路径」无法按预期放行（其内部为整体正则匹配），
        // 会误剥离站点内资源地址；因此 URL 方案白名单统一由 MarkdownService#enforceSafeUrls
        // 在渲染阶段显式执行，本类只负责「标签 + 属性」白名单。
        Safelist list = new Safelist()
                .addTags("p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
                        "strong", "b", "em", "i", "del", "s", "u", "mark", "sub", "sup",
                        "blockquote", "ul", "ol", "li", "dl", "dt", "dd",
                        "a", "img", "figure", "figcaption",
                        "code", "pre", "span", "div", "section",
                        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
                        "input", "kbd", "abbr", "small")
                .addAttributes(":all", "class", "id", "title")
                .addAttributes("a", "href", "rel", "target")
                .addAttributes("img", "src", "alt", "width", "height", "loading")
                .addAttributes("input", "type", "checked", "disabled")
                .addAttributes("th", "colspan", "rowspan", "align")
                .addAttributes("td", "colspan", "rowspan", "align");

        this.safelist = list;
    }

    public String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, "", safelist, settings);
    }
}
