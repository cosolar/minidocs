package cn.minims.minidocs.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规范 §2.6 的守门测试：权限判定只能长在 AccessService 里。
 *
 * <p>不启动 Spring 容器，直接扫 {@code src/main/java}。理由：绕过点一旦写回来，即使当下没有一行
 * 代码调用它，它也会成为下一次「顺手用一下」的入口。让 CI 变红比让评审看漏便宜。</p>
 *
 * <p>路径相对模块目录（{@code backend/}）解析，因此必须在 backend 模块下运行。</p>
 */
@DisplayName("M2 权限守门：判定不得散落在业务代码")
class AuthorizationGuardrailTest {

    private static final Path SOURCE_ROOT = locateSourceRoot();

    private static final String PERMISSION_PACKAGE_DIR = "cn/minims/minidocs/permission/";

    /** 角色与组织形态字面量：只准出现在常量声明处（entity）与裁决层（permission）。 */
    private static final Pattern ROLE_LITERAL =
            Pattern.compile("\"(OWNER|ADMIN|MEMBER|EDITOR|VIEWER|PERSONAL|TEAM|invite_only)\"");

    /** 「拿创建者当权限用」的形状：与调用者比较，或按 owner_id 过滤查询。 */
    private static final Pattern OWNER_COMPARISON = Pattern.compile(
            "getOwnerId\\(\\)\\s*(==|!=|\\.equals|!)|Objects\\.equals\\([^)]*getOwnerId|::getOwnerId\\s*,");

    /** 曾经存在的绕过入口：统一裁决后这两个名字不该再出现。 */
    private static final Pattern BYPASS_HELPER = Pattern.compile("\\brequire(?:Owned|Readable)\\s*\\(");

    /**
     * owner_id 过滤的许可清单：相对路径 → 允许的代码片段（去空白后子串匹配）。
     *
     * <p>按片段而不是按文件放行，是为了让「同一文件里再加一处 owner 比较」也变红。
     * 每一处都必须是数据清理或展示，不是放行判定；新增条目请写清理由。</p>
     */
    private static final Map<String, List<String>> OWNER_FILTER_ALLOWLIST = Map.of(
            // 注销用户时连带清理其名下的库：流程里没有「人」可用，只能按归属查
            "cn/minims/minidocs/kb/service/impl/KnowledgeBaseServiceImpl.java",
            List.of(".eq(KnowledgeBase::getOwnerId,userId)"),
            // 管理台用户列表的「库数量」列 + 删除用户时清理其创建的分享
            "cn/minims/minidocs/user/service/UserAdminService.java",
            List.of(".eq(KnowledgeBase::getOwnerId,user.getId())", ".eq(Share::getOwnerId,id)"));

    @Test
    @DisplayName("角色与组织形态字面量只存在于实体常量与裁决层")
    void roleLiteralsStayInConstantsAndAccessLayer() {
        assertThat(scan(ROLE_LITERAL).stream()
                .filter(hit -> !hit.inPermissionLayer() && !hit.inEntity())
                .map(Hit::render)
                .toList())
                .as("§2.6：业务代码不得出现角色字面量，判定请写进 AccessService")
                .isEmpty();
    }

    @Test
    @DisplayName("没有第二处拿 owner_id 当权限用")
    void nobodyComparesCreatorAsPermission() {
        assertThat(scan(OWNER_COMPARISON).stream()
                .filter(hit -> !hit.inPermissionLayer())
                .filter(hit -> !hit.allowlisted())
                .map(Hit::render)
                .toList())
                .as("§2.6：创建者字段只是审计信息，读写请走 requireKb / requireShare")
                .isEmpty();
    }

    @Test
    @DisplayName("许可清单不过期：清单里的每一处都还得在原地")
    void allowlistDoesNotRot() {
        List<Hit> hits = scan(OWNER_COMPARISON).stream()
                .filter(hit -> !hit.inPermissionLayer())
                .toList();

        OWNER_FILTER_ALLOWLIST.forEach((file, snippets) -> snippets.forEach(snippet -> assertThat(hits)
                .as("许可清单条目已失效，请一并删除：%s#%s", file, snippet)
                .anyMatch(hit -> hit.file().equals(file) && hit.normalized().contains(squash(snippet)))));
    }

    @Test
    @DisplayName("绕过式判定入口不得复活")
    void bypassHelpersStayDeleted() {
        assertThat(scan(BYPASS_HELPER).stream().map(Hit::render).toList())
                .as("§2.6：requireOwned / requireReadable 已删除，裁决统一走 AccessService")
                .isEmpty();
    }

    @Test
    @DisplayName("源码根确实被扫到：防止守门测试静默空跑")
    void sourceRootIsNotEmpty() throws IOException {
        // 命中数为 0 既可能是干净也可能是路径错了，所以先证明扫描器本身在工作
        long javaFiles;
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            javaFiles = files.filter(path -> path.toString().endsWith(".java")).count();
        }
        assertThat(javaFiles).as("源码根 %s 下应有上百个 java 文件", SOURCE_ROOT).isGreaterThan(50);
        assertThat(scan(Pattern.compile("AccessService")).stream().filter(Hit::inPermissionLayer).count())
                .as("裁决层自身必须能被扫到").isPositive();
    }

    // ------------------------------------------------------------------ 扫描

    private static List<Hit> scan(Pattern pattern) {
        Predicate<String> ignored = line -> line.startsWith("//") || line.startsWith("*")
                || line.startsWith("/*");
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            return sources.stream().flatMap(path -> matchLines(path, pattern, ignored).stream()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("无法扫描源码目录：" + SOURCE_ROOT, e);
        }
    }

    private static List<Hit> matchLines(Path path, Pattern pattern, Predicate<String> ignored) {
        String file = SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 " + path, e);
        }
        IntPredicate matched = i -> !ignored.test(lines.get(i).trim())
                && pattern.matcher(lines.get(i)).find();
        return IntStream.range(0, lines.size()).filter(matched)
                .mapToObj(i -> new Hit(file, i + 1, lines.get(i).trim()))
                .toList();
    }

    /** 从当前工作目录向上找到 backend 的源码根，便于从任意目录运行。 */
    private static Path locateSourceRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            Path candidate = dir.resolve("backend").resolve("src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            if (Files.isDirectory(dir.resolve("src/main/java"))) {
                return dir.resolve("src/main/java");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 src/main/java，请在 backend 模块或其上级目录下运行本测试");
    }

    private record Hit(String file, int line, String text) {

        /** 去掉全部空白后的行内容：许可清单按片段匹配，不受换行与缩进影响。 */
        String normalized() {
            return squash(text);
        }

        boolean inPermissionLayer() {
            return file.startsWith(PERMISSION_PACKAGE_DIR);
        }

        boolean inEntity() {
            return file.contains("/entity/");
        }

        boolean allowlisted() {
            return OWNER_FILTER_ALLOWLIST.getOrDefault(file, List.of()).stream()
                    .anyMatch(snippet -> normalized().contains(squash(snippet)));
        }

        String render() {
            return file + ":" + line + ": " + text;
        }
    }

    private static String squash(String value) {
        return value.replaceAll("\\s+", "");
    }
}
