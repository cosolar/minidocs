package cn.minims.minidocs.mail;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 邮件正文模板。
 *
 * <p>邮件 HTML 与站内渲染是两回事：站内的样式表与 CSS 变量到了邮件客户端里基本不生效，
 * 所以要写内联样式，且只用表格/圆角这类各家居然都能画对的东西。这里刻意不做深色模式适配 ——
 * 邮件正文只有一句话和一个按钮，白底黑字在两种模式下都读得清。</p>
 *
 * <p>三封邮件共用一套品牌外壳（顶部色线、眉标、Logo 页脚），只换中间的正文：
 * 注册验证码、邮箱验证链接、配置测试。将来要加「找回密码」之类，再在这里加一个方法，
 * 而不是搭一套模板引擎 —— 邮件文案的稳定需求远低于它的改动成本。</p>
 */
final class MailBodies {

    /** 品牌青绿，与站点主色同源；写死是因为邮件里读不到 CSS 变量 */
    private static final String BRAND = "#128577";

    private MailBodies() {
    }

    /**
     * 注册验证码邮件。
     *
     * <p>版式对齐站点品牌页：顶部品牌色细线、小号字距标签、大标题、浅色验证码框、
     * 安全提示，底部是 Logo + 站点名 + 域名。整封邮件只用表格与内联样式 ——
     * 邮件客户端不认站点的 CSS 变量，也不保证支持 flex/grid。</p>
     *
     * <p>验证码只在正文里出现：不写进链接、不落库原文、不进日志，抄到的人也只在
     * {@code validMinutes} 内、且只有一次机会用它。</p>
     *
     * @param siteName     站点名，同时作为 Logo 的 alt 文字（图片被拦时仍读得出是谁发的）
     * @param subtitle     站点副标题，可空
     * @param siteHost     站点域名，可空；有才渲染那一行小字
     * @param email        收码邮箱，正文里高亮出来，让人确认验的是哪个地址
     * @param code         6 位数字
     * @param validMinutes 有效期，写进正文免得用户翻出一封旧邮件来试
     * @param logoUrl      Logo 绝对地址，可空；空则只显示站点名
     */
    static String registerCode(String siteName, String subtitle, String siteHost, String email,
                               String code, int validMinutes, String logoUrl) {
        String brand = escape(siteName);
        String content = """
                <h1 style="margin:6px 0 16px;font-size:24px;line-height:1.35;color:#14231f;font-weight:700">注册验证码</h1>
                <p style="margin:0;font-size:15px;line-height:1.8;color:#46524e">
                  你正在使用 <span style="padding:1px 5px;border-radius:4px;background:#edf8f4;\
                color:%s;font-weight:600">%s</span> 注册「<strong style="color:#1f2d28">%s</strong>」账号。
                  把下面这 6 位数字填回注册页面，即可完成注册。
                </p>
                <div style="margin:22px 0 20px;padding:20px 10px;text-align:center;border:1px dashed #a9d8cc;\
                border-radius:12px;background:#f4fbf8;color:%s;font-size:38px;font-weight:700;\
                line-height:1.15;letter-spacing:.3em;text-indent:.3em">%s</div>
                <p style="margin:0 0 6px;font-size:14px;line-height:1.75;color:#46524e">
                  验证码 <strong style="color:#1f2d28">%d 分钟内</strong>有效，填写成功后立即失效。
                </p>
                <p style="margin:0;font-size:13.5px;line-height:1.75;color:#7a8782">%s 不会通过电话、聊天软件或邮件向你索要验证码。</p>
                <p style="margin:4px 0 0;font-size:13.5px;line-height:1.75;color:#7a8782">
                  如果这不是你本人的操作，请忽略本邮件，账号不会被创建。
                </p>
                """.formatted(BRAND, escape(email), brand, BRAND, escape(code), validMinutes, brand);
        return brandShell(siteName, subtitle, siteHost, logoUrl, "账号验证", content);
    }

    /**
     * 邮箱验证链接邮件（改邮箱、补验证时用）。
     *
     * @param displayName 收件人昵称，可空
     * @param link        点开即完成验证的绝对地址（含站点基址与令牌）
     * @param validHours  令牌有效期，写进正文，免得用户翻出一封三个月前的邮件来点
     */
    static String verification(String siteName, String subtitle, String siteHost, String logoUrl,
                               String displayName, String link, int validHours) {
        String greeting = displayName == null || displayName.isBlank()
                ? "你好"
                : "你好，" + escape(displayName);
        String content = """
                <h1 style="margin:6px 0 16px;font-size:24px;line-height:1.35;color:#14231f;font-weight:700">验证你的邮箱</h1>
                <p style="margin:0 0 14px;font-size:15px;color:#1f2d28">%s</p>
                <p style="margin:0 0 20px;font-size:15px;line-height:1.8;color:#46524e">
                  有人用这个邮箱在 <strong style="color:#1f2d28">%s</strong> 注册了账号。
                  确认是你本人的话，按下方的按钮验证邮箱；链接 <strong style="color:#1f2d28">%d 小时内</strong>有效，只能用一次。
                </p>
                %s
                <p style="margin:18px 0 0;font-size:13.5px;line-height:1.75;color:#7a8782">
                  不是你自己的操作？不用做任何事 —— 不点这个链接，账号会一直停在未验证状态。
                </p>
                <p style="margin:8px 0 0;font-size:12px;line-height:1.7;color:#a3aea9;word-break:break-all">
                  按钮打不开时，把下面这行整段复制到浏览器地址栏：<br>%s
                </p>
                """.formatted(greeting, escape(siteName), validHours, button(link), escape(link));
        return brandShell(siteName, subtitle, siteHost, logoUrl, "账号验证", content);
    }

