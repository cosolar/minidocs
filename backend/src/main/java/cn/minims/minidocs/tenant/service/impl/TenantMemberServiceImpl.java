package cn.minims.minidocs.tenant.service.impl;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.tenant.dto.TenantDtos.AddMemberRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.JoinRequestMessage;
import cn.minims.minidocs.tenant.dto.TenantDtos.JoinRequestVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.MemberVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateRoleRequest;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantJoinRequest;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantJoinRequestMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.tenant.service.TenantMemberService;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMemberServiceImpl implements TenantMemberService {

    private final TenantService tenantService;
    private final TenantMemberMapper memberMapper;
    private final TenantJoinRequestMapper joinRequestMapper;
    private final UserService userService;
    private final AccessService accessService;
    private final AuditService auditService;
    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public List<MemberVO> list(String slug, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug), KbAction.TENANT_VIEW, actor);
        List<TenantMember> rows = memberMapper.selectList(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenant.getId())
                .orderByAsc(TenantMember::getJoinedAt));
        Map<Long, User> users = usersOf(rows.stream().map(TenantMember::getUserId).toList());
        return rows.stream()
                .map(row -> {
                    User user = users.get(row.getUserId());
                    return MemberVO.of(row, user == null ? "(已注销)" : user.getUsername(),
                            user == null ? null : user.getDisplayName());
                })
                .toList();
    }

    /** 一次取回这批人，成员列表按行查用户会在大组织上刷出 N 条 SQL。 */
    private Map<Long, User> usersOf(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(userIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemberVO add(String slug, AddMemberRequest request, LoginUser actor) {
        Tenant tenant = joinableOrg(slug, actor, KbAction.TENANT_MEMBER_MANAGE);
        String role = normalizeAssignableRole(request.role());
        requireCanSetRole(tenant, actor, null, role);
        User user = userService.findByUsernameOrEmail(request.username().trim().toLowerCase(Locale.ROOT));
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if (!user.isActive()) {
            // 拉一个禁用 / 待审账号进组织，等于给它一份进不去的成员身份
            throw BizException.param("该账号当前不可用，无法加入组织");
        }
        if (memberOf(tenant.getId(), user.getId()) != null) {
            throw BizException.exists("该用户已是组织成员");
        }
        TenantMember member = insertMember(tenant.getId(), user.getId(), role, actor, "invite");
        approvePendingRequest(tenant.getId(), user.getId(), actor);
        audit(KbAction.TENANT_MEMBER_MANAGE, actor, tenant.getId(),
                Map.of("op", "add", "userId", user.getId(), "role", role));
        return MemberVO.of(member, user.getUsername(), user.getDisplayName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(String slug, Long userId, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug),
                KbAction.TENANT_MEMBER_MANAGE, actor);
        TenantMember target = requireMember(tenant.getId(), userId);
        if (actor.id().equals(userId)) {
            // 自己走退出流程：语义是「我离开」，与「我被移除」在审计上要分得开
            throw BizException.param("不能移除自己，请使用退出组织");
        }
        requireCanSetRole(tenant, actor, target, target.getRole());
        memberMapper.deleteById(target.getId());
        // 移出组织就是完整的撤销动作（§9 / I5）；连名单一起清掉，是为了防止「重新加入」悄悄复活旧授权
        knowledgeBaseService.clearRosterOf(tenant.getId(), userId);
        audit(KbAction.TENANT_MEMBER_MANAGE, actor, tenant.getId(), Map.of("op", "remove", "userId", userId));
        log.info("成员已移出 org={} user={} by={}", tenant.getSlug(), userId, actor.username());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemberVO setRole(String slug, Long userId, UpdateRoleRequest request, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug),
                KbAction.TENANT_MEMBER_MANAGE, actor);
        TenantMember target = requireMember(tenant.getId(), userId);
        String role = normalizeAssignableRole(request.role());
        requireCanSetRole(tenant, actor, target, role);
        User user = userService.getById(userId);
        String username = user == null ? "(已注销)" : user.getUsername();
        String displayName = user == null ? null : user.getDisplayName();
        if (target.getRole().equalsIgnoreCase(role)) {
            return MemberVO.of(target, username, displayName);
        }
        // I1：降级会让 OWNER 少一个 —— 但 OWNER 行根本进不到这里（requireCanSetRole 已挡住）
        target.setRole(role);
        memberMapper.updateById(target);
        audit(KbAction.TENANT_MEMBER_MANAGE, actor, tenant.getId(),
                Map.of("op", "setRole", "userId", userId, "role", role));
        return MemberVO.of(target, username, displayName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferOwner(String slug, Long userId, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug),
                KbAction.TENANT_TRANSFER, actor);
        if (tenant.isPersonal()) {
            throw BizException.param("个人组织不能转让");
        }
        if (Objects.equals(tenant.getOwnerUserId(), userId)) {
            throw BizException.param("该用户已经是组织拥有者");
        }
        TenantMember incoming = requireMember(tenant.getId(), userId);
        TenantMember current = memberMapper.selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenant.getId())
                .eq(TenantMember::getUserId, actor.id())
                .last("LIMIT 1"));
        // 转让是「一降一升」的同一条写：先落 OWNER 再降现任，中途失败也不会出现没人当家的一刻
        incoming.setRole(TenantMember.ROLE_OWNER);
        memberMapper.updateById(incoming);
        if (current != null) {
            current.setRole(TenantMember.ROLE_ADMIN);
            memberMapper.updateById(current);
        }
        tenant.setOwnerUserId(userId);
        tenantService.updateById(tenant);
        audit(KbAction.TENANT_TRANSFER, actor, tenant.getId(), Map.of("toUserId", userId));
        log.info("组织 OWNER 已转让 org={} from={} to={}", tenant.getSlug(), actor.username(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void leave(String slug, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug),
                KbAction.TENANT_VIEW, actor);
        TenantMember self = requireMember(tenant.getId(), actor.id());
        if (tenant.isPersonal()) {
            throw BizException.param("个人组织不能退出");
        }
        if (self.isOwner() && countOwners(tenant.getId()) <= 1) {
            // I1：最后一个 OWNER 退出即组织无人治理，库与成员都失去管理者
            throw BizException.forbidden("请先转让 OWNER，再退出组织");
        }
        memberMapper.deleteById(self.getId());
        knowledgeBaseService.clearRosterOf(tenant.getId(), actor.id());
        rehomeOwner(tenant, self);
        audit(KbAction.TENANT_VIEW, actor, tenant.getId(), Map.of("op", "leave", "userId", actor.id()));
        log.info("已退出组织 org={} user={}", tenant.getSlug(), actor.username());
    }

    /**
     * OWNER 退出后把 {@code owner_user_id} 指向仍然在家的 OWNER。
     *
     * <p>{@code owner_user_id} 是「谁是这个组织的家」的落库事实，留着离场的人会让
     * {@link #transferOwner} 那句「该用户已经是组织拥有者」把正确的人挡掉。</p>
     */
    private void rehomeOwner(Tenant tenant, TenantMember leaving) {
        if (!leaving.isOwner()) {
            return;
        }
        TenantMember successor = memberMapper.selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenant.getId())
                .eq(TenantMember::getRole, TenantMember.ROLE_OWNER)
                .last("LIMIT 1"));
        // 必须逐列写：updateById 跳过 null，没有接班 OWNER 时那个离场的人就会留在字段上
        tenantService.update(Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, tenant.getId())
                .set(Tenant::getOwnerUserId, successor == null ? null : successor.getUserId()));
        tenant.setOwnerUserId(successor == null ? null : successor.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyToJoin(String slug, JoinRequestMessage message, LoginUser actor) {
        Tenant tenant = tenantService.requireBySlug(slug);
        if (!tenant.isPublicProfile()) {
            // 申请入口是 /console/{org} 成员门的唯一例外，但它只对外开放的组织（§3.3）
            throw BizException.notFound("组织不存在");
        }
        if (!Tenant.JOIN_REQUEST.equalsIgnoreCase(tenant.getJoinPolicy())) {
            throw BizException.forbidden("该组织不开放申请加入");
        }
        if (memberOf(tenant.getId(), actor.id()) != null) {
            throw BizException.exists("你已是该组织成员");
        }
        TenantJoinRequest existing = requestOf(tenant.getId(), actor.id());
        if (existing != null && existing.isPending()) {
            throw BizException.of(ErrorCode.JOIN_REQUEST_PENDING, "已有待处理的申请，请等待管理员审批");
        }
        String text = message == null ? null : trimToNull(message.message());
        // 一人一组织只留一行：被拒后再申请是覆盖同一行，审批列表不会堆出重复条目。
        // 覆盖必须逐列写：updateById 跳过 null，上一轮的审批人会留在 pending 行上。
        if (existing == null) {
            TenantJoinRequest row = new TenantJoinRequest();
            row.setTenantId(tenant.getId());
            row.setUserId(actor.id());
            row.setMessage(text);
            row.setStatus(TenantJoinRequest.STATUS_PENDING);
            joinRequestMapper.insert(row);
        } else {
            joinRequestMapper.update(null, Wrappers.<TenantJoinRequest>lambdaUpdate()
                    .eq(TenantJoinRequest::getId, existing.getId())
                    .set(TenantJoinRequest::getMessage, text)
                    .set(TenantJoinRequest::getStatus, TenantJoinRequest.STATUS_PENDING)
                    .set(TenantJoinRequest::getReviewerUserId, null)
                    .set(TenantJoinRequest::getReviewedAt, null)
                    .set(TenantJoinRequest::getUpdatedAt, TimeUtil.now()));
        }
        log.info("已提交入组申请 org={} user={}", tenant.getSlug(), actor.username());
    }

    @Override
    public List<JoinRequestVO> listJoinRequests(String slug, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug),
                KbAction.TENANT_JOIN_REVIEW, actor);
        return joinRequestMapper.selectList(Wrappers.<TenantJoinRequest>lambdaQuery()
                        .eq(TenantJoinRequest::getTenantId, tenant.getId())
                        .eq(TenantJoinRequest::getStatus, TenantJoinRequest.STATUS_PENDING)
                        .orderByDesc(TenantJoinRequest::getCreatedAt))
                .stream()
                .map(row -> {
                    User user = userService.getById(row.getUserId());
                    return JoinRequestVO.of(row, user == null ? "(已注销)" : user.getUsername(),
                            user == null ? null : user.getDisplayName());
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JoinRequestVO reviewJoinRequest(String slug, Long requestId, boolean approve, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug),
                KbAction.TENANT_JOIN_REVIEW, actor);
        TenantJoinRequest request = joinRequestMapper.selectById(requestId);
        if (request == null || !Objects.equals(request.getTenantId(), tenant.getId())) {
            // 跨组织的申请 id 与「不存在」不可区分（§2.5）
            throw BizException.notFound("申请不存在");
        }
        if (!request.isPending()) {
            throw BizException.exists("该申请已处理");
        }
        User applicant = userService.getById(request.getUserId());
        if (approve && (applicant == null || !applicant.isActive())) {
            // 与 add() 同一条口径：批准一个禁用 / 待审账号等于发出一份进不去的成员身份
            throw BizException.param("该账号当前不可用，无法加入组织");
        }
        if (approve && memberOf(tenant.getId(), request.getUserId()) == null) {
            insertMember(tenant.getId(), request.getUserId(), TenantMember.ROLE_MEMBER, actor, "request");
        }
        request.setStatus(approve ? TenantJoinRequest.STATUS_APPROVED : TenantJoinRequest.STATUS_REJECTED);
        request.setReviewerUserId(actor.id());
        request.setReviewedAt(TimeUtil.now());
        joinRequestMapper.updateById(request);
        audit(KbAction.TENANT_JOIN_REVIEW, actor, tenant.getId(),
                Map.of("requestId", request.getId(), "approve", approve));
        return JoinRequestVO.of(request, applicant == null ? "(已注销)" : applicant.getUsername(),
                applicant == null ? null : applicant.getDisplayName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void purgeUserMemberships(Long userId) {
        List<TenantMember> rows = memberMapper.selectList(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getUserId, userId));
        for (TenantMember row : rows) {
            Tenant tenant = tenantService.getById(row.getTenantId());
            if (tenant == null) {
                continue;
            }
            if (row.isOwner() && !tenant.isPersonal()) {
                throw BizException.param("该账号仍是组织「" + tenant.getName() + "」的拥有者，请先转让 OWNER");
            }
        }
        for (TenantMember row : rows) {
            Tenant tenant = tenantService.getById(row.getTenantId());
            knowledgeBaseService.clearRosterOf(row.getTenantId(), userId);
            memberMapper.deleteById(row.getId());
            if (tenant != null && tenant.isPersonal() && !hasAnyKb(tenant.getId())) {
                // 个人组织只有这一个成员，库也已经在删号流程里清干净了 —— 组织行留着就是纯孤儿
                tenantService.removeById(tenant.getId());
            }
        }
        log.info("已清理用户的组织成员身份 userId={} 组织数={}", userId, rows.size());
    }

    private boolean hasAnyKb(Long tenantId) {
        return knowledgeBaseService.count(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getTenantId, tenantId)) > 0;
    }

    // ------------------------------------------------------------------ 内部规则
    /** 个人组织没有第二个成员（I3）：所有「拉人进来」的入口一律先挡这一条。 */
    private Tenant joinableOrg(String slug, LoginUser actor, KbAction action) {
        Tenant tenant = accessService.requireTenant(tenantService.requireBySlug(slug), action, actor);
        if (tenant.isPersonal()) {
            throw BizException.param("个人组织不能有第二个成员");
        }
        return tenant;
    }

    private TenantMember insertMember(Long tenantId, Long userId, String role, LoginUser actor, String from) {
        TenantMember member = new TenantMember();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setRole(role);
        member.setJoinedFrom(from);
        member.setInvitedBy(actor.id());
        memberMapper.insert(member);
        return member;
    }

    private void approvePendingRequest(Long tenantId, Long userId, LoginUser actor) {
        TenantJoinRequest pending = requestOf(tenantId, userId);
        if (pending == null || !pending.isPending()) {
            return;
        }
        // 管理员直接添加成员时，把那条还挂着的申请一并结案，否则审批列表里留着一个必然失败的操作
        pending.setStatus(TenantJoinRequest.STATUS_APPROVED);
        pending.setReviewerUserId(actor.id());
        pending.setReviewedAt(TimeUtil.now());
        joinRequestMapper.updateById(pending);
    }

    /**
     * 角色档位的写入规则（规范 §2.4 注 + I1），一条判断覆盖「改人」与「加人」两种入口：
     * OWNER 行谁也动不了，ADMIN 档（无论升还是降）只认现任 OWNER。
     *
     * @param target 被改的成员行；{@code add} 时为 null，表示新成员
     */
    private void requireCanSetRole(Tenant tenant, LoginUser actor, TenantMember target, String newRole) {
        if (target != null && target.isOwner()) {
            // 唯一的例外是转让 —— 那是 TENANT_TRANSFER 一条路径，走的是「一降一升」的原子写法
            throw BizException.forbidden("不能变更 OWNER 的角色或将其移除，请使用转让");
        }
        boolean adminTier = (target != null && target.isOrgAdmin())
                || TenantMember.ROLE_ADMIN.equalsIgnoreCase(newRole);
        if (!adminTier) {
            return;
        }
        // 任免管理员是独立一档动作（KbAction 里单列），前端与这里读同一份判定：
        // 在这里重抄一次 self.isOwner() 就会出现「按钮给了 ADMIN、接口 403」
        if (!accessService.canInTenant(tenant, actor, KbAction.TENANT_APPOINT_ADMIN)) {
            throw BizException.forbidden("只有组织拥有者可以任免管理员");
        }
    }

    private static String normalizeAssignableRole(String role) {
        String value = role == null ? TenantMember.ROLE_MEMBER : role.trim().toUpperCase(Locale.ROOT);
        if (TenantMember.ROLE_OWNER.equalsIgnoreCase(value)) {
            // OWNER 只能由转让产生：能写 OWNER 的接口等于一条无需现任同意的夺权路径
            throw BizException.param("OWNER 只能通过转让产生");
        }
        if (!TenantMember.ROLE_ADMIN.equalsIgnoreCase(value) && !TenantMember.ROLE_MEMBER.equalsIgnoreCase(value)) {
            throw BizException.param("成员角色只能是 ADMIN / MEMBER");
        }
        return value;
    }

    private TenantMember requireMember(Long tenantId, Long userId) {
        TenantMember member = memberOf(tenantId, userId);
        if (member == null) {
            throw BizException.notFound("该用户不是组织成员");
        }
        return member;
    }

    private TenantMember memberOf(Long tenantId, Long userId) {
        if (userId == null) {
            return null;
        }
        return memberMapper.selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId)
                .eq(TenantMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    private TenantJoinRequest requestOf(Long tenantId, Long userId) {
        return joinRequestMapper.selectOne(Wrappers.<TenantJoinRequest>lambdaQuery()
                .eq(TenantJoinRequest::getTenantId, tenantId)
                .eq(TenantJoinRequest::getUserId, userId)
                .last("LIMIT 1"));
    }

    private long countOwners(Long tenantId) {
        return memberMapper.selectCount(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId)
                .eq(TenantMember::getRole, TenantMember.ROLE_OWNER));
    }

    private void audit(KbAction action, LoginUser actor, Long tenantId, Map<String, Object> detail) {
        auditService.record(action, actor.id(), tenantId, null, null, detail);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
