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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩包导入：目录结构与文件（含图片等资源）一并还原。
 *
 * <p>两条回归契约：</p>
 * <ul>
 *   <li>以前 importZip 只写 Markdown，图片被整批跳过，导入后文档里的图片全裂；现在条目按原
 *       相对路径落库，渲染时 {@code MarkdownService} 按文档目录解析出的正是原路径。</li>
 *   <li><b>按普通解压的语义走</b>：包内结构原样落库，最外层那个文件夹不再被剥掉；
 *       条目数也不再按 200 拦（体积闸门才是真的闸门）。</li>
 * </ul>
 */
@DisplayName("压缩包导入还原目录与资源")
class ZipImportFlowTest extends MiniDocsTestBase {

    /** slug 每个用例独占：DB 会回滚，磁盘不会。 */
    private static final AtomicInteger SLUG = new AtomicInteger();

    /** PNG 魔数 + 一段 IHDR，够 ImageValidator 的 magicMatches 认下它。 */
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};

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

    private Tenant team;
    private Long kbId;
    private LoginUser alice;
    private Path root;

    @BeforeEach
    void seed() {
        alice = as(saveUser("zip-alice", "爱丽丝", User.ROLE_USER));
        team = teamOrg("zip-acme");
        member(team.getId(), alice, TenantMember.ROLE_OWNER);
        kbId = newKb();
        root = vaultFileService.rootOf(kbService.getById(kbId).getStorageKey());
    }

    @Test
    @DisplayName("Markdown、图片与非图片资源按原相对路径落库，最外层文件夹一并还原")
    void restoresTreeAndAssets() throws Exception {
        byte[] zip = zipOf(
                entry("面试手册/notes/a.md", "# A\n\n![图](img/pic.png)".getBytes(StandardCharsets.UTF_8)),
                entry("面试手册/notes/img/pic.png", PNG),
                entry("面试手册/notes/deep/b.md", "# B".getBytes(StandardCharsets.UTF_8)),
                entry("面试手册/notes/files/report.pdf", "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8)));

        ImportResult result = importZip(zip);

        assertThat(result.imported()).isEqualTo(4);
        assertThat(result.failed()).isZero();
        // 包内结构原样还原
        assertThat(vaultFileService.read(root, "面试手册/notes/a.md")).contains("![图](img/pic.png)");
        assertThat(vaultFileService.read(root, "面试手册/notes/deep/b.md")).isEqualTo("# B");
        assertThat(vaultFileService.readBytes(vaultFileService.resolve(root, "面试手册/notes/img/pic.png")))
                .isEqualTo(PNG);
        assertThat(vaultFileService.readBytes(vaultFileService.resolve(root, "面试手册/notes/files/report.pdf")))
                .isEqualTo("%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8));
        /*
         * 最外层那个文件夹要留下。以前会把它当「外壳」剥掉，用户看到的是「目录没给我解压出来」——
         * 多一层自己删得掉，少一层找不回来。
         */
        assertThat(Files.isDirectory(vaultFileService.resolve(root, "面试手册"))).isTrue();
    }

    @Test
    @DisplayName("包内条目数不再按 200 拦，250 个文件整包导入")
    @SuppressWarnings("unchecked")
    void doesNotCapEntryCount() throws Exception {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            entries.add(entry("场景设计题/" + i + ".md", ("# " + i).getBytes(StandardCharsets.UTF_8)));
        }

        ImportResult result = importZip(zipOf(entries.toArray(new Map.Entry[0])));

        // 闸门是解压后的体积，不是篇数：以前第 201 个就会整包失败
        assertThat(result.imported()).isEqualTo(250);
        assertThat(result.failed()).isZero();
        assertThat(vaultFileService.read(root, "场景设计题/249.md")).isEqualTo("# 249");
    }

    @Test
    @DisplayName("还原后的图片与引用它的文档同相对路径，渲染解析得到库内原路径")
    void imageResolvesAgainstDocDirectory() throws Exception {
        docService.importFiles(kbId, null,
                new MultipartFile[]{zipFile(zipOf(
                        entry("手册/notes/a.md", "# A\n\n![图](../img/pic.png)".getBytes(StandardCharsets.UTF_8)),
                        entry("手册/img/pic.png", PNG)))},
                null, alice);

        // MarkdownService.resolveRelative 的行为复算：文档在 手册/notes/，../img/pic.png → 手册/img/pic.png
        assertThat(Files.exists(vaultFileService.resolve(root, "手册/img/pic.png"))).isTrue();
        assertThat(Files.exists(vaultFileService.resolve(root, "手册/notes/a.md"))).isTrue();
    }

    @Test
    @DisplayName("系统 / 隐藏条目不进库，且不覆盖库级配置")
    void skipsSystemEntries() throws Exception {
        byte[] zip = zipOf(
                entry("手册/a.md", "# A".getBytes(StandardCharsets.UTF_8)),
                entry("手册/.git/config", "[core]".getBytes(StandardCharsets.UTF_8)),
                entry("手册/.obsidian/app.json", "{}".getBytes(StandardCharsets.UTF_8)),
                entry("手册/node_modules/dep/index.js", "1".getBytes(StandardCharsets.UTF_8)),
                entry("手册/.minidocs.json", "{\"order\":{\"\":[\"zzz.md\"]}}".getBytes(StandardCharsets.UTF_8)));

        ImportResult result = importZip(zip);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(4);
        assertThat(result.messages()).anyMatch(m -> m.contains("系统 / 隐藏"));
        assertThat(vaultFileService.read(root, "手册/a.md")).isEqualTo("# A");
        assertThat(Files.exists(vaultFileService.resolve(root, "手册/.git/config"))).isFalse();
        assertThat(Files.exists(vaultFileService.resolve(root, "手册/.minidocs.json"))).isFalse();
    }

    @Test
    @DisplayName("扩展名与内容不符的图片按失败计入，不写入磁盘")
    void rejectsFakeImage() throws Exception {
        byte[] zip = zipOf(
                entry("手册/a.md", "# A".getBytes(StandardCharsets.UTF_8)),
                entry("手册/img/fake.png", "这不是 PNG".getBytes(StandardCharsets.UTF_8)));

        ImportResult result = importZip(zip);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.messages()).anyMatch(m -> m.contains("fake.png") && m.contains("扩展名不符"));
        assertThat(Files.exists(vaultFileService.resolve(root, "手册/img/fake.png"))).isFalse();
    }

    @Test
    @DisplayName("SVG 里的脚本在落库前被净化")
    void sanitizesSvg() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script>"
                .concat("<rect width=\"10\" height=\"10\"/></svg>").getBytes(StandardCharsets.UTF_8);

        ImportResult result = importZip(zipOf(entry("手册/logo.svg", svg)));

        assertThat(result.imported()).isEqualTo(1);
        String written = new String(
                vaultFileService.readBytes(vaultFileService.resolve(root, "手册/logo.svg")),
                StandardCharsets.UTF_8);
        assertThat(written).doesNotContain("script").contains("<rect");
    }

    @Test
    @DisplayName("targetDir 指定时，还原路径叠加在目标目录之下")
    void honorsTargetDir() throws Exception {
        ImportResult result = docService.importFiles(kbId, "目标",
                new MultipartFile[]{zipFile(zipOf(
                        entry("手册/notes/a.md", "# A".getBytes(StandardCharsets.UTF_8)),
                        entry("手册/notes/img/pic.png", PNG)))},
                null, alice);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(vaultFileService.read(root, "目标/手册/notes/a.md")).isEqualTo("# A");
        assertThat(Files.exists(vaultFileService.resolve(root, "目标/手册/notes/img/pic.png"))).isTrue();
    }

    // ------------------------------------------------------------------ 工具

    private ImportResult importZip(byte[] zip) {
        return docService.importFiles(kbId, null, new MultipartFile[]{zipFile(zip)}, null, alice);
    }

    private static MockMultipartFile zipFile(byte[] content) {
        return new MockMultipartFile("files", "手册.zip", "application/zip", content);
    }

    private static Map.Entry<String, byte[]> entry(String name, byte[] content) {
        return Map.entry(name, content);
    }

    @SafeVarargs
    private static byte[] zipOf(Map.Entry<String, byte[]>... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
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

    private Long newKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(team.getId());
        kb.setOwnerId(alice.id());
        kb.setName("库名");
        kb.setSlug("zip-kb-" + SLUG.incrementAndGet());
        kb.setStorageKey(StorageKey.of(team.getId(), kb.getSlug()));
        kb.setVisibility(KnowledgeBase.VISIBILITY_ORG);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_MEMBERS);
        kb.setDocCount(0);
        kbService.save(kb);
        vaultFileService.ensureRoot(kb.getStorageKey());
        return kb.getId();
    }
}
