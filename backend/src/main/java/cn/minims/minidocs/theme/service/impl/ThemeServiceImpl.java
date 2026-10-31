package cn.minims.minidocs.theme.service.impl;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.theme.dto.ThemeDtos.ThemePackVO;
import cn.minims.minidocs.theme.model.ThemePack;
import cn.minims.minidocs.theme.service.ThemeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题包存储与校验。
 *
 * <p><b>为什么是 JSON 令牌而不是上传 CSS：</b>主题包的消费方式是把令牌写进一段
 * {@code <style>} 的声明块。如果允许任意 CSS，上传者就能用 {@code url()} 发出带原始
 * Referer 的请求、用 {@code position:fixed} 盖住登录入口，而平台只读场景也从「展示内容」
 * 变成「执行上传者的样式脚本」。令牌白名单是更窄的接口：只能改颜色、字体栈、渐变与尺寸，
 * 不能新增选择器，也碰不到脚本。</p>
 *
 * <p><b>内置主题只种一次：</b>每套内置主题落盘时写一个 {@code .seed-<id>} 标记。
 * 管理员卸载后只删 {@code .json}、保留标记，下次启动就不会「删了又回来」；
 * 想找回走「恢复内置主题」（{@link #reseed()}），那一层会忽略标记强制复制。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeServiceImpl implements ThemeService {

    private static final String CLASS_PATH_THEMES = "classpath*:themes/*.json";
    private static final String SEED_MARKER_PREFIX = ".seed-";
    private static final String DEFAULT_THEME_ID = "default";

    /** 主题包 JSON 上限：五套内置包最大约 6KB，64KB 足够容纳自定义调色板。 */
    private static final long MAX_PACK_BYTES = 64L * 1024;
    private static final int MAX_TOKENS_PER_MODE = 160;
    private static final int MAX_TOKEN_VALUE = 240;

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$");
    private static final Pattern TOKEN_NAME = Pattern.compile("^--md-[a-z0-9-]{1,60}$");

    /**
     * 主题包不能覆盖的令牌：它们是读者的独立设置或页面骨架尺寸。
     *
     * <p>放行 {@code --md-read-font} 的话，读者在「正文字体」里选的字会被主题静默盖掉；
     * 字号、正文宽度、内容宽度同理 —— 界面上海存在两个控件，实际只有主题说了算，比没有控件更糟。</p>
     */
    private static final Set<String> RESERVED_TOKENS = Set.of(
            "--md-read-font",
            "--md-read-scale",
            "--md-read-size",
            "--md-doc-width",
            "--md-content-width",
            "--md-aside-width",
            "--md-outline-width",
            "--md-topbar-height");

    /**
     * 令牌值里不允许出现的东西：能逃出声明块、能发起请求、能提升优先级。
     *
     * <p>正常值包含 {@code color-mix(in srgb, ...)}、{@code linear-gradient(...)}、
     * {@code var(--md-ink-blue)} —— 都不会命中这些模式。</p>
     */
    private static final Pattern UNSAFE_VALUE = Pattern.compile(
            "url\\s*\\(|image-set\\s*\\(|expression\\s*\\(|javascript:|@import|behavior\\s*:"
                    + "|-moz-binding|!important|[;{}<>\\\\]|[\\u0000-\\u001f\\u007f]",
            Pattern.CASE_INSENSITIVE);

    private final MiniDocsProperties properties;

    @PostConstruct
    public void init() {
        try {
            seedBuiltins(false);
        } catch (Exception e) {
            // 种子失败不能拦住整个应用启动：主题接口退化为「没有额外主题」，默认主题仍可用
            log.warn("内置阅读主题初始化失败", e);
        }
    }

    @Override
    public List<ThemePackVO> list() {
        Path dir = themesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ThemePackVO> result = new ArrayList<>();
        Map<String, ThemePackVO> byId = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    if (Files.size(file) > MAX_PACK_BYTES) {
                        log.warn("跳过超限主题包 file={}", file.getFileName());
                        continue;
                    }
                    ThemePack pack = JsonUtil.mapper().readValue(Files.readString(file, StandardCharsets.UTF_8), ThemePack.class);
                    ThemePack normalized = validate(pack, file.getFileName().toString());
                    byId.putIfAbsent(normalized.id(), toVO(normalized, builtinOf(normalized.id())));
                } catch (BizException e) {
                    log.warn("跳过无效主题包 file={} reason={}", file.getFileName(), e.getMessage());
                } catch (Exception e) {
                    log.warn("读取主题包失败 file={}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.warn("读取主题目录失败 dir={}", dir, e);
            return List.of();
        }
        result.addAll(byId.values());
        result.sort(Comparator.comparing(ThemePackVO::builtin).reversed()
                .thenComparing(ThemePackVO::name, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    @Override
    public ThemePackVO install(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.param("请选择主题包文件");
        }
        if (file.getSize() > MAX_PACK_BYTES) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE, "主题包不能超过 64KB");
        }
        ThemePack pack;
        try {
            pack = JsonUtil.mapper().readValue(file.getBytes(), ThemePack.class);
        } catch (IOException e) {
            throw BizException.param("主题包不是有效的 JSON 文件");
        }
        ThemePack normalized = validate(pack, file.getOriginalFilename());
        write(normalized);
        log.info("安装阅读主题 id={} name={}", normalized.id(), normalized.name());
        return toVO(normalized, builtinOf(normalized.id()));
    }

    @Override
    public void uninstall(String id) {
        String normalized = requireId(id);
        if (DEFAULT_THEME_ID.equals(normalized)) {
            throw BizException.param("默认主题不可卸载");
        }
        Path target = themesDir().resolve(normalized + ".json");
        try {
            if (!Files.deleteIfExists(target)) {
                throw BizException.notFound("主题不存在或已卸载");
            }
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "卸载主题失败");
        }
        log.info("卸载阅读主题 id={}", normalized);
    }

    @Override
    public List<ThemePackVO> reseed() {
        seedBuiltins(true);
        return list();
    }

    // ------------------------------------------------------------------ 种子

    /**
     * 把 classpath 里的内置主题复制到站点目录。
     *
     * @param force true = 恢复内置主题，覆盖已存在的同名包；false = 启动时只补缺，
     *              且已有 {@code .seed-<id>} 标记的（被卸载过的）不再补回。
     */
    private void seedBuiltins(boolean force) {
        Path dir = themesDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("创建主题目录失败 dir={}", dir, e);
            return;
        }
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(CLASS_PATH_THEMES);
        } catch (IOException e) {
            log.warn("扫描内置主题失败", e);
            return;
        }
        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            if (fileName == null || !fileName.endsWith(".json")) {
                continue;
            }
            try {
                byte[] raw = resource.getInputStream().readAllBytes();
                ThemePack pack = validate(JsonUtil.mapper().readValue(raw, ThemePack.class), fileName);
                Path marker = dir.resolve(SEED_MARKER_PREFIX + pack.id());
                Path target = dir.resolve(pack.id() + ".json");
                if (!force && Files.exists(marker)) {
                    continue;
                }
                if (!force && Files.exists(target)) {
                    // 管理员自己放过同名包：尊重文件内容，只补标记，不覆盖
                    Files.writeString(marker, "", StandardCharsets.UTF_8);
                    continue;
                }
                writeTo(target, JsonUtil.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(pack));
                Files.writeString(marker, "", StandardCharsets.UTF_8);
                log.info("写入内置阅读主题 id={}", pack.id());
            } catch (Exception e) {
                log.warn("内置主题 {} 无法写入：{}", fileName, e.getMessage(), e);
            }
        }
    }

    private boolean builtinOf(String id) {
        return Files.exists(themesDir().resolve(SEED_MARKER_PREFIX + id));
    }

    // ------------------------------------------------------------------ 校验

    private ThemePack validate(ThemePack pack, String source) {
        if (pack == null) {
            throw BizException.param("主题包内容为空");
        }
        String id = requireId(pack.id());
        if (DEFAULT_THEME_ID.equals(id)) {
            throw BizException.param("default 是内置默认主题的保留 id");
        }
        String name = requiredText(pack.name(), "主题名称", 40);
        String description = optionalText(pack.description(), "主题描述", 200);
        String author = optionalText(pack.author(), "作者", 40);
        String version = optionalText(pack.version(), "版本", 20);
        String license = optionalText(pack.license(), "许可证", 30);
        Map<String, String> light = validateTokens(pack.light(), "light");
        Map<String, String> dark = validateTokens(pack.dark(), "dark");
        if (!light.keySet().equals(dark.keySet())) {
            Set<String> diff = new LinkedHashSet<>(light.keySet());
            diff.removeAll(dark.keySet());
            Set<String> reverse = new LinkedHashSet<>(dark.keySet());
            reverse.removeAll(light.keySet());
            diff.addAll(reverse);
            throw BizException.param("light 与 dark 必须定义同一组令牌，差异: " + String.join(", ", diff));
        }
        return new ThemePack(id, name, description, author, version, license, light, dark);
    }

    private Map<String, String> validateTokens(Map<String, String> raw, String mode) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.param("tokens." + mode + " 不能为空");
        }
        if (raw.size() > MAX_TOKENS_PER_MODE) {
            throw BizException.param("tokens." + mode + " 最多 " + MAX_TOKENS_PER_MODE + " 项");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!TOKEN_NAME.matcher(key).matches()) {
                throw BizException.param("令牌名不合法: " + key);
            }
            if (RESERVED_TOKENS.contains(key)) {
                throw BizException.param("主题包不能覆盖 " + key + "（该值由读者的字体 / 字号 / 宽度设置控制）");
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.isEmpty()) {
                throw BizException.param(key + " 的值为空");
            }
            if (value.length() > MAX_TOKEN_VALUE) {
                throw BizException.param(key + " 的值超过 " + MAX_TOKEN_VALUE + " 个字符");
            }
            if (UNSAFE_VALUE.matcher(value).find()) {
                throw BizException.param(key + " 的值包含不允许的内容");
            }
            result.put(key, value);
        }
        return result;
    }

    private String requireId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw BizException.param("主题 id 只能是小写字母、数字与连字符，长度 2-40");
        }
        return id;
    }

    private String requiredText(String raw, String label, int max) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw BizException.param(label + "不能为空");
        }
        if (value.length() > max) {
            throw BizException.param(label + "不能超过 " + max + " 个字符");
        }
        return value;
    }

    private String optionalText(String raw, String label, int max) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw BizException.param(label + "不能超过 " + max + " 个字符");
        }
        return value;
    }

    // ------------------------------------------------------------------ 磁盘

    private Path themesDir() {
        return properties.siteDir().resolve("themes");
    }

    private void write(ThemePack pack) {
        Path dir = themesDir();
        try {
            Files.createDirectories(dir);
            writeTo(dir.resolve(pack.id() + ".json"),
                    JsonUtil.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(pack));
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "主题包保存失败");
        }
    }

    /** 先写临时文件再原子替换：读侧不会看到写了一半的 JSON。 */
    private void writeTo(Path target, String json) throws IOException {
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, ".theme-", ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private ThemePackVO toVO(ThemePack pack, boolean builtin) {
        return new ThemePackVO(
                pack.id(),
                pack.name(),
                pack.description(),
                pack.author(),
                pack.version(),
                pack.license(),
                pack.light(),
                pack.dark(),
                builtin);
    }
}
