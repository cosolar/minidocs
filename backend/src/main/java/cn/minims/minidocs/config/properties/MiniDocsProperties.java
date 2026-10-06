package cn.minims.minidocs.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 平台级配置（前缀 minidocs）。
 */
@Data
@ConfigurationProperties(prefix = "minidocs")
public class MiniDocsProperties {

    /** 应用数据根目录，日志 / vaults / 开发库均位于其下 */
    private String vaultHome = "./data";

    /**
     * 可选的前端产物<b>覆盖</b>目录：其下须有 {@code admin/}（管理侧）与 {@code user/}（用户侧）两个子目录。
     *
     * <p>前端产物默认由 {@code front/} 构建直接写入
     * {@code backend/src/main/resources/static/}，打包后即为 {@code classpath:/static/}，
     * 因此本项<b>通常留空</b>。仅在需要「不重新打包即替换前端产物」（如运维热更新）时才配置；
     * 相对路径按进程工作目录解析，生产建议给绝对路径。classpath 中的产物始终优先。</p>
     */
    private String frontDir;

    /**
     * 页面基址（站点根，不含 context-path），用于拼分享链接。
     *
     * <p>分享链接是给<b>浏览器直接请求</b>的，落在前端那一侧：前端 SPA 挂在站点根时，链接就是
     * {@code https://host/share/{token}}，不能再带 {@code /minidocs}（那是接口所在的前缀）。
     * 留空（默认）表示沿用当前请求的 context-path，即「页面与后端同前缀」的单jar 部署方式。</p>
     *
     * <p>前后端分离部署（前端由 Nginx 伺服在根、后端挂在 context-path 下）时必须配置，
     * 例如 {@code https://kb.example.com}；要换前缀不必重打包前端，但改这个值要重启后端。</p>
     *
     * <p><b>优先级</b>：站点设置页里配的「站点基址」({@code site_config.config.baseUrl}) 高于本项。
     * 两者都没配才按当前请求推导。取值逻辑集中在 {@code SiteBaseUrlResolver}，
     * 本项因此退居「部署期兜底 / 平台注入配置」的角色，常规调整请到站点设置页改。</p>
     */
    private String pageBaseUrl;

    /** 数据库方言：sqlite / mysql，决定迁移脚本目录 */
    private String dbDialect = "sqlite";

    private String jwtSecret;
    private String jwtExpiresIn = "7d";

    /** 分享访问 Cookie 有效期（小时） */
    private int shareCookieHours = 12;

    private String adminUser = "admin";
    private String adminPass = "admin123";

    /** 是否开放自主注册（规范 §3.1 默认开启；关闭后只由管理员建号） */
    private boolean registerEnabled = true;

    /** 单篇 Markdown 读写上限 */
    private long docMaxSize = 2L * 1024 * 1024;
    /** 单张图片上限 */
    private long imageMaxSize = 20L * 1024 * 1024;
    /** 封面图上限 */
    private long coverMaxSize = 2L * 1024 * 1024;
    /** 头像图上限 */
    private long avatarMaxSize = 2L * 1024 * 1024;

    private int importMaxFiles = 200;
    private long importMaxSize = 50L * 1024 * 1024;

    /** 目录层级上限 */
    private int maxDepth = 6;

    private int renderCacheSize = 50;
    private int treeCacheSize = 20;

    private String logLevel = "info";

    public Path vaultRoot() {
        return Paths.get(vaultHome).toAbsolutePath().normalize();
    }

    /** 知识库根目录：VAULT_HOME/vaults */
    public Path vaultsDir() {
        return vaultRoot().resolve("vaults");
    }

    /** 头像目录：VAULT_HOME/avatars。头像属于账号而非某个库，故独立于 vaults 之外 */
    public Path avatarsDir() {
        return vaultRoot().resolve("avatars");
    }

    /** 站点资源目录：VAULT_HOME/site。站点 Logo 属于整个部署，同样独立于 vaults 之外 */
    public Path siteDir() {
        return vaultRoot().resolve("site");
    }

    public Path logsDir() {
        return vaultRoot().resolve("logs");
    }

    /**
     * 外部前端产物覆盖目录（未配置时为 {@link Optional#empty()}）。
     *
     * <p>默认产物随 jar 分发在 {@code classpath:/static/}，故通常为空。</p>
     */
    public Optional<Path> externalFrontRoot() {
        if (frontDir == null || frontDir.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Paths.get(frontDir).toAbsolutePath().normalize());
    }

    /**
     * CSP 的 {@code img-src} 是否允许任意 https 来源（默认允许）。
     *
     * <p>关掉之后，markdown 里的外链图片（自建图床、{@code img.shields.io} 徽章）会被浏览器
     * 按「已屏蔽：csp」丢弃，页面上只剩空白。知识库要读别人写的文档，外链图片是常态，
     * 所以默认开着；图片是惰性内容，{@code script-src} 仍为 {@code 'self'}，拿不到执行能力，
     * 代价只是图片请求会带上 Referer。</p>
     *
     * <p><b>注意</b>：前端与接口两侧各发一份 CSP（后端过滤器 + Nginx），改这一项的同时
     * 也要改 {@code deploy/nginx.conf} 里那份，否则两份取交集，仍会被更严的那份挡掉。</p>
     */
    private boolean cspAllowExternalImages = true;

    public static final List<String> DOC_EXTENSIONS = List.of(".md", ".markdown");

    public static final List<String> IMAGE_EXTENSIONS =
            Arrays.asList("png", "jpg", "jpeg", "gif", "webp", "svg", "avif", "bmp", "ico");

    /** 目录树中需要跳过的系统目录 */
    public static final List<String> SYSTEM_DIRS =
            Arrays.asList("node_modules", ".git", ".obsidian", ".idea", "assets");
}
