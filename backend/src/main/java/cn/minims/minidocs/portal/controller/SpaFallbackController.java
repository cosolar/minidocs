package cn.minims.minidocs.portal.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端 SPA（history 模式）路由回退。
 *
 * <p>M5d 起前后端只有一份构建产物、一个入口 HTML（规范 §3.4 / F11）：这里把「不是资源、
 * 也不是接口」的深链接一律 forward 到 {@code /index.html}，由前端路由决定渲染门户、阅读页
 * 还是管理页。</p>
 *
 * <p><b>为什么显式枚举而不是 {@code @GetMapping("/**")}</b>：控制器映射优先级恒高于静态资源
 * 处理器，通配会把 {@code /assets/**} 一并吃掉，导致带 hash 的 JS/CSS 全部 404。
 * 新增前端页面时，只要落在下面这些前缀里就不用改这里；开了新的顶层前缀才需要补一条。</p>
 *
 * <p>组织段 {@code /console/{org}/...} 层级不固定，只能整条通配；它与 {@code /assets/**}
 * 首段不同，不会吃掉构建资源。{@code /kb/{org}/{slug}/asset/**}、
 * {@code /share/{token}/asset/**} 是资源代理接口，路径比 {@code /kb/**}、{@code /share/**}
 * 更具体，会被对应的 {@code @RestController} 优先命中。单段的 {@code /kb/{旧slug}} 由
 * {@code LegacyKbRedirectController} 承接（301 或消歧）。</p>
 *
 * <p>注意不要映射 {@code /index.html} 自身：forward 会重新进入分发链路，造成自转发死循环。</p>
 */
@Controller
public class SpaFallbackController {

    /** 单 SPA 入口。 */
    private static final String ENTRY = "forward:/index.html";

    /**
     * 阅读端 + 管理端 + 认证页：一律回退到同一个入口。
     *
     * <p>{@code /platform} 是平台管理员页面（规范 §3.4），跟组织无关，所以不带 {@code /console} 前缀；
     * {@code /account}、{@code /discover} 同理是「此刻还没有组织段」的个人页与发现页。</p>
     */
    @GetMapping({
            "/",
            "/kb", "/kb/**",
            "/share", "/share/**",
            "/login", "/register",
            "/account", "/discover",
            "/console", "/console/**",
            "/platform", "/platform/**"})
    public String entry() {
        return ENTRY;
    }

    /**
     * 老管理后台地址（{@code /admin/**}）：产物已经合并，整前跳进组织落地页，
     * 由它读 {@code /api/me} 决定去哪个组织 —— 组织只能从地址栏来，这里不猜。
     */
    @GetMapping("/admin/**")
    public String legacyAdmin() {
        return "redirect:/console";
    }
}
