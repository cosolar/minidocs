package cn.minims.minidocs.auth;

import cn.minims.minidocs.auth.dto.AuthDtos.LoginCodeVO;
import cn.minims.minidocs.auth.entity.LoginCode;
import cn.minims.minidocs.auth.mapper.LoginCodeMapper;
import cn.minims.minidocs.auth.service.LoginCodeService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.HashUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.mail.MailService;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 登录邮箱验证码：发码、掩码、校验、一次性与试错上限。
 *
 * <p>只测到 {@link LoginCodeService} 为止 —— 之后的「发 token、打改密标记」依赖 Web 请求上下文
 * （Sa-Token 的会话只能挂在真实请求上），放在集成测试里价值不大、噪音却很大。</p>
 *
 * <p>SMTP 由 mock 的 {@link MailService} 接管，用例之间不共用邮箱与 IP：
 * {@code RateLimiter} 是跨事务的单例，回滚不会把限速窗口一起回滚。</p>
 */
@DisplayName("登录邮箱验证码")
class LoginCodeFlowTest extends MiniDocsTestBase {

    @Autowired
    private LoginCodeService loginCodeService;

    @Autowired
    private LoginCodeMapper loginCodeMapper;

    @Autowired
    private SiteConfigService siteConfigService;

    @MockitoBean
    private MailService mailService;

    /** 配好发信传输参数（口令留空，transportReady 不要求它）。 */
    private void enableMail() {
        Map<String, Object> patch = new HashMap<>();
        patch.put(SiteConfig.KEY_SMTP_HOST, "smtp.example.com");
        patch.put(SiteConfig.KEY_SMTP_PORT, 587);
        patch.put(SiteConfig.KEY_SMTP_FROM, "no-reply@example.com");
        siteConfigService.updateMail(patch);
    }

    /** 建一个带邮箱的可用账号。 */
    private User userWithEmail(String username, String email) {
        User user = saveUser(username, null, User.ROLE_USER);
        user.setEmail(email);
        userService.updateById(user);
        return user;
    }

    /** 发一次码，并从 mock 的邮件里取回明文验证码。 */
    private String issue(String account, String ip) {
        loginCodeService.send(account, ip, TEST_BASE_URL);
        return lastMailedCode();
    }

