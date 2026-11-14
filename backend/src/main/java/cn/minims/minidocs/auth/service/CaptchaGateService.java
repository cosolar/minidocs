package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.CaptchaDtos.TicketVO;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「人机验证已通过」的一次性票据。
 *
 * <p>验证本身是「取图 → 点三下」的三次往返，而登录是另一次请求。这里在两者之间架一张短票据：</p>
 *
 * <pre>
 *   前端点选 → POST /api/captcha/pick（每点一下一次）→ 第三个命中 → 拿到 ticket
 *   前端点登录 → POST /api/auth/login（用户名 + 密码 + ticket）→ 后端消费 ticket
 * </pre>
 *
 * <p>票据有三个性质，缺一都会让这层防线形同虚设：</p>
 * <ul>
 *   <li><b>一次性</b>：{@code consume} 先移除再判断，无论登录成功还是密码错误都已作废，
 *       否则一次验证可以反复试密码；</li>
 *   <li><b>有时效</b>：默认 2 分钟，与验证图本身的过期时间对齐；</li>
 *   <li><b>不可猜</b>：{@link SecureRandom} 生成的 48 位十六进制串。</li>
 * </ul>
 *
 * <p>存在进程内 Map 里：本部署是单容器，重启后票据全丢（用户重滑一次即可），
 * 换来的是不引入 Redis 这类额外依赖。</p>
 */
@Service
public class CaptchaGateService {

    /** 票据有效期：从「点选通过」到「点下登录」的真实耗时，2 分钟足够，再久就是给爆破留窗口。 */
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final int TOKEN_BYTES = 24;

    private final Map<String, Long> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** 签发一张票据。 */
    public TicketVO issue() {
        prune();
        byte[] buffer = new byte[TOKEN_BYTES];
        random.nextBytes(buffer);
        String ticket = HexFormat.of().formatHex(buffer);
        tickets.put(ticket, System.currentTimeMillis() + TTL.toMillis());
        return new TicketVO(ticket, TTL.toSeconds());
    }

    /**
     * 消费一张票据：存在且未过期才放行，随后立刻作废。
     *
     * <p>无论后续登录成功与否，这张票据都不会再被接受第二次。</p>
     */
    public void consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw BizException.of(ErrorCode.CAPTCHA_INVALID);
        }
        Long expireAt = tickets.remove(ticket);
        if (expireAt == null || expireAt < System.currentTimeMillis()) {
            throw BizException.of(ErrorCode.CAPTCHA_INVALID, "人机验证已失效，请重新验证");
        }
    }

    /** 顺手清掉过期票据，避免长期运行的进程里攒一堆死记录。 */
    private void prune() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
