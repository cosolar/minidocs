package cn.minims.minidocs.audit.controller;

import cn.minims.minidocs.audit.dto.AuditDtos.AuditVO;
import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 审计读取（规范 §9 / F9）：只开放本组织，跨组织汇总留给 {@code /api/platform/**}。
 */
@Tag(name = "审计日志")
@RestController
@RequestMapping("/api/console/{org}/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;
    private final TenantService tenantService;
    private final AccessService accessService;

    @Operation(summary = "本组织审计流（OWNER / ADMIN，新的在前，可按动作/操作者/日期区间筛）")
    @GetMapping
    public ApiResponse<PageResult<AuditVO>> page(@PathVariable String org,
                                                 @RequestParam(required = false) String action,
                                                 @RequestParam(required = false) Long actor,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        LoginUser actorUser = UserContext.require();
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(org), KbAction.AUDIT_READ, actorUser);
        // 日期按整天算：from 含当天零点，to 取「结束日的次日零点」做开区间上界，
        // 否则按日期筛时结束那天只有零点到查询时刻的记录能出来，看着就像漏了一批
        LocalDate fromDate = TimeUtil.parseDate(from);
        LocalDate toDate = TimeUtil.parseDate(to);
        return ApiResponse.ok(auditService.page(tenant.getId(), action, actor,
                fromDate == null ? null : fromDate.atStartOfDay(),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay(),
                page, size));
    }
}
