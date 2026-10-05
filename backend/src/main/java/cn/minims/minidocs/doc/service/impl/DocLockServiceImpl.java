package cn.minims.minidocs.doc.service.impl;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.doc.dto.DocDtos.LockVO;
import cn.minims.minidocs.doc.entity.DocEditLock;
import cn.minims.minidocs.doc.mapper.DocEditLockMapper;
import cn.minims.minidocs.doc.service.DocLockService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 编辑锁落 DB 的实现（规范 §4.3：单实例、无 Redis，也不放 Caffeine —— 重启丢锁等于两人同时进编辑）。
 *
 * <p>并发一律靠「带条件的单条 UPDATE 影响行数」裁决，不靠先读后写：读到的状态在写之前就可能变了。
 * 过期锁不删、由下一个获取者就地接管，因此不需要清理任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocLockServiceImpl implements DocLockService {

    /** 锁的存活秒数；编辑器按 TTL/3 心跳续期。 */
    static final long TTL_SECONDS = 60;

    private final DocEditLockMapper lockMapper;
    private final AccessService accessService;
    private final UserService userService;
    private final AuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LockVO acquire(Long kbId, String path, LoginUser actor) {
        accessService.requireKb(kbId, KbAction.DOC_WRITE, actor);
        String normalized = PathGuard.normalizeRelative(path);
        LocalDateTime now = TimeUtil.now();
        LocalDateTime expiresAt = now.plusSeconds(TTL_SECONDS);

        DocEditLock row = rowOf(kbId, normalized);
        if (row == null) {
            row = insertFree(kbId, normalized, actor.id(), now, expiresAt) ? null : rowOf(kbId, normalized);
        }
        if (row == null) {
            // 首次获取：插入成功即已持有；并发对手抢到那一行又刚好释放了，本轮按「无锁」返回，下次心跳自然拿到
            return view(kbId, normalized, actor);
        }
        if (actor.id().equals(row.getHolderUserId())) {
            // 续期只推到期时间，acquired_at 不动 —— 编辑器要显示「已持有 N 秒」
            lockMapper.update(null, Wrappers.<DocEditLock>lambdaUpdate()
                    .eq(DocEditLock::getKbId, kbId)
                    .eq(DocEditLock::getDocPath, normalized)
                    .eq(DocEditLock::getHolderUserId, actor.id())
                    .set(DocEditLock::getExpiresAt, expiresAt));
            return view(kbId, normalized, actor);
        }
        if (!takeOver(kbId, normalized, actor.id(), now, expiresAt)) {
            throw locked(kbId, normalized, actor);
        }
        return view(kbId, normalized, actor);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long kbId, String path, LoginUser actor) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.DOC_READ, actor);
        String normalized = PathGuard.normalizeRelative(path);
        DocEditLock row = rowOf(kbId, normalized);
        if (row == null) {
            return;
        }
        boolean self = actor.id().equals(row.getHolderUserId());
        if (!self && !accessService.can(kb, actor, KbAction.KB_MEMBER_MANAGE)) {
            // 普通成员既不该替别人解锁，也不该能借「解锁」打断别人的编辑
            throw BizException.forbidden("只有持有者本人或知识库管理员可以释放编辑锁");
        }
        // 带上持有者条件删除：读判与删除之间锁若已易主，这里就不该删掉新持有者的锁
        int removed = lockMapper.delete(Wrappers.<DocEditLock>lambdaQuery()
                .eq(DocEditLock::getKbId, kbId)
                .eq(DocEditLock::getDocPath, normalized)
                .eq(DocEditLock::getHolderUserId, row.getHolderUserId()));
        if (removed > 0 && !self) {
            auditService.record(KbAction.KB_MEMBER_MANAGE, actor.id(), kb.getTenantId(), kbId, normalized,
                    Map.of("op", "forceUnlock", "holderUserId", row.getHolderUserId()));
            log.info("强制释放编辑锁 kb={} path={} holder={} by={}", kbId, normalized, row.getHolderUserId(),
                    actor.username());
        }
    }

    @Override
    public LockVO probe(Long kbId, String path, LoginUser actor) {
        accessService.requireKb(kbId, KbAction.DOC_READ, actor);
        return view(kbId, PathGuard.normalizeRelative(path), actor);
    }

    @Override
    public void requireHeldBy(Long kbId, String normalizedPath, LoginUser actor) {
        DocEditLock row = rowOf(kbId, normalizedPath);
        if (row == null) {
            throw BizException.of(ErrorCode.FORBIDDEN, "请先获取编辑锁再保存");
        }
        if (isExpired(row, TimeUtil.now())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "编辑锁已过期，请重新获取后再保存");
        }
        if (!actor.id().equals(row.getHolderUserId())) {
            throw locked(kbId, normalizedPath, actor);
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private DocEditLock rowOf(Long kbId, String path) {
        return lockMapper.selectOne(Wrappers.<DocEditLock>lambdaQuery()
                .eq(DocEditLock::getKbId, kbId)
                .eq(DocEditLock::getDocPath, path)
                .last("LIMIT 1"));
    }

    /** @return 是否插入成功；false 表示同一刻有人抢到了同一行。 */
    private boolean insertFree(Long kbId, String path, Long userId, LocalDateTime now, LocalDateTime expiresAt) {
        DocEditLock fresh = new DocEditLock();
        fresh.setKbId(kbId);
        fresh.setDocPath(path);
        fresh.setHolderUserId(userId);
        fresh.setAcquiredAt(now);
        fresh.setExpiresAt(expiresAt);
        try {
            lockMapper.insert(fresh);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 主键 (kb_id, doc_path) 冲突就是并发获取，交由上层复核那一行的归属
            return false;
        }
    }

    /** @return 是否接管成功；false 表示对方仍在有效期内（或刚好被第三人接管）。 */
    private boolean takeOver(Long kbId, String path, Long userId, LocalDateTime now, LocalDateTime expiresAt) {
        return lockMapper.update(null, Wrappers.<DocEditLock>lambdaUpdate()
                .eq(DocEditLock::getKbId, kbId)
                .eq(DocEditLock::getDocPath, path)
                .lt(DocEditLock::getExpiresAt, now)
                .set(DocEditLock::getHolderUserId, userId)
                .set(DocEditLock::getAcquiredAt, now)
                .set(DocEditLock::getExpiresAt, expiresAt)) > 0;
    }

    /** 锁状态视图：过期一律当作「无人持有」，客户端据此直接获取。 */
    private LockVO view(Long kbId, String path, LoginUser actor) {
        DocEditLock row = rowOf(kbId, path);
        if (row == null || isExpired(row, TimeUtil.now())) {
            return LockVO.free(path, TTL_SECONDS);
        }
        User holder = userService.getById(row.getHolderUserId());
        String username = holder == null ? "(已注销)" : holder.getUsername();
        String nickname = holder == null || holder.getDisplayName() == null || holder.getDisplayName().isBlank()
                ? username : holder.getDisplayName();
        return new LockVO(path, true, row.getHolderUserId(), username, nickname,
                row.getAcquiredAt(), row.getExpiresAt(),
                actor != null && actor.id().equals(row.getHolderUserId()), TTL_SECONDS);
    }

    private BizException locked(Long kbId, String path, LoginUser actor) {
        LockVO state = view(kbId, path, actor);
        String who = state.nickname() == null ? "他人" : state.nickname();
        return BizException.of(ErrorCode.DOC_LOCKED, who + " 正在编辑该文档").payload(state);
    }

    private static boolean isExpired(DocEditLock row, LocalDateTime now) {
        return !row.getExpiresAt().isAfter(now);
    }
}
