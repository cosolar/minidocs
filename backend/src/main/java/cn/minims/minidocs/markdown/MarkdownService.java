package cn.minims.minidocs.markdown;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.HashUtil;
import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.markdown.model.MarkdownModels.DocMeta;
import cn.minims.minidocs.markdown.model.MarkdownModels.OutlineNode;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderContext;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderResult;
import cn.minims.minidocs.markdown.model.MarkdownModels.Variant;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.definition.DefinitionExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Markdown 渲染管线（单一渲染源）。
 *
 * <pre>
 * 读取 .md → 校验大小 → 解析 frontmatter → flexmark 渲染（禁用原生 HTML）
 *   → 白名单过滤 → 生成标题锚点并提取大纲 → 改写图片 / 链接 → 写入 LRU 缓存 + ETag
 * </pre>
 *
 * <p>缓存中保存「中性 HTML」（图片与文档链接以占位符表达），
 * 输出时按入口（后台 / 门户 / 分享）替换占位符，避免为每个入口重复渲染。</p>
 */
@Slf4j
@Service
public class MarkdownService {

    /**
     * 资源与文档链接占位符。
     *
     * <p>刻意写成「根路径」形式：渲染结果最终要经过 jsoup 协议白名单过滤，
     * 白名单只放行 http / https / data / 根路径，相对路径会被剥离，
     * 因此先在源文件相对路径阶段改写为根路径占位符，输出时再替换为真实前缀。</p>
     */
    private static final String ASSET_TOKEN = "/@@md-asset@@/";
    private static final String DOC_TOKEN = "/@@md-doc@@/";
    private static final int WORDS_PER_MINUTE = 300;

    private final MiniDocsProperties properties;
    private final MarkdownSanitizer sanitizer;
    private final Parser parser;
    private final HtmlRenderer renderer;
    /** 表格分隔行每格的最少横线数 —— flexmark 的硬要求，GFM 规范本身没写这一条 */
    private static final int DELIMITER_MIN_DASHES = 3;
    /** 合法分隔格：冒号? + 横线+ + 冒号? */
    private static final java.util.regex.Pattern DELIMITER_CELL =
            java.util.regex.Pattern.compile("^:?-+:?$");

    private final Cache<String, Neutral> cache;

