package cn.minims.minidocs.doc.service;

import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.kb.entity.KbConfigEntity;
import cn.minims.minidocs.kb.mapper.KbConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库级配置（隐藏规则 / 目录顺序 / 展示偏好）的存储。
 *
 * <p><b>为什么在数据库而不是知识库目录里。</b>配置最初写在库根的
 * {@code .minidocs.json}，目的是「随云库的 Git 一起走」。但它是一个未跟踪文件，
 * 而 Git 清理未跟踪内容的方式不止一种：强制拉取的 {@code reset --hard + clean}、
 * 换仓库时的重新克隆、以及部署者自己执行的 {@code git clean}。平台只能在
 * 「应用内拉取成功」那一条路上做备份还原，别的路径都会把配置删掉；而且那个还原
 * 反过来会覆盖远程的新配置，「随 Git 走」也名不副实。配置只属于当前部署，
 * 放进数据库才是它该待的地方 —— Git 的任何操作都碰不到它。</p>
 *
 * <p>旧部署库根下可能还留着 {@code .minidocs.json}：数据库里没有该库的记录时，
 * 读一次并按原格式导入，此后以数据库为准。文件本身不删除 —— 它可能已被作者
 * 提交进仓库，平台没有理由替人做这个删除决定；但它也不会再被读取或写回。</p>
 *
 * <p>主键用 {@code storage_key}（{@code o{tenantId}/{slug}}）而不是知识库自增 id：
 * 调用方手里只有根目录，而 storage_key 与目录一一对应，且在库的生命周期里不变。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbConfigStore {

    /** 旧版配置文件：只作为一次性迁移来源。 */
    private static final String LEGACY_FILE = ".minidocs.json";

    private static final String CONFIG_KEY_ORDER = "order";
    private static final String CONFIG_KEY_HIDDEN = "hidden";
    private static final String CONFIG_KEY_DISPLAY = "display";
    private static final String CONFIG_KEY_SHOW_MD_SUFFIX = "showMdSuffix";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CONFIG_MAP = new TypeReference<>() {
    };

    /** 没有配置时的缓存标识：与任何一次真实写入的 updatedAt 都不相同。 */
    private static final String NO_STAMP = "0";

    private final KbConfigMapper mapper;
    private final MiniDocsProperties properties;

    /**
     * 配置快照。
     *
     * <p>时间戳供目录树缓存做键：配置一变键就变，不必依赖调用方记得失效缓存
     * （旧实现用的是 {@code .minidocs.json} 的 mtime，语义相同）。</p>
     */
    public record Loaded(KbConfig config, String stamp) {
    }

    /**
     * 库级配置。
     *
     * @param order  目录相对路径 → 该目录下的子项有序数组；根目录用空串
     * @param hidden 隐藏规则；精确路径或 glob，命中即不进入目录树、搜索与计数
     */
    public record KbConfig(Map<String, List<String>> order, List<String> hidden, Display display) {

        static KbConfig empty() {
            return new KbConfig(new LinkedHashMap<>(), new ArrayList<>(), Display.defaults());
        }

        public boolean isDefault() {
            return order.isEmpty() && hidden.isEmpty() && display.showMdSuffix();
        }
    }

    /**
     * 展示偏好。
     *
     * <p>只有「目录树是否显示 {@code .md} 后缀」一项。字段用原始 {@code boolean} 而不是
     * {@code Boolean}：缺省必须是「显示」，若用包装类型就得在每个读取处判 null。</p>
     */
    public record Display(boolean showMdSuffix) {

        static Display defaults() {
            return new Display(true);
        }
    }

    /** 读取配置；数据库没有记录时尝试从旧版文件导入，之后一律以数据库为准。 */
    public Loaded load(Path root) {
        String key = storageKeyOf(root);
        KbConfigEntity row = mapper.selectById(key);
        if (row != null) {
            return new Loaded(parse(row.getConfigJson(), key), stampOf(row));
        }
        KbConfig legacy = readLegacy(root);
        if (legacy.isDefault()) {
            return new Loaded(KbConfig.empty(), NO_STAMP);
        }
        write(root, legacy);
        log.info("已将仓库内 {} 迁移到数据库（{}）", LEGACY_FILE, key);
        KbConfigEntity migrated = mapper.selectById(key);
        return new Loaded(legacy, migrated == null ? NO_STAMP : stampOf(migrated));
    }

    /** 整份写回；三个分区都回到默认值时删除记录，不留空壳。 */
    public void write(Path root, KbConfig config) {
        String key = storageKeyOf(root);
        Map<String, Object> out = toJsonMap(config);
        if (out.isEmpty()) {
            mapper.deleteById(key);
            return;
        }
        KbConfigEntity entity = new KbConfigEntity();
        entity.setStorageKey(key);
        entity.setConfigJson(toJson(out));
        entity.setUpdatedAt(TimeUtil.now());
        // 单实例下 select-then-write 已经够用；仍兜住并发首次写入撞主键的情况
        if (mapper.selectById(key) == null) {
            try {
                mapper.insert(entity);
                return;
            } catch (DuplicateKeyException ignored) {
                // 另一个请求刚插进去，退化为更新
            }
        }
        mapper.updateById(entity);
    }

    /** 知识库删除时清掉它的配置，避免同名新库继承旧规则。 */
    public void delete(String storageKey) {
        if (storageKey != null && !storageKey.isBlank()) {
            mapper.deleteById(storageKey);
        }
    }

    /** 记录 → 缓存戳；时间为空只能是脏数据，按「无配置」处理。 */
    private static String stampOf(KbConfigEntity row) {
        String stamp = TimeUtil.format(row.getUpdatedAt());
        return stamp == null ? NO_STAMP : stamp;
    }

    /** 知识库根目录 → storage_key；只服务于 VAULT_HOME/vaults 下的库。 */
    private String storageKeyOf(Path root) {
        Path vaults = properties.vaultsDir();
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(vaults)) {
            throw new IllegalStateException("库级配置只能用于 VAULT_HOME/vaults 下的知识库：" + root);
        }
        return vaults.relativize(normalized).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ 解析与序列化

    /**
     * 解析配置 JSON。
     *
     * <p>坏内容不该让整棵树打不开，更不该让文档凭空消失 —— 解析失败只记一条 warn，
     * 然后按「无配置」继续，文档照常全部可见。</p>
     */
    private static KbConfig parse(String json, String key) {
        if (json == null || json.isBlank()) {
            return KbConfig.empty();
        }
        try {
            return fromJsonMap(JSON.readValue(json, CONFIG_MAP));
        } catch (Exception e) {
            log.warn("库级配置解析失败，按默认展示：{} - {}", key, e.getMessage());
            return KbConfig.empty();
        }
    }

    /**
     * 旧版 {@code .minidocs.json} 的读取。
     *
     * <p>格式与数据库里那份完全一致（数据库只是换了存放位置），所以共用
     * {@link #fromJsonMap(Map)}；文件不存在、读不动或内容为空都返回「无配置」。</p>
     */
    private static KbConfig readLegacy(Path root) {
        Path file = root.resolve(LEGACY_FILE);
        if (!Files.isRegularFile(file)) {
            return KbConfig.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return KbConfig.empty();
            }
            return fromJsonMap(JSON.readValue(text, CONFIG_MAP));
        } catch (Exception e) {
            log.warn("旧版库级配置解析失败，按默认展示：{} - {}", file, e.getMessage());
            return KbConfig.empty();
        }
    }

    /**
     * JSON 对象 → 配置。
     *
     * <p>兼容两种格式：显式分区的 {@code order/hidden/display}，以及旧格式
     * （顶层每个数组键都当「目录路径 → 有序数组」）。旧格式来自 {@code order}
     * 这个能力早于隐藏规则的年代，直接不认会让所有已写好的排序一夜失效。</p>
     */
    private static KbConfig fromJsonMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return KbConfig.empty();
        }
        Map<String, List<String>> order = new LinkedHashMap<>();
        List<String> hidden = new ArrayList<>();
        Display display = Display.defaults();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (CONFIG_KEY_ORDER.equals(key)) {
                if (entry.getValue() instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> order.put(String.valueOf(k), asStringList(v)));
                }
            } else if (CONFIG_KEY_HIDDEN.equals(key)) {
                hidden.addAll(asStringList(entry.getValue()));
            } else if (CONFIG_KEY_DISPLAY.equals(key)) {
                display = parseDisplay(entry.getValue());
            } else if (entry.getValue() instanceof List) {
                order.put(key, asStringList(entry.getValue()));
            }
        }
        return new KbConfig(order, hidden, display);
    }

    /**
     * 解析展示偏好分区。
     *
     * <p>只有<b>显式的 false</b> 才算关掉：老仓库里没有这个键，或者值类型被手改坏了，
     * 一律按「显示后缀」处理。反过来（任何非 false 都当成关掉）会让一份损坏的配置
     * 静默把所有文档的后缀藏起来，而用户完全不知道为什么。</p>
     */
    private static Display parseDisplay(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Display.defaults();
        }
        Object flag = map.get(CONFIG_KEY_SHOW_MD_SUFFIX);
        if (flag == null) {
            return Display.defaults();
        }
        if (flag instanceof Boolean b) {
            return new Display(b);
        }
        return new Display(!"false".equalsIgnoreCase(String.valueOf(flag).trim()));
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * 配置 → 待落库的 JSON 对象。
     *
     * <p>空分区一律不写：默认值落进库会让每行都带着与行为无关的配置。
     * 复制一份再清理 —— 调用方传进来的可能是 {@code load()} 刚返回的对象，
     * 就地改它会连带影响同一流程里后续的读。</p>
     */
    private static Map<String, Object> toJsonMap(KbConfig config) {
        Map<String, List<String>> order = new LinkedHashMap<>(config.order());
        order.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
        List<String> rules = new ArrayList<>(config.hidden());
        rules.removeIf(item -> item == null || item.isBlank());

        Map<String, Object> out = new LinkedHashMap<>();
        if (!order.isEmpty()) {
            out.put(CONFIG_KEY_ORDER, order);
        }
        if (!rules.isEmpty()) {
            out.put(CONFIG_KEY_HIDDEN, rules);
        }
        if (!config.display().showMdSuffix()) {
            out.put(CONFIG_KEY_DISPLAY, Map.of(CONFIG_KEY_SHOW_MD_SUFFIX, false));
        }
        return out;
    }

    private static String toJson(Map<String, Object> config) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("序列化库级配置失败", e);
        }
    }
}
