package cn.minims.minidocs.auth;

import cn.minims.minidocs.auth.dto.AuthDtos.AccountUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.auth.service.EmailVerificationService;
import cn.minims.minidocs.auth.support.VerificationMailWorker;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.HashUtil;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.mapper.SiteConfigMapper;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.user.entity.User;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 邮箱验证的端到端行为：注册写状态、点链接解状态、闸门只认那一位。
 *
 * <p>发信由 mock 的 {@link VerificationMailWorker} 接管：测试不碰 SMTP，
 * 而「任务里装了什么东西」正是这几条契约的全部证据 —— 收件地址、链接里的令牌原文、
 * 以及什么情况下根本不该发。</p>
 *
 * <p>用例之间不共用用户名：{@code RateLimiter} 是跨事务的单例，回滚不会把限速窗口一起回滚。</p>
 */
@DisplayName("邮箱验证（注册闸门）")
class EmailVerificationFlowTest extends MiniDocsTestBase {

    @Autowired
    private AuthService authService;

    @Autowired
    private EmailVerificationService verificationService;

    @Autowired
    private SiteConfigService siteConfigService;

    @Autowired
    private SiteConfigMapper siteConfigMapper;

    @MockitoBean
    private VerificationMailWorker mailWorker;

    /**
     * 打开「要求验证」并把传输参数配齐。
     *
     * <p>口令故意留空：{@code transportReady()} 不要求用户名与口令，测试因此不必造一份假密文。</p>
     */
    private void enableVerification() {
        Map<String, Object> patch = new HashMap<>();
        patch.put(SiteConfig.KEY_SMTP_HOST, "smtp.example.com");
        patch.put(SiteConfig.KEY_SMTP_PORT, 587);
        patch.put(SiteConfig.KEY_SMTP_FROM, "no-reply@example.com");
        patch.put(SiteConfig.KEY_EMAIL_VERIFY_ENABLED, true);
        siteConfigService.updateMail(patch);
    }

    /**
     * 把配置写成「开关开着、传输参数没了」。
     *
     * <p>这个组合在界面上存不进去（保存侧会拒绝），只能来自直接改库或换了加密密钥 ——
     * 而闸门判断必须能扛住它，否则管理员一次误操作就把注册通道彻底堵死。</p>
     */
    private void enableSwitchWithoutTransport() {
        enableVerification();
        SiteConfig row = siteConfigMapper.selectById(SiteConfig.SINGLETON_ID);
        row.setConfig(JsonUtil.toJson(Map.of(SiteConfig.KEY_EMAIL_VERIFY_ENABLED, true)));
        siteConfigMapper.updateById(row);
    }

    private User reload(long userId) {
        return userService.getById(userId);
    }

    /** 已投递任务的收件箱：按调用顺序排列，最后一条是最新发出的那封。 */
    private List<VerificationMailWorker.PendingMail> dispatched() {
        ArgumentCaptor<VerificationMailWorker.PendingMail> captor =
                ArgumentCaptor.forClass(VerificationMailWorker.PendingMail.class);
        verify(mailWorker, atLeastOnce()).sendAsync(captor.capture());
        return captor.getAllValues();
    }

