package cn.minims.minidocs.site.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点级配置（JSON 字符串，MySQL 存 JSON / SQLite 存 TEXT）。
 *
 * <p>整张表只有 {@code id = 1} 这一行，见 {@code V7__site_config.sql}。</p>
 */
@Data
@TableName("site_config")
public class SiteConfig {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String config;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
