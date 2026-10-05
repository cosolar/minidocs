package cn.minims.minidocs.tenant.dto;

import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantJoinRequest;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组织模块 DTO 集合。
 */
public final class TenantDtos {

    private TenantDtos() {
    }

    /**
     * 建组织。slug 省略时由名称生成。
     *
     * <p>这里不用 {@code @Pattern} 卡字符集：组织 slug 的保留字与个人前缀是业务规则，
     * 注解里再抄一份就会有两处口径（规范 §3.2 的判定集中在 {@code TenantServiceImpl}）。</p>
     */
    public record CreateOrgRequest(
            @NotBlank(message = "组织名称不能为空")
            @Size(max = 64, message = "组织名称不能超过 64") String name,
            @Size(max = 48, message = "组织标识不能超过 48") String slug,
            @Size(max = 255, message = "简介不能超过 255") String description,
            @Pattern(regexp = "request|invite_only", message = "加入方式只能是 request / invite_only") String joinPolicy,
            Boolean discoverable) {
    }

    /** 改组织：一次表单全量提交，字段为 null 表示不改。 */
    public record UpdateOrgRequest(
            @Size(max = 64, message = "组织名称不能超过 64") String name,
            @Size(max = 255, message = "简介不能超过 255") String description,
            String logoUrl,
            @Pattern(regexp = "request|invite_only", message = "加入方式只能是 request / invite_only") String joinPolicy,
            Boolean discoverable) {
    }

    public record AddMemberRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @Pattern(regexp = "ADMIN|MEMBER", message = "成员角色只能是 ADMIN / MEMBER") String role) {
    }

    public record UpdateRoleRequest(
            @NotBlank(message = "角色不能为空")
            @Pattern(regexp = "ADMIN|MEMBER", message = "只能设置为 ADMIN / MEMBER；OWNER 只能通过转让产生") String role) {
    }

    public record TransferOwnerRequest(@jakarta.validation.constraints.NotNull(message = "必须指定接手人") Long userId) {
    }

    public record JoinRequestMessage(@Size(max = 255, message = "申请说明不能超过 255") String message) {
    }

    /**
     * 切换器条目：够渲染下拉框，外加这一档在组织里能做什么。
     *
     * <p>{@code myPermissions} 与 {@code role} 都给：角色留着是为了「显示我是 OWNER」，
     * 但按钮的可用与否只读动作集 —— 前端不再自己推断档位含义（规范 §11.4）。</p>
     */
    public record TenantBrief(Long id, String slug, String name, String type, String role, boolean owner,
                              List<String> myPermissions) {
    }

    /** 平台侧组织行：不带访问者角色，只有治理要看的事实。 */
    public record AdminOrgVO(Long id, String slug, String name, String type, String status, Long ownerUserId,
                             String ownerName, long memberCount, long kbCount, LocalDateTime createdAt,
                             String createdText) {
    }

    /**
     * 应用外壳的一次性装载：账号 + 我的组织 + 上次所在组织（规范 §3.4）。
     *
     * <p>{@code lastTenantSlug} 只是登录后的默认落地点，为 null 表示存的组织已经不是自己的，
     * 前端应回落到 {@code tenants[0]}；权限一律不经这里。</p>
     */
    public record MeVO(UserVO user, List<TenantBrief> tenants, String lastTenantSlug) {
    }

    /** 组织详情：带访问者自己的角色、动作集与计数。 */
    public record OrgVO(Long id, String slug, String name, String type, String description, String logoUrl,
                        String joinPolicy, boolean discoverable, String status, String myRole,
                        List<String> myPermissions,
                        long memberCount, long kbCount, LocalDateTime createdAt, String createdText) {

        public static OrgVO of(Tenant tenant, String myRole, List<KbAction> permissions,
                               long memberCount, long kbCount) {
            return new OrgVO(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getType(),
                    tenant.getDescription(), tenant.getLogoUrl(), tenant.getJoinPolicy(),
                    Boolean.TRUE.equals(tenant.getDiscoverable()), tenant.getStatus(), myRole,
                    actionsOf(permissions),
                    memberCount, kbCount, tenant.getCreatedAt(), TimeUtil.display(tenant.getCreatedAt()));
        }

        public static List<String> actionsOf(List<KbAction> permissions) {
            return permissions == null ? List.of() : permissions.stream().map(Enum::name).toList();
        }
    }

    /** 发现列表条目：只出公开字段，成员构成与内部库一概不给。 */
    public record DiscoverVO(String slug, String name, String description, String logoUrl,
                             String joinPolicy, long memberCount, long publicKbCount) {
    }

    public record MemberVO(Long userId, String username, String displayName, String role,
                           boolean orgAdmin, String joinedFrom, LocalDateTime joinedAt) {

        public static MemberVO of(TenantMember member, String username, String displayName) {
            return new MemberVO(member.getUserId(), username, displayName, member.getRole(),
                    member.isOrgAdmin(), member.getJoinedFrom(), member.getJoinedAt());
        }
    }

    public record JoinRequestVO(Long id, Long userId, String username, String displayName, String message,
                                String status, LocalDateTime createdAt, LocalDateTime reviewedAt) {

        public static JoinRequestVO of(TenantJoinRequest request, String username, String displayName) {
            return new JoinRequestVO(request.getId(), request.getUserId(), username, displayName,
                    request.getMessage(), request.getStatus(), request.getCreatedAt(), request.getReviewedAt());
        }
    }
}