    /** 从待投递任务里取出令牌原文：链接是唯一带原文的地方，库里只有摘要。 */
    private static String tokenOf(VerificationMailWorker.PendingMail pending) {
        String link = pending.link();
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    /** 用最新那封邮件里的链接把账号验成已验证，返回验证结果里的地址。 */
    private String verifyLatestLink() {
        List<VerificationMailWorker.PendingMail> all = dispatched();
        return verificationService.verify(tokenOf(all.get(all.size() - 1))).email();
    }

    /** 最近一封待投递的验证邮件。 */
    private VerificationMailWorker.PendingMail latestPending() {
        List<VerificationMailWorker.PendingMail> all = dispatched();
        return all.get(all.size() - 1);
    }

    /**
     * 直接造一个「等验证」的用户。
     *
     * <p>注册路径已改为邮箱验证码，注册出来的账号直接是已验证；链接流程现在主要服务
     * 「之后改邮箱」，所以这些用例从一行未验证用户起步，而不是先走注册。</p>
     */
    private User pendingUser(String username, String email) {
        User user = saveUser(username, null, User.ROLE_USER);
        user.setEmail(email);
        user.setEmailVerified(false);
        userService.updateById(user);
        return reload(user.getId());
    }

    @Nested
    @DisplayName("没配邮件服务时")
    class WithoutTransport {

        @Test
        @DisplayName("注册照旧放行：不发信、不设闸门")
        void registerStaysOpen() {
            var vo = authService.register(
                    new RegisterRequest("open1", "Passw0rd!", null, null), TEST_BASE_URL);
            runAfterCommitHooks();

            User saved = reload(vo.id());
            assertThat(saved.hasVerifiedEmail()).isTrue();
            assertThat(saved.getVerifyToken()).isNull();
            assertThat(verificationService.blocksLogin(saved)).isFalse();
            verify(mailWorker, never()).sendAsync(any());
        }

        @Test
        @DisplayName("开关开着但参数不齐，同样退回放行")
        void switchWithoutTransportFallsBack() {
            enableSwitchWithoutTransport();

            var vo = authService.register(
                    new RegisterRequest("open2", "Passw0rd!", null, "open2@example.com"), TEST_BASE_URL);
            runAfterCommitHooks();

            assertThat(reload(vo.id()).hasVerifiedEmail()).isTrue();
            verify(mailWorker, never()).sendAsync(any());
        }

        @Test
        @DisplayName("没查这一列的对象按已验证处理，闸门不会误伤")
        void unknownFlagPasses() {
            User unknown = new User();
            unknown.setRole(User.ROLE_USER);
            unknown.setStatus(User.STATUS_ACTIVE);
            unknown.setEmail("unknown@example.com");

            assertThat(unknown.getEmailVerified()).isNull();
            assertThat(unknown.hasVerifiedEmail()).isTrue();
            assertThat(verificationService.blocksLogin(unknown)).isFalse();
        }
    }

    @Nested
    @DisplayName("验证已开启")
    class WithVerification {

        @Test
        @DisplayName("邮箱必填")
        void emailBecomesMandatory() {
            enableVerification();

            BizException error = catchBiz(() -> authService.register(
                    new RegisterRequest("nodeep", "Passw0rd!", null, null), TEST_BASE_URL));

            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(error.getMessage()).contains("邮箱");
            verify(mailWorker, never()).sendAsync(any());
        }

        @Test
        @DisplayName("邮箱已被占用时不建号也不发信")
        void duplicateEmailRejected() {
            enableVerification();
            User existing = saveUser("dup1", null, User.ROLE_USER);
            existing.setEmail("dup1@example.com");
            userService.updateById(existing);

            assertBizError(ErrorCode.ALREADY_EXISTS, () -> authService.register(
                    new RegisterRequest("dup2", "Passw0rd!", null, "DUP1@example.com", "123456"), TEST_BASE_URL));
            verify(mailWorker, never()).sendAsync(any());
        }

        @Test
        @DisplayName("验证过后置为已验证，同一条链接不能再用第二次")
        void verifyIsOneTime() {
            enableVerification();
            pendingUser("once1", "once1@example.com");
            verificationService.resend("once1", "10.0.0.1", TEST_BASE_URL);
            String raw = tokenOf(latestPending());

            assertThat(verifyLatestLink()).isEqualTo("once1@example.com");

            User saved = userService.findByUsernameOrEmail("once1");
            assertThat(saved.getEmailVerified()).isTrue();
            assertThat(saved.getVerifyToken()).isNull();
            assertThat(saved.getVerifyExpiresAt()).isNull();
            assertBizError(ErrorCode.VERIFY_LINK_INVALID, () -> verificationService.verify(raw));
        }

        @Test
        @DisplayName("过期的链接与乱填的链接一样无效，也不会顺手改状态")
        void expiredLinkRejected() {
            enableVerification();
            User user = pendingUser("exp1", "exp1@example.com");
            verificationService.resend("exp1", "10.0.0.1", TEST_BASE_URL);
            String raw = tokenOf(latestPending());
            userService.update(Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, user.getId())
                    .set(User::getVerifyExpiresAt, TimeUtil.now().minusMinutes(1)));

            assertBizError(ErrorCode.VERIFY_LINK_INVALID, () -> verificationService.verify(raw));
            assertBizError(ErrorCode.VERIFY_LINK_INVALID, () -> verificationService.verify("  "));
            assertBizError(ErrorCode.VERIFY_LINK_INVALID, () -> verificationService.verify(raw + "x"));
            assertThat(reload(user.getId()).getEmailVerified()).isFalse();
        }

        @Test
        @DisplayName("闸门：未验证的普通用户拦下，平台 ADMIN 被豁免")
        void gateCoversOnlyPendingUsers() {
            enableVerification();
            User pending = saveUser("gate1", "盖茨", User.ROLE_USER);
            pending.setEmail("gate1@example.com");
            pending.setEmailVerified(false);
            userService.updateById(pending);
            assertThat(verificationService.blocksLogin(pending)).isTrue();

            User admin = saveUser("gate2", "甘特", User.ROLE_ADMIN);
            admin.setEmail("gate2@example.com");
            admin.setEmailVerified(false);
            userService.updateById(admin);
            assertThat(verificationService.blocksLogin(admin)).isFalse();

            User done = saveUser("gate3", "关涛", User.ROLE_USER);
            done.setEmail("gate3@example.com");
            userService.updateById(done);
            assertThat(verificationService.blocksLogin(done)).isFalse();
        }

        @Test
        @DisplayName("关掉开关就给被卡住的人开门")
        void switchOffOpensTheGate() {
            enableVerification();
            User pending = saveUser("gate4", "郭涛", User.ROLE_USER);
            pending.setEmail("gate4@example.com");
            pending.setEmailVerified(false);
            userService.updateById(pending);
            assertThat(verificationService.blocksLogin(reload(pending.getId()))).isTrue();

            Map<String, Object> patch = new HashMap<>();
            patch.put(SiteConfig.KEY_EMAIL_VERIFY_ENABLED, false);
            siteConfigService.updateMail(patch);

            assertThat(verificationService.blocksLogin(reload(pending.getId()))).isFalse();
        }

        @Test
        @DisplayName("拦下的提示只给脱敏邮箱")
        void gateMessageMasksTheAddress() {
            User pending = saveUser("gate5", "高原", User.ROLE_USER);
            pending.setEmail("gaoyuan@example.com");
            pending.setEmailVerified(false);
            userService.updateById(pending);

            BizException error = verificationService.emailNotVerified(pending);

            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
            assertThat(error.getMessage()).contains("g***n@example.com")
                    .doesNotContain("gaoyuan@example.com");
            assertThat(String.valueOf(error.getPayload())).doesNotContain("gaoyuan@example.com");
        }

        @Test
        @DisplayName("重发：新令牌作废旧链接")
        void resendReplacesThePendingToken() {
            enableVerification();
            User user = saveUser("rsnd1", null, User.ROLE_USER);
            user.setEmail("rsnd1@old.example");
            user.setEmailVerified(true);
            userService.updateById(user);
            // 第一枚令牌来自改邮箱：它和重发共用同一条「待验证」状态，但不占重发的配额
            UserContext.set(as(reload(user.getId())));
            try {
                authService.updateAccount(
                        new AccountUpdateRequest(null, null, "rsnd1@example.com"), TEST_BASE_URL);
                runAfterCommitHooks();
            } finally {
                UserContext.clear();
            }
            String oldToken = tokenOf(latestPending());

            String notice = verificationService.resend("rsnd1", "10.0.0.9", TEST_BASE_URL);

            assertThat(notice).isEqualTo(EmailVerificationService.RESEND_NOTICE);
            String fresh = tokenOf(latestPending());
            assertThat(fresh).isNotEqualTo(oldToken);
            assertThat(reload(user.getId()).getVerifyToken()).isEqualTo(HashUtil.sha256Hex(fresh));
            assertBizError(ErrorCode.VERIFY_LINK_INVALID, () -> verificationService.verify(oldToken));
            assertThat(verificationService.verify(fresh).email()).isEqualTo("rsnd1@example.com");
        }

        @Test
        @DisplayName("重发不泄露账号存在性：查不到也回同一句话，且不发信")
        void resendStaysGeneric() {
            enableVerification();

            String missing = verificationService.resend("nosuchuser", "10.0.0.8", TEST_BASE_URL);
            String noEmail = verificationService.resend("gate6", "10.0.0.8", TEST_BASE_URL);

            User bare = saveUser("gate6", "关六", User.ROLE_USER);
            assertThat(missing).isEqualTo(EmailVerificationService.RESEND_NOTICE);
            assertThat(noEmail).isEqualTo(EmailVerificationService.RESEND_NOTICE);
            assertThat(bare.getEmail()).isNull();
            verify(mailWorker, never()).sendAsync(any());
        }

        @Test
        @DisplayName("重发限速：同一账号 60 秒内只给一次")
        void resendIsRateLimited() {
            enableVerification();
            pendingUser("rlim1", "rlim1@example.com");

            verificationService.resend("rlim1", "10.0.0.7", TEST_BASE_URL);
            assertBizError(ErrorCode.RESEND_TOO_FREQUENT,
                    () -> verificationService.resend("rlim1", "10.0.0.7", TEST_BASE_URL));
        }

        @Test
        @DisplayName("已登录的人补验证：地址取自己的邮箱，已验证的照实说")
        void resendForCurrentUserUsesOwnAddress() {
            enableVerification();
            User user = pendingUser("own1", "own1@example.com");
            verificationService.resend("own1", "10.0.0.6", TEST_BASE_URL);
            String first = tokenOf(latestPending());

            UserContext.set(as(reload(user.getId())));
            try {
                assertThat(verificationService.resendForCurrentUser(TEST_BASE_URL))
                        .isEqualTo(EmailVerificationService.RESEND_NOTICE);
                assertThat(tokenOf(latestPending())).isNotEqualTo(first);
                assertThat(latestPending().to()).isEqualTo("own1@example.com");

                userService.update(Wrappers.<User>lambdaUpdate()
                        .eq(User::getId, user.getId())
                        .set(User::getEmailVerified, true)
                        .set(User::getVerifyToken, null)
                        .set(User::getVerifyExpiresAt, null));
                BizException error = catchBiz(() -> verificationService.resendForCurrentUser(TEST_BASE_URL));
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
                assertThat(error.getMessage()).contains("已经验证");
            } finally {
                UserContext.clear();
            }
        }

        @Test
        @DisplayName("改邮箱要重新验证并给新地址发一封")
        void emailChangeRestartsVerification() {
            enableVerification();
            User user = saveUser("mail1", "马力", User.ROLE_USER);
            user.setEmail("mail1@example.com");
            user.setEmailVerified(true);
            userService.updateById(user);

            UserContext.set(as(reload(user.getId())));
            try {
                var changed = authService.updateAccount(
                        new AccountUpdateRequest(null, "马力二号", "mail1@other.example"), TEST_BASE_URL);
                runAfterCommitHooks();

                User after = reload(changed.id());
                assertThat(after.getEmail()).isEqualTo("mail1@other.example");
                assertThat(after.getEmailVerified()).isFalse();
                VerificationMailWorker.PendingMail newest = latestPending();
                assertThat(newest.to()).isEqualTo("mail1@other.example");
                assertThat(after.getVerifyToken()).isEqualTo(HashUtil.sha256Hex(tokenOf(newest)));
            } finally {
                UserContext.clear();
            }
        }

        @Test
        @DisplayName("只改显示名不动验证状态，也不多发邮件")
        void displayNameOnlyKeepsVerificationState() {
            enableVerification();
            User user = saveUser("mail3", "马三", User.ROLE_USER);
            user.setEmail("mail3@example.com");
            user.setEmailVerified(true);
            userService.updateById(user);

            UserContext.set(as(reload(user.getId())));
            try {
                authService.updateAccount(new AccountUpdateRequest(null, "马三三号", null), TEST_BASE_URL);
                runAfterCommitHooks();

                User after = reload(user.getId());
                assertThat(after.getDisplayName()).isEqualTo("马三三号");
                assertThat(after.getEmailVerified()).isTrue();
                assertThat(after.getVerifyToken()).isNull();
                verify(mailWorker, never()).sendAsync(any());
            } finally {
                UserContext.clear();
            }
        }

        @Test
        @DisplayName("未验证的人不能把邮箱清空")
        void cannotClearEmailWhilePending() {
            enableVerification();
            User user = pendingUser("mail2", "mail2@example.com");

            UserContext.set(as(user));
            try {
                BizException error = catchBiz(() -> authService.updateAccount(
                        new AccountUpdateRequest(null, null, ""), TEST_BASE_URL));
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
                assertThat(error.getMessage()).contains("不能清空");

                User after = reload(user.getId());
                assertThat(after.getEmail()).isEqualTo("mail2@example.com");
                assertThat(after.getEmailVerified()).isFalse();
            } finally {
                UserContext.clear();
            }
        }
    }
}
