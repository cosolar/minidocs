package cn.minims.minidocs.share.support;

import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.TimeUtil;

import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 分享 token 生成与校验。
 *
 * <p>自动生成的格式：S + yyyyMMddHHmmssSSS（17 位）+ 6 位随机数字 = 共 24 位。
 * 另允许维护者自定义一个好记的短链（{@link #isValidCustom}），此时格式限制更宽：
 * 6-32 位的字母、数字、{@code -}、{@code _}。</p>
 *
 * <p>为什么下限是 6：token 是地址里唯一寻址手段（{@code /share/{token}}），短于 6 位时
 * 可枚举空间太小（4 位只有 60 多万种），拿到链接的人可以很快撞库试出别人的分享。
 * 6 位约 560 亿种，够短到好记、也够长到猜不到。</p>
 */
public final class ShareTokenUtil {

    /** 自定义短链的长度区间 */
    public static final int CUSTOM_MIN_LENGTH = 6;
    public static final int CUSTOM_MAX_LENGTH = 32;

    /**
     * 读取 token 时放行的格式：自动生成的 24 位，或历史/自定义的安全字符。
     *
     * <p>下限随自定义短链一起放宽到 6 —— 早先下限是 16，自定义短链若照 16 位下限就失去了
     * 「好记」的意义；而且读取侧的校验必须与写入侧一致，否则自己设的链接自己打不开。</p>
     */
    private static final Pattern VALID = Pattern.compile("^([sS]\\d{23}|[A-Za-z0-9_-]{6,64})$");

    /** 自定义短链的写入格式（比读取侧更紧：长度区间明确，字符集只有 URL 安全那几个） */
    private static final Pattern CUSTOM = Pattern.compile(
            "^[A-Za-z0-9_-]{" + CUSTOM_MIN_LENGTH + "," + CUSTOM_MAX_LENGTH + "}$");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private ShareTokenUtil() {
    }

    public static String generate() {
        return "S" + TimeUtil.now().format(STAMP) + FileNameUtil.randomDigits(6);
    }

    public static boolean isValid(String token) {
        return token != null && VALID.matcher(token).matches();
    }

    public static boolean isValidCustom(String token) {
        return token != null && CUSTOM.matcher(token).matches();
    }

    /**
     * 规整用户填的自定义短链：去空白；空串与 null 都归一成 {@code null}（= 不指定，走自动生成）。
     *
     * <p>前端把「留空」发成空串，这里统一成 null，服务层就只需要判一次 {@code null}。</p>
     */
    public static String normalizeCustom(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
