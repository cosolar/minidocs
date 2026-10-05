package cn.minims.minidocs.doc.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档编辑锁（分工式协同，规范 §4.3）。
 *
 * <p>状态落 DB 而不是 Caffeine：进程重启丢锁会让两个人同时进编辑，正是这把锁要消灭的问题。
 * 过期行不删除，由下一个获取者就地接管，因此不需要清理任务。</p>
 *
 * <p><b>主键是 {@code (kb_id, doc_path)} 复合键</b>：本类没有 {@code @TableId}，
 * 一切读写都必须按这两列走 wrapper，不能调用 {@code selectById} 等按主键 API。</p>
 */
@Data
@TableName("doc_edit_lock")
public class DocEditLock {

    private Long kbId;

    private String docPath;

    private Long holderUserId;

    private LocalDateTime acquiredAt;

    private LocalDateTime expiresAt;
}
