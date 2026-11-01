package cn.minims.minidocs.mail;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.EmailUtil;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.site.support.MailSettingsReader;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.time.Duration;
import java.util.Date;
import java.util.Properties;

/**
 * 邮件发送：把站点配置里的 SMTP 参数变成一次真实的投递。
 *
 * <p>不用 {@code spring.mail.*} 那套自动配置：MiniDocs 的 SMTP 参数存在数据库里、
 * 由管理员在站点设置页随时改，而自动配置读的是启动时的 Environment。
 * 因此这里只用 starter 带的 JavaMail 客户端，发送器按当前配置现场构造 ——
 * 构造一个 {@link JavaMailSenderImpl} 不建连接、开销可忽略，也就省掉了「配置变了要失效缓存」这一层。</p>
 *
 * <p>本类只管「怎么投递」与「管理员按了测试按钮」，不认识用户、也不改账号状态：
 * 验证链接怎么拼、发信失败后账号算什么状态，都归注册那边（{@code EmailVerificationService}）。
 * 这样将来加「找回密码」只是多一个调用方，而不是往这里塞第二套状态机。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    /**
     * 连接与读写超时。
     *
     * <p>没有超时的话，一台不通的 SMTP 会让发信线程挂在 TCP 层默认超时（几分钟起步）上；
     * 注册验证走的是异步池，池子被挂住的线程占满后，后面注册的人就再也没有邮件了 —— 而且不报错。</p>
     */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int WRITE_TIMEOUT_MS = 20_000;

    private static final String ENCODING = "UTF-8";

    /**
     * 测试发信限速：一个管理员 1 分钟内最多 10 次。
     *
     * <p>不是防管理员本人，而是防「管理员口令泄漏后，这台 SMTP 变成别人的群发中继」——
     * 一个能任意指定收件地址、又能连发不限次数的接口，正是别人最想借的东西。</p>
     */
    private static final int TEST_LIMIT = 10;
    private static final Duration TEST_WINDOW = Duration.ofMinutes(1);
    private static final String TEST_KEY_PREFIX = "mail-test:";

    private final MailSettingsReader mailSettingsReader;
    private final SiteConfigService siteConfigService;
    private final RateLimiter rateLimiter;

    /**
     * 用给定配置投递一封 HTML 邮件。
     *
     * <p>配置由调用方给而不自己再查一遍，是因为调用方在此之前已经拿同一份配置做过判断
     * （要不要强制验证、按钮点下去该不该报错）。查两次就有两个答案。</p>
     *
     * <p>失败抛 {@link ErrorCode#MAIL_SEND_FAILED}，消息带上服务端给的原因 ——
     * 调用方要么据此放行用户（注册验证），要么把原因直接给管理员看（测试发信）。</p>
     */
    private void send(MailSettings settings, String to, String subject, String html) {
        try {
            JavaMailSenderImpl sender = senderOf(settings);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, ENCODING);
            helper.setFrom(new InternetAddress(settings.from(), siteName(), ENCODING));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("邮件已发送 to={} subject={}", to, subject);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            log.warn("邮件发送失败 to={} subject={}", to, subject, e);
            throw BizException.of(ErrorCode.MAIL_SEND_FAILED, "邮件发送失败：" + reasonOf(e));
        }
    }

    /**
     * 站点设置里「测试发信」按钮走这里。
     *
     * <p>收件地址由管理员现场填，因此和配置项一样要过形状校验；限速按管理员计，理由见
     * {@link #TEST_LIMIT}。发送是同步的 —— 点按钮的人就在等一个结果，
     * 异步只会让「失败」变成「没反应」。</p>
     */
    public void sendTest(String to, long actorUserId, String baseUrl) {
        if (!rateLimiter.tryAcquire(TEST_KEY_PREFIX + actorUserId, TEST_LIMIT, TEST_WINDOW)) {
            throw BizException.of(ErrorCode.RESEND_TOO_FREQUENT, "测试发信过于频繁，请一分钟后再试");
        }
        String address = EmailUtil.require(to, "收件地址");
        MailSettings settings = mailSettingsReader.read();
        requireReady(settings);
        Brand brand = brand(baseUrl);
        send(settings, address, "【" + brand.name() + "】邮件配置测试",
                MailBodies.test(brand.name(), brand.subtitle(), brand.host(), brand.logoUrl(),
                        settings.from(), new Date()));
    }

    /**
     * 注册邮箱验证邮件。
     *
     * <p>配置由调用方传进来：注册那一次已经读过一遍配置来判定「要不要强制验证」，
     * 再查一遍会出现两次判定结果不一致的窗口（管理员正好在中间改了设置）。
     * 用同一份配置，判定与发信说的是同一件事。</p>
     */
    public void sendVerification(MailSettings settings, String to, String displayName, String link,
                                 int validHours, String baseUrl) {
        requireReady(settings);
        Brand brand = brand(baseUrl);
        send(settings, to, "验证你的邮箱 - " + brand.name(),
                MailBodies.verification(brand.name(), brand.subtitle(), brand.host(), brand.logoUrl(),
                        displayName, link, validHours));
    }

    /**
     * 注册验证码邮件。
     *
     * <p>与验证链接不同，这里同步发送：用户正等在「获取验证码」按钮后面，
     * 失败必须当场报出来 —— 验证码没有第二个送达渠道，异步吞掉失败等于让人干等。</p>
     *
     * <p>Logo 必须是绝对地址：邮件客户端不认站点的相对路径。站内配了 Logo 就用它，
     * 没配则退回内置的 {@code /logo-mark.png}，两者都补上站点基址。</p>
     */
    public void sendRegisterCode(MailSettings settings, String to, String code, int validMinutes,
                                 String baseUrl) {
        requireReady(settings);
        Brand brand = brand(baseUrl);
        send(settings, to, "注册验证码 - " + brand.name(),
                MailBodies.registerCode(brand.name(), brand.subtitle(), brand.host(), to, code,
                        validMinutes, brand.logoUrl()));
    }

    /** 邮件里要用的品牌信息：站点名、副标题、域名与 Logo 绝对地址。 */
    private record Brand(String name, String subtitle, String host, String logoUrl) {
    }

    /**
     * 读一次站点配置，把邮件里要用的品牌信息一次取齐。
     *
     * <p>Logo 先看管理员上传的那张，没有就退回内置的 {@code /logo-mark.png}；
     * 两者都拼成绝对地址，邮件客户端才加载得到。</p>
     */
    private Brand brand(String baseUrl) {
        SiteConfigVO site = siteConfigService.get();
        String logoSrc = site.logoSrc() == null || site.logoSrc().isBlank()
                ? AppPaths.of("/logo-mark.png")
                : site.logoSrc();
        return new Brand(site.name(), site.subtitle(), hostOf(baseUrl),
                absoluteUrl(baseUrl, logoSrc));
    }

    /**
     * 站点基址 + 站内路径。
     *
     * <p>两处坑都要兜住：配置里可能只写域名（没有协议），邮件客户端会把它当相对地址；
     * 而站点基址可能已经带了上下文前缀（{@code /minidocs}），Logo 路径也自带同一段，
     * 直接拼会变成 {@code /minidocs/minidocs/...}。</p>
     *
     * <p>基址为空时保持原路径（宁可图片不显示，也不要拼出一个坏地址）。</p>
     */
    private static String absoluteUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            return path;
        }
        if (!base.contains("://")) {
            base = "https://" + base;
        }
        String prefix = AppPaths.prefix();
        String p = path.startsWith("/") ? path : "/" + path;
        if (!prefix.isEmpty() && base.endsWith(prefix) && p.startsWith(prefix + "/")) {
            p = p.substring(prefix.length());
        }
        return base + p;
    }

    /** 页脚那行域名：从站点基址里取 host，取不到就不渲染。 */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String base = baseUrl.trim();
        if (!base.contains("://")) {
            base = "https://" + base;
        }
        try {
            String host = URI.create(base).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 齐备性判定：交给 {@link MailSettings#transportReady()}，报错只说人能改的那几项。 */
    private void requireReady(MailSettings settings) {
        if (!settings.transportReady()) {
            throw BizException.param(settings.credentialUnusable()
                    ? "已存的 SMTP 口令解不开（加密密钥可能已变更），请重新填写口令"
                    : settings.credentialIncomplete()
                    ? "填了 SMTP 用户名却没填口令：这台服务器要求先认证，请补上授权码；中继不需要认证就把用户名清空"
                    : "站点没有配好可用的邮件服务（SMTP 主机、端口、发件地址）");
        }
    }

    /**
     * 按当前配置现场组装发送器。
     *
     * <p>三种保护方式的差异只体现在两对属性上；协议一律用 {@code smtp}，
     * 让隐式 SSL 也走 {@code mail.smtp.ssl.enable} —— 换成 {@code smtps} 的话
     * 所有属性前缀都要跟着换，漏改一个就变成「配了等于没配」。</p>
     *
     * <p>STARTTLS 用 {@code required}：服务器不 advertised 时直接失败，
     * 而不是退化成明文把口令发出去。真需要明文内网中继的管理员显式选 none。</p>
     *
     * <p>包内可见：这段映射是「配了但连不上」最可能的出处，而它的对错在没接通服务器的
     * 单测里看不见 —— 留一个能直接断言属性表的入口，比让测试去猜一次真实连接的结果便宜。</p>
     */
    JavaMailSenderImpl senderOf(MailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setDefaultEncoding(ENCODING);
        // 只有用户名和口令都在才发 AUTH：只填了用户名（内网中继常这样，把身份写进 EHLO 就够了）
        // 强行认证会让 JavaMail 直接抛「no password specified」，看起来像配错了主机
        boolean useAuth = settings.username() != null && settings.password() != null;
        if (useAuth) {
            sender.setUsername(settings.username());
            sender.setPassword(settings.password());
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", useAuth ? "true" : "false");
        props.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(READ_TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(WRITE_TIMEOUT_MS));
        switch (settings.effectiveSecurity()) {
            case MailSettings.SECURITY_SSL -> props.put("mail.smtp.ssl.enable", "true");
            case MailSettings.SECURITY_NONE -> {
                // 显式明文：什么都不加。选到这里的人知道自己是在内网中继上省事
            }
            default -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
        }
        return sender;
    }

    /** 发件人显示名用站点名：收件人看到的应该是「MiniDocs 的验证邮件」，而不是一个陌生主机名。 */
    private String siteName() {
        return siteConfigService.get().name();
    }

    /** 取因果链最里层的原因：外层永远是「Could not connect」这种转述，真正的原因在最里面。 */
    private static String reasonOf(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
