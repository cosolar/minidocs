package cn.minims.minidocs.tenant;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.dto.TenantDtos.AddMemberRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.AdminOrgVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.CreateOrgRequest;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.tenant.service.OrgAdminService;
import cn.minims.minidocs.tenant.service.TenantMemberService;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserAdminService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台侧组织治理与删号连带：规范 §4.4（停用 / 恢复）与 I6（删号前必须先转让 OWNER）。
 *
 * <p>这里的人一律是普通用户而不是平台管理员：超管在 {@code AccessService} 里是绕过判定的，
 * 用他来做「停用后读不到」这类断言会静默地测不到东西。</p>
 *
 * <p>删号路径不断言「磁盘目录消失」——基座每个用例回滚，而删目录排在提交之后；
 * 那条契约由 {@code PermissionBaselineTest} 直接断言。</p>
 */
@DisplayName("M3b 平台治理：组织停用与删号连带")
class PlatformAdminTest extends MiniDocsTestBase {

    @Autowired
    private UserAdminService userAdminService;
    @Autowired
    private OrgAdminService orgAdminService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService memberService;
    @Autowired
    private TenantMemberMapper memberMapper;
    @Autowired
    private KnowledgeBaseService kbService;
    @Autowired
    private VaultFileService vaultFileService;
    @Autowired
    private AccessService accessService;

    private LoginUser alice;
    private LoginUser dave;
    private Tenant org;

    @BeforeEach
    void seed() {
        alice = as(saveUser("alice", "爱丽丝", User.ROLE_USER));
        dave = as(saveUser("dave", "戴夫", User.ROLE_USER));
        tenantService.createTeam(new CreateOrgRequest("团队", "team", null, Tenant.JOIN_REQUEST, true), alice);
        org = tenantService.findBySlug("team");
        memberService.add("team", new AddMemberRequest("dave", null), alice);
    }

    private Long teamKb(String name, String maintainScope, String visibility) {
        return kbService.create("team", createKb(name, null, visibility, maintainScope, null, null), alice)
                .id();
    }

    @Nested
    @DisplayName("删号连带（I6）")
    class PurgeUser {

        @Test
        @DisplayName("仍是团队组织的 OWNER 时不许删号：组织不能没人当家")
        void teamOwnerCannotBeDeleted() {
            memberService.transferOwner("team", dave.id(), alice);

            assertBizError(ErrorCode.PARAM_INVALID, () -> userAdminService.delete(dave.id()));

            assertThat(userService.getById(dave.id())).isNotNull();
            assertThat(tenantService.findBySlug("team")).isNotNull();
        }

        @Test
        @DisplayName("转让之后可以删：成员行、维护名单、收藏与个人组织一并清掉，团队的库留着")
        void deletePurgesEverything() {
            Long personalKb = kbService.create(null, createKb("戴夫的库", null, null, null, null, null), dave)
                    .id();
            Long kbId = teamKb("团队库", KnowledgeBase.MAINTAIN_MEMBERS, null);
            kbService.switchFavorite(kbId, dave, true);
            memberService.transferOwner("team", dave.id(), alice);
            memberService.transferOwner("team", alice.id(), dave);
            Tenant personal = tenantService.ensurePersonalTenant(dave.id());

            userAdminService.delete(dave.id());

            assertThat(userService.getById(dave.id())).isNull();
            assertThat(kbService.getById(personalKb)).isNull();
            assertThat(memberMapper.selectCount(Wrappers.<TenantMember>lambdaQuery()
                    .eq(TenantMember::getUserId, dave.id()))).isZero();
            assertThat(tenantService.getById(personal.getId())).isNull();
            assertThat(kbService.getById(kbId)).isNotNull();
            assertThat(accessService.can(kbService.getById(kbId), dave, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("用户名释放后可重用：新账号不继承前一个账号的组织与收藏")
        void usernameIsReusableCleanly() {
            Long kbId = teamKb("团队库", KnowledgeBase.MAINTAIN_MEMBERS, null);
            kbService.switchFavorite(kbId, dave, true);
            Tenant personal = tenantService.ensurePersonalTenant(dave.id());

            userAdminService.delete(dave.id());
            User reborn = saveUser("dave", "新戴夫", User.ROLE_USER);

            assertThat(reborn.getId()).isNotEqualTo(dave.id());
            assertThat(tenantService.myTenants(as(reborn))).isEmpty();
            assertThat(tenantService.getById(personal.getId())).isNull();
            assertThat(memberMapper.selectCount(Wrappers.<TenantMember>lambdaQuery()
                    .eq(TenantMember::getTenantId, org.getId())
                    .eq(TenantMember::getUserId, reborn.getId()))).isZero();
        }
    }

    @Nested
    @DisplayName("组织停用与恢复（§4.4）")
    class Suspend {

        @Test
        @DisplayName("停用即整库下线，public 也一并下线，但磁盘一个字节都不动")
        void disableFreezesContentNotDisk() {
            Long kbId = teamKb("团队库", null, KnowledgeBase.VISIBILITY_PUBLIC);
            KnowledgeBase kb = kbService.getById(kbId);

            AdminOrgVO disabled = orgAdminService.changeStatus("team", Tenant.STATUS_DISABLED, alice);

            assertThat(disabled.status()).isEqualTo(Tenant.STATUS_DISABLED);
            assertThat(accessService.canRead(kb, dave)).isFalse();
            assertThat(accessService.canInTenant(org, dave, KbAction.KB_CREATE)).isFalse();
            assertThat(tenantService.myTenants(alice)).extracting("slug").doesNotContain("team");
            assertThat(vaultFileService.rootExists(kb.getStorageKey())).isTrue();
            assertThat(orgAdminService.page(null, Tenant.TYPE_TEAM, Tenant.STATUS_DISABLED, 1, 20).list())
                    .extracting(AdminOrgVO::slug).contains("team");
        }

        @Test
        @DisplayName("恢复即原样回来：成员身份与库都还在")
        void enableBringsEverythingBack() {
            Long kbId = teamKb("团队库", null, null);
            orgAdminService.changeStatus("team", Tenant.STATUS_DISABLED, alice);

            orgAdminService.changeStatus("team", Tenant.STATUS_ACTIVE, alice);

            assertThat(accessService.can(kbService.getById(kbId), dave, KbAction.KB_VIEW)).isTrue();
            assertThat(tenantService.myTenants(alice)).extracting("slug").contains("team");
        }

        @Test
        @DisplayName("个人组织不许停用：账号可用性只在 users.status 表达一次")
        void personalOrgCannotBeDisabled() {
            Tenant personal = tenantService.ensurePersonalTenant(dave.id());

            assertBizError(ErrorCode.PARAM_INVALID,
                    () -> orgAdminService.changeStatus(personal.getSlug(), Tenant.STATUS_DISABLED, alice));
        }

        @Test
        @DisplayName("停不动内容也停不掉磁盘布局：storage_key 前后一致")
        void diskLayoutUntouched() {
            Long kbId = teamKb("内容库", null, null);
            String storageKey = kbService.getById(kbId).getStorageKey();

            orgAdminService.changeStatus("team", Tenant.STATUS_DISABLED, alice);
            orgAdminService.changeStatus("team", Tenant.STATUS_ACTIVE, alice);

            assertThat(kbService.getById(kbId).getStorageKey()).isEqualTo(storageKey);
            assertThat(vaultFileService.rootExists(storageKey)).isTrue();
        }
    }
}
