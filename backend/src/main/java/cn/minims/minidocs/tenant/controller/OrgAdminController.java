package cn.minims.minidocs.tenant.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.tenant.dto.TenantDtos.AdminOrgVO;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.service.OrgAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧组织治理：组织列表与停用 / 恢复（规范 §4.4）。
 *
 * <p>门槛与 {@code /api/platform/users} 同一条（{@code AdminInterceptor} 判平台角色），
 * 因此这里不出现任何 {@code AccessService} 调用 —— 停用组织不是组织内的治理动作，
 * 让它过 {@code requireTenant} 反而会把「组织 OWNER 能停用自己」这条并不存在的权利做出来。</p>
 */
@Tag(name = "组织管理（平台）")
@RestController
@RequestMapping("/api/platform/orgs")
@RequiredArgsConstructor
public class OrgAdminController {

    private final OrgAdminService orgAdminService;

    @Operation(summary = "组织列表")
    @GetMapping
    public ApiResponse<PageResult<AdminOrgVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String status) {
        return ApiResponse.ok(orgAdminService.page(keyword, type, status, page, size));
    }

    @Operation(summary = "停用组织（内容整体下线，磁盘不动）")
    @PostMapping("/{slug}/disable")
    public ApiResponse<AdminOrgVO> disable(@PathVariable String slug) {
        return ApiResponse.ok(orgAdminService.changeStatus(slug, Tenant.STATUS_DISABLED, UserContext.require()));
    }

    @Operation(summary = "恢复组织")
    @PostMapping("/{slug}/enable")
    public ApiResponse<AdminOrgVO> enable(@PathVariable String slug) {
        return ApiResponse.ok(orgAdminService.changeStatus(slug, Tenant.STATUS_ACTIVE, UserContext.require()));
    }
}
