package cn.minims.minidocs.markdown.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Markdown 渲染相关模型。
 */
public final class MarkdownModels {

    private MarkdownModels() {
    }

    /** 大纲节点（H1~H6 树）。 */
    public record OutlineNode(String id, String text, int level, List<OutlineNode> children) {
    }

    /** 文档元信息。 */
    public record DocMeta(String title, String summary, List<String> tags, String author,
                          LocalDateTime publishedAt, int wordCount, int readingMinutes, int lineCount,
                          long size, LocalDateTime modifiedAt) {
    }

    /** 渲染上下文：决定图片与文档链接如何改写。 */
    public record RenderContext(String assetPrefix, String docLinkPrefix, boolean singleDoc) {

        public static RenderContext of(String assetPrefix, String docLinkPrefix, boolean singleDoc) {
            return new RenderContext(assetPrefix, docLinkPrefix, singleDoc);
        }
    }

    /** 渲染变体：整库 / 单篇（影响 .md 链接的降级策略，缓存按变体区分）。 */
    public enum Variant {
        /** 整库分享 / 门户 / 后台：文档间可跳转 */
        KB,
        /** 单篇分享：指向其它文档的链接降级为不可点击文本 */
        DOC
    }

    /** 渲染结果。 */
    public record RenderResult(String html, List<OutlineNode> outline, Map<String, Object> frontmatter,
                               DocMeta meta, String etag) {
    }
}
