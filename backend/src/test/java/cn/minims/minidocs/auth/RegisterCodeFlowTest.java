package cn.minims.minidocs.auth;

import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.auth.entity.RegisterCode;
import cn.minims.minidocs.auth.mapper.RegisterCodeMapper;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.auth.service.RegisterCodeService;
import cn.minims.minidocs.auth.support.VerificationMailWorker;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * 注册邮箱验证码：发码、校验、一次性、限速与失败清理。
 *
 * <p>SMTP 由 mock 的 {@link MailService} 接管，测试只关心「发出去的是什么码」与
 * 「账号最终是什么状态」，不碰真实网络。用户注册成功即已验证，链接邮件不再参与注册。</p>
 *
 * <p>用例之间不共用邮箱与 IP：{@code RateLimiter} 是跨事务的单例，回滚不会把限速窗口一起回滚。</p>
 */
@DisplayName("注册邮箱验证码")
class RegisterCodeFlowTest extends MiniDocsTestBase {

    @Autowired
    private AuthService authService;

    @Autowired
    private RegisterCodeService registerCodeService;

    @Autowired
    private SiteConfigService siteConfigService;

    @Autowired
    private RegisterCodeMapper registerCodeMapper;

    @MockitoBean
    private MailService mailService;

    @MockitoBean
    private VerificationMailWorker mailWorker;

    /** 打开「要求验证」并把传输参数配齐（口令留空，transportReady 不要求它）。 */
    private void enableVerification() {
        Map<String, Object> patch = new HashMap<>();
        patch.put(SiteConfig.KEY_SMTP_HOST, "smtp.example.com");
        patch.put(SiteConfig.KEY_SMTP_PORT, 587);
        patch.put(SiteConfig.KEY_SMTP_FROM, "no-reply@example.com");
        patch.put(SiteConfig.KEY_EMAIL_VERIFY_ENABLED, true);
        siteConfigService.updateMail(patch);
    }

