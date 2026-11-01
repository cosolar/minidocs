package cn.minims.minidocs.site.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点级配置（JSON 字符串，MySQL 存 JSON / SQLite 存 TEXT）。
 *
 * <p>整张表只有 {@code id = 1} 这一行，见 {@code V7__site_config.sql}。</p>
 *
 * <p>{@code config} 里的键名常量也放在这里：它们描述的是这一列 JSON 的结构，
 * 写入侧（{@code SiteConfigServiceImpl}）与读取侧（{@code SiteBaseUrlResolver}）都要用，
 * 放一处才不会各写一份字符串拼错。</p>
 */
@Data
@TableName("site_config")
public class SiteConfig {

    /** 单行表的固定主键 */
    public static final int SINGLETON_ID = 1;

    /** JSON 键：站点名称 */
    public static final String KEY_NAME = "name";
    /** JSON 键：站点副标题 */
    public static final String KEY_SUBTITLE = "subtitle";
    /** JSON 键：站点 Logo 文件名 */
    public static final String KEY_LOGO = "logo";
    /** JSON 键：站点图标文件名（正方形；浏览器标签页与未配 Logo 时的品牌位） */
    public static final String KEY_ICON = "icon";
    /** JSON 键：Logo 显示宽度（像素）；缺键表示跟随各品牌位的默认尺寸 */
    public static final String KEY_LOGO_WIDTH = "logoWidth";
    /** JSON 键：Logo 显示高度（像素）；缺键表示跟随各品牌位的默认尺寸 */
    public static final String KEY_LOGO_HEIGHT = "logoHeight";
    /** JSON 键：只显示 Logo 图片、隐藏站点名称与副标题；缺键即 false */
    public static final String KEY_LOGO_ONLY = "logoOnly";
    /** JSON 键：站点基址（对外分享链接的协议 + 主机 [+ 路径]，可由站点设置页修改） */
    public static final String KEY_BASE_URL = "baseUrl";
    /** JSON 键：默认阅读主题 id（只作用于没选过主题的新访客；缺键表示用内置默认） */
    public static final String KEY_READER_THEME = "readerTheme";

    // ---- 邮件发送（注册邮箱验证用）----------------------------------------------------
    //
    // 七个键分两层：前六个是「怎么发出去」的传输参数，最后一个是「要不要挡人」的策略开关。
    // 分成两层是因为它们会被分别关掉 —— 只想用 SMTP 发通知而不想拦注册，或者反过来，
    // 都是自部署里真实的需求；合成一个开关就只能二选一。
    //
    // 「传输参数是否可用」不设独立开关：六个键齐了才算配好（判定见 SiteMailSettingsResolver）。
    // 再加一个 enabled 会多出「开关开着但参数残缺」这种既难解释又难测的状态。

    /** JSON 键：SMTP 服务器主机名 */
    public static final String KEY_SMTP_HOST = "smtpHost";
    /** JSON 键：SMTP 端口（465 = SSL，587 = STARTTLS，25 = 明文） */
    public static final String KEY_SMTP_PORT = "smtpPort";
    /** JSON 键：连接保护方式 ssl / starttls / none，缺省按端口推断 */
    public static final String KEY_SMTP_SECURITY = "smtpSecurity";
    /** JSON 键：登录用户名，多数服务商即发件地址本身 */
    public static final String KEY_SMTP_USERNAME = "smtpUsername";
    /** JSON 键：登录口令的 AES-GCM 密文（见 CryptoUtil），绝不明文落库、也不回显给前端 */
    public static final String KEY_SMTP_PASSWORD = "smtpPassword";
    /** JSON 键：发件地址（邮件 From 头，也是退信的去向） */
    public static final String KEY_SMTP_FROM = "smtpFrom";
    /** JSON 键：是否要求注册邮箱验证；缺键即 false。只在传输参数齐备时才真正生效 */
    public static final String KEY_EMAIL_VERIFY_ENABLED = "emailVerifyEnabled";

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String config;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
