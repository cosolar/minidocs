package cn.minims.minidocs.permission;

import cn.minims.minidocs.audit.entity.AuditLog;
import cn.minims.minidocs.audit.mapper.AuditLogMapper;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.kb.entity.KbMember;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbMemberMapper;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两轴裁决矩阵（规范 §2.2 / §2.3 / §2.4 / §2.5）。
 *
 * <p>与 {@code PermissionBaselineTest} 的分工：那边逐条看守 v1 断言的去向，这里正面铺矩阵。
 * 组织与名单直接落表 —— 建组织接口在 M3，判定轴不该等它。</p>
 */
@DisplayName("M2 裁决矩阵")
class AccessServiceTest extends MiniDocsTestBase {

    @Autowired
    private AccessService accessService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private KbMemberMapper kbMemberMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private TenantService tenantService;

    private LoginUser owner;
    private LoginUser admin;
    private LoginUser member;
    private LoginUser outsider;
    private LoginUser root;
    private Tenant team;
    private Long orgOnlyDocKb;

    @BeforeEach
    void seedOrg() {
        owner = as(saveUser("owner", "组织拥有者", User.ROLE_USER));
        admin = as(saveUser("admin2", "组织管理员", User.ROLE_USER));
        member = as(saveUser("member", "普通成员", User.ROLE_USER));
        outsider = as(saveUser("outsider", "组织外用户", User.ROLE_USER));
        root = as(saveUser("root", "超级管理员", User.ROLE_ADMIN));
        team = teamOrg("acme");
        grant(team.getId(), owner, TenantMember.ROLE_OWNER);
        grant(team.getId(), admin, TenantMember.ROLE_ADMIN);
        grant(team.getId(), member, TenantMember.ROLE_MEMBER);
        orgOnlyDocKb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG,
                KnowledgeBase.MAINTAIN_OWNER_ONLY);
    }

    private Tenant teamOrg(String slug) {
        Tenant tenant = new Tenant();
        tenant.setSlug(slug);
        tenant.setName(slug);
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setOwnerUserId(owner.id());
        tenant.setJoinPolicy(Tenant.JOIN_REQUEST);
        tenant.setDiscoverable(true);
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenantMapper.insert(tenant);
        return tenant;
    }

    private void grant(Long tenantId, LoginUser user, String role) {
        TenantMember row = new TenantMember();
        row.setTenantId(tenantId);
        row.setUserId(user.id());
        row.setRole(role);
        row.setJoinedFrom("bootstrap");
        tenantMemberMapper.insert(row);
    }

    private Long newKb(Long tenantId, Long creatorId, String visibility, String maintainScope) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenantId);
        kb.setOwnerId(creatorId);
        kb.setName("库");
        // 每个用例一套 fixtures，slug 只需在本组织内不重复
        kb.setSlug("kb-" + (kbService.count() + 1));
        kb.setStorageKey(StorageKey.of(tenantId, kb.getSlug()));
        kb.setVisibility(visibility);
        kb.setMaintainScope(maintainScope);
        kb.setDocCount(0);
        kbService.save(kb);
        return kb.getId();
    }

    private void roster(Long kbId, LoginUser user, String role) {
        kbMemberMapper.delete(Wrappers.<KbMember>lambdaQuery()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, user.id()));
        KbMember row = new KbMember();
        row.setKbId(kbId);
        row.setUserId(user.id());
        row.setRole(role);
        row.setGrantedBy(owner.id());
        kbMemberMapper.insert(row);
    }

    private void orgMemberRole(LoginUser user, String role) {
        revokeMembership(user);
        grant(team.getId(), user, role);
    }

    private void revokeMembership(LoginUser user) {
        tenantMemberMapper.delete(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, team.getId())
                .eq(TenantMember::getUserId, user.id()));
    }

    // ---------------------------------------------------------------- 读轴

    @Nested
    @DisplayName("读轴：visibility（§2.2）")
    class ReadAxis {

        @Test
        @DisplayName("public 对游客与组织外用户均可读")
        void publicIsReadableByEveryone() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PUBLIC,
                    KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(accessService.canRead(kb(kb), null)).isTrue();
            assertThat(accessService.canRead(kb(kb), outsider)).isTrue();
        }

        @Test
        @DisplayName("org 只对内可读：组织外用户与游客都不行")
        void orgIsReadableOnlyInside() {
            assertThat(accessService.canRead(kb(orgOnlyDocKb), member)).isTrue();
            assertThat(accessService.canRead(kb(orgOnlyDocKb), outsider)).isFalse();
            assertThat(accessService.canRead(kb(orgOnlyDocKb), null)).isFalse();
        }

        @Test
        @DisplayName("private 对创建者、组织管理员可读，普通成员不可读")
        void privateHidesFromPlainMember() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PRIVATE,
                    KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(accessService.canRead(kb(kb), owner)).isTrue();
            assertThat(accessService.canRead(kb(kb), admin)).isTrue();
            assertThat(accessService.canRead(kb(kb), member)).isFalse();
        }

        @Test
        @DisplayName("他人视角下 org/private 库统一 404：ID 探测得不到区分")
        void invisibleKbLooksMissing() {
            assertBizError(ErrorCode.NOT_FOUND, () -> accessService.requireKb(orgOnlyDocKb, KbAction.KB_VIEW, outsider));
            assertBizError(ErrorCode.NOT_FOUND, () -> accessService.requireKb(orgOnlyDocKb, KbAction.DOC_READ, null));
        }

        @Test
        @DisplayName("脏 visibility 一律按最严处理，不当成 org 放行")
        void dirtyVisibilityFailsClosed() {
            Long kb = newKb(team.getId(), owner.id(), "PUBLICLY", KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(accessService.canRead(kb(kb), member)).isFalse();
            assertThat(accessService.canRead(kb(kb), outsider)).isFalse();
        }

        @Test
        @DisplayName("组织停用后成员身份失效：创建者本人的写权限也一并冻结")
        void disabledTenantRevokesMembership() {
            team.setStatus(Tenant.STATUS_DISABLED);
            tenantMapper.updateById(team);

            assertThat(accessService.canRead(kb(orgOnlyDocKb), member)).isFalse();
            assertThat(accessService.can(kb(orgOnlyDocKb), owner, KbAction.DOC_WRITE)).isFalse();
            // 解封即恢复：冻结由组织状态表达，不改任何成员行
            team.setStatus(Tenant.STATUS_ACTIVE);
            tenantMapper.updateById(team);
            assertThat(accessService.can(kb(orgOnlyDocKb), owner, KbAction.DOC_WRITE)).isTrue();
        }
    }

    // ---------------------------------------------------------------- 写轴

    @Nested
    @DisplayName("写轴：maintain_scope 三档（§2.3）")
    class WriteAxis {

        @Test
        @DisplayName("owner_only：仅创建者与组织管理员")
        void ownerOnly() {
            assertThat(accessService.can(kb(orgOnlyDocKb), owner, KbAction.DOC_WRITE)).isTrue();
            assertThat(accessService.can(kb(orgOnlyDocKb), admin, KbAction.DOC_WRITE)).isTrue();
            assertThat(accessService.can(kb(orgOnlyDocKb), member, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("members：名单内 EDITOR 可写，VIEWER 与名单外不可")
        void members() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG,
                    KnowledgeBase.MAINTAIN_MEMBERS);
            roster(kb, member, KbMember.ROLE_EDITOR);
            assertThat(accessService.can(kb(kb), member, KbAction.DOC_DELETE)).isTrue();

            roster(kb, member, KbMember.ROLE_VIEWER);
            assertThat(accessService.can(kb(kb), member, KbAction.DOC_DELETE)).isFalse();
            assertThat(accessService.can(kb(kb), member, KbAction.DOC_READ)).isTrue();
        }

        @Test
        @DisplayName("org_all：全组织可写，但仍要先过读轴")
        void orgAllStillNeedsRead() {
            Long orgKb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG,
                    KnowledgeBase.MAINTAIN_ORG_ALL);
            assertThat(accessService.can(kb(orgKb), member, KbAction.DOC_WRITE)).isTrue();

            Long privateKb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PRIVATE,
                    KnowledgeBase.MAINTAIN_ORG_ALL);
            // 普通成员读不到 private，故 org_all 也不授予写：不自相矛盾地开后门
            assertThat(accessService.can(kb(privateKb), member, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("有读无写 → 403，与不可见的 404 区分开")
        void readableButNotWritableIsForbidden() {
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> accessService.requireKb(orgOnlyDocKb, KbAction.DOC_WRITE, member));
        }

        @Test
        @DisplayName("未知 maintain_scope 一律按最严处理，不当成 org_all 放行")
        void dirtyScopeFailsClosed() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG, "EVERYONE");

            assertThat(accessService.can(kb(kb), member, KbAction.DOC_WRITE)).isFalse();
            assertThat(accessService.can(kb(kb), owner, KbAction.DOC_WRITE)).isTrue();
            // 不再断言 null 分支：V3 已把 maintain_scope 收紧为 NOT NULL，谓词里的兜底到不了
        }
    }

    // ---------------------------------------------------------------- 治理轴

    @Nested
    @DisplayName("治理轴：名单是加法（§2.3）")
    class Governance {

        @Test
        @DisplayName("EDITOR 能改内容，不能删库、不能改可见性")
        void editorCannotGovern() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG,
                    KnowledgeBase.MAINTAIN_MEMBERS);
            roster(kb, member, KbMember.ROLE_EDITOR);

            assertThat(accessService.can(kb(kb), member, KbAction.DOC_WRITE)).isTrue();
            assertThat(accessService.can(kb(kb), member, KbAction.KB_DELETE)).isFalse();
            assertThat(accessService.can(kb(kb), member, KbAction.KB_SET_VISIBILITY)).isFalse();
            assertBizError(ErrorCode.FORBIDDEN, () -> accessService.requireKb(kb, KbAction.KB_DELETE, member));
        }

        @Test
        @DisplayName("组织 ADMIN 与 OWNER 同权治理他人建的库（刻意的团队兜底）")
        void orgAdminGovernsOthersKb() {
            assertThat(accessService.can(kb(orgOnlyDocKb), admin, KbAction.KB_DELETE)).isTrue();
            assertThat(accessService.can(kb(orgOnlyDocKb), admin, KbAction.KB_SET_VISIBILITY)).isTrue();
        }

        @Test
        @DisplayName("组织外用户写进名单什么也拿不到：名单是组织内的加法（§9 不穿透）")
        void outsiderInRosterGetsNothing() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PUBLIC,
                    KnowledgeBase.MAINTAIN_MEMBERS);
            roster(kb, outsider, KbMember.ROLE_EDITOR);

            assertThat(accessService.can(kb(kb), outsider, KbAction.DOC_WRITE)).isFalse();
            assertThat(accessService.can(kb(kb), outsider, KbAction.KB_DELETE)).isFalse();
        }

        @Test
        @DisplayName("移出组织即失效：名单行还在，也不能继续写")
        void removedMemberLosesRosterRights() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG,
                    KnowledgeBase.MAINTAIN_MEMBERS);
            roster(kb, member, KbMember.ROLE_EDITOR);
            assertThat(accessService.can(kb(kb), member, KbAction.DOC_WRITE)).isTrue();

            revokeMembership(member);

            assertThat(accessService.can(kb(kb), member, KbAction.DOC_WRITE)).isFalse();
            assertThat(accessService.canRead(kb(kb), member)).isFalse();
        }

        @Test
        @DisplayName("创建者退出组织后，自己建的库连存在性都不再确认")
        void creatorLeavingOrgLosesOwnKb() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PRIVATE,
                    KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(accessService.can(kb(kb), owner, KbAction.KB_DELETE)).isTrue();

            revokeMembership(owner);

            // 库属于组织：人走了不该继续握着删除权，否则离职变成无声的权限残留
            assertThat(accessService.canRead(kb(kb), owner)).isFalse();
            assertThat(accessService.can(kb(kb), owner, KbAction.DOC_WRITE)).isFalse();
            assertThat(accessService.can(kb(kb), owner, KbAction.KB_DELETE)).isFalse();
            assertBizError(ErrorCode.NOT_FOUND, () -> accessService.requireKb(kb, KbAction.KB_VIEW, owner));
        }
    }

    // ---------------------------------------------------------------- 出参形状

    @Nested
    @DisplayName("出参形状：permissionsOf 逐轴给按钮（§4.2）")
    class PermissionVector {

        @Test
        @DisplayName("能读不能写的普通成员：只有读轴两项")
        void readOnlyMember() {
            assertThat(accessService.permissionsOf(kb(orgOnlyDocKb), member))
                    .containsExactly(KbAction.KB_VIEW, KbAction.DOC_READ);
        }

        @Test
        @DisplayName("名单 EDITOR：读轴 + 写轴全集，治理轴一项都不给")
        void editorGetsWholeWriteAxis() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG,
                    KnowledgeBase.MAINTAIN_MEMBERS);
            roster(kb, member, KbMember.ROLE_EDITOR);

            assertThat(accessService.permissionsOf(kb(kb), member))
                    .containsExactly(KbAction.KB_VIEW, KbAction.DOC_READ,
                            KbAction.KB_EDIT_META, KbAction.DOC_WRITE, KbAction.DOC_RENAME,
                            KbAction.DOC_MOVE, KbAction.DOC_DELETE, KbAction.DOC_UPLOAD_ASSET,
                            KbAction.DOC_IMPORT, KbAction.SHARE_CREATE);
        }

        @Test
        @DisplayName("创建者在 owner_only 下三轴齐给：治理轴不受维护档位影响（§2.4）")
        void creatorOwnsAllThreeAxes() {
            assertThat(accessService.permissionsOf(kb(orgOnlyDocKb), owner))
                    .contains(KbAction.DOC_WRITE, KbAction.KB_DELETE, KbAction.KB_SET_VISIBILITY,
                            KbAction.KB_MEMBER_MANAGE);
        }

        @Test
        @DisplayName("组织 ADMIN 能写 owner_only 的库：出参如实反映这条刻意的兜底（§1.2）")
        void orgAdminSeesWriteOnOwnerOnly() {
            assertThat(accessService.permissionsOf(kb(orgOnlyDocKb), admin))
                    .contains(KbAction.DOC_WRITE);
        }

        @Test
        @DisplayName("不可见的库给出空集：游客连读轴都不该拿到")
        void invisibleKbYieldsEmpty() {
            assertThat(accessService.permissionsOf(kb(orgOnlyDocKb), outsider)).isEmpty();
            assertThat(accessService.permissionsOf(kb(orgOnlyDocKb), null)).isEmpty();
        }

        @Test
        @DisplayName("null 库不炸：列表里混进脏数据时前端只是拿不到按钮")
        void nullKbIsEmptyNotError() {
            assertThat(accessService.permissionsOf(null, member)).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 组织轴

    @Nested
    @DisplayName("组织轴与 403/404 分界（§2.5）")
    class TenantAxis {

        @Test
        @DisplayName("成员可在本组织建库，组织外用户得到 404")
        void createRequiresMembership() {
            assertThat(accessService.canInTenant(team, member, KbAction.KB_CREATE)).isTrue();
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireTenant(team, KbAction.KB_CREATE, outsider));
        }

        @Test
        @DisplayName("成员权限不足 → 403，不是 404：他已经知道这个组织存在")
        void memberLackingAdminGetsForbidden() {
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> accessService.requireTenant(team, KbAction.TENANT_MEMBER_MANAGE, member));
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> accessService.requireTenant(team, KbAction.TENANT_DELETE, admin));
        }

        @Test
        @DisplayName("TENANT_DELETE 只看 OWNER")
        void onlyOwnerCanDeleteOrg() {
            assertThat(accessService.canInTenant(team, owner, KbAction.TENANT_DELETE)).isTrue();
            assertThat(accessService.canInTenant(team, admin, KbAction.TENANT_DELETE)).isFalse();
        }

        @Test
        @DisplayName("AUDIT_READ 与 TENANT_JOIN_REVIEW 归组织管理员")
        void adminAxis() {
            assertThat(accessService.canInTenant(team, admin, KbAction.AUDIT_READ)).isTrue();
            assertThat(accessService.canInTenant(team, admin, KbAction.TENANT_JOIN_REVIEW)).isTrue();
            assertThat(accessService.canInTenant(team, member, KbAction.AUDIT_READ)).isFalse();
        }

        @Test
        @DisplayName("游客访问组织上下文 → 404")
        void anonymousGetsNotFound() {
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireTenant(team, KbAction.KB_CREATE, null));
        }

        @Test
        @DisplayName("组织可被发现也不放行陌生人：详情页仍要成员身份，否则 404")
        void discoverableOrgStillHiddenFromOutsider() {
            assertThat(team.isPublicProfile()).isTrue();
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireTenant(team, KbAction.TENANT_VIEW, outsider));
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireTenant(team, KbAction.TENANT_VIEW, null));
            // 成员视角：详情页放行，不再要求更高档位
            assertThat(accessService.requireTenant(team, KbAction.TENANT_VIEW, member)).isSameAs(team);
        }
    }

    // ---------------------------------------------------------------- 分享轴

    @Nested
    @DisplayName("分享治理：看创建者，不看库归属（§2.4）")
    class ShareAxis {

        @Test
        @DisplayName("名单内 EDITOR 能撤销自己建的分享，即使他管不了库")
        void creatorCanRevokeOwnShare() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_ORG,
                    KnowledgeBase.MAINTAIN_MEMBERS);
            roster(kb, member, KbMember.ROLE_EDITOR);
            Share share = share(kb, member.id());

            assertThat(accessService.requireShare(share, KbAction.SHARE_REVOKE, member)).isSameAs(share);
        }

        @Test
        @DisplayName("他人的分享不可撤销：有读权限也只是 403")
        void cannotRevokeOthersShare() {
            Share share = share(orgOnlyDocKb, owner.id());
            orgMemberRole(admin, TenantMember.ROLE_MEMBER);

            assertBizError(ErrorCode.FORBIDDEN, () -> accessService.requireShare(share, KbAction.SHARE_REVOKE, admin));
        }

        @Test
        @DisplayName("组织 ADMIN 可撤销任一条分享（兜底清链接）")
        void orgAdminCanRevokeAnyShare() {
            Share share = share(orgOnlyDocKb, owner.id());
            assertThat(accessService.requireShare(share, KbAction.SHARE_REVOKE, admin)).isSameAs(share);
        }

        @Test
        @DisplayName("读不到库的人对分享也是 404")
        void invisibleKbHidesShare() {
            Share share = share(orgOnlyDocKb, owner.id());
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireShare(share, KbAction.SHARE_VIEW_STATS, outsider));
        }

        private Share share(Long kbId, Long creatorId) {
            Share share = new Share();
            share.setToken("S" + System.nanoTime());
            share.setOwnerId(creatorId);
            share.setKbId(kbId);
            share.setScope(Share.SCOPE_KB);
            share.setViews(0);
            share.setRevoked(0);
            share.setInvalid(0);
            return share;
        }
    }

    // ---------------------------------------------------------------- 超管

    @Nested
    @DisplayName("超管：bypass 与留痕（I7）")
    class SuperAdmin {

        @Test
        @DisplayName("跨组织放行")
        void reachesEveryKb() {
            assertThat(accessService.canRead(kb(orgOnlyDocKb), root)).isTrue();
            assertThat(accessService.can(kb(orgOnlyDocKb), root, KbAction.KB_DELETE)).isTrue();
        }

        @Test
        @DisplayName("越权式放行必须留痕：动作在审计里能查到")
        void bypassIsAudited() {
            accessService.requireKb(orgOnlyDocKb, KbAction.KB_DELETE, root);

            assertThat(auditOf(root, KbAction.KB_DELETE, orgOnlyDocKb)).isNotEmpty();
        }

        @Test
        @DisplayName("超管在自己个人组织内的正常操作不刷审计")
        void ordinaryAdminTrafficIsNotAudited() {
            Long ownKb = newKb(personalTenantId(root), root.id(), KnowledgeBase.VISIBILITY_PRIVATE,
                    KnowledgeBase.MAINTAIN_OWNER_ONLY);
            accessService.requireKb(ownKb, KbAction.KB_DELETE, root);

            assertThat(auditOf(root, KbAction.KB_DELETE, ownKb)).isEmpty();
        }

        @Test
        @DisplayName("超管读他人 private 库同样留痕：越权式访问不看动作读写")
        void bypassReadIsAudited() {
            accessService.requireKb(orgOnlyDocKb, KbAction.KB_VIEW, root);

            assertThat(auditOf(root, KbAction.KB_VIEW, orgOnlyDocKb)).hasSize(1);
        }

        @Test
        @DisplayName("超管读 public 库不留痕：他本来就有权限，否则审计全是噪音")
        void permittedReadIsNotAudited() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PUBLIC,
                    KnowledgeBase.MAINTAIN_OWNER_ONLY);
            accessService.requireKb(kb, KbAction.KB_VIEW, root);

            assertThat(auditOf(root, KbAction.KB_VIEW, kb)).isEmpty();
        }

        @Test
        @DisplayName("列表可见集：超管看得见跨组织的 private 库，判定本身不留痕")
        void visibleScopeReachesEveryKbWithoutAuditing() {
            assertThat(visibleKbIds(root)).contains(orgOnlyDocKb);

            assertThat(auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                    .eq(AuditLog::getActorUserId, root.id()))).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 可见集

    @Nested
    @DisplayName("可见集与谓词同口径（applyVisibleScope）")
    class VisibleScope {

        @Test
        @DisplayName("成员在列表里看得见本组织的 org 库")
        void orgMemberSeesOrgKb() {
            assertThat(visibleKbIds(member)).contains(orgOnlyDocKb);
            assertThat(visibleKbIds(outsider)).doesNotContain(orgOnlyDocKb);
        }

        @Test
        @DisplayName("停用组织的库从可见集消失：不会列表有、详情 404")
        void disabledTenantDropsKbsFromList() {
            assertThat(visibleKbIds(owner)).contains(orgOnlyDocKb);

            team.setStatus(Tenant.STATUS_DISABLED);
            tenantMapper.updateById(team);

            assertThat(visibleKbIds(owner)).doesNotContain(orgOnlyDocKb);
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireKb(orgOnlyDocKb, KbAction.KB_VIEW, owner));
        }

        @Test
        @DisplayName("停用组织后仍出现在 public 列表里的只可能是别的组织的库")
        void guestSeesOnlyPublicOfActiveOrgs() {
            Long publicKb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PUBLIC,
                    KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(visibleKbIds(null)).contains(publicKb);

            team.setStatus(Tenant.STATUS_DISABLED);
            tenantMapper.updateById(team);

            // 封禁组织的 public 库必须一起下线：门户首页不该继续分发它的内容
            assertThat(visibleKbIds(null)).doesNotContain(publicKb);
        }

        @Test
        @DisplayName("创建者退出组织后，自己建的库也从列表消失：SQL 与 canRead 同口径")
        void creatorLeavingOrgDropsOwnKbFromList() {
            Long kb = newKb(team.getId(), owner.id(), KnowledgeBase.VISIBILITY_PRIVATE,
                    KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(visibleKbIds(owner)).contains(kb);

            revokeMembership(owner);

            assertThat(visibleKbIds(owner)).doesNotContain(kb);
            assertThat(visibleKbIds(owner, true)).doesNotContain(kb);
        }

        @Test
        @DisplayName("「我参与的」只是筛选项：不放大也不改变可见集")
        void participatingScopeIntersectsVisibleScope() {
            roster(orgOnlyDocKb, member, KbMember.ROLE_EDITOR);
            assertThat(visibleKbIds(member, true)).contains(orgOnlyDocKb);
            assertThat(visibleKbIds(outsider, true)).doesNotContain(orgOnlyDocKb);
            assertThat(visibleKbIds(null, true)).isEmpty();
        }
    }

    private List<Long> visibleKbIds(LoginUser viewer) {
        return visibleKbIds(viewer, false);
    }

    private List<Long> visibleKbIds(LoginUser viewer, boolean mine) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.<KnowledgeBase>lambdaQuery();
        accessService.applyVisibleScope(wrapper, viewer, null);
        if (mine) {
            accessService.applyParticipatingScope(wrapper, viewer);
        }
        return kbService.list(wrapper).stream().map(KnowledgeBase::getId).toList();
    }

    private KnowledgeBase kb(Long id) {
        return kbService.getById(id);
    }

    private List<AuditLog> auditOf(LoginUser actor, KbAction action, Long kbId) {
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getActorUserId, actor.id())
                .eq(AuditLog::getAction, action.name())
                .eq(AuditLog::getKbId, kbId));
    }

    private Long personalTenantId(LoginUser user) {
        return tenantService.ensurePersonalTenant(user.id()).getId();
    }
}
