package cn.minims.minidocs.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * V11（邮箱验证列）迁移验证。
 *
 * <p>与 {@link TenantMigrationTest} 同样的理由不走 Spring 容器：{@link ResourceDatabasePopulator}
 * 用的分割与执行语义与 {@code DbMigrator} 一致，这里跑通即等于生产路径跑通。</p>
 *
 * <p>重点断言的是 <b>DEFAULT 1</b> 这一件事：它决定「打开邮箱验证」上线的瞬间，
 * 存量账号是被放行还是被集体挡在登录页外。这一位没有别的兜底，所以值得单独一个测试。</p>
 */
@DisplayName("邮箱验证迁移 V1→V11")
class EmailVerificationMigrationTest {

    private static final String DIALECT = "sqlite";

    private Path dbFile;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void applyV1AndSeedLegacyUsers() throws IOException {
        dbFile = Files.createTempFile("minidocs-email-verify", ".db");
        dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        runScript("V1__init.sql");
        execute("""
                INSERT INTO users (id, username, password_hash, display_name, email, role, status, created_at, updated_at)
                VALUES (1, 'admin', 'x', '超级管理员', NULL,      'ADMIN', 'active', '2026-01-01T00:00:00', '2026-01-01T00:00:00'),
                       (2, 'alice', 'x', 'Alice',     'a@x.test', 'USER',  'active', '2026-01-02T00:00:00', '2026-01-02T00:00:00')
                """);
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

    private void execute(String sql) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
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

    @Test
    @DisplayName("存量账号迁移后即为已验证：上线这一次不会把老用户挡在门外")
    void legacyUsersBecomeVerified() {
        runScript("V11__email_verification.sql");

        assertThat(queryStrings("""
                SELECT username, email_verified, verify_token, verify_expires_at
                FROM users ORDER BY id
                """))
                .containsExactly("admin|1|null|null|", "alice|1|null|null|");
    }

    @Test
    @DisplayName("旧代码的插入路径不受影响：新列有默认值或可空")
    void legacyInsertPathStillWorks() {
        runScript("V11__email_verification.sql");

        assertThatCode(() -> execute("""
                INSERT INTO users (username, password_hash, display_name, email, role, status, created_at, updated_at)
                VALUES ('carol', 'x', 'Carol', 'c@x.test', 'USER', 'active',
                        '2026-03-01T00:00:00', '2026-03-01T00:00:00')
                """)).doesNotThrowAnyException();

        assertThat(queryStrings("SELECT email_verified FROM users WHERE username = 'carol'"))
                .containsExactly("1|");
    }

    @Test
    @DisplayName("未验证是注册时显式写入的状态，且令牌可按索引反查")
    void pendingVerificationIsWritableAndIndexable() {
        runScript("V11__email_verification.sql");

        execute("""
                UPDATE users SET email_verified = 0, verify_token = 'tok-abc',
                       verify_expires_at = '2026-03-02T00:00:00'
                WHERE username = 'alice'
                """);

        assertThat(queryStrings("""
                SELECT email_verified, verify_token, verify_expires_at
                FROM users WHERE verify_token = 'tok-abc'
                """)).containsExactly("0|tok-abc|2026-03-02T00:00:00|");

        // 令牌反查走索引：没有它，验证接口每次点链接都要全表扫
        assertThat(queryStrings("""
                SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'users'
                  AND name = 'idx_users_verify_token'
                """)).containsExactly("idx_users_verify_token|");
    }
}
