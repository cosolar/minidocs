package cn.minims.minidocs.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1 → V2 → V3 多租户迁移验证。
 *
 * <p>不走 Spring 容器：直接以 {@link ResourceDatabasePopulator} 顺序执行脚本，与
 * {@code DbMigrator} 用的是同一套分割与执行语义（separator {@code ;}），这里跑通即等于生产路径跑通。</p>
 *
 * <p>迁移刻意分成扩展（V2，只增）与收缩（V3，收紧 NOT NULL 与唯一索引）两段，
 * 因为收缩段依赖「会写新列的代码」—— 把两步合成一步时，v1 实体不含 tenant_id，
 * 插入直接撞 NOT NULL（{@code PermissionBaselineTest} 实测复现）。因此本类也分两段断言：
 * V2 之后 v1 写入路径必须仍然可用，V3 之后才是目标形态。</p>
 */
@DisplayName("多租户迁移 V1→V2→V3")
class TenantMigrationTest {

    private static final String DIALECT = "sqlite";

    private Path dbFile;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void applyV1AndSeedLegacyData() throws IOException {
        dbFile = Files.createTempFile("minidocs-migration", ".db");
        dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        runScript("V1__init.sql");
        seedLegacyData();
    }

    @AfterEach
    void deleteDbFile() throws IOException {
        dataSource = null;
        Files.deleteIfExists(dbFile);
    }

