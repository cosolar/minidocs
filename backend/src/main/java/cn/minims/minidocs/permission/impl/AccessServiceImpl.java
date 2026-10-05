package cn.minims.minidocs.permission.impl;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.kb.entity.KbMember;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbMemberMapper;
import cn.minims.minidocs.kb.mapper.KnowledgeBaseMapper;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.user.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 两轴裁决的实现。见规范 §2.2 / §2.3 / §2.4 / §2.5。
 */
@Service
@RequiredArgsConstructor
public class AccessServiceImpl implements AccessService {

    /**
     * 该组织内的活跃成员身份。
     *
     * <p>所有加法（创建者、名单）都要与它取交集：库属于组织，人离开组织后不该继续握着那里的一切 ——
     * 那是 {@code kb_member} 残留之外的第二条权限残留路径（I5）。</p>
     */
    private static final String ORG_MEMBER_EXISTS =
            "SELECT 1 FROM tenant_member tm3 JOIN tenant t3 ON t3.id = tm3.tenant_id"
                    + " WHERE t3.id = knowledge_base.tenant_id AND tm3.user_id = {0} AND t3.status = 'active'";

    /**
     * 名单授权只在组织内生效（规范 §9「跨组织授权外部维护者」明确不做）。
     *
     * <p>必须带组织成员这一层：移出组织后 {@code kb_member} 行仍在库里，若名单单独授权，
     * 被移除的人会继续保有读写 —— 那是静默的权限残留。</p>
     */
    private static final String KB_MEMBER_EXISTS =
            "SELECT 1 FROM kb_member km WHERE km.kb_id = knowledge_base.id AND km.user_id = {0}"
                    + " AND EXISTS (" + ORG_MEMBER_EXISTS + ")";

    /** 组织内可见：普通成员看 org 库，OWNER/ADMIN 看本组织全部库（含 private）。 */
    private static final String TENANT_VISIBLE_EXISTS =
            "SELECT 1 FROM tenant_member tm JOIN tenant t ON t.id = tm.tenant_id"
                    + " WHERE t.id = knowledge_base.tenant_id AND tm.user_id = {0} AND t.status = 'active'"
                    + " AND (tm.role IN ('OWNER','ADMIN') OR knowledge_base.visibility = 'org')";

    /**
     * 停用组织的全部内容对非超管一律下线。
     *
     * <p>列表必须和 {@code canRead} 同口径，否则停用的库仍出现在门户首页，点进去才 404。</p>
     */
    private static final String TENANT_NOT_DISABLED =
            "NOT EXISTS (SELECT 1 FROM tenant t WHERE t.id = knowledge_base.tenant_id AND t.status <> 'active')";

    private final KnowledgeBaseMapper kbMapper;
    private final KbMemberMapper kbMemberMapper;
    private final TenantMapper tenantMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final AuditService auditService;

    @Override
    public boolean canRead(KnowledgeBase kb, LoginUser user) {
        if (kb == null) {
            return false;
        }
        if (isSuperAdmin(user)) {
            return true;
        }
        OrgFacts facts = orgFacts(kb.getTenantId(), user == null ? null : user.id());
        if (!facts.tenantActive()) {
            // 组织被封禁即整库冻结，public 也一并下线：责任主体还在，内容先不出门
            return false;
        }
        if (KnowledgeBase.VISIBILITY_PUBLIC.equalsIgnoreCase(kb.getVisibility())) {
            return true;
        }
        if (user == null) {
            return false;
        }
        // 名单与创建者都只在组织内生效：人离开组织后，库对前创建者按陌生人处理（§9 / I5）
        if (facts.member() && (facts.orgAdmin() || isCreator(kb, user) || isKbMember(kb.getId(), user.id()))) {
            return true;
        }
        // 未列值一律按最严格处理（fail closed）：'org' 之外出现脏值不应当变成放行
        return KnowledgeBase.VISIBILITY_ORG.equalsIgnoreCase(kb.getVisibility()) && facts.member();
    }

