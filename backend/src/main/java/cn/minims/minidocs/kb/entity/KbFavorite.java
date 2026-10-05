package cn.minims.minidocs.kb.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库收藏（用户 n : n 知识库，联合主键去重）。
 */
@Data
@TableName("kb_favorite")
public class KbFavorite implements Serializable {

    private Long userId;

    private Long kbId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
