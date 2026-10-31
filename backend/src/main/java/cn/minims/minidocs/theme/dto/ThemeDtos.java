package cn.minims.minidocs.theme.dto;

import java.util.Map;

/**
 * 阅读主题包 DTO。
 *
 * <p>主题包是纯 JSON：元信息 + 明暗两套 CSS 自定义属性。它不携带样式表，也不执行脚本 ——
 * 前端把令牌注入成一段 {@code <style>}，因此「安装主题」不引入任何可执行内容。</p>
 *
 * <p>{@code builtin} 只由服务端填写：磁盘上存在 {@code .seed-<id>} 标记即视为内置主题，
 * 管理员卸载后仍可通过「恢复内置主题」找回。主题包文件里的同名字段会被忽略。</p>
 */
public final class ThemeDtos {

    private ThemeDtos() {
    }

    public record ThemePackVO(
            String id,
            String name,
            String description,
            String author,
            String version,
            String license,
            Map<String, String> light,
            Map<String, String> dark,
            boolean builtin) {
    }
}
