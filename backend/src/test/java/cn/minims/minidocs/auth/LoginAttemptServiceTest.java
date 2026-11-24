package cn.minims.minidocs.auth;

import cn.minims.minidocs.auth.service.LoginAttemptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码失败计数：连续三次触发切换、换账号互不影响、登录成功后清零。
 *
 * <p>纯内存实现，不起 Spring —— 这样连窗口边界都能直接断言。</p>
 */
@DisplayName("密码失败计数")
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    @Test
    @DisplayName("同一账号连续失败到阈值即提示改用验证码")
    void countsUpToThreshold() {
        assertThat(service.recordFailure("alice")).isEqualTo(1);
        assertThat(service.recordFailure("alice")).isEqualTo(2);
        assertThat(service.recordFailure("alice")).isEqualTo(LoginAttemptService.MAX_FAILURES);
    }

    @Test
    @DisplayName("不同账号各数各的，不互相牵连")
    void accountsAreIsolated() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertThat(service.recordFailure("bob")).isEqualTo(1);
    }

    @Test
    @DisplayName("登录成功后清零：下次输错重新从 1 开始数")
    void clearResetsCounter() {
        service.recordFailure("carol");
        service.recordFailure("carol");
        service.clear("carol");
        assertThat(service.recordFailure("carol")).isEqualTo(1);
    }

    @Test
    @DisplayName("空账号不计数，也不抛异常")
    void blankAccountIsIgnored() {
        assertThat(service.recordFailure("  ")).isEqualTo(1);
        service.clear(null);
    }
}
