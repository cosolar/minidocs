package cn.minims.minidocs.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户外观偏好（JSON 字符串，MySQL 存 JSON / SQLite 存 TEXT）。
 */
@Data
@TableName("user_settings")
public class UserSettings {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String settings;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
