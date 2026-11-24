package cn.minims.minidocs.auth.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码失败的连续计数：连续输错达到阈值后，登录页切到邮箱验证码。
 *
 * <p>它只负责「界面该不该换一种登录方式」这一件事，<b>不是</b>安全防线：
 * 计数器放在内存里，进程重启即清空，按账号字符串计而不是按人计。
 * 真正挡住撞库的是 {@code RateLimiter}（同一账号 60 秒内最多 5 次）与
 * 邮箱验证码自身的「10 分钟 + 5 次尝试」。</p>
 *
 * <p>因此这里的取舍很直接：宁可重启后重新数三次，也不要为了持久化在登录主链路上
 * 多写一张表、多一次磁盘 I/O。</p>
 */
@Service
public class LoginAttemptService {

    /** 连续失败到这个次数，就建议改用邮箱验证码 */
    public static final int MAX_FAILURES = 3;

    /** 计数窗口：超过这段时间没有新的失败，旧计数作废，从 0 重新数 */
    private static final Duration WINDOW = Duration.ofMinutes(30);

    /** 防止被随机账号名撑爆内存：超过这个量级就清理一遍过期项 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 记一次密码失败，返回该账号在窗口内的连续失败次数。
     *
     * <p>调用点只在「用户名或密码不正确」这一种分支：账号停用、邮箱未验证都不算输错密码，
     * 不该把人往验证码登录上赶。</p>
     */
    public int recordFailure(String account) {
        if (account == null || account.isBlank()) {
            return 1;
        }
        Instant now = Instant.now();
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.entrySet().removeIf(entry -> entry.getValue().lastFailureAt().plus(WINDOW).isBefore(now));
        }
        Attempt updated = attempts.compute(account, (key, current) -> {
            if (current == null || current.lastFailureAt().plus(WINDOW).isBefore(now)) {
                return new Attempt(1, now);
            }
            return new Attempt(current.count() + 1, now);
        });
        return updated.count();
    }

    /** 登录成功或重设密码后清零：下一次输错重新从 1 开始数。 */
    public void clear(String account) {
        if (account != null && !account.isBlank()) {
            attempts.remove(account);
        }
    }

    private record Attempt(int count, Instant lastFailureAt) {
    }
}
