package cn.minims.minidocs.config;

import cn.minims.minidocs.auth.interceptor.AdminInterceptor;
import cn.minims.minidocs.auth.interceptor.AuthInterceptor;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.tenant.interceptor.TenantInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Web 层装配。
 *
 * <p><b>前端产物位置</b>：{@code front/} 单入口构建，直接输出到
 * {@code backend/src/main/resources/static/}，Maven 复制资源后即为 {@code classpath:/static/}，
 * 前端随 jar 一起分发，无需单独部署静态目录。
 * 后端本身不写任何前端<b>源码</b>；{@code static/} 下只有构建产物，且已在 .gitignore 中排除。</p>
 *
 * <p>静态资源映射（单 SPA，规范 §3.4）：{@code /assets/**} 走长缓存，其余 {@code /**} 走协商缓存。
 * 深链接由 {@code SpaFallbackController} 回退到唯一的 {@code /index.html}。</p>
 *
 * <p>若配置了 {@code minidocs.front-dir}，该目录会作为<b>追加</b>查找位置，
 * 用于「不重新打包即替换前端产物」；classpath 始终优先。</p>
 *
 * <p>Spring Boot 默认的静态资源映射（{@code spring.web.resources.add-mappings}）已关闭：
 * 它同样注册 {@code /**}，注册顺序会与这里的映射相互覆盖，导致首页解析结果不确定。</p>
 *
 * <p>{@code @RestController} 的映射优先级高于资源处理器，故 {@code /api/**}、
 * {@code /kb/{org}/{slug}/asset/**}、{@code /share/{token}/asset/**} 等接口不会被静态资源覆盖。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 带内容哈希的构建产物，可长期缓存 */
    private static final CacheControl IMMUTABLE =
            CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();

    /** 产物随 jar 分发时所在的 classpath 前缀 */
    private static final String CLASSPATH_STATIC = "classpath:/static/";

    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final TenantInterceptor tenantInterceptor;
    private final MiniDocsProperties properties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/search",
                        "/api/search/suggest",
                        "/api/portal/**",
                        "/api/avatars/**",
                        "/api/site-logos/**",
                        "/api/share/**");

        // 组织上下文只在路径里（§3.4）；join-request 的申请人按定义还不是成员，判定的那条
        // 「组织是否公开」的路在 service 里，不能在这里被成员门挡掉。
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/console/**")
                .excludePathPatterns(TenantInterceptor.MEMBER_GATE_EXEMPTION);

        // 平台侧接口：不分组织，门槛是平台角色。路径与组织管理台（/api/console/**）分开，
        // 免得「管理员」这个词在两处指两种身份 —— 组织 ADMIN 到这里没有权。
        registry.addInterceptor(adminInterceptor).addPathPatterns("/api/platform/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String[] roots = roots();

        // 1) 带 hash 的构建资源：长缓存
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(sub(roots, "assets/"))
                .setCacheControl(IMMUTABLE);

        // 2) 入口 HTML 与其它资源：协商缓存（no-cache + ETag）
        registry.addResourceHandler("/**")
                .addResourceLocations(roots)
                .setCacheControl(CacheControl.noCache());

        logLocations(roots);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 仅开发期需要：Vite dev server 与后端不同源
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 静态资源查找位置，按优先级排列：
     * <ol>
     *   <li>{@code classpath:/static/} —— 单 SPA 的构建产物随 jar 分发</li>
     *   <li>{@code file:{front-dir}/} —— 可选的外部覆盖目录</li>
     * </ol>
     */
    private String[] roots() {
        List<String> locations = new ArrayList<>();
        locations.add(CLASSPATH_STATIC);
        properties.externalFrontRoot()
                .filter(Files::isDirectory)
                .ifPresent(dir -> locations.add(toLocation(dir)));
        return locations.toArray(String[]::new);
    }

    /** 给每个查找位置拼上子路径，例如 {@code classpath:/static/ + assets/} */
    private String[] sub(String[] roots, String suffix) {
        String[] result = new String[roots.length];
        for (int i = 0; i < roots.length; i++) {
            result[i] = roots[i] + suffix;
        }
        return result;
    }

    private String toLocation(Path dir) {
        return "file:" + dir.toString().replace('\\', '/') + "/";
    }

    private void logLocations(String[] roots) {
        log.info("静态资源查找位置 [{}]", String.join(", ", roots));

        // 未配置外部覆盖目录时，classpath 是唯一来源：缺产物要立刻给出可执行的提示
        boolean onClasspath = new ClassPathResource("static/index.html").exists();
        if (properties.externalFrontRoot().isEmpty() && !onClasspath) {
            log.warn("classpath:/static/ 下未找到前端产物（缺 index.html），页面将返回 404。"
                    + "请在 front/ 执行 npm run build，产物会写入 src/main/resources/static，"
                    + "并确保已重新构建后端（mvn compile / IDE Build）。");
        }
    }
}
