package cn.minims.minidocs.auth.support;

import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.AsyncConfig;
import cn.minims.minidocs.mail.MailService;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 在后台把验证邮件发出去。
 *
 * <p>注册接口不该等一趟 SMTP：连接一台慢服务器要几十秒，而这段时间里注册用户什么都做不了
 * （连接与读写超时见 {@link MailService}）。更糟的是失败该怎么算 —— 抛回注册接口，人就卡在
 * 注册页上一个跟自己无关的错误；所以状态回落在下面兜住。</p>
 *
 * <p><b>发信失败 = 放行</b>：把账号标成已验证并清掉待用令牌。理由和 SMTP 没配置时一样 ——
 * 「防乱注册」实现成「收不到邮件的人永远进不来」就本末倒置了，而这封邮件失败的原因
 * 十有八九是管理员那边的问题（域名解析、端口、口令），不该由注册的人承担。</p>
 *
 * <p>{@code void} 异步方法的异常不会回到调用方，所以这里必须自己兜住全部异常：
 * 让它逃出去只会落进线程池的默认处理器，界面上连一声响都听不到。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationMailWorker {

    private final MailService mailService;
    private final UserService userService;

    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void sendAsync(PendingMail pending) {
        try {
            mailService.sendVerification(pending.settings(), pending.to(),
                    pending.displayName(), pending.link(), pending.validHours(), pending.baseUrl());
        } catch (Exception e) {
            log.warn("验证邮件发送失败，账号按已验证放行 id={} to={}", pending.userId(), pending.to(), e);
            release(pending.userId());
        }
    }

    /**
     * 回落：解除闸门。
     *
     * <p>{@code updateById} 会跳过 null，而这里要写的正是 null（令牌用完就该没了），
     * 所以逐列显式给出 —— 与 {@code AuthServiceImpl.persistAccount} 同一个理由。</p>
     */
    private void release(long userId) {
        userService.update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getEmailVerified, true)
                .set(User::getVerifyToken, null)
                .set(User::getVerifyExpiresAt, null)
                .set(User::getUpdatedAt, TimeUtil.now()));
    }

    /**
     * 一封还没发出去的验证邮件。
     *
     * <p>收件地址、链接与 SMTP 配置都在任务里带齐：异步线程不回库重读用户行，
     * 免得有人在「排队」和「投递」之间改了邮箱，把验证链接发到旧地址上还算新地址已验证。</p>
     */
    public record PendingMail(long userId, String to, String displayName, String link,
                              String baseUrl, MailSettings settings, int validHours) {
    }
}