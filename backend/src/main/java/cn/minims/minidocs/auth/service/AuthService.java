package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.AuthDtos.AccountUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.LoginResponse;
import cn.minims.minidocs.auth.dto.AuthDtos.PasswordUpdateRequest;
import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;

public interface AuthService {

    LoginResponse login(LoginRequest request, String ip);

    UserVO register(RegisterRequest request);

    UserVO currentUser();

    UserVO updateAccount(AccountUpdateRequest request);

    void updatePassword(PasswordUpdateRequest request);

    void logout();
}
