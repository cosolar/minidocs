package cn.minims.minidocs.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织标识的历史别名。
 *
 * <p>个人组织允许改名，但已经发出去的阅读链接与收藏夹还在用旧 slug。这里把旧值留成别名，
 * 解析时仍回到同一个组织；别名同样占据全局 slug 命名空间，后来者不能占用。</p>
 */
@Data
@TableName("tenant_slug_alias")
public class TenantSlugAlias {

    /** 旧 slug，全小写；主键，天然全局唯一 */
    @TableId(type = IdType.INPUT)
    private String slug;

    private Long tenantId;

    private LocalDateTime createdAt;
}