    public MarkdownService(MiniDocsProperties properties, MarkdownSanitizer sanitizer) {
        this.properties = properties;
        this.sanitizer = sanitizer;

        MutableDataSet options = new MutableDataSet();
        List<Extension> extensions = new ArrayList<>();
        extensions.add(TablesExtension.create());
        extensions.add(StrikethroughExtension.create());
        extensions.add(TaskListExtension.create());
        extensions.add(AutolinkExtension.create());
        extensions.add(DefinitionExtension.create());
        options.set(Parser.EXTENSIONS, extensions);
        // 不解析原生 HTML（block 与 inline），从源头阻断注入
        options.set(Parser.HTML_BLOCK_PARSER, false);
        options.set(HtmlRenderer.SUPPRESS_HTML, true);
        options.set(HtmlRenderer.ESCAPE_HTML, true);
        options.set(HtmlRenderer.GENERATE_HEADER_ID, false);
        options.set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX, "language-");

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
        this.cache = Caffeine.newBuilder().maximumSize(properties.getRenderCacheSize()).build();
    }

    /**
     * 表格分隔行归一：横线不足 3 个的补足。
     *
     * <p><b>为什么需要这一步：同一个库有两套渲染器，对「分隔行最少几个横线」的判断
     * 不一致。</b>工作区预览走 Cherry Markdown（前端），阅读页与分享页走本类的 flexmark
     * （后端）。GFM 规范只要求每格是「若干横线 + 可选的首尾冒号」，而 flexmark 额外要求
     * 至少 3 个 —— 于是 {@code |:-|:---|} 这种合法 GFM 在工作区里渲染成表格、在阅读页
     * 里塌成一段带竖线的文字。</p>
     *
     * <p>实测（agentlearn/docs/README.md）：全文 5 个分割行里，只有第一格写成 {@code :-}
     * （1 个横线）的那一个没渲染，其余 4 个（4–6 个横线）都正常。</p>
     *
     * <p><b>改渲染器而不是改内容</b>：这些文档多半是工具或模型生成的，让作者去逐篇把
     * {@code :-} 改成 {@code :--} 既不现实也易复发；而 Cherry 已经能渲染它，说明「能渲染」
     * 才是这套产品该有的行为。</p>
     *
     * <p>只碰「整行都由分隔格组成」的那些行，且只补横线、不动冒号与列数 —— 表格里的
     * {@code ---} 会被误伤，所以要求该行至少含 2 个 {@code |} 且每格都匹配
     * {@code :?-+:?}。</p>
     */
    private static String normalizeTableDelimiters(String md) {
        // 按行处理但保留原换行符：不能把 CRLF 一起吃掉，那会让整篇文档变成一行
        String[] lines = md.split("\r\n|\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (!t.startsWith("|") || t.indexOf('|', 1) < 0) {
                continue;
            }
            // 整行按 | 切开后每格都必须是「冒号? + 横线+ + 冒号?」，否则不是分隔行
            String[] cells = t.split("\\|", -1);
            boolean isDelimiter = true;
            boolean needsPad = false;
            for (int c = 0; c < cells.length; c++) {
                String cell = cells[c].trim();
                if (cell.isEmpty()) {
                    // 首尾空格格（| A | 切出来的）跳过
                    continue;
                }
                if (!DELIMITER_CELL.matcher(cell).matches()) {
                    isDelimiter = false;
                    break;
                }
                if (countDashes(cell) < DELIMITER_MIN_DASHES) {
                    needsPad = true;
                }
            }
            if (!isDelimiter || !needsPad) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < cells.length; c++) {
                if (c > 0) {
                    sb.append('|');
                }
                String cell = cells[c].trim();
                if (cell.isEmpty()) {
                    sb.append(cell);
                    continue;
                }
                /*
                 * 首尾冒号要分开数，不能用 startsWith/endsWith 各判一次 ——
                 * {@code :-} 同样 endsWith(":"), 那样会把「左对齐」写成「右对齐」，
                 * 输出 {@code --:} 这种废分隔行，表照样渲染不出来。
                 */
                int lead = 0;
                while (lead < cell.length() && cell.charAt(lead) == ':') {
                    lead++;
                }
                int tail = 0;
                while (tail < cell.length() - lead
                        && cell.charAt(cell.length() - 1 - tail) == ':') {
                    tail++;
                }
                if (lead > 0) {
                    sb.append(':');
                }
                int dashes = countDashes(cell);
                // 写 max(原有, 下限) 根，而不是「只补差额」—— 后者在原有横线已达标时
                // 一次都不写，那格就退化成一个冒号，整行反而废了
                int total = Math.max(dashes, DELIMITER_MIN_DASHES);
                for (int d = 0; d < total; d++) {
                    sb.append('-');
                }
                if (tail > 0) {
                    sb.append(':');
                }
            }
            lines[i] = sb.toString();
            changed = true;
        }
        return changed ? String.join("\n", lines) : md;
    }

    private static int countDashes(String cell) {
        int n = 0;
        for (int i = 0; i < cell.length(); i++) {
            if (cell.charAt(i) == '-') {
                n++;
            }
        }
        return n;
    }

    /**
     * 渲染知识库内某文档。
     *
     * @param kbRoot  知识库根目录
     * @param kbId    知识库 id（缓存键组成部分）
     * @param docPath 文档相对路径
     * @param variant 渲染变体
     * @param context 入口上下文（图片前缀 / 文档链接前缀）
     */
    public RenderResult render(Path kbRoot, long kbId, String docPath, Variant variant, RenderContext context) {
        Path file = PathGuard.resolve(kbRoot, docPath);
        if (!Files.isRegularFile(file)) {
            throw BizException.notFound("文档不存在：" + docPath);
        }
        long size;
        FileTime lastModified;
        try {
            size = Files.size(file);
            lastModified = Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw BizException.notFound("文档不存在：" + docPath);
        }
        if (size > properties.getDocMaxSize()) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE, "文档超过 2MB 限制");
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.notFound("文档读取失败：" + docPath);
        }

        long mtimeMillis = lastModified.toMillis();
        LocalDateTime modifiedAt = LocalDateTime.ofInstant(lastModified.toInstant(), TimeUtil.ZONE);
        String cacheKey = kbId + "|" + docPath + "|" + mtimeMillis + "|" + size + "|" + variant;

        Neutral neutral = cache.get(cacheKey, key -> build(docPath, content, modifiedAt, size, mtimeMillis, variant));
        if (neutral == null) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "渲染失败");
        }
        String html = neutral.html()
                .replace(ASSET_TOKEN, context.assetPrefix())
                .replace(DOC_TOKEN, context.docLinkPrefix());
        return new RenderResult(html, neutral.outline(), neutral.frontmatter(), neutral.meta(), neutral.etag());
    }

    /** 文档变更后清除该文档的缓存。 */
    public void invalidateDocument(long kbId, String docPath) {
        String prefix = kbId + "|" + docPath + "|";
        cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 目录级变更（重命名 / 移动 / 删除目录）后清除前缀缓存。 */
    public void invalidatePrefix(long kbId, String pathPrefix) {
        String prefix = kbId + "|" + pathPrefix;
        cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 知识库整体失效。 */
    public void invalidateKnowledgeBase(long kbId) {
        String prefix = kbId + "|";
        cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    // ------------------------------------------------------------------ 内部实现

    /** 与入口无关的渲染结果。 */
    private record Neutral(String html, List<OutlineNode> outline, Map<String, Object> frontmatter,
                           DocMeta meta, String etag) {
    }

    private Neutral build(String docPath, String content, LocalDateTime modifiedAt, long size,
                          long mtimeMillis, Variant variant) {
        FrontMatterParser.Parsed parsed = FrontMatterParser.parse(content);
        Map<String, Object> frontmatter = parsed.data();

        String rawHtml = renderer.render(parser.parse(normalizeTableDelimiters(parsed.body())));

        Document document = Jsoup.parseBodyFragment(rawHtml);
        document.outputSettings().prettyPrint(false);
        Element body = document.body();

        // 先改写（相对路径 -> 根路径占位符）与补齐锚点，再统一白名单过滤
        List<OutlineNode> outline = assignHeadingIds(body);
        rewriteImages(body, docPath);
        rewriteLinks(body, docPath, variant);

        enforceSafeUrls(body);
        String html = sanitizer.sanitize(body.html());
        String plainText = body.text();
        int wordCount = countWords(plainText);
        int readingMinutes = Math.max(1, (int) Math.ceil(wordCount / (double) WORDS_PER_MINUTE));

        String title = firstNonBlank(
                FrontMatterParser.str(frontmatter, "title"),
                firstHeadingText(body),
                FileNameUtil.stripMarkdownExt(PathGuard.fileNameOf(docPath)));
        String summary = firstNonBlank(
                FrontMatterParser.str(frontmatter, "summary"),
                FrontMatterParser.str(frontmatter, "description"),
                firstParagraphText(body));
        List<String> tags = FrontMatterParser.list(frontmatter, "tags");
        String author = FrontMatterParser.str(frontmatter, "author");
        LocalDateTime publishedAt = parsePublishedAt(frontmatter, modifiedAt);

        DocMeta meta = new DocMeta(title, truncate(summary, 140), tags, author, publishedAt,
                wordCount, readingMinutes, countLines(content), size, modifiedAt);

        String etag = "W/\"" + Long.toString(mtimeMillis, 36) + "-" + Long.toString(size, 36)
                + "-" + HashUtil.sha256Prefix(content, 8) + "\"";
        return new Neutral(html, outline, frontmatter, meta, etag);
    }

    private List<OutlineNode> assignHeadingIds(Element body) {
        Set<String> used = new HashSet<>();
        List<MutableOutline> roots = new ArrayList<>();
        Deque<MutableOutline> stack = new ArrayDeque<>();

        for (Element heading : body.select("h1, h2, h3, h4, h5, h6")) {
            String text = heading.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            int level = Integer.parseInt(heading.tagName().substring(1));
            String id = uniqueId(text, used);
            heading.attr("id", id);

            MutableOutline node = new MutableOutline(id, text, level);
            while (!stack.isEmpty() && stack.peek().level >= level) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children.add(node);
            }
            stack.push(node);
        }
        return roots.stream().map(MutableOutline::toNode).toList();
    }

    private void rewriteImages(Element body, String docPath) {
        for (Element img : body.select("img[src]")) {
            String src = img.attr("src").trim();
            if (src.isEmpty()) {
                img.replaceWith(placeholder("图片地址为空"));
                continue;
            }
            if (src.startsWith("data:") || src.startsWith("//") || isExternal(src)) {
                continue;
            }
            /*
             * 坑一：`/images/a.png` 这种以 / 开头的**库内绝对路径**。
             * 它长得像站内路径，但站点根不是库根 —— 原样放行会让浏览器去请求
             * `https://站点/images/a.png`，撞上 SPA 回退、拿回一段 HTML，图片永远裂着。
             * Obsidian / 某些导出工具偏偏就爱写这种绝对形式，所以按库内路径处理它。
             * 真正的站外图片（http(s)://）在上面已经 return 了，不会走到这里。
             */
            String candidate = src.startsWith("/") ? src.substring(1) : src;
            String resolved = resolveRelative(docPath, stripAnchor(candidate));
            if (resolved == null) {
                // 坑二：越出库根（`../../x.png`）或路径非法。
                // 原来直接 img.remove()，图片凭空消失、页面上什么都不剩，
                // 读者完全无从判断是「作者没放图」还是「平台不给看」。
                // 改为留下占位：alt 文本照旧显示，缺失原因也写在 title 里。
                img.replaceWith(placeholder("图片在库根之外，无法显示", img.attr("alt")));
                continue;
            }
            img.attr("src", ASSET_TOKEN + encodePath(resolved));
            img.attr("loading", "lazy");
        }
    }

    /**
     * 图片不可用时的占位元素。
     *
     * <p>用文字块而不是 {@code <img src="">}：后者会让浏览器再次发起一次无意义请求，
     * 而空 src 在部分浏览器上还会回退到当前页面 URL。</p>
     */
    private static Element placeholder(String reason) {
        return placeholder(reason, "");
    }

    private static Element placeholder(String reason, String alt) {
        Element el = new Element("span");
        el.addClass("md-img-missing");
        el.attr("title", reason);
        el.text(alt == null || alt.isBlank() ? "图片无法显示" : alt);
        return el;
    }

    private void rewriteLinks(Element body, String docPath, Variant variant) {
        for (Element link : body.select("a[href]")) {
            String href = link.attr("href").trim();
            if (href.isEmpty()) {
                continue;
            }
            if (href.startsWith("#")) {
                link.attr("class", appendClass(link.attr("class"), "md-anchor"));
                continue;
            }
            if (href.startsWith("mailto:") || isExternal(href) || isAbsolute(href)) {
                if (isExternal(href)) {
                    link.attr("target", "_blank");
                    link.attr("rel", "noopener noreferrer");
                }
                continue;
            }

            String bare = stripAnchor(href);
            String anchor = anchorOf(href);

            if (isMarkdownLink(bare)) {
                if (variant == Variant.DOC) {
                    degrade(link, "该文档未包含在此分享中");
                    continue;
                }
                String target = resolveRelative(docPath, bare);
                if (target == null) {
                    degrade(link, "目标文档不在当前知识库内");
                    continue;
                }
                link.attr("href", DOC_TOKEN + encodePath(target) + anchor);
                continue;
            }

            if (FileNameUtil.isImage(bare)) {
                String target = resolveRelative(docPath, bare);
                if (target == null) {
                    degrade(link, "资源不在当前知识库内");
                    continue;
                }
                link.attr("href", ASSET_TOKEN + encodePath(target));
                link.attr("target", "_blank");
                link.attr("rel", "noopener noreferrer");
                continue;
            }

            degrade(link, "附件不支持在线访问");
        }
    }

    /**
     * URL 方案白名单校验（渲染阶段最后一道防火墙）。
     *
     * <p>仅放行：站内根路径、页内锚点、http(s)、mailto、data:image。
     * 其余（javascript:、vbscript:、协议相对地址 //host 等）直接摘除属性或移除元素。</p>
     */
    private void enforceSafeUrls(Element body) {
        for (Element link : body.select("a[href]")) {
            if (!isSafeUrl(link.attr("href"))) {
                link.removeAttr("href");
            }
        }
        List<Element> unsafeImages = new ArrayList<>();
        for (Element img : body.select("img[src]")) {
            if (!isSafeUrl(img.attr("src"))) {
                unsafeImages.add(img);
            }
        }
        unsafeImages.forEach(Element::remove);
    }

    private static boolean isSafeUrl(String value) {
        if (value == null) {
            return false;
        }
        String url = value.trim().toLowerCase(Locale.ROOT);
        if (url.isEmpty() || url.startsWith("//")) {
            return false;
        }
        return url.startsWith("/")
                || url.startsWith("#")
                || url.startsWith("http://")
                || url.startsWith("https://")
                || url.startsWith("mailto:")
                || url.startsWith("data:image/");
    }

    private void degrade(Element link, String tip) {
        Element span = new Element("span");
        span.attr("class", "md-link-disabled");
        span.attr("title", tip);
        span.text(link.text());
        link.replaceWith(span);
    }

    private String uniqueId(String text, Set<String> used) {
        String base = text.toLowerCase()
                .replaceAll("[\\s\\u00a0]+", "-")
                .replaceAll("[^\\p{L}\\p{N}\\-_]", "")
                .replaceAll("-{2,}", "-");
        base = base.replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "section";
        }
        if (base.length() > 64) {
            base = base.substring(0, 64);
        }
        String candidate = base;
        int index = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + index++;
        }
        return candidate;
    }

    /** 依据当前文档所在目录解析相对路径；越出知识库根返回 null。 */
    private String resolveRelative(String docPath, String relative) {
        String decoded;
        try {
            decoded = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = relative;
        }
        String docDir = PathGuard.parentOf(docPath);
        String combined = docDir.isEmpty() ? decoded : docDir + "/" + decoded;
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : combined.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (stack.isEmpty()) {
                    return null;
                }
                stack.removeLast();
                continue;
            }
            if (PathGuard.containsControlChar(segment)) {
                return null;
            }
            stack.addLast(segment);
        }
        return stack.isEmpty() ? null : String.join("/", stack);
    }

    private String encodePath(String path) {
        return PathEncoder.encodePath(path);
    }

    private static boolean isAbsolute(String value) {
        return value.startsWith("/") || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private static boolean isExternal(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static boolean isMarkdownLink(String value) {
        String lower = value.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private static String stripAnchor(String value) {
        int hash = value.indexOf('#');
        int query = value.indexOf('?');
        int cut = value.length();
        if (hash >= 0) {
            cut = Math.min(cut, hash);
        }
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        return value.substring(0, cut);
    }

    private static String anchorOf(String value) {
        int hash = value.indexOf('#');
        return hash < 0 ? "" : value.substring(hash);
    }

    private static String appendClass(String existing, String extra) {
        if (existing == null || existing.isBlank()) {
            return extra;
        }
        return existing.contains(extra) ? existing : existing + " " + extra;
    }

    private static String firstHeadingText(Element body) {
        Element heading = body.selectFirst("h1, h2, h3, h4, h5, h6");
        return heading == null ? null : heading.text().trim();
    }

    private static String firstParagraphText(Element body) {
        Element paragraph = body.selectFirst("p");
        if (paragraph == null) {
            return null;
        }
        String text = paragraph.text().trim();
        return text.isEmpty() ? null : text;
    }

    private static LocalDateTime parsePublishedAt(Map<String, Object> frontmatter, LocalDateTime fallback) {
        for (String key : List.of("date", "published", "updated", "created")) {
            String value = FrontMatterParser.str(frontmatter, key);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return TimeUtil.parseDateTime(value);
            } catch (Exception ignored) {
                // 忽略无法解析的日期
            }
        }
        return fallback;
    }

    /**
     * 源码行数。
     *
     * <p>只数换行符、且末尾那一次换行不算：编辑器里光标停在最后一行时行号就是这个数，
     * 直接 {@code split("\n").length} 会把结尾的换行多算一行。</p>
     */
    private static int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return content.endsWith("\n") ? lines - 1 : lines;
    }

    private static int countWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inLatinWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                count++;
                inLatinWord = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inLatinWord) {
                    count++;
                    inLatinWord = true;
                }
            } else {
                inLatinWord = false;
            }
        }
        return count;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** 大纲构建内部可变节点。 */
    private static final class MutableOutline {
        private final String id;
        private final String text;
        private final int level;
        private final List<MutableOutline> children = new ArrayList<>();

        private MutableOutline(String id, String text, int level) {
            this.id = id;
            this.text = text;
            this.level = level;
        }

        private OutlineNode toNode() {
            return new OutlineNode(id, text, level, children.stream().map(MutableOutline::toNode).toList());
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyFrontMatter() {
        return new LinkedHashMap<>();
    }
}
