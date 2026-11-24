package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.AuthDtos.LoginCodeVO;
import cn.minims.minidocs.auth.entity.LoginCode;
import cn.minims.minidocs.auth.mapper.LoginCodeMapper;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.EmailUtil;
import cn.minims.minidocs.common.util.HashUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.mail.MailService;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.site.support.MailSettingsReader;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 登录邮箱验证码：发码、校验、作废。
 *
 * <p>用途只有一个：同一个账号的密码连续输错 3 次后，让本人用「收得到邮件」这件事证明身份，
 * 先进入平台，再提醒重设密码。它不替代密码登录，也不会因为验证码通过就修改密码。</p>
 *
 * <p>安全边界与注册验证码一致：码只有 10 分钟生命、最多试 5 次、校验通过即删行；
 * 发码按邮箱 60 秒一次、按 IP 每分钟 10 次。库里只存
 * {@code SHA-256(email + ':' + code)}，原文只出现在邮件正文里。</p>
 *
 * <p>发信同步：用户正等在按钮后面，失败必须当场报出来。发不出去时删掉刚写的行并清掉冷却，
 * 免得留下一个永远收不到的码、还要再等一分钟才能重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginCodeService {

    /** 验证码有效期：够走完「切到邮箱 → 抄码 → 切回来」，又不至于变成长期口令 */
    public static final Duration CODE_TTL = Duration.ofMinutes(10);

    /** 同一个码最多试 5 次，超过即作废 */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * 验证码登录成功后，「免原密码重设密码」的许可保留多久。
     *
     * <p>按「进平台后马上处理」来定：太短会让人刚看完提示就失效，太长等于留了一个
     * 绕过原密码的窗口。15 分钟够改，也够反悔。</p>
     */
    public static final Duration RESET_TTL = Duration.ofMinutes(15);

    /** 已消费行的占位摘要：永远匹配不上任何 6 位码，等价于「这行不能再登录」 */
    private static final String CONSUMED_HASH = "!";

    /** 发码冷却：同一邮箱 60 秒一次；同一 IP 60 秒十次 */
    private static final int SEND_LIMIT_EMAIL = 1;

    private static final int SEND_LIMIT_IP = 10;

    /** 校验限速：防止把「每个码 5 次」变成可以无限换号试 */
    private static final int VERIFY_LIMIT = 10;

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private static final String SEND_KEY_EMAIL = "login-code:email:";

    private static final String SEND_KEY_IP = "login-code:ip:";

    private static final String VERIFY_KEY = "login-code:verify:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LoginCodeMapper loginCodeMapper;
    private final UserService userService;
    private final MailService mailService;
    private final MailSettingsReader mailSettingsReader;
    private final RateLimiter rateLimiter;

    /**
     * 给账号绑定的邮箱发一枚登录验证码。
     *
     * <p>账号不存在、被停用、没绑邮箱，都回同一句话：这个接口在登录页上公开，
     * 回复一旦能区分，就变成了「拿用户名探测账号状态」的工具。管理员侧的原因写在日志里，
     * 但日志同样不带账号与邮箱。</p>
     *
     * @param baseUrl 站点基址，邮件里的 Logo 要拼成绝对地址才能被客户端加载
     */
    public LoginCodeVO send(String account, String ip, String baseUrl) {
        String accountKey = normalizeAccount(account);
        User user = userService.findByUsernameOrEmail(accountKey);
        if (user == null || !user.isActive() || user.getEmail() == null || user.getEmail().isBlank()) {
            log.info("登录验证码请求被拒绝：账号不可用或未绑定邮箱");
            throw BizException.param("这个账号暂时不能使用验证码登录，请确认账号是否可用，或联系管理员");
        }
        String email = user.getEmail().trim().toLowerCase(Locale.ROOT);
        MailSettings settings = mailSettingsReader.read();
        if (!settings.transportReady()) {
            throw BizException.param("站点还没有配好邮件服务，请联系管理员");
        }
        acquireSendQuota(email, ip);

        String code = newCode();
        LocalDateTime now = TimeUtil.now();
        LoginCode row = new LoginCode();
        row.setEmail(email);
        row.setCodeHash(hash(email, code));
        row.setAttempts(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plus(CODE_TTL));
        // 同一邮箱只留最新一条：重发即覆盖，旧码随之失效
        loginCodeMapper.deleteById(email);
        loginCodeMapper.insert(row);
        try {
            mailService.sendLoginCode(settings, email, code, (int) CODE_TTL.toMinutes(), baseUrl);
        } catch (RuntimeException e) {
            loginCodeMapper.deleteById(email);
            // 信没发出去，冷却也就没有意义；否则用户按提示修好配置后还得再等一分钟
            rateLimiter.reset(SEND_KEY_EMAIL + email);
            throw e;
        }
        log.info("登录验证码已发送");
        return new LoginCodeVO(EmailUtil.mask(email), (int) WINDOW.toSeconds(), (int) CODE_TTL.toMinutes());
    }

    /**
     * 校验并消费验证码，返回这个码对应的账号。
     *
     * <p>先查账号再校验：码是绑定邮箱的，但调用方传的是用户名或邮箱 —— 必须先把二者
     * 落到同一个邮箱上，否则「用户名 A + 邮箱 B 的码」就能串号。</p>
     */
    public User verify(String account, String rawCode) {
        String accountKey = normalizeAccount(account);
        if (!rateLimiter.tryAcquire(VERIFY_KEY + accountKey, VERIFY_LIMIT, WINDOW)) {
            throw BizException.of(ErrorCode.LOGIN_TOO_FREQUENT, "验证码校验过于频繁，请 60 秒后重试");
        }
        User user = userService.findByUsernameOrEmail(accountKey);
        if (user == null || !user.isActive() || user.getEmail() == null || user.getEmail().isBlank()) {
            throw BizException.param("验证码不可用，请重新获取");
        }
        String email = user.getEmail().trim().toLowerCase(Locale.ROOT);
        String code = rawCode == null ? "" : rawCode.trim();
        LoginCode row = loginCodeMapper.selectById(email);
        LocalDateTime now = TimeUtil.now();
        if (row == null) {
            throw BizException.param("验证码已失效，请重新获取");
        }
        if (row.getExpiresAt() == null || row.getExpiresAt().isBefore(now)) {
            loginCodeMapper.deleteById(email);
            throw BizException.param("验证码已过期，请重新获取");
        }
        int attempts = row.getAttempts() == null ? 0 : row.getAttempts();
        if (attempts >= MAX_ATTEMPTS) {
            loginCodeMapper.deleteById(email);
            throw BizException.param("验证码错误次数过多，请重新获取");
        }
        if (!hash(email, code).equals(row.getCodeHash())) {
            row.setAttempts(attempts + 1);
            row.setUpdatedAt(now);
            loginCodeMapper.updateById(row);
            if (attempts + 1 >= MAX_ATTEMPTS) {
                loginCodeMapper.deleteById(email);
                throw BizException.param("验证码错误次数过多，请重新获取");
            }
            throw BizException.param("验证码不正确");
        }
        // 一次性：校验通过即删，重放同一个码只会得到「已失效」
        loginCodeMapper.deleteById(email);
        return user;
    }

    /**
     * 用码登录成功后写一条「允许免原密码重设密码」的一次性许可。
     *
     * <p>为什么落在库里：Sa-Token 在本项目是 JWT 无状态模式，服务端没有会话可挂标记。
     * 许可与邮箱绑定，行内摘要换成占位符，保证这条已被消费的码不能再拿去登录。</p>
     */
    public void allowPasswordReset(String email) {
        String key = normalizeEmail(email);
        if (key == null) {
            return;
        }
        LocalDateTime now = TimeUtil.now();
        LoginCode row = new LoginCode();
        row.setEmail(key);
        row.setCodeHash(CONSUMED_HASH);
        row.setAttempts(MAX_ATTEMPTS);
        row.setExpiresAt(now);
        row.setResetExpiresAt(now.plus(RESET_TTL));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        loginCodeMapper.deleteById(key);
        loginCodeMapper.insert(row);
    }

    /**
     * 取用一次性许可：有效则当场删掉并返回 true。
     *
     * <p>调用方必须已经通过 JWT 登录，且传入的邮箱取自该登录用户本人 —— 许可只证明
     * 「刚刚用这个邮箱的验证码进来过」，不证明「现在这个 token 是那次登录发的」；
     * 后者由重置接口的登录态保证。</p>
     */
    public boolean consumeResetPermission(String email) {
        String key = normalizeEmail(email);
        if (key == null) {
            return false;
        }
        LoginCode row = loginCodeMapper.selectById(key);
        if (row == null || row.getResetExpiresAt() == null) {
            return false;
        }
        LocalDateTime now = TimeUtil.now();
        // 读一次就删：无论过期与否，这条许可都不该再被别人或下一次请求复用
        loginCodeMapper.deleteById(key);
        return !row.getResetExpiresAt().isBefore(now);
    }

    /**
     * 用密码正常登录成功后，清掉残留的重设许可。
     *
     * <p>只删「待重设」行：如果库里是一条刚发出、还没使用的验证码（resetExpiresAt 为空），
     * 不能顺手清掉，否则用户点了发码又去密码登录，回来发现码没了。</p>
     */
    public void clearResetPermission(String email) {
        String key = normalizeEmail(email);
        if (key == null) {
            return;
        }
        LoginCode row = loginCodeMapper.selectById(key);
        if (row != null && row.getResetExpiresAt() != null) {
            loginCodeMapper.deleteById(key);
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void acquireSendQuota(String email, String ip) {
        if (!rateLimiter.tryAcquire(SEND_KEY_EMAIL + email, SEND_LIMIT_EMAIL, WINDOW)) {
            throw BizException.of(ErrorCode.RESEND_TOO_FREQUENT, "验证码刚刚发过，请 60 秒后再试");
        }
        if (ip != null && !ip.isBlank()
                && !rateLimiter.tryAcquire(SEND_KEY_IP + ip, SEND_LIMIT_IP, WINDOW)) {
            throw BizException.of(ErrorCode.RESEND_TOO_FREQUENT, "当前网络获取验证码过于频繁，请 60 秒后再试");
        }
    }

    /** 用户名与邮箱都按小写比对，与密码登录保持同一口径。 */
    private static String normalizeAccount(String account) {
        return account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
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
