package cn.minims.minidocs.auth.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import cn.minims.minidocs.auth.entity.ApiToken;
import cn.minims.minidocs.auth.service.ApiTokenService;
import cn.minims.minidocs.auth.support.ApiTokenContext;
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

import java.util.List;
import java.util.Locale;

/**
 * /api/** 鉴权拦截器：校验登录态、加载用户并检查账号状态。
 *
 * <p>登录态有两种走法：</p>
 * <ul>
 *   <li><b>登录 JWT</b>（无状态）—— 每次请求都回源加载用户，保证「禁用后下次请求即失效」；</li>
 *   <li><b>访问令牌</b>（{@code mdt_} 前缀）—— 第三方应用用的长期令牌，换来的是同一个人的身份。</li>
 * </ul>
 *
 * <p>两者在拦截器里合流：填好 {@code UserContext} 之后，下游的服务层完全看不出区别。
 * 但令牌多两条限制：只读令牌只能读，且<b>不能</b>碰令牌管理与账号安全这几条路 ——
 * 否则一枚被复制出去的令牌就能自己增发令牌，或改掉账号密码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 令牌认证时禁止访问的路径前缀：令牌管理 + 账号安全。 */
    private static final List<String> TOKEN_DENIED_PATHS = List.of(
            "/api/tokens",
            "/api/auth/password",
            "/api/auth/account",
            "/api/auth/logout",
            "/api/auth/verification/resend");

    private final UserService userService;
    private final ApiTokenService apiTokenService;

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

        String rawToken = extractApiToken(request);
        if (rawToken != null) {
            return authenticateByApiToken(request, rawToken);
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

    /**
     * 令牌认证：换身份、填上下文，再按权限收口。
     *
     * <p>令牌换取的就是所有者本人，所以这里的判定与登录态完全一致 ——
     * 账号被禁用、令牌被吊销、令牌过期，任何一条都当场失效。</p>
     */
    private boolean authenticateByApiToken(HttpServletRequest request, String rawToken) {
        ApiTokenService.Authenticated authenticated = apiTokenService.authenticate(rawToken);
        User user = authenticated.user();
        if (!User.STATUS_ACTIVE.equals(user.getStatus())) {
            throw BizException.of(ErrorCode.ACCOUNT_DISABLED);
        }
        ApiTokenContext.set(new ApiTokenContext.Principal(authenticated.tokenId(), authenticated.scopes()));
        UserContext.set(new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getRole(), user.getStatus()));
        checkApiTokenPermission(request);
        return true;
    }

    /**
     * 令牌的权限收口。
     *
     * <p>两条规矩：</p>
     * <ol>
     *   <li>令牌不能管理令牌、不能改账号密码这类「会话内」的事 —— 那不是 API 的活；</li>
     *   <li>只读令牌只能读：写操作一律拒绝，避免一枚「只用来取数据」的令牌被拿去改数据。</li>
     * </ol>
     */
    private void checkApiTokenPermission(HttpServletRequest request) {
        String path = apiPath(request);
        for (String denied : TOKEN_DENIED_PATHS) {
            if (path.equals(denied) || path.startsWith(denied + "/")) {
                throw BizException.forbidden("访问令牌不能用于该操作");
            }
        }
        String method = request.getMethod();
        boolean readOnly = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method);
        if (!readOnly) {
            ApiTokenContext.Principal principal = ApiTokenContext.get();
            if (principal == null || !principal.hasWrite()) {
                throw BizException.forbidden("该访问令牌只有只读权限");
            }
        }
    }

    /**
     * 从请求头里取出访问令牌原文。
     *
     * <p>形如 {@code Authorization: Bearer mdt_xxxx}。登录 JWT 不带这个前缀，
     * 因此两者不会撞车；返回 null 表示「这次请求不是令牌认证」。</p>
     */
    private static String extractApiToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            value = value.substring(7).trim();
        }
        if (value.startsWith(ApiToken.TOKEN_PREFIX)) {
            return value;
        }
        return null;
    }

    /** 去掉 context-path 的接口路径（拦截器的路径规则都是相对 context-path 写的）。 */
    private static String apiPath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
        }
        return path;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        UserContext.clear();
        ApiTokenContext.clear();
    }
}
