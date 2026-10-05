package cn.minims.minidocs.permission;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.dto.KbDtos.StatsVO;
import cn.minims.minidocs.kb.entity.KbMember;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbMemberMapper;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.user.entity.User;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1 权限基线在 v2 两轴模型下的落点。
 *
 * <p>本类逐条继承 M0 的基线断言（当时锁的是改造前<b>确凿如此</b>的 v1 行为），每条标注去向：</p>
 *
 * <ul>
 *   <li>{@code [保持]} —— 语义不变，替换调用点后仍然通过；</li>
 *   <li>{@code [变更]} —— v2 有意改掉的 v1 行为，断言已按新语义<b>改写</b>而非删除，
 *       注释里保留 v1 原断言，便于回归时看出这是决策而不是意外。</li>
 * </ul>
 *
 * <p>v1 的两个门 {@code requireOwned} / {@code requireReadable} 已删除，判定统一经
 * {@link AccessService}，见规范 §2.5 / §2.6。</p>
 *
 * @see <a href="file:../../../../../docs/多租户知识库权限与功能规范.md">多租户知识库权限与功能规范</a>
 */
@DisplayName("权限层：v1 基线 → v2 落点")
class PermissionBaselineTest extends MiniDocsTestBase {

    private static final String PUBLIC = KnowledgeBase.VISIBILITY_PUBLIC;
    private static final String PRIVATE = KnowledgeBase.VISIBILITY_PRIVATE;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private AccessService accessService;

    @Autowired
    private KbMemberMapper kbMemberMapper;

    private LoginUser alice;
    private LoginUser bob;
    private LoginUser root;
    private Long alicePublic;
    private Long alicePrivate;
    private Long bobPublic;
    private Long bobPrivate;

    @BeforeEach
    void seedFixtures() {
        alice = as(saveUser("alice", "爱丽丝", User.ROLE_USER));
        bob = as(saveUser("bob", "鲍勃", User.ROLE_USER));
        root = as(saveUser("root", "超级管理员", User.ROLE_ADMIN));
        alicePublic = createKb(alice, "Alice 公开库", PUBLIC);
        alicePrivate = createKb(alice, "Alice 私密库", PRIVATE);
        bobPublic = createKb(bob, "Bob 公开库", PUBLIC);
        bobPrivate = createKb(bob, "Bob 私密库", PRIVATE);
    }

    private Long createKb(LoginUser owner, String name, String visibility) {
        return kbService.create(null,
                createKb(name, "基线测试描述", visibility, null, List.of("基线"), null), owner).id();
    }

    private KnowledgeBase maintainScope(Long kbId, String scope) {
        KnowledgeBase kb = kbService.getById(kbId);
        kb.setMaintainScope(scope);
        kbService.updateById(kb);
        return kb;
    }

