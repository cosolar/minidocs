package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.AuthDtos.RegisterCodeVO;
import cn.minims.minidocs.auth.entity.RegisterCode;
import cn.minims.minidocs.auth.mapper.RegisterCodeMapper;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.EmailUtil;
import cn.minims.minidocs.common.util.HashUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.mail.MailService;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.site.support.MailSettingsReader;
import cn.minims.minidocs.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 注册邮箱验证码：发码、校验、作废。
 *
 * <p><b>为什么是验证码而不是链接</b>：注册页是访客第一次停留的页面，
 * 跳去邮箱点链接再回来会丢掉填了一半的表单；6 位数字填回原地就能继续。</p>
 *
 * <p><b>安全边界</b>：码只有 10 分钟生命、最多试 5 次、校验通过即删行；
 * 发码按邮箱 60 秒一次、按 IP 每分钟 10 次。库里只存
 * {@code SHA-256(email + ':' + code)}，原文只出现在邮件正文里。</p>
 *
 * <p>发信是同步的：用户正等在按钮后面，失败必须当场报出来。发不出去时删掉刚写的行，
 * 免得留下一个永远收不到的码；同时清掉该邮箱的发送冷却，允许立刻重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterCodeService {

    /** 验证码有效期：够走完「切到邮箱 → 抄码 → 切回来」，又不至于变成长期口令 */
    public static final Duration CODE_TTL = Duration.ofMinutes(10);

    /** 同一个码最多试 5 次，超过即作废 */
    public static final int MAX_ATTEMPTS = 5;

    /** 发码冷却：同一邮箱 60 秒一次；同一 IP 60 秒十次 */
    private static final int SEND_LIMIT_EMAIL = 1;

    private static final int SEND_LIMIT_IP = 10;

    private static final Duration SEND_WINDOW = Duration.ofSeconds(60);

    private static final String SEND_KEY_EMAIL = "register-code:email:";

    private static final String SEND_KEY_IP = "register-code:ip:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegisterCodeMapper registerCodeMapper;
    private final UserService userService;
    private final MailService mailService;
    private final MailSettingsReader mailSettingsReader;
    private final RateLimiter rateLimiter;
    private final MiniDocsProperties properties;

    /**
     * 给一个还没注册的邮箱发验证码。
     *
     * <p>站点没开启邮箱验证时直接拒绝：前端不该显示这个按钮，后端也不能让
     * 「发一封无用的邮件」变成任何人都能触发的动作。</p>
     *
     * @param baseUrl 站点基址，邮件里的 Logo 要拼成绝对地址才能被客户端加载
     */
    public RegisterCodeVO send(String rawEmail, String ip, String baseUrl) {
        if (!properties.isRegisterEnabled()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "自主注册已关闭，请联系管理员开通账号");
        }
        String email = normalize(rawEmail);
        MailSettings settings = mailSettingsReader.read();
        if (!settings.verificationRequired()) {
            throw BizException.param("当前站点未开启邮箱验证，无需获取验证码");
        }
        if (userService.emailExists(email, null)) {
            throw BizException.exists("邮箱已被占用");
        }
        acquireSendQuota(email, ip);

        String code = newCode();
        LocalDateTime now = TimeUtil.now();
        RegisterCode row = new RegisterCode();
        row.setEmail(email);
        row.setCodeHash(hash(email, code));
        row.setAttempts(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plus(CODE_TTL));
        // 同一邮箱只留最新一条：重发即覆盖，旧码随之失效
        registerCodeMapper.deleteById(email);
        registerCodeMapper.insert(row);
        try {
            mailService.sendRegisterCode(settings, email, code, (int) CODE_TTL.toMinutes(), baseUrl);
        } catch (RuntimeException e) {
            registerCodeMapper.deleteById(email);
            // 信没发出去，冷却也就没有意义；否则用户按提示修好配置后还得再等一分钟
            rateLimiter.reset(SEND_KEY_EMAIL + email);
            throw e;
        }
        // 日志不带邮箱与验证码：前者是个人信息，后者是凭据
        log.info("注册验证码已发送");
        return new RegisterCodeVO((int) SEND_WINDOW.toSeconds(), (int) CODE_TTL.toMinutes());
    }

    /**
     * 校验并消费验证码，失败抛业务异常。
     *
     * <p>过期、试错超限、码不对都归入「验证码不可用」这一层提示，但文案分开写：
     * 用户需要知道是「重新获取」还是「再看一眼邮件」。</p>
     */
    public void verify(String rawEmail, String rawCode) {
        String email = normalize(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        RegisterCode row = registerCodeMapper.selectById(email);
        LocalDateTime now = TimeUtil.now();
        if (row == null) {
            throw BizException.param("验证码已失效，请重新获取");
        }
        if (row.getExpiresAt() == null || row.getExpiresAt().isBefore(now)) {
            registerCodeMapper.deleteById(email);
            throw BizException.param("验证码已过期，请重新获取");
        }
        int attempts = row.getAttempts() == null ? 0 : row.getAttempts();
        if (attempts >= MAX_ATTEMPTS) {
            registerCodeMapper.deleteById(email);
            throw BizException.param("验证码错误次数过多，请重新获取");
        }
        if (!hash(email, code).equals(row.getCodeHash())) {
            row.setAttempts(attempts + 1);
            row.setUpdatedAt(now);
            registerCodeMapper.updateById(row);
            if (attempts + 1 >= MAX_ATTEMPTS) {
                registerCodeMapper.deleteById(email);
                throw BizException.param("验证码错误次数过多，请重新获取");
            }
            throw BizException.param("验证码不正确");
        }
        // 一次性：校验通过即删，重放同一个码只会得到「已失效」
        registerCodeMapper.deleteById(email);
    }

    private void acquireSendQuota(String email, String ip) {
        if (!rateLimiter.tryAcquire(SEND_KEY_EMAIL + email, SEND_LIMIT_EMAIL, SEND_WINDOW)) {
            throw BizException.of(ErrorCode.RESEND_TOO_FREQUENT, "验证码刚刚发过，请稍后再试");
        }
        if (ip != null && !ip.isBlank()
                && !rateLimiter.tryAcquire(SEND_KEY_IP + ip, SEND_LIMIT_IP, SEND_WINDOW)) {
            throw BizException.of(ErrorCode.RESEND_TOO_FREQUENT, "当前网络获取验证码过于频繁，请稍后再试");
        }
    }

    private static String normalize(String rawEmail) {
        String email = EmailUtil.require(rawEmail, "邮箱");
        return email.toLowerCase(Locale.ROOT);
    }

    /** 6 位数字，允许前导零：{@code 000123} 也是合法码。 */
    private static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** 摘要绑定邮箱：同一串数字发给别人时不会顺手验过另一个地址。 */
    private static String hash(String email, String code) {
        return HashUtil.sha256Hex(email + ":" + code);
    }
}