    @Override
    public boolean can(KnowledgeBase kb, LoginUser user, KbAction action) {
        if (action.isRead()) {
            return canRead(kb, user);
        }
        if (user == null || kb == null) {
            return false;
        }
        if (isSuperAdmin(user)) {
            return true;
        }
        OrgFacts facts = orgFacts(kb.getTenantId(), user.id());
        if (!facts.tenantActive()) {
            return false;
        }
        // 治理权：组织 OWNER/ADMIN 与本库创建者，且都必须是该组织的活跃成员
        if (facts.member() && (facts.orgAdmin() || isCreator(kb, user))) {
            return switch (action.axis()) {
                case MAINTAIN, GOVERN, SHARE_OWNER -> true;
                // 组织轴动作只经 canInTenant / requireTenant 判定，不针对已存在的库放行
                case READ, TENANT_MEMBER, TENANT_ADMIN, TENANT_OWNER -> false;
                // 平台轴不经任何组织身份产生，只由 AdminInterceptor 把关
                case PLATFORM -> false;
            };
        }
        return switch (action.axis()) {
            // 写轴必须先有读轴：private + org_all 这类自相矛盾的配置不授予写权限
            case MAINTAIN -> canRead(kb, user) && maintainByScope(kb, user.id(), facts);
            case READ, GOVERN, SHARE_OWNER, TENANT_MEMBER, TENANT_ADMIN, TENANT_OWNER, PLATFORM -> false;
        };
    }

    @Override
    public boolean canInTenant(Tenant tenant, LoginUser user, KbAction action) {
        if (tenant == null) {
            return false;
        }
        if (isSuperAdmin(user)) {
            return true;
        }
        return permissionsOfTenant(roleOf(tenant, user)).contains(action);
    }

    @Override
    public OrgRole roleOf(Tenant tenant, LoginUser user) {
        if (tenant == null) {
            return OrgRole.none();
        }
        OrgFacts facts = orgFacts(tenant.getId(), user == null ? null : user.id());
        return new OrgRole(facts.member(), facts.orgAdmin(), facts.owner());
    }

    @Override
    public KnowledgeBase requireKb(Long kbId, KbAction action, LoginUser user) {
        KnowledgeBase kb = kbId == null ? null : kbMapper.selectById(kbId);
        if (kb == null || !canRead(kb, user)) {
            // 读权限不满足一律 404，与「不存在」不可区分（§2.5）
            throw BizException.notFound("知识库不存在");
        }
        if (!can(kb, user, action)) {
            throw BizException.forbidden("无权限执行该操作");
        }
        auditBypass(kb, user, action);
        return kb;
    }

    @Override
    public boolean canGovernShare(Share share, KnowledgeBase kb, LoginUser user) {
        // 分享治理权看「该分享的创建者」与组织管理员，不看库创建者（§2.4）
        if (share == null || user == null) {
            return false;
        }
        if (isSuperAdmin(user)) {
            return true;
        }
        return Objects.equals(share.getOwnerId(), user.id())
                || (kb != null && orgFacts(kb.getTenantId(), user.id()).orgAdmin());
    }

    @Override
    public Share requireShare(Share share, KbAction action, LoginUser user) {
        if (share == null) {
            throw BizException.notFound("分享不存在");
        }
        KnowledgeBase kb = kbMapper.selectById(share.getKbId());
        if (kb == null || !canRead(kb, user)) {
            throw BizException.notFound("分享不存在");
        }
        if (!canGovernShare(share, kb, user)) {
            if (!isSuperAdmin(user)) {
                throw BizException.forbidden("无权限操作该分享");
            }
            auditService.superBypass(action, user.id(), kb.getTenantId(), kb.getId(), null);
        }
        return share;
    }

    @Override
    public Tenant requireTenant(Tenant tenant, KbAction action, LoginUser user) {
        // 先成员门、再动作判定：合成一步会让普通成员拿到 404，以为组织被删了
        requireMember(tenant, user);
        if (!canInTenant(tenant, user, action)) {
            throw BizException.forbidden("无权限执行该操作");
        }
        if (isSuperAdmin(user)) {
            auditService.superBypass(action, user.id(), tenant.getId(), null, null);
        }
        return tenant;
    }

    @Override
    public Tenant requireMember(Tenant tenant, LoginUser user) {
        if (tenant == null) {
            throw BizException.notFound("组织不存在");
        }
        // 非成员连组织存在性都不确认（§2.5）。这一条与动作判定分开，是因为成员门要跑在
        // 每个 /api/console/** 请求上，动作判定与留痕留在 requireTenant 里。
        if (!isSuperAdmin(user) && !orgFacts(tenant.getId(), user == null ? null : user.id()).member()) {
            throw BizException.notFound("组织不存在");
        }
        return tenant;
    }

