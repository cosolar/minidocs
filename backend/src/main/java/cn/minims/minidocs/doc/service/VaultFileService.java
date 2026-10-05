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
import java.nio.file.Files;
import java.nio.file.Path;
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
     * 自定义顺序清单，只在知识库根目录放一份：{@code { 目录相对路径 → 子项名有序数组 }}，根目录用空串。
     *
     * <p>放在库内（而非数据库）是为了随云库的 Git 一起同步；以 {@code .} 开头，扫描时被
     * {@link #isIgnored} 跳过，不会混进目录树。</p>
     */
    private static final String ORDER_FILE = ".minidocs.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> ORDER_MAP = new TypeReference<>() {
    };

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
        String key = root.toString();
        List<DocNode> cached = treeCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<DocNode> tree = scan(root, "", 0, readOrder(root));
        treeCache.put(key, tree);
        return tree;
    }

    /** 文档总数（含子目录，不含 assets 等系统目录）。 */
    public long countDocs(Path root) {
        return countDocs(root, "", 0);
    }

    /** 全部文档相对路径（用于全文搜索 / 校准）。 */
    public List<String> listDocPaths(Path root) {
        List<String> paths = new ArrayList<>();
        collectDocs(root, "", 0, paths, readOrder(root));
        return paths;
    }

    /** 首篇文档（深度优先第一个 Markdown），不存在返回 null。 */
    public String firstDoc(Path root) {
        List<String> paths = listDocPaths(root);
        return paths.isEmpty() ? null : paths.get(0);
    }

    public void invalidateTree(Path root) {
        treeCache.invalidate(root.toString());
    }

    public void invalidateAll() {
        treeCache.invalidateAll();
    }

    // ------------------------------------------------------------------ 内部实现

    private List<DocNode> scan(Path dir, String prefix, int depth, Map<String, List<String>> order) {
        List<DocNode> result = new ArrayList<>();
        if (depth >= properties.getMaxDepth()) {
            return result;
        }
        List<Path> children = listChildren(dir);
        children.sort(childComparator(order.getOrDefault(prefix, List.of())));
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (isIgnored(name)) {
                continue;
            }
            String relative = PathGuard.join(prefix, name);
            if (Files.isDirectory(child)) {
                List<DocNode> sub = scan(child, relative, depth + 1, order);
                result.add(DocNode.dir(name, relative, sub));
            } else if (FileNameUtil.isMarkdown(name)) {
                result.add(DocNode.doc(name, relative, sizeOf(child), modifiedAt(child)));
            }
        }
        return result;
    }

    private long countDocs(Path dir, String prefix, int depth) {
        if (depth >= properties.getMaxDepth()) {
            return 0L;
        }
        long count = 0L;
        for (Path child : listChildren(dir)) {
            String name = child.getFileName().toString();
            if (isIgnored(name)) {
                continue;
            }
            if (Files.isDirectory(child)) {
                count += countDocs(child, PathGuard.join(prefix, name), depth + 1);
            } else if (FileNameUtil.isMarkdown(name)) {
                count++;
            }
        }
        return count;
    }

    private void collectDocs(Path dir, String prefix, int depth, List<String> collector,
                             Map<String, List<String>> order) {
        if (depth >= properties.getMaxDepth()) {
            return;
        }
        List<Path> children = listChildren(dir);
        children.sort(childComparator(order.getOrDefault(prefix, List.of())));
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (isIgnored(name)) {
                continue;
            }
            String relative = PathGuard.join(prefix, name);
            if (Files.isDirectory(child)) {
                collectDocs(child, relative, depth + 1, collector, order);
            } else if (FileNameUtil.isMarkdown(name)) {
                collector.add(relative);
            }
        }
    }

    private List<Path> listChildren(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
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

    // ------------------------------------------------------------------ 顺序清单

    /** 读取顺序清单；缺失或损坏一律当「没有自定义顺序」——坏文件不该让整棵树打不开。 */
    private Map<String, List<String>> readOrder(Path root) {
        Path file = root.resolve(ORDER_FILE);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, List<String>> order =
                    JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), ORDER_MAP);
            return order == null ? new LinkedHashMap<>() : new LinkedHashMap<>(order);
        } catch (Exception e) {
            log.warn("顺序清单解析失败，按默认顺序展示：{} - {}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 写回顺序清单；全部为空时删掉文件，免得留下一份没有内容的清单。 */
    private void writeOrder(Path root, Map<String, List<String>> order) {
        order.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
        Path file = root.resolve(ORDER_FILE);
        try {
            if (order.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(order),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入顺序清单失败", e);
        }
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
        Map<String, List<String>> order = readOrder(root);
        if (order.isEmpty()) {
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
        }
        writeOrder(root, order);
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
