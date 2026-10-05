package cn.minims.minidocs.meta;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.dto.KbDtos.UpdateRequest;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateOrgRequest;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「清空」这类写 null 的动作要真的落库。
 *
 * <p>MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段，于是元数据编辑流程里
 * 清空简介 / 去掉封面 / 取消口令 只会改内存对象：接口按内存回显「已清空」，库里仍是旧值，
 * 刷新后原样回来。分享那一处由 {@code ShareOrgScopeTest} 锁住，这里锁知识库与组织两处同形问题。</p>
 *
 * <p>断言一律读回库（{@code getById}）而不是看返回值 —— 返回值恰恰是被跳过 null 之后仍然正确的那一份。</p>
 */
@DisplayName("可空列回写：清空简介 / 封面 / 组织资料")
class NullWritebackTest extends MiniDocsTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private KnowledgeBaseService kbService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private VaultFileService vaultFileService;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    @Test
    @DisplayName("知识库：描述与封面传空串，读回来确实是 null")
    void clearsKbDescriptionAndCover() {
        LoginUser owner = owner();
        Tenant team = org(owner);
        Long id = kb(team, owner, "带上过简介的库", "旧简介", "covers/old.png");

        kbService.update(id, new UpdateRequest(null, "", null, List.of("v2"), ""), owner);

        KnowledgeBase reloaded = kbService.getById(id);
        assertThat(reloaded.getDescription()).isNull();
        assertThat(reloaded.getCoverUrl()).isNull();
        // 同一条更新里非 null 的列照常生效，证明不是「整条没写进去」
        assertThat(reloaded.getTags()).contains("v2");
    }

    @Test
    @DisplayName("知识库：不传 description / coverUrl 时保持原值，不被顺手抹掉")
    void keepsKbColumnsNotSent() {
        LoginUser owner = owner();
        Tenant team = org(owner);
        Long id = kb(team, owner, "只改名字的库", "要留下的简介", "covers/keep.png");

        kbService.update(id, new UpdateRequest("改过名", null, null, null, null), owner);

        KnowledgeBase reloaded = kbService.getById(id);
        assertThat(reloaded.getName()).isEqualTo("改过名");
        assertThat(reloaded.getDescription()).isEqualTo("要留下的简介");
        assertThat(reloaded.getCoverUrl()).isEqualTo("covers/keep.png");
    }

    @Test
    @DisplayName("组织：简介与 logo 传空串同样落库")
    void clearsOrgDescriptionAndLogo() {
        LoginUser owner = owner();
        Tenant team = org(owner);
        team.setDescription("旧简介");
        team.setLogoUrl("logo/old.png");
        tenantMapper.updateById(team);

        tenantService.update(team.getSlug(), new UpdateOrgRequest(null, "", "", null, null), owner);

        Tenant reloaded = tenantService.getById(team.getId());
        assertThat(reloaded.getDescription()).isNull();
        assertThat(reloaded.getLogoUrl()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private LoginUser owner() {
        return as(saveUser("writeback-user-" + SEQ.incrementAndGet(), "回写测试者", User.ROLE_USER));
    }

    private Tenant org(LoginUser owner) {
        Tenant tenant = new Tenant();
        tenant.setSlug("writeback-" + SEQ.incrementAndGet());
        tenant.setName(tenant.getSlug());
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setOwnerUserId(owner.id());
        tenant.setJoinPolicy(Tenant.JOIN_REQUEST);
        tenant.setDiscoverable(true);
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenantMapper.insert(tenant);
        TenantMember row = new TenantMember();
        row.setTenantId(tenant.getId());
        row.setUserId(owner.id());
        row.setRole(TenantMember.ROLE_OWNER);
        row.setJoinedFrom("test");
        tenantMemberMapper.insert(row);
        return tenant;
    }

    private Long kb(Tenant tenant, LoginUser owner, String name, String description, String coverUrl) {
        String slug = "writeback-kb-" + SEQ.incrementAndGet();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenant.getId());
        kb.setOwnerId(owner.id());
        kb.setName(name);
        kb.setSlug(slug);
        kb.setStorageKey(StorageKey.of(tenant.getId(), slug));
        kb.setDescription(description);
        kb.setCoverUrl(coverUrl);
        kb.setVisibility(KnowledgeBase.VISIBILITY_ORG);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_OWNER_ONLY);
        kb.setDocCount(0);
        kbService.save(kb);
        vaultFileService.ensureRoot(kb.getStorageKey());
        return kb.getId();
    }
}
