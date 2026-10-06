package cn.minims.minidocs.doc.service;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.text.Collator;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库磁盘文件操作（纯文件系统层，不感知数据库）。
 *
 * <p>定位目录一律使用 {@code storage_key}（{@code o{tenantId}/{slug}}）而非 slug：slug 已降级为
 * 组织内唯一，两个组织可以有同名目录名。标识非法时直接抛出，不回退到 slug —— 回退会把越界标识
 * 静默解析成另一个存在的目录。</p>
 *
 * <p>所有路径均经过 {@link PathGuard} 校验，保证不越出知识库根目录。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultFileService {

    private static final Collator ZH_COLLATOR = Collator.getInstance(Locale.CHINA);

    /**
     * 库级配置，只在知识库根目录放一份。
     *
     * <p>两种格式并存，读的时候自动判别：</p>
     * <pre>
     * 新格式（推荐，显式分区）：
     *   {"order": {"": ["docs", "README.md"], "docs": ["a.md"]}, "hidden": ["drafts", "**&#47;_*"]}
     * 旧格式（历史包袱）：{"": ["docs"], "docs": ["a.md"]}  —— 顶层每个数组键都当顺序
     * </pre>
     *
     * <p>之所以保留旧格式：{@code order} 这个能力早于隐藏规则就已经能用了，直接改成新格式会让
     * 所有已写好的排序配置一夜失效（读到的全是 {@code {"order": …}} 之外的键，顺序全丢）。</p>
     *
     * <p>放在库内（而非数据库）是为了随云库的 Git 一起同步；以 {@code .} 开头，扫描时被
     * {@link #isIgnored} 跳过，不会混进目录树。</p>
     */
    private static final String ORDER_FILE = ".minidocs.json";

    /** 保留键：顺序分区。仅当值是对象时才是新格式，否则按旧格式的「目录路径 → 数组」处理。 */
    private static final String CONFIG_KEY_ORDER = "order";
    /** 保留键：隐藏规则数组。 */
    private static final String CONFIG_KEY_HIDDEN = "hidden";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CONFIG_MAP = new TypeReference<>() {
    };

    /**
     * 库级配置。
     *
     * @param order  目录相对路径 → 该目录下的子项有序数组；根目录用空串
     * @param hidden 隐藏规则；精确路径或 glob，命中即不进入目录树、搜索与计数
     */
    private record KbConfig(Map<String, List<String>> order, List<String> hidden) {

        static KbConfig empty() {
            return new KbConfig(new LinkedHashMap<>(), new ArrayList<>());
        }
    }

    private final MiniDocsProperties properties;

    /** 目录树缓存：key = 知识库根绝对路径 */
    private final Cache<String, List<DocNode>> treeCache = Caffeine.newBuilder()
            .maximumSize(20)
            .build();

    public Path rootOf(String storageKey) {
        StorageKey.validate(storageKey);
        return properties.vaultsDir().resolve(storageKey).toAbsolutePath().normalize();
    }

    public Path ensureRoot(String storageKey) {
        Path root = rootOf(storageKey);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("创建知识库目录失败：" + root, e);
        }
        return root;
    }

    public void deleteRoot(String storageKey) {
        Path root = rootOf(storageKey);
        deleteRecursively(root);
        invalidateTree(root);
    }

    public boolean rootExists(String storageKey) {
        return Files.isDirectory(rootOf(storageKey));
    }

    /** 解析知识库内的绝对路径（越界抛 40001）。 */
    public Path resolve(Path root, String relative) {
        return PathGuard.resolve(root, relative);
    }

    public boolean exists(Path root, String relative) {
        return Files.exists(resolve(root, relative));
    }

    public boolean isDirectory(Path root, String relative) {
        return Files.isDirectory(resolve(root, relative));
    }

    // ------------------------------------------------------------------ 读取

    public String read(Path root, String relative) {
        Path file = resolve(root, relative);
        assertMarkdown(file);
        long size = sizeOf(file);
        if (size > properties.getDocMaxSize()) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                    "文档超过 " + (properties.getDocMaxSize() / 1024 / 1024) + "MB 限制");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.notFound("文档读取失败：" + relative);
        }
    }

    public byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw BizException.notFound("文件读取失败：" + file.getFileName());
        }
    }

    public long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    public LocalDateTime modifiedAt(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 写入

    public void createFile(Path root, String relative, String content) {
        PathGuard.checkDepth(relative, true, properties.getMaxDepth());
        Path file = resolve(root, relative);
        if (Files.exists(file)) {
            throw BizException.exists("文件已存在：" + relative);
        }
        writeFile(file, content);
        invalidateTree(root);
    }

    public void write(Path root, String relative, String content) {
        PathGuard.checkDepth(relative, true, properties.getMaxDepth());
        Path file = resolve(root, relative);
        if (Files.isDirectory(file)) {
            throw BizException.param("目标是一个目录：" + relative);
        }
        if (content != null && content.getBytes(StandardCharsets.UTF_8).length > properties.getDocMaxSize()) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE, "文档内容超过大小限制");
        }
        writeFile(file, content);
        invalidateTree(root);
    }

    public void createDirectory(Path root, String relative) {
        PathGuard.checkDepth(relative, false, properties.getMaxDepth());
        Path dir = resolve(root, relative);
        if (Files.exists(dir)) {
            throw BizException.exists("目录已存在：" + relative);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建目录失败：" + relative, e);
        }
        invalidateTree(root);
    }

    /** 删除文件或目录（目录递归删除）。 */
    public void delete(Path root, String relative) {
        String normalized = PathGuard.normalizeRelative(relative);
        if (normalized.isEmpty()) {
            throw BizException.param("不允许删除知识库根目录");
        }
        Path target = resolve(root, relative);
        if (!Files.exists(target)) {
            throw BizException.notFound("目标不存在：" + relative);
        }
        deleteRecursively(target);
        patchOrder(root, normalized, null);
        invalidateTree(root);
    }

    /** 同级重命名，返回新相对路径。 */
    public String rename(Path root, String relative, String newName) {
        String normalized = PathGuard.normalizeRelative(relative);
        if (normalized.isEmpty()) {
            throw BizException.param("不允许重命名知识库根目录");
        }
        Path source = resolve(root, normalized);
        if (!Files.exists(source)) {
            throw BizException.notFound("目标不存在：" + relative);
        }
        String safeName = FileNameUtil.validateName(newName);
        boolean markdown = Files.isRegularFile(source) && FileNameUtil.isMarkdown(normalized);
        if (markdown) {
            safeName = FileNameUtil.ensureMarkdownExt(safeName);
        }
        String parent = PathGuard.parentOf(normalized);
        String target = PathGuard.join(parent, safeName);
        if (target.equals(normalized)) {
            return normalized;
        }
        PathGuard.checkDepth(target, Files.isRegularFile(source), properties.getMaxDepth());
        moveTo(root, source, resolve(root, target));
        patchOrder(root, normalized, target);
        invalidateTree(root);
        return target;
    }

    /** 移动到目标目录（含根目录），返回新相对路径。 */
    public String move(Path root, String relative, String targetDir) {
        String normalized = PathGuard.normalizeRelative(relative);
        if (normalized.isEmpty()) {
            throw BizException.param("不允许移动知识库根目录");
        }
        Path source = resolve(root, normalized);
        if (!Files.exists(source)) {
            throw BizException.notFound("目标不存在：" + relative);
        }
        String normalizedDir = PathGuard.normalizeRelative(targetDir);
        Path dir = resolve(root, normalizedDir);
        if (!Files.isDirectory(dir)) {
            throw BizException.notFound("目标目录不存在：" + targetDir);
        }
        if (Files.isDirectory(source)) {
            if (normalizedDir.equals(normalized) || normalizedDir.startsWith(normalized + "/")) {
                throw BizException.param("不能将目录移动到其自身或子目录内");
            }
        }
        String name = PathGuard.fileNameOf(normalized);
        String target = PathGuard.join(normalizedDir, name);
        if (target.equals(normalized)) {
            return normalized;
        }
        PathGuard.checkDepth(target, Files.isRegularFile(source), properties.getMaxDepth());
        moveTo(root, source, resolve(root, target));
        patchOrder(root, normalized, target);
        invalidateTree(root);
        return target;
    }

    /**
     * 同目录内自定义排序：把 {@code relative} 排到 {@code targetRelative} 之前/之后。
     *
     * <p>只处理同目录排序。跨目录的意图是「移动」，走 {@link #move}——两者语义不同，
     * 混在一起会让「拖到别的文件夹的某一行」既像移动又像排序。</p>
     *
     * @param position {@code before} 或 {@code after}，其余值按 before 处理
     * @return 顺序是否真的变了
     */
    public boolean reorder(Path root, String relative, String targetRelative, String position) {
        String normalized = PathGuard.normalizeRelative(relative);
        String target = PathGuard.normalizeRelative(targetRelative);
        if (normalized.isEmpty() || target.isEmpty()) {
            throw BizException.param("不允许调整知识库根目录的顺序");
        }
        String dir = PathGuard.parentOf(normalized);
        if (!dir.equals(PathGuard.parentOf(target))) {
            throw BizException.param("只能在同一目录内调整顺序");
        }
        String name = PathGuard.fileNameOf(normalized);
        String targetName = PathGuard.fileNameOf(target);
        if (name.equals(targetName)) {
            return false;
        }
        Map<String, List<String>> order = readOrder(root);
        List<String> current = effectiveOrder(root, dir, order);
        current.remove(name);
        int index = current.indexOf(targetName);
        if (index < 0) {
            // 目标刚被删掉之类：不改清单，让前端刷新后重来
            return false;
        }
        current.add("after".equalsIgnoreCase(position) ? index + 1 : index, name);
        order.put(dir, current);
        writeOrder(root, order);
        invalidateTree(root);
        return true;
    }

    /** 目录下子项当前生效顺序（清单优先，未列出的按默认规则补尾；系统项忽略）。 */
    private List<String> effectiveOrder(Path root, String dir, Map<String, List<String>> order) {
        List<Path> children = listChildren(resolve(root, dir));
        children.sort(childComparator(order.getOrDefault(dir, List.of())));
        List<String> names = new ArrayList<>();
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (!isIgnored(name)) {
                names.add(name);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ 目录树

    /** 目录树（目录在前，按 zh-CN 排序；仅 Markdown 与空目录）。 */
    public List<DocNode> tree(Path root) {
        KbConfig config = readConfig(root);
        // 缓存键带上配置文件的 mtime：作者改完 .minidocs.json（比如新加一条隐藏规则）后，
        // 不该还要等他把另外 19 个库轮一遍才生效。文件不存在时是 0，配置一落地键就变。
        String key = root + "@" + configStamp(root);
        List<DocNode> cached = treeCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<DocNode> tree = scan(root, "", 0, config);
        treeCache.put(key, tree);
        return tree;
    }

    /** 配置文件的时间戳；不存在视为 0。用它做缓存键的一部分，好让改配置立刻生效。 */
    private long configStamp(Path root) {
        try {
            Path file = root.resolve(ORDER_FILE);
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 未过滤的完整树：{@link #isHidden} 的规则一律不生效。
     *
     * <p>只给「库设置」面板用。理由很直接：设置界面要能勾选隐藏项，那就必须<b>看得见</b>已被隐藏的
     * 内容，否则用户只能加规则、没法取消 —— 一个只能单向操作的面板等于把配置变成只写。</p>
     *
     * <p>仍然过滤 {@link #isIgnored} 那批系统目录与隐藏文件（{@code .git}、{@code assets} 等）：
     * 那些不是作者能配置的东西，列出来只会让人以为能勾。</p>
     */
    public List<DocNode> treeIncludingHidden(Path root) {
        String key = root + "@full@" + configStamp(root);
        List<DocNode> cached = treeCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<DocNode> tree = scan(root, "", 0, null);
        treeCache.put(key, tree);
        return tree;
    }

    /**
     * 当前的隐藏规则（相对库根的路径列表）。
     *
     * <p>设置面板用它回填勾选状态。缺省返回空列表而不是 null，前端不必判空。</p>
     */
    public List<String> hiddenRules(Path root) {
        return List.copyOf(readConfig(root).hidden());
    }

    /**
     * 覆盖写隐藏规则。
     *
     * <p><b>整份替换而非增删</b>：面板给出的是勾选后的全集，按差集增量算的话，勾选与取消的顺序
     * 会影响结果，中途刷新一次就可能把别人的规则冲掉。</p>
     *
     * <p>排序清单与隐藏规则写进同一个 {@code .minidocs.json}，这里只动 hidden 分区，
     * 排序部分原样带回（见 {@link #writeConfig}）。</p>
     */
    public void saveHiddenRules(Path root, List<String> rules) {
        writeConfig(root, readConfig(root).order(), rules);
        // 两棵树（过滤 / 未过滤）的缓存键都含配置 mtime，这里失效是兜底：
        // 某些文件系统上 mtime 精度只到秒，同一秒内的两次改动会撞键
        invalidateTree(root);
    }

    /** 文档总数（含子目录，不含 assets 等系统目录与被隐藏的内容）。 */
    public long countDocs(Path root) {
        return countDocs(root, "", 0, readConfig(root));
    }

    /** 全部文档相对路径（用于全文搜索 / 校准；不含被隐藏的内容）。 */
    public List<String> listDocPaths(Path root) {
        List<String> paths = new ArrayList<>();
        collectDocs(root, "", 0, paths, readConfig(root));
        return paths;
    }

    /** 首篇文档（深度优先第一个 Markdown），不存在返回 null。 */
    public String firstDoc(Path root) {
        List<String> paths = listDocPaths(root);
        return paths.isEmpty() ? null : paths.get(0);
    }

    /**
     * 这个相对路径是否被作者配置为隐藏。
     *
     * <p>供<b>按路径直达</b>的入口自查：{@link DocServiceImpl#read}（工作区点开一篇）、
     * {@code ?path=} 深链接、旧的分享链接，这些都不经过目录树，
     * 不会自动被 {@link #tree} 过滤掉——不在这里挡一道，就会出现
     * 「左栏里看不见、点旧链接却打得开」，隐藏规则等于没生效一半。</p>
     */
    public boolean isHiddenPath(Path root, String relativePath) {
        return isHiddenWithAncestors(relativePath, readConfig(root));
    }

    /**
     * 单路径判定：命中自身<b>或它的任一祖先</b>的隐藏规则，都算隐藏。
     *
     * <p>{@link #isHidden} 刻意只做全等比对（见其注释）：扫描是深度优先的，父目录被跳过
     * 就走不到子节点，树 / 文档列表 / 计数三个调用方天然没有漏，不必为它们多付一次前缀比较。
     * 但本方法是<b>第四个调用方，也是唯一没有递归兜底的那个</b> —— 直达入口只判这一个路径。
     * 于是规则写着 {@code drafts} 时，{@code drafts/secret.md} 与它不等比、判定放行，
     * 作者「藏了整个目录」的意图在深链接上完全失效。逐级剥掉尾部补前缀即可补齐，
     * 代价是 O(路径段数) 次字符串比较，相对一次磁盘读可以忽略。</p>
     */
    private boolean isHiddenWithAncestors(String relativePath, KbConfig config) {
        if (isHidden(relativePath, config)) {
            return true;
        }
        String normalized = relativePath.startsWith("./") ? relativePath.substring(2) : relativePath;
        // slash > 0 而不是 >= 0：下标 0 处是斜杠说明路径以分隔符开头（脏数据），
        // 那时 substring(0, 0) 是空串，继续 while 就会原地打转。
        int slash = normalized.lastIndexOf('/');
        while (slash > 0) {
            if (isHidden(normalized.substring(0, slash), config)) {
                return true;
            }
            slash = normalized.lastIndexOf('/', slash - 1);
        }
        return false;
    }

    public void invalidateTree(Path root) {
        treeCache.invalidate(root.toString());
    }

    public void invalidateAll() {
        treeCache.invalidateAll();
    }

    // ------------------------------------------------------------------ 内部实现

    private List<DocNode> scan(Path dir, String prefix, int depth, KbConfig config) {
        List<DocNode> result = new ArrayList<>();
        if (depth >= properties.getMaxDepth()) {
            return result;
        }
        List<Path> children = listChildren(dir);
        Map<String, List<String>> order = config == null ? Map.of() : config.order();
        children.sort(childComparator(order.getOrDefault(prefix, List.of())));
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (isIgnored(name)) {
                continue;
            }
            String relative = PathGuard.join(prefix, name);
            // config 为 null 是「未过滤」模式（设置面板用），此时不套任何作者规则。
            // 隐藏判定放在拼出相对路径之后：规则是相对库根写的（如 "drafts"、"notes/private.md"）
            if (config != null && isHidden(relative, config)) {
                continue;
            }
            if (Files.isDirectory(child)) {
                List<DocNode> sub = scan(child, relative, depth + 1, config);
                result.add(DocNode.dir(name, relative, sub));
            } else if (FileNameUtil.isMarkdown(name)) {
                result.add(DocNode.doc(name, relative, sizeOf(child), modifiedAt(child)));
            } else if (FileNameUtil.isImage(name)) {
                // 图片只进树，不进 countDocs / listDocPaths（那两处仍只收 Markdown）：
                // 否则文档数会算上图片、上/下一篇会试着打开一张二进制、搜索会返回一堆无用命中
                result.add(DocNode.image(name, relative, sizeOf(child), modifiedAt(child)));
            }
        }
        return result;
    }

    private long countDocs(Path dir, String prefix, int depth, KbConfig config) {
        if (depth >= properties.getMaxDepth()) {
            return 0L;
        }
        long count = 0L;
        for (Path child : listChildren(dir)) {
            String name = child.getFileName().toString();
            if (isIgnored(name)) {
                continue;
            }
            String relative = PathGuard.join(prefix, name);
            if (isHidden(relative, config)) {
                continue;
            }
            if (Files.isDirectory(child)) {
                count += countDocs(child, relative, depth + 1, config);
            } else if (FileNameUtil.isMarkdown(name)) {
                count++;
            }
        }
        return count;
    }

    private void collectDocs(Path dir, String prefix, int depth, List<String> collector, KbConfig config) {
        if (depth >= properties.getMaxDepth()) {
            return;
        }
        List<Path> children = listChildren(dir);
        Map<String, List<String>> order = config.order();
        children.sort(childComparator(order.getOrDefault(prefix, List.of())));
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (isIgnored(name)) {
                continue;
            }
            String relative = PathGuard.join(prefix, name);
            // 与 scan / countDocs 同一份判断：不然「目录藏起来了，它的正文却还能被搜到」
            if (isHidden(relative, config)) {
                continue;
            }
            if (Files.isDirectory(child)) {
                collectDocs(child, relative, depth + 1, collector, config);
            } else if (FileNameUtil.isMarkdown(name)) {
                collector.add(relative);
            }
        }
    }

    /**
     * 目录下的子项；目录不存在（或读不了）时返回**可变**空表。
     *
     * <p>刻意不返回 {@code List.of()}：两个调用方会就地 {@code sort()}，而
     * {@code List.of()} 是不可变实现，{@code sort()} 直接抛
     * {@link UnsupportedOperationException}。知识库目录还不存在时就会走到这个空分支 ——
     * 新建库还没传文档、云端库还没同步下来、或磁盘上目录被误删，
     * 此时阅读页本该回一句「内容已删除」，不该是 500。</p>
     */
    private List<Path> listChildren(Path dir) {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(children::add);
        } catch (IOException e) {
            log.warn("读取目录失败：{}", dir, e);
        }
        return children;
    }

    /**
     * 目录内排序：清单里列出的按清单顺序，未列出的排在最后（目录在前、再按 zh-CN）。
     *
     * <p>清单一旦给出就完全作主，不再强制「目录在前」——把某个文件拖到目录上方是用户的明确意图，
     * 再按类型插回去等于拖了没反应。</p>
     */
    private Comparator<Path> childComparator(List<String> order) {
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rank.put(order.get(i), i);
        }
        return (left, right) -> {
            String leftName = left.getFileName().toString();
            String rightName = right.getFileName().toString();
            Integer leftRank = rank.get(leftName);
            Integer rightRank = rank.get(rightName);
            if (leftRank != null && rightRank != null) {
                return Integer.compare(leftRank, rightRank);
            }
            if (leftRank != null) {
                return -1;
            }
            if (rightRank != null) {
                return 1;
            }
            boolean leftDir = Files.isDirectory(left);
            boolean rightDir = Files.isDirectory(right);
            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            }
            return ZH_COLLATOR.compare(leftName, rightName);
        };
    }

    // ------------------------------------------------------------------ 库级配置（排序 + 隐藏）

    /**
     * 读取库级配置；缺失或损坏一律当「没有配置」。
     *
     * <p>坏文件不该让整棵树打不开，更不该让文档凭空消失 —— 所以解析失败只记一条 warn，
     * 然后按「无配置」继续，文档照常全部可见。</p>
     */
    private KbConfig readConfig(Path root) {
        Path file = root.resolve(ORDER_FILE);
        if (!Files.isRegularFile(file)) {
            return KbConfig.empty();
        }
        try {
            Map<String, Object> raw = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), CONFIG_MAP);
            if (raw == null || raw.isEmpty()) {
                return KbConfig.empty();
            }
            Map<String, List<String>> order = new LinkedHashMap<>();
            List<String> hidden = new ArrayList<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                String key = entry.getKey();
                if (CONFIG_KEY_ORDER.equals(key)) {
                    if (entry.getValue() instanceof Map<?, ?> map) {
                        map.forEach((k, v) -> order.put(String.valueOf(k), asStringList(v)));
                    }
                } else if (CONFIG_KEY_HIDDEN.equals(key)) {
                    hidden.addAll(asStringList(entry.getValue()));
                } else if (entry.getValue() instanceof List) {
                    // 旧格式：顶层其余的数组键是「目录相对路径 → 子项有序数组」
                    order.put(key, asStringList(entry.getValue()));
                }
            }
            return new KbConfig(order, hidden);
        } catch (Exception e) {
            log.warn("库级配置解析失败，按默认展示：{} - {}", file, e.getMessage());
            return KbConfig.empty();
        }
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
     * 这个相对路径是否被作者配置为隐藏。
     *
     * <p>规则逐条匹配，支持两种写法：</p>
     * <ul>
     *   <li><b>精确路径</b>：{@code "drafts"}、{@code "notes/private.md"}。目录命中即整棵子树
     *       不再遍历 —— 扫描是逐层递归的，父目录被跳过，里面的东西自然不会出现在任何地方；</li>
     *   <li><b>通配符</b>：{@code "**&#47;_*"}、{@code "drafts/*"}，走 JDK 的 glob 匹配。
     *       判定顺序是「先全部精确、再全部通配」：精确是字符串比较，几乎零成本；
     *       通配要建匹配器，只在精确没命中时才逐条试。</li>
     * </ul>
     *
     * <p><b>刻意不重复判祖先</b>：扫描是深度优先的，父目录一旦被跳过就不会走到子节点，
     * 而树 / 文档列表 / 计数三处共用这一个判断 —— 所以不会出现「目录藏了但里面的文档
     * 还进搜索结果」这种漏。<b>前提是调用方也在递归里</b>：单路径直达入口
     * （{@link #isHiddenPath}）没有这层兜底，得由 {@link #isHiddenWithAncestors} 自己补前缀。</p>
     */
    private boolean isHidden(String relativePath, KbConfig config) {
        List<String> rules = config.hidden();
        if (rules.isEmpty()) {
            return false;
        }
        String normalized = relativePath.startsWith("./") ? relativePath.substring(2) : relativePath;
        for (String rule : rules) {
            if (rule != null && rule.trim().equals(normalized)) {
                return true;
            }
        }
        for (String rule : rules) {
            if (rule == null || rule.isBlank() || rule.indexOf('*') < 0) {
                continue;
            }
            String pattern = rule.trim();
            try {
                if (FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(normalized))) {
                    return true;
                }
            } catch (Exception e) {
                // 非法 glob 只该让这一条规则失效，不该让整棵树打不开
                log.warn("忽略非法的隐藏规则：{}", pattern);
            }
        }
        return false;
    }

    /**
     * 写回配置。
     *
     * <p>顺序与隐藏分区写；两者都空时删文件，免得留下一份没有内容的配置。</p>
     */
    private void writeConfig(Path root, Map<String, List<String>> order, List<String> hidden) {
        order.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
        List<String> rules = new ArrayList<>(hidden);
        rules.removeIf(item -> item == null || item.isBlank());
        Path file = root.resolve(ORDER_FILE);
        try {
            if (order.isEmpty() && rules.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            if (!order.isEmpty()) {
                out.put(CONFIG_KEY_ORDER, order);
            }
            if (!rules.isEmpty()) {
                out.put(CONFIG_KEY_HIDDEN, rules);
            }
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入库级配置失败", e);
        }
    }

    /** 只取顺序部分。 */
    private Map<String, List<String>> readOrder(Path root) {
        return readConfig(root).order();
    }

    /**
     * 只写顺序部分，<b>原样保留已有的隐藏规则</b>。
     *
     * <p>这个保留是必须的：重命名 / 移动 / 排序都会走到写配置，如果顺手按「只有排序」重写，
     * 作者的隐藏清单就会被抹掉 —— 而配置是他自己写在仓库里、还跟着 Git 走的，丢了没法找回。</p>
     */
    private void writeOrder(Path root, Map<String, List<String>> order) {
        writeConfig(root, order, readConfig(root).hidden());
    }

    /**
     * 重命名 / 移动 / 删除后同步顺序清单。
     *
     * <p>不做这步的话，清单里留着旧名（扫描时被忽略），新名不在清单里 → 直接掉到末尾，
     * 用户会看到「改个名，位置就跑了」。目录还要把它自身与整棵子树的 key 一并换前缀。</p>
     *
     * @param newPath 新相对路径；{@code null} 表示删除
     */
    private void patchOrder(Path root, String oldPath, String newPath) {
        KbConfig config = readConfig(root);
        Map<String, List<String>> order = config.order();
        List<String> hidden = new ArrayList<>(config.hidden());
        if (order.isEmpty() && hidden.isEmpty()) {
            return;
        }
        String oldParent = PathGuard.parentOf(oldPath);
        String oldName = PathGuard.fileNameOf(oldPath);
        List<String> siblings = order.get(oldParent);
        if (siblings != null) {
            int index = siblings.indexOf(oldName);
            if (index >= 0) {
                if (newPath != null && PathGuard.parentOf(newPath).equals(oldParent)) {
                    siblings.set(index, PathGuard.fileNameOf(newPath));
                } else {
                    siblings.remove(index);
                }
            }
        }
        if (newPath == null) {
            dropOrderKeys(order, oldPath);
            // 删掉的东西不必继续藏着（留着也无害，但清单会越攒越脏）
            hidden.removeIf(rule -> rule.equals(oldPath) || rule.startsWith(oldPath + "/"));
        } else {
            String newParent = PathGuard.parentOf(newPath);
            if (!newParent.equals(oldParent)) {
                List<String> target = order.computeIfAbsent(newParent, key -> new ArrayList<>());
                String newName = PathGuard.fileNameOf(newPath);
                if (!target.contains(newName)) {
                    target.add(newName);
                }
            }
            rekeyOrder(order, oldPath, newPath);
            // 隐藏规则同步换前缀：作者藏的是「某个东西」，它改名了不该自己冒出来。
            // 只改精确路径那部分；通配规则靠模式匹配，改名后自动跟上。
            for (int i = 0; i < hidden.size(); i++) {
                String rule = hidden.get(i);
                if (rule == null) {
                    continue;
                }
                if (rule.equals(oldPath)) {
                    hidden.set(i, newPath);
                } else if (rule.startsWith(oldPath + "/")) {
                    hidden.set(i, newPath + rule.substring(oldPath.length()));
                }
            }
        }
        writeConfig(root, order, hidden);
    }

    /** 目录改名 / 移动：把清单里以旧路径为前缀的 key 整体换成新路径（含目录自身那份）。 */
    private void rekeyOrder(Map<String, List<String>> order, String oldPath, String newPath) {
        for (String key : new ArrayList<>(order.keySet())) {
            String renamed = null;
            if (key.equals(oldPath)) {
                renamed = newPath;
            } else if (key.startsWith(oldPath + "/")) {
                renamed = newPath + key.substring(oldPath.length());
            }
            if (renamed != null) {
                order.put(renamed, order.remove(key));
            }
        }
    }

    /** 删除：摘掉该路径自身的顺序，以及它作为目录时的整棵子树。 */
    private void dropOrderKeys(Map<String, List<String>> order, String path) {
        order.keySet().removeIf(key -> key.equals(path) || key.startsWith(path + "/"));
    }

    private boolean isIgnored(String name) {
        if (name.startsWith(".")) {
            return true;
        }
        return MiniDocsProperties.SYSTEM_DIRS.contains(name);
    }

    private void assertMarkdown(Path file) {
        if (!Files.isRegularFile(file)) {
            throw BizException.notFound("文档不存在：" + file.getFileName());
        }
        if (!FileNameUtil.isMarkdown(file.getFileName().toString())) {
            throw BizException.of(ErrorCode.FILE_TYPE_NOT_ALLOWED, "仅支持 .md / .markdown 文档");
        }
    }

    private void writeFile(Path file, String content) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败：" + file.getFileName(), e);
        }
    }

    private void moveTo(Path root, Path source, Path target) {
        if (Files.exists(target)) {
            throw BizException.of(ErrorCode.TARGET_EXISTS, "目标已存在同名项：" + target.getFileName());
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(source, target);
        } catch (IOException e) {
            throw new UncheckedIOException("移动失败：" + source.getFileName(), e);
        }
    }

    private void deleteRecursively(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            if (Files.isDirectory(target)) {
                List<Path> children = listChildren(target);
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("删除失败：" + target.getFileName(), e);
        }
    }
}
