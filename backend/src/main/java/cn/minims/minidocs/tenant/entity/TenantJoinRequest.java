package cn.minims.minidocs.tenant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入组申请。一人对同一组织只有一行（{@code uk_join_request}），拒绝后再次申请复用该行重置为 pending。
 *
 * <p>唯一键是「为什么再次申请不产生第二行」的答案：审批列表要能看出这个人的历史，而不是堆叠重复条目。</p>
 */
@Data
@TableName("tenant_join_request")
public class TenantJoinRequest {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    private String message;

    private String status;

    private Long reviewerUserId;

    private LocalDateTime reviewedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isPending() {
        return STATUS_PENDING.equalsIgnoreCase(status);
    }
}
