package cn.minims.minidocs.auth.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /api/** 鉴权拦截器：校验 JWT 登录态、加载用户并检查账号状态。
 *
 * <p>采用无状态 JWT，因此每次请求都会回源加载用户，保证「禁用后下次请求即失效」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            // 路径没落到任何接口方法上（打错的 /api/xxx）：放行给资源处理器出 404，
            // 在这里抛鉴权异常会以「没有 controller」的身份逃过 @RestControllerAdvice，变成 500。
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Object loginId;
        try {
            loginId = StpUtil.getLoginIdDefaultNull();
        } catch (Exception e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (loginId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        long userId;
        try {
            userId = Long.parseLong(String.valueOf(loginId));
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (!User.STATUS_ACTIVE.equals(user.getStatus())) {
            throw BizException.of(ErrorCode.ACCOUNT_DISABLED);
        }
        UserContext.set(new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getRole(), user.getStatus()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        UserContext.clear();
    }
}