    /** 取最近一封登录验证码邮件里的明文码。 */
    private String lastMailedCode() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mailService, atLeastOnce()).sendLoginCode(any(MailSettings.class), anyString(),
                captor.capture(), eq((int) LoginCodeService.CODE_TTL.toMinutes()), anyString());
        List<String> values = captor.getAllValues();
        return values.get(values.size() - 1);
    }

    /** 造一个必定错误的 6 位码：翻转最后一位，不依赖随机数撞车。 */
    private static String wrongOf(String code) {
        char last = code.charAt(code.length() - 1);
        return code.substring(0, code.length() - 1) + (last == '0' ? '1' : '0');
    }

    @Test
    @DisplayName("用用户名也能发码：只回打码邮箱，库里只存摘要")
    void codeIsMailedToBoundEmail() {
        enableMail();
        userWithEmail("loginmask", "mask@example.com");

        LoginCodeVO vo = loginCodeService.send("loginmask", "10.3.0.1", TEST_BASE_URL);
        String code = lastMailedCode();

        assertThat(vo.maskedEmail()).isEqualTo("m***k@example.com");
        assertThat(vo.cooldownSeconds()).isEqualTo(60);
        assertThat(vo.validMinutes()).isEqualTo((int) LoginCodeService.CODE_TTL.toMinutes());
        assertThat(code).matches("^\\d{6}$");

        LoginCode row = loginCodeMapper.selectById("mask@example.com");
        assertThat(row).isNotNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getExpiresAt()).isAfter(TimeUtil.now().plusMinutes(9));
        assertThat(row.getCodeHash())
                .isEqualTo(HashUtil.sha256Hex("mask@example.com:" + code))
                .isNotEqualTo(code);
    }

    @Test
    @DisplayName("验证码一次性：验过即删，重放无效")
    void codeIsOneTime() {
        enableMail();
        userWithEmail("logincome", "once-login@example.com");
        String code = issue("logincome", "10.3.0.2");

        LoginCode row = loginCodeMapper.selectById("once-login@example.com");
        assertThat(row).isNotNull();
        User verified = loginCodeService.verify("logincome", code);
        assertThat(verified.getUsername()).isEqualTo("logincome");
        assertThat(loginCodeMapper.selectById("once-login@example.com")).isNull();

        assertBizError(ErrorCode.PARAM_INVALID,
                () -> loginCodeService.verify("logincome", code));
    }

    @Test
    @DisplayName("码不对：记录试错次数，账号不会被登录")
    void wrongCodeIncrementsAttempts() {
        enableMail();
        userWithEmail("loginwrong", "wrong-login@example.com");
        String code = issue("loginwrong", "10.3.0.3");

        BizException error = catchBiz(() -> loginCodeService.verify("loginwrong", wrongOf(code)));

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).contains("不正确");
        assertThat(loginCodeMapper.selectById("wrong-login@example.com").getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("连错 5 次即作废，之后连正确的码也不认")
    void tooManyAttemptsInvalidatesCode() {
        enableMail();
        userWithEmail("loginbrute", "brute-login@example.com");
        String code = issue("loginbrute", "10.3.0.4");
        String wrong = wrongOf(code);
        for (int i = 0; i < LoginCodeService.MAX_ATTEMPTS - 1; i++) {
            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> loginCodeService.verify("loginbrute", wrong));
        }

        BizException error = catchBiz(() -> loginCodeService.verify("loginbrute", wrong));
        assertThat(error.getMessage()).contains("次数过多");
        assertThat(loginCodeMapper.selectById("brute-login@example.com")).isNull();

        BizException gone = catchBiz(() -> loginCodeService.verify("loginbrute", code));
        assertThat(gone.getMessage()).contains("失效");
    }

    @Test
    @DisplayName("过期码不能用，并随即清掉")
    void expiredCodeRejected() {
        enableMail();
        userWithEmail("loginexpire", "expire-login@example.com");
        String code = issue("loginexpire", "10.3.0.5");
        LoginCode row = loginCodeMapper.selectById("expire-login@example.com");
        row.setExpiresAt(TimeUtil.now().minusMinutes(1));
        loginCodeMapper.updateById(row);

        BizException error = catchBiz(() -> loginCodeService.verify("loginexpire", code));

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).contains("过期");
        assertThat(loginCodeMapper.selectById("expire-login@example.com")).isNull();
    }

    @Test
    @DisplayName("同一邮箱 60 秒内只发一次")
    void sendIsRateLimited() {
        enableMail();
        userWithEmail("loginlimit", "limit-login@example.com");
        issue("loginlimit", "10.3.0.6");

        assertBizError(ErrorCode.RESEND_TOO_FREQUENT,
                () -> loginCodeService.send("loginlimit", "10.3.0.6", TEST_BASE_URL));
    }

    @Test
    @DisplayName("重设许可：用码登录后可取用一次，第二次被拒")
    void resetPermissionIsOneShot() {
        enableMail();
        User user = userWithEmail("loginreset", "reset-login@example.com");

        loginCodeService.allowPasswordReset(user.getEmail());

        assertThat(loginCodeService.consumeResetPermission("reset-login@example.com")).isTrue();
        assertThat(loginCodeService.consumeResetPermission("reset-login@example.com")).isFalse();
    }

    @Test
    @DisplayName("重设许可过期即失效")
    void resetPermissionExpires() {
        enableMail();
        User user = userWithEmail("loginexpiredreset", "expired-reset@example.com");
        loginCodeService.allowPasswordReset(user.getEmail());
        LoginCode row = loginCodeMapper.selectById("expired-reset@example.com");
        row.setResetExpiresAt(TimeUtil.now().minusSeconds(1));
        loginCodeMapper.updateById(row);

        assertThat(loginCodeService.consumeResetPermission("expired-reset@example.com")).isFalse();
        assertThat(loginCodeMapper.selectById("expired-reset@example.com")).isNull();
    }

    @Test
    @DisplayName("密码登录成功只清重设许可，不误删还没用的验证码")
    void clearResetKeepsFreshCode() {
        enableMail();
        userWithEmail("loginclear", "clear-login@example.com");
        issue("loginclear", "10.3.0.10");
        loginCodeService.clearResetPermission("clear-login@example.com");
        assertThat(loginCodeMapper.selectById("clear-login@example.com")).isNotNull();

        loginCodeService.allowPasswordReset("clear-login@example.com");
        loginCodeService.clearResetPermission("clear-login@example.com");
        assertThat(loginCodeMapper.selectById("clear-login@example.com")).isNull();
    }

    @Test
    @DisplayName("查无此人 / 没绑邮箱 / 已停用：同一句话，且不发信")
    void unusableAccountLooksTheSame() {
        enableMail();
        User disabled = userWithEmail("loginoff", "off-login@example.com");
        disabled.setStatus(User.STATUS_DISABLED);
        userService.updateById(disabled);
        // 没有邮箱的账号：同样只能说「不能用验证码登录」，否则就成了账号状态探测器
        saveUser("login-noemail", null, User.ROLE_USER);

        BizException missing = catchBiz(() -> loginCodeService.send("nobody-here", "10.3.0.7", TEST_BASE_URL));
        BizException noMail = catchBiz(() -> loginCodeService.send("login-noemail", "10.3.0.8", TEST_BASE_URL));
        BizException inactive = catchBiz(() -> loginCodeService.send("loginoff", "10.3.0.9", TEST_BASE_URL));

        assertThat(missing.getMessage()).isEqualTo(noMail.getMessage()).isEqualTo(inactive.getMessage());
        verify(mailService, never()).sendLoginCode(any(), any(), any(), anyInt(), anyString());
    }
}
