package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.AuthDtos.AccountUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginResponse;
import cn.minims.minidocs.auth.dto.AuthDtos.PasswordUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;

public interface AuthService {

    LoginResponse login(LoginRequest request, String ip);

    /**
     * @param baseUrl 验证链接的前缀，由控制器按 {@code SiteBaseUrlResolver} 的优先级解析；
     *                服务层不自己拿请求上下文，是为了让单测能直接调用（注册不发邮件时这个值用不上）
     */
    UserVO register(RegisterRequest request, String baseUrl);

    UserVO currentUser();

    UserVO updateAccount(AccountUpdateRequest request, String baseUrl);

    void updatePassword(PasswordUpdateRequest request);

    void logout();
}
