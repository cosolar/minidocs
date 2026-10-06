package cn.minims.minidocs.doc.service.impl;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.event.DocumentChangedEvent;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.dto.DocDtos.DocContentVO;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.doc.dto.DocDtos.DownloadPayload;
import cn.minims.minidocs.doc.dto.DocDtos.ImportResult;
import cn.minims.minidocs.doc.dto.DocDtos.PathChangeVO;
import cn.minims.minidocs.doc.service.DocLockService;
import cn.minims.minidocs.doc.service.DocService;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.doc.support.ImageValidator;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.markdown.MarkdownService;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderContext;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderResult;
import cn.minims.minidocs.markdown.model.MarkdownModels.Variant;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocServiceImpl implements DocService {

    private static final String ASSETS_DIR = "assets";
    private static final String COVER_PREFIX = "cover-";
    private static final int MAX_MESSAGES = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> PATH_LIST = new TypeReference<>() {
    };

    private final KnowledgeBaseService knowledgeBaseService;
    private final AccessService accessService;
    private final VaultFileService vaultFileService;
    private final DocLockService docLockService;
    private final MarkdownService markdownService;
    private final ImageValidator imageValidator;
    private final MiniDocsProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    // ------------------------------------------------------------------ 读取

    @Override
    public List<DocNode> tree(Long kbId, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_READ, user);
        return vaultFileService.tree(vaultFileService.rootOf(kb.getStorageKey()));
    }

    @Override
    public DocContentVO read(Long kbId, String path, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_READ, user);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        String normalized = PathGuard.normalizeRelative(path);
        // 按路径直达的入口也要过隐藏规则：这条路径不经过目录树，
        // 所以「左栏看不见、?path= 或旧分享链接却打得开」是最容易漏的一处。
        // 报 404 而不是 403：隐藏项与「不存在」在界面上无法区分，
        // 而且报 403 等于承认「这里有个东西，只是不让你看」。
        if (vaultFileService.isHiddenPath(root, normalized)) {
            throw BizException.notFound("文档不存在");
        }
        Path file = vaultFileService.resolve(root, normalized);
        String content = vaultFileService.read(root, normalized);
        return new DocContentVO(normalized, PathGuard.fileNameOf(normalized),
                FileNameUtil.stripMarkdownExt(PathGuard.fileNameOf(normalized)),
                content, vaultFileService.sizeOf(file), vaultFileService.modifiedAt(file));
    }

    @Override
    public RenderResult render(Long kbId, String path, LoginUser user, Variant variant, RenderContext context) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_READ, user);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        return markdownService.render(root, kbId, PathGuard.normalizeRelative(path), variant, context);
    }

    // ------------------------------------------------------------------ 写入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PathChangeVO createDoc(Long kbId, String path, String content, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_WRITE, user);
        Path root = vaultFileService.ensureRoot(kb.getStorageKey());
        String raw = PathGuard.normalizeRelative(path);
        if (raw.isEmpty()) {
            throw BizException.param("请提供文档路径");
        }
        String name = FileNameUtil.validateName(PathGuard.fileNameOf(raw));
        String parent = PathGuard.parentOf(raw);
        String normalized = PathGuard.join(parent, FileNameUtil.ensureMarkdownExt(name));
        String body = (content == null || content.isBlank())
                ? "# " + FileNameUtil.stripMarkdownExt(name)
                : content;
        vaultFileService.createFile(root, normalized, body);
        reload(kbId, normalized);
        return new PathChangeVO(normalized, PathGuard.fileNameOf(normalized));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PathChangeVO createDirectory(Long kbId, String path, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_WRITE, user);
        Path root = vaultFileService.ensureRoot(kb.getStorageKey());
        String raw = PathGuard.normalizeRelative(path);
        if (raw.isEmpty()) {
            throw BizException.param("请提供目录路径");
        }
        String name = FileNameUtil.validateName(PathGuard.fileNameOf(raw));
        String normalized = PathGuard.join(PathGuard.parentOf(raw), name);
        vaultFileService.createDirectory(root, normalized);
        knowledgeBaseService.touch(kbId);
        return new PathChangeVO(normalized, name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PathChangeVO saveDoc(Long kbId, String path, String content, Long baseSize,
                               LocalDateTime baseMtime, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_WRITE, user);
        Path root = vaultFileService.ensureRoot(kb.getStorageKey());
        String normalized = PathGuard.normalizeRelative(path);
        docLockService.requireHeldBy(kbId, normalized, user);
        requireBaseline(root, normalized, baseSize, baseMtime);
        vaultFileService.write(root, normalized, content == null ? "" : content);
        reload(kbId, normalized);
        return new PathChangeVO(normalized, PathGuard.fileNameOf(normalized));
    }

    /**
     * 保存基线比对（规范 §4.3，412）。冲突只提示不合并：412 的响应体带回磁盘当前的 size 与
     * modifiedAt，编辑器据此决定「重新载入」还是「复制我的内容」。
     *
     * <p>时间比到秒为止：客户端手里那份是从 JSON 回来的，纳秒位不该成为拒绝保存的理由。</p>
     */
    private void requireBaseline(Path root, String path, Long baseSize, LocalDateTime baseMtime) {
        if (baseSize == null || baseMtime == null) {
            throw BizException.of(ErrorCode.STALE_WRITE, "缺少保存基线，请重新载入后再保存")
                    .payload(baselineOf(root, path));
        }
        Path file = vaultFileService.resolve(root, path);
        if (!Files.isRegularFile(file)) {
            throw BizException.of(ErrorCode.STALE_WRITE, "文档已被他人删除").payload(Map.of("missing", true));
        }
        long size = vaultFileService.sizeOf(file);
        LocalDateTime modifiedAt = vaultFileService.modifiedAt(file);
        if (modifiedAt == null) {
            // mtime 读不出来就没法验基线，而「验不了」绝不能当成「验过了」：给 412 让人重新载入，
            // 而不是让下面那句比较拿 null 去 truncatedTo 变成一个 500
            throw BizException.of(ErrorCode.STALE_WRITE, "无法确认文档的当前状态，请重新载入后再保存")
                    .payload(Map.of("baseSize", size, "unreadable", true));
        }
        if (size != baseSize || !sameToTheSecond(modifiedAt, baseMtime)) {
            throw BizException.of(ErrorCode.STALE_WRITE, "文档已被他人修改，请先载入最新内容")
                    .payload(Map.of("baseSize", size, "baseMtime", modifiedAt));
        }
    }

    private Map<String, Object> baselineOf(Path root, String path) {
        Path file = vaultFileService.resolve(root, path);
        if (!Files.isRegularFile(file)) {
            return Map.of("missing", true);
        }
        LocalDateTime modifiedAt = vaultFileService.modifiedAt(file);
        if (modifiedAt == null) {
            // Map.of 不接受 null，缺 mtime 时给「拿不到基线」比给一条半截记录更诚实
            return Map.of("missing", true);
        }
        return Map.of("baseSize", vaultFileService.sizeOf(file), "baseMtime", modifiedAt);
    }

    private static boolean sameToTheSecond(LocalDateTime left, LocalDateTime right) {
        return left.truncatedTo(ChronoUnit.SECONDS).equals(right.truncatedTo(ChronoUnit.SECONDS));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long kbId, String path, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_DELETE, user);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        String normalized = PathGuard.normalizeRelative(path);
        vaultFileService.delete(root, normalized);
        markdownService.invalidateKnowledgeBase(kbId);
        knowledgeBaseService.touch(kbId);
        eventPublisher.publishEvent(DocumentChangedEvent.deleted(kbId, normalized));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PathChangeVO rename(Long kbId, String path, String name, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_RENAME, user);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        String normalized = PathGuard.normalizeRelative(path);
        String newPath = vaultFileService.rename(root, normalized, name);
        markdownService.invalidateKnowledgeBase(kbId);
        knowledgeBaseService.touch(kbId);
        eventPublisher.publishEvent(DocumentChangedEvent.renamed(kbId, normalized, newPath));
        return new PathChangeVO(newPath, PathGuard.fileNameOf(newPath));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PathChangeVO move(Long kbId, String path, String targetDir, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_MOVE, user);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        String normalized = PathGuard.normalizeRelative(path);
        String newPath = vaultFileService.move(root, normalized, targetDir);
        markdownService.invalidateKnowledgeBase(kbId);
        knowledgeBaseService.touch(kbId);
        eventPublisher.publishEvent(DocumentChangedEvent.moved(kbId, normalized, newPath));
        return new PathChangeVO(newPath, PathGuard.fileNameOf(newPath));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorder(Long kbId, String path, String targetPath, String position, LoginUser user) {
        // 排序是「整理目录结构」，与移动同轴；没有 DOC_MOVE 的人也不该改顺序
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_MOVE, user);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        if (vaultFileService.reorder(root, path, targetPath, position)) {
            knowledgeBaseService.touch(kbId);
        }
    }

    // ------------------------------------------------------------------ 资源

    @Override
    public String uploadImage(Long kbId, MultipartFile file, LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_UPLOAD_ASSET, user);
        Path root = vaultFileService.ensureRoot(kb.getStorageKey());
        String extension = imageValidator.validate(file, properties.getImageMaxSize());
        String fileName = FileNameUtil.buildImageName(extension);
        writeAsset(root, fileName, file, extension);
        return ASSETS_DIR + "/" + fileName;
    }

    @Override
    public String uploadCover(Long kbId, MultipartFile file, LoginUser user) {
        // 封面写进 knowledge_base.cover_url，改的是库元信息，故门在 KB_EDIT_META 而非资源上传
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.KB_EDIT_META, user);
        Path root = vaultFileService.ensureRoot(kb.getStorageKey());
        String extension = imageValidator.validate(file, properties.getCoverMaxSize());
        String fileName = COVER_PREFIX + FileNameUtil.buildImageName(extension);
        writeAsset(root, fileName, file, extension);
        String relative = ASSETS_DIR + "/" + fileName;

        KnowledgeBase update = new KnowledgeBase();
        update.setId(kbId);
        update.setCoverUrl(relative);
        knowledgeBaseService.updateById(update);
        return relative;
    }

    @Override
    public DownloadPayload download(Long kbId, String path, LoginUser user) {
        // 下载只是把原文再读一遍，v1 误挂在写门上；v2 归回读轴（规范 §2.2）
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_READ, user);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        String normalized = PathGuard.normalizeRelative(path);
        if (!FileNameUtil.isMarkdown(normalized)) {
            throw BizException.of(ErrorCode.FILE_TYPE_NOT_ALLOWED, "仅支持下载 Markdown 文档");
        }
        Path file = PathGuard.resolve(root, normalized);
        if (!Files.isRegularFile(file)) {
            throw BizException.notFound("文档不存在：" + normalized);
        }
        return new DownloadPayload(new FileSystemResource(file), PathGuard.fileNameOf(normalized));
    }

    // ------------------------------------------------------------------ 导入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importFiles(Long kbId, String targetDir, MultipartFile[] files, String relativePathsJson,
                                    LoginUser user) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_IMPORT, user);
        Path root = vaultFileService.ensureRoot(kb.getStorageKey());
        String baseDir = PathGuard.normalizeRelative(targetDir);
        List<String> relativePaths = parseRelativePaths(relativePathsJson);

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<String> messages = new ArrayList<>();
        long totalSize = 0L;
        int totalFiles = 0;

        MultipartFile[] incoming = files == null ? new MultipartFile[0] : files;
        for (int index = 0; index < incoming.length; index++) {
            MultipartFile file = incoming[index];
            if (file == null || file.isEmpty()) {
                continue;
            }
            totalSize += file.getSize();
            if (totalSize > properties.getImportMaxSize()) {
                throw BizException.of(ErrorCode.FILE_TOO_LARGE, "导入内容超过 50MB 限制");
            }
            String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            /*
             * 有的客户端会把整段相对路径塞进 filename（形如 Java/面试题库/a.md），而 / 在
             * validateName 里属于非法字符。这里拆出目录与基名：目录当子目录用，只校验基名，
             * 否则「选择目录导入」会整批失败。
             */
            String rawName = originalName.replace('\\', '/').trim();
            int slash = rawName.lastIndexOf('/');
            String baseName = slash < 0 ? rawName : rawName.substring(slash + 1);
            String extension = FileNameUtil.extensionOf(baseName);
            // 目录导入时这一项自带的子目录（相对选择器根目录）；选文件导入时为空串
            String subDir = subDirOf(relativePaths, index);
            if (subDir.isEmpty() && slash >= 0) {
                // paths 没给出目录时，用 filename 里那段相对路径兜底还原
                subDir = sanitizeDirPath(rawName.substring(0, slash));
            }
            if ("zip".equals(extension)) {
                Counter counter = new Counter();
                importZip(root, PathGuard.join(baseDir, subDir), file, counter, messages, totalFiles);
                imported += counter.imported;
                skipped += counter.skipped;
                failed += counter.failed;
                totalFiles += counter.total;
            } else if (FileNameUtil.isMarkdown(baseName)) {
                totalFiles++;
                if (totalFiles > properties.getImportMaxFiles()) {
                    throw BizException.param("单次导入文件数不能超过 " + properties.getImportMaxFiles() + " 个");
                }
                try {
                    String name = FileNameUtil.validateName(baseName);
                    String target = PathGuard.join(baseDir, PathGuard.join(subDir, FileNameUtil.ensureMarkdownExt(name)));
                    vaultFileService.write(root, target, new String(file.getBytes(), StandardCharsets.UTF_8));
                    imported++;
                } catch (Exception e) {
                    failed++;
                    addMessage(messages, "导入失败：" + originalName + " - " + e.getMessage());
                }
            } else {
                skipped++;
                addMessage(messages, "已跳过不支持的文件：" + originalName);
            }
        }

        if (imported > 0) {
            markdownService.invalidateKnowledgeBase(kbId);
            knowledgeBaseService.touch(kbId);
        }
        return new ImportResult(imported, skipped, failed, messages);
    }

    /**
     * 解析与 files 同序的相对路径数组。
     *
     * <p>解析失败不当错误处理：这一项只是用来还原子目录树，退回按文件名平铺照样能把文件导进来，
     * 没必要让整次导入因为一个辅助参数而失败。</p>
     */
    private List<String> parseRelativePaths(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> paths = JSON.readValue(json, PATH_LIST);
            return paths == null ? List.of() : paths;
        } catch (Exception e) {
            log.warn("导入相对路径参数解析失败，退回按文件名平铺：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 取第 {@code index} 个文件所属的子目录（相对选择器根目录）。
     */
    private String subDirOf(List<String> relativePaths, int index) {
        if (index >= relativePaths.size()) {
            return "";
        }
        String raw = relativePaths.get(index);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized;
        try {
            normalized = PathGuard.normalizeRelative(raw);
        } catch (Exception e) {
            log.warn("导入相对路径不可用，退回平铺：{} - {}", raw, e.getMessage());
            return "";
        }
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : sanitizeDirPath(normalized.substring(0, slash));
    }

    /**
     * 清洗一段相对目录：逐段过 {@code validateName} 挡掉 Windows 非法字符，再交给 {@code PathGuard}
     * 挡掉 {@code ..} 与绝对路径。任何一段不合法就整体放弃（返回空串）—— 退回平铺，总比整次导入失败强。
     */
    private String sanitizeDirPath(String rawDir) {
        if (rawDir == null || rawDir.isBlank()) {
            return "";
        }
        try {
            String normalized = PathGuard.normalizeRelative(rawDir);
            List<String> segments = new ArrayList<>();
            for (String segment : normalized.split("/")) {
                if (!segment.isEmpty()) {
                    segments.add(FileNameUtil.validateName(segment));
                }
            }
            return String.join("/", segments);
        } catch (Exception e) {
            log.warn("导入目录路径不可用，退回平铺：{} - {}", rawDir, e.getMessage());
            return "";
        }
    }

    private void importZip(Path root, String baseDir, MultipartFile file, Counter counter,
                           List<String> messages, int alreadyCounted) {
        String wrapper = zipWrapperDir(file);
        int ignored = 0;
        try (InputStream inputStream = file.getInputStream();
             ZipInputStream zip = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = normalizeZipEntry(entry.getName());
                if (entryName == null) {
                    counter.skipped++;
                    addMessage(messages, "已跳过非法路径：" + entry.getName());
                    continue;
                }
                if (!FileNameUtil.isMarkdown(entryName)) {
                    counter.skipped++;
                    ignored++;
                    continue;
                }
                counter.total++;
                if (alreadyCounted + counter.total > properties.getImportMaxFiles()) {
                    throw BizException.param("单次导入文件数不能超过 " + properties.getImportMaxFiles() + " 个");
                }
                try {
                    String target = PathGuard.join(baseDir, stripZipWrapper(entryName, wrapper));
                    byte[] bytes = zip.readAllBytes();
                    vaultFileService.write(root, target, new String(bytes, StandardCharsets.UTF_8));
                    counter.imported++;
                } catch (Exception e) {
                    counter.failed++;
                    addMessage(messages, "导入失败：" + entryName + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            counter.failed++;
            addMessage(messages, "压缩包解析失败：" + file.getOriginalFilename());
        }
        if (ignored > 0) {
            addMessage(messages, "压缩包内 " + ignored + " 个非 Markdown 文件已跳过（图片等资源不会导入）");
        }
    }

    /**
     * 压缩包那层「外壳文件夹」的名字；没有外壳时返回空串。
     *
     * <p>「压缩成 zip」打出来的包，条目一律带外层目录名（面试手册/a.md）。选目录导入时前端已经
     * 把这层剥掉了（见 {@code relativeOf}），压缩包得跟上，否则同一个文件夹打包后导入会凭空多一级。</p>
     *
     * <p>判定看全部文件条目：出现一个落在根上的条目，说明压的是「文件夹里的内容」；首段不止一种，
     * 说明本来就没有统一外壳 —— 这两种情况都原样保留路径。</p>
     */
    private String zipWrapperDir(MultipartFile file) {
        Set<String> roots = new LinkedHashSet<>();
        try (InputStream inputStream = file.getInputStream();
             ZipInputStream zip = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = normalizeZipEntry(entry.getName());
                if (entryName == null) {
                    continue;
                }
                int slash = entryName.indexOf('/');
                if (slash < 0) {
                    return "";
                }
                roots.add(entryName.substring(0, slash));
                if (roots.size() > 1) {
                    return "";
                }
            }
        } catch (IOException e) {
            log.warn("读取压缩包目录结构失败，按原路径导入：{} - {}", file.getOriginalFilename(), e.getMessage());
            return "";
        }
        return roots.size() == 1 ? roots.iterator().next() : "";
    }

    private String stripZipWrapper(String entryName, String wrapper) {
        if (wrapper.isEmpty()) {
            return entryName;
        }
        String prefix = wrapper + "/";
        return entryName.startsWith(prefix) ? entryName.substring(prefix.length()) : entryName;
    }

    private String normalizeZipEntry(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String value = rawName.replace('\\', '/').trim();
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || PathGuard.containsControlChar(segment)) {
                return null;
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return null;
        }
        String joined = String.join("/", segments);
        try {
            PathGuard.checkDepth(joined, true, properties.getMaxDepth());
        } catch (BizException e) {
            return null;
        }
        return joined;
    }

    // ------------------------------------------------------------------ 内部实现

    private void writeAsset(Path root, String fileName, MultipartFile file, String extension) {
        Path assetsDir = vaultFileService.resolve(root, ASSETS_DIR);
        Path target = assetsDir.resolve(fileName);
        try {
            Files.createDirectories(assetsDir);
            if ("svg".equals(extension)) {
                Files.write(target, imageValidator.sanitizeSvg(file.getBytes()));
            } else {
                file.transferTo(target);
            }
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "图片保存失败");
        }
    }

    private void reload(Long kbId, String docPath) {
        markdownService.invalidateDocument(kbId, docPath);
        knowledgeBaseService.touch(kbId);
    }

    private void addMessage(List<String> messages, String message) {
        if (messages.size() < MAX_MESSAGES) {
            messages.add(message);
        }
    }

    private static final class Counter {
        private int imported;
        private int skipped;
        private int failed;
        private int total;
    }
}
