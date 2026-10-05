package cn.minims.minidocs.tenant.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.tenant.dto.TenantDtos.MeVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.TenantBrief;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.SettingsService;
import cn.minims.minidocs.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织上下文：一次请求给齐应用外壳要装的东西（账号、可切换的组织、上次所在组织）。
 *
 * <p>与 {@code GET /api/auth/me} 的分工是故意的 —— 那边只管登录态，这里管顶栏渲染。</p>
 */
@Tag(name = "组织上下文")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    /** {@code user_settings} 里记「上次所在组织」的键，写入走 PUT /api/settings 的浅合并。 */
    public static final String LAST_TENANT_KEY = "lastTenantSlug";

    private final UserService userService;
    private final TenantService tenantService;
    private final SettingsService settingsService;

    @Operation(summary = "当前用户 + 我的组织 + 上次所在组织")
    @GetMapping
    public ApiResponse<MeVO> me() {
        LoginUser viewer = UserContext.require();
        User user = userService.getById(viewer.id());
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        List<TenantBrief> tenants = tenantService.myTenants(viewer);
        return ApiResponse.ok(new MeVO(UserVO.from(user), tenants, lastTenantSlug(viewer.id(), tenants)));
    }

    /**
     * 上次所在组织只在「仍然是我的组织」时才回给前端。
     *
     * <p>成员身份会变，存下的 slug 可能已经指向一个点进去 404 的组织；这里过滤掉，
     * 前端就能把 null 当成「回落到第一个组织」的信号。</p>
     */
    private String lastTenantSlug(Long userId, List<TenantBrief> tenants) {
        Object stored = settingsService.getSettings(userId).get(LAST_TENANT_KEY);
        if (!(stored instanceof String slug) || slug.isBlank()) {
            return null;
        }
        return tenants.stream().anyMatch(brief -> slug.equalsIgnoreCase(brief.slug())) ? slug : null;
    }
}
