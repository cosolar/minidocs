package cn.minims.minidocs.tenant.service;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.tenant.dto.TenantDtos.AddMemberRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.JoinRequestMessage;
import cn.minims.minidocs.tenant.dto.TenantDtos.JoinRequestVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.MemberVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateRoleRequest;

import java.util.List;

/**
 * 组织成员与入组申请（规范 §3.3）。
 *
 * <p>I1（每个组织至少一个 OWNER，最后一个不可降级 / 退出 / 被移除）在这里的每条写路径上成立，
 * 而不是靠 {@code AccessService} 兜底：裁决层判的是「能不能做」，这里的规则是「做了会不会把组织
 * 变成没人管的状态」。</p>
 */
public interface TenantMemberService {

    List<MemberVO> list(String slug, LoginUser actor);

    MemberVO add(String slug, AddMemberRequest request, LoginUser actor);

    void remove(String slug, Long userId, LoginUser actor);

    MemberVO setRole(String slug, Long userId, UpdateRoleRequest request, LoginUser actor);

    /** 转让 OWNER：现任 OWNER 专属，自己降为 ADMIN（I1 要求任一时刻都有人当家）。 */
    void transferOwner(String slug, Long userId, LoginUser actor);

    void leave(String slug, LoginUser actor);

    /** 提交入组申请：走「公开资料」那条门，不要求成员身份。 */
    void applyToJoin(String slug, JoinRequestMessage message, LoginUser actor);

    List<JoinRequestVO> listJoinRequests(String slug, LoginUser actor);

    /** 审批：批准即插入 MEMBER 成员行。 */
    JoinRequestVO reviewJoinRequest(String slug, Long requestId, boolean approve, LoginUser actor);

    /**
     * 删号时清掉这个人的全部成员身份与该组织维护名单里指向他的行（规范 I6）。
     *
     * <p>仍是团队组织 OWNER 时直接拒绝：把当家的人抹掉会让整个组织无人治理，而这条路径上没有
     * 「现任同意」可言。个人组织随账号一起消失（前提是名下已无库），因为个人组织按定义只有他一个成员，
     * 留下一行 {@code u-{username}} 的孤儿组织只会占掉这个 slug 让别人建不了同名组织。</p>
     */
    void purgeUserMemberships(Long userId);
}
