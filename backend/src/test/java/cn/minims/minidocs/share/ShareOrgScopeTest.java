package cn.minims.minidocs.share;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.share.dto.ShareDtos.CreateRequest;
import cn.minims.minidocs.share.dto.ShareDtos.ShareVO;
import cn.minims.minidocs.share.dto.ShareDtos.UpdateRequest;
import cn.minims.minidocs.share.service.ShareService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5c 分享的组织化：目标库改用 slug 定位，列表范围从「我创建的」变成「这个组织里我看得见的」。
 *
 * <p>{@code shares} 表本身不带 {@code tenant_id}（V3 契约），所以组织归属是顺着
 * 「分享 → 库 → 库.tenant_id」推出来的。本类锁的就是这条推导：同名 slug 各归各的库，
 * 跨组织的 token 与「不存在」同形。</p>
 *
 * <p>走服务层不起 HTTP：{@code /api/console/{org}/shares} 的成员门已由 {@code TenantInterceptorTest}
 * 锁住（{@code shares} 不在豁免名单里），这里要判的是过了门之后范围划得对不对。</p>
 */
@DisplayName("M5c 分享按组织隔离")
class ShareOrgScopeTest extends MiniDocsTestBase {

    private static final String BASE_URL = "https://example.test";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private ShareService shareService;
    @Autowired
    private KnowledgeBaseService kbService;
    @Autowired
    private VaultFileService vaultFileService;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    @Nested
    @DisplayName("建分享：slug 只在本组织内解析")
    class Create {

        @Test
        @DisplayName("按 slug 建：出参带 slug，链接是 token 拼出来的匿名入口")
        void createsBySlug() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team", TenantMember.ROLE_OWNER);
            String slug = newKb(team, owner);

            ShareVO vo = shareService.createOrUpdate(team.getId(), request(slug), owner, BASE_URL);

            assertThat(vo.kbSlug()).isEqualTo(slug);
            assertThat(vo.url()).isEqualTo(BASE_URL + "/share/" + vo.token());
            assertThat(shareService.findByToken(vo.token()).getKbId()).isEqualTo(kbIdOf(team, slug));
        }

        @Test
        @DisplayName("两个组织各有一个同名 slug：各自的请求解到各自的库")
        void sameSlugResolvesPerOrg() {
            LoginUser owner = alice();
            String slug = "handbook-" + SEQ.incrementAndGet();
            Tenant alpha = org(owner, "alpha", TenantMember.ROLE_OWNER);
            Tenant beta = org(owner, "beta", TenantMember.ROLE_OWNER);
            kb(alpha, owner, slug);
            kb(beta, owner, slug);

            assertThat(shareService.createOrUpdate(alpha.getId(), request(slug), owner, BASE_URL).kbId())
                    .isEqualTo(kbIdOf(alpha, slug));
            assertThat(shareService.createOrUpdate(beta.getId(), request(slug), owner, BASE_URL).kbId())
                    .isEqualTo(kbIdOf(beta, slug));
        }

