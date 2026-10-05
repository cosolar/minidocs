package cn.minims.minidocs.tenant.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.tenant.dto.TenantDtos.AddMemberRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.JoinRequestMessage;
import cn.minims.minidocs.tenant.dto.TenantDtos.JoinRequestVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.MemberVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.OrgVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.TransferOwnerRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateOrgRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateRoleRequest;
import cn.minims.minidocs.tenant.service.TenantMemberService;
import cn.minims.minidocs.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织上下文 {@code /api/console/{org}/**}。
 *
 * <p>{@code POST .../join-request} 是成员门的唯一例外（发起申请的人按定义还不是成员），
 * 它自己走「组织是否公开」那条判定，见 {@code TenantMemberServiceImpl.applyToJoin}（规范 §3.3 / §3.4）。</p>
 */
@Tag(name = "组织内")
@RestController
@RequestMapping("/api/console/{org}")
@RequiredArgsConstructor
public class OrgController {

    private final TenantService tenantService;
    private final TenantMemberService memberService;

    @Operation(summary = "组织详情 + 我的角色")
    @GetMapping
    public ApiResponse<OrgVO> detail(@PathVariable String org) {
        return ApiResponse.ok(tenantService.detail(org, UserContext.require()));
    }

    @Operation(summary = "改组织资料（OWNER）")
    @PutMapping
    public ApiResponse<OrgVO> update(@PathVariable String org, @Valid @RequestBody UpdateOrgRequest request) {
        return ApiResponse.ok(tenantService.update(org, request, UserContext.require()));
    }

    @Operation(summary = "删除组织（OWNER，且组织内已无知识库）")
    @DeleteMapping
    public ApiResponse<Void> delete(@PathVariable String org) {
        tenantService.delete(org, UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "成员列表")
    @GetMapping("/members")
    public ApiResponse<List<MemberVO>> members(@PathVariable String org) {
        return ApiResponse.ok(memberService.list(org, UserContext.require()));
    }

    @Operation(summary = "直接添加成员（跳过申请流）")
    @PostMapping("/members")
    public ApiResponse<MemberVO> addMember(@PathVariable String org,
                                          @Valid @RequestBody AddMemberRequest request) {
        return ApiResponse.ok(memberService.add(org, request, UserContext.require()));
    }

    @Operation(summary = "改成员角色（OWNER 任免 ADMIN）")
    @PutMapping("/members/{userId}/role")
    public ApiResponse<MemberVO> setRole(@PathVariable String org, @PathVariable Long userId,
                                         @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(memberService.setRole(org, userId, request, UserContext.require()));
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable String org, @PathVariable Long userId) {
        memberService.remove(org, userId, UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "转让 OWNER（仅现任 OWNER）")
    @PostMapping("/transfer-owner")
    public ApiResponse<Void> transfer(@PathVariable String org, @Valid @RequestBody TransferOwnerRequest request) {
        memberService.transferOwner(org, request.userId(), UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "退出组织")
    @PostMapping("/leave")
    public ApiResponse<Void> leave(@PathVariable String org) {
        memberService.leave(org, UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "提交入组申请（非成员也可，前提是组织公开）")
    @PostMapping("/join-request")
    public ApiResponse<Void> apply(@PathVariable String org,
                                   @RequestBody(required = false) JoinRequestMessage message) {
        memberService.applyToJoin(org, message, UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "待处理申请列表（OWNER / ADMIN）")
    @GetMapping("/join-requests")
    public ApiResponse<List<JoinRequestVO>> joinRequests(@PathVariable String org) {
        return ApiResponse.ok(memberService.listJoinRequests(org, UserContext.require()));
    }

    @Operation(summary = "批准入组")
    @PostMapping("/join-requests/{requestId}/approve")
    public ApiResponse<JoinRequestVO> approve(@PathVariable String org, @PathVariable Long requestId) {
        return ApiResponse.ok(memberService.reviewJoinRequest(org, requestId, true, UserContext.require()));
    }

    @Operation(summary = "拒绝入组")
    @PostMapping("/join-requests/{requestId}/reject")
    public ApiResponse<JoinRequestVO> reject(@PathVariable String org, @PathVariable Long requestId) {
        return ApiResponse.ok(memberService.reviewJoinRequest(org, requestId, false, UserContext.require()));
    }
}
