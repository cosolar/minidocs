package cn.minims.minidocs.tenant.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.tenant.dto.TenantDtos.CreateOrgRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.DiscoverVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.OrgVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.TenantBrief;
import cn.minims.minidocs.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "组织")
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @Operation(summary = "创建团队组织（创建者成为 OWNER）")
    @PostMapping
    public ApiResponse<OrgVO> create(@Valid @RequestBody CreateOrgRequest request) {
        return ApiResponse.ok(tenantService.createTeam(request, UserContext.require()));
    }

    @Operation(summary = "组织发现列表（仅可发现的活跃团队组织）")
    @GetMapping("/discover")
    public ApiResponse<PageResult<DiscoverVO>> discover(@RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(tenantService.discover(page, size));
    }

    @Operation(summary = "我参与的组织（顶栏切换器）")
    @GetMapping("/mine")
    public ApiResponse<List<TenantBrief>> mine() {
        return ApiResponse.ok(tenantService.myTenants(UserContext.require()));
    }
}
