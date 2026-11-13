package cn.minims.minidocs.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 第三方应用访问令牌的 DTO 集合。
 */
public final class ApiTokenDtos {

    private ApiTokenDtos() {
    }

    /**
     * 签发请求。
     *
     * <p>{@code scopes} 取 {@code read}（只读）或 {@code write}（读写）；只读是默认档，
     * 因为第三方接入官方接口最常见的诉求就是「把数据取出来」。</p>
     *
     * <p>{@code expiresInDays} 为空表示长期有效；给了就按天算，到期后这枚令牌自动作废。</p>
     */
    public record CreateRequest(
            @NotBlank(message = "请填写应用名称")
            @Size(max = 64, message = "应用名称不能超过 64 个字")
            String name,
            @Size(max = 16, message = "权限取值不正确")
            String scopes,
            @Min(value = 1, message = "有效期至少 1 天")
            @Max(value = 3650, message = "有效期最长 10 年")
            Integer expiresInDays) {
    }

    /**
     * 令牌概要。
     *
     * <p>没有原文字段：服务端只存摘要，原文拿不回来，列表里只能给一段前缀用于辨认。</p>
     */
    public record TokenVO(
            Long id,
            String name,
            /** 展示用前缀，如 {@code mdt_a1b2c3d4} */
            String tokenPrefix,
            /** 逗号分隔的权限 */
            String scopes,
            /** 是否含写权限 */
            boolean writeable,
            /** active 生效中 / expired 已过期 / revoked 已吊销 */
            String status,
            LocalDateTime createdAt,
            LocalDateTime lastUsedAt,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt) {
    }

    /**
     * 签发结果。
     *
     * <p>{@code token} 是令牌原文，<b>只在这一次响应里出现</b>；关掉弹窗就再也看不到了。</p>
     */
    public record CreatedVO(String token, TokenVO item) {
    }
}
