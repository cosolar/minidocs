package cn.minims.minidocs.portal;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.doc.service.DocService;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.portal.controller.PortalApiController;
import cn.minims.minidocs.reader.support.CurrentUserResolver;
import cn.minims.minidocs.share.controller.ShareApiController;
import cn.minims.minidocs.share.dto.ShareDtos;
import cn.minims.minidocs.share.service.ShareService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 封面不参与访问口令门。
 *
 * <p>封面是平台元数据里的一张标题图，不是文档内容：库一旦发布，封面就该对所有访问者可见，
 * 不该因为「分享设了访问口令」而 404 —— 那会让门户卡片上只剩一张占位图，
 * 藏住的还是一个列表早就公开了名字的库。</p>
 *
 * <p>同时锁住边界没有放松：正文、库内图片、分享页资源仍然要求先过口令。</p>
 */
@DisplayName("封面不参与口令门")
class PortalCoverAccessTest extends MiniDocsTestBase {

    /** 1×1 PNG：过扩展名 + 魔数校验，不必往仓库里塞图片资源。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lV4cQwAAAABJRU5ErkJggg==");

    private static final String KB_NAME = "封面口令测试";
    private static final String PASSWORD = "cover-secret-123";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private PortalApiController portalController;
    @Autowired
    private ShareApiController shareController;
    @Autowired
    private KnowledgeBaseService kbService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private DocService docService;
    @Autowired
    private ShareService shareService;

    /** 默认返回 null = 游客；本类只用匿名视角，正好覆盖「不登录也要出图」的诉求。 */
    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @Test
    @DisplayName("加密库的封面匿名可取，正文与库内图片仍要口令")
    void encryptedKbCoverIsPublicButContentIsGated() throws Exception {
        Fixture fixture = publishedKb(true);

        ResponseEntity<Resource> cover = portalController.cover(
                fixture.tenantSlug(), fixture.kbSlug(), null, new MockHttpServletRequest("GET", "/kb/x/y/cover"));
        assertThat(cover.getStatusCode().value()).isEqualTo(200);
        assertThat(cover.getBody()).isNotNull();
        assertThat(cover.getBody().getInputStream().readAllBytes()).isEqualTo(PNG);

        // 库内图片：口令门先拦，与文件在不在无关
        MockHttpServletRequest assetRequest = new MockHttpServletRequest(
                "GET", "/kb/" + fixture.tenantSlug() + "/" + fixture.kbSlug() + "/asset/photo.png");
        assertThatThrownBy(() -> portalController.asset(
                fixture.tenantSlug(), fixture.kbSlug(), null, assetRequest))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("知识库不存在或未公开");

        // 阅读接口仍返回「需要口令」，正文没有被顺带放开
        var read = portalController.read(fixture.tenantSlug(), fixture.kbSlug(), null,
                new MockHttpServletRequest("GET", "/api/portal/kb/x/y"));
        assertThat(read.data().state()).isEqualTo("password");
    }

    @Test
    @DisplayName("未发布的库封面仍然 404，不确认存在性")
    void unpublishedKbCoverStaysHidden() {
        Fixture fixture = publishedKb(false);

        assertThatThrownBy(() -> portalController.cover(
                fixture.tenantSlug(), fixture.kbSlug(), null, new MockHttpServletRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("知识库不存在或未公开");
    }

    @Test
    @DisplayName("加密分享的封面匿名可取，分享资源仍要口令")
    void encryptedShareCoverIsPublicButAssetIsGated() {
        Fixture fixture = publishedKb(true);

        ResponseEntity<Resource> cover = shareController.cover(fixture.shareToken(), null);
        assertThat(cover.getStatusCode().value()).isEqualTo(200);

        MockHttpServletRequest assetRequest = new MockHttpServletRequest(
                "GET", "/share/" + fixture.shareToken() + "/asset/photo.png");
        assertThatThrownBy(() -> shareController.asset(fixture.shareToken(), null, assetRequest))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分享不存在或已被撤销");
    }

    @Test
    @DisplayName("已撤销的分享连封面一起下线")
    void revokedShareHidesCover() {
        Fixture fixture = publishedKb(true);
        shareService.revoke(fixture.tenantId(), fixture.shareToken(), fixture.actor());

        assertThatThrownBy(() -> shareController.cover(fixture.shareToken(), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分享不存在或已被撤销");
    }

    /**
     * 建库 + 传封面 + （可选）发布成加密分享。
     *
     * <p>{@code publish=false} 时库处于「没对外」状态，用来锁住「未发布仍然 404」。</p>
     */
    private Fixture publishedKb(boolean publish) {
        int seq = SEQ.incrementAndGet();
        String name = KB_NAME + "-" + seq;
        User owner = saveUser("cover-access-" + seq, name, User.ROLE_USER);
        LoginUser actor = as(owner);
        KbVO created = kbService.create(null, createKb(name, "封面口令测试", KnowledgeBase.VISIBILITY_ORG,
                null, List.of(), null), actor);
        KnowledgeBase kb = kbService.getById(created.id());
        Tenant tenant = tenantService.getById(kb.getTenantId());

        docService.uploadCover(kb.getId(),
                new MockMultipartFile("file", "cover.png", "image/png", PNG), actor);

        // 库内放一张真图：用它证明「资源接口拦的是口令，不是文件不存在」
        writeVaultAsset(kb, "photo.png");

        String token = null;
        if (publish) {
            ShareDtos.ShareVO share = shareService.createOrUpdate(kb.getTenantId(),
                    new ShareDtos.CreateRequest(kb.getSlug(), "kb", null, PASSWORD, true, null,
                            "anonymous", null, null, null, null),
                    actor, TEST_BASE_URL);
            token = share.token();
        }
        return new Fixture(kb.getTenantId(), tenant.getSlug(), kb.getSlug(), token, actor);
    }

    private void writeVaultAsset(KnowledgeBase kb, String fileName) {
        try {
            Path dir = vaultRoot().resolve(kb.getStorageKey()).resolve("assets");
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), PNG);
        } catch (Exception e) {
            throw new IllegalStateException("写入测试图片失败", e);
        }
    }

    private record Fixture(Long tenantId, String tenantSlug, String kbSlug, String shareToken, LoginUser actor) {
    }
}
