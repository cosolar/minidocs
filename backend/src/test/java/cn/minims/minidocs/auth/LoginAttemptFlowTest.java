package cn.minims.minidocs.auth;

import cn.minims.minidocs.auth.dto.AuthDtos.LoginRequest;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码连续输错：前两次还是普通的「用户名或密码错误」，第三次改成「请改用邮箱验证码」。
 *
 * <p>人机验证在这里关掉（{@code captcha.login-required=false}）：本用例关心的是计数阈值，
 * 而不是滑块本身。登录成功后的路径依赖 Sa-Token 的 Web 会话，不在这里断言。</p>
 */
@DisplayName("密码连续输错三次切换验证码登录")
@TestPropertySource(properties = "minidocs.captcha.login-required=false")
class LoginAttemptFlowTest extends MiniDocsTestBase {

    @Autowired
    private AuthService authService;

    @Test
    @DisplayName("前两次普通报错，第三次返回 40101")
    void thirdFailureAsksForCode() {
        saveUser("attempt-user", null, User.ROLE_USER);
        LoginRequest wrong = new LoginRequest("attempt-user", "definitely-wrong", "unused");

        BizException first = catchBiz(() -> authService.login(wrong, "10.4.0.1"));
        BizException second = catchBiz(() -> authService.login(wrong, "10.4.0.1"));
        BizException third = catchBiz(() -> authService.login(wrong, "10.4.0.1"));

        assertThat(first.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(second.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(third.getErrorCode()).isEqualTo(ErrorCode.LOGIN_CODE_REQUIRED);
    }

    @Test
    @DisplayName("计数按账号各算各的：别人输错不会把自己送进验证码模式")
    void otherAccountsDoNotShareCounter() {
        saveUser("attempt-a", null, User.ROLE_USER);
        saveUser("attempt-b", null, User.ROLE_USER);

        for (int i = 0; i < 3; i++) {
            catchBiz(() -> authService.login(
                    new LoginRequest("attempt-a", "definitely-wrong", "unused"), "10.4.0.2"));
        }
        BizException other = catchBiz(() -> authService.login(
                new LoginRequest("attempt-b", "definitely-wrong", "unused"), "10.4.0.3"));

        assertThat(other.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
