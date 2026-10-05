package cn.minims.minidocs.common.context;

import cn.minims.minidocs.user.entity.User;

/**
 * 当前登录用户（请求级）。
 */
public record LoginUser(Long id, String username, String displayName, String email, String role, String status) {

    public boolean isAdmin() {
        return User.ROLE_ADMIN.equalsIgnoreCase(role);
    }

    public String nickname() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }
}
