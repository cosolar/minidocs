package cn.minims.minidocs.site.support;

/**
 * 邮件发送与注册验证的最终生效配置（由 {@code site_config} 的邮件相关键合成）。
 *
 * <p>这里是「服务端内部」的形态，含解密后的口令，因此<b>绝不作为响应体回给前端</b>：
 * 对外一律走 {@code SiteDtos.SiteMailVO}，那里只有脱敏字段与 {@code smtpPasswordSet} 布尔。</p>
 *
 * @param host               SMTP 主机名，未配置为 null
 * @param port               SMTP 端口
 * @param security           连接保护方式（ssl / starttls / none），null = 按端口推断
 * @param username           登录用户名，明文
 * @param password           登录口令，明文；库里存的是密文，解不开时为 null 且 {@code credentialUnusable} 为 true
 * @param from               发件地址
 * @param verifyEnabled      管理员是否打开了「要求注册邮箱验证」开关
 * @param credentialUnusable 库里存过口令，但用当前密钥解不出来（多半是换过 {@code minidocs.jwt-secret}）
 */
public record MailSettings(String host, Integer port, String security, String username,
                           String password, String from, boolean verifyEnabled,
                           boolean credentialUnusable) {

    public static final String SECURITY_SSL = "ssl";
    public static final String SECURITY_STARTTLS = "starttls";
    public static final String SECURITY_NONE = "none";

    /** 465 是隐式 SSL 的传统端口，其余（587 / 25 / 2525）默认走 STARTTLS。 */
    private static final int IMPLICIT_SSL_PORT = 465;

    public static boolean isKnownSecurity(String value) {
        return SECURITY_SSL.equals(value) || SECURITY_STARTTLS.equals(value) || SECURITY_NONE.equals(value);
    }

    /** 实际使用的连接保护方式；没显式选时按端口推断，避免「465 + STARTTLS」这种必然连不上的组合。 */
    public String effectiveSecurity() {
        if (security != null) {
            return security;
        }
        if (port != null && port == IMPLICIT_SSL_PORT) {
            return SECURITY_SSL;
        }
        return SECURITY_STARTTLS;
    }

    /**
     * 传输参数是否齐到「有可能发出去」。
     *
     * <p>用户名与口令不计入硬要求：内网中继常允许匿名发信。主机与发件地址缺一个则必发不出。
     * 口令解不开算作不齐 —— 那种状态下发信必然失败，与其让注册者收不到邮件、
     * 被永久挡在登录页外，不如当作没配。</p>
     *
     * <p>只填用户名、没填口令也算不齐：那台服务器收到的是一次未经认证的投递，
     * QQ / 腾讯企业邮这类会直接回 {@code 503 need EHLO and AUTH first}，
     * 报错读起来像主机填错了，其实是口令没填。</p>
     */
    public boolean transportReady() {
        return host != null && port != null && from != null
                && !credentialUnusable && !credentialIncomplete();
    }

    /**
     * 用户名与口令只填了一半：要么补口令，要么把用户名也清空走匿名中继。
     *
     * <p>{@code credentialUnusable} 时口令同样是 null，但那一种有自己的解释（密钥换过），
     * 判断时要先排除它。</p>
     */
    public boolean credentialIncomplete() {
        return username != null && password == null;
    }

    /**
     * 是否要求注册先验证邮箱 —— 注册写状态、登录设闸门、前端决定是否强制填邮箱，都只看这一个判断。
     *
     * <p>开关打开但传输参数不齐时返回 false：闸门一旦生效，收不到验证邮件的人就再也进不来了，
     * 「防乱注册」会变成「谁也别注册」。宁可退回现状放行，也不制造打不开的门。</p>
     */
    public boolean verificationRequired() {
        return verifyEnabled && transportReady();
    }
}
