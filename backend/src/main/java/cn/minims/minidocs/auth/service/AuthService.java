package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.AuthDtos.AccountUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginCodeLoginRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginResponse;
import cn.minims.minidocs.auth.dto.AuthDtos.PasswordResetRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.PasswordUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;

public interface AuthService {

    LoginResponse login(LoginRequest request, String ip);

    /**
     * 邮箱验证码登录。
     *
     * <p>验证码通过即视为本人：直接发 token，并在会话里打上「需要重设密码」的标记，
     * 前端据此进入平台后提醒一次。</p>
     */
    LoginResponse loginByCode(LoginCodeLoginRequest request, String ip);

    /**
     * 重设密码：不校验原密码，授权来自 {@link #loginByCode} 写下的会话标记。
     */
    void resetPassword(PasswordResetRequest request);

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
