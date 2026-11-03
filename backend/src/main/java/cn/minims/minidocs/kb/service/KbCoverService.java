package cn.minims.minidocs.kb.service;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.event.KnowledgeBaseDeletedEvent;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.doc.support.ImageValidator;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.support.CoverAssets;
import cn.minims.minidocs.reader.service.AssetService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 知识库封面：落盘、替换、清理与读取。
 *
 * <p>封面是<b>平台元数据</b>，不是知识库内容：文件存在 {@code VAULT_HOME/platform/covers}，
 * 不写进工作副本。库表 {@code knowledge_base.cover_url} 只记文件名（或外链），
 * 因此 Git 拉取、强制覆盖、整目录清理都不会再碰到封面 —— 不会再出现「更新 Git 后封面消失」。</p>
 *
 * <p>文件名形如 {@code k{kbId}-{时间戳}{随机}.{扩展名}}：前缀用于库删除时清理该库的封面，
 * 时间戳让 URL 随内容变化，浏览器不必依赖缓存失效。删除库时如果先删文件后回滚事务，
 * 库还在但封面没了，所以清理挂在 {@link KnowledgeBaseDeletedEvent} 的提交之后。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbCoverService {

    private final MiniDocsProperties properties;
    private final ImageValidator imageValidator;
    private final AssetService assetService;
    private final VaultFileService vaultFileService;
    private final KnowledgeBaseService knowledgeBaseService;

    /** 上传新封面并写回文件名。 */
    public String save(KnowledgeBase kb, MultipartFile file) {
        String extension = imageValidator.validate(file, properties.getCoverMaxSize());
        String fileName = fileNameOf(kb.getId(), FileNameUtil.buildImageName(extension));
        write(properties.coversDir().resolve(fileName), file, extension);
        // 先写新图、再改库，最后删旧图：中途失败最坏是留下一个孤儿文件，不会让封面变空
        updateColumn(kb.getId(), fileName);
        deletePrevious(kb);
        return fileName;
    }

    /** 设置外链封面；旧的本地上传图随后清掉。 */
    public String setExternal(KnowledgeBase kb, String url) {
        updateColumn(kb.getId(), url);
        deletePrevious(kb);
        return url;
    }

    /** 清空封面：先把列置空，再删文件；删失败只是占点磁盘，不会让页面指向一个不存在的文件。 */
    public void clear(KnowledgeBase kb) {
        updateColumn(kb.getId(), null);
        deletePrevious(kb);
    }

    /**
     * 读取封面。
     *
     * <p>外链不该走到这里（调用方会直接使用外链地址）；旧版库内路径保留一段兜底，
     * 覆盖「迁移还没跑到」与「旧值指向的文件仍在库内」两种情况。</p>
     */
    public AssetService.Asset load(KnowledgeBase kb) {
        String cover = kb.getCoverUrl();
        if (CoverAssets.isStoredName(cover)) {
            return assetService.load(properties.coversDir(), cover);
        }
        if (cover != null && !CoverAssets.isExternal(cover) && cover.indexOf('/') >= 0) {
            return assetService.load(vaultFileService.rootOf(kb.getStorageKey()), cover);
        }
        throw BizException.notFound("封面不存在");
    }

    /**
     * 把旧版库内封面复制到平台目录，返回新文件名；旧文件由调用方在数据库更新成功后再删。
     *
     * <p>只复制不移动：如果复制后数据库没更新成功，旧路径仍然有效，下次启动还能重试。</p>
     */
    public String adoptLegacy(Long kbId, Path legacy) {
        String fileName = fileNameOf(kbId, legacy.getFileName().toString());
        Path target = properties.coversDir().resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.copy(legacy, target);
            }
            return fileName;
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "迁移封面失败：" + fileName);
        }
    }

    /**
     * 删库时清理封面，排在事务提交之后。
     *
     * <p>直接监听事件就删文件的话，purge 后续任何一步失败、事务回滚，库还在而封面已经没了。
     * 所以有事务时登记 afterCommit，没有事务（例如手工维护脚本）才当场删。</p>
     */
    @EventListener
    public void onKnowledgeBaseDeleted(KnowledgeBaseDeletedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteAllForKb(event.kbId());
                }
            });
            return;
        }
        deleteAllForKb(event.kbId());
    }

    /** 删掉该库名下的全部封面文件（含历史上换封面留下的孤儿）。 */
    public void deleteAllForKb(Long kbId) {
        Path dir = properties.coversDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = fileNameOf(kbId, "");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> stale = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .toList();
            for (Path path : stale) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // 文件没删掉只是占点磁盘，不该让删库失败
            log.warn("清理知识库封面失败 kbId={}", kbId, e);
        }
    }

    private String fileNameOf(Long kbId, String suffix) {
        // 尾部的 '-' 让 k1- 不会误伤 k11- 的文件
        return "k" + kbId + "-" + suffix;
    }

    private void write(Path target, MultipartFile file, String extension) {
        try {
            Files.createDirectories(target.getParent());
            byte[] content = file.getBytes();
            if ("svg".equals(extension)) {
                content = imageValidator.sanitizeSvg(content);
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "封面保存失败");
        }
    }

    /** 删除当前封面文件：平台文件直接删；旧版库内文件按 storageKey 定位后删。外链不动。 */
    private void deletePrevious(KnowledgeBase kb) {
        String coverUrl = kb.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank() || CoverAssets.isExternal(coverUrl)) {
            return;
        }
        try {
            Path file = CoverAssets.isStoredName(coverUrl)
                    ? PathGuard.resolve(properties.coversDir(), coverUrl)
                    : PathGuard.resolve(vaultFileService.rootOf(kb.getStorageKey()), coverUrl);
            Files.deleteIfExists(file);
        } catch (Exception e) {
            // 旧文件没删掉只是占点磁盘，不该让换封面 / 清空封面失败
            log.warn("清理旧封面失败 kbId={} file={}", kb.getId(), coverUrl, e);
        }
    }

    private void updateColumn(Long kbId, String coverUrl) {
        // 必须用显式 set：MyBatis-Plus 的 updateById 默认忽略 null，清空封面会静默失败
        knowledgeBaseService.update(Wrappers.<KnowledgeBase>lambdaUpdate()
                .eq(KnowledgeBase::getId, kbId)
                .set(KnowledgeBase::getCoverUrl, coverUrl));
    }
}
