package cn.minims.minidocs.auth.dto;

import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 认证模块 DTO 集合。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名或邮箱不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record LoginResponse(String token, long expiresIn, UserVO user, Map<String, Object> settings) {
    }

    public record AccountUpdateRequest(
            @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
            @Pattern(regexp = "^[A-Za-z0-9_]*$", message = "用户名只能包含字母、数字与下划线")
            String username,
            @Size(max = 64, message = "显示名长度不能超过 64") String displayName,
            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱长度不能超过 128") String email) {
    }

    public record PasswordUpdateRequest(
            @NotBlank(message = "原密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 64, message = "新密码长度需在 6-64 之间") String newPassword) {
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
            @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字与下划线") String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间") String password,
            @Size(max = 64, message = "显示名长度不能超过 64") String displayName,
            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱长度不能超过 128") String email,
            @Pattern(regexp = "^\\d{6}$", message = "验证码应为 6 位数字") String emailCode) {

        /** 兼容不带验证码的调用方：站点未开启邮箱验证时这条路径本来就不需要它。 */
        public RegisterRequest(String username, String password, String displayName, String email) {
            this(username, password, displayName, email, null);
        }
    }

    /** 获取注册验证码：公开接口，按邮箱与来源 IP 限速，见 {@code RegisterCodeService}。 */
    public record SendRegisterCodeRequest(
            @NotBlank(message = "请填写邮箱")
            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱长度不能超过 128") String email) {
    }

    /** 发码结果：前端据此起倒计时，邮件正文里的有效期也用它。 */
    public record RegisterCodeVO(int cooldownSeconds, int validMinutes) {
    }

    /**
     * 注册请求里的邮箱什么时候必填见 {@code EmailVerificationService#required()}：
     * 验证开关打开时它是硬性要求，没开时留空照旧放行 —— 校验注解做不到这种条件判断，
     * 所以必填由服务层判，这里只保证「填了就得是个像样的地址」。
     */
    public record VerifyEmailRequest(
            @NotBlank(message = "验证链接不完整") String token) {
    }

    /** 公开的重发入口：用户名或邮箱都行，判定与回复都不区分二者，理由见服务层。 */
    public record ResendVerificationRequest(
            @NotBlank(message = "请填写用户名或邮箱")
            @Size(max = 128, message = "长度不能超过 128") String account) {
    }

    /** 验证成功后回给落地页的信息：只有那个邮箱地址，让人确认验的是哪个。 */
    public record VerificationResultVO(String email) {
    }
}