    /** 发一次码，并从 mock 的邮件里取回明文验证码。 */
    private String issue(String email, String ip) {
        registerCodeService.send(email, ip, TEST_BASE_URL);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mailService, atLeastOnce()).sendRegisterCode(any(MailSettings.class), eq(email),
                captor.capture(), eq((int) RegisterCodeService.CODE_TTL.toMinutes()), anyString());
        List<String> values = captor.getAllValues();
        return values.get(values.size() - 1);
    }

    /** 造一个必定错误的 6 位码：翻转最后一位，不依赖随机数撞车。 */
    private static String wrongOf(String code) {
        char last = code.charAt(code.length() - 1);
        return code.substring(0, code.length() - 1) + (last == '0' ? '1' : '0');
    }

    @Test
    @DisplayName("站点没开邮箱验证时不发码")
    void sendingRequiresVerificationEnabled() {
        BizException error = catchBiz(() -> registerCodeService.send("off@example.com", "10.2.0.1", TEST_BASE_URL));

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).contains("未开启");
        verify(mailService, never()).sendRegisterCode(any(), any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("库里只存摘要：发出去的 6 位码与哈希对得上，10 分钟有效")
    void codeIsMailedAndHashed() {
        enableVerification();
        String code = issue("hash@example.com", "10.2.0.2");

        assertThat(code).matches("^\\d{6}$");
        RegisterCode row = registerCodeMapper.selectById("hash@example.com");
        assertThat(row).isNotNull();
        assertThat(row.getCodeHash())
                .isEqualTo(HashUtil.sha256Hex("hash@example.com:" + code))
                .isNotEqualTo(code);
        assertThat(row.getExpiresAt()).isAfter(TimeUtil.now().plusMinutes(9));
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    @DisplayName("同一邮箱 60 秒内只发一次")
    void sendIsRateLimited() {
        enableVerification();
        issue("limit@example.com", "10.2.0.3");

        assertBizError(ErrorCode.RESEND_TOO_FREQUENT,
                () -> registerCodeService.send("limit@example.com", "10.2.0.3", TEST_BASE_URL));
        verify(mailService, atLeastOnce()).sendRegisterCode(any(), eq("limit@example.com"), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("邮箱已被占用时不发码")
    void existingEmailIsRejected() {
        enableVerification();
        User existing = saveUser("taken", null, User.ROLE_USER);
        existing.setEmail("taken@example.com");
        userService.updateById(existing);

        assertBizError(ErrorCode.ALREADY_EXISTS,
                () -> registerCodeService.send("taken@example.com", "10.2.0.4", TEST_BASE_URL));
        verify(mailService, never()).sendRegisterCode(any(), any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("带正确的码注册：账号直接已验证、码被消费、不再发验证链接")
    void registerConsumesCode() {
        enableVerification();
        String code = issue("code1@example.com", "10.2.0.5");

        var vo = authService.register(
                new RegisterRequest("code1", "Passw0rd!", "编码", "code1@example.com", code), TEST_BASE_URL);
        runAfterCommitHooks();

        User saved = userService.getById(vo.id());
        assertThat(saved.hasVerifiedEmail()).isTrue();
        assertThat(saved.getVerifyToken()).isNull();
        assertThat(saved.getVerifyExpiresAt()).isNull();
        assertThat(registerCodeMapper.selectById("code1@example.com")).isNull();
        verify(mailWorker, never()).sendAsync(any());
    }

    @Test
    @DisplayName("码不对：不建号，码还在，试错次数 +1")
    void wrongCodeDoesNotCreateAccount() {
        enableVerification();
        String code = issue("wrong@example.com", "10.2.0.6");

        BizException error = catchBiz(() -> authService.register(
                new RegisterRequest("wrong1", "Passw0rd!", null, "wrong@example.com", wrongOf(code)),
                TEST_BASE_URL));

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).contains("不正确");
        assertThat(userService.findByUsernameOrEmail("wrong1")).isNull();
        assertThat(registerCodeMapper.selectById("wrong@example.com").getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("过期的码不能用，并随即清掉")
    void expiredCodeRejected() {
        enableVerification();
        String code = issue("expire@example.com", "10.2.0.7");
        RegisterCode row = registerCodeMapper.selectById("expire@example.com");
        row.setExpiresAt(TimeUtil.now().minusMinutes(1));
        registerCodeMapper.updateById(row);

        BizException error = catchBiz(() -> registerCodeService.verify("expire@example.com", code));

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).contains("过期");
        assertThat(registerCodeMapper.selectById("expire@example.com")).isNull();
    }

    @Test
    @DisplayName("连错 5 次即作废，之后连正确的码也不认")
    void tooManyAttemptsInvalidatesCode() {
        enableVerification();
        String code = issue("brute@example.com", "10.2.0.8");
        String wrong = wrongOf(code);
        for (int i = 0; i < RegisterCodeService.MAX_ATTEMPTS - 1; i++) {
            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> registerCodeService.verify("brute@example.com", wrong));
        }

        BizException error = catchBiz(() -> registerCodeService.verify("brute@example.com", wrong));
        assertThat(error.getMessage()).contains("次数过多");
        assertThat(registerCodeMapper.selectById("brute@example.com")).isNull();

        BizException gone = catchBiz(() -> registerCodeService.verify("brute@example.com", code));
        assertThat(gone.getMessage()).contains("失效");
    }

    @Test
    @DisplayName("验证码一次性：验过即删，重放无效")
    void codeIsOneTime() {
        enableVerification();
        String code = issue("once@example.com", "10.2.0.9");

        registerCodeService.verify("once@example.com", code);
        assertThat(registerCodeMapper.selectById("once@example.com")).isNull();
        assertBizError(ErrorCode.PARAM_INVALID,
                () -> registerCodeService.verify("once@example.com", code));
    }

    @Test
    @DisplayName("开启验证后不带码注册直接拒绝，且不建号")
    void registerWithoutCodeIsRejected() {
        enableVerification();

        BizException error = catchBiz(() -> authService.register(
                new RegisterRequest("nocode", "Passw0rd!", null, "nocode@example.com"), TEST_BASE_URL));

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).contains("验证码");
        assertThat(userService.findByUsernameOrEmail("nocode")).isNull();
    }

    @Test
    @DisplayName("发信失败：不留存根，冷却也放开，可以立刻重试")
    void mailFailureClearsPendingCode() {
        enableVerification();
        doThrow(BizException.of(ErrorCode.MAIL_SEND_FAILED, "邮件发送失败：连不上"))
                .when(mailService).sendRegisterCode(any(), any(), any(), anyInt(), anyString());

        assertBizError(ErrorCode.MAIL_SEND_FAILED,
                () -> registerCodeService.send("retry@example.com", "10.2.0.10", TEST_BASE_URL));
        assertThat(registerCodeMapper.selectById("retry@example.com")).isNull();

        reset(mailService);
        String code = issue("retry@example.com", "10.2.0.10");
        assertThat(code).matches("^\\d{6}$");
    }
}
