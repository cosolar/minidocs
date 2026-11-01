package cn.minims.minidocs.site;

import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.CryptoUtil;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.support.ImageValidator;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.dto.SiteDtos.SiteMailVO;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.mapper.SiteConfigMapper;
import cn.minims.minidocs.site.service.impl.SiteConfigServiceImpl;
import cn.minims.minidocs.site.support.MailSettingsReader;
import cn.minims.minidocs.site.support.SiteBaseUrlResolver;
import cn.minims.minidocs.theme.service.ThemeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 站点邮件配置的存取与脱敏回显。
 *
 * <p>与 {@link SiteLogoDisplayTest} 同一套内存版 mapper：要证明的是「写进去什么、读出来什么」，
 * 不启容器也不连邮件服务器。真正发信在 {@code MailSendTest} 里单独验。</p>
 *
 * <p>本类的重心是三条容易写错的语义：口令不回显但留空即沿用、开关与传输参数的先后关系、
 * 以及「公开的站点信息接口里那个 emailVerifyRequired」必须与后端闸门同源。</p>
 */
@DisplayName("站点邮件配置：脱敏回显、口令沿用与验证开关")
class SiteMailConfigTest {

    private static final String SECRET = "unit-test-secret";

    private AtomicReference<SiteConfig> row;
    private SiteConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        row = new AtomicReference<>();
        SiteConfigMapper mapper = mock(SiteConfigMapper.class);
        when(mapper.selectById(SiteConfig.SINGLETON_ID)).thenAnswer(call -> row.get());
        when(mapper.insert(any(SiteConfig.class))).thenAnswer(call -> {
            row.set(call.getArgument(0));
            return 1;
        });
        when(mapper.updateById(any(SiteConfig.class))).thenAnswer(call -> {
            row.set(call.getArgument(0));
            return 1;
        });
        MiniDocsProperties properties = mock(MiniDocsProperties.class);
        when(properties.getJwtSecret()).thenReturn(SECRET);
        service = new SiteConfigServiceImpl(mapper, properties, mock(ImageValidator.class),
                mock(AssetService.class), mock(SiteBaseUrlResolver.class), mock(ThemeService.class),
                new MailSettingsReader(mapper, properties));
    }

    /** 一份参数齐备的 SMTP 配置（不开验证开关）。 */
    private SiteMailVO readyMail() {
        return service.updateMail(patch(
                "smtpHost", "smtp.example.test", "smtpPort", 465,
                "smtpUsername", "bot@example.test", "smtpFrom", "bot@example.test",
                "smtpPassword", "s3cret"));
    }

    @Test
    @DisplayName("参数原样读回，口令只回「存过」这一个布尔")
    void roundTripsWithoutEchoingPassword() {
        SiteMailVO vo = readyMail();

        assertThat(vo.smtpHost()).isEqualTo("smtp.example.test");
        assertThat(vo.smtpPort()).isEqualTo(465);
        assertThat(vo.smtpUsername()).isEqualTo("bot@example.test");
        assertThat(vo.smtpFrom()).isEqualTo("bot@example.test");
        assertThat(vo.smtpPasswordSet()).isTrue();
        assertThat(vo.mailReady()).isTrue();

        // 明文与密文都不许出现在响应里
        assertThat(vo.toString()).doesNotContain("s3cret");
        String stored = JsonUtil.toMap(row.get().getConfig()).get(SiteConfig.KEY_SMTP_PASSWORD).toString();
        assertThat(stored).doesNotContain("s3cret").startsWith("v1:");
    }

    @Test
    @DisplayName("口令留空保存 = 沿用原值：改主机名不该顺手抹掉口令")
    void blankPasswordKeepsStoredOne() {
        readyMail();

        SiteMailVO vo = service.updateMail(patch("smtpHost", "smtp2.example.test", "smtpPassword", ""));

        assertThat(vo.smtpHost()).isEqualTo("smtp2.example.test");
        assertThat(vo.smtpPasswordSet()).isTrue();
        assertThat(decryptStored()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("清除口令必须连用户名一起清：只剩用户名的配置是一台必然 503 的服务器")
    void explicitClearRemovesPassword() {
        readyMail();

        // 只清口令、留着用户名：服务器收到的是一次未认证投递，保存时就拒，不等到发信才报 503
        assertThatThrownBy(() -> service.updateMail(patch("smtpPasswordClear", true)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("填了 SMTP 用户名就要填口令");

        // 用户名一起清掉 = 匿名中继，这是合法形态
        SiteMailVO vo = service.updateMail(patch("smtpPasswordClear", true, "smtpUsername", ""));

        assertThat(vo.smtpPasswordSet()).isFalse();
        assertThat(JsonUtil.toMap(row.get().getConfig())).doesNotContainKey(SiteConfig.KEY_SMTP_PASSWORD);
        // 主机/端口/发件地址还在：齐备性不看口令
        assertThat(vo.mailReady()).isTrue();
    }

    @Test
    @DisplayName("只填用户名不填口令：保存被拒，且报错给出「补口令」或「清用户名」两条出路")
    void usernameWithoutPasswordIsRejected() {
        assertThatThrownBy(() -> service.updateMail(patch(
                "smtpHost", "smtp.example.test", "smtpPort", 465,
                "smtpUsername", "bot@example.test", "smtpFrom", "bot@example.test")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("503 need EHLO and AUTH first")
                .hasMessageContaining("用户名也清空");
    }

    @Test
    @DisplayName("加密密钥换过之后旧密文解不开：标记出来，且不算「传输就绪」")
    void undecryptablePasswordIsReportedAndBlocksGate() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(SiteConfig.KEY_SMTP_HOST, "smtp.example.test");
        config.put(SiteConfig.KEY_SMTP_PORT, 465);
        config.put(SiteConfig.KEY_SMTP_FROM, "bot@example.test");
        config.put(SiteConfig.KEY_SMTP_PASSWORD, CryptoUtil.encrypt("s3cret", "a-different-secret"));
        config.put(SiteConfig.KEY_EMAIL_VERIFY_ENABLED, true);
        setRow(config);

        SiteMailVO vo = service.getMail();
        assertThat(vo.smtpCredentialUnusable()).isTrue();
        assertThat(vo.smtpPasswordSet()).isTrue();
        assertThat(vo.mailReady()).isFalse();
        assertThat(vo.emailVerifyRequired()).isFalse();

        // 这种状态下想再开一次开关要给出「重新填口令」这条能执行的话，而不是笼统说参数不齐
        assertThatThrownBy(() -> service.updateMail(patch("emailVerifyEnabled", true)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("解不开");

        // 公开的站点信息接口同样不能把它算成「需要验证」，否则注册页让人等一封永远收不到的邮件
        SiteConfigVO site = service.get();
        assertThat(site.emailVerifyRequired()).isFalse();
    }

    @Test
    @DisplayName("465 端口默认按 SSL 解释，显式选择优先")
    void securityDefaultsByPort() {
        SiteMailVO vo = service.updateMail(patch("smtpHost", "smtp.example.test", "smtpPort", 465));
        assertThat(vo.smtpSecurity()).isNull();
        assertThat(vo.smtpSecurityEffective()).isEqualTo("ssl");

        vo = service.updateMail(patch("smtpSecurity", "starttls"));
        assertThat(vo.smtpSecurity()).isEqualTo("starttls");
        assertThat(vo.smtpSecurityEffective()).isEqualTo("starttls");

        vo = service.updateMail(patch("smtpPort", 587, "smtpSecurity", ""));
        assertThat(vo.smtpSecurityEffective()).isEqualTo("starttls");
    }

    @Test
    @DisplayName("开关单独打开要被拒，且报错只点名缺了的那几项")
    void verifySwitchRequiresTransport() {
        assertThatThrownBy(() -> service.updateMail(patch("emailVerifyEnabled", true)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SMTP 主机")
                .hasMessageContaining("SMTP 端口")
                .hasMessageContaining("发件地址");

        assertThat(service.getMail().emailVerifyEnabled()).isFalse();
        // 校验不过就一行都没落：库里有半个开关于是「看着像配好了」，是最难查的一种坏状态
        assertThat(row.get()).isNull();

        service.updateMail(patch("smtpHost", "smtp.example.test", "smtpPort", 465));
        assertThatThrownBy(() -> service.updateMail(patch("emailVerifyEnabled", true)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发件地址")
                .hasMessageNotContaining("SMTP 主机");
        assertThat(JsonUtil.toMap(row.get().getConfig())).doesNotContainKey(SiteConfig.KEY_EMAIL_VERIFY_ENABLED);
    }

    @Test
    @DisplayName("参数与开关一次提交是合法路径：校验看的是合并后的配置")
    void transportAndSwitchInOneRequest() {
        SiteMailVO vo = service.updateMail(patch(
                "smtpHost", "smtp.example.test", "smtpPort", 587, "smtpFrom", "bot@example.test",
                "emailVerifyEnabled", true));

        assertThat(vo.mailReady()).isTrue();
        assertThat(vo.emailVerifyEnabled()).isTrue();
        assertThat(vo.emailVerifyRequired()).isTrue();
        assertThat(service.get().emailVerifyRequired()).isTrue();
    }

    @Test
    @DisplayName("关掉开关不要求参数齐备：可以先停验证再慢慢改 SMTP")
    void turningSwitchOffNeedsNoTransport() {
        readyMail();
        service.updateMail(patch("emailVerifyEnabled", true));

        SiteMailVO vo = service.updateMail(patch(
                "emailVerifyEnabled", false, "smtpHost", "", "smtpPort", "", "smtpFrom", ""));

        assertThat(vo.emailVerifyEnabled()).isFalse();
        assertThat(vo.mailReady()).isFalse();
        assertThat(vo.emailVerifyRequired()).isFalse();
    }

    @Nested
    @DisplayName("写入侧的形状校验")
    class InputValidation {

        @Test
        @DisplayName("主机不接受协议前缀与端口 —— 它们各自的报错都不指向填写方式")
        void hostRejectsSchemeAndPort() {
            assertThatThrownBy(() -> service.updateMail(patch("smtpHost", "https://smtp.example.test")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不要带");
            assertThatThrownBy(() -> service.updateMail(patch("smtpHost", "smtp.example.test:465")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("端口请单独填");
        }

        @Test
        @DisplayName("端口只认 1–65535 的整数")
        void portMustBeInRange() {
            assertThatThrownBy(() -> service.updateMail(patch("smtpPort", 0)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("SMTP 端口");
            assertThatThrownBy(() -> service.updateMail(patch("smtpPort", "四五八七")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("整数");
        }

        @Test
        @DisplayName("加密方式只认三个取值")
        void securityMustBeKnown() {
            assertThatThrownBy(() -> service.updateMail(patch("smtpSecurity", "tls")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("ssl、starttls 或 none");
        }

        @Test
        @DisplayName("发件地址要像个地址：收件人退信去向不明比少一个校验更要命")
        void fromMustLookLikeAnAddress() {
            assertThatThrownBy(() -> service.updateMail(patch("smtpFrom", "not-an-email")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("发件地址格式");
            assertThat(service.updateMail(patch("smtpFrom", "bot@example.test")).smtpFrom())
                    .isEqualTo("bot@example.test");
        }

        @Test
        @DisplayName("品牌配置的保存路径不碰邮件键：两个通道各写各的，互不覆盖")
        void brandUpdateLeavesMailKeysAlone() {
            readyMail();

            service.update(patch("name", "MiniWho"));

            assertThat(service.getMail().smtpHost()).isEqualTo("smtp.example.test");
            assertThat(service.get().name()).isEqualTo("MiniWho");
        }
    }

    private Object storedPassword() {
        return JsonUtil.toMap(row.get().getConfig()).get(SiteConfig.KEY_SMTP_PASSWORD);
    }

    private String decryptStored() {
        return CryptoUtil.decrypt(String.valueOf(storedPassword()), SECRET);
    }

    private void setRow(Map<String, Object> config) {
        SiteConfig entity = new SiteConfig();
        entity.setId(SiteConfig.SINGLETON_ID);
        entity.setConfig(JsonUtil.toJson(config));
        row.set(entity);
    }

    private Map<String, Object> patch(Object... pairs) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            patch.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return patch;
    }
}
