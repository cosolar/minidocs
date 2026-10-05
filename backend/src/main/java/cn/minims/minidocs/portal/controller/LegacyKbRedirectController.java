package cn.minims.minidocs.portal.controller;

import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.reader.support.CurrentUserResolver;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.util.List;

/**
 * v1 老链接 {@code /kb/{slug}} 的迁出（规范 §7.1.3）。
 *
 * <p>阅读页 URL 自 v2 起带组织段，因为 slug 只在组织内唯一。老链接不能直接失效：唯一命中就 301
 * 到新地址，命中零个或多个时把请求交给用户侧 SPA，由 SPA 调 {@code /api/portal/locate} 渲染消歧列表
 * 或未找到态。</p>
 *
 * <p>「命中几个」是按访问者的读权限算的（{@code visibleBySlug} 内部走 {@code applyVisibleScope}）：
 * 游客与成员看到的数量可以不同，同一条老链接对游客是「未找到」，对成员是 301。这里刻意不做区分，
 * 因为给游客消歧列表等于向无读权的人确认库的存在。</p>
 *
 * <p>本映射比 {@code SpaFallbackController} 的 {@code /kb/**} 更具体，会优先命中两段的阅读页之外的
 * 单段路径。</p>
 */
@Controller
@RequiredArgsConstructor
public class LegacyKbRedirectController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping("/kb/{slug}")
    public void legacy(@PathVariable String slug, HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        List<KbVO> matches = knowledgeBaseService.visibleBySlug(slug, currentUserResolver.resolve());
        if (matches.size() == 1) {
            KbVO kb = matches.get(0);
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", request.getContextPath() + "/kb/" + PathEncoder.encodePath(kb.tenantSlug())
                    + "/" + PathEncoder.encodePath(kb.slug()));
            return;
        }
        RequestDispatcher dispatcher = request.getRequestDispatcher("/index.html");
        dispatcher.forward(request, response);
    }
}
