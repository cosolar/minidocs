package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.AuthDtos.VerificationResultVO;
import cn.minims.minidocs.auth.support.VerificationMailWorker;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.EmailUtil;
import cn.minims.minidocs.common.util.HashUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.site.support.MailSettingsReader;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 邮箱验证的状态机：谁需要验证、链接怎么发出去、点完算什么状态、什么时候拦登录。
 *
 * <p><b>为什么要有这道闸门</b>：注册不要邮箱也能过，同一个人就能批量建号占位。
 * 要求「先证明这个收件箱归你」把成本从敲三下键盘抬高到持有一个真实邮箱。</p>
 *
 * <p><b>为什么闸门只放在登录</b>：正在写文档的人已经被认过一次，验证与否都不该把人从编辑器里踢出去；
 * 而登录是零成本的一刻 —— 邮件在注册时就发出去了，只是补点一下。平台 ADMIN 另外豁免
 * （见 {@link #blocksLogin(User)}）：单实例自部署常常只有这一个账号，它要是正好填了个收不到信的邮箱，
 * 全站就没人进得去了。</p>
 *
 * <p><b>发不出邮件不许挡人</b>：SMTP 没配、配错、口令解不开、投递失败 —— 每一种都让注册照常放行
 * （{@link MailSettings#verificationRequired()} 与 {@link VerificationMailWorker} 的回落）。
 * 「防乱注册」不该实现成「谁都注册不了」。</p>
 *
 * <p>库里只存令牌的 SHA-256 摘要：拖走 users 表也换不来一条可用的验证链接。
 * 原文只在发信那一次的内存里存在，不入库、不进日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    /** 验证链接有效期 24 小时：再短会让人「睡一觉起来链接没了」，再长等于发出去一条长期口令 */
    public static final Duration LINK_TTL = Duration.ofHours(24);

    /** 令牌随机 32 字节，base64url 无填充后 43 字符 —— 猜不到，也不必查重 */
    private static final int TOKEN_BYTES = 32;

    /** 令牌原文的长度上限：正常是 43 位，放宽只为挡住「地址栏里被塞进别的东西」 */
    private static final int TOKEN_MAX_LENGTH = 128;

    /** 重发限速：同一账号 60 秒一次；同一来源 IP 60 秒十次（别让人把这个公开接口当轰炸按钮） */
    private static final int RESEND_LIMIT_ACCOUNT = 1;

    private static final int RESEND_LIMIT_IP = 10;

    private static final Duration RESEND_WINDOW = Duration.ofSeconds(60);

    private static final String RESEND_KEY_ACCOUNT = "verify-resend:account:";

    private static final String RESEND_KEY_IP = "verify-resend:ip:";

    /**
     * 公开重发接口的统一回复。
     *
     * <p>账号不存在、账号没邮箱、早就验证过了 —— 这几种情况都回这一句，和「已经发了」区分不开。
     * 这个接口不带登录态，任何差别都会被当成「这个用户名存不存在」的探测器，
     * 而注册页本来就不该告诉别人这件事。</p>
     */
    public static final String RESEND_NOTICE =
            "验证邮件已发送。没有收到请检查邮箱是否填写正确，以及邮件是否在垃圾邮件箱里";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService userService;
    private final MailSettingsReader mailSettingsReader;
    private final VerificationMailWorker verificationMailWorker;
    private final RateLimiter rateLimiter;

    /** 此刻是否要求邮箱验证：注册写状态、登录设闸门、前端要不要强制填邮箱，都问这同一个地方。 */
    public boolean required() {
        return settings().verificationRequired();
    }

    /** 本次流程生效的邮件配置。调用方拿一份用到底，判定与发信才是同一个答案。 */
    public MailSettings settings() {
        return mailSettingsReader.read();
    }

    /**
     * 注册：按配置决定这个账号要不要先验证邮箱，要则挂上一次性令牌。
     *
     * @return 令牌原文；不需要验证时返回 {@code null}（此时账号直接记为已验证）
     */
    public String prepareRegistration(User user, MailSettings settings) {
        return arm(user, settings.verificationRequired());
    }

    /**
     * 改了邮箱 ⇒ 重新走一遍验证。
     *
     * <p>「已验证」说的是旧地址；换成新的还留着这个标记，等于承认一个没人检查过的邮箱归他。
     * 验证没开启时不动任何状态，改邮箱照旧即时可用。</p>
     *
     * @return 令牌原文；不需要重新验证时返回 {@code null}
     */
    public String prepareEmailChange(User user, String previousEmail, MailSettings settings) {
        String email = user.getEmail();
        if (email == null || email.isBlank() || email.equals(previousEmail)) {
            return null;
        }
        return arm(user, settings.verificationRequired());
    }

    /**
     * 事务提交后再把邮件发出去。
     *
     * <p>必须等提交：投递失败时的回落是一次 UPDATE，事务还没提交时另一个连接读不到这一行，
     * 回落就写在空气上。没有活动事务时（单元测试或将来的非事务调用方）就地触发，语义不变。</p>
     */
    public void dispatchAfterCommit(User user, MailSettings settings, String rawToken, String baseUrl) {
        if (rawToken == null || rawToken.isEmpty()) {
            return;
        }
        VerificationMailWorker.PendingMail pending = pendingOf(user, settings, rawToken, baseUrl);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            verificationMailWorker.sendAsync(pending);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                verificationMailWorker.sendAsync(pending);
            }
        });
    }

    /**
     * 点开链接：认令牌、置为已验证、令牌作废。
     *
     * <p>验证这件事任何时候都允许，即使管理员已经关掉了开关 —— 有人愿意自证邮箱归自己，没有拒绝的道理。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public VerificationResultVO verify(String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty() || token.length() > TOKEN_MAX_LENGTH) {
            throw BizException.of(ErrorCode.VERIFY_LINK_INVALID);
        }
        User user = userService.getOne(Wrappers.<User>lambdaQuery()
                .eq(User::getVerifyToken, HashUtil.sha256Hex(token))
                .last("LIMIT 1"));
        if (user == null || user.getVerifyExpiresAt() == null
                || user.getVerifyExpiresAt().isBefore(TimeUtil.now())) {
            throw BizException.of(ErrorCode.VERIFY_LINK_INVALID);
        }
        // 一次性：验证成功即刻作废；重发也会换掉旧令牌 —— 转发出去的邮件、抄下来的链接都不该长期有效
        userService.update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getEmailVerified, true)
                .set(User::getVerifyToken, null)
                .set(User::getVerifyExpiresAt, null)
                .set(User::getUpdatedAt, TimeUtil.now()));
        // 只记 id：这一行日志的读者是运维，验证链接本身是秘密，邮箱地址则由用户自己那页去看
        log.info("邮箱验证成功 id={}", user.getId());
        return new VerificationResultVO(user.getEmail());
    }

    /**
     * 公开重发：按用户名或邮箱找回账号再发一封。注册后停在「去收邮件」的人与被闸门拦下的人都走这里。
     *
     * <p>回复永远是 {@link #RESEND_NOTICE}；账号不存在也照样扣一次限速额度，免得有人拿响应快慢反推账号在不在。</p>
     */
    public String resend(String account, String ip, String baseUrl) {
        String key = account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
        acquireResendQuota(key, ip);
        User user = key.isEmpty() ? null : userService.findByUsernameOrEmail(key);
        if (user == null || !needsMail(user)) {
            return RESEND_NOTICE;
        }
        String rawToken = arm(user, true);
        persistPending(user);
        verificationMailWorker.sendAsync(pendingOf(user, settings(), rawToken, baseUrl));
        return RESEND_NOTICE;
    }

    /**
     * 已登录的人补验证：收件地址只取当前账号自己的邮箱，不接受参数。
     *
     * <p>地址来自登录态而不是请求体，别人就借不了这个接口往任意邮箱投邮件。
     * 这里带着登录态，账号身份是已知的，所以照实报错 —— 与公开接口的口径不同，是有意的。</p>
     */
    public String resendForCurrentUser(String baseUrl) {
        long userId = UserContext.requireUserId();
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw BizException.param("先填写邮箱，再发送验证邮件");
        }
        if (user.hasVerifiedEmail()) {
            throw BizException.param("这个邮箱已经验证过了");
        }
        acquireResendQuota("uid:" + userId, null);
        String rawToken = arm(user, true);
        persistPending(user);
        verificationMailWorker.sendAsync(pendingOf(user, settings(), rawToken, baseUrl));
        log.info("补发验证邮件 id={}", userId);
        return RESEND_NOTICE;
    }

    /**
     * 登录闸门：这个账号此刻该不该被挡在门外。
     *
     * <p>先读那一列再读配置 —— 绝大多数账号都是已验证的，不必为了问一句「开关开着吗」多打一次库。
     * 平台 ADMIN 豁免的理由见类注释；关掉总开关等于给所有被卡住的人开门，
     * 那是管理员手上唯一也够用的一把备用钥匙。</p>
     */
    public boolean blocksLogin(User user) {
        return !user.hasVerifiedEmail() && !user.isAdmin() && required();
    }

    /** 拦下时带上脱敏邮箱，前端才说得出「该去翻哪个收件箱」。 */
    public BizException emailNotVerified(User user) {
        String masked = EmailUtil.mask(user.getEmail());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", masked);
        return BizException.of(ErrorCode.EMAIL_NOT_VERIFIED,
                "邮箱 " + masked + " 还没验证，请查收验证邮件").payload(payload);
    }

    /** 这个账号现在还「应该且能够」被要求验证吗：开关开着、没验证过、并且有一个能收信的地址。 */
    private boolean needsMail(User user) {
        return required() && !user.hasVerifiedEmail()
                && user.getEmail() != null && !user.getEmail().isBlank();
    }

    /**
     * 挂上或解除「待验证」状态。
     *
     * @param required 需要验证时生成新令牌并返回原文；不需要时把账号标成已验证并清空令牌
     */
    private String arm(User user, boolean required) {
        if (!required) {
            user.setEmailVerified(true);
            user.setVerifyToken(null);
            user.setVerifyExpiresAt(null);
            return null;
        }
        String rawToken = newToken();
        user.setEmailVerified(false);
        user.setVerifyToken(HashUtil.sha256Hex(rawToken));
        user.setVerifyExpiresAt(TimeUtil.now().plus(LINK_TTL));
        return rawToken;
    }

    /**
     * 重发要把新令牌写进库（旧链接随之作废）。
     *
     * <p>{@code updateById} 会跳过 null，而这里要写的正是可能为 null 的那几列，
     * 所以逐列显式给出 —— 与 {@code AuthServiceImpl.persistAccount} 同一个理由。</p>
     */
    private void persistPending(User user) {
        userService.update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getEmailVerified, user.getEmailVerified())
                .set(User::getVerifyToken, user.getVerifyToken())
                .set(User::getVerifyExpiresAt, user.getVerifyExpiresAt())
                .set(User::getUpdatedAt, TimeUtil.now()));
    }

    /** 组装一封待投递的验证邮件；链接里的令牌是原文，只在内存里存在。 */
    private VerificationMailWorker.PendingMail pendingOf(User user, MailSettings settings,
                                                         String rawToken, String baseUrl) {
        String link = (baseUrl == null ? "" : baseUrl) + "/verify-email?token=" + rawToken;
        return new VerificationMailWorker.PendingMail(user.getId(), user.getEmail(),
                user.getDisplayName(), link, baseUrl, settings, (int) LINK_TTL.toHours());
    }

    private void acquireResendQuota(String account, String ip) {
        if (!rateLimiter.tryAcquire(RESEND_KEY_ACCOUNT + account, RESEND_LIMIT_ACCOUNT, RESEND_WINDOW)) {
            throw BizException.of(ErrorCode.RESEND_TOO_FREQUENT, "验证邮件刚刚发过，请稍后再试");
        }
        if (ip != null && !ip.isBlank()
                && !rateLimiter.tryAcquire(RESEND_KEY_IP + ip, RESEND_LIMIT_IP, RESEND_WINDOW)) {
            throw BizException.of(ErrorCode.RESEND_TOO_FREQUENT, "当前网络发送验证邮件过于频繁，请稍后再试");
        }
    }

    /** base64url 无填充：链接里不会出现 {@code +}、{@code /}、{@code =}，邮件客户端折行也不会把令牌拆坏。 */
    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
