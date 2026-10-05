package cn.minims.minidocs.auth.controller;

import cn.minims.minidocs.auth.dto.AuthDtos.AccountUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginResponse;
import cn.minims.minidocs.auth.dto.AuthDtos.PasswordUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.auth.support.TokenCookieSupport;
import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.web.AccessLogFilter;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证与账号")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenCookieSupport tokenCookieSupport;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest servletRequest,
                                           HttpServletResponse servletResponse) {
        LoginResponse response = authService.login(request, AccessLogFilter.clientIp(servletRequest));
        tokenCookieSupport.write(servletResponse, servletRequest, response.token());
        return ApiResponse.ok(response);
    }

    @Operation(summary = "注册（默认开启，REGISTER_ENABLED=false 时关闭）")
    @PostMapping("/register")
    public ApiResponse<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @Operation(summary = "当前用户")
    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.ok(authService.currentUser());
    }

    @Operation(summary = "修改账号资料")
    @PutMapping("/account")
    public ApiResponse<UserVO> updateAccount(@Valid @RequestBody AccountUpdateRequest request) {
        return ApiResponse.ok(authService.updateAccount(request));
    }

    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public ApiResponse<Void> updatePassword(@Valid @RequestBody PasswordUpdateRequest request) {
        authService.updatePassword(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        authService.logout();
        tokenCookieSupport.clear(servletResponse, servletRequest);
        return ApiResponse.ok();
    }

    @Operation(summary = "当前登录用户（内存态，供页面渲染使用）")
    @GetMapping("/principal")
    public ApiResponse<LoginUser> principal() {
        return ApiResponse.ok(UserContext.require());
    }
}
