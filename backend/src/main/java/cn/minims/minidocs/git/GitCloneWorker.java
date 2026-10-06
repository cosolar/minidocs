package cn.minims.minidocs.git;

import cn.minims.minidocs.common.util.CryptoUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.AsyncConfig;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KnowledgeBaseMapper;
import cn.minims.minidocs.doc.service.VaultFileService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 建库后的后台克隆。
 *
 * <p><b>为什么不能同步克隆</b>：{@code create()} 上挂着 {@code @Transactional}，克隆一次要几十秒到
 * 两分钟（JGit 侧超时 120s）。同步做意味着这段时间里数据库连接和行锁一直被占着，而用户的浏览器
 * 只能在建库弹窗前干等 —— 网络一抖就超时，刷新也救不回来（请求还在服务端跑着）。所以拆成两步：
 * 事务里只落库记录，提交后再由本类把代码拉回来。</p>
 *
 * <p><b>为什么状态写在数据库而不是内存</b>：进程可能在克隆途中重启，内存里的「正在拉取」会连同
 * 进程一起消失，界面上就永远停在「拉取中」。落库则重启后依然能显示上一次的结果（成功或失败原因）。</p>
 *
 * <p>状态语义（前端据此决定要不要继续轮询）：{@code git_last_sync_ok} 为 {@code null} 表示
 * 「已排队 / 进行中」，true / false 才是终态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitCloneWorker {

    private final KnowledgeBaseMapper kbMapper;
    private final GitVaultService gitVaultService;
    private final VaultFileService vaultFileService;
    private final MiniDocsProperties properties;

    /**
     * 后台克隆。返回即代表「已被接受」，不代表「已完成」——进度与结果看知识库的 git 状态字段。
     *
     * <p>{@code void} 异步方法抛出的异常不会回到调用方，所以这里自己兜住全部异常并落状态：
     * 任何一次失败都必须让界面上的「拉取中」停下来，否则用户会一直等一个永远不会来的结果。</p>
     */
    @Async(AsyncConfig.GIT_EXECUTOR)
    public void cloneAsync(Long kbId) {
        KnowledgeBase kb = kbId == null ? null : kbMapper.selectById(kbId);
        if (kb == null || !kb.isGit()) {
            log.warn("跳过后台克隆：知识库不存在或未绑定 Git，kbId={}", kbId);
            return;
        }
        Path root = null;
        try {
            root = vaultFileService.rootOf(kb.getStorageKey());
            Files.createDirectories(root);
            gitVaultService.cloneRepository(kb.getGitUrl(), kb.getGitBranch(), kb.getGitUsername(),
                    CryptoUtil.decrypt(kb.getGitToken(), properties.getJwtSecret()), root);

            // 拉回来的提交带进新文档与删除：树缓存必须作废，否则界面上还是空库
            vaultFileService.invalidateTree(root);
            long docs = vaultFileService.countDocs(root);
            markFinished(kbId, true, "已拉取分支 " + branchLabel(kb) + "，共 " + docs + " 篇文档");
            log.info("后台克隆完成 kbId={} docs={}", kbId, docs);
        } catch (Exception e) {
            // 克隆失败时 cloneRepository 已清掉半成品目录，这里只负责把原因落到界面上
            String reason = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
            markFinished(kbId, false, "克隆失败：" + reason);
            log.warn("后台克隆失败 kbId={} url={} reason={}", kbId, kb.getGitUrl(), reason, e);
            cleanUp(kb.getStorageKey());
        }
    }

    /**
     * 落终态，并顺手把 {@code doc_count} 对齐到真实篇数。
     *
     * <p>计数必须在这里算：建库那一刻库里还没有文件，之后没有别的入口会重算它，
     * 列表页与门户就会一直显示「0 篇」直到下一次 pull/push。</p>
     */
    private void markFinished(Long kbId, boolean ok, String message) {
        Long docs = null;
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (ok && kb != null) {
            try {
                docs = vaultFileService.countDocs(vaultFileService.rootOf(kb.getStorageKey()));
            } catch (Exception e) {
                log.warn("统计克隆后的文档数失败 kbId={}", kbId, e);
            }
        }
        var update = Wrappers.<KnowledgeBase>lambdaUpdate()
                .eq(KnowledgeBase::getId, kbId)
                .set(KnowledgeBase::getGitLastSyncAt, TimeUtil.now())
                .set(KnowledgeBase::getGitLastSyncOk, ok)
                .set(KnowledgeBase::getGitLastSyncStatus, truncate(message));
        if (docs != null) {
            update.set(KnowledgeBase::getDocCount, docs.intValue());
        }
        kbMapper.update(null, update);
    }

    /**
     * 清理克隆残留的半成品目录。
     *
     * <p>{@code cloneRepository} 自己在失败时会 {@code deleteQuietly}，这里是兜底：
     * 残留目录会让「重新拉取」直接失败在「目标目录已存在且不为空」上，用户只能删库重建。</p>
     */
    private void cleanUp(String storageKey) {
        if (storageKey == null) {
            return;
        }
        try {
            vaultFileService.deleteRoot(storageKey);
        } catch (Exception e) {
            log.warn("清理克隆残留目录失败 storageKey={}", storageKey, e);
        }
    }

    private static String branchLabel(KnowledgeBase kb) {
        return kb.getGitBranch() == null || kb.getGitBranch().isBlank() ? "默认分支" : kb.getGitBranch();
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
