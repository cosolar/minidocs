package cn.minims.minidocs.reader.support;

import cn.dev33.satoken.stp.StpUtil;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 页面（SSR）侧解析当前登录用户：不抛异常，未登录或状态异常返回 null。
 */
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

    private final UserService userService;

    public LoginUser resolve() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null) {
                return null;
            }
            long userId = Long.parseLong(String.valueOf(loginId));
            User user = userService.getById(userId);
            if (user == null || !user.isActive()) {
                return null;
            }
            return new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getEmail(), user.getRole(), user.getStatus());
        } catch (Exception e) {
            return null;
        }
    }
}