    /**
     * 站点设置里「测试发信」按钮产生的邮件：与正式邮件同一套版式，
     * 管理员收到的预览就是用户将看到的样子（只是正文换成测试说明）。
     */
    static String test(String siteName, String subtitle, String siteHost, String logoUrl,
                       String from, Date sentAt) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(sentAt);
        String content = """
                <h1 style="margin:6px 0 16px;font-size:24px;line-height:1.35;color:#14231f;font-weight:700">邮件配置测试</h1>
                <p style="margin:0 0 14px;font-size:15px;line-height:1.8;color:#46524e">
                  能收到它，说明 <strong style="color:#1f2d28">%s</strong> 的站点设置里那组 SMTP 参数是可用的：
                  发件地址 <span style="padding:1px 5px;border-radius:4px;background:#edf8f4;color:%s;font-weight:600">%s</span>，
                  发送时间 %s。
                </p>
                <p style="margin:0;font-size:13.5px;line-height:1.75;color:#7a8782">
                  这封邮件不需要任何操作，也没有链接。收到后可以放心关掉；
                  没收到请先翻垃圾邮件目录，再回设置页看这次发送的报错。
                </p>
                """.formatted(escape(siteName), BRAND, escape(from), escape(time));
        return brandShell(siteName, subtitle, siteHost, logoUrl, "邮件配置", content);
    }

    /**
     * 三封邮件共用的品牌外壳：顶部色线、眉标、正文、分隔线、Logo 页脚。
     *
     * <p>Logo 用绝对地址且给 alt；被客户端拦截图片时，旁边的站点名仍然读得出来。</p>
     */
    private static String brandShell(String siteName, String subtitle, String siteHost, String logoUrl,
                                     String eyebrowSuffix, String content) {
        String brand = escape(siteName);
        String eyebrow = escape(siteName == null ? "" : siteName.toUpperCase(Locale.ROOT))
                + " · " + escape(eyebrowSuffix);
        String subtitleLine = subtitle == null || subtitle.isBlank() ? "" : """
                <p style="margin:14px 0 0;font-size:12.5px;line-height:1.7;color:#8a9792">%s</p>
                """.formatted(escape(subtitle));
        String hostLine = siteHost == null || siteHost.isBlank() ? "" : """
                <div style="margin-top:2px;font-size:11px;color:#9aa7a2;line-height:1.2">%s</div>
                """.formatted(escape(siteHost));
        String logoCell = logoUrl == null || logoUrl.isBlank() ? "" : """
                <td style="padding-right:9px" valign="middle">\
                <img src="%s" height="36" alt="%s" style="display:block;height:36px;width:auto;\
                max-width:150px;border:0;border-radius:9px"></td>
                """.formatted(escape(logoUrl), brand);
        return """
                <!doctype html>
                <html lang="zh-CN"><body style="margin:0;padding:32px 12px;background:#f2f6f4;\
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif">
                <table role="presentation" width="100%%" style="max-width:560px;margin:0 auto;background:#ffffff;\
                border:1px solid #e3eae6;border-radius:14px;border-collapse:separate;overflow:hidden">\
                <tr><td style="height:3px;background:%s;font-size:0;line-height:0">&nbsp;</td></tr>
                <tr><td style="padding:28px 36px 6px;font-size:11px;letter-spacing:.18em;color:#8a9a94">%s</td></tr>
                <tr><td style="padding:0 36px 26px">%s</td></tr>
                <tr><td style="padding:0 36px"><div style="height:1px;background:#eef2f0"></div></td></tr>
                <tr><td style="padding:22px 36px 30px;text-align:center">
                  <table role="presentation" align="center" style="margin:0 auto"><tr>\
                %s<td valign="middle" style="text-align:left">\
                <div style="font-size:15px;font-weight:700;color:#1f2d28;line-height:1.2">%s</div>%s</td></tr></table>\
                %s
                  <p style="margin:4px 0 0;font-size:12px;line-height:1.7;color:#a3aea9">本邮件由系统自动发出，请勿直接回复。</p>
                </td></tr>
                </table>
                </body></html>
                """.formatted(BRAND, eyebrow, content, logoCell, brand, hostLine, subtitleLine);
    }

    private static String button(String link) {
        return """
                <a href="%s" style="display:inline-block;padding:11px 22px;border-radius:8px;\
                background:%s;color:#ffffff;font-size:14px;font-weight:600;text-decoration:none">\
                验证邮箱</a>
                """.formatted(escape(link), BRAND);
    }

    /**
     * 最小 HTML 转义。
     *
     * <p>站点名由管理员填、昵称由注册用户自己填，两者都会进正文。不做转义时
     * {@code <img src=//attacker/x>} 这样的昵称会让收件客户端去敲别人的服务器 ——
     * 一封「验证邮箱」的邮件正是用户最愿意点开图的东西。</p>
     */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
