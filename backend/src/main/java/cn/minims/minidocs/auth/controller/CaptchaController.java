package cn.minims.minidocs.auth.controller;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cn.minims.minidocs.auth.dto.CaptchaDtos.CheckRequest;
import cn.minims.minidocs.auth.dto.CaptchaDtos.SliderVO;
import cn.minims.minidocs.auth.dto.CaptchaDtos.TicketVO;
import cn.minims.minidocs.auth.service.CaptchaGateService;
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
 * 登录滑块验证码。
 *
 * <p>两步交互，故意不在登录接口里塞图片：</p>
 * <ol>
 *   <li>{@code GET /api/captcha/slider} —— 取一张底图 + 拼图块；</li>
 *   <li>{@code POST /api/captcha/check} —— 交轨迹，通过则换一张一次性票据；</li>
 *   <li>登录时把票据带上，后端消费掉（见 {@link CaptchaGateService}）。</li>
 * </ol>
 *
 * <p>这两个接口对未登录者开放（拦截器白名单），所以各自带一层按 IP 的限流：
 * 前者防「把图刷爆内存」，后者防「拿轨迹硬撞」。限流比登录本身宽一档 ——
 * 正常用户可能滑歪两三次，不该因此被锁。</p>
 */
@Tag(name = "验证码")
@Slf4j
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    /** 取图：每 IP 每分钟 40 次。一次登录正常用 1~3 次，40 足够宽容，又不至于被拿来刷图。 */
    private static final int GEN_LIMIT = 40;
    /** 交轨迹：每 IP 每分钟 30 次。比取图略紧，因为一次轨迹就是一次瞄准答案的尝试。 */
    private static final int CHECK_LIMIT = 30;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final ImageCaptchaApplication captchaApplication;
    private final CaptchaGateService captchaGateService;
    private final RateLimiter rateLimiter;

    @Operation(summary = "取一张滑块验证码")
    @GetMapping("/slider")
    public ApiResponse<SliderVO> slider(HttpServletRequest request) {
        String ip = AccessLogFilter.clientIp(request);
        if (!rateLimiter.tryAcquire("captcha:gen:" + ip, GEN_LIMIT, WINDOW)) {
            throw BizException.of(ErrorCode.CAPTCHA_TOO_FREQUENT);
        }
        cloud.tianai.captcha.common.response.ApiResponse<ImageCaptchaVO> response =
                captchaApplication.generateCaptcha(CaptchaTypeConstant.SLIDER);
        if (!response.isSuccess() || response.getData() == null) {
            log.warn("验证码生成失败：{}", response.getMsg());
            throw BizException.of(ErrorCode.SERVER_ERROR, "验证码生成失败，请刷新重试");
        }
        ImageCaptchaVO vo = response.getData();
        return ApiResponse.ok(new SliderVO(vo.getId(), vo.getBackgroundImage(), vo.getTemplateImage(),
                vo.getBackgroundImageWidth(), vo.getBackgroundImageHeight(),
                vo.getTemplateImageWidth(), vo.getTemplateImageHeight(), vo.getData()));
    }

    @Operation(summary = "校验滑动轨迹，换一次性票据")
    @PostMapping("/check")
    public ApiResponse<TicketVO> check(@Valid @RequestBody CheckRequest body, HttpServletRequest request) {
        String ip = AccessLogFilter.clientIp(request);
        if (!rateLimiter.tryAcquire("captcha:check:" + ip, CHECK_LIMIT, WINDOW)) {
            throw BizException.of(ErrorCode.CAPTCHA_TOO_FREQUENT);
        }
        cloud.tianai.captcha.common.response.ApiResponse<?> match =
                captchaApplication.matching(body.id(), body.data());
        if (!match.isSuccess()) {
            // 只进日志、不回前端：库内部的判定细节（差了几像素、轨迹像不像人）
            // 恰好是脚本调参最想要的信息
            log.info("验证码校验未通过 code={} msg={}", match.getCode(), match.getMsg());
            throw BizException.of(ErrorCode.CAPTCHA_INVALID, "拼图没对上，再来一次");
        }
        return ApiResponse.ok(captchaGateService.issue());
    }
}
