package cn.minims.minidocs.tenant;

import cn.minims.minidocs.auth.dto.AuthDtos.RegisterRequest;
import cn.minims.minidocs.auth.service.AuthService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.dto.KbDtos.RosterGrantRequest;
import cn.minims.minidocs.kb.entity.KbMember;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbMemberMapper;
import cn.minims.minidocs.kb.service.KbMemberService;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.controller.MeController;
import cn.minims.minidocs.tenant.dto.TenantDtos.AddMemberRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.CreateOrgRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.JoinRequestMessage;
import cn.minims.minidocs.tenant.dto.TenantDtos.MeVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.MemberVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.OrgVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.TenantBrief;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateOrgRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateRoleRequest;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantJoinRequest;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantJoinRequestMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.tenant.service.TenantMemberService;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.SettingsService;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M3 组织与成员：注册流、组织 CRUD、成员治理（I1 / §2.4 注）、入组申请、维护名单。
 *
 * <p>全部走服务层，不起 HTTP：判定与规则都在服务里，控制器只是形状。</p>
 *
 * @see <a href="file:../../../../../docs/多租户知识库权限与功能规范.md">规范 §3</a>
 */
@DisplayName("M3 组织与成员")
class OrgFlowTest extends MiniDocsTestBase {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService memberService;
    @Autowired
    private TenantMemberMapper memberMapper;
    @Autowired
    private TenantJoinRequestMapper joinRequestMapper;
    @Autowired
    private KnowledgeBaseService kbService;
    @Autowired
    private KbMemberService rosterService;
    @Autowired
    private KbMemberMapper rosterMapper;
    @Autowired
    private AccessService accessService;
    @Autowired
    private MeController meController;
    @Autowired
    private SettingsService settingsService;

    private LoginUser alice;
    private LoginUser bob;
    private LoginUser carol;
    private Tenant org;

    @BeforeEach
    void seed() {
        alice = as(saveUser("alice", "爱丽丝", User.ROLE_USER));
        bob = as(saveUser("bob", "鲍勃", User.ROLE_USER));
        carol = as(saveUser("carol", "卡萝", User.ROLE_USER));
        org = createOrg("acme", alice);
        grant(org, bob, TenantMember.ROLE_MEMBER);
        grant(org, carol, TenantMember.ROLE_ADMIN);
    }

    // ---------------------------------------------------------------- 注册与个人组织

    @Nested
    @DisplayName("注册与个人组织（§3.1 / F1）")
    class Registration {

        @Test
        @DisplayName("注册即 active，不再有平台待审队列")
        void registerIsImmediatelyUsable() {
            var vo = authService.register(new RegisterRequest("dave", "Passw0rd!", "戴夫", "dave@example.com"));

            User saved = userService.getById(vo.id());
            assertThat(saved.getStatus()).isEqualTo(User.STATUS_ACTIVE);
            assertThat(saved.isActive()).isTrue();
        }

        @Test
        @DisplayName("注册事务内建好个人组织与 OWNER 成员行")
        void registerBootstrapsPersonalOrg() {
            var vo = authService.register(new RegisterRequest("erin", "Passw0rd!", "艾琳", null));

            Tenant personal = tenantService.ensurePersonalTenant(vo.id());
            assertThat(personal.getType()).isEqualTo(Tenant.TYPE_PERSONAL);
            assertThat(personal.getSlug()).isEqualTo("u-erin");
            assertThat(personal.isPublicProfile()).as("个人组织永不出现在发现列表（I3）").isFalse();
            assertThat(memberMapper.selectList(Wrappers.<TenantMember>lambdaQuery()
                    .eq(TenantMember::getTenantId, personal.getId())
                    .eq(TenantMember::getRole, TenantMember.ROLE_OWNER)))
                    .extracting(TenantMember::getUserId).containsExactly(vo.id());
        }

        @Test
        @DisplayName("个人组织 slug 用 u- 前缀，与用户名的清洗结果一致")
        void personalSlugUsesReservedPrefix() {
            var vo = authService.register(new RegisterRequest("Frank_Jr", "Passw0rd!", null, null));

            assertThat(tenantService.ensurePersonalTenant(vo.id()).getSlug()).isEqualTo("u-frank_jr");
        }
    }

    // ---------------------------------------------------------------- 组织 CRUD

    @Nested
    @DisplayName("组织创建、发现与删除（§3.2 / I3）")
    class OrgLifecycle {

        @Test
        @DisplayName("创建者自动成为 OWNER，组织出现在发现列表")
        void createMakesOwner() {
            OrgVO vo = tenantService.createTeam(new CreateOrgRequest("蓝军", "blue", "简介", null, null), bob);

            assertThat(vo.myRole()).isEqualTo(TenantMember.ROLE_OWNER);
            assertThat(vo.memberCount()).isEqualTo(1);
            assertThat(tenantService.discover(1, 20).list()).extracting("slug").contains("blue");
        }

