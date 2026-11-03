package cn.minims.minidocs.kb;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.config.bootstrap.CoverAssetMigrator;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.service.DocService;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KbCoverService;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 封面属于平台元数据：文件落在 {@code VAULT_HOME/platform/covers}，不写进知识库工作副本。
 */
@DisplayName("知识库封面存储")
class KbCoverFlowTest extends MiniDocsTestBase {

    /** 1×1 PNG：足够过扩展名 + 魔数校验，又不必往仓库里塞图片资源。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lV4cQwAAAABJRU5ErkJggg==");

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private KnowledgeBaseService kbService;
    @Autowired
    private KbCoverService coverService;
    @Autowired
    private DocService docService;
    @Autowired
    private CoverAssetMigrator coverAssetMigrator;
    @Autowired
    private MiniDocsProperties properties;

    @Test
    @DisplayName("上传落在平台目录，库表只记文件名，不写进 vault")
    void uploadStoresOutsideVault() {
        Created created = createKb();
        String fileName = docService.uploadCover(created.kb().getId(), png(), created.actor());

        assertThat(fileName).startsWith("k" + created.kb().getId() + "-").doesNotContain("/");
        Path stored = properties.coversDir().resolve(fileName);
        assertThat(stored).exists();
        assertThat(properties.vaultsDir().resolve(created.kb().getStorageKey())
                .resolve("assets").resolve(fileName)).doesNotExist();

        KnowledgeBase reloaded = kbService.getById(created.kb().getId());
        assertThat(reloaded.getCoverUrl()).isEqualTo(fileName);
        KbVO detail = kbService.detail(created.kb().getId(), created.actor());
        assertThat(detail.coverSrc()).endsWith("/kb/" + detail.tenantSlug() + "/" + detail.slug() + "/cover");
        assertThat(coverService.load(reloaded).length()).isEqualTo(PNG.length);
    }

    @Test
    @DisplayName("换封面删旧文件，清空封面连文件一起删")
    void replacingAndClearingRemoveOldFile() {
        Created created = createKb();
        String first = docService.uploadCover(created.kb().getId(), png(), created.actor());
        String second = docService.uploadCover(created.kb().getId(), png(), created.actor());

        assertThat(second).isNotEqualTo(first);
        assertThat(properties.coversDir().resolve(first)).doesNotExist();
        assertThat(properties.coversDir().resolve(second)).exists();

        docService.clearCover(created.kb().getId(), created.actor());
        assertThat(properties.coversDir().resolve(second)).doesNotExist();
        assertThat(kbService.getById(created.kb().getId()).getCoverUrl()).isNull();
    }

    @Test
    @DisplayName("外链封面原样下发，不经过平台目录")
    void externalCoverStaysAsIs() {
        Created created = createKb();
        String url = "https://example.com/cover.png";

        docService.setCoverUrl(created.kb().getId(), url, created.actor());

        assertThat(kbService.getById(created.kb().getId()).getCoverUrl()).isEqualTo(url);
        assertThat(kbService.detail(created.kb().getId(), created.actor()).coverSrc()).isEqualTo(url);
    }

    @Test
    @DisplayName("启动迁移把旧版库内封面搬到平台目录并改写数据库")
    void legacyCoverMovesToPlatform() throws Exception {
        Created created = createKb();
        Path legacy = properties.vaultsDir().resolve(created.kb().getStorageKey())
                .resolve("assets").resolve("cover-legacy.png");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, PNG);

        KnowledgeBase update = new KnowledgeBase();
        update.setId(created.kb().getId());
        update.setCoverUrl("assets/cover-legacy.png");
        kbService.updateById(update);

        assertThat(coverAssetMigrator.migrateLegacy()).isPositive();

        String expected = "k" + created.kb().getId() + "-cover-legacy.png";
        assertThat(kbService.getById(created.kb().getId()).getCoverUrl()).isEqualTo(expected);
        assertThat(properties.coversDir().resolve(expected)).exists();
        assertThat(legacy).doesNotExist();
    }

    @Test
    @DisplayName("删库后清理该库的平台封面")
    void deletingKbRemovesCoverFiles() {
        Created created = createKb();
        String fileName = docService.uploadCover(created.kb().getId(), png(), created.actor());
        Path stored = properties.coversDir().resolve(fileName);
        assertThat(stored).exists();

        kbService.deleteKnowledgeBase(created.kb().getId(), created.actor());
        runAfterCommitHooks();

        assertThat(stored).doesNotExist();
    }

    private Created createKb() {
        String name = "封面测试-" + SEQ.incrementAndGet();
        User owner = saveUser("cover-user-" + SEQ.incrementAndGet(), name, User.ROLE_USER);
        LoginUser actor = as(owner);
        KbVO created = kbService.create(null, createKb(name, null, "public", null, List.of(), null), actor);
        return new Created(kbService.getById(created.id()), actor);
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "cover.png", "image/png", PNG);
    }

    private record Created(KnowledgeBase kb, LoginUser actor) {
    }
}
