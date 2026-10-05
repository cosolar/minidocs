package cn.minims.minidocs.auth.interceptor;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /api/platform/** 平台角色校验（在 AuthInterceptor 之后执行）。
 *
 * <p>判的是 {@code User.role} 这一层的平台管理员，与任何组织的 OWNER/ADMIN 无关 ——
 * 组织内的裁决全部走 {@code AccessService} 的三轴，不经这里。</p>
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (!UserContext.require().isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
        return true;
    }
}
