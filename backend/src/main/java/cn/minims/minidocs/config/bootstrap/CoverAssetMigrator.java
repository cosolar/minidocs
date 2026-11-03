package cn.minims.minidocs.config.bootstrap;

import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.kb.service.KbCoverService;
import cn.minims.minidocs.kb.support.CoverAssets;
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
 * 把旧版留在知识库工作副本里的封面搬到平台目录。
 *
 * <p>1.2.5 之前上传的封面存在 {@code vaults/{storageKey}/assets/cover-*}，与作者内容混在一起，
 * Git 拉取会把它当未跟踪文件收进 stash。现在封面统一存到 {@code VAULT_HOME/platform/covers}，
 * 数据库只记文件名；这里负责把老数据搬过去并改写 {@code cover_url}。</p>
 *
 * <p>顺序是「复制 → 改库 → 删旧文件」，不是直接移动：复制后若改库失败，旧路径仍然有效，
 * 下次启动还能重试；反过来先移动，数据库就会指向一个已经不存在的文件。</p>
 *
 * <p>放在 {@link InitializingBean} 且在目录搬迁之后：Web 容器尚未启动，没有请求会读到半迁移状态；
 * 而旧封面的路径取决于 {@code storage_key} 指向的目录已经就位。</p>
 */
@Slf4j
@Component
@DependsOn({"dbMigrator", "vaultDirectoryRelocator"})
@RequiredArgsConstructor
public class CoverAssetMigrator implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;
    private final MiniDocsProperties properties;
    private final KbCoverService kbCoverService;

    @Override
    public void afterPropertiesSet() {
        int moved = migrateLegacy();
        if (moved > 0) {
            log.info("封面迁移完成，共移动 {} 张到平台目录", moved);
        }
    }

    /** 返回实际迁移的文件数；重复执行安全。 */
    public int migrateLegacy() {
        try {
            Files.createDirectories(properties.coversDir());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建平台封面目录：" + properties.coversDir(), e);
        }
        List<Row> rows = jdbcTemplate.query(
                "SELECT id, storage_key, cover_url FROM knowledge_base WHERE cover_url IS NOT NULL",
                (rs, rowNum) -> new Row(rs.getLong("id"), rs.getString("storage_key"), rs.getString("cover_url")));
        int moved = 0;
        for (Row row : rows) {
            if (row.coverUrl() == null || row.coverUrl().isBlank()
                    || CoverAssets.isExternal(row.coverUrl()) || row.coverUrl().indexOf('/') < 0) {
                continue;
            }
            if (row.storageKey() == null || row.storageKey().isBlank()) {
                continue;
            }
            Path legacy = legacyFile(row);
            if (legacy == null || !Files.isRegularFile(legacy)) {
                log.warn("封面迁移跳过（旧文件不存在）：kbId={} cover={}", row.id(), row.coverUrl());
                continue;
            }
            try {
                String fileName = kbCoverService.adoptLegacy(row.id(), legacy);
                jdbcTemplate.update("UPDATE knowledge_base SET cover_url = ? WHERE id = ?", fileName, row.id());
                Files.deleteIfExists(legacy);
                moved++;
                log.info("封面迁移 kbId={} {} -> platform/covers/{}", row.id(), row.coverUrl(), fileName);
            } catch (Exception e) {
                // 单张失败不该阻止启动：库和旧文件都还在，修掉原因后下次启动会重试
                log.warn("封面迁移失败：kbId={} cover={}", row.id(), row.coverUrl(), e);
            }
        }
        return moved;
    }

    private Path legacyFile(Row row) {
        try {
            StorageKey.validate(row.storageKey());
            return PathGuard.resolve(properties.vaultsDir().resolve(row.storageKey()), row.coverUrl());
        } catch (BizException e) {
            log.warn("封面迁移跳过（路径非法）：kbId={} cover={}", row.id(), row.coverUrl());
            return null;
        }
    }

    private record Row(Long id, String storageKey, String coverUrl) {
    }
}
