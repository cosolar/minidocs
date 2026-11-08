package cn.minims.minidocs.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数学公式保护：把 LaTeX 公式从 flexmark 的解析范围里摘出来。
 *
 * <p><b>为什么需要这一步：</b>flexmark 按 CommonMark 语义解析整篇文档，而数学公式里
 * 满是 Markdown 的保留字符 —— {@code x_{i}} 与 {@code y_{i}} 里的两个 {@code _} 会被
 * 配对成 {@code <em>}，{@code w^{*}} 与 {@code b^{*}} 里的两个 {@code *} 同理，
 * 行尾的 LaTeX 换行 {@code \\} 会被折叠成单个 {@code \}。公式被标签打断后，浏览器端
 * KaTeX auto-render（按单个文本节点匹配 {@code $...$} 分隔符）就再也配不上对，页面上
 * 于是露出整段原始 LaTeX（实测南瓜书第 3 章：14 个 display 公式挂 4 个，含
 * {@code _}/{@code *} 的内联公式大面积挂）。</p>
 *
 * <p><b>修法是「摘出来、渲染、放回去」：</b>解析前把 {@code $$...$$}、{@code $...$}、
 * {@code \[...\]}、{@code \(...\)} 四种公式整体替换为不透明占位符，flexmark 看到的只是
 * 普通文本，公式原文不经任何转义与强调处理；渲染完成后把占位符替换回 HTML 转义过的
 * 公式原文，交给浏览器端 auto-render 排版。公式对两套渲染器的语法差异完全免疫；
 * 占位符只存在于单次渲染的中间态，不落盘，因此天然幂等。</p>
 *
 * <p>保护范围刻意保守：围栏代码块与行内代码里的 {@code $} 一律不动；行内公式要求开口
 * {@code $} 之后不是空白、闭口 {@code $} 之前不是空白且不跨行 —— 「价格 $5 和 $10」
 * 这类白话不会被误当成公式；未闭合或超长的疑似公式保持原样不摘。</p>
 */
public final class MathGuard {

    /** 占位符：与 {@code MarkdownService} 的资源占位符同一族（@@md-xxx@@），不会与正文冲突。 */
    private static final String PREFIX = "@@md-math-";
    private static final String SUFFIX = "@@";

    /** display 公式最大长度：超过视为未闭合的杂散 {@code $$}，不摘取，避免整章被吞进公式。 */
    private static final int MAX_DISPLAY = 4000;
    /** 行内公式最大长度，同上。 */
    private static final int MAX_INLINE = 1000;

    /** 围栏代码块的开头行（缩进 ≤3 空格）。 */
    private static final Pattern FENCE_LINE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");

    /** 行内代码：成对的反引号包住至少一个非反引号字符（仅同行）。 */
    private static final Pattern INLINE_CODE = Pattern.compile("`+[^`]+`+");

    /** 屏蔽填充符：代码区在扫描缓冲区里替换成它，公式扫描直接跳过。 */
    private static final char MASKED = '\u0000';

    private MathGuard() {
    }

    /**
     * 摘取结果：{@code text} 是替换了占位符的 Markdown，{@code formulas} 按占位符序号
     * 保存公式原文（含定界符本身）。
     */
    public record Extraction(String text, List<String> formulas) {

        public boolean isEmpty() {
            return formulas.isEmpty();
        }
    }

    /**
     * 从 Markdown 里摘出全部数学公式，换成占位符。
     *
     * <p>没有公式时原样返回，避免无谓的整篇扫描开销之外的多余拷贝。</p>
     */
    public static Extraction extract(String markdown) {
        List<String> formulas = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return new Extraction(markdown == null ? "" : markdown, formulas);
        }
        if (!markdown.contains("$") && !markdown.contains("\\[") && !markdown.contains("\\(")) {
            return new Extraction(markdown, formulas);
        }
        // 屏蔽代码区（围栏块 + 行内代码），公式扫描看到的是打码后的缓冲区；
        // 摘取的下标在打码前后一致（填充符与原字符一一对应），原文可直接按区间截取
        char[] buf = markdown.toCharArray();
        maskCodeRegions(markdown, buf);

