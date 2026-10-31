package cn.minims.minidocs.markdown;

import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderContext;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderResult;
import cn.minims.minidocs.markdown.model.MarkdownModels.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阅读端与工作区预览的渲染一致性 + 原生 HTML 的安全边界。
 *
 * <p>背景：工作区预览（bytemd）渲染作者写的原生 HTML，阅读端若把它当纯文本丢弃，
 * 同一篇文档会出现「预览居中、阅读左对齐且链接失效」。现在 flexmark 会解析原生 HTML，
 * 安全性完全由 {@link MarkdownSanitizer} 的标签 / 属性白名单与
 * {@code MarkdownService#enforceSafeUrls} 的协议校验承担 —— 这两件事必须有测试守着，
 * 否则「顺手放宽一个标签」就会变成存储型 XSS。</p>
 */
@DisplayName("Markdown 渲染：原生 HTML 白名单与版式归一")
class MarkdownHtmlRenderTest {

    @TempDir
    Path kbRoot;

    private MarkdownService service;

    @BeforeEach
    void setUp() {
        service = new MarkdownService(new MiniDocsProperties(), new MarkdownSanitizer());
    }

    @Test
    @DisplayName("原生 HTML 的对齐写法保留为受控类名，不残留 align / style")
    void rawHtmlAlignBecomesClass() throws IOException {
        String html = render("""
                <div align="center">
                <h1>居中标题</h1>
                <p style="text-align: center">居中段落</p>
                </div>
                """);

        assertThat(html)
                .contains("md-align-center")
                .contains("居中标题")
                .contains("居中段落")
                .doesNotContain("align=")
                .doesNotContain("style=");
    }

    @Test
    @DisplayName("<center> 这种老写法同样转换成受控类名")
    void centerTagIsConverted() throws IOException {
        String html = render("<center><h2>老式居中</h2></center>");

        assertThat(html)
                .contains("md-align-center")
                .contains("老式居中")
                .doesNotContain("<center");
    }

    @Test
    @DisplayName("非对齐的内联样式整体丢弃，不扩大样式注入面")
    void nonAlignStylesAreDropped() throws IOException {
        String html = render("<p style=\"color: red; background: url(javascript:alert(1))\">带样式</p>");

        assertThat(html)
                .contains("带样式")
                .doesNotContain("style=")
                .doesNotContain("javascript:");
    }

    @Test
    @DisplayName("脚本、事件处理器与 javascript: 链接被剥离")
    void scriptsAndEventHandlersAreStripped() throws IOException {
        String html = render("""
                <script>alert(1)</script>

                <img src="a.png" onerror="alert(1)">

                <a href="javascript:alert(1)" onclick="alert(2)">点我</a>

                [普通链接](javascript:alert(3))
                """);

        assertThat(html)
                .doesNotContain("<script")
                .doesNotContain("onerror")
                .doesNotContain("onclick")
                .doesNotContain("javascript:")
                .contains("点我");
    }

    @Test
    @DisplayName("常规 Markdown 语法不受影响：标题锚点、表格、强调、外链")
    void plainMarkdownStillRenders() throws IOException {
        RenderResult result = renderResult("""
                # 标题

                | 名称 | 值 |
                | --- | --- |
                | a | b |

                **加粗** 与 [链接](https://example.com)
                """);
        String html = result.html();

        assertThat(html)
                .contains("<h1 id=\"标题\">")
                .contains("<table>")
                .contains("<strong>加粗</strong>")
                .contains("href=\"https://example.com\"");
        assertThat(result.outline()).hasSize(1);
    }

    private String render(String markdown) throws IOException {
        return renderResult(markdown).html();
    }

    private RenderResult renderResult(String markdown) throws IOException {
        Files.writeString(kbRoot.resolve("doc.md"), markdown, StandardCharsets.UTF_8);
        return service.render(kbRoot, 1L, "doc.md", Variant.KB,
                RenderContext.of("/minidocs/api/asset/", "/kb/", false));
    }
}
