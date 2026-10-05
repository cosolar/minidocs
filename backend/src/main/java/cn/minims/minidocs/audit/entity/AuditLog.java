package cn.minims.minidocs.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计条目：只记动作与对象，<b>不记文档正文</b>（规范 §9 不做历史版本）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long actorUserId;

    /** KbAction 名，或超管绕过标记 SUPER_BYPASS 等 */
    private String action;

    private Long kbId;

    private String docPath;

    /** JSON 文本，不含正文 */
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
