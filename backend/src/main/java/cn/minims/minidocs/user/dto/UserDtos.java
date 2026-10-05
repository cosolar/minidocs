package cn.minims.minidocs.user.dto;

import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 用户模块 DTO 集合。
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** 对外用户信息。 */
    public record UserVO(Long id, String username, String displayName, String email,
                         String role, String status, String avatarUrl, String avatarSrc, LocalDateTime createdAt) {

        /** 头像接口在上下文内的路径，对外地址要再补上上下文前缀（见 {@link AppPaths}） */
        public static final String AVATAR_PREFIX = "/api/avatars/";

        public static UserVO from(User user) {
            String avatarUrl = user.getAvatarUrl();
            // 库里存的是文件名，对外给拼好的地址；没有头像时两者都为 null，前端回退首字母
            String avatarSrc = avatarUrl == null || avatarUrl.isBlank() ? null
                    : AppPaths.of(AVATAR_PREFIX + avatarUrl);
            return new UserVO(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                    user.getRole(), user.getStatus(), avatarUrl, avatarSrc, user.getCreatedAt());
        }
    }

    /** 管理员侧用户列表项。 */
    public record AdminUserVO(Long id, String username, String displayName, String email,
                              String role, String status, long kbCount, LocalDateTime createdAt) {
    }

    /** 管理员修改用户资料。 */
    public record AdminUpdateRequest(
            @Size(max = 64, message = "长度不能超过 64") String displayName,
            @Email(message = "邮箱格式不正确") @Size(max = 128, message = "长度不能超过 128") String email,
            @Pattern(regexp = "ADMIN|USER", message = "角色只能是 ADMIN 或 USER") String role) {
    }

    /** 管理员重置密码。 */
    public record ResetPasswordRequest(
            @NotBlank(message = "不能为空")
            @Size(min = 6, max = 64, message = "长度需在 6-64 之间") String newPassword) {
    }
}
