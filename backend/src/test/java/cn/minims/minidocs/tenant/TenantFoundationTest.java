package cn.minims.minidocs.tenant;

import cn.minims.minidocs.config.bootstrap.VaultDirectoryRelocator;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 租户地基：个人组织、knowledge_base 新列、storage_key 与磁盘搬迁。
 *
 * <p>本类跑在 {@code backend/target/test-vault} 临时目录下，不会触碰 {@code data/vaults}，
 * 因此可以真做目录搬迁断言 —— 这是允许碰真实数据目录之前的验收条件。</p>
 */
@DisplayName("M1 租户地基")
class TenantFoundationTest extends MiniDocsTestBase {

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberMapper memberMapper;

    @Autowired
    private VaultFileService vaultFileService;

    @Autowired
    private VaultDirectoryRelocator relocator;

    private User alice;

    @BeforeEach
    void seedUser() {
        alice = saveUser("alice", "爱丽丝", User.ROLE_USER);
    }

    @Test
    @DisplayName("建库时惰性创建个人组织，slug 为 u-{用户名}、不可发现、不可申请")
    void createsPersonalTenantOnFirstKb() {
        kbService.create(null, createKb("我的库", null, "private", null, List.of(), null), as(alice));

        Tenant tenant = tenantService.ensurePersonalTenant(alice.getId());
        assertThat(tenant.getId()).isNotNull();
        assertThat(tenant.getSlug()).isEqualTo("u-alice");
        assertThat(tenant.getName()).isEqualTo("爱丽丝");
        assertThat(tenant.getType()).isEqualTo(Tenant.TYPE_PERSONAL);
        assertThat(tenant.getOwnerUserId()).isEqualTo(alice.getId());
        assertThat(tenant.getJoinPolicy()).isEqualTo(Tenant.JOIN_INVITE_ONLY);
        assertThat(tenant.getDiscoverable()).isFalse();
        assertThat(tenant.isActive()).isTrue();
    }