        @Test
        @DisplayName("slug 省略时从名称生成")
        void slugDerivedFromName() {
            OrgVO vo = tenantService.createTeam(new CreateOrgRequest("我的 团队", null, null, null, null), bob);

            assertThat(vo.slug()).isEqualTo("我的-团队");
        }

        @Test
        @DisplayName("路由保留字与个人前缀一律拒绝，不静默改写")
        void reservedSlugsRejected() {
            for (String slug : List.of("w", "api", "share", "kb", "admin", "platform", "u", "u-team", "a b")) {
                assertBizError(ErrorCode.PARAM_INVALID,
                        () -> tenantService.createTeam(new CreateOrgRequest("组织", slug, null, null, null), bob));
            }
        }

        @Test
        @DisplayName("slug 全局唯一：重名报错而不是加后缀")
        void duplicateSlugRejected() {
            assertBizError(ErrorCode.ALREADY_EXISTS,
                    () -> tenantService.createTeam(new CreateOrgRequest("另一个", "acme", null, null, null), bob));
        }

        @Test
        @DisplayName("发现列表排除个人组织、不可发现与停用的组织")
        void discoverFilters() {
            Tenant personal = tenantService.ensurePersonalTenant(alice.id());
            Tenant hidden = createOrg("hidden", alice);
            tenantService.update(Wrappers.<Tenant>lambdaUpdate().eq(Tenant::getId, hidden.getId())
                    .set(Tenant::getDiscoverable, false));
            Tenant frozen = createOrg("frozen", alice);
            tenantService.update(Wrappers.<Tenant>lambdaUpdate().eq(Tenant::getId, frozen.getId())
                    .set(Tenant::getStatus, Tenant.STATUS_DISABLED));

            assertThat(tenantService.discover(1, 20).list()).extracting("slug").containsExactly("acme");
            assertThat(tenantService.discover(1, 20).list()).extracting("slug")
                    .doesNotContain(personal.getSlug(), hidden.getSlug(), frozen.getSlug());
        }

        @Test
        @DisplayName("组织资料只有 OWNER 能改，ADMIN 得到 403")
        void onlyOwnerRenames() {
            OrgVO vo = tenantService.update("acme", new UpdateOrgRequest("改名", null, null, null, null), alice);
            assertThat(vo.name()).isEqualTo("改名");

            assertBizError(ErrorCode.FORBIDDEN,
                    () -> tenantService.update("acme", new UpdateOrgRequest("越权", null, null, null, null), carol));
        }

        @Test
        @DisplayName("删组织：个人组织禁止，组织内有库时拒绝，清空后才可删")
        void deleteGuarded() {
            Tenant personal = tenantService.ensurePersonalTenant(alice.id());
            assertBizError(ErrorCode.PARAM_INVALID, () -> tenantService.delete(personal.getSlug(), alice));

            Long kbId = newKb(org, alice);
            assertBizError(ErrorCode.TENANT_NOT_EMPTY, () -> tenantService.delete("acme", alice));

            kbService.deleteKnowledgeBase(kbId, alice);
            tenantService.delete("acme", alice);
            assertThat(tenantService.findBySlug("acme")).isNull();
            assertThat(memberMapper.selectList(Wrappers.<TenantMember>lambdaQuery()
                    .eq(TenantMember::getTenantId, org.getId()))).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 成员治理

    @Nested
    @DisplayName("成员治理（I1 / §2.4 注）")
    class Members {

        @Test
        @DisplayName("按用户名拉人，角色默认 MEMBER 由入参决定")
        void addMember() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));

            MemberVO vo = memberService.add("acme", new AddMemberRequest("dave", TenantMember.ROLE_MEMBER), alice);

            assertThat(vo.userId()).isEqualTo(dave.id());
            assertThat(vo.role()).isEqualTo(TenantMember.ROLE_MEMBER);
            assertThat(memberService.list("acme", alice)).hasSize(4);
        }

