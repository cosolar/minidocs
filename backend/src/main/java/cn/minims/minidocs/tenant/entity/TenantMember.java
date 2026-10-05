package cn.minims.minidocs.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织成员。一人可属多个组织；同一组织内一人一行。
 *
 * <p>成员被移除即刻失效：{@code AuthInterceptor} 每请求重读用户行，权限判定不落缓存。</p>
 */
@Data
@TableName("tenant_member")
public class TenantMember {

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    private String role;

    /** 加入来源：request / invite / bootstrap / transfer */
    private String joinedFrom;

    private Long invitedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinedAt;

    public boolean isOwner() {
        return ROLE_OWNER.equalsIgnoreCase(role);
    }

    /** OWNER 与 ADMIN 合称组织管理员（规范 §2.3）。 */
    public boolean isOrgAdmin() {
        return isOwner() || ROLE_ADMIN.equalsIgnoreCase(role);
    }
}