    @Test
    @DisplayName("个人组织必须自带 OWNER 成员行，否则建了组织也看不见自己的库")
    void personalTenantHasOwnerMembership() {
        Tenant tenant = tenantService.ensurePersonalTenant(alice.getId());

        List<TenantMember> members = memberMapper.selectList(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenant.getId()));
        assertThat(members).singleElement().satisfies(member -> {
            assertThat(member.getUserId()).isEqualTo(alice.getId());
            assertThat(member.getRole()).isEqualTo(TenantMember.ROLE_OWNER);
            assertThat(member.getJoinedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("ensurePersonalTenant 幂等，重复调用不产生第二个组织")
    void ensurePersonalTenantIsIdempotent() {
        Tenant first = tenantService.ensurePersonalTenant(alice.getId());
        Tenant second = tenantService.ensurePersonalTenant(alice.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(tenantService.count(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getOwnerUserId, alice.getId()))).isEqualTo(1);
    }

    @Test
    @DisplayName("同一用户的库复用同一个组织，storage_key 形如 o{租户ID}/{slug}")
    void kbRowsCarryTenantAndStorageKey() {
        KbFixture fixture = new KbFixture();
        Long firstKb = fixture.create("第一本");
        Long secondKb = fixture.create("第二本");

        KnowledgeBase first = kbService.getById(firstKb);
        KnowledgeBase second = kbService.getById(secondKb);
        Long tenantId = tenantService.ensurePersonalTenant(alice.getId()).getId();

        assertThat(first.getTenantId()).isEqualTo(tenantId);
        assertThat(second.getTenantId()).isEqualTo(tenantId);
        assertThat(first.getStorageKey()).isEqualTo("o" + tenantId + "/" + first.getSlug());
        assertThat(second.getStorageKey()).isEqualTo("o" + tenantId + "/" + second.getSlug());
        assertThat(first.getMaintainScope()).isEqualTo(KnowledgeBase.MAINTAIN_OWNER_ONLY);
    }

    @Test
    @DisplayName("新库的目录落在租户目录下，而不是 vaults/{slug}")
    void kbDirectoryFollowsStorageKey() {
        Long kbId = new KbFixture().create("落盘库");
        KnowledgeBase kb = kbService.getById(kbId);

        assertThat(vaultFileService.rootExists(kb.getStorageKey())).isTrue();
        assertThat(Files.isDirectory(vaultRoot().resolve(kb.getSlug()))).isFalse();
    }

    @Test
    @DisplayName("搬迁把 v1 的 vaults/{slug} 移进租户目录，文档随行，重复执行不再移动")
    void relocatesLegacyDirectory() throws Exception {
        Long kbId = new KbFixture().create("遗留库");
        KnowledgeBase kb = kbService.getById(kbId);
        Path legacy = vaultRoot().resolve(kb.getSlug());
        Path tenantDir = vaultRoot().resolve(kb.getStorageKey());

        Files.writeString(tenantDir.resolve("说明.md"), "内容", StandardCharsets.UTF_8);
        Files.createDirectories(tenantDir.getParent());
        Files.move(tenantDir, legacy);
        assertThat(Files.isDirectory(legacy)).isTrue();

        assertThat(relocator.relocateLegacyDirectories()).isEqualTo(1);

        assertThat(Files.exists(legacy)).isFalse();
        assertThat(tenantDir.resolve("说明.md")).exists();
        assertThat(Files.readString(tenantDir.resolve("说明.md"), StandardCharsets.UTF_8)).isEqualTo("内容");
        assertThat(relocator.relocateLegacyDirectories()).isZero();
    }

    @Test
    @DisplayName("有一行的 storage_key 不可用时整批中止，动磁盘之前就把已经能搬的库留在原位")
    void relocationAbortsBeforeTouchingDiskWhenAnyStorageKeyUnavailable() throws Exception {
        // V3 之后 storage_key 是 NOT NULL，所以「迁得一半」的真实形状是空串或被旧代码写成 slug：
        // 两种都必须 abort，且回退到 slug 定位目录这条歧路绝不能出现
        relocateWithUnusableKey("");
        relocateWithUnusableKey("缺键库");
    }

    /** 把一本库的 storage_key 改成不可用的值，另一本保持正确，验证整批不动。 */
    private void relocateWithUnusableKey(String unusableKey) throws Exception {
        Long keptId = new KbFixture().create("可搬库");
        Long brokenId = new KbFixture().create("缺键库");
        KnowledgeBase kept = kbService.getById(keptId);
        String validKey = kbService.getById(brokenId).getStorageKey();
        // 两本库都退成 v1 的目录形态；坏键的那本排在后面，所以「没搬」才证明预检在动磁盘之前
        for (KnowledgeBase kb : List.of(kept, kbService.getById(brokenId))) {
            Path tenantDir = vaultRoot().resolve(kb.getStorageKey());
            Files.createDirectories(tenantDir);
            Files.writeString(tenantDir.resolve("说明.md"), "内容", StandardCharsets.UTF_8);
            Files.move(tenantDir, vaultRoot().resolve(kb.getSlug()));
        }
        kbService.update(Wrappers.<KnowledgeBase>lambdaUpdate().eq(KnowledgeBase::getId, brokenId)
                .set(KnowledgeBase::getStorageKey, unusableKey));

        assertThatThrownBy(() -> relocator.relocateLegacyDirectories())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage_key");

        assertThat(Files.isDirectory(vaultRoot().resolve(kept.getSlug()))).isTrue();
        assertThat(Files.exists(vaultRoot().resolve(kept.getStorageKey()))).isFalse();

        // 同事务里上一轮的坏行还在，会把后面的形状不清掉，所以先复位再进下一种形态
        kbService.update(Wrappers.<KnowledgeBase>lambdaUpdate().eq(KnowledgeBase::getId, brokenId)
                .set(KnowledgeBase::getStorageKey, validKey));
    }

    @Test
    @DisplayName("storage_key 缺失或形态非法时直接抛错，绝不回退到 slug 定位目录")
    void storageKeyGuardFailsFast() {
        assertThatThrownBy(() -> vaultFileService.rootOf(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage_key");
        assertThatThrownBy(() -> vaultFileService.rootOf("some-slug"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法");
        assertThatThrownBy(() -> vaultFileService.rootOf("o1/../../etc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法");
    }

    private class KbFixture {
        Long create(String name) {
            return kbService.create(null, createKb(name, null, "private", null, List.of(), null), as(alice)).id();
        }
    }
}
