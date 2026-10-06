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
 *
 * <p>{@code config} 里的键名常量也放在这里：它们描述的是这一列 JSON 的结构，
 * 写入侧（{@code SiteConfigServiceImpl}）与读取侧（{@code SiteBaseUrlResolver}）都要用，
 * 放一处才不会各写一份字符串拼错。</p>
 */
@Data
@TableName("site_config")
public class SiteConfig {

    /** 单行表的固定主键 */
    public static final int SINGLETON_ID = 1;

    /** JSON 键：站点名称 */
    public static final String KEY_NAME = "name";
    /** JSON 键：站点副标题 */
    public static final String KEY_SUBTITLE = "subtitle";
    /** JSON 键：站点 Logo 文件名 */
    public static final String KEY_LOGO = "logo";
    /** JSON 键：站点基址（对外分享链接的协议 + 主机 [+ 路径]，可由站点设置页修改） */
    public static final String KEY_BASE_URL = "baseUrl";

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String config;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
