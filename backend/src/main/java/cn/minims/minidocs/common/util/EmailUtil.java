package cn.minims.minidocs.common.util;

import cn.minims.minidocs.common.exception.BizException;

import java.util.regex.Pattern;

/**
 * 邮箱地址的形状判断。
 *
 * <p>只回答「像不像一个地址」，不回答「这个地址收得到吗」—— 后者只有收件服务器知道，
 * 而一次发信试探的代价是打一趟 SMTP，不该用在保存配置这种纯输入校验上。</p>
 *
 * <p>两处用同一套规则：站点设置里存发件地址、测试发信时填收件地址。分开写会出现
 * 「配置里存得进去、测试时说格式不对」这种对不上口径的报错。</p>
 */
public final class EmailUtil {

    /** 本地部分@域名.顶级域；三段都不含空白，@ 只允许出现一次 */
    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    private EmailUtil() {
    }

    public static boolean isLikely(String value) {
        return value != null && SHAPE.matcher(value).matches();
    }

    /**
     * 归一化并校验：去空白后必须像个地址，否则报参数错。
     *
     * @param label 报错里的字段名，让「哪里不对」指向界面上的那一栏
     */
    public static String require(String value, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (!isLikely(trimmed)) {
            throw BizException.param(label + "格式不正确，例如 no-reply@example.com");
        }
        return trimmed;
    }

    /**
     * 脱敏显示：留本地部分的首尾各一位，域名整段保留。
     *
     * <p>验证邮件被拦在垃圾箱里时，用户想知道「发到哪儿去了」。整段地址不能给 ——
     * 那个接口是公开的；{@code z***@example.com} 够认出自己的邮箱，又不足以从一堆
     * 候选用户名里确认某个账号的存在。</p>
     */
    public static String mask(String value) {
        String email = value == null ? "" : value.trim();
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return local + "***" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }
}
