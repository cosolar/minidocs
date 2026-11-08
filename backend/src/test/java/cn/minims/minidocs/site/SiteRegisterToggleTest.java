package cn.minims.minidocs.site;

import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自助注册开关：部署参数只是默认值，后台改过以后台为准。
 *
 * <p>这一条对应的真实故障是「后台打开了注册相关开关，注册页却一直提示自主注册已关闭」——
 * 那个开关当时只控制邮箱验证，注册入口却只认部署参数。现在两个服务都读同一份生效值，
 * 本测试把「默认关闭 → 后台打开 → 放行 → 后台再关 → 拒绝」整条链跑一遍。</p>
 */
@DisplayName("自助注册开关")
@TestPropertySource(properties = "minidocs.register-enabled=false")
class SiteRegisterToggleTest extends MiniDocsTestBase {

    @Autowired
    private AuthService authService;

    @Autowired
    private SiteConfigService siteConfigService;

    private static RegisterRequest user(String name) {
        return new RegisterRequest(name, "Passw0rd!", null, null);
    }

    @Test
    @DisplayName("部署默认关闭时被拒；后台打开后放行；再关闭后重新拒绝")
    void siteSettingOverridesDeploymentDefault() {
        // 配置里没有这个键：回落到部署参数 false，公开配置也必须是 false
        assertThat(siteConfigService.get().registerEnabled()).isFalse();
        assertBizError(ErrorCode.FORBIDDEN, () ->
                authService.register(user("toggle_denied"), TEST_BASE_URL));

        siteConfigService.update(Map.of("registerEnabled", true));
        assertThat(siteConfigService.get().registerEnabled()).isTrue();
        assertThat(authService.register(user("toggle_allowed"), TEST_BASE_URL).username())
                .isEqualTo("toggle_allowed");

        siteConfigService.update(Map.of("registerEnabled", false));
        assertThat(siteConfigService.get().registerEnabled()).isFalse();
        assertBizError(ErrorCode.FORBIDDEN, () ->
                authService.register(user("toggle_denied_again"), TEST_BASE_URL));
    }
}
