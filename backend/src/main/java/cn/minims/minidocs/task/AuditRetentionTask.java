package cn.minims.minidocs.task;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 每天 03:30 清掉超过 180 天的审计条目（规范 F9 的保留期）。
 *
 * <p>整段删除而非逐行：{@code idx_audit_tenant(tenant_id, created_at)} 让按时间裁剪是索引范围扫描，
 * 保留期一到不该让索引白长。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionTask {

    static final int RETENTION_DAYS = 180;

    private final AuditService auditService;

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void purge() {
        LocalDateTime cutoff = TimeUtil.now().minusDays(RETENTION_DAYS);
        int removed = auditService.purgeOlderThan(cutoff);
        log.info("审计清理完成：删除 {} 条 {} 之前的记录", removed, cutoff);
    }
}
