package cn.minims.minidocs.tenant.interceptor;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员门对组织段的解析：{@code getRequestURI()} 给的是未解码的原始串，中文 slug 必须以解码后的
 * 形态交给 {@code requireBySlug}，否则一个真实存在的组织会被门成 404（规范 §2.5 只允许
 * 「不是成员」与「不存在」同形，不允许「是成员且存在」也同形）。
 */
@DisplayName("M5a 成员门：组织段解码")
class TenantInterceptorTest {

    private static final LoginUser ALICE = new LoginUser(1L, "alice", "爱丽丝", "a@b.c", "USER", "active");

    private final TenantService tenantService = mock(TenantService.class);
    private final AccessService accessService = mock(AccessService.class);
    private final TenantInterceptor interceptor = new TenantInterceptor(tenantService, accessService);

    @AfterEach
    void clear() {
        TenantContext.clear();
        UserContext.clear();
    }

    private Tenant team() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        tenant.setSlug("面试手册");
        return tenant;
    }

    private MockHttpServletRequest request(String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", rawUri);
        request.setRequestURI(rawUri);
        return request;
    }

    @Test
    @DisplayName("浏览器发来的百分号形式解析成中文 slug 后查组织")
    void decodesPercentEncodedOrgSlug() {
        when(tenantService.requireBySlug("面试手册")).thenReturn(team());
        UserContext.set(ALICE);

        boolean passed = interceptor.preHandle(
                request("/api/console/" + URLEncoder.encode("面试手册", StandardCharsets.UTF_8) + "/kbs"),
                new MockHttpServletResponse(), mock(HandlerMethod.class));

        assertThat(passed).isTrue();
        verify(tenantService).requireBySlug("面试手册");
        verify(accessService).requireMember(any(Tenant.class), any(LoginUser.class));
        assertThat(TenantContext.requireId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("afterCompletion 一定清掉组织上下文：线程复用不能把上一个请求的组织带给下一个")
    void clearsContextAfterRequest() {
        when(tenantService.requireBySlug("面试手册")).thenReturn(team());
        UserContext.set(ALICE);
        MockHttpServletRequest request = request("/api/console/"
                + URLEncoder.encode("面试手册", StandardCharsets.UTF_8) + "/kbs");

        interceptor.preHandle(request, new MockHttpServletResponse(), mock(HandlerMethod.class));
        assertThat(TenantContext.get()).isNotNull();
        interceptor.afterCompletion(request, new MockHttpServletResponse(), null, null);

        assertThat(TenantContext.get()).isNull();
        // 无上下文时取值入口给 404 而不是 NPE：路由挂错要按「不存在」回答
        assertThatThrownBy(TenantContext::requireId).isInstanceOf(BizException.class);
    }
}
