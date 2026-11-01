package cn.minims.minidocs.auth.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.minims.minidocs.auth.dto.AuthDtos.AccountUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginResponse;
import cn.minims.minidocs.auth.dto.AuthDtos.PasswordUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.auth.service.EmailVerificationService;
import cn.minims.minidocs.auth.service.RegisterCodeService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.SettingsService;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final int LOGIN_LIMIT = 5;
    private static final Duration LOGIN_WINDOW = Duration.ofSeconds(60);
    private static final String GENERIC_LOGIN_ERROR = "用户名或密码错误";

    private final UserService userService;
    private final SettingsService settingsService;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final MiniDocsProperties properties;
    private final EmailVerificationService emailVerificationService;
    private final RegisterCodeService registerCodeService;

    @Override
    public LoginResponse login(LoginRequest request, String ip) {
        String account = request.username() == null ? "" : request.username().trim().toLowerCase(Locale.ROOT);
        String limitKey = "login:" + (account.isEmpty() ? ip : account);
        if (!rateLimiter.tryAcquire(limitKey, LOGIN_LIMIT, LOGIN_WINDOW)) {
            throw BizException.of(ErrorCode.LOGIN_TOO_FREQUENT, "尝试过于频繁，请 60 秒后重试");
        }

        User user = userService.findByUsernameOrEmail(account);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, GENERIC_LOGIN_ERROR);
        }
        // 账号状态异常时对外统一提示，避免暴露账号存在性
        if (!user.isActive()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, GENERIC_LOGIN_ERROR);
        }
        // 密码已经验过，这里不再伪装成「密码错误」：拦的是「这个邮箱还没证明归你」，
        // 说成口令不对只会让人反复改密码。豁免与回落规则见 EmailVerificationService。
        if (emailVerificationService.blocksLogin(user)) {
            throw emailVerificationService.emailNotVerified(user);
        }

        StpUtil.login(user.getId());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        rateLimiter.reset(limitKey);
        log.info("用户登录成功 id={} username={}", user.getId(), user.getUsername());
        return new LoginResponse(tokenInfo.getTokenValue(), tokenInfo.getTokenTimeout(),
                UserVO.from(user), settingsService.getSettings(user.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterRequest request, String baseUrl) {
        if (!properties.isRegisterEnabled()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "自主注册已关闭，请联系管理员开通账号");
        }
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (userService.usernameExists(username, null)) {
            throw BizException.exists("用户名已被占用");
        }
        String email = request.email() == null ? null : request.email().trim().toLowerCase(Locale.ROOT);
        MailSettings mail = emailVerificationService.settings();
        boolean codeRequired = mail.verificationRequired();
        if (codeRequired && (email == null || email.isEmpty())) {
            // 闸门开着时邮箱必填：缺了它就没有可验证的地址，把人放进来的唯一方式是让闸门形同虚设
            throw BizException.param("请填写邮箱，注册后需要验证才能登录");
        }
        if (email != null && !email.isEmpty() && userService.emailExists(email, null)) {
            throw BizException.exists("邮箱已被占用");
        }
        if (codeRequired) {
            String emailCode = request.emailCode() == null ? "" : request.emailCode().trim();
            if (emailCode.isEmpty()) {
                throw BizException.param("请填写邮箱验证码");
            }
            // 码不对或过期就不建号：注册不该留下一个「进不去也重发不了」的半成品账号
            registerCodeService.verify(email, emailCode);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(trimToNull(request.displayName()));
        user.setEmail(email == null || email.isEmpty() ? null : email);
        user.setRole(User.ROLE_USER);
        // 自助注册即可用：v2 的准入由组织成员关系决定，不再有一层平台审核队列（规范 §3.1）。
        // 历史 pending / rejected 行继续被 AuthInterceptor 拦住，由平台后台处置 —— 那是账号级状态，与本条无关。
        user.setStatus(User.STATUS_ACTIVE);
        String rawToken;
        if (codeRequired) {
            // 验证码已经证明这个收件箱归本人，账号直接落已验证；不再另发一封验证链接
            user.setEmailVerified(true);
            user.setVerifyToken(null);
            user.setVerifyExpiresAt(null);
            rawToken = null;
        } else {
            rawToken = emailVerificationService.prepareRegistration(user, mail);
        }
        userService.save(user);
        // 注册即建个人组织：库归属不能取决于「是否已经建过库」
        tenantService.ensurePersonalTenant(user.getId());
        emailVerificationService.dispatchAfterCommit(user, mail, rawToken, baseUrl);
        return UserVO.from(user);
    }

    @Override
    public UserVO currentUser() {
        User user = userService.getById(UserContext.requireUserId());
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return UserVO.from(user);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateAccount(AccountUpdateRequest request, String baseUrl) {
        Long userId = UserContext.requireUserId();
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String previousEmail = user.getEmail();
        if (request.username() != null && !request.username().isBlank()) {
            String username = request.username().trim().toLowerCase(Locale.ROOT);
            if (userService.usernameExists(username, userId)) {
                throw BizException.exists("用户名已被占用");
            }
            user.setUsername(username);
        }
        if (request.displayName() != null) {
            user.setDisplayName(trimToNull(request.displayName()));
        }
        if (request.email() != null) {
            String email = trimToNull(request.email());
            MailSettings mail = emailVerificationService.settings();
            if (email == null && mail.verificationRequired() && !user.hasVerifiedEmail()) {
                // 没验证过的人清掉邮箱 = 把自己锁在门外：闸门只认这个地址，清掉就再没有能收到链接的地方
                throw BizException.param("邮箱还没验证，验证前不能清空");
            }
            if (email != null) {
                email = email.toLowerCase(Locale.ROOT);
                if (userService.emailExists(email, userId)) {
                    throw BizException.exists("邮箱已被占用");
                }
            }
            user.setEmail(email);
            persistAccount(user, emailVerificationService
                    .prepareEmailChange(user, previousEmail, mail), mail, baseUrl);
            return UserVO.from(user);
        }
        persistAccount(user, null, emailVerificationService.settings(), baseUrl);
        return UserVO.from(user);
    }

    /**
     * 写回账号资料。
     *
     * <p>{@code updateById} 会跳过 null，而「把显示名/邮箱清空」要写的正是 null：用整行更新时
     * 界面回显已清空、库里仍是旧值，刷新后邮箱又冒出来。列全部显式列出，顺带保证
     * {@code password_hash} 与 {@code role} 不会从这条流程被带出去。</p>
     *
     * <p>{@code rawToken} 非空表示这次改了邮箱且需要重新验证：连同那三列一起写，
     * 提交后再把邮件发出去（发信失败由后台任务回落成已验证）。</p>
     */
    private void persistAccount(User user, String rawToken, MailSettings mail, String baseUrl) {
        LambdaUpdateWrapper<User> update = Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getUsername, user.getUsername())
                .set(User::getDisplayName, user.getDisplayName())
                .set(User::getEmail, user.getEmail())
                .set(User::getUpdatedAt, TimeUtil.now());
        if (rawToken != null) {
            update.set(User::getEmailVerified, user.getEmailVerified())
                    .set(User::getVerifyToken, user.getVerifyToken())
                    .set(User::getVerifyExpiresAt, user.getVerifyExpiresAt());
        }
        userService.update(update);
        emailVerificationService.dispatchAfterCommit(user, mail, rawToken, baseUrl);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(PasswordUpdateRequest request) {
        Long userId = UserContext.requireUserId();
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw BizException.param("原密码不正确");
        }
        User update = new User();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userService.updateById(update);
        log.info("用户修改密码 id={}", userId);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
