package cn.minims.minidocs.share.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * UV 去重日志：同一 share + 同一访客指纹 + 同一天只记 1 条。
 */
@Data
@TableName("share_view_log")
public class ShareViewLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shareId;

    /** SHA-256(IP + UA) 前 16 位十六进制 */
    private String visitorHash;

    /** 访问日期（UTC+8） */
    private LocalDate viewDate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
