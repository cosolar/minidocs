package cn.minims.minidocs.audit.service;

import cn.minims.minidocs.audit.dto.AuditDtos.AuditVO;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.permission.KbAction;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计写入与读取（规范 I7：超管绕过必须留痕；F9：组织 ADMIN 可查本组织，保留 180 天）。
 *
 * <p>写入与业务同事务：审计失败即业务失败。反过来做（吞掉审计异常）会让「无留痕的越权操作」
 * 成为可能，正是 I7 要挡住的。</p>
 */
public interface AuditService {

    void record(KbAction action, Long actorUserId, Long tenantId, Long kbId, String docPath,
                Map<String, Object> detail);

    /** 超管绕过判定：单独成动作名，便于事后区分「有权」与「越权式放行」。 */
    void superBypass(KbAction action, Long actorUserId, Long tenantId, Long kbId, String docPath);

    /**
     * 本组织审计流，新的在前。调用方必须已经过 {@code AUDIT_READ} 裁决。
     *
     * @param action 动作名过滤，留空表示不过滤
     */
    /**
     * 本组织的审计流，新的在前。
     *
     * <p>筛选条件全部可空，且一律在 SQL 里做：一页二十条的内存过滤会让 {@code total} 与翻页都变成
     * 假数，而审计页正是拿 {@code total} 判断「这事最近发生过几次」的地方。</p>
     *
     * @param from 起始时间（含）
     * @param to   截止时间（不含），调用方按「结束日 +1 天」传，这样按日期筛时当天整日都在范围内
     */
    PageResult<AuditVO> page(Long tenantId, String action, Long actorUserId,
                             LocalDateTime from, LocalDateTime to, long page, long size);

    /** 删除 {@code cutoff} 之前的条目，返回删除行数（F9 保留 180 天）。 */
    int purgeOlderThan(LocalDateTime cutoff);
}
