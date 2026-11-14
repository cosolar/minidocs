package cn.minims.minidocs.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 人机验证（点选图形）的请求 / 响应体。
 */
public final class CaptchaDtos {

    private CaptchaDtos() {
    }

    /**
     * 一张待点选的验证图。
     *
     * <p>{@code image} 是 {@code data:image/png;base64,…}，前端直接塞进 {@code <img src>}，
     * 不必为「取图」再开一个带鉴权的接口。</p>
     *
     * <p><b>这里只给目标图形的形状与颜色，不给坐标。</b> 坐标一旦下发，脚本读一下状态
     * 就能算出该点哪里，这层校验等于没有；所以点击位置要提交给后端判定，
     * 前端只负责把提示画成图标。</p>
     */
    public record ChallengeVO(String id,
                              String image,
                              int width,
                              int height,
                              List<TargetVO> targets) {
    }

    /**
     * 提示里要点的图形。
     *
     * @param shape 图形种类，对应前端 SVG 图标：circle / triangle / square / diamond / star / hexagon
     * @param color 十六进制颜色，如 {@code #0B5C4F}
     */
    public record TargetVO(String shape, String color) {
    }

    /**
     * 提交一次点击。
     *
     * <p>坐标是<b>原图坐标</b>（{@code width} × {@code height} 那套），
     * 前端按图片实际显示宽度换算后再提交。</p>
     */
    public record PickRequest(@NotBlank(message = "验证码已失效，请刷新") String id,
                              @NotNull(message = "点击位置缺失") Double x,
                              @NotNull(message = "点击位置缺失") Double y) {
    }

    /**
     * 一次点击的结果。
     *
     * @param progress 已点对几个（0 表示没点中）
     * @param hit      点中的那一个图形的位置；没点中时为 {@code null}。前端据此在图上画选中环
     * @param ticket   第三个点对时给出的登录票据，其余情况为 {@code null}
     */
    public record PickVO(int progress, HitVO hit, TicketVO ticket) {
    }

    /** 点中的图形位置，保留原图坐标，前端自己缩放。 */
    public record HitVO(double x, double y) {
    }

    /**
     * 验证通过后的一次性票据。
     *
     * <p>把「已通过」这件事接力到登录请求上，且用一次就作废 ——
     * 拿到票据也换不来第二次试密码的机会。</p>
     */
    public record TicketVO(String ticket, long expiresIn) {
    }
}
