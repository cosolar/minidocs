package cn.minims.minidocs.user.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.user.dto.UserDtos.AdminUpdateRequest;
import cn.minims.minidocs.user.dto.UserDtos.AdminUserVO;
import cn.minims.minidocs.user.dto.UserDtos.ResetPasswordRequest;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserAdminService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧用户治理：不分组织，管的是账号本身（审核、启停、重置密码、删除）。
 *
 * <p>挂在 {@code /api/platform/**} 而不是 {@code /api/admin/**}：v2 里「管理」这个词已经被
 * 组织管理台（{@code /console/{org}/**}）占了，两者不是一回事 —— 这里的门槛是平台角色
 * （{@code LoginUser.isAdmin()}），不是任何组织的 OWNER/ADMIN。拦截器见
 * {@code WebMvcConfig} 对 {@code /api/platform/**} 的注册。</p>
 */
@Tag(name = "用户管理（平台）")
@RestController
@RequestMapping("/api/platform/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    @Operation(summary = "用户列表")
    @GetMapping
    public ApiResponse<PageResult<AdminUserVO>> page(@RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long size,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String status) {
        return ApiResponse.ok(userAdminService.page(keyword, status, page, size));
    }

    @Operation(summary = "修改用户资料")
    @PutMapping("/{id}")
    public ApiResponse<AdminUserVO> update(@PathVariable Long id, @Valid @RequestBody AdminUpdateRequest request) {
        return ApiResponse.ok(userAdminService.update(id, request));
    }

    @Operation(summary = "审核通过")
    @PostMapping("/{id}/approve")
    public ApiResponse<AdminUserVO> approve(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.changeStatus(id, User.STATUS_ACTIVE));
    }

    @Operation(summary = "审核拒绝")
    @PostMapping("/{id}/reject")
    public ApiResponse<AdminUserVO> reject(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.changeStatus(id, User.STATUS_REJECTED));
    }

    @Operation(summary = "置回待审核")
    @PostMapping("/{id}/pending")
    public ApiResponse<AdminUserVO> pending(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.changeStatus(id, User.STATUS_PENDING));
    }

    @Operation(summary = "禁用")
    @PostMapping("/{id}/disable")
    public ApiResponse<AdminUserVO> disable(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.changeStatus(id, User.STATUS_DISABLED));
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    public ApiResponse<AdminUserVO> enable(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.changeStatus(id, User.STATUS_ACTIVE));
    }

    @Operation(summary = "重置密码")
    @PostMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                          @Valid @RequestBody ResetPasswordRequest request) {
        userAdminService.resetPassword(id, request.newPassword());
        return ApiResponse.ok();
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userAdminService.delete(id);
        return ApiResponse.ok();
    }
}
