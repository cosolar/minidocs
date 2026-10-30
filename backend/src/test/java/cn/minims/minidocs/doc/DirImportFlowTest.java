package cn.minims.minidocs.doc;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.doc.dto.DocDtos.ImportResult;
import cn.minims.minidocs.doc.service.DocService;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选目录导入：{@code paths} 里带几层目录，库里就长几层，<b>根目录名不再被剥掉</b>。
 *
 * <p>前端 {@code relativeOf} 直接把 {@code webkitRelativePath} 送过来（形如
 * {@code 场景设计题/sub/a.md}）。它以前会剥掉第一段，于是同一个文件夹「打包成 zip 导入」
 * 与「选目录导入」长出两棵不一样的树 —— 前者多一层，用户只觉得是 bug。</p>
 *
 * <p>这条契约只有前端在实现，而前端没有单测，所以按后端的视角把它钉在这里：
 * 只要 {@code paths} 里出现了根目录名，库里就必须出现同名文件夹。</p>
 */
@DisplayName("选目录导入保留根文件夹")
class DirImportFlowTest extends MiniDocsTestBase {

    /** slug 每个用例独占：DB 会回滚，磁盘不会。 */
    private static final AtomicInteger SLUG = new AtomicInteger();

    @Autowired
    private DocService docService;

    @Autowired
    private VaultFileService vaultFileService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    private Long kbId;
    private LoginUser alice;
    private Path root;

    @BeforeEach
    void seed() {
        alice = as(saveUser("dir-alice", "爱丽丝", User.ROLE_USER));
        Tenant team = teamOrg("dir-acme");
        member(team.getId(), alice, TenantMember.ROLE_OWNER);
        kbId = newKb(team);
        root = vaultFileService.rootOf(kbService.getById(kbId).getStorageKey());
    }

    @Test
    @DisplayName("paths 带根目录名：库里长出同名文件夹，子树一并还原")
    void keepsRootFolder() {
        ImportResult result = importDocs(
                new MultipartFile[]{md("a.md", "# A"), md("b.md", "# B")},
                "[\"场景设计题/a.md\",\"场景设计题/sub/b.md\"]");

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(vaultFileService.read(root, "场景设计题/a.md")).isEqualTo("# A");
        assertThat(vaultFileService.read(root, "场景设计题/sub/b.md")).isEqualTo("# B");
        assertThat(Files.isDirectory(vaultFileService.resolve(root, "场景设计题"))).isTrue();
    }

    @Test
    @DisplayName("paths 全为空串（选文件导入）：按文件名平铺在落点目录")
    void flattensWhenNoRelativePath() {
        ImportResult result = importDocs(
                new MultipartFile[]{md("a.md", "# A")},
                "[\"\"]");

        assertThat(result.imported()).isEqualTo(1);
        assertThat(vaultFileService.read(root, "a.md")).isEqualTo("# A");
    }

    @Test
    @DisplayName("targetDir 是落点前缀，叠在根目录名之上")
    void targetDirPrefixesRootFolder() {
        ImportResult result = docService.importFiles(kbId, "落点",
                new MultipartFile[]{md("a.md", "# A")},
                "[\"场景设计题/a.md\"]", alice);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(vaultFileService.read(root, "落点/场景设计题/a.md")).isEqualTo("# A");
    }

    // ------------------------------------------------------------------ 工具

    private ImportResult importDocs(MultipartFile[] files, String relativePathsJson) {
        return docService.importFiles(kbId, null, files, relativePathsJson, alice);
    }

    private static MockMultipartFile md(String name, String content) {
        return new MockMultipartFile("files", name, "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private Tenant teamOrg(String slug) {
        Tenant tenant = new Tenant();
        tenant.setSlug(slug);
        tenant.setName(slug);
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setOwnerUserId(alice.id());
        tenant.setJoinPolicy(Tenant.JOIN_REQUEST);
        tenant.setDiscoverable(true);
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenantMapper.insert(tenant);
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

    private Long newKb(Tenant team) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(team.getId());
        kb.setOwnerId(alice.id());
        kb.setName("库名");
        kb.setSlug("dir-kb-" + SLUG.incrementAndGet());
        kb.setStorageKey(StorageKey.of(team.getId(), kb.getSlug()));
        kb.setVisibility(KnowledgeBase.VISIBILITY_ORG);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_MEMBERS);
        kb.setDocCount(0);
        kbService.save(kb);
        vaultFileService.ensureRoot(kb.getStorageKey());
        return kb.getId();
    }
}