    private void runScript(String name) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.setSeparator(";");
        populator.addScript(new ClassPathResource("db/migration/" + DIALECT + "/" + name));
        populator.execute(dataSource);
    }

    private void runV2() {
        runScript("V2__tenant.sql");
    }

    private void runV2AndV3() {
        runScript("V2__tenant.sql");
        runScript("V3__tenant_contract.sql");
    }

    /** 构造 v1 形态的存量数据：两个用户 + 内置管理员、公私库、分享、收藏，外加一个孤儿库。 */
    private void seedLegacyData() {
        execute("""
                INSERT INTO users (id, username, password_hash, display_name, email, role, status, created_at, updated_at)
                VALUES (1, 'admin', 'x', '超级管理员', NULL, 'ADMIN', 'active', '2026-01-01T00:00:00', '2026-01-01T00:00:00'),
                       (2, 'alice', 'x', 'Alice', NULL, 'USER', 'active', '2026-01-02T00:00:00', '2026-01-02T00:00:00'),
                       (3, 'bob',   'x', NULL,    NULL, 'USER', 'pending','2026-01-03T00:00:00', '2026-01-03T00:00:00')
                """);
        execute("""
                INSERT INTO knowledge_base (id, owner_id, name, slug, description, visibility, cover_url, tags, doc_count, created_at, updated_at)
                VALUES (1, 1, '运维手册', '运维手册', NULL, 'public',  NULL, 'ops', 3, '2026-02-01T00:00:00', '2026-02-01T00:00:00'),
                       (2, 2, '面试手册', '面试手册', NULL, 'private', NULL, NULL, 5, '2026-02-02T00:00:00', '2026-02-02T00:00:00'),
                       (3, 2, '读书笔记', '读书笔记', NULL, 'public',  NULL, NULL, 2, '2026-02-03T00:00:00', '2026-02-03T00:00:00'),
                       -- 孤儿库：owner_id=99 不在 users 里（v1 无外键，现实中会出现）
                       (4, 99, '遗留库',   '遗留库',   NULL, 'private', NULL, NULL, 1, '2026-02-04T00:00:00', '2026-02-04T00:00:00')
                """);
        execute("""
                INSERT INTO shares (id, token, owner_id, kb_id, doc_path, scope, password_hash, expires_at, revoked, invalid, views, created_at, updated_at)
                VALUES (1, 'S20260201000000000001', 1, 1, NULL, 'kb',   NULL, NULL, 0, 0, 7, '2026-02-05T00:00:00', '2026-02-05T00:00:00'),
                       (2, 'S20260201000000000002', 2, 3, 'a.md', 'doc', NULL, NULL, 0, 0, 0, '2026-02-05T00:00:00', '2026-02-05T00:00:00')
                """);
        execute("INSERT INTO kb_favorite (user_id, kb_id, created_at) VALUES (3, 1, '2026-02-06T00:00:00')");
    }

    private void execute(String sql) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            // 带上 SQL 原文：约束类断言要看的就是这段消息
            throw new AssertionError("SQL 执行失败：" + e.getMessage(), e);
        }
    }

    private List<String> queryStrings(String sql) {
        List<String> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(rs.getString(i)).append('|');
                }
                rows.add(row.toString());
            }
        } catch (SQLException e) {
            throw new AssertionError("查询失败：" + e.getMessage(), e);
        }
        return rows;
    }

    private int count(String table) {
        String value = queryStrings("SELECT COUNT(*) FROM " + table).get(0);
        return Integer.parseInt(value.substring(0, value.length() - 1));
    }

    @Nested
    @DisplayName("V2 扩展阶段")
    class Expand {

        @Test
        @DisplayName("六张新表就位，个人组织与 OWNER 成员行按用户数回填")
        void tablesAndMembershipBackfilled() {
            runV2();

            for (String table : List.of("tenant", "tenant_member", "tenant_join_request",
                    "kb_member", "doc_edit_lock", "audit_log")) {
                assertThat(count(table)).as("表 %s 应存在", table).isGreaterThanOrEqualTo(0);
            }
            // 3 个真实用户 + 1 个孤儿归属
            assertThat(count("tenant")).isEqualTo(4);
            assertThat(count("tenant_member")).isEqualTo(4);
        }

        @Test
        @DisplayName("每个存量用户得到个人组织，本人是 OWNER，且组织不可被发现/申请")
        void everyLegacyUserGetsPersonalTenantOwnedByThem() {
            runV2();

            assertThat(queryStrings("""
                    SELECT LOWER(u.username), t.slug, t.type, t.discoverable, m.role
                    FROM users u
                    JOIN tenant t ON t.owner_user_id = u.id
                    JOIN tenant_member m ON m.tenant_id = t.id AND m.user_id = u.id
                    ORDER BY u.id
                    """))
                    .containsExactly(
                            "admin|u-admin|PERSONAL|0|OWNER|",
                            "alice|u-alice|PERSONAL|0|OWNER|",
                            "bob|u-bob|PERSONAL|0|OWNER|");

            assertThat(queryStrings("SELECT slug FROM tenant WHERE discoverable = 1")).isEmpty();
            // 个人组织一律 invite_only：不能被陌生人申请加入
            assertThat(queryStrings("SELECT DISTINCT join_policy FROM tenant WHERE type = 'PERSONAL'"))
                    .containsExactly("invite_only|");
        }

        @Test
        @DisplayName("pending 用户的个人组织仍为 active：账号状态只在 users 上表达一次")
        void personalTenantDoesNotMirrorAccountStatus() {
            runV2();

            // 若迁移把 pending 抄进 tenant.status，审批通过后没人会去改组织，
            // 用户被放行却看不见自己的库 —— 这类静默失效正是要避免的
            assertThat(queryStrings("""
                    SELECT t.status FROM tenant t JOIN users u ON u.id = t.owner_user_id
                    WHERE u.username = 'bob'
                    """)).containsExactly("active|");
        }

        @Test
        @DisplayName("知识库归入创建者的个人组织，storage_key = o{tenantId}/{slug}")
        void knowledgeBasesGetTenantAndStorageKey() {
            runV2();

            assertThat(queryStrings("""
                    SELECT k.slug, t.slug, k.storage_key, k.visibility, k.maintain_scope
                    FROM knowledge_base k JOIN tenant t ON t.id = k.tenant_id
                    ORDER BY k.id
                    """))
                    .containsExactly(
                            "运维手册|u-admin|o1/运维手册|public|owner_only|",
                            "面试手册|u-alice|o2/面试手册|private|owner_only|",
                            "读书笔记|u-alice|o2/读书笔记|public|owner_only|",
                            // 孤儿库落到 u-deleted-99：目录仍在磁盘上，不丢数据
                            "遗留库|u-deleted-99|o4/遗留库|private|owner_only|");
        }

        @Test
        @DisplayName("visibility 不被改写：迁移绝不放大可见范围")
        void visibilityNeverWidenedByMigration() {
            List<String> before = queryStrings("SELECT visibility FROM knowledge_base ORDER BY id");

            runV2();

            assertThat(queryStrings("SELECT visibility FROM knowledge_base ORDER BY id")).isEqualTo(before);
            assertThat(queryStrings("SELECT visibility FROM knowledge_base WHERE visibility = 'org'")).isEmpty();
        }

        @Test
        @DisplayName("V2 之后 v1 写入路径必须仍然可用（新列可空，不撞约束）")
        void legacyWritePathStillWorksWithoutNewColumns() {
            runV2();

            // 这正是把收缩合并进扩展时炸掉的语句：v1 实体没有 tenant_id / storage_key
            assertThatCode(() -> execute("""
                    INSERT INTO knowledge_base (owner_id, name, slug, visibility, doc_count, created_at, updated_at)
                    VALUES (2, '老代码新建', '老代码新建', 'private', 0, '2026-03-01T00:00:00', '2026-03-01T00:00:00')
                    """)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("既有收藏与用户设置不受影响")
        void favoritesAndSettingsSurvive() {
            runV2();

            assertThat(count("kb_favorite")).isEqualTo(1);
            assertThat(queryStrings("SELECT kb_id FROM kb_favorite WHERE user_id = 3")).containsExactly("1|");
        }
    }

    @Nested
    @DisplayName("V3 收缩阶段")
    class Contract {

        @Test
        @DisplayName("V2 与代码之间新建的漏网行会被兜底回填，再收紧 NOT NULL")
        void orphanRowsGetRescuedBeforeTightening() {
            runV2();
            // 模拟旧代码在 V2 之后写入、tenant_id 为空的一行
            execute("""
                    INSERT INTO knowledge_base (id, owner_id, name, slug, visibility, doc_count, created_at, updated_at)
                    VALUES (10, 3, '漏网库', '漏网库', 'private', 0, '2026-03-01T00:00:00', '2026-03-01T00:00:00')
                    """);
            assertThat(queryStrings("SELECT storage_key FROM knowledge_base WHERE id = 10")).containsExactly("null|");

            runScript("V3__tenant_contract.sql");

            assertThat(queryStrings("SELECT tenant_id, storage_key FROM knowledge_base WHERE id = 10"))
                    .containsExactly("3|o3/漏网库|");
        }

        @Test
        @DisplayName("V3 之后 tenant_id / storage_key 不可为空")
        void newColumnsBecomeNotNull() {
            runV2AndV3();

            assertThatThrownBy(() -> execute("""
                    INSERT INTO knowledge_base (owner_id, name, slug, visibility, doc_count, created_at, updated_at)
                    VALUES (2, '缺租户', '缺租户', 'private', 0, '2026-03-01T00:00:00', '2026-03-01T00:00:00')
                    """)).hasMessageContaining("NOT NULL");
        }

        @Test
        @DisplayName("slug 唯一性降级为组织内：跨组织同名可共存，同组织仍冲突")
        void slugBecomesTenantScoped() {
            runV2AndV3();

            assertThatThrownBy(() -> execute("""
                    INSERT INTO knowledge_base (id, tenant_id, owner_id, name, slug, storage_key, visibility,
                                                maintain_scope, doc_count, created_at, updated_at)
                    VALUES (11, 2, 2, '重名库', '面试手册', 'o2/面试手册-2', 'private',
                            'owner_only', 0, '2026-03-01T00:00:00', '2026-03-01T00:00:00')
                    """)).hasMessageContaining("UNIQUE");

            // 换到别的组织用同一个 slug —— v1 的全局唯一索引下这是不可能的
            assertThatCode(() -> execute("""
                    INSERT INTO knowledge_base (id, tenant_id, owner_id, name, slug, storage_key, visibility,
                                                maintain_scope, doc_count, created_at, updated_at)
                    VALUES (12, 3, 3, '面试手册', '面试手册', 'o3/面试手册', 'org',
                            'members', 0, '2026-03-01T00:00:00', '2026-03-01T00:00:00')
                    """)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("既有数据在重建表后完整保留（id / doc_count / 时间戳不漂移）")
        void rebuildPreservesEveryRow() {
            List<String> before = queryStrings(
                    "SELECT id, owner_id, slug, doc_count, created_at, updated_at FROM knowledge_base ORDER BY id");

            runV2AndV3();

            assertThat(queryStrings(
                    "SELECT id, owner_id, slug, doc_count, created_at, updated_at FROM knowledge_base ORDER BY id"))
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("分享唯一性脱离 owner：同库同目标只允许一条，owner_id 退为创建者审计字段")
        void shareTargetUniquenessNoLongerOwnerScoped() {
            runV2AndV3();

            assertThatThrownBy(() -> execute("""
                    INSERT INTO shares (id, token, owner_id, kb_id, doc_path, scope, password_hash,
                                        expires_at, revoked, invalid, views, created_at, updated_at)
                    VALUES (3, 'S20260201000000000003', 3, 3, 'a.md', 'doc', NULL,
                            NULL, 0, 0, 0, '2026-03-02T00:00:00', '2026-03-02T00:00:00')
                    """)).hasMessageContaining("UNIQUE");

            assertThatCode(() -> execute("""
                    INSERT INTO shares (id, token, owner_id, kb_id, doc_path, scope, password_hash,
                                        expires_at, revoked, invalid, views, created_at, updated_at)
                    VALUES (4, 'S20260201000000000004', 3, 3, 'b.md', 'doc', NULL,
                            NULL, 0, 0, 0, '2026-03-02T00:00:00', '2026-03-02T00:00:00')
                    """)).doesNotThrowAnyException();

            assertThat(queryStrings("SELECT owner_id FROM shares WHERE id = 2")).containsExactly("2|");
        }

        @Test
        @DisplayName("编辑锁主键为（库, 路径）：同库同文档互斥，跨库跨文档并行")
        void docLockIsPerKbAndPath() {
            runV2AndV3();

            execute("""
                    INSERT INTO doc_edit_lock (kb_id, doc_path, holder_user_id, acquired_at, expires_at)
                    VALUES (1, 'a.md', 1, '2026-03-03T00:00:00', '2026-03-03T00:01:00')
                    """);
            assertThatThrownBy(() -> execute("""
                    INSERT INTO doc_edit_lock (kb_id, doc_path, holder_user_id, acquired_at, expires_at)
                    VALUES (1, 'a.md', 2, '2026-03-03T00:00:00', '2026-03-03T00:01:00')
                    """)).hasMessageContaining("PRIMARY KEY");

            assertThatCode(() -> execute("""
                    INSERT INTO doc_edit_lock (kb_id, doc_path, holder_user_id, acquired_at, expires_at)
                    VALUES (1, 'b.md', 2, '2026-03-03T00:00:00', '2026-03-03T00:01:00'),
                           (2, 'a.md', 2, '2026-03-03T00:00:00', '2026-03-03T00:01:00')
                    """)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("维护名单唯一约束：一人一库一行，VIEWER 也能存")
        void kbMemberUniquePerUser() {
            runV2AndV3();

            execute("""
                    INSERT INTO kb_member (kb_id, user_id, role, granted_by, created_at)
                    VALUES (1, 2, 'EDITOR', 1, '2026-03-04T00:00:00')
                    """);
            assertThatThrownBy(() -> execute("""
                    INSERT INTO kb_member (kb_id, user_id, role, granted_by, created_at)
                    VALUES (1, 2, 'VIEWER', 1, '2026-03-04T00:00:00')
                    """)).hasMessageContaining("UNIQUE");

            assertThatCode(() -> execute("""
                    INSERT INTO kb_member (kb_id, user_id, role, granted_by, created_at)
                    VALUES (1, 3, 'VIEWER', 1, '2026-03-04T00:00:00')
                    """)).doesNotThrowAnyException();
        }
    }
}
