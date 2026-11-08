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
 * 数学公式与 Markdown 语法的隔离：公式里的 {@code _} {@code *} {@code \\} 是 LaTeX
 * 语义，不能被当成强调 / 转义处理。
 *
 * <p>背景（南瓜书实测）：display 公式里成对的 {@code _} / {@code *} 会被 flexmark
 * 配对成 {@code <em>}，把 {@code $$...$$} 撕成多个文本节点，浏览器端 KaTeX
 * auto-render 按文本节点匹配分隔符就再也配不上，页面露出原始 LaTeX。修复是渲染前
 * 摘出公式、渲染后放回（见 {@link MathGuard}），这组测试守住这条隔离边界。</p>
 */
@DisplayName("Markdown 渲染：数学公式保护")
class MathGuardTest {

    @TempDir
    Path kbRoot;

    private MarkdownService service;

    @BeforeEach
    void setUp() {
        service = new MarkdownService(new MiniDocsProperties(), new MarkdownSanitizer());
    }

    @Test
    @DisplayName("跨行 $$ 公式里的下标不生成 <em>，原文完整还原")
    void displayMathKeepsUnderscores() throws IOException {
        String html = render("""
                式(3.9)推导：

                $$
                \\begin{aligned}
                \\sum_{i=1}^{m}\\left(y_{i}-\\boldsymbol{w}^{\\mathrm{T}}\\boldsymbol{x}_{i}-b\\right)^{2}
                \\end{aligned}
                $$

                以上是目标函数。
                """);

        assertThat(html).contains("\\begin{aligned}");
        assertThat(html).doesNotContain("<em>");
        assertThat(html).doesNotContain("@@md-math");
    }

    @Test
    @DisplayName("$$ 公式里的 LaTeX 换行 \\\\ 不被折叠成单个 \\")
    void displayMathKeepsDoubleBackslash() throws IOException {
        String html = render("""
                $$
                \\begin{aligned}
                \\text{s.t.} & w > 0, \\\\
                & b < 0.
                \\end{aligned}
                $$
                """);

        assertThat(html).contains("\\\\");
    }

    @Test
    @DisplayName("内联公式里的成对 * 不生成 <em>")
    void inlineMathKeepsAsterisk() throws IOException {
        String html = render("参数取值 $(w^{*},b^{*})$ 表示最优解。");

        assertThat(html).contains("(w^{*},b^{*})");
        assertThat(html).doesNotContain("<em>");
    }

    @Test
    @DisplayName("公式里的 < 与 > 保持文本形态，不被当成标签")
    void mathComparisonStaysText() throws IOException {
        String html = render("约束为 $w > 0$ 且 $b < 0$ 的范围。");

        assertThat(html).contains("w &gt; 0");
        assertThat(html).contains("b &lt; 0");
        assertThat(html).doesNotContain("@@md-math");
    }

    @Test
    @DisplayName("\\[ \\] 与 \\( \\) 定界符同样被保护")
    void bracketDelimitersAreProtected() throws IOException {
        String html = render("欧拉公式 \\[e^{i\\pi}+1=0\\] 与 \\(a_1+b_1\\) 都要保留。");

        assertThat(html).contains("e^{i\\pi}+1=0");
        assertThat(html).contains("a_1+b_1");
        assertThat(html).doesNotContain("<em>");
    }

    @Test
    @DisplayName("围栏代码块里的 $$ 原样进入 <code>，不参与公式配对")
    void fencedCodeIsUntouched() throws IOException {
        String html = render("""
                ```python
                # $$\\boldsymbol{x}_{i}$$ 只是注释
                print(x)
                ```
                """);

        assertThat(html).contains("<code");
        assertThat(html).doesNotContain("katex");
        // 代码块内容原样保留（含下标与 $$）
        assertThat(html).contains("$$\\boldsymbol{x}_{i}$$");
    }

    @Test
    @DisplayName("行内代码里的 $ 不参与配对")
    void inlineCodeIsUntouched() throws IOException {
        String html = render("写法 `$x_1$` 在代码里只是字符串。");

        assertThat(html).contains("<code>$x_1$</code>");
    }

    @Test
    @DisplayName("白话里的美元金额不会被误当成公式")
    void proseDollarAmountsStayProse() throws IOException {
        String html = render("价格在 $5 和 $10 之间浮动。");

        assertThat(html).contains("$5 和 $10");
        assertThat(html).doesNotContain("@@md-math");
    }

    @Test
    @DisplayName("未闭合的 $$ 保持原样，不吞掉后文")
    void unclosedDisplayMathStaysAsIs() throws IOException {
        String html = render("""
                开头 $$
                这一整段都是正文，$$ 这里也不是公式。
                """);

        assertThat(html).contains("这一整段都是正文");
        assertThat(html).doesNotContain("@@md-math");
    }

    @Test
    @DisplayName("表格单元格里的公式同样被保护")
    void mathInsideTableCellIsProtected() throws IOException {
        String html = render("""
                | 符号 | 含义 |
                |:-----|:-----|
                | $x_i$ | 第 $i$ 个样本 |
                """);

        assertThat(html).contains("$x_i$");
        assertThat(html).contains("$i$");
        assertThat(html).doesNotContain("<em>");
    }

    @Test
    @DisplayName("多个公式按出现顺序一一还原")
    void multipleFormulasRestoredInOrder() throws IOException {
        String html = render("已知 $a_1$ 与 $b_2$，求 $\\sum_{i} x_i$。");

        assertThat(html).contains("$a_1$");
        assertThat(html).contains("$b_2$");
        assertThat(html).contains("$\\sum_{i} x_i$");
        assertThat(html).doesNotContain("<em>");
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
