package cn.minims.minidocs.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册验证码邮件的版式契约：品牌、Logo 绝对地址、验证码与安全提示都必须真的渲染出来。
 *
 * <p>这里不连邮件服务器，只锁住正文里的关键信息 —— 版式改样式可以，改掉这几项就说明
 * 收件人收到的不是一封能用的验证码邮件了。</p>
 */
@DisplayName("邮件正文：注册验证码")
class MailBodiesTest {

    @Test
    @DisplayName("正文带 Logo 绝对地址、站点名、域名与 6 位验证码")
    void registerCodeRendersBrandAndCode() {
        String html = MailBodies.registerCode("MiniDocs", "极简智库", "docs.example.com",
                "alice@example.com", "672998", 10, "https://docs.example.com/logo-mark.png");

        assertThat(html)
                .contains("注册验证码")
                .contains("672998")
                .contains("alice@example.com")
                .contains("MiniDocs")
                .contains("docs.example.com")
                .contains("极简智库")
                .contains("https://docs.example.com/logo-mark.png")
                .contains("10 分钟内")
                .contains("请勿直接回复");
    }

    @Test
    @DisplayName("动态文本一律转义，不让站点名变成注入点")
    void registerCodeEscapesDynamicText() {
        String html = MailBodies.registerCode("<站点>", "<副标题>", "d.example.com",
                "a@b.co", "000123", 10, "https://d.example.com/logo.png");

        assertThat(html)
                .doesNotContain("<站点>")
                .doesNotContain("<副标题>")
                .contains("&lt;站点&gt;")
                .contains("&lt;副标题&gt;");
    }

    @Test
    @DisplayName("没有 Logo 地址时只显示站点名，不产生坏图片")
    void registerCodeWithoutLogoOmitsImage() {
        String html = MailBodies.registerCode("MiniDocs", null, null,
                "a@b.co", "123456", 10, null);

        assertThat(html).doesNotContain("<img").doesNotContain("null");
    }
}
