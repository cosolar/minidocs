package cn.minims.minidocs.portal;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.kb.dto.KbDtos.StatsVO;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.portal.controller.PortalApiController;
import cn.minims.minidocs.reader.support.CurrentUserResolver;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 门户统计接口的登录态口径。
 *
 * <p>{@code /api/portal/**} 不在 {@code AuthInterceptor} 的拦截范围内，{@code UserContext}
 * 始终为空；接口若直接读它，统计会落进游客分支，只数得到公开库。这个用例锁住修复后的行为。</p>
 */
@DisplayName("门户统计接口：登录态口径")
class PortalStatsControllerTest extends MiniDocsTestBase {

    @Autowired
    private PortalApiController controller;

    @Autowired
    private KnowledgeBaseService kbService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @Test
    @DisplayName("登录用户调用统计时包含非公开库")
    void authenticatedStatsIncludeNonPublicKbs() {
        LoginUser root = as(saveUser("stats-root", "统计管理员", User.ROLE_ADMIN));
        kbService.create(null, createKb("统计公开库", "描述", KnowledgeBase.VISIBILITY_PUBLIC,
                null, List.of(), null), root);
        kbService.create(null, createKb("统计私有库", "描述", KnowledgeBase.VISIBILITY_PRIVATE,
                null, List.of(), null), root);
        when(currentUserResolver.resolve()).thenReturn(root);

        ApiResponse<StatsVO> response = controller.stats();

        assertThat(response.data().kbTotal()).isEqualTo(2);
        assertThat(response.data().kbPrivate()).isEqualTo(1);
    }
}
