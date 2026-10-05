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
            @Email(message = "邮箱格式不正确") String email) {
    }
}