        StringBuilder out = new StringBuilder(markdown.length());
        int n = buf.length;
        int i = 0;
        while (i < n) {
            if (buf[i] == MASKED) {
                // 代码区原样保留，flexmark 自己处理
                out.append(markdown.charAt(i));
                i++;
                continue;
            }
            char c = buf[i];
            boolean escaped = i > 0 && markdown.charAt(i - 1) == '\\';
            // \[...\] 与 \(...\)：LaTeX 另一套定界符，允许跨行
            if (c == '\\' && !escaped && i + 1 < n && (buf[i + 1] == '[' || buf[i + 1] == '(')) {
                String close = buf[i + 1] == '[' ? "\\]" : "\\)";
                int end = findClose(buf, markdown, i + 2, close, MAX_DISPLAY);
                if (end > 0) {
                    appendToken(out, formulas, markdown.substring(i, end));
                    i = end;
                    continue;
                }
            }
            // $$...$$：display 公式，允许跨行
            if (c == '$' && !escaped && i + 1 < n && buf[i + 1] == '$') {
                int end = findClose(buf, markdown, i + 2, "$$", MAX_DISPLAY);
                if (end > 0 && end - 2 > i + 2) {
                    // 内容为空的 $$$$ 不摘，保持原样
                    appendToken(out, formulas, markdown.substring(i, end));
                    i = end;
                    continue;
                }
                out.append("$$");
                i += 2;
                continue;
            }
            // $...$：行内公式，不跨行、两端不留空白
            if (c == '$' && !escaped && i + 1 < n && !Character.isWhitespace(buf[i + 1])
                    && buf[i + 1] != '$') {
                int end = findInlineClose(buf, markdown, i + 1);
                if (end > 0 && end - i <= MAX_INLINE) {
                    appendToken(out, formulas, markdown.substring(i, end));
                    i = end;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return new Extraction(out.toString(), formulas);
    }

    /**
     * 把占位符替换回 HTML 转义过的公式原文。
     *
     * <p>必须发生在 jsoup 解析之前：公式里的 {@code <}/{@code >}/{@code &} 要以文本
     * 形态进入 HTML，交给浏览器端 KaTeX；转义过的实体在 jsoup 里仍是文本节点，
     * 白名单过滤不会碰它。</p>
     */
    public static String restore(String html, List<String> formulas) {
        if (html == null || html.isEmpty() || formulas.isEmpty()) {
            return html;
        }
        for (int i = 0; i < formulas.size(); i++) {
            html = html.replace(PREFIX + i + SUFFIX, escapeHtml(formulas.get(i)));
        }
        return html;
    }

    /* ------------------------------------------------------------------ 内部 */

    private static void appendToken(StringBuilder out, List<String> formulas, String formula) {
        out.append(PREFIX).append(formulas.size()).append(SUFFIX);
        formulas.add(formula);
    }

    /**
     * 从 {@code from} 开始找闭合定界符，返回「闭合定界符结束位置」下标，找不到返回 -1。
     * 跳过打码区与被反斜杠转义的闭合符。
     */
    private static int findClose(char[] buf, String md, int from, String close, int maxLen) {
        int n = buf.length;
        for (int j = from; j < n && j <= from + maxLen; j++) {
            if (buf[j] == MASKED) {
                continue;
            }
            if (buf[j] == '\\' && j + 1 < n) {
                j++; // 跳过转义对
                continue;
            }
            if (matchesAt(buf, j, close) && md.charAt(j - 1) != '\\') {
                return j + close.length();
            }
        }
        return -1;
    }

    /**
     * 行内公式的闭合 {@code $}：不跨行、前面不是空白、不是 {@code $$} 的一半、
     * 未被转义。遇到不合规的 {@code $} 继续向后看（保守：宁可不摘也不能错摘）。
     */
    private static int findInlineClose(char[] buf, String md, int from) {
        int n = buf.length;
        for (int j = from; j < n; j++) {
            char c = buf[j];
            if (c == '\n' || c == MASKED) {
                return -1;
            }
            if (c == '\\' && j + 1 < n) {
                j++;
                continue;
            }
            if (c != '$') {
                continue;
            }
            if (Character.isWhitespace(md.charAt(j - 1)) || md.charAt(j - 1) == '\\') {
                continue;
            }
            if (j + 1 < n && buf[j + 1] == '$') {
                return -1;
            }
            return j + 1;
        }
        return -1;
    }

    private static boolean matchesAt(char[] buf, int at, String needle) {
        if (at + needle.length() > buf.length) {
            return false;
        }
        for (int k = 0; k < needle.length(); k++) {
            if (buf[at + k] != needle.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把围栏代码块（整块）与行内代码（同行片段）在缓冲区里打码。
     * 换行符保留原位，行内公式的「不跨行」判断照常工作。
     */
    private static void maskCodeRegions(String markdown, char[] buf) {
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        int lineStart = 0;
        String[] lines = markdown.split("\n", -1);
        for (String line : lines) {
            int lineEnd = lineStart + line.length();
            if (inFence) {
                if (isClosingFence(line, fenceChar, fenceLen)) {
                    inFence = false;
                }
                mask(buf, lineStart, lineEnd);
            } else {
                Matcher fence = FENCE_LINE.matcher(line);
                if (fence.find()) {
                    inFence = true;
                    fenceChar = fence.group(1).charAt(0);
                    fenceLen = fence.group(1).length();
                    mask(buf, lineStart, lineEnd);
                } else {
                    Matcher inline = INLINE_CODE.matcher(line);
                    while (inline.find()) {
                        mask(buf, lineStart + inline.start(), lineStart + inline.end());
                    }
                }
            }
            lineStart = lineEnd + 1; // 越过换行符
        }
    }

    /** 闭合围栏：同一字符、长度不短于开头，且整行只有它（CRLF 已含在 line 里）。 */
    private static boolean isClosingFence(String line, char fenceChar, int fenceLen) {
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        int k = 0;
        while (k < t.length() && t.charAt(k) == fenceChar) {
            k++;
        }
        return k >= fenceLen && k == t.length();
    }

    private static void mask(char[] buf, int start, int end) {
        for (int k = Math.max(0, start); k < end && k < buf.length; k++) {
            if (buf[k] != '\n' && buf[k] != '\r') {
                buf[k] = MASKED;
            }
        }
    }

    /** 文本节点级别的转义：公式原文里最常见的 {@code <} {@code >} {@code &}。 */
    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
