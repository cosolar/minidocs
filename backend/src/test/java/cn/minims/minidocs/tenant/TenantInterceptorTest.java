package cn.minims.minidocs.tenant;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.interceptor.TenantInterceptor;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M5a：{@code /api/console/{org}/**} 的组织上下文解析与成员门（规范 §3.4 / §2.5）。
 *
 * <p>裁决矩阵本身在 {@code AccessServiceTest} 里已经铺满，这里只锁拦截器该负责的那三件事：
 * slug 取的是哪一段、门挡下时上下文里留没留东西、豁免路径有多窄。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("M5a 组织拦截器")
class TenantInterceptorTest {

    @Mock
    private TenantService tenantService;
    @Mock
    private AccessService accessService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
        TenantContext.clear();
    }

    /** 每个用例现构造：@Mock 字段是在测试实例创建之后才注入的，字段初始化时会拿到 null。 */
    private TenantInterceptor interceptor() {
        return new TenantInterceptor(tenantService, accessService);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    /**
     * 命中了接口方法的 handler。拦截器第一件事是「这条路径有没有 controller 要执行」，
     * 未命中的（打错的 {@code /api/console/xxx}）一律放行给资源处理器出 404，所以判定用例都得传它。
     */
    private static HandlerMethod api() {
        return mock(HandlerMethod.class);
    }

    private static void login() {
        UserContext.set(new LoginUser(1L, "alice", "Alice", null, User.ROLE_USER, User.STATUS_ACTIVE));
    }

    private static Tenant acme() {
        Tenant tenant = new Tenant();
        tenant.setId(42L);
        tenant.setSlug("acme");
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setStatus("active");
        return tenant;
    }

    @Test
    @DisplayName("slug 取路径第三段，与后面还有多深无关")
    void slugIsThirdSegment() {
        login();
        when(tenantService.requireBySlug("acme")).thenReturn(acme());

        boolean passed = interceptor().preHandle(request("GET", "/api/console/acme/kbs/notes/tree"),
                new MockHttpServletResponse(), api());

        assertThat(passed).isTrue();
        verify(accessService).requireMember(any(Tenant.class), any(LoginUser.class));
        assertThat(TenantContext.require().getId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("组织段之后为空也能解析（GET /api/console/acme）")
    void orgRootOnlyPath() {
        login();
        when(tenantService.requireBySlug("acme")).thenReturn(acme());

        assertThat(interceptor().preHandle(request("GET", "/api/console/acme"), new MockHttpServletResponse(),
                api())).isTrue();
        assertThat(TenantContext.get()).isNotNull();
    }

    @Test
    @DisplayName("空组织段与缺前缀都是 404，不是 500")
    void malformedPathIsNotFound() {
        login();

        assertThatThrownBy(() -> interceptor().preHandle(request("GET", "/api/console/"),
                new MockHttpServletResponse(), api()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(tenantService);
    }

    /**
     * 打错的路径（{@code /api/console/acme/nope}）没有 controller 接手，handler 是静态资源处理器；
     * 此时必须整条放行，由资源处理器出 404。若在门内解析组织，游客访问一个不存在的路径
     * 会先吃到 401/404 之外的语义（甚至 500），错误类型不再可信。
     */
    @Test
    @DisplayName("路径没落到接口方法上：放行给资源处理器出 404，不判成员")
    void nonHandlerMethodPassesThrough() {
        assertThat(interceptor().preHandle(request("GET", "/api/console/acme/nope"), new MockHttpServletResponse(),
                new Object())).isTrue();
        verifyNoInteractions(tenantService, accessService);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("无登录态：成员门拿不到访问者即 401，绝不按游客放行")
    void anonymousIsUnauthorized() {
        assertThatThrownBy(() -> interceptor().preHandle(request("GET", "/api/console/acme/kbs"),
                new MockHttpServletResponse(), api()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("预检请求不解析组织（浏览器不带 Authorization 头时也要能过）")
    void optionsSkipsResolution() {
        assertThat(interceptor().preHandle(request("OPTIONS", "/api/console/acme/kbs"), new MockHttpServletResponse(),
                api())).isTrue();
        verifyNoInteractions(tenantService, accessService);
    }

    @Test
    @DisplayName("非成员被挡下时不留组织上下文")
    void rejectedRequestLeavesNoContext() {
        login();
        Tenant tenant = acme();
        when(tenantService.requireBySlug("acme")).thenReturn(tenant);
        when(accessService.requireMember(tenant, UserContext.get()))
                .thenThrow(BizException.notFound("组织不存在"));

        assertThatThrownBy(() -> interceptor().preHandle(request("GET", "/api/console/acme/kbs"),
                new MockHttpServletResponse(), api()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("afterCompletion 清上下文：线程复用不会把上一个组织带给下一个请求")
    void contextClearedAfterCompletion() {
        login();
        when(tenantService.requireBySlug("acme")).thenReturn(acme());
        MockHttpServletRequest request = request("GET", "/api/console/acme/kbs");

        interceptor().preHandle(request, new MockHttpServletResponse(), api());
        assertThat(TenantContext.get()).isNotNull();

        interceptor().afterCompletion(request, new MockHttpServletResponse(), api(), null);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("成员门异常照常抛出，afterCompletion 仍会清上下文")
    void handlerFailureStillClearsContext() {
        login();
        when(tenantService.requireBySlug("acme")).thenReturn(acme());
        MockHttpServletRequest request = request("GET", "/api/console/acme/kbs");
        interceptor().preHandle(request, new MockHttpServletResponse(), api());

        interceptor().afterCompletion(request, new MockHttpServletResponse(), api(),
                new IllegalStateException("boom"));

        assertThat(TenantContext.get()).isNull();
        verify(accessService).requireMember(any(Tenant.class), any(LoginUser.class));
    }

    @Test
    @DisplayName("豁免只盖住 join-request 一条，join-requests 列表仍在门内")
    void exemptionIsNarrowerThanItLooks() {
        AntPathMatcher matcher = new AntPathMatcher();
        String exemption = TenantInterceptor.MEMBER_GATE_EXEMPTION;

        assertThat(matcher.match(exemption, "/api/console/acme/join-request")).isTrue();
        assertThat(matcher.match(exemption, "/api/console/acme/join-requests")).isFalse();
        assertThat(matcher.match(exemption, "/api/console/acme/join-requests/9/approve")).isFalse();
        assertThat(matcher.match(exemption, "/api/console/acme/kbs")).isFalse();
        assertThat(matcher.match(exemption, "/api/console/acme/members/7")).isFalse();
    }
}