    /** 写维护名单：名单的管理界面在 M3 落地，本类只验证判定轴，故直接落表。 */
    private void grantKbMember(Long kbId, LoginUser grantee, String role) {
        kbMemberMapper.delete(Wrappers.<KbMember>lambdaQuery()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, grantee.id()));
        KbMember member = new KbMember();
        member.setKbId(kbId);
        member.setUserId(grantee.id());
        member.setRole(role);
        member.setGrantedBy(alice.id());
        kbMemberMapper.insert(member);
    }

    // ---------------------------------------------------------------- 读轴

    @Nested
    @DisplayName("读门：v1 requireReadable → requireKb(KB_VIEW)")
    class ReadGate {

        @Test
        @DisplayName("[保持] public 库对游客可读")
        void publicReadableByAnonymous() {
            assertThat(accessService.requireKb(alicePublic, KbAction.KB_VIEW, null).getId())
                    .isEqualTo(alicePublic);
        }

        @Test
        @DisplayName("[保持] public 库对非归属用户可读")
        void publicReadableByOtherUser() {
            assertThat(accessService.requireKb(alicePublic, KbAction.KB_VIEW, bob).getId())
                    .isEqualTo(alicePublic);
        }

        @Test
        @DisplayName("[保持] private 库对创建者可读")
        void privateReadableByOwner() {
            assertThat(accessService.requireKb(alicePrivate, KbAction.KB_VIEW, alice).getId())
                    .isEqualTo(alicePrivate);
        }

        @Test
        @DisplayName("[保持] private 库对他人 → 404 而非 403（不泄露存在性）")
        void privateHiddenFromOtherUser() {
            assertBizError(ErrorCode.NOT_FOUND, () -> accessService.requireKb(alicePrivate, KbAction.KB_VIEW, bob));
        }

        @Test
        @DisplayName("[保持] private 库对游客 → 404")
        void privateHiddenFromAnonymous() {
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireKb(alicePrivate, KbAction.KB_VIEW, null));
        }

        @Test
        @DisplayName("[保持] detail() 复用同一读门：他人 private 库不可见")
        void detailFollowsReadable() {
            assertThat(kbService.detail(alicePublic, bob).id()).isEqualTo(alicePublic);
            assertBizError(ErrorCode.NOT_FOUND, () -> kbService.detail(alicePrivate, bob));
        }

        @Test
        @DisplayName("[变更] detail() 不再强制登录：public 库游客可看详情")
        void detailOpenToAnonymous() {
            // v1 走 UserContext.requireUserId()，游客拿 401；v2 门户要求匿名可读公开库
            assertThat(kbService.detail(alicePublic, null).id()).isEqualTo(alicePublic);
        }
    }

    // ---------------------------------------------------------------- 写轴（owner_only）

    @Nested
    @DisplayName("写门：v1 requireOwned → requireKb(写动作)")
    class WriteGate {

        @Test
        @DisplayName("[保持] 创建者通过并返回实体")
        void creatorPasses() {
            assertThat(accessService.requireKb(alicePrivate, KbAction.DOC_WRITE, alice).getId())
                    .isEqualTo(alicePrivate);
        }

        @Test
        @DisplayName("[保持] public 库对他人 → 403：能读不能写")
        void publicReadableButNotWritable() {
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> accessService.requireKb(alicePublic, KbAction.DOC_WRITE, bob));
        }

        @Test
        @DisplayName("[变更] private 库对他人从 403 改为 404（§2.5 不泄露存在性）")
        void privateIsHiddenNotForbidden() {
            // v1：非归属一律 403，等于告诉探测者「这个 ID 确实存在」
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireKb(alicePrivate, KbAction.DOC_WRITE, bob));
        }

        @Test
        @DisplayName("[变更] 游客对 private 库从 403 改为 404")
        void anonymousOnPrivateIsNotFound() {
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireKb(alicePrivate, KbAction.DOC_WRITE, null));
        }

        @Test
        @DisplayName("[保持] 游客对 public 库仍是 403：读得到才谈得上写不了")
        void anonymousForbiddenOnPublic() {
            assertBizError(ErrorCode.FORBIDDEN,
                    () -> accessService.requireKb(alicePublic, KbAction.DOC_WRITE, null));
        }

        @Test
        @DisplayName("[保持] 记录不存在 → 404，且与无权同码")
        void missingIsNotFound() {
            assertBizError(ErrorCode.NOT_FOUND,
                    () -> accessService.requireKb(999_999L, KbAction.DOC_WRITE, alice));
        }

        @Test
        @DisplayName("[保持] 写轴先看读轴：private + org_all 不自相矛盾地放行")
        void writeRequiresReadFirst() {
            KnowledgeBase kb = maintainScope(alicePrivate, KnowledgeBase.MAINTAIN_ORG_ALL);
            // bob 不在 alice 的个人组织里，org_all 对他不产生任何权利
            assertBizError(ErrorCode.NOT_FOUND, () -> accessService.requireKb(kb.getId(), KbAction.DOC_WRITE, bob));
        }
    }

    // ---------------------------------------------------------------- 写轴（三种预设）

    @Nested
    @DisplayName("maintain_scope 三档（§2.3）")
    class MaintainScopes {

        @Test
        @DisplayName("[保持] owner_only：组织内其他成员也不能写")
        void ownerOnlyBlocksEvenForReaders() {
            KnowledgeBase kb = maintainScope(alicePublic, KnowledgeBase.MAINTAIN_OWNER_ONLY);
            assertThat(accessService.can(kb, alice, KbAction.DOC_WRITE)).isTrue();
            assertThat(accessService.can(kb, bob, KbAction.DOC_WRITE)).isFalse();
        }

        @Test
        @DisplayName("[变更] members：名单只在组织内生效，跨组织的个人授权不穿透")
        void membersScopeFollowsRosterInsideOrgOnly() {
            // v1 没有名单概念。v2 定了名单是「组织内的加法」（规范 §9：不做跨组织授权外部维护者）：
            // bob 不在 alice 的个人组织里，把他写进名单不产生任何权利 —— 移出组织即失效，
            // 否则残留的 kb_member 行会变成静默的权限后门。组织内名单的正向矩阵见 AccessServiceTest$WriteAxis
            KnowledgeBase kb = maintainScope(alicePublic, KnowledgeBase.MAINTAIN_MEMBERS);
            grantKbMember(kb.getId(), bob, KbMember.ROLE_EDITOR);
            assertThat(accessService.can(kb, bob, KbAction.DOC_WRITE)).isFalse();

            grantKbMember(kb.getId(), alice, KbMember.ROLE_VIEWER);
            assertThat(accessService.can(kb, alice, KbAction.DOC_READ)).isTrue();
            assertThat(accessService.can(kb, alice, KbAction.DOC_DELETE)).isTrue();
        }

        @Test
        @DisplayName("[保持] 名单是加法：能写内容的人，不能删库、不能改可见性")
        void rosterCannotGovern() {
            KnowledgeBase kb = maintainScope(alicePublic, KnowledgeBase.MAINTAIN_MEMBERS);
            grantKbMember(kb.getId(), alice, KbMember.ROLE_EDITOR);
            // alice 是创建者，写当然通；这里锁的是「名单身份不带来治理权」
            assertThat(accessService.can(kb, alice, KbAction.DOC_WRITE)).isTrue();

            LoginUser outsider = as(saveUser("carol", "卡罗尔", User.ROLE_USER));
            grantKbMember(kb.getId(), outsider, KbMember.ROLE_EDITOR);
            // 组织外的人连写都拿不到，更不用谈治理
            assertThat(accessService.can(kb, outsider, KbAction.DOC_WRITE)).isFalse();
            assertThat(accessService.can(kb, outsider, KbAction.KB_DELETE)).isFalse();
            assertThat(accessService.can(kb, outsider, KbAction.KB_SET_VISIBILITY)).isFalse();
        }
    }

    // ---------------------------------------------------------------- 列表与统计

    @Nested
    @DisplayName("page / stats —— 可见集")
    class VisibilityScopes {

        @Test
        @DisplayName("[保持] 游客只见 public 库")
        void anonymousSeesOnlyPublic() {
            List<String> names = names(kbService.page(null, null, null, null, null, null, 1, 50));
            assertThat(names).containsExactlyInAnyOrder("Alice 公开库", "Bob 公开库");
        }

        @Test
        @DisplayName("[变更] 登录用户可见集 = public + 自己的库（v1 只见自己的）")
        void loginUserSeesPublicPlusOwn() {
            assertThat(names(kbService.page(alice, null, null, null, null, null, 1, 50)))
                    .containsExactlyInAnyOrder("Alice 公开库", "Alice 私密库", "Bob 公开库");
        }

        @Test
        @DisplayName("[变更] mine=true 收回 v1 语义：只看我创建的库")
        void mineNarrowsToOwn() {
            assertThat(names(kbService.page(alice, null, null, null, null, true, 1, 50)))
                    .containsExactlyInAnyOrder("Alice 公开库", "Alice 私密库");
        }

        @Test
        @DisplayName("[变更] visibility 过滤在可见集之上：他人的 public 库也进入结果")
        void visibilityFilter() {
            // v1 只可能是自己的库；v2 的 public 是全平台可见的
            List<String> names = names(kbService.page(alice, null, null, PUBLIC, null, null, 1, 50));
            assertThat(names).containsExactlyInAnyOrder("Alice 公开库", "Bob 公开库");
        }

        @Test
        @DisplayName("[保持] 关键字命中名称 / 描述 / 标签")
        void keywordMatchesNameDescriptionAndTags() {
            assertThat(names(kbService.page(alice, "私密", null, null, null, null, 1, 50))).hasSize(1);
            assertThat(names(kbService.page(alice, "基线测试描述", null, null, null, null, 1, 50))).hasSize(3);
            assertThat(names(kbService.page(alice, "基线", null, null, null, null, 1, 50))).hasSize(3);
        }

        @Test
        @DisplayName("[保持] 游客统计 = 全部 public 库")
        void anonymousStats() {
            StatsVO stats = kbService.stats(null);
            assertThat(stats.kbTotal()).isEqualTo(2);
            assertThat(stats.kbPublic()).isEqualTo(2);
        }

        @Test
        @DisplayName("[变更] 登录用户统计按可见集计算，不再只算自己名下")
        void loginUserStatsFollowsVisibleSet() {
            StatsVO stats = kbService.stats(alice);
            assertThat(stats.kbTotal()).isEqualTo(3);
            assertThat(stats.kbPublic()).isEqualTo(2);
            assertThat(stats.kbPrivate()).isEqualTo(1);
        }

        @Test
        @DisplayName("[保持] 超管统计覆盖全平台")
        void superAdminStatsCoverEverything() {
            StatsVO stats = kbService.stats(root);
            assertThat(stats.kbTotal()).isEqualTo(4);
        }

        @Test
        @DisplayName("[变更] 三档可见性各自计数，org 不再被算进私有")
        void visibilityBucketsAreDisjoint() {
            createKb(alice, "Alice 组织库", KnowledgeBase.VISIBILITY_ORG);
            StatsVO stats = kbService.stats(root);
            assertThat(stats.kbOrg()).isEqualTo(1);
            assertThat(stats.kbPublic()).isEqualTo(2);
            assertThat(stats.kbPrivate()).isEqualTo(2);
            assertThat(stats.kbPublic() + stats.kbOrg() + stats.kbPrivate()).isEqualTo(stats.kbTotal());
        }
    }

    // ---------------------------------------------------------------- 删除与收藏

    @Nested
    @DisplayName("删除与收藏")
    class DeleteAndFavorite {

        @Test
        @DisplayName("[变更] 删他人库不再靠 byAdmin 参数，改由超管身份判定")
        void superAdminDeletesOthersByIdentity() {
            KnowledgeBase target = kbService.getById(bobPrivate);
            // v2 按 storage_key（vaults/o{租户ID}/{slug}）定位目录，slug 单独已不足以定位
            Path dir = vaultRoot().resolve(target.getStorageKey());
            Path sibling = vaultRoot().resolve(kbService.getById(bobPublic).getStorageKey());
            assertThat(dir).exists();

            kbService.deleteKnowledgeBase(bobPrivate, root);

            assertThat(kbService.getById(bobPrivate)).isNull();
            // 磁盘是内容的权威，删目录排在提交之后：这一句证明「库里没了但盘还没动」的窗口存在，
            // 也就是回滚不会造成「记录回来了、内容没了」
            assertThat(Files.exists(dir)).isTrue();
            runAfterCommitHooks();
            assertThat(Files.exists(dir)).isFalse();
            assertThat(sibling).exists();
        }

        @Test
        @DisplayName("[保持] 组织 ADMIN 可删本组织库")
        void orgAdminCanDelete() {
            kbService.deleteKnowledgeBase(alicePrivate, alice);
            assertThat(kbService.getById(alicePrivate)).isNull();
        }

        @Test
        @DisplayName("[保持] 他人 public 库可读不可删 → 403")
        void publicKbNotDeletableByStranger() {
            assertBizError(ErrorCode.FORBIDDEN, () -> kbService.deleteKnowledgeBase(bobPublic, alice));
        }

        @Test
        @DisplayName("[变更] 他人 private 库删不动，且返回 404（v1 是 403）")
        void privateKbInvisibleToDelete() {
            assertBizError(ErrorCode.NOT_FOUND, () -> kbService.deleteKnowledgeBase(bobPrivate, alice));
        }

        @Test
        @DisplayName("[保持] 删除级联清理该库的收藏记录")
        void deleteCascadesFavorites() {
            kbService.switchFavorite(bobPublic, alice, true);
            kbService.deleteKnowledgeBase(bobPublic, bob);
            assertThat(names(kbService.page(alice, null, null, null, true, null, 1, 50)))
                    .doesNotContain("Bob 公开库");
        }

        @Test
        @DisplayName("[变更] v1 缺陷已修：不可见的库不可收藏")
        void favoriteRejectsInvisibleKb() {
            // v1 只校验存在性，bob 能把 alice 的私密库收进自己的收藏夹
            assertBizError(ErrorCode.NOT_FOUND, () -> kbService.switchFavorite(alicePrivate, bob, true));
        }

        @Test
        @DisplayName("[保持] 可收藏自己可见的库，取消收藏返回 false")
        void favoriteRoundTrip() {
            assertThat(kbService.switchFavorite(bobPublic, alice, true)).isTrue();
            assertThat(kbService.switchFavorite(bobPublic, alice, false)).isFalse();
        }
    }

    // ---------------------------------------------------------------- slug 命名空间

    @Nested
    @DisplayName("slug 命名空间")
    class SlugNamespace {

        @Test
        @DisplayName("[变更] slug 只在组织内唯一：不同用户建同名库得到同一个 slug")
        void slugIsTenantScoped() {
            String first = kbService.getById(alicePublic).getSlug();
            Long second = createKb(bob, "Alice 公开库", PUBLIC);
            // v1 会得到 first + "-2"；v2 两个组织各自独立，目录由 storage_key 区分
            assertThat(kbService.getById(second).getSlug()).isEqualTo(first);
            assertThat(kbService.getById(second).getStorageKey())
                    .isNotEqualTo(kbService.getById(alicePublic).getStorageKey());
        }

        @Test
        @DisplayName("[变更] 同组织内重名仍加后缀")
        void sameTenantStillSuffixed() {
            Long second = createKb(alice, "Alice 公开库", PUBLIC);
            assertThat(kbService.getById(second).getSlug())
                    .isEqualTo(kbService.getById(alicePublic).getSlug() + "-2");
        }

        @Test
        @DisplayName("[变更] findBySlug 只是定位手段，不再等于放行")
        void findBySlugGrantsNothing() {
            KnowledgeBase kb = kbService.findBySlug(kbService.getById(alicePrivate).getSlug());
            assertThat(kb).isNotNull();
            assertThat(kb.getVisibility()).isEqualTo(PRIVATE);
            // v1 门户拿到返回值就直接渲染；v2 门户必须再过一次读门
            assertBizError(ErrorCode.NOT_FOUND, () -> accessService.requireKb(kb.getId(), KbAction.KB_VIEW, bob));
        }
    }

    private static List<String> names(PageResult<KbVO> page) {
        return page.list().stream().map(KbVO::name).toList();
    }
}
