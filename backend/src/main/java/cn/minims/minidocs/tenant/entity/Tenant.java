package cn.minims.minidocs.tenant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户 = 组织。每个用户注册时自带一个 {@link #TYPE_PERSONAL} 组织。
 */
@Data
@TableName("tenant")
public class Tenant {

    public static final String TYPE_PERSONAL = "PERSONAL";
    public static final String TYPE_TEAM = "TEAM";

    public static final String JOIN_REQUEST = "request";
    public static final String JOIN_INVITE_ONLY = "invite_only";
    /** 免审直接加入，仅用于内部演示组织 */
    public static final String JOIN_OPEN = "open";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    /** 个人组织 slug 前缀，该前缀为个人组织保留（规范 §3.2） */
    public static final String PERSONAL_SLUG_PREFIX = "u-";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String slug;

    private String name;

    private String type;

    private Long ownerUserId;

    private String joinPolicy;

    private String description;

    private String logoUrl;

    /** 是否出现在组织发现列表；个人组织恒为 false */
    private Boolean discoverable;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isPersonal() {
        return TYPE_PERSONAL.equalsIgnoreCase(type);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    /**
     * 是否对组织外的人展示公开资料（组织发现列表与其详情页的依据）。
     *
     * <p>个人组织永远返回 false：它是私有空间，不参与被发现。停用的组织也不该出现在任何列表里。</p>
     */
    public boolean isPublicProfile() {
        return !isPersonal() && isActive() && Boolean.TRUE.equals(discoverable);
    }
}
