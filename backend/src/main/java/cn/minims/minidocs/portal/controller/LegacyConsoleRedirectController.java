package cn.minims.minidocs.portal.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * 老管理台地址 {@code /w/**} 的迁出。
 *
 * <p>组织管理台的前缀已从单字母的 {@code /w} 改成 {@code /console}。用户可能已经收藏了工作区
 * 地址（{@code /w/{org}/kbs/{slug}}），直接换前缀会让这些书签 404，所以这里 301 到新地址。</p>
 *
 * <p>用 {@code getRequestURI()} 而不是 {@code @PathVariable} 拼目标：原始串是百分号编码的，
 * 中文组织名照抄即可，不必再编码一次。查询串（{@code /w?new=1} 这类）原样带上。</p>
 *
 * <p>只迁页面地址。{@code /api/w/**} 是前端内部调用，随本次改造一并更新，没有外部消费者，
 * 不留重定向 —— 那需要再绕开鉴权拦截器，多出来的复杂度不划算。</p>
 */
@Controller
public class LegacyConsoleRedirectController {

    private static final String OLD_PREFIX = "/w";
    private static final String NEW_PREFIX = "/console";

    @GetMapping({OLD_PREFIX, OLD_PREFIX + "/**"})
    public void legacy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String target = contextPath + NEW_PREFIX + path.substring(OLD_PREFIX.length());
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            target = target + "?" + query;
        }
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader("Location", target);
    }
}
