package cn.minims.minidocs.tenant.interceptor;

import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /api/console/{org}/**} 的组织上下文解析与成员门（规范 §3.4 / §2.5）。
 *
 * <p>放在拦截器而不是各个 controller 里，是为了让「新增一个组织内接口却忘了判成员」这种
 * 失误不可能静默发生：路径前缀即门，controller 只管动作级判定。</p>
 *
 * <p>唯一例外是 {@code POST /api/console/{org}/join-request} —— 发起申请的人按定义还不是成员，
 * 它在 {@code TenantMemberServiceImpl.applyToJoin} 里走「组织是否公开」那条判定，
 * 由 {@link #MEMBER_GATE_EXEMPTION} 在 {@code WebMvcConfig} 放行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private static final String ORG_PREFIX = "/api/console/";

    /**
     * 成员门的唯一豁免路径，由 {@code WebMvcConfig} 注册成 excludePathPatterns。
     *
     * <p>写成常量而不是在配置里抄一遍字符串：这条豁免是权限模型上的一处洞（申请人还不是成员），
     * 它必须和拦截器放在一起读得到，而且只能盖住 {@code join-request} 这一条 ——
     * {@code /api/console/*}{@code /join-requests}（申请列表，ADMIN 以上）不在豁免范围内。</p>
     */
    public static final String MEMBER_GATE_EXEMPTION = "/api/console/*/join-request";

    private final TenantService tenantService;
    private final AccessService accessService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Tenant tenant = tenantService.requireBySlug(orgSlug(request));
        accessService.requireMember(tenant, UserContext.require());
        TenantContext.set(tenant);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        TenantContext.clear();
    }

    /**
     * 取路径里的第三段（{@code /api/console/} 之后到下一个 {@code /}）作为组织 slug。
     *
     * <p>必须自己解码：{@code getRequestURI()} 给的是原始字节串，中文组织名（本项目最常见的 slug）
     * 在浏览器里是百分号形式，不解码就查不到组织 —— 一个真实存在的组织会被门成 404，
     * 而 controller 侧的 {@code @PathVariable} 是解码过的，两边口径不一致最难查。</p>
     */
    private static String orgSlug(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!path.startsWith(ORG_PREFIX)) {
            throw BizException.notFound("组织不存在");
        }
        int end = path.indexOf('/', ORG_PREFIX.length());
        String slug = end == -1 ? path.substring(ORG_PREFIX.length()) : path.substring(ORG_PREFIX.length(), end);
        if (slug.isBlank()) {
            throw BizException.notFound("组织不存在");
        }
        // slug 的合法字符集里没有 '+'（见 SlugUtil.isAvailableOrgSlug），因此 URLDecoder 的
        // 「+ 当作空格」这条表单编码规则在这里不会误伤路径；残缺的转义会原样落回查库，
        // 查不到就是 404 —— 与「组织不存在」同形，符合 §2.5。
        return PathEncoder.decode(slug);
    }
}
