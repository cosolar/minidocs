package cn.minims.minidocs.theme.model;

import java.util.Map;

/**
 * 磁盘上主题包文件的原始结构（安装时解析、种子写入时序列化）。
 *
 * <p>与对外 VO 分开：文件里没有 {@code builtin}，也不该让上传者决定自己是不是内置主题。</p>
 */
public record ThemePack(
        String id,
        String name,
        String description,
        String author,
        String version,
        String license,
        Map<String, String> light,
        Map<String, String> dark) {
}
