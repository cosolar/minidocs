package cn.minims.minidocs.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器。
 *
 * <p>目前只有一处用到：{@code GitCloneWorker} 的建库后台克隆。之所以要独立一个池而不用
 * {@code SimpleAsyncTaskExecutor}（默认行为）——后者每次调用新建一个线程且<b>不限制并发</b>，
 * 连续建几个云端库就会拉起一排 120 秒起步的克隆线程，JVM 的线程和内存都会被拖住。</p>
 *
 * <p>池的规模按「克隆是低频重 IO 操作」定：核心 2、最大 4，队列 100。
 * 超出队列用 {@link ThreadPoolExecutor.AbortPolicy} 拒绝<b>而不是</b> CallerRuns——
 * 后者会让任务退回调用方线程执行，而调用方是「事务提交后回调」所在的 HTTP 线程，
 * 那就等于绕回原地：用户又得盯着转圈直到超时。</p>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** 建库后台克隆用的执行器名。单独命名是为了在 {@code jstack} / 监控里一眼认出这类线程。 */
    public static final String GIT_EXECUTOR = "minidocsGitExecutor";

    @Bean(name = GIT_EXECUTOR)
    public ThreadPoolTaskExecutor gitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("minidocs-git-");
        // 拒绝而非退回调用方：理由见类注释
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // 进程重启时尚未完成的克隆就此放弃：库记录已提交，用户重试「拉取」即可，
        // 让它等完反而会拖住优雅停机
        executor.setAwaitTerminationSeconds(0);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return gitExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // void 异步方法抛出的异常不会传回调用方，默认处理器只丢进日志；
        // 这里保留堆栈，便于排查「库一直显示拉取中」这类问题
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
