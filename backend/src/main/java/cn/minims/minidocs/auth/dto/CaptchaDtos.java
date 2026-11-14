package cn.minims.minidocs.auth.dto;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import jakarta.validation.constraints.NotBlank;

/**
 * 滑块验证码的请求 / 响应体。
 */
public final class CaptchaDtos {

    private CaptchaDtos() {
    }

    /**
     * 一张待拼的滑块图。
     *
     * <p>图片都是 {@code data:image/png;base64,…} 形式的字符串，前端直接塞进 {@code <img src>}，
     * 不必再为「取图」多开一个带鉴权的接口。</p>
     *
     * <p>{@code extra} 里带着 {@code y}：缺口所在的纵向位置。前端据此把拼图块初始摆到对应高度，
     * 用户只需要横向拖动 —— 与市面上常见的滑块一致。</p>
     */
    public record SliderVO(String id,
                           String backgroundImage,
                           String templateImage,
                           Integer backgroundWidth,
                           Integer backgroundHeight,
                           Integer templateWidth,
                           Integer templateHeight,
                           Object extra) {
    }

    /**
     * 提交滑动轨迹。
     *
     * <p>直接复用库的 {@link ImageCaptchaTrack}：字段（起止时间、图宽高、逐点坐标）
     * 是它的校验算法约定的，自己再抄一份只会随版本漂移。</p>
     */
    public record CheckRequest(@NotBlank(message = "验证码已失效，请刷新") String id,
                               ImageCaptchaTrack data) {
    }

    /**
     * 验证通过后的一次性票据。
     *
     * <p>之所以要有它：库的 {@code matching} 是「取出即销毁」，前端若先校验再登录，
     * 第二次校验同一个 id 必然失败。票据把「已通过」这件事接力到登录请求上，
     * 且用一次就作废 —— 拿到票据也换不来第二次试密码的机会。</p>
     */
    public record TicketVO(String ticket, long expiresIn) {
    }
}