    @Override
    public void applyVisibleScope(LambdaQueryWrapper<KnowledgeBase> wrapper, LoginUser user, Long tenantId) {
        if (tenantId != null) {
            wrapper.eq(KnowledgeBase::getTenantId, tenantId);
        }
        if (isSuperAdmin(user)) {
            // 列表不加留痕：搜索框逐字符联想会把审计刷满。超管真正读到内容发生在
            // requireKb（详情 / 正文）那一步，留痕集中在那里。
            return;
        }
        wrapper.apply(TENANT_NOT_DISABLED);
        if (user == null) {
            wrapper.eq(KnowledgeBase::getVisibility, KnowledgeBase.VISIBILITY_PUBLIC);
            return;
        }
        wrapper.and(w -> w.eq(KnowledgeBase::getVisibility, KnowledgeBase.VISIBILITY_PUBLIC)
                .or(o -> o.apply("EXISTS (" + ORG_MEMBER_EXISTS + ")", user.id())
                        .eq(KnowledgeBase::getOwnerId, user.id()))
                .or().apply("EXISTS (" + KB_MEMBER_EXISTS + ")", user.id())
                .or().apply("EXISTS (" + TENANT_VISIBLE_EXISTS + ")", user.id()));
    }

    @Override
    public void applyParticipatingScope(LambdaQueryWrapper<KnowledgeBase> wrapper, LoginUser user) {
        if (user == null) {
            // 游客不参与任何库：给出恒假条件，而不是不过滤
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.and(w -> w.eq(KnowledgeBase::getOwnerId, user.id())
                .or(o -> o.apply("EXISTS (" + ORG_MEMBER_EXISTS + ")", user.id())
                        .eq(KnowledgeBase::getOwnerId, user.id()))
                .or().apply("EXISTS (" + KB_MEMBER_EXISTS + ")", user.id()));
    }

    @Override
    public void applyTenantActiveScope(LambdaQueryWrapper<KnowledgeBase> wrapper) {
        wrapper.apply(TENANT_NOT_DISABLED);
    }

    // ------------------------------------------------------------------ 内部判定

    private boolean maintainByScope(KnowledgeBase kb, Long userId, OrgFacts facts) {
        String scope = kb.getMaintainScope() == null
                ? KnowledgeBase.MAINTAIN_OWNER_ONLY : kb.getMaintainScope();
        return switch (scope) {
            // 两档都以组织成员为前提：名单是组织内的加法，不跨组织授权（§9）
            case KnowledgeBase.MAINTAIN_MEMBERS -> facts.member() && isKbEditor(kb.getId(), userId);
            case KnowledgeBase.MAINTAIN_ORG_ALL -> facts.member();
            default -> false;
        };
    }

    private boolean isKbMember(Long kbId, Long userId) {
        return kbMemberMapper.selectCount(Wrappers.<KbMember>lambdaQuery()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, userId)) > 0;
    }

    private boolean isKbEditor(Long kbId, Long userId) {
        return kbMemberMapper.selectCount(Wrappers.<KbMember>lambdaQuery()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, userId)
                .eq(KbMember::getRole, KbMember.ROLE_EDITOR)) > 0;
    }

    private OrgFacts orgFacts(Long tenantId, Long userId) {
        Tenant tenant = tenantId == null ? null : tenantMapper.selectById(tenantId);
        // 组织不存在或已停用：该组织内的成员身份一律失效，不能让「换个入口」绕过停用
        if (tenant == null || !tenant.isActive()) {
            return new OrgFacts(false, false, false, false);
        }
        if (userId == null) {
            return new OrgFacts(true, false, false, false);
        }
        TenantMember member = tenantMemberMapper.selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId)
                .eq(TenantMember::getUserId, userId)
                .last("LIMIT 1"));
        if (member == null) {
            return new OrgFacts(true, false, false, false);
        }
        return new OrgFacts(true, true, member.isOrgAdmin(), member.isOwner());
    }

    /**
     * 超管绕过留痕：把访问者当成普通成员再判一次，只有「本来不行」才记，
     * 避免管理员在自己个人组织内的正常操作也刷审计。
     */
    private void auditBypass(KnowledgeBase kb, LoginUser user, KbAction action) {
        if (!isSuperAdmin(user)) {
            return;
        }
        LoginUser plain = new LoginUser(user.id(), user.username(), user.displayName(), user.email(),
                User.ROLE_USER, user.status());
        if (!can(kb, plain, action)) {
            auditService.superBypass(action, user.id(), kb.getTenantId(), kb.getId(), null);
        }
    }

    private static boolean isSuperAdmin(LoginUser user) {
        return user != null && user.isAdmin();
    }

    private static boolean isCreator(KnowledgeBase kb, LoginUser user) {
        return user != null && Objects.equals(kb.getOwnerId(), user.id());
    }

    private record OrgFacts(boolean tenantActive, boolean member, boolean orgAdmin, boolean owner) {
    }
}
