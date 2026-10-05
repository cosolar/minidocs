package cn.minims.minidocs.support;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.kb.dto.KbDtos;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试基座。
 *
 * <p>DB 由 {@code DbMigrator}（InitializingBean）在容器启动时自动建表，无需额外脚本；
 * 每个测试方法事务回滚，互不污染。注意 {@code VaultDirInitializer} 只在 {@code main()} 中注册，
 * 测试不经 {@code main()}，因此数据目录必须由本类自行创建。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class MiniDocsTestBase {

    private static final Path VAULT_HOME = prepareVaultHome();

    @Autowired
    protected UserService userService;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("minidocs.vault-home", VAULT_HOME::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + VAULT_HOME.resolve("minidocs-test.db"));
    }

    /** 每次运行从空目录开始，避免上次遗留的库与 vault 目录干扰断言。 */
    private static Path prepareVaultHome() {
        Path root = Paths.get("target", "test-vault").toAbsolutePath().normalize();
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("无法清空测试数据目录：" + root, e);
            }
        }
        try {
            Files.createDirectories(root.resolve("vaults"));
            Files.createDirectories(root.resolve("logs"));
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建测试数据目录：" + root, e);
        }
        return root;
    }

    protected static Path vaultRoot() {
        return VAULT_HOME.resolve("vaults");
    }

    /**
     * 手动触发已登记的提交后钩子。
     *
     * <p>本基座每个用例回滚，所以 {@code afterCommit} 在测试里永远不会自己跑到 ——
     * 而「删目录排在提交之后」正是被测的那条契约。这里按 Spring 提交时的同一顺序把钩子过一遍，
     * 于是既能证明钩子确实登记了，也能证明它只清该清的那个目录。</p>
     */
    protected static void runAfterCommitHooks() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    /**
     * 落一个测试用户。
     *
     * <p>密码哈希是非法的 bcrypt 串：测试不走登录接口，Token 由 {@link #as} 直接构造，
     * 留假哈希比留真哈希安全，也避免每个用例重复 bcrypt 的开销。</p>
     */
    protected User saveUser(String username, String displayName, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash("$2a$10$invalid-invalid-invalid-invalid-invalid-invalid-inva");
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus(User.STATUS_ACTIVE);
        userService.save(user);
        return user;
    }

    /** 由用户实体构造请求级登录身份：测试不经 AuthInterceptor，UserContext 为空。 */
    protected static LoginUser as(User user) {
        return new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getRole(), user.getStatus());
    }

    /**
     * 建库请求的简写。
     *
     * <p>{@link KbDtos.CreateRequest} 自 v2 起尾部多了 5 个 git 字段（来源类型、仓库地址、分支、
     * 用户名、令牌），而绝大多数用例只关心前六项。集中在这里补尾，改签名时不用逐处返工。</p>
     */
    protected static KbDtos.CreateRequest createKb(String name, String description, String visibility,
                                                   String maintainScope, List<String> tags, String coverUrl) {
        return new KbDtos.CreateRequest(name, description, visibility, maintainScope, tags, coverUrl,
                null, null, null, null, null);
    }

    /** 断言抛出的 BizException 携带预期错误码。 */
    protected static void assertBizError(ErrorCode expected, ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
                .isInstanceOf(BizException.class)
                .satisfies(t -> {
                    ErrorCode actual = ((BizException) t).getErrorCode();
                    org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
                });
    }

    /** 返回 BizException 本身，供进一步断言 message。 */
    protected static BizException catchBiz(ThrowingCallable callable) {
        try {
            callable.call();
        } catch (BizException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("期望 BizException，实际抛出 " + e.getClass().getName(), e);
        }
        throw new AssertionError("期望抛出 BizException，但没有抛出");
    }

    @FunctionalInterface
    public interface ThrowingCallable {
        void call() throws Exception;
    }
}
