package cn.minims.minidocs.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库级配置（隐藏规则 / 目录顺序 / 展示偏好）。
 *
 * <p>三份配置放在同一个 JSON 文档里，因为它们总是一起读、一起写回 —— 拆成多行或
 * 多列后，任何一条只改自己那部分的更新路径都可能顺手抹掉另外两部分。</p>
 *
 * <p>主键是 {@code storage_key} 而不是自增 id：调用方手里只有知识库根目录，
 * 而 {@code o{tenantId}/{slug}} 与目录是一一对应的。</p>
 */
@Data
@TableName("kb_config")
public class KbConfigEntity {

    @TableId(value = "storage_key", type = IdType.INPUT)
    private String storageKey;

    /** 配置 JSON，形状与旧版 {@code .minidocs.json} 一致 */
    private String configJson;

    /** 最后写入时间（与其他表同一口径：MySQL DATETIME / SQLite TEXT） */
    private LocalDateTime updatedAt;
}
