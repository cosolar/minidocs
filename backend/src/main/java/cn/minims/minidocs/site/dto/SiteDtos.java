package cn.minims.minidocs.site.dto;

/**
 * 站点配置 DTO。
 */
public final class SiteDtos {

    private SiteDtos() {
    }

    /**
     * 对外站点信息。
     *
     * <p>{@code logo} / {@code icon} 是库里存的文件名（仅用于「有没有配」这类判断），
     * {@code logoSrc} / {@code iconSrc} 才是可直接放进 {@code <img src>} 的地址 ——
     * 与头像同一套口径：换部署目录时不必洗库。{@code logo} 是横版品牌图，优先用于站内品牌位；
     * {@code icon} 是正方形标记，用于浏览器标签页，并在没有 Logo 时兜底品牌位。</p>
     *
     * <p>{@code baseUrl} 是「管理员配了什么」，不是「最终生效的基址」：没配时为 null，
     * 此时实际取值还会回落到环境变量 {@code PAGE_BASE_URL} 或按请求推导。
     * 两者混为一谈会让管理员以为自己配的值生效了，故分开两个概念，回显的表单用这个字段。</p>
     *
     * <p>{@code logoWidth} / {@code logoHeight} 是管理员指定的显示尺寸（像素），没配时为 null，
     * 由前端各品牌位用自己的默认值；{@code logoOnly} 为 true 时品牌位只出图、不出名称与副标题。
     * 三者都只影响渲染，不改图片本身 —— 非正方形图配 32×32 也仍然完整显示（contain）。</p>
     *
    /**
     * <p>{@code readerTheme} 是管理员选的「默认阅读主题」id：只对没选过主题的新访客生效，
     * 读者一旦在本地选过自己的，就以读者的为准。null = 未配置，前端用内置默认。</p>
     *
     * <p>{@code emailVerifyRequired} 是<b>当前真正生效</b>的注册验证要求，不只是管理员存下的开关：
     * 开关打开但 SMTP 没配齐时它是 false，因为那种状态下验证邮件发不出去，
     * 强制验证等于把所有注册者挡在门外。注册页用它决定邮箱是否必填、
     * 注册后是否要停在「请查收邮件」这一步 —— 前端不能自己猜，猜错就会出现
     * 「页面让人等邮件、后端却已经放行」这种两边都对不上的体验。</p>
     */
    public record SiteConfigVO(String name, String subtitle, String logo, String logoSrc, String icon, String iconSrc,
                               String baseUrl, Integer logoWidth, Integer logoHeight, Boolean logoOnly,
                               String readerTheme, Boolean emailVerifyRequired) {

        /** Logo 接口在上下文内的路径，对外地址要再补上上下文前缀（见 {@code AppPaths}） */
        public static final String LOGO_PREFIX = "/api/site-logos/";

        /** 站点图标接口在上下文内的路径，取值口径与 {@link #LOGO_PREFIX} 相同 */
        public static final String ICON_PREFIX = "/api/site-icons/";
    }

    /**
     * 邮件配置（只在平台管理员通道出现，公开接口不给）。
     *
     * <p><b>口令不回显</b>：{@code smtpPassword} 落库是 AES-GCM 密文，回显给前端等于把凭据
     * 从「只有服务端能用」降级成「浏览器历史里躺着一份」。表单因此拿到的是
     * {@code smtpPasswordSet}（有没有配过），留空保存即沿用原值。</p>
     *
     * <p>三个布尔把「配了 / 能用 / 生效」分开说，因为它们是三件事：
     * {@code mailReady} = 传输参数齐到有可能发出去；
     * {@code emailVerifyEnabled} = 管理员存下的开关原值；
     * {@code emailVerifyRequired} = 两者与起来后的实际行为。
     * 只回一个布尔的话，「我明明打开了开关为什么不生效」没有答案可给。</p>
     *
     * @param smtpHost             SMTP 主机名
     * @param smtpPort             SMTP 端口
     * @param smtpSecurity         连接保护方式原值；null = 未显式选择，按端口推断
     * @param smtpSecurityEffective 实际生效的保护方式，供面板把推断结果显示出来
     * @param smtpUsername         登录用户名
     * @param smtpFrom             发件地址
     * @param smtpPasswordSet      是否已存过登录口令（口令本身永不回传）
     * @param smtpCredentialUnusable 存过但当前密钥解不开 —— 需要人重新填一次口令
     * @param mailReady            传输参数是否齐备
     * @param emailVerifyEnabled   「要求注册邮箱验证」开关的原值
     * @param emailVerifyRequired  实际是否强制验证
     */
    public record SiteMailVO(String smtpHost, Integer smtpPort, String smtpSecurity, String smtpSecurityEffective,
                             String smtpUsername, String smtpFrom, Boolean smtpPasswordSet,
                             Boolean smtpCredentialUnusable, Boolean mailReady,
                             Boolean emailVerifyEnabled, Boolean emailVerifyRequired) {
    }

    /**
     * 测试发信请求。
     *
     * <p>收件地址不进配置存储：它只服务于「这一秒钟验证一下参数对不对」，
     * 存下来会变成第二个发件相关字段，还得解释它和 {@code smtpFrom} 谁说了算。</p>
     */
    public record MailTestRequest(String to) {
    }
}
