package cn.minims.minidocs.auth;

import cn.minims.minidocs.auth.dto.ApiTokenDtos;
import cn.minims.minidocs.auth.entity.ApiToken;
import cn.minims.minidocs.auth.interceptor.AuthInterceptor;
import cn.minims.minidocs.auth.mapper.ApiTokenMapper;
import cn.minims.minidocs.auth.service.ApiTokenService;
import cn.minims.minidocs.auth.service.impl.ApiTokenServiceImpl;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 第三方应用访问令牌：签发、校验、吊销，以及令牌在拦截器里的权限边界。
 *
 * <p>要守住的三条：</p>
 * <ul>
 *   <li>只读令牌不能写；</li>
 *   <li>令牌不能自己签发令牌，也不能改账号密码；</li>
 *   <li>吊销与过期立刻生效 —— 令牌是「随时可以掐断」的凭据，不是发出去就没了的东西。</li>
 * </ul>
 */
@DisplayName("第三方应用访问令牌")
class ApiTokenFlowTest extends MiniDocsTestBase {

    @Autowired
    private ApiTokenService apiTokenService;
    @Autowired
    private ApiTokenMapper apiTokenMapper;
    @Autowired
    private UserService userService;

    private User alice;
    private AuthInterceptor interceptor;

    @BeforeEach
    void seed() {
        alice = saveUser("alice", "爱丽丝", User.ROLE_USER);
        interceptor = new AuthInterceptor(userService, apiTokenService);
    }

    /** 造一个能被 {@code preHandle} 认出来的 HandlerMethod —— 它只用来判断「路径落到了接口上」。 */
    private HandlerMethod handlerMethod() throws Exception {
        return new HandlerMethod(new ProbeController(), ProbeController.class.getMethod("probe"));
    }

    private static class ProbeController {
        public void probe() {
        }
    }

