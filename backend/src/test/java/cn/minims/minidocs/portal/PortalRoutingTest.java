package cn.minims.minidocs.portal;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.portal.controller.LegacyKbRedirectController;
import cn.minims.minidocs.portal.controller.PortalApiController;
import cn.minims.minidocs.reader.model.ReadView;
import cn.minims.minidocs.search.service.SearchService;
import cn.minims.minidocs.search.service.SearchService.SuggestItem;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5c 门户读路径：URL 带上组织段之后，「看得到什么」由谁裁决、老链接往哪儿去。
 *
 * <p>控制器直接调用而不套 MockMvc：门户的登录态来自 Cookie 桥接，{@code CurrentUserResolver}
 * 在容器外恒为游客，而本类要锁的恰好就是游客口径 —— 成员能读到 org 库这件事已由
 * {@code AccessServiceTest} 的判定矩阵铺满，在这里重复一遍只会让两处口径各自漂移。</p>
 */
@DisplayName("M5c 门户组织路由与老链接迁出")
class PortalRoutingTest extends MiniDocsTestBase {

    /** 每次取号拼进 slug：DB 会回滚，磁盘不会 —— 目录撞上前一个用例留下的文件会以 409 失败。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private PortalApiController portalApi;
    @Autowired
    private LegacyKbRedirectController legacyRedirect;
    @Autowired
    private KnowledgeBaseService kbService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private VaultFileService vaultFileService;
    @Autowired
    private TenantMapper tenantMapper;

    @Nested
    @DisplayName("阅读数据按组织定位")
    class Read {

        @Test
        @DisplayName("同名 slug 落在两个组织：各自的 URL 打开各自的库")
        void sameSlugResolvesPerOrg() {
            LoginUser owner = alice();
            String slug = sharedSlug();
            Tenant alpha = org(owner, "alpha");
            Tenant beta = org(owner, "beta");
            Long inAlpha = kb(alpha, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");
            Long inBeta = kb(beta, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");

            assertThat(read(alpha, slug).getKbId()).isEqualTo(inAlpha);
            assertThat(read(beta, slug).getKbId()).isEqualTo(inBeta);
        }

        @Test
        @DisplayName("游客只看 public：org 库、不存在的 slug、不存在的组织同为 404")
        void guestSeesOnlyPublic() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team");
            String open = shared("open");
            String internal = shared("internal");
            kb(team, owner, open, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");
            kb(team, owner, internal, KnowledgeBase.VISIBILITY_ORG, "首页.md");

            assertThat(read(team, open).isEmpty()).isFalse();
            assertBizError(ErrorCode.NOT_FOUND, () -> read(team, internal));
            assertBizError(ErrorCode.NOT_FOUND, () -> read(team, shared("nope")));
            assertBizError(ErrorCode.NOT_FOUND, () -> portalApi.read("no-such-org", open, null).data());
        }

        @Test
        @DisplayName("渲染出的链接全带组织段：资源前缀、文档前缀、上下篇同一个口径")
        void emittedLinksCarryTheOrg() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team");
            String slug = shared("handbook");
            kb(team, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "a/1.md", "a/2.md");

            ReadView view = read(team, slug);
            // 链接是给浏览器直接请求的，要带部署前缀（context-path），故用 AppPaths 拼期望值
            String base = AppPaths.of("/kb/" + team.getSlug() + "/" + slug);
            assertThat(view.getCurrentPath()).isEqualTo("a/1.md");
            assertThat(view.getAssetPrefix()).isEqualTo(base + "/asset/");
            assertThat(view.getDocLinkPrefix()).isEqualTo(base + "?path=");
            assertThat(view.getNextLink()).isEqualTo(base + "?path=a/2.md");
        }
    }

    @Nested
    @DisplayName("资源代理按组织取目录")
    class Asset {

        @Test
        @DisplayName("同名 slug 的两个组织各有目录：只有本组织里的文件取得到")
        void servesOnlyThisOrgsDirectory() {
            LoginUser owner = alice();
            String slug = sharedSlug();
            Tenant alpha = org(owner, "alpha");
            Tenant beta = org(owner, "beta");
            Long inAlpha = kb(alpha, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");
            kb(beta, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");

            vaultFileService.createFile(rootOf(inAlpha), "logo.png", "png-bytes");

            assertThat(asset(alpha, slug, "logo.png").getStatusCode().value()).isEqualTo(200);
            assertBizError(ErrorCode.NOT_FOUND, () -> asset(beta, slug, "logo.png"));
        }

        @Test
        @DisplayName("游客取非 public 库的资源：与文件不存在同为 404")
        void guestCannotReachPrivateKbAssets() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team");
            String slug = shared("secret");
            kb(team, owner, slug, KnowledgeBase.VISIBILITY_ORG, "首页.md");

            assertBizError(ErrorCode.NOT_FOUND, () -> asset(team, slug, "logo.png"));
        }
    }

    @Nested
    @DisplayName("老链接 /kb/{slug} 迁出（§7.1.3）")
    class Legacy {

        @Test
        @DisplayName("唯一命中：301 到带组织段的新地址")
        void uniqueMatchRedirectsPermanently() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team");
            String slug = shared("handbook");
            kb(team, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");

            MockHttpServletResponse response = legacy(slug);
            assertThat(response.getStatus()).isEqualTo(301);
            assertThat(response.getHeader("Location")).isEqualTo("/kb/" + team.getSlug() + "/" + slug);
        }

        @Test
        @DisplayName("跨组织撞名：不猜目标，交给 SPA 出消歧列表")
        void ambiguousMatchFallsBackToSpa() {
            LoginUser owner = alice();
            String slug = sharedSlug();
            Tenant alpha = org(owner, "alpha");
            Tenant beta = org(owner, "beta");
            kb(alpha, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");
            kb(beta, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");

            MockHttpServletResponse response = legacy(slug);
            assertThat(response.getForwardedUrl()).isEqualTo("/index.html");
        }

        @Test
        @DisplayName("一个都没命中：同样回 SPA，由它渲染未找到态而不是白屏 404")
        void noMatchFallsBackToSpa() {
            assertThat(legacy(shared("unknown")).getForwardedUrl()).isEqualTo("/index.html");
        }

        @Test
        @DisplayName("消歧接口对游客只吐 public：列表本身不泄露私库存在性")
        void locateHidesPrivateFromGuest() {
            LoginUser owner = alice();
            String slug = sharedSlug();
            Tenant alpha = org(owner, "alpha");
            Tenant beta = org(owner, "beta");
            kb(alpha, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, "首页.md");
            kb(beta, owner, slug, KnowledgeBase.VISIBILITY_ORG, "首页.md");

            assertThat(portalApi.locate(slug).data())
                    .extracting(item -> item.tenantSlug())
                    .containsExactly(alpha.getSlug());
        }
    }

    @Nested
    @DisplayName("联想结果的链接带组织段")
    class Suggest {

        @Test
        @DisplayName("知识库 / 文档 / 标签三类都指向 /kb/{org}/{slug}")
        void everyItemCarriesTheOrg() {
            LoginUser owner = alice();
            Tenant team = org(owner, "team");
            String slug = shared("handbook");
            Long kbId = kb(team, owner, slug, KnowledgeBase.VISIBILITY_PUBLIC, slug + "-1.md");
            KnowledgeBase row = kbService.getById(kbId);
            row.setTags("部署手册");
            kbService.updateById(row);

            String base = AppPaths.of("/kb/" + team.getSlug() + "/" + slug);
            Map<String, List<SuggestItem>> items = searchService.suggest(slug, owner);
            assertThat(items.get("kbs")).extracting(SuggestItem::url).containsExactly(base);
            assertThat(items.get("docs")).extracting(SuggestItem::url).containsExactly(base + "?path=" + slug + "-1.md");
            assertThat(searchService.suggest("部署", owner).get("tags"))
                    .extracting(SuggestItem::url).containsExactly(base);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ReadView read(Tenant tenant, String slug) {
        return portalApi.read(tenant.getSlug(), slug, null).data();
    }

    private ResponseEntity<Resource> asset(Tenant tenant, String slug, String relative) {
        String uri = "/kb/" + tenant.getSlug() + "/" + slug + "/asset/" + relative;
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return portalApi.asset(tenant.getSlug(), slug, null, request);
    }

    private MockHttpServletResponse legacy(String slug) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/kb/" + slug);
        request.setRequestURI("/kb/" + slug);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            legacyRedirect.legacy(slug, request, response);
        } catch (Exception e) {
            throw new AssertionError("老链接处理抛异常", e);
        }
        return response;
    }

    private Path rootOf(Long kbId) {
        return vaultFileService.rootOf(kbService.getById(kbId).getStorageKey());
    }

    private LoginUser alice() {
        return as(saveUser("portal-alice-" + SEQ.incrementAndGet(), "爱丽丝", User.ROLE_USER));
    }

    private Tenant org(LoginUser owner, String prefix) {
        Tenant tenant = new Tenant();
        tenant.setSlug(prefix + "-" + SEQ.incrementAndGet());
        tenant.setName(tenant.getSlug());
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setOwnerUserId(owner.id());
        tenant.setJoinPolicy(Tenant.JOIN_REQUEST);
        tenant.setDiscoverable(true);
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenantMapper.insert(tenant);
        return tenant;
    }

    private String sharedSlug() {
        return "kb-" + SEQ.incrementAndGet();
    }

    private String shared(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    /** 直接落库 + 建目录：建库的权限门已在 AccessServiceTest 测过，这里只关心读路径。 */
    private Long kb(Tenant tenant, LoginUser owner, String slug, String visibility, String... docs) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenant.getId());
        kb.setOwnerId(owner.id());
        kb.setName(slug);
        kb.setSlug(slug);
        kb.setStorageKey(StorageKey.of(tenant.getId(), slug));
        kb.setVisibility(visibility);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_OWNER_ONLY);
        kb.setDocCount(0);
        kbService.save(kb);
        Path root = vaultFileService.ensureRoot(kb.getStorageKey());
        for (String doc : docs) {
            vaultFileService.createFile(root, doc, "# " + doc + "\n\n正文");
        }
        return kb.getId();
    }
}
