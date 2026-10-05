package cn.minims.minidocs.common.util;

import java.util.regex.Pattern;

/**
 * 知识库物理目录标识：{@code o{tenantId}/{slug}}，相对 {@code VAULT_HOME/vaults/}。
 *
 * <p>引入原因：slug 降级为「组织内唯一」后两个组织可以有同名 slug，磁盘必须靠本标识区分；
 * 同时让「改 slug」不再等于「挪目录」。一经写入永不可变（规范 I4）。</p>
 */
public final class StorageKey {

    /** 形如 o123/some-slug：首段固定为 o{租户ID}，末段为目录名，不允许再分层。 */
    private static final Pattern SHAPE = Pattern.compile("o\\d+/[^/\\\\.][^/\\\\]*");

    private StorageKey() {
    }

    public static String of(long tenantId, String slug) {
        if (tenantId <= 0) {
            throw new IllegalStateException("生成 storage_key 需要有效的租户 ID");
        }
        return "o" + tenantId + "/" + slug;
    }

    /**
     * 校验来自数据库的标识。不合法即抛而非回退到 slug —— 回退会让越界路径被静默解析成另一个目录。
     */
    public static void validate(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalStateException("知识库缺少 storage_key，无法定位物理目录（请确认 V2/V3 迁移已应用）");
        }
        if (!SHAPE.matcher(storageKey).matches()) {
            throw new IllegalStateException("非法的 storage_key：" + storageKey);
        }
    }
}
