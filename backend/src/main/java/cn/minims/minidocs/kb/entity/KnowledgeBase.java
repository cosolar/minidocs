package cn.minims.minidocs.kb.entity;

import cn.minims.minidocs.common.exception.BizException;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 知识库 = 一个受管目录（VAULT_HOME/vaults/{storage_key}）。
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    public static final String VISIBILITY_PUBLIC = "public";
    /** 仅本组织可见 */
    public static final String VISIBILITY_ORG = "org";
    public static final String VISIBILITY_PRIVATE = "private";

    /** 仅创建者与组织管理员可维护 */
    public static final String MAINTAIN_OWNER_ONLY = "owner_only";
    /** 维护名单内 EDITOR 可维护 */
    public static final String MAINTAIN_MEMBERS = "members";
    /** 组织内任意成员可维护 */
    public static final String MAINTAIN_ORG_ALL = "org_all";

    /** 本地知识库：内容只存在服务器磁盘上（默认） */
    public static final String SOURCE_LOCAL = "local";
    /** 云端知识库：绑定一个线上 Git 仓库，磁盘那份是它的工作副本 */
    public static final String SOURCE_GIT = "git";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属组织。权限判定一律经 AccessService，不得从本字段直接推断写权限 */
    private Long tenantId;

    /** 创建者（v1 的 owner 语义已降级为审计字段） */
    private Long ownerId;

    private String name;

    /** 目录名（安全字符），组织内唯一 */
    private String slug;

    /** 物理目录相对 vaults/ 的路径 {@code o{tenantId}/{slug}}，一经写入不可变（规范 I4） */
    private String storageKey;

    private String description;

    /** public 全平台公开 / org 本组织 / private 维护名单 */
    private String visibility;

    /** 谁能维护，与 visibility 正交（规范 §2.3） */
    private String maintainScope;

    /** 封面图相对路径（相对知识库根），为空时使用默认渐变封面 */
    private String coverUrl;

    /** 标签，逗号分隔 */
    private String tags;

    /** 缓存字段：文档数（含子目录） */
    private Integer docCount;

    /** local 本地目录 / git 绑定线上仓库 */
    private String sourceType;

    /** 远程仓库地址（HTTPS）。URL 里若带 user:token 会在入库前剥掉，凭证只走下面两列 */
    private String gitUrl;

    /** 跟踪的分支短名 */
    private String gitBranch;

    /** 访问令牌的用户名；公开仓库可为空 */
    private String gitUsername;

    /** 访问令牌的 AES-GCM 密文（见 CryptoUtil），不落明文，也不回给前端 */
    private String gitToken;

    /** 最近一次同步时间与结果，纯展示用，同步失败不影响知识库可用性 */
    private LocalDateTime gitLastSyncAt;
    private String gitLastSyncStatus;
    private Boolean gitLastSyncOk;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isPublic() {
        return VISIBILITY_PUBLIC.equalsIgnoreCase(visibility);
    }

    /** 是否绑定了 Git 远程仓库。 */
    public boolean isGit() {
        return SOURCE_GIT.equalsIgnoreCase(sourceType);
    }

    /**
     * 归一化维护档位：值域只有这三个，写在常量旁边而不是散落到各个服务里。
     *
     * <p>{@code whenMissing} 由调用方给：建库要落保守默认（{@code owner_only}），而改档位的接口
     * 不接受「省略」（省略等于偷偷换成 owner_only，会把名单一次清空）。</p>
     */
    public static String normalizeMaintainScope(String scope, String whenMissing) {
        String value = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            if (whenMissing == null) {
                throw BizException.param("维护档位不能为空");
            }
            return whenMissing;
        }
        if (!MAINTAIN_OWNER_ONLY.equals(value) && !MAINTAIN_MEMBERS.equals(value)
                && !MAINTAIN_ORG_ALL.equals(value)) {
            throw BizException.param("维护档位只能是 owner_only / members / org_all");
        }
        return value;
    }
}
