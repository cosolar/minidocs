package cn.minims.minidocs.common.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内固定窗口限速（Caffeine 实现，不依赖 Redis）。
 *
 * <p>进程重启会清空计数窗口，属可接受行为。</p>
 */
@Component
public class RateLimiter {

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private final Cache<String, Long> windowStart = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    /**
     * 尝试获取一次配额。
     *
     * @param key    业务键，如 login:admin
     * @param limit  窗口内最大次数
     * @param window 窗口长度
     * @return true 表示允许；false 表示超限
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        Long start = windowStart.get(key, k -> now);
        if (start == null || now - start >= window.toMillis()) {
            windowStart.put(key, now);
            counters.put(key, new AtomicInteger(0));
        }
        AtomicInteger counter = counters.get(key, k -> new AtomicInteger(0));
        return counter.incrementAndGet() <= limit;
    }

    /** 成功后清除计数（如登录成功）。 */
    public void reset(String key) {
        counters.invalidate(key);
        windowStart.invalidate(key);
    }

    /** 返回剩余可尝试次数（不小于 0）。 */
    public int remaining(String key, int limit) {
        AtomicInteger counter = counters.getIfPresent(key);
        if (counter == null) {
            return limit;
        }
        return Math.max(0, limit - counter.get());
    }
}
