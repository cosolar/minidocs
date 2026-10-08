package cn.minims.minidocs.share.entity;

import cn.minims.minidocs.common.util.TimeUtil;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享记录。
 *
 * <p>target_key 为数据库生成列（IFNULL(doc_path,'')），用于唯一约束，Java 侧不感知。</p>
 *
 * <p>布尔语义方法刻意不带 {@code is} / {@code get} 前缀，避免与 Lombok 生成的
 * {@code getRevoked()} 等属性访问器在 MyBatis 反射时产生歧义。</p>
 */
@Data
@TableName("shares")
public class Share {

    public static final String SCOPE_KB = "kb";
    public static final String SCOPE_DOC = "doc";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_REVOKED = "revoked";
    public static final String STATUS_INVALID = "invalid";

    /* 门户曝光范围：这条分享在门户里对谁可见。与库的 visibility 无关 —— 那是平台内权限，这一列管的是对外。 */

    /** 所有人（含未登录访客）。历史数据都落这一档，DEFAULT 与之相同 */
    public static final String PORTAL_ALL = "anonymous";
    /** 任何登录用户（不限组织） */
    public static final String PORTAL_MEMBER = "member";
    /** 仅该库的维护者 */
    public static final String PORTAL_MAINTAINER = "maintainer";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String token;

    private Long ownerId;

    private Long kbId;

    /** 文档相对路径；整库分享为 null */
    private String docPath;

    /** kb / doc */
    private String scope;

    /**
     * 分享页顶部导航菜单配置（JSON 数组），null = 不显示导航条。
     *
     * <p>存成字符串而不是拆表：菜单是「这条链接怎么被读」的一部分，条目数上限也就几十，
     * 拆一张 shares 子表只会换来一个永远要连表读、却从不单独查询的实体。</p>
     */
    private String menuConfig;

    private String passwordHash;

    /** 门户曝光范围，取值见 {@link #PORTAL_ALL} 等常量；null 视为 {@link #PORTAL_ALL} */
    private String portalScope;

    /** null = 永久有效 */
    private LocalDateTime expiresAt;

    private Integer revoked;

    private Integer invalid;

    private Integer views;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean revoked() {
        return revoked != null && revoked == 1;
    }

    public boolean invalid() {
        return invalid != null && invalid == 1;
    }

    public boolean encrypted() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean kbScope() {
        return SCOPE_KB.equalsIgnoreCase(scope);
    }

    public boolean docScope() {
        return SCOPE_DOC.equalsIgnoreCase(scope);
    }

    public boolean expired() {
        return expiresAt != null && expiresAt.isBefore(TimeUtil.now());
    }

    public int viewCount() {
        return views == null ? 0 : views;
    }

    /** 状态判定：撤销 &gt; 失效 &gt; 过期 &gt; 有效。 */
    public String status() {
        if (revoked()) {
            return STATUS_REVOKED;
        }
        if (invalid()) {
            return STATUS_INVALID;
        }
        if (expired()) {
            return STATUS_EXPIRED;
        }
        return STATUS_ACTIVE;
    }
}