        @Test
        @DisplayName("slug 只在别的组织里有：本组织视角 404，不确认它存在")
        void foreignSlugIsNotFoundHere() {
            LoginUser owner = alice();
            Tenant alpha = org(owner, "alpha", TenantMember.ROLE_OWNER);
            Tenant beta = org(owner, "beta", TenantMember.ROLE_OWNER);
            String slug = newKb(alpha, owner);

            assertBizError(ErrorCode.NOT_FOUND,
                    () -> shareService.createOrUpdate(beta.getId(), request(slug), owner, BASE_URL));
        }
    }

    @Nested
    @DisplayName("列表：范围是组织内可见的库，不再按创建者")
    class ListScope {

        @Test
        @DisplayName("只看本组织的分享：另一个组织的同名 slug 库不在内")
        void filteredByOrg() {
            LoginUser owner = alice();
            String slug = "handbook-" + SEQ.incrementAndGet();
            Tenant alpha = org(owner, "alpha", TenantMember.ROLE_OWNER);
            Tenant beta = org(owner, "beta", TenantMember.ROLE_OWNER);
            kb(alpha, owner, slug);
            kb(beta, owner, slug);
            String inAlpha = shareService.createOrUpdate(alpha.getId(), request(slug), owner, BASE_URL).token();
            String inBeta = shareService.createOrUpdate(beta.getId(), request(slug), owner, BASE_URL).token();

            assertThat(shareService.page(alpha.getId(), owner, null, null, 1, 10, BASE_URL).list())
                    .extracting(ShareVO::token).containsExactly(inAlpha);
            assertThat(shareService.page(beta.getId(), owner, null, null, 1, 10, BASE_URL).list())
                    .extracting(ShareVO::token).containsExactly(inBeta);
        }

        @Test
        @DisplayName("别人在同一组织建的分享也在列表里：v1 的 owner_id 过滤会把第二个人屏蔽掉")
        void showsOtherPeoplesSharesInSameOrg() {
            LoginUser owner = alice();
            LoginUser admin = bob();
            Tenant team = org(owner, "team", TenantMember.ROLE_OWNER);
            member(team.getId(), admin, TenantMember.ROLE_ADMIN);
            String mine = shareService.createOrUpdate(team.getId(), request(newKb(team, owner)),
                    owner, BASE_URL).token();
            String theirs = shareService.createOrUpdate(team.getId(), request(newKb(team, admin)),
                    admin, BASE_URL).token();

            assertThat(shareService.page(team.getId(), owner, null, null, 1, 10, BASE_URL).list())
                    .extracting(ShareVO::token).containsExactlyInAnyOrder(mine, theirs);
        }

        @Test
        @DisplayName("不是本组织成员，也看不到本组织的 org 库：列表为空")
        void nonMemberSeesNothing() {
            LoginUser owner = alice();
            LoginUser stranger = bob();
            Tenant team = org(owner, "team", TenantMember.ROLE_OWNER);
            String slug = newKb(team, owner);
            shareService.createOrUpdate(team.getId(), request(slug), owner, BASE_URL);

            assertThat(shareService.page(team.getId(), stranger, null, null, 1, 10, BASE_URL).list()).isEmpty();
        }
    }

    @Nested
    @DisplayName("改与撤销：token 换个组织来用即失效")
    class Mutate {

        @Test
        @DisplayName("拿别的组织的 token 来撤销：404，那条分享没被动过")
        void foreignTokenCannotBeRevoked() {
            LoginUser owner = alice();
            Tenant alpha = org(owner, "alpha", TenantMember.ROLE_OWNER);
            Tenant beta = org(owner, "beta", TenantMember.ROLE_OWNER);
            String token = shareService.createOrUpdate(beta.getId(), request(newKb(beta, owner)),
                    owner, BASE_URL).token();

            assertBizError(ErrorCode.NOT_FOUND, () -> shareService.revoke(alpha.getId(), token, owner));
            assertThat(shareService.findByToken(token).revoked()).isFalse();

            shareService.revoke(beta.getId(), token, owner);
            assertThat(shareService.findByToken(token).revoked()).isTrue();
        }

        @Test
        @DisplayName("跨组织 token 与根本不存在的 token 同一个 404：不给「它存在但在别处」的信号")
        void foreignAndUnknownTokensLookAlike() {
            LoginUser owner = alice();
            Tenant alpha = org(owner, "alpha", TenantMember.ROLE_OWNER);
            Tenant beta = org(owner, "beta", TenantMember.ROLE_OWNER);
            String token = shareService.createOrUpdate(alpha.getId(), request(newKb(alpha, owner)),
                    owner, BASE_URL).token();

            assertBizError(ErrorCode.NOT_FOUND, () -> shareService.update(beta.getId(), token,
                    new UpdateRequest(null, false, "1d"), owner, BASE_URL));
            assertBizError(ErrorCode.NOT_FOUND, () -> shareService.update(beta.getId(), "no-such-token",
                    new UpdateRequest(null, false, "1d"), owner, BASE_URL));
        }
    }

    @Nested
    @DisplayName("可空列回写：取消口令 / 改成永久必须真的落到库里")
    class NullWriteback {

        @Test
        @DisplayName("再次提交时把 encrypted 关掉：库里不留旧哈希，链接不再要旧密码")
        void upsertClearsPassword() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team", TenantMember.ROLE_OWNER);
            String slug = newKb(team, owner);
            String token = shareService.createOrUpdate(team.getId(),
                    guarded(slug, "abcd1234"), owner, BASE_URL).token();
            assertThat(shareService.findByToken(token).encrypted()).isTrue();

            shareService.createOrUpdate(team.getId(),
                    new CreateRequest(slug, "kb", null, null, false, "forever"), owner, BASE_URL);

            assertThat(shareService.findByToken(token).encrypted()).isFalse();
            assertThat(shareService.findByToken(token).getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("编辑框里改口令与有效期：走 PUT 也是同一份回写")
        void updateClearsPassword() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team", TenantMember.ROLE_OWNER);
            String slug = newKb(team, owner);
            String token = shareService.createOrUpdate(team.getId(),
                    guarded(slug, "abcd1234"), owner, BASE_URL).token();

            shareService.update(team.getId(), token, new UpdateRequest(null, false, "forever"),
                    owner, BASE_URL);

            assertThat(shareService.findByToken(token).encrypted()).isFalse();
            assertThat(shareService.findByToken(token).getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("口令开着但没给新密码：保留原哈希，不能被当成关闭")
        void keepingPasswordWhenNotProvided() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team", TenantMember.ROLE_OWNER);
            String slug = newKb(team, owner);
            String token = shareService.createOrUpdate(team.getId(),
                    guarded(slug, "abcd1234"), owner, BASE_URL).token();

            shareService.update(team.getId(), token, new UpdateRequest(null, true, "1d"), owner, BASE_URL);

            assertThat(shareService.findByToken(token).encrypted()).isTrue();
            assertThat(shareService.findByToken(token).getExpiresAt()).isNotNull();
        }
    }

    // ------------------------------------------------------------------ helpers

    private CreateRequest request(String slug) {
        return new CreateRequest(slug, "kb", null, null, false, null);
    }

    private CreateRequest guarded(String slug, String password) {
        return new CreateRequest(slug, "kb", null, password, true, "7d");
    }

    private LoginUser alice() {
        return as(saveUser("share-alice-" + SEQ.incrementAndGet(), "爱丽丝", User.ROLE_USER));
    }

    private LoginUser bob() {
        return as(saveUser("share-bob-" + SEQ.incrementAndGet(), "鲍勃", User.ROLE_USER));
    }

    private Tenant org(LoginUser owner, String prefix, String role) {
        Tenant tenant = new Tenant();
        tenant.setSlug(prefix + "-" + SEQ.incrementAndGet());
        tenant.setName(tenant.getSlug());
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setOwnerUserId(owner.id());
        tenant.setJoinPolicy(Tenant.JOIN_REQUEST);
        tenant.setDiscoverable(true);
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenantMapper.insert(tenant);
        member(tenant.getId(), owner, role);
        return tenant;
    }

    private void member(Long tenantId, LoginUser user, String role) {
        TenantMember row = new TenantMember();
        row.setTenantId(tenantId);
        row.setUserId(user.id());
        row.setRole(role);
        row.setJoinedFrom("test");
        tenantMemberMapper.insert(row);
    }

    /** org 档可见性：写轴用 owner_only，用例里的操作者全是 OWNER/ADMIN，判定不掺进档位差异。 */
    private String newKb(Tenant tenant, LoginUser owner) {
        return kb(tenant, owner, "kb-" + SEQ.incrementAndGet());
    }

    private String kb(Tenant tenant, LoginUser owner, String slug) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenant.getId());
        kb.setOwnerId(owner.id());
        kb.setName(slug);
        kb.setSlug(slug);
        kb.setStorageKey(StorageKey.of(tenant.getId(), slug));
        kb.setVisibility(KnowledgeBase.VISIBILITY_ORG);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_OWNER_ONLY);
        kb.setDocCount(0);
        kbService.save(kb);
        vaultFileService.ensureRoot(kb.getStorageKey());
        return slug;
    }

    private Long kbIdOf(Tenant tenant, String slug) {
        return kbService.requireBySlug(tenant.getId(), slug).getId();
    }
}
