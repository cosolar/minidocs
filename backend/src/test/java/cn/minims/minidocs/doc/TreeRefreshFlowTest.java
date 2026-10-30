package cn.minims.minidocs.doc;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录树的「清缓存后重扫」。
 *
 * <p>回归的那条契约：正文是磁盘上的文件，作者完全可能绕过本服务直接增删（rsync、Git 客户端、
 * 外部编辑器），而目录树是<b>进程内缓存</b>的，只有应用内的写操作才会失效它。于是出现
 * 「在系统里删了文件，左栏点刷新还在」。修法不是让普通读每次都扫盘（那等于废掉缓存），
 * 而是给一个显式的 refresh 入口，把「以磁盘为准」这个意图单独表达出来。</p>
 *
 * <p>本用例刻意断言了「普通读命中缓存」这一条 —— 它是 refresh 存在的理由，哪天缓存策略改成
 * 按目录 mtime 失效，这条会先红，提醒改动者回头把 refresh 的注释与前端提示一起更新。</p>
 */
@DisplayName("目录树刷新：清缓存后重扫磁盘")
class TreeRefreshFlowTest extends MiniDocsTestBase {

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
        alice = as(saveUser("refresh-alice", "爱丽丝", User.ROLE_USER));
        Tenant team = teamOrg("refresh-acme");
        member(team.getId(), alice, TenantMember.ROLE_OWNER);
        kbId = newKb(team);
        root = vaultFileService.rootOf(kbService.getById(kbId).getStorageKey());
    }

    @Test
    @DisplayName("外部删掉的文件：普通读树照旧显示，刷新后才消失")
    void refreshDropsStaleCache() throws Exception {
        docService.createDoc(kbId, "a.md", "# A", alice);
        docService.createDoc(kbId, "b.md", "# B", alice);
        assertThat(docNames(docService.tree(kbId, alice))).containsExactly("a.md", "b.md");

        // 绕过应用直接删：这一步不经过 VaultFileService，缓存不会自己失效
        Files.delete(vaultFileService.resolve(root, "b.md"));

        assertThat(docNames(docService.tree(kbId, alice)))
                .as("普通读树命中的是缓存，磁盘上已经没有 b.md 了")
                .containsExactly("a.md", "b.md");

        assertThat(docNames(docService.refreshTree(kbId, alice)))
                .as("刷新必须作废缓存后重扫，把已删的项摘掉")
                .containsExactly("a.md");
    }

    @Test
    @DisplayName("外部新增的文件：刷新后立刻出现")
    void refreshPicksUpExternalAdds() throws Exception {
        docService.createDoc(kbId, "a.md", "# A", alice);
        // 先读一次把缓存坐实：缓存键只看 .minidocs.json 的 mtime，写一个 md 不会改它
        docService.tree(kbId, alice);
        Files.writeString(vaultFileService.resolve(root, "c.md"), "# C", StandardCharsets.UTF_8);

        assertThat(docNames(docService.refreshTree(kbId, alice))).containsExactly("a.md", "c.md");
    }

    @Test
    @DisplayName("外部新增的目录连同其下的文档一起进入树")
    void refreshPicksUpExternalDirectory() throws Exception {
        docService.createDoc(kbId, "a.md", "# A", alice);
        docService.tree(kbId, alice);
        Files.createDirectories(vaultFileService.resolve(root, "面试题库"));
        Files.writeString(vaultFileService.resolve(root, "面试题库/JVM.md"), "# JVM", StandardCharsets.UTF_8);

        List<DocNode> tree = docService.refreshTree(kbId, alice);

        // 不按下标断言：同级里目录排在文件之前是 childComparator 的既有规则，
        // 这里要守的是「新目录连同它底下的文档一起进来了」，与排序无关
        assertThat(docNames(tree)).containsExactlyInAnyOrder("a.md", "面试题库/JVM.md");
    }

    @Test
    @DisplayName("每日计数校准顺手清树缓存：篇数与列表不会再各说各话")
    void countCalibrationAlsoDropsTreeCache() throws Exception {
        docService.createDoc(kbId, "a.md", "# A", alice);
        docService.createDoc(kbId, "b.md", "# B", alice);
        docService.tree(kbId, alice);
        Files.delete(vaultFileService.resolve(root, "b.md"));

        kbService.refreshAllDocCounts();

        // 计数本就是实时扫盘的，这一条只是前提；要守的是它别把树留在旧状态上
        assertThat(kbService.getById(kbId).getDocCount()).isEqualTo(1);
        assertThat(docNames(docService.tree(kbId, alice)))
                .as("校准过计数就该顺带把树一起作废，否则界面会自相矛盾")
                .containsExactly("a.md");
    }

    // ------------------------------------------------------------------ 工具

    /** 树里的全部 Markdown 相对路径（含子目录），按遍历顺序。 */
    private static List<String> docNames(List<DocNode> nodes) {
        List<String> paths = new ArrayList<>();
        collect(nodes, paths);
        return paths;
    }

    private static void collect(List<DocNode> nodes, List<String> collector) {
        for (DocNode node : nodes) {
            if ("doc".equals(node.type())) {
                collector.add(node.path());
            } else if (node.children() != null) {
                collect(node.children(), collector);
            }
        }
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
        kb.setSlug("refresh-kb-" + SLUG.incrementAndGet());
        kb.setStorageKey(StorageKey.of(team.getId(), kb.getSlug()));
        kb.setVisibility(KnowledgeBase.VISIBILITY_ORG);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_MEMBERS);
        kb.setDocCount(0);
        kbService.save(kb);
        vaultFileService.ensureRoot(kb.getStorageKey());
        return kb.getId();
    }
}
