package cn.minims.minidocs.auth.controller;

import cn.minims.minidocs.auth.dto.CaptchaDtos.ChallengeVO;
import cn.minims.minidocs.auth.dto.CaptchaDtos.HitVO;
import cn.minims.minidocs.auth.dto.CaptchaDtos.PickRequest;
import cn.minims.minidocs.auth.dto.CaptchaDtos.PickVO;
import cn.minims.minidocs.auth.service.CaptchaGateService;
import cn.minims.minidocs.auth.service.GraphCaptchaService;
import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.web.AccessLogFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 登录人机验证（依次点选图形）。
 *
 * <p>三步交互，故意不把图塞进登录接口：</p>
 * <ol>
 *   <li>{@code GET /api/captcha/challenge} —— 取一张验证图与「要点哪三个图形」的提示；</li>
 *   <li>{@code POST /api/captcha/pick} —— 每点一下提交一次，命中即推进，第三个命中换票据；</li>
 *   <li>登录时把票据带上，后端消费掉（见 {@link CaptchaGateService}）。</li>
 * </ol>
 *
 * <p>这两个接口对未登录者开放（拦截器白名单），所以各自带一层按 IP 的限流：
 * 前者防「把图刷爆内存」，后者防「拿坐标硬撞」。限流比登录本身宽一档 ——
 * 正常用户可能点歪一两次，不该因此被锁。</p>
 */
@Tag(name = "验证码")
@Slf4j
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    /** 取图：每 IP 每分钟 40 次。一次登录正常用 1~3 次，40 足够宽容，又不至于被拿来刷图。 */
    private static final int GEN_LIMIT = 40;
    /** 点击判定：每 IP 每分钟 30 次。一次登录成功只花 3 次，点歪会重新取图，30 有富余。 */
    private static final int PICK_LIMIT = 30;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final GraphCaptchaService graphCaptchaService;
    private final CaptchaGateService captchaGateService;
    private final RateLimiter rateLimiter;

    @Operation(summary = "取一张人机验证图")
    @GetMapping("/challenge")
    public ApiResponse<ChallengeVO> challenge(HttpServletRequest request) {
        String ip = AccessLogFilter.clientIp(request);
        if (!rateLimiter.tryAcquire("captcha:gen:" + ip, GEN_LIMIT, WINDOW)) {
            throw BizException.of(ErrorCode.CAPTCHA_TOO_FREQUENT);
        }
        return ApiResponse.ok(graphCaptchaService.issue());
    }

    @Operation(summary = "提交一次点击，全部点对后换一次性票据")
    @PostMapping("/pick")
    public ApiResponse<PickVO> pick(@Valid @RequestBody PickRequest body, HttpServletRequest request) {
        String ip = AccessLogFilter.clientIp(request);
        if (!rateLimiter.tryAcquire("captcha:pick:" + ip, PICK_LIMIT, WINDOW)) {
            throw BizException.of(ErrorCode.CAPTCHA_TOO_FREQUENT);
        }
        GraphCaptchaService.Outcome outcome = graphCaptchaService.pick(body);
        if (!outcome.accepted()) {
            // 具体差了多少像素只进 debug 日志，不回前端：那是脚本调参最想要的信息
            throw BizException.of(ErrorCode.CAPTCHA_INVALID, "没点中，换一张再来");
        }
        HitVO hit = new HitVO(outcome.hitX(), outcome.hitY());
        return ApiResponse.ok(new PickVO(outcome.progress(), hit,
                outcome.done() ? captchaGateService.issue() : null));
    }
}