        @Test
        @DisplayName("ADMIN 不能任命 ADMIN，只能加普通成员")
        void adminCannotAppointAdmin() {
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> memberService.add("acme", new AddMemberRequest("alice", TenantMember.ROLE_ADMIN), carol));
        }

        @Test
        @DisplayName("个人组织不能有第二个成员（I3）")
        void personalOrgHasNoSecondMember() {
            Tenant personal = tenantService.ensurePersonalTenant(alice.id());

            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> memberService.add(personal.getSlug(), new AddMemberRequest("bob", null), alice));
        }

        @Test
        @DisplayName("重复拉同一人报错，禁用账号拉不进来")
        void duplicateAndDisabledRejected() {
            assertBizError(ErrorCode.ALREADY_EXISTS,
                    () -> memberService.add("acme", new AddMemberRequest("bob", null), alice));

            User frozen = saveUser("frozen", "禁用者", User.ROLE_USER);
            userService.update(Wrappers.<User>lambdaUpdate().eq(User::getId, frozen.getId())
                    .set(User::getStatus, User.STATUS_DISABLED));
            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> memberService.add("acme", new AddMemberRequest("frozen", null), alice));
        }

        @Test
        @DisplayName("OWNER 行谁也动不了：移除与降级都要求先转让")
        void ownerRowIsImmutable() {
            assertBizError(ErrorCode.FORBIDDEN, () -> memberService.remove("acme", alice.id(), carol));
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> memberService.setRole("acme", alice.id(),
                            new UpdateRoleRequest(TenantMember.ROLE_MEMBER), alice));
        }

        @Test
        @DisplayName("ADMIN 之间不能互相任免，OWNER 可以")
        void adminTierIsOwnersAlone() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
            memberService.add("acme", new AddMemberRequest("dave", TenantMember.ROLE_ADMIN), alice);

            assertBizError(ErrorCode.FORBIDDEN,
                    () -> memberService.setRole("acme", dave.id(),
                            new UpdateRoleRequest(TenantMember.ROLE_MEMBER), carol));

            memberService.setRole("acme", dave.id(), new UpdateRoleRequest(TenantMember.ROLE_MEMBER), alice);
            assertThat(role(dave)).isEqualTo(TenantMember.ROLE_MEMBER);
        }

        @Test
        @DisplayName("想直接写 OWNER 当角色？只有转让这一条路")
        void ownerRoleNotAssignable() {
            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> memberService.setRole("acme", bob.id(), new UpdateRoleRequest(TenantMember.ROLE_OWNER), alice));
        }

        @Test
        @DisplayName("移除成员不能移自己，也不能移 OWNER")
        void removeGuarded() {
            assertBizError(ErrorCode.PARAM_INVALID, () -> memberService.remove("acme", alice.id(), alice));

            memberService.remove("acme", bob.id(), alice);
            assertThat(role(bob)).isNull();
        }

        @Test
        @DisplayName("移出组织连带清空维护名单：重新加入不会复活旧授权（§9）")
        void removeClearsRoster() {
            Long kbId = newKb(org, alice, KnowledgeBase.MAINTAIN_MEMBERS);
            rosterService.grant(kbId, new RosterGrantRequest(bob.id(), KbMember.ROLE_EDITOR), alice);
            assertThat(accessService.can(kb(kbId), bob, KbAction.DOC_WRITE)).isTrue();

            memberService.remove("acme", bob.id(), alice);

            assertThat(rosterMapper.selectList(Wrappers.<KbMember>lambdaQuery().eq(KbMember::getKbId, kbId)))
                    .isEmpty();
            memberService.add("acme", new AddMemberRequest("bob", null), alice);
            assertThat(accessService.can(kb(kbId), bob, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("最后一个 OWNER 既不能退出也不能被降级（I1）")
        void lastOwnerCannotLeave() {
            assertBizError(ErrorCode.FORBIDDEN, () -> memberService.leave("acme", alice));
        }

        @Test
        @DisplayName("普通成员退出后不再能读该组织的库")
        void memberCanLeave() {
            Long kbId = newKb(org, alice);
            assertThat(accessService.canRead(kb(kbId), bob)).isTrue();

            memberService.leave("acme", bob);

            assertThat(accessService.canRead(kb(kbId), bob)).isFalse();
        }

        @Test
        @DisplayName("转让 OWNER：一升一降同时发生，原 OWNER 降为 ADMIN 仍留在组织里")
        void transferOwner() {
            memberService.transferOwner("acme", bob.id(), alice);

            assertThat(role(bob)).isEqualTo(TenantMember.ROLE_OWNER);
            assertThat(role(alice)).isEqualTo(TenantMember.ROLE_ADMIN);
            assertThat(tenantService.findBySlug("acme").getOwnerUserId()).isEqualTo(bob.id());
            // 转让后原 OWNER 仍是管理员，不该失去对组织库的治理权
            Long kbId = newKb(org, alice);
            assertThat(accessService.can(kb(kbId), alice, KbAction.KB_DELETE)).isTrue();
            assertBizError(ErrorCode.FORBIDDEN, () -> memberService.transferOwner("acme", carol.id(), alice));
        }

        @Test
        @DisplayName("转让只能给成员：组织外的人拿不到 OWNER")
        void transferTargetMustBeMember() {
            LoginUser outsider = as(saveUser("outsider", "路人", User.ROLE_USER));

            assertBizError(ErrorCode.NOT_FOUND, () -> memberService.transferOwner("acme", outsider.id(), alice));
        }

        @Test
        @DisplayName("两个 OWNER 时其中一人退出：owner_user_id 跟着搬到接班的人")
        void leaveRehomesOwnerUserId() {
            memberMapper.update(null, Wrappers.<TenantMember>lambdaUpdate()
                    .eq(TenantMember::getTenantId, org.getId())
                    .eq(TenantMember::getUserId, carol.id())
                    .set(TenantMember::getRole, TenantMember.ROLE_OWNER));

            memberService.leave("acme", alice);

            // 留着离场的人当家，下一句就会被「该用户已经是组织拥有者」挡掉
            assertThat(tenantService.findBySlug("acme").getOwnerUserId()).isEqualTo(carol.id());
            memberService.transferOwner("acme", bob.id(), carol);
            assertThat(tenantService.findBySlug("acme").getOwnerUserId()).isEqualTo(bob.id());
        }
    }

    // ---------------------------------------------------------------- 入组申请

    @Nested
    @DisplayName("入组申请与审批（§3.3）")
    class JoinRequests {

        @Test
        @DisplayName("非成员可以申请加入公开组织：这条门故意不开成员校验")
        void outsiderCanApply() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));

            memberService.applyToJoin("acme", new JoinRequestMessage("想做文档"), dave);

            TenantJoinRequest row = requestOf(org, dave);
            assertThat(row.getStatus()).isEqualTo(TenantJoinRequest.STATUS_PENDING);
            assertThat(memberService.listJoinRequests("acme", alice)).hasSize(1);
        }

        @Test
        @DisplayName("重复申请被拒；被拒后可以再申请，且复用同一行")
        void reapplyReusesRow() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
            memberService.applyToJoin("acme", new JoinRequestMessage("第一次"), dave);
            assertBizError(ErrorCode.JOIN_REQUEST_PENDING,
                    () -> memberService.applyToJoin("acme", new JoinRequestMessage("重复"), dave));

            memberService.reviewJoinRequest("acme", requestOf(org, dave).getId(), false, alice);
            memberService.applyToJoin("acme", new JoinRequestMessage("再试一次"), dave);

            assertThat(joinRequestMapper.selectList(Wrappers.<TenantJoinRequest>lambdaQuery()
                    .eq(TenantJoinRequest::getTenantId, org.getId())
                    .eq(TenantJoinRequest::getUserId, dave.id())))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.getStatus()).isEqualTo(TenantJoinRequest.STATUS_PENDING);
                        assertThat(row.getMessage()).isEqualTo("再试一次");
                        assertThat(row.getReviewerUserId()).isNull();
                    });
        }

        @Test
        @DisplayName("批准即插入 MEMBER 成员行")
        void approveAddsMember() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
            memberService.applyToJoin("acme", null, dave);

            memberService.reviewJoinRequest("acme", requestOf(org, dave).getId(), true, carol);

            assertThat(role(dave)).isEqualTo(TenantMember.ROLE_MEMBER);
            assertThat(accessService.canInTenant(org, dave, KbAction.KB_CREATE)).isTrue();
        }

        @Test
        @DisplayName("普通成员看不到审批列表：403，不是 404")
        void reviewRequiresAdmin() {
            assertBizError(ErrorCode.FORBIDDEN, () -> memberService.listJoinRequests("acme", bob));
        }

        @Test
        @DisplayName("invite_only 组织不开放申请；已停用的组织连存在都不确认")
        void closedOrgRejectsApplications() {
            tenantService.update("acme", new UpdateOrgRequest(null, null, null, Tenant.JOIN_INVITE_ONLY, null), alice);
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
            assertBizError(ErrorCode.FORBIDDEN, () -> memberService.applyToJoin("acme", null, dave));

            tenantService.update("acme", new UpdateOrgRequest(null, null, null, Tenant.JOIN_REQUEST, false), alice);
            assertBizError(ErrorCode.NOT_FOUND, () -> memberService.applyToJoin("acme", null, dave));

            tenantService.update("acme", new UpdateOrgRequest(null, null, null, null, true), alice);
            tenantService.update(Wrappers.<Tenant>lambdaUpdate().eq(Tenant::getId, org.getId())
                    .set(Tenant::getStatus, Tenant.STATUS_DISABLED));
            assertBizError(ErrorCode.NOT_FOUND, () -> memberService.applyToJoin("acme", null, dave));
        }

        @Test
        @DisplayName("直接添加成员会顺手结案那条挂着的申请")
        void addAutoReviewsPendingRequest() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
            memberService.applyToJoin("acme", null, dave);

            memberService.add("acme", new AddMemberRequest("dave", null), alice);

            assertThat(requestOf(org, dave).getStatus()).isEqualTo(TenantJoinRequest.STATUS_APPROVED);
        }

        @Test
        @DisplayName("跨组织的申请 id 与「不存在」不可区分")
        void foreignRequestIdLooksMissing() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
            memberService.applyToJoin("acme", null, dave);
            Tenant other = createOrg("other", bob);

            assertBizError(ErrorCode.NOT_FOUND,
                    () -> memberService.reviewJoinRequest(other.getSlug(), requestOf(org, dave).getId(), true, bob));
        }

        @Test
        @DisplayName("批准一个已禁用的申请人：与「直接添加」同一条口径拒绝，不发一份进不去的成员身份")
        void approveDisabledApplicantRejected() {
            User daveRow = saveUser("dave", "戴夫", User.ROLE_USER);
            LoginUser dave = as(daveRow);
            memberService.applyToJoin("acme", null, dave);
            userService.update(Wrappers.<User>lambdaUpdate().eq(User::getId, daveRow.getId())
                    .set(User::getStatus, User.STATUS_DISABLED));

            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> memberService.reviewJoinRequest("acme", requestOf(org, dave).getId(), true, carol));

            assertThat(role(dave)).isNull();
        }
    }

    // ---------------------------------------------------------------- 建库落点与切换器

    @Nested
    @DisplayName("建库落点与组织切换器（§3.4 / §4.2）")
    class OrgContext {

        @Test
        @DisplayName("不指定组织：落到自己的个人组织，v1 语义不变")
        void blankSlugLandsInPersonalOrg() {
            Tenant personal = tenantService.ensurePersonalTenant(alice.id());

            Long kbId = kbService.create(null, createKb("我的库", null, null, null, List.of(), null),
                    alice).id();

            KnowledgeBase kb = kb(kbId);
            assertThat(kb.getTenantId()).isEqualTo(personal.getId());
            assertThat(kb.getStorageKey()).isEqualTo("o" + personal.getId() + "/" + kb.getSlug());
        }

        @Test
        @DisplayName("缺省可见性从 private 变成 org：个人组织只有一名成员，等同私有")
        void defaultVisibilityIsOrg() {
            Long kbId = kbService.create(null, createKb("默认档", null, null, null, List.of(), null),
                    alice).id();

            assertThat(kb(kbId).getMaintainScope()).isEqualTo(KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(kb(kbId).getVisibility()).isEqualTo(KnowledgeBase.VISIBILITY_ORG);
        }

        @Test
        @DisplayName("指定组织：库属于组织，创建者是成员而不是主人")
        void createIntoTeamOrg() {
            Long kbId = kbService.create("acme", createKb("团队库", null, null, null, List.of(), null),
                    bob).id();

            KnowledgeBase kb = kb(kbId);
            assertThat(kb.getTenantId()).isEqualTo(org.getId());
            assertThat(kb.getOwnerId()).isEqualTo(bob.id());
            // 建在团队组织里的 org 库，同组织的普通成员卡萝读得到
            assertThat(accessService.canRead(kb, carol)).isTrue();
            // 读到了不等于写得动：默认档位 owner_only 下，写仍只属于创建者
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
            memberService.add("acme", new AddMemberRequest("dave", null), alice);
            assertThat(accessService.can(kb, dave, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("组织外的人不能往别人组织里建库：404，不确认组织存在")
        void outsiderCannotCreateIntoOrg() {
            LoginUser dave = as(saveUser("dave", "戴夫", User.ROLE_USER));

            assertBizError(ErrorCode.NOT_FOUND, () -> kbService.create(
                    "acme", createKb("混进来的", null, null, null, List.of(), null), dave));
        }

        @Test
        @DisplayName("停用组织冻结建库：成员身份在停用的组织里一律失效")
        void disabledOrgBlocksCreate() {
            tenantService.update(Wrappers.<Tenant>lambdaUpdate().eq(Tenant::getId, org.getId())
                    .set(Tenant::getStatus, Tenant.STATUS_DISABLED));

            assertBizError(ErrorCode.NOT_FOUND, () -> kbService.create(
                    "acme", createKb("建不了", null, null, null, List.of(), null), bob));
        }

        @Test
        @DisplayName("切换器：个人组织置顶，团队组织按 slug 字典序")
        void switcherPinsPersonalFirst() {
            createOrg("zeta", alice);
            createOrg("beta", alice);
            Tenant personal = tenantService.ensurePersonalTenant(alice.id());

            assertThat(tenantService.myTenants(alice))
                    .extracting(TenantBrief::slug)
                    .containsExactly(personal.getSlug(), "acme", "beta", "zeta");
        }

        @Test
        @DisplayName("切换器：停用的组织不再是入口")
        void switcherHidesDisabledOrg() {
            tenantService.update(Wrappers.<Tenant>lambdaUpdate().eq(Tenant::getId, org.getId())
                    .set(Tenant::getStatus, Tenant.STATUS_DISABLED));

            assertThat(tenantService.myTenants(alice)).isEmpty();
        }

        @Test
        @DisplayName("/api/me：上次所在组织只在仍是我的组织时才回传")
        void mePayloadFiltersStaleLastTenant() {
            UserContext.set(alice);
            try {
                settingsService.mergeSettings(alice.id(), Map.of("lastTenantSlug", "acme"));
                assertThat(meApi().lastTenantSlug()).isEqualTo("acme");

                memberMapper.delete(Wrappers.<TenantMember>lambdaQuery()
                        .eq(TenantMember::getTenantId, org.getId())
                        .eq(TenantMember::getUserId, alice.id()));
                assertThat(meApi().lastTenantSlug()).isNull();
            } finally {
                UserContext.clear();
            }
        }

        private MeVO meApi() {
            return meController.me().data();
        }

        @Test
        @DisplayName("切换器条目带动作集：三档各拿各的，前端不必自己推断档位含义")
        void switcherCarriesTenantPermissions() {
            assertThat(tenantService.myTenants(alice).stream()
                    .filter(brief -> "acme".equals(brief.slug())).findFirst().orElseThrow().myPermissions())
                    .contains(KbAction.TENANT_VIEW.name(), KbAction.KB_CREATE.name(),
                            KbAction.TENANT_MEMBER_MANAGE.name(), KbAction.TENANT_RENAME.name(),
                            KbAction.TENANT_DELETE.name(), KbAction.TENANT_TRANSFER.name());
            assertThat(tenantService.myTenants(bob).stream()
                    .filter(brief -> "acme".equals(brief.slug())).findFirst().orElseThrow().myPermissions())
                    .containsExactlyInAnyOrder(KbAction.TENANT_VIEW.name(), KbAction.KB_CREATE.name());
            assertThat(tenantService.myTenants(carol).stream()
                    .filter(brief -> "acme".equals(brief.slug())).findFirst().orElseThrow().myPermissions())
                    .contains(KbAction.AUDIT_READ.name(), KbAction.TENANT_MEMBER_MANAGE.name())
                    .doesNotContain(KbAction.TENANT_RENAME.name(), KbAction.TENANT_TRANSFER.name());
        }

        @Test
        @DisplayName("组织详情的动作集与 canInTenant 同一份口径：按钮与接口不会各说各话")
        void orgDetailPermissionsMatchRulings() {
            OrgVO detail = tenantService.detail("acme", carol);

            assertThat(detail.myPermissions()).contains(
                    KbAction.TENANT_MEMBER_MANAGE.name(), KbAction.AUDIT_READ.name());
            assertThat(detail.myPermissions()).doesNotContain(KbAction.TENANT_RENAME.name());
            assertThat(accessService.canInTenant(org, carol, KbAction.TENANT_MEMBER_MANAGE)).isTrue();
            assertThat(accessService.canInTenant(org, carol, KbAction.TENANT_RENAME)).isFalse();
        }
    }

    @Nested
    @DisplayName("列表与创建的权限标记（§4.2）")
    class ListPermissions {

        @Test
        @DisplayName("普通成员在列表里只拿到读轴，不因为看得到就拿到写轴")
        void memberRowIsReadOnly() {
            // alice 建的 org 库，bob 只是普通成员：读得到，档位 owner_only 写不了
            newKb(org, alice, KnowledgeBase.MAINTAIN_OWNER_ONLY);

            assertThat(rows(org, bob))
                    .singleElement()
                    .extracting(KbVO::myPermissions)
                    .asList()
                    .containsExactly(KbAction.KB_VIEW.name(), KbAction.DOC_READ.name());
        }

        @Test
        @DisplayName("名单 EDITOR 在列表里就带上写轴，治理轴仍然没有")
        void editorRowGetsWriteAxis() {
            Long kbId = newKb(org, alice, KnowledgeBase.MAINTAIN_MEMBERS);
            rosterService.grant(kbId, new RosterGrantRequest(bob.id(), KbMember.ROLE_EDITOR), alice);

            assertThat(rows(org, bob))
                    .singleElement()
                    .extracting(KbVO::myPermissions)
                    .asList()
                    .contains(KbAction.DOC_WRITE.name(), KbAction.SHARE_CREATE.name())
                    .doesNotContain(KbAction.KB_DELETE.name(), KbAction.KB_MEMBER_MANAGE.name());
        }

        @Test
        @DisplayName("刚建好的库不能回一个空权限数组（曾经的行为：只在详情里算）")
        void createResponseCarriesPermissions() {
            KbVO created = kbService.create("acme", createKb("标记", null, null, null, null, null), alice);

            assertThat(created.myPermissions())
                    .contains(KbAction.KB_DELETE.name(), KbAction.DOC_WRITE.name(), KbAction.KB_VIEW.name());
        }

        @Test
        @DisplayName("建库时一次给定写轴，省略即 owner_only")
        void createCarriesMaintainScope() {
            assertThat(kbService.create("acme",
                    createKb("名单库", null, null, KnowledgeBase.MAINTAIN_MEMBERS, null, null), alice)
                    .maintainScope()).isEqualTo(KnowledgeBase.MAINTAIN_MEMBERS);

            assertThat(kbService.create("acme",
                    createKb("默认档", null, null, null, null, null), alice)
                    .maintainScope()).isEqualTo(KnowledgeBase.MAINTAIN_OWNER_ONLY);
        }

        @Test
        @DisplayName("绕过 @Valid 直接调服务也要拦住非法档位，不能落成「谁都能改」")
        void createRejectsUnknownMaintainScope() {
            assertBizError(ErrorCode.PARAM_INVALID, () -> kbService.create("acme",
                    createKb("错档", null, null, "everyone", null, null), alice));
        }

        @Test
        @DisplayName("外组织的人看 public 行：读轴照给，写与治理一律没有")
        void outsiderRowIsReadOnlyToo() {
            Tenant beta = createOrg("beta-org", bob);
            // 与 acme 那行同 slug：若列表按 slug 而非 kb_id 取事实，两行会互相串味
            kbWithSlug(org, "handbook", alice);
            kbWithSlug(beta, "handbook", bob);

            assertThat(rows(beta, alice))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.tenantSlug()).isEqualTo("beta-org");
                        assertThat(row.myPermissions())
                                .containsExactly(KbAction.KB_VIEW.name(), KbAction.DOC_READ.name());
                    });
            // 同一个人看自己那侧的同名行，治理轴在
            assertThat(rows(org, alice))
                    .singleElement()
                    .extracting(KbVO::myPermissions)
                    .asList()
                    .contains(KbAction.KB_DELETE.name());
        }

        private List<KbVO> rows(Tenant tenant, LoginUser viewer) {
            return kbService.page(tenant.getId(), viewer, null, null, null, null, null, 1, 20).list();
        }

        private void kbWithSlug(Tenant tenant, String slug, LoginUser owner) {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setTenantId(tenant.getId());
            kb.setOwnerId(owner.id());
            kb.setName(slug);
            kb.setSlug(slug);
            kb.setStorageKey(StorageKey.of(tenant.getId(), slug));
            kb.setVisibility(KnowledgeBase.VISIBILITY_PUBLIC);
            kb.setMaintainScope(KnowledgeBase.MAINTAIN_OWNER_ONLY);
            kb.setDocCount(0);
            kbService.save(kb);
        }
    }

    @Nested
    @DisplayName("库维护名单（§2.3 / §9）")
    class Roster {

        @Test
        @DisplayName("EDITOR 授权即生效，撤销即失效")
        void grantAndRevoke() {
            Long kbId = newKb(org, alice, KnowledgeBase.MAINTAIN_MEMBERS);

            rosterService.grant(kbId, new RosterGrantRequest(bob.id(), KbMember.ROLE_EDITOR), alice);
            assertThat(accessService.can(kb(kbId), bob, KbAction.DOC_WRITE)).isTrue();
            assertThat(accessService.can(kb(kbId), bob, KbAction.KB_DELETE)).isFalse();

            rosterService.grant(kbId, new RosterGrantRequest(bob.id(), null), alice);
            assertThat(accessService.can(kb(kbId), bob, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("VIEWER 只给读，不给写")
        void viewerReadsOnly() {
            // private + members：bob 的读权限只能来自名单那一行，否则这条断言是空的
            Long kbId = newKb(org, alice, KnowledgeBase.MAINTAIN_MEMBERS, KnowledgeBase.VISIBILITY_PRIVATE);
            rosterService.grant(kbId, new RosterGrantRequest(bob.id(), KbMember.ROLE_VIEWER), alice);

            assertThat(accessService.canRead(kb(kbId), bob)).isTrue();
            assertThat(accessService.can(kb(kbId), bob, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("授权给组织外的人：报错，而不是保存成功却什么也没发生")
        void grantOutsideOrgRejected() {
            LoginUser outsider = as(saveUser("outsider", "路人", User.ROLE_USER));
            Long kbId = newKb(org, alice);

            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> rosterService.grant(kbId, new RosterGrantRequest(outsider.id(), KbMember.ROLE_EDITOR), alice));
        }

        @Test
        @DisplayName("名单归创建者管：EDITOR 不能继续往下授权")
        void editorCannotManageRoster() {
            Long kbId = newKb(org, alice);
            rosterService.grant(kbId, new RosterGrantRequest(bob.id(), KbMember.ROLE_EDITOR), alice);

            assertBizError(ErrorCode.FORBIDDEN,
                    () -> rosterService.grant(kbId, new RosterGrantRequest(carol.id(), KbMember.ROLE_EDITOR), bob));
        }

        @Test
        @DisplayName("改维护档位要 KB_MEMBER_MANAGE；档位为 members 时名单生效")
        void maintainScopeSwitch() {
            Long kbId = newKb(org, alice);
            rosterService.grant(kbId, new RosterGrantRequest(bob.id(), KbMember.ROLE_EDITOR), alice);

            String scope = rosterService.updateMaintainScope(kbId, KnowledgeBase.MAINTAIN_MEMBERS, alice);

            assertThat(scope).isEqualTo(KnowledgeBase.MAINTAIN_MEMBERS);
            assertThat(kb(kbId).getMaintainScope()).isEqualTo(KnowledgeBase.MAINTAIN_MEMBERS);
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> rosterService.updateMaintainScope(kbId, KnowledgeBase.MAINTAIN_ORG_ALL, bob));
        }

        @Test
        @DisplayName("档位取值非法一律拒绝，不落到 fail-closed 的默认档")
        void unknownScopeRejected() {
            Long kbId = newKb(org, alice);

            assertBizError(ErrorCode.PARAM_INVALID, () -> rosterService.updateMaintainScope(kbId, "EVERYONE", alice));
        }
    }

    @Nested
    @DisplayName("slug 定位只在组织内（§4.2 / §7.1.3）")
    class SlugRouting {

        @Test
        @DisplayName("同名 slug 落在两个组织：各自解析到自己那个，互不串门")
        void sameSlugResolvesPerOrg() {
            Tenant other = createOrg("beta", alice);

            Long inAcme = kbWithSlug(org, "handbook", KnowledgeBase.VISIBILITY_PUBLIC);
            Long inBeta = kbWithSlug(other, "handbook", KnowledgeBase.VISIBILITY_PUBLIC);

            assertThat(kbService.requireBySlug(org.getId(), "handbook").getId()).isEqualTo(inAcme);
            assertThat(kbService.requireBySlug(other.getId(), "handbook").getId()).isEqualTo(inBeta);
        }

        @Test
        @DisplayName("slug 在别的组织里有、在本组织里没有：404，不确认它存在")
        void missingSlugInThisOrgIsNotFound() {
            Tenant other = createOrg("beta", alice);
            kbWithSlug(other, "handbook", KnowledgeBase.VISIBILITY_PUBLIC);

            assertBizError(ErrorCode.NOT_FOUND, () -> kbService.requireBySlug(org.getId(), "handbook"));
        }

        @Test
        @DisplayName("组织上下文缺失时绝不退回全局 slug 查找")
        void nullTenantNeverFallsBackToGlobalLookup() {
            kbWithSlug(org, "handbook", KnowledgeBase.VISIBILITY_PUBLIC);
            // 全局 findBySlug 仍然能找到它 —— 说明退回旧口径确实会出错，而不是恰好通过
            assertThat(kbService.findBySlug("handbook")).isNotNull();

            assertBizError(ErrorCode.NOT_FOUND, () -> kbService.requireBySlug(null, "handbook"));
        }

        @Test
        @DisplayName("老链接消歧：命中多个组织，每条带自己的 tenantSlug")
        void visibleBySlugListsEveryReadableMatch() {
            Tenant other = createOrg("beta", alice);
            kbWithSlug(org, "handbook", KnowledgeBase.VISIBILITY_PUBLIC);
            kbWithSlug(other, "handbook", KnowledgeBase.VISIBILITY_PRIVATE);

            assertThat(kbService.visibleBySlug("handbook", alice))
                    .extracting(KbVO::tenantSlug, KbVO::visibility)
                    .containsExactlyInAnyOrder(
                            tuple("acme", KnowledgeBase.VISIBILITY_PUBLIC),
                            tuple("beta", KnowledgeBase.VISIBILITY_PRIVATE));
        }

        @Test
        @DisplayName("老链接消歧对游客只吐出 public：不因消歧泄露私库存在性")
        void visibleBySlugHidesPrivateFromGuest() {
            Tenant other = createOrg("beta", alice);
            kbWithSlug(org, "handbook", KnowledgeBase.VISIBILITY_PUBLIC);
            kbWithSlug(other, "handbook", KnowledgeBase.VISIBILITY_PRIVATE);

            assertThat(kbService.visibleBySlug("handbook", null))
                    .extracting(KbVO::tenantSlug)
                    .containsExactly("acme");
        }

        private Long kbWithSlug(Tenant tenant, String slug, String visibility) {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setTenantId(tenant.getId());
            kb.setOwnerId(alice.id());
            kb.setName(slug);
            kb.setSlug(slug);
            kb.setStorageKey(StorageKey.of(tenant.getId(), slug));
            kb.setVisibility(visibility);
            kb.setMaintainScope(KnowledgeBase.MAINTAIN_OWNER_ONLY);
            kb.setDocCount(0);
            kbService.save(kb);
            return kb.getId();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Tenant createOrg(String slug, LoginUser owner) {
        tenantService.createTeam(new CreateOrgRequest(slug, slug, null, Tenant.JOIN_REQUEST, true), owner);
        return tenantService.findBySlug(slug);
    }

    private void grant(Tenant tenant, LoginUser user, String role) {
        TenantMember row = new TenantMember();
        row.setTenantId(tenant.getId());
        row.setUserId(user.id());
        row.setRole(role);
        row.setJoinedFrom("bootstrap");
        memberMapper.insert(row);
    }

    private String role(LoginUser user) {
        TenantMember row = memberMapper.selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, org.getId())
                .eq(TenantMember::getUserId, user.id())
                .last("LIMIT 1"));
        return row == null ? null : row.getRole();
    }

    private TenantJoinRequest requestOf(Tenant tenant, LoginUser user) {
        return joinRequestMapper.selectOne(Wrappers.<TenantJoinRequest>lambdaQuery()
                .eq(TenantJoinRequest::getTenantId, tenant.getId())
                .eq(TenantJoinRequest::getUserId, user.id())
                .last("LIMIT 1"));
    }

    private Long newKb(Tenant tenant, LoginUser creator) {
        return newKb(tenant, creator, KnowledgeBase.MAINTAIN_OWNER_ONLY);
    }

    private Long newKb(Tenant tenant, LoginUser creator, String maintainScope) {
        return newKb(tenant, creator, maintainScope, KnowledgeBase.VISIBILITY_ORG);
    }

    private Long newKb(Tenant tenant, LoginUser creator, String maintainScope, String visibility) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenant.getId());
        kb.setOwnerId(creator.id());
        kb.setName("库");
        kb.setSlug("kb-" + (kbService.count() + 1));
        kb.setStorageKey(StorageKey.of(tenant.getId(), kb.getSlug()));
        kb.setVisibility(visibility);
        kb.setMaintainScope(maintainScope);
        kb.setDocCount(0);
        kbService.save(kb);
        return kb.getId();
    }

    private KnowledgeBase kb(Long id) {
        return kbService.getById(id);
    }
}
