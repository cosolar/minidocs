package cn.minims.minidocs.mail;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.site.support.MailSettingsReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 邮件发送器的组装与测试发信的入口校验。
 *
 * <p>不连真的邮件服务器：这里能证明的是「配置怎么变成 JavaMail 属性表」和「什么情况下根本不发送」。
 * 一次真实的 SMTP 往返在单测里既慢又必然依赖外部服务，交给站点设置页的「测试发信」按钮
 * 由人按一次，比在 CI 里挂个假服务器划算。</p>
 */
@DisplayName("邮件发送：参数映射与测试发信")
class MailServiceTest {

    private MailSettingsReader reader;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        reader = mock(MailSettingsReader.class);
        SiteConfigService site = mock(SiteConfigService.class);
        when(site.get()).thenReturn(new SiteConfigVO("测试站点", null, null, null, null, null,
                null, null, null, false, null, false));
        mailService = new MailService(reader, site, new RateLimiter());
    }

    private MailSettings settings(String security) {
        return new MailSettings("smtp.example.test", 465, security, "bot@example.test",
                "s3cret", "bot@example.test", false, false);
    }

    @Test
    @DisplayName("465 走隐式 SSL：开 ssl.enable，且不再叠 STARTTLS")
    void implicitSslOnPort465() {
        JavaMailSenderImpl sender = mailService.senderOf(settings(null));

        assertThat(sender.getHost()).isEqualTo("smtp.example.test");
        assertThat(sender.getPort()).isEqualTo(465);
        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.ssl.enable", "true")
                .doesNotContainKey("mail.smtp.starttls.enable");
    }

    @Test
    @DisplayName("587 默认 STARTTLS 且是必需级：服务器不支持时宁可失败也不降级成明文发口令")
    void starttlsIsRequired() {
        MailSettings starttls = new MailSettings("smtp.example.test", 587, "starttls",
                "bot@example.test", "s3cret", "bot@example.test", false, false);

        assertThat(mailService.senderOf(starttls).getJavaMailProperties())
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .doesNotContainKey("mail.smtp.ssl.enable");
    }

    @Test
    @DisplayName("显式选 none 就真明文：管理员要的匿名内网中继不该被偷偷升级")
    void explicitNoneStaysCleartext() {
        MailSettings none = new MailSettings("10.0.0.8", 25, "none",
                null, null, "bot@intranet", false, false);

        assertThat(mailService.senderOf(none).getJavaMailProperties())
                .doesNotContainKey("mail.smtp.ssl.enable")
                .doesNotContainKey("mail.smtp.starttls.enable");
    }

    @Test
    @DisplayName("没填用户名就不要求认证，口令也不会带着走")
    void anonymousRelaySkipsAuth() {
        MailSettings anonymous = new MailSettings("10.0.0.8", 25, "none",
                null, null, "bot@intranet", false, false);

        JavaMailSenderImpl sender = mailService.senderOf(anonymous);
        assertThat(sender.getUsername()).isNull();
        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false");
    }

    @Test
    @DisplayName("半截凭据（只有用户名）走到发送器也不发起 AUTH —— 保存环节本就会先拦住这种组合")
    void usernameWithoutPasswordSkipsAuth() {
        MailSettings identityOnly = new MailSettings("10.0.0.8", 25, "none",
                "bot@example.test", null, "bot@example.test", false, false);

        JavaMailSenderImpl sender = mailService.senderOf(identityOnly);
        assertThat(sender.getUsername()).isNull();
        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false");
    }

    @Test
    @DisplayName("每次发送都带超时：挂在 TCP 上的线程会把发信池占满，后面的注册再也没有邮件")
    void timeoutsAreAlwaysSet() {
        assertThat(mailService.senderOf(settings(null)).getJavaMailProperties())
                .containsKey("mail.smtp.connectiontimeout")
                .containsKey("mail.smtp.timeout")
                .containsKey("mail.smtp.writetimeout");
    }

    @Test
    @DisplayName("收件地址不像地址时直接报参数错，不去碰 SMTP")
    void testSendValidatesRecipientFirst() {
        when(reader.read()).thenReturn(settings(null));

        assertThatThrownBy(() -> mailService.sendTest("不是邮箱", 1L, "http://localhost:9098/minidocs"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("收件地址格式");
    }

    @Test
    @DisplayName("测试发信按管理员限速，且限速在读配置之前")
    void testSendIsRateLimited() {
        // 参数没配齐：每一次都会走到「没配好」这条分支，但配额已经在前面扣掉了
        when(reader.read()).thenReturn(new MailSettings(null, null, null, null, null, null, false, false));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> mailService.sendTest("a@b.co", 7L, "http://localhost:9098/minidocs"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("邮件服务");
        }
        assertThatThrownBy(() -> mailService.sendTest("a@b.co", 7L, "http://localhost:9098/minidocs"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESEND_TOO_FREQUENT);

        // 限速键按管理员分：别人按按钮不该被牵连
        assertThatThrownBy(() -> mailService.sendTest("a@b.co", 8L, "http://localhost:9098/minidocs"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邮件服务");
    }

    @Test
    @DisplayName("正文转义站点名与昵称：一封验证邮件里的可点内容，攻击面比普通页面大")
    void bodiesEscapeUserControlledText() {
        String body = MailBodies.verification("Mini<script>", null, null, null,
                "<img src=//evil/x>",
                "https://kb.example.test/api/auth/verify-email?token=abc", 24);

        assertThat(body).doesNotContain("<script>").doesNotContain("<img src=//evil");
        assertThat(body).contains("token=abc").contains("验证邮箱");
    }

    @Test
    @DisplayName("测试邮件里带上发件地址与发送时间：光说「成功」不够，管理员要核对的是发出去的那个地址")
    void testBodyCarriesFromAndTime() {
        String body = MailBodies.test("测试站点", null, "kb.example.test",
                "https://kb.example.test/logo-mark.png", "bot@example.test", new java.util.Date(0));

        assertThat(body).contains("bot@example.test").contains("1970-01-01")
                .contains("https://kb.example.test/logo-mark.png");
    }
}
