package cn.minims.minidocs.reader.model;

import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.markdown.model.MarkdownModels.OutlineNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读页视图模型（门户 / 分享共用）。
 */
@Data
public class ReadView {

    /** portal | share */
    private String mode = "portal";

    private Long kbId;
    private String kbName;
    private String kbSlug;
    /**
     * 该库所属组织的 slug。
     *
     * <p>阅读页要给出「进入工作区」的入口，而那条地址是 {@code /console/{org}/kbs/{slug}}：slug 自 v2
     * 起只在组织内唯一，前端手里没有组织段就拼不出这个链接，让它从当前 URL 反推等于把路由形状
     * 抄第二份。分享模式没有组织上下文，字段留空。</p>
     */
    private String orgSlug;
    private String kbDescription;
    /**
     * 知识库封面在当前场景下的取图地址。
     *
     * <p>前缀必须是本场景自己的资源路由（门户是 {@code /kb/{org}/{slug}/asset/}，分享是
     * {@code /share/{token}/asset/}）：封面落在库的 vault 里，走另一侧的资源端点会撞上权限门。</p>
     */
    private String kbCoverSrc;
    /** 知识库最近更新时间（展示用文案），分享页左栏「共 N 篇 · 更新 …」用。 */
    private String kbUpdatedText;
    private List<String> kbTags = new ArrayList<>();
    private String visibility = "private";
    private boolean publicKb;
    private int docCount;
    private int tagCount;

    /** 文档树（整库阅读时展示） */
    private List<DocNode> tree = new ArrayList<>();
    private boolean showTree;
    /**
     * 分享页顶部导航条；分享者没配菜单时为空。
     *
     * <p>挂在阅读视图上而不是另开一个接口：导航条与目录树同源同命，分两次请求只会让
     * 「树到了、菜单还没到」变成一个要额外处理的中态。</p>
     */
    private List<NavMenuItem> menu = new ArrayList<>();

    private String currentPath;
    private String currentName;
    private boolean empty;

    private String html = "";
    private List<OutlineNode> outline = new ArrayList<>();
    private String etag;

    private String title;
    private String summary;
    private List<String> tags = new ArrayList<>();
    private String author;
    private String publishedText;
    private String updatedText;
    private int wordCount;
    private int readingMinutes;
    /** 源码行数，底部状态栏展示 */
    private int lineCount;

    /** 仅分享页展示 */
    private Long views;
    private String shareToken;
    private String expiresText;
    private boolean singleDoc;

    private String prevPath;
    private String prevName;
    private String prevLink;
    private String nextPath;
    private String nextName;
    private String nextLink;

    /** 链接前缀 */
    private String assetPrefix;
    private String docLinkPrefix;

    /**
     * 站点基址（协议 + 主机 + 端口），分享模式下由 {@code ShareLinks} 统一给出。
     *
     * <p>顶栏那个「分享」按钮要复制当前页的绝对地址，而它自己在浏览器里只知道
     * {@code window.location} —— 反代后面那可能正是内网地址。这里下发同一个基址，
     * 前端拼上当前路由即可，两处链接从此同源。</p>
     */
    private String siteBase;

    /** 顶栏状态 */
    private boolean loggedIn;
    private boolean canManage;
}
