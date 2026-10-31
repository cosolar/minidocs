package cn.minims.minidocs.doc;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KbConfigEntity;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbConfigMapper;
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
 * 库级配置存在数据库，Git 操作碰不到它。
 *
 * <p>回归的那条契约：隐藏规则 / 目录顺序此前写在库根的 {@code .minidocs.json}，而它是个
 * 未跟踪文件 —— 强制拉取的 {@code reset --hard + clean}、换仓库的重克隆都会把它删掉，
 * 用户看到的是「一拉取配置就重置」。现在配置落 {@code kb_config} 表，目录里不再产生文件，
 * 旧文件只在数据库无记录时导入一次。</p>
 */
@DisplayName("库级配置：落库、迁移与清理")
class KbConfigStorageTest extends MiniDocsTestBase {

    private static final AtomicInteger SLUG = new AtomicInteger();

    @Autowired
    private VaultFileService vaultFileService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private KbConfigMapper configMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    private LoginUser alice;
    private KnowledgeBase kb;
    private Path root;

    @BeforeEach
    void seed() {
        alice = as(saveUser("config-alice", "爱丽丝", User.ROLE_USER));
        Tenant team = new Tenant();
        team.setSlug("config-acme");
        team.setName("config-acme");
        team.setType(Tenant.TYPE_TEAM);
        team.setOwnerUserId(alice.id());
        team.setJoinPolicy(Tenant.JOIN_REQUEST);
        team.setDiscoverable(true);
        team.setStatus(Tenant.STATUS_ACTIVE);
        tenantMapper.insert(team);

        TenantMember member = new TenantMember();
        member.setTenantId(team.getId());
        member.setUserId(alice.id());
        member.setRole(TenantMember.ROLE_OWNER);
        member.setJoinedFrom("test");
        tenantMemberMapper.insert(member);

        kb = new KnowledgeBase();
        kb.setTenantId(team.getId());
        kb.setOwnerId(alice.id());
        kb.setName("库名");
        kb.setSlug("config-kb-" + SLUG.incrementAndGet());
        kb.setStorageKey(StorageKey.of(team.getId(), kb.getSlug()));
        kb.setVisibility(KnowledgeBase.VISIBILITY_ORG);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_MEMBERS);
        kb.setDocCount(0);
        kbService.save(kb);
        root = vaultFileService.ensureRoot(kb.getStorageKey());
    }

    @Test
    @DisplayName("保存隐藏规则：写数据库、目录树立即过滤，库里不产生配置文件")
    void hiddenRulesLiveInDatabase() throws Exception {
        write("a.md");
        write("drafts/secret.md");
        assertThat(docPaths(vaultFileService.tree(root)))
                .containsExactlyInAnyOrder("a.md", "drafts/secret.md");

        vaultFileService.saveHiddenRules(root, List.of("drafts"));

        assertThat(docPaths(vaultFileService.tree(root)))
                .as("配置一落库，缓存键就变，不必刷新")
                .containsExactly("a.md");
        assertThat(vaultFileService.hiddenRules(root)).containsExactly("drafts");
        assertThat(vaultFileService.countDocs(root)).isEqualTo(1);
        assertThat(configMapper.selectById(kb.getStorageKey()).getConfigJson()).contains("drafts");
        assertThat(Files.exists(root.resolve(".minidocs.json")))
                .as("配置不该再写进知识库目录 —— 那是 Git 清理会碰到的地方")
                .isFalse();
    }

    @Test
    @DisplayName("旧版 .minidocs.json 只导入一次：文件保留，之后以数据库为准")
    void legacyFileImportedOnce() throws Exception {
        write("a.md");
        write("drafts/secret.md");
        Path legacy = root.resolve(".minidocs.json");
        Files.writeString(legacy, """
                {
                  "hidden": ["drafts"],
                  "display": { "showMdSuffix": false }
                }
                """, StandardCharsets.UTF_8);

        assertThat(vaultFileService.hiddenRules(root)).containsExactly("drafts");
        assertThat(vaultFileService.showMdSuffix(root)).isFalse();
        assertThat(configMapper.selectById(kb.getStorageKey()))
                .as("首次读取应把旧文件导入数据库")
                .isNotNull();
        assertThat(Files.exists(legacy))
                .as("文件可能是作者提交进仓库的，平台不替他删")
                .isTrue();

        Files.writeString(legacy, "{\"hidden\":[]}", StandardCharsets.UTF_8);
        assertThat(vaultFileService.hiddenRules(root))
                .as("迁移之后不再读旧文件，改它不生效")
                .containsExactly("drafts");
    }

    @Test
    @DisplayName("配置全部回到默认值：删除记录，不留空壳")
    void defaultsDeleteRow() {
        vaultFileService.saveHiddenRules(root, List.of("drafts"));
        assertThat(configMapper.selectById(kb.getStorageKey())).isNotNull();

        vaultFileService.saveHiddenRules(root, List.of());

        assertThat(configMapper.selectById(kb.getStorageKey())).isNull();
        assertThat(vaultFileService.hiddenRules(root)).isEmpty();
    }

    @Test
    @DisplayName("删除知识库：配置行随事务删除，同名新库不会继承旧规则")
    void deleteKbDropsConfig() {
        vaultFileService.saveHiddenRules(root, List.of("drafts"));

        kbService.deleteKnowledgeBase(kb.getId(), alice);

        KbConfigEntity row = configMapper.selectById(kb.getStorageKey());
        assertThat(row).isNull();
    }

    // ------------------------------------------------------------------ 工具

    private void write(String relative) throws Exception {
        Path file = vaultFileService.resolve(root, relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# " + file.getFileName(), StandardCharsets.UTF_8);
    }

    private static List<String> docPaths(List<DocNode> nodes) {
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
}