    private MockHttpServletRequest request(String method, String servletPath, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
        request.setServletPath(servletPath);
        request.setRequestURI("/minidocs" + servletPath);
        request.setContextPath("/minidocs");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    private String issue(String scopes) {
        return apiTokenService.create(alice.getId(), new ApiTokenDtos.CreateRequest("测试应用", scopes, null)).token();
    }

    @Nested
    @DisplayName("签发与校验")
    class Issue {

        @Test
        @DisplayName("签发出来的令牌带 mdt_ 前缀，原文不进库")
        void issueKeepsRawTokenOutOfDatabase() {
            String raw = issue("read");

            assertThat(raw).startsWith(ApiToken.TOKEN_PREFIX);
            assertThat(raw.length()).isGreaterThan(40);

            ApiToken stored = apiTokenMapper.selectOne(Wrappers.<ApiToken>lambdaQuery()
                    .eq(ApiToken::getUserId, alice.getId()));
            assertThat(stored).isNotNull();
            // 库里只有摘要：原文既不等于摘要，也不等于令牌本身
            assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(raw);
            assertThat(stored.getTokenPrefix()).startsWith(ApiToken.TOKEN_PREFIX);
            assertThat(stored.getScopes()).isEqualTo(ApiToken.SCOPE_READ);
        }

        @Test
        @DisplayName("令牌换来的身份就是所有者本人")
        void authenticateReturnsOwner() {
            String raw = issue("read");
            ApiTokenService.Authenticated authenticated = apiTokenService.authenticate(raw);

            assertThat(authenticated.user().getId()).isEqualTo(alice.getId());
            assertThat(authenticated.scopes()).isEqualTo(ApiToken.SCOPE_READ);
        }

        @Test
        @DisplayName("无效令牌按未登录处理")
        void invalidTokenRejected() {
            expectError(ErrorCode.UNAUTHORIZED, () -> apiTokenService.authenticate("mdt_不是真令牌"));
            expectError(ErrorCode.UNAUTHORIZED, () -> apiTokenService.authenticate(""));
        }

        @Test
        @DisplayName("有效期到点即失效")
        void expiredTokenRejected() {
            String raw = issue("read");
            ApiToken stored = apiTokenMapper.selectOne(Wrappers.<ApiToken>lambdaQuery()
                    .eq(ApiToken::getUserId, alice.getId()));
            stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            apiTokenMapper.updateById(stored);

            expectError(ErrorCode.UNAUTHORIZED, () -> apiTokenService.authenticate(raw));
        }

        @Test
        @DisplayName("吊销后立刻失效")
        void revokeKillsToken() {
            String raw = issue("read");
            Long id = apiTokenService.list(alice.getId()).get(0).id();

            apiTokenService.revoke(alice.getId(), id);

            expectError(ErrorCode.UNAUTHORIZED, () -> apiTokenService.authenticate(raw));
            assertThat(apiTokenService.list(alice.getId()).get(0).status()).isEqualTo("revoked");
        }

        @Test
        @DisplayName("不能吊销别人的令牌")
        void cannotRevokeOthersToken() {
            User bob = saveUser("bob", "鲍勃", User.ROLE_USER);
            String raw = apiTokenService.create(bob.getId(),
                    new ApiTokenDtos.CreateRequest("鲍勃的应用", "read", null)).token();
            Long id = apiTokenService.list(bob.getId()).get(0).id();

            expectError(ErrorCode.NOT_FOUND, () -> apiTokenService.revoke(alice.getId(), id));
            // 别人的令牌仍然可用
            assertThat(apiTokenService.authenticate(raw).user().getId()).isEqualTo(bob.getId());
        }

        @Test
        @DisplayName("有效令牌数量有上限")
        void tokenCountCapped() {
            for (int i = 0; i < ApiTokenServiceImpl.MAX_TOKENS_PER_USER; i++) {
                apiTokenService.create(alice.getId(), new ApiTokenDtos.CreateRequest("应用" + i, "read", null));
            }
            expectError(ErrorCode.PARAM_INVALID, () -> apiTokenService.create(alice.getId(),
                    new ApiTokenDtos.CreateRequest("再多一个", "read", null)));
        }
    }

    @Nested
    @DisplayName("权限边界")
    class Boundaries {

        @Test
        @DisplayName("只读令牌只能读")
        void readOnlyTokenCannotWrite() throws Exception {
            String raw = issue("read");

            assertThatCode(() -> interceptor.preHandle(request("GET", "/api/me", raw), null, handlerMethod()))
                    .doesNotThrowAnyException();
            interceptor.afterCompletion(request("GET", "/api/me", raw), null, handlerMethod(), null);

            expectError(ErrorCode.FORBIDDEN, () ->
                    interceptor.preHandle(request("POST", "/api/console/acme/kbs", raw), null, handlerMethod()));
        }

        @Test
        @DisplayName("读写令牌可以写")
        void writeTokenCanWrite() throws Exception {
            String raw = issue("read,write");

            assertThatCode(() -> interceptor.preHandle(request("POST", "/api/console/acme/kbs", raw), null,
                    handlerMethod())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("令牌不能管理令牌，也不能改账号密码")
        void tokenCannotManageTokensOrPassword() throws Exception {
            String raw = issue("read,write");

            expectError(ErrorCode.FORBIDDEN, () ->
                    interceptor.preHandle(request("GET", "/api/tokens", raw), null, handlerMethod()));
            expectError(ErrorCode.FORBIDDEN, () ->
                    interceptor.preHandle(request("PUT", "/api/auth/password", raw), null, handlerMethod()));
        }

        @Test
        @DisplayName("令牌认证后当前用户就是令牌所有者")
        void contextCarriesOwner() throws Exception {
            String raw = issue("read");
            MockHttpServletRequest req = request("GET", "/api/me", raw);

            interceptor.preHandle(req, null, handlerMethod());

            assertThat(UserContext.require().id()).isEqualTo(alice.getId());
            interceptor.afterCompletion(req, null, handlerMethod(), null);
        }

        @Test
        @DisplayName("吊销后的令牌连读都读不了")
        void revokedTokenCannotRead() throws Exception {
            String raw = issue("read");
            Long id = apiTokenService.list(alice.getId()).get(0).id();
            apiTokenService.revoke(alice.getId(), id);

            expectError(ErrorCode.UNAUTHORIZED, () ->
                    interceptor.preHandle(request("GET", "/api/me", raw), null, handlerMethod()));
        }
    }

    /** 断言抛出的 BizException 携带预期错误码。
     *
     * <p>不叫 assertBizError：基座里已有一个同名的重载（参数是 ThrowingCallable），
     * 两个重载对同一个 lambda 会产生歧义，编译器直接报错。</p>
     */
    private void expectError(ErrorCode expected, ThrowingCallable callable) {
        try {
            callable.call();
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(expected);
            return;
        } catch (Exception e) {
            throw new AssertionError("期望抛出 " + expected + "，实际抛出 " + e.getClass().getName(), e);
        }
        throw new AssertionError("期望抛出 " + expected + "，但没有异常");
    }
}
