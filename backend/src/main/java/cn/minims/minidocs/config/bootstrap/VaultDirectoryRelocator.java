package cn.minims.minidocs.config.bootstrap;

import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.common.util.StorageKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 把 v1 的 {@code vaults/{slug}} 目录搬到 v2 的 {@code vaults/o{tenantId}/{slug}}。
 *
 * <p>放在 {@code InitializingBean} 而非 ApplicationRunner：Web 容器在容器 refresh 的收尾阶段才启动，
 * 而 Bean 初始化在此之前，因此搬迁过程不可能有请求在读写目录。依赖 {@code dbMigrator} 是为了
 * 确保列与回填已就位。</p>
 *
 * <p>搬迁不删任何东西：目标已存在则跳过（幂等，重启安全），旧目录不存在也跳过（可能是空库或已手工挪走）。</p>
 *
 * <p>但「跳过」只适用于目录，不适用于数据：只要有一行的 {@code storage_key} 是空的就整批中止、
 * 一个目录都不动。回填没跑完意味着落库的 {@code o{id}/{slug}} 是错的，此时按 slug 搬会把库放到一个
 * 永远读不到的路径上，而 {@code rename} 之后的旧名字还会被下一次的 {@code create} 当成空目录复用。
 * 所以先全表预检再动手，宁可启动失败让人来看。</p>
 */
@Slf4j
@Component
@DependsOn("dbMigrator")
@RequiredArgsConstructor
public class VaultDirectoryRelocator implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;
    private final MiniDocsProperties properties;
    private final VaultFileService vaultFileService;

    @Override
    public void afterPropertiesSet() {
        int moved = relocateLegacyDirectories();
        if (moved > 0) {
            log.info("知识库目录搬迁完成，共移动 {} 个目录", moved);
        }
    }

    /** 返回实际移动的目录数，便于测试断言。 */
    public int relocateLegacyDirectories() {
        Path vaults = properties.vaultsDir();
        if (!Files.isDirectory(vaults)) {
            return 0;
        }
        List<LegacyRow> rows = jdbcTemplate.query(
                "SELECT id, slug, storage_key FROM knowledge_base",
                (rs, rowNum) -> new LegacyRow(rs.getLong("id"), rs.getString("slug"), rs.getString("storage_key")));
        requireStorageKeys(rows);
        int moved = 0;
        for (LegacyRow row : rows) {
            if (moveInto(vaults, row)) {
                moved++;
            }
        }
        if (moved > 0) {
            vaultFileService.invalidateAll();
        }
        return moved;
    }

    /** 全表预检：任何一行缺 storage_key 或格式非法都在动磁盘之前中止。 */
    private static void requireStorageKeys(List<LegacyRow> rows) {
        List<Long> missing = rows.stream()
                .filter(row -> row.storageKey() == null || row.storageKey().isBlank())
                .map(LegacyRow::id)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("知识库 " + missing + " 缺少 storage_key，V2/V3 迁移未完整执行。"
                    + "已中止启动，未移动任何目录。");
        }
        rows.forEach(row -> StorageKey.validate(row.storageKey()));
    }

    private boolean moveInto(Path vaults, LegacyRow row) {
        Path target = vaults.resolve(row.storageKey()).toAbsolutePath().normalize();
        if (Files.exists(target)) {
            return false;
        }
        Path legacy = vaults.resolve(row.slug()).toAbsolutePath().normalize();
        if (!Files.isDirectory(legacy) || legacy.equals(target)) {
            return false;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(legacy, target);
            log.info("知识库目录搬迁 id={} {} -> {}", row.id(), legacy.getFileName(), row.storageKey());
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("知识库目录搬迁失败：" + legacy + " -> " + target, e);
        }
    }

    private record LegacyRow(Long id, String slug, String storageKey) {
    }
}
