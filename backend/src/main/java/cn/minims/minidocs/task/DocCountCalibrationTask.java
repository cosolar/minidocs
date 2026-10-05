package cn.minims.minidocs.task;

import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天 03:00 全量校准 doc_count，防止直接操作磁盘造成的偏差。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocCountCalibrationTask {

    private final KnowledgeBaseService knowledgeBaseService;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void calibrate() {
        long start = System.currentTimeMillis();
        int changed = knowledgeBaseService.refreshAllDocCounts();
        log.info("doc_count 校准完成，修正 {} 个知识库，耗时 {}ms", changed, System.currentTimeMillis() - start);
    }
}
