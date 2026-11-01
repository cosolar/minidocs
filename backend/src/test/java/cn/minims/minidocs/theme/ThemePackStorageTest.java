package cn.minims.minidocs.theme;

import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.theme.dto.ThemeDtos.ThemePackVO;
import cn.minims.minidocs.theme.service.impl.ThemeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主题包落盘契约：内置种子、卸载后不复活、安装校验。
 *
 * <p>不启 Spring 容器：这里要证明的是「磁盘上的主题目录怎么被读写」，
 * 与数据库、请求链无关，构造一个 {@link MiniDocsProperties} 指向临时目录就够了。</p>
 */
@DisplayName("阅读主题包：种子、卸载与安装校验")
class ThemePackStorageTest {

    @TempDir
    Path tempDir;

    private ThemeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = newService();
        service.init();
    }

    @Test
    @DisplayName("首次启动把内置主题种到站点目录，并标记为内置")
    void seedsBuiltinsOnFirstStart() {
        List<ThemePackVO> packs = service.list();

        assertThat(packs).extracting(ThemePackVO::id)
                .contains("default", "ayu", "celadon", "gatsby", "jiang", "newsprint", "nord", "pine", "solarized", "vitepress");
        assertThat(packs).allSatisfy(pack -> assertThat(pack.builtin())
                .as("%s 应标记为内置", pack.id())
                .isTrue());
        assertThat(packs).allSatisfy(pack -> {
            assertThat(pack.light()).isNotEmpty();
            assertThat(pack.dark()).hasSameSizeAs(pack.light());
        });
    }

    @Test
    @DisplayName("default 每次启动都跟随程序内版本刷新，旧配色不会滞留在磁盘上")
    void defaultPackAlwaysRefreshes() throws Exception {
        Path target = themesDir().resolve("default.json");
        Files.writeString(target, """
                {"id":"default","name":"旧默认","version":"0.0.1","license":"MIT",
                 "light":{"--md-primary":"#000000"},"dark":{"--md-primary":"#000000"}}
                """, StandardCharsets.UTF_8);

        // 模拟升级重启：其它内置主题沿用种子标记，default 以 jar 内版本覆盖
        ThemeServiceImpl restarted = newService();
        restarted.init();

        assertThat(restarted.list())
                .filteredOn(pack -> "default".equals(pack.id()))
                .singleElement()
                .satisfies(pack -> {
                    assertThat(pack.name()).isEqualTo("澄澈");
                    assertThat(pack.light()).containsEntry("--md-primary", "#128577");
                    assertThat(pack.dark()).hasSameSizeAs(pack.light());
                });
    }

    @Test
    @DisplayName("default 也可以卸载：重启不复活，恢复内置主题后回来")
    void defaultCanBeUninstalled() {
        service.uninstall("default");
        assertThat(service.list()).extracting(ThemePackVO::id).doesNotContain("default");

        ThemeServiceImpl restarted = newService();
        restarted.init();
        assertThat(restarted.list())
                .as("卸载标记存在时，default 不该被启动种子写回来")
                .extracting(ThemePackVO::id).doesNotContain("default");

        assertThat(restarted.reseed()).extracting(ThemePackVO::id).contains("default");
    }

    @Test
    @DisplayName("内置主题卸载后重启不复活；恢复内置主题才回来")
    void uninstalledBuiltinDoesNotResurrect() {
        service.uninstall("nord");
        assertThat(service.list()).extracting(ThemePackVO::id).doesNotContain("nord");

        // 模拟重启：同一个目录重新构造服务并跑一次种子
        ThemeServiceImpl restarted = newService();
        restarted.init();
        assertThat(restarted.list()).extracting(ThemePackVO::id)
                .as("卸载标记存在时，启动种子不该把包补回来")
                .doesNotContain("nord");

        assertThat(restarted.reseed()).extracting(ThemePackVO::id).contains("nord");
    }

    @Test
    @DisplayName("安装自定义主题：列表可见、builtin=false，卸载后文件消失")
    void installAndUninstallCustomPack() {
        ThemePackVO installed = service.install(pack("my-theme", "我的主题", validTokens()));

        assertThat(installed.id()).isEqualTo("my-theme");
        assertThat(installed.builtin()).isFalse();
        assertThat(themesDir().resolve("my-theme.json")).exists();
        assertThat(service.list()).extracting(ThemePackVO::id).contains("my-theme");

        service.uninstall("my-theme");
        assertThat(themesDir().resolve("my-theme.json")).doesNotExist();
        assertThat(service.list()).extracting(ThemePackVO::id).doesNotContain("my-theme");
    }

    @Test
    @DisplayName("安装校验：明暗令牌不一致、危险取值、保留令牌、保留 id 全部拒绝")
    void installRejectsBrokenPacks() {
        Map<String, String> light = validTokens();
        Map<String, String> dark = new LinkedHashMap<>(light);
        dark.remove("--md-primary");

        assertThatThrownBy(() -> service.install(pack("broken", "缺一项", light, dark)))
                .as("light 与 dark 必须同一组键")
                .hasMessageContaining("同一组令牌");

        Map<String, String> dangerous = new LinkedHashMap<>(validTokens());
        dangerous.put("--md-bg", "url(https://evil.example/x.png)");
        assertThatThrownBy(() -> service.install(pack("danger", "危险", dangerous)))
                .hasMessageContaining("不允许的内容");

        Map<String, String> reserved = new LinkedHashMap<>(validTokens());
        reserved.put("--md-read-font", "Comic Sans MS");
        assertThatThrownBy(() -> service.install(pack("reserved", "抢字体", reserved)))
                .as("正文字体是读者自己的设置，主题不能覆盖")
                .hasMessageContaining("不能覆盖");

        assertThatThrownBy(() -> service.install(pack("default", "冒名", validTokens())))
                .hasMessageContaining("保留 id");
    }

    @Test
    @DisplayName("卸载不存在的主题返回未找到")
    void uninstallGuards() {
        assertThatThrownBy(() -> service.uninstall("no-such-theme")).hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("读取列表时跳过坏文件，不影响其余主题")
    void listSkipsBrokenFiles() throws Exception {
        int before = service.list().size();
        Files.writeString(themesDir().resolve("broken.json"), "{ not json", StandardCharsets.UTF_8);
        Files.writeString(themesDir().resolve("evil.json"),
                JsonUtil.toJson(Map.of("id", "evil", "name", "x")), StandardCharsets.UTF_8);

        assertThat(service.list()).hasSize(before);
    }

    // ------------------------------------------------------------------ 工具

    private ThemeServiceImpl newService() {
        MiniDocsProperties properties = new MiniDocsProperties();
        properties.setVaultHome(tempDir.toAbsolutePath().toString());
        return new ThemeServiceImpl(properties);
    }

    private Path themesDir() {
        return tempDir.resolve("site").resolve("themes");
    }

    private static Map<String, String> validTokens() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("--md-bg", "#ffffff");
        tokens.put("--md-surface", "#ffffff");
        tokens.put("--md-surface-2", "#f5f6f8");
        tokens.put("--md-border", "#e3e5ea");
        tokens.put("--md-border-strong", "#cfd3dc");
        tokens.put("--md-text", "#1f2333");
        tokens.put("--md-text-2", "#4b5266");
        tokens.put("--md-text-3", "#7a8196");
        tokens.put("--md-primary", "#2563eb");
        tokens.put("--md-primary-soft", "color-mix(in srgb, #2563eb 12%, transparent)");
        tokens.put("--md-accent", "#0d9488");
        return tokens;
    }

    private static MockMultipartFile pack(String id, String name, Map<String, String> tokens) {
        return pack(id, name, tokens, tokens);
    }

    private static MockMultipartFile pack(String id, String name,
                                          Map<String, String> light, Map<String, String> dark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("version", "1.0.0");
        body.put("light", light);
        body.put("dark", dark);
        return new MockMultipartFile("file", id + ".json", "application/json",
                JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8));
    }
}
