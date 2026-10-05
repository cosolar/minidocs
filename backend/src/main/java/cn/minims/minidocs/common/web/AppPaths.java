package cn.minims.minidocs.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 全站路径前缀（{@code server.servlet.context-path}）的单一出口。
 *
 * <p>应用挂在 {@code /minidocs} 之下后，凡是「后端自己拼出来的绝对地址」都必须带上这一段，
 * 否则浏览器会把 {@code /api/avatars/x.png}、{@code /kb/{org}/{库}/asset/x.png} 解析到
 * <b>上下文之外</b>，一律 404：头像裂图、封面裂图、阅读页图片裂图、站内 .md 链接跳空。
 * 容器不会替这些字符串补前缀，只能自己补。</p>
 *
 * <p>为什么用静态持有而不是逐层注入：{@code KbDtos.KbVO.from}、{@code UserDtos.UserVO.from}
 * 是静态映射方法，注入不进去；而这些前缀在进程生命周期内是常量，启动时定一次即可。</p>
 *
 * <p>只给「后端拼给前端的绝对路径」用。控制器上的 {@code @RequestMapping}、
 * {@code WebMvcConfig} 的拦截器与资源映射、{@code SpaFallbackController} 的 forward
 * 都不需要它 —— 那些路径由容器按上下文路径解析。</p>
 */
@Component
public class AppPaths {

    private static volatile String base = "";

    public AppPaths(@Value("${server.servlet.context-path:}") String contextPath) {
        base = normalize(contextPath);
    }

    /** 归一化后的前缀：{@code ""} 或 {@code "/minidocs"}（无尾斜杠）。 */
    public static String prefix() {
        return base;
    }

    /** 给一条以 {@code /} 开头的绝对路径补上上下文前缀。 */
    public static String of(String path) {
        if (path == null || path.isEmpty()) {
            return base;
        }
        return path.charAt(0) == '/' ? base + path : base + "/" + path;
    }

    private static String normalize(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return "";
        }
        String trimmed = contextPath.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
