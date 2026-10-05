package cn.minims.minidocs.common.db;

import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻量数据库迁移器（替代 Flyway）。
 *
 * <p>Flyway 社区版不支持 SQLite，故此处自行实现：按方言读取
 * {@code classpath:db/migration/{dialect}/V*.sql}，以 {@code schema_version} 表记录已应用版本，
 * 未应用的脚本按版本号顺序执行。脚本编写规则与 Flyway 一致（V1__init.sql）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbMigrator implements InitializingBean {

    private static final String VERSION_TABLE_SQLITE =
            "CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version TEXT PRIMARY KEY, script TEXT NOT NULL, applied_at TEXT NOT NULL)";
    private static final String VERSION_TABLE_MYSQL =
            "CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version VARCHAR(64) PRIMARY KEY, script VARCHAR(255) NOT NULL, applied_at DATETIME NOT NULL)";

    private final DataSource dataSource;
    private final MiniDocsProperties properties;

    @Override
    public void afterPropertiesSet() {
        String dialect = properties.getDbDialect() == null ? "sqlite" : properties.getDbDialect().toLowerCase();
        boolean sqlite = "sqlite".equals(dialect);
        if (sqlite) {
            applySqlitePragmas();
        }
        createVersionTable(sqlite);

        List<Resource> scripts = loadScripts(dialect);
        Set<String> applied = loadedVersions();
        List<Resource> pending = new ArrayList<>();
        for (Resource resource : scripts) {
            String version = versionOf(resource);
            if (!applied.contains(version)) {
                pending.add(resource);
            }
        }
        if (pending.isEmpty()) {
            log.info("数据库迁移检查完成，已是最新版本（dialect={}）", dialect);
            return;
        }
        for (Resource resource : pending) {
            String version = versionOf(resource);
            String script = resource.getFilename();
            log.info("应用迁移脚本 {} ({})", version, script);
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(false);
            populator.setSeparator(";");
            populator.addScript(resource);
            DatabasePopulatorUtils.execute(populator, dataSource);
            recordVersion(sqlite, version, script);
        }
        log.info("数据库迁移完成，共应用 {} 个脚本", pending.size());
    }

    private void applySqlitePragmas() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (Exception e) {
            log.warn("SQLite PRAGMA 初始化失败：{}", e.getMessage());
        }
    }

    private void createVersionTable(boolean sqlite) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sqlite ? VERSION_TABLE_SQLITE : VERSION_TABLE_MYSQL);
        } catch (Exception e) {
            throw new IllegalStateException("创建 schema_version 表失败", e);
        }
    }

    private Set<String> loadedVersions() {
        Set<String> versions = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM schema_version")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取迁移版本失败", e);
        }
        return versions;
    }

    private void recordVersion(boolean sqlite, String version, String script) {
        String sql = sqlite
                ? "INSERT INTO schema_version(version, script, applied_at) VALUES(?,?,?)"
                : "INSERT INTO schema_version(version, script, applied_at) VALUES(?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, version);
            statement.setString(2, script);
            statement.setString(3, TimeUtil.format(TimeUtil.now()));
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("记录迁移版本失败：" + version, e);
        }
    }

    private List<Resource> loadScripts(String dialect) {
        String pattern = "classpath*:db/migration/" + dialect + "/V*.sql";
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            List<Resource> list = new ArrayList<>(List.of(resources));
            list.sort(Comparator.comparing(resource -> versionOrder(versionOf(resource))));
            return list;
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败：" + pattern, e);
        }
    }

    private static String versionOf(Resource resource) {
        String filename = resource.getFilename() == null ? "" : resource.getFilename();
        int end = filename.indexOf("__");
        String version = end > 0 ? filename.substring(1, end) : filename;
        return version;
    }

    private static long versionOrder(String version) {
        try {
            return Long.parseLong(version.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
