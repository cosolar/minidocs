package cn.minims.minidocs.auth.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.minims.minidocs.auth.dto.AuthDtos.AccountUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginResponse;
import cn.minims.minidocs.auth.dto.AuthDtos.PasswordUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.SettingsService;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

        StpUtil.login(user.getId());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        rateLimiter.reset(limitKey);
        log.info("用户登录成功 id={} username={}", user.getId(), user.getUsername());
        return new LoginResponse(tokenInfo.getTokenValue(), tokenInfo.getTokenTimeout(),
                UserVO.from(user), settingsService.getSettings(user.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterRequest request) {
        if (!properties.isRegisterEnabled()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "自主注册已关闭，请联系管理员开通账号");
        }
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (userService.usernameExists(username, null)) {
            throw BizException.exists("用户名已被占用");
        }
        String email = request.email() == null ? null : request.email().trim().toLowerCase(Locale.ROOT);
        if (email != null && !email.isEmpty() && userService.emailExists(email, null)) {
            throw BizException.exists("邮箱已被占用");
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
        userService.save(user);
        // 注册即建个人组织：库归属不能取决于「是否已经建过库」
        tenantService.ensurePersonalTenant(user.getId());
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
    public UserVO updateAccount(AccountUpdateRequest request) {
        Long userId = UserContext.requireUserId();
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
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
            if (email != null) {
                email = email.toLowerCase(Locale.ROOT);
                if (userService.emailExists(email, userId)) {
                    throw BizException.exists("邮箱已被占用");
                }
            }
            user.setEmail(email);
        }
        persistAccount(user);
        return UserVO.from(user);
    }

    /**
     * 写回账号资料。
     *
     * <p>{@code updateById} 会跳过 null，而「把显示名/邮箱清空」要写的正是 null：用整行更新时
     * 界面回显已清空、库里仍是旧值，刷新后邮箱又冒出来。列全部显式列出，顺带保证
     * {@code password_hash} 与 {@code role} 不会从这条流程被带出去。</p>
     */
    private void persistAccount(User user) {
        userService.update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getUsername, user.getUsername())
                .set(User::getDisplayName, user.getDisplayName())
                .set(User::getEmail, user.getEmail())
                .set(User::getUpdatedAt, TimeUtil.now()));
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
