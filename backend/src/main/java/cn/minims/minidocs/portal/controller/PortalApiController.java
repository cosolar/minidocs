package cn.minims.minidocs.portal.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.common.web.AccessLogFilter;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.dto.KbDtos.PortalStatsVO;
import cn.minims.minidocs.kb.dto.KbDtos.StatsVO;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.reader.model.ReadView;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.reader.service.ReaderService;
import cn.minims.minidocs.reader.support.CurrentUserResolver;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.share.service.ShareService;
import cn.minims.minidocs.share.support.ShareAccessSupport;
import cn.minims.minidocs.share.support.SharePublicationSupport;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 门户（用户侧）只读 API。
 *
 * <p>页面渲染完全由 {@code front/} 的用户侧 SPA 承担，这里只提供数据与资源代理。
 * 首页与阅读页对游客开放，登录态由 Cookie 桥接自动识别（失败即视为游客）。</p>
 *
 * <p>可见性口径：<b>分享等同于发布</b>。只有存在「有效整库分享」的库才出现在门户，且对所有访问者
 * 一视同仁（登录不再多出未发布的库）。分享未加密即公开，加密即私有 —— 后者需要先过访问口令，
 * 口令校验与分享页复用同一套 {@link ShareAccessSupport} Cookie，因此私有库在门户与分享链接下
 * 是同一道门。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PortalApiController {

    private static final int PASSWORD_LIMIT = 10;
    private static final Duration PASSWORD_WINDOW = Duration.ofSeconds(60);

    private final KnowledgeBaseService knowledgeBaseService;
    private final TenantService tenantService;
    private final AccessService accessService;
    private final ReaderService readerService;
    private final VaultFileService vaultFileService;
    private final AssetService assetService;
    private final CurrentUserResolver currentUserResolver;
    private final SharePublicationSupport publicationSupport;
    private final ShareAccessSupport shareAccessSupport;
    private final ShareService shareService;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;

    /**
     * 管理台统计卡片（可见性三档）。
     *
     * <p>这是管理视角的口径，与门户首页那份「已发布」统计不是一回事，故保留独立入口。</p>
     */
    @GetMapping("/api/portal/stats")
    public ApiResponse<StatsVO> stats() {
        return ApiResponse.ok(knowledgeBaseService.stats(UserContext.get()));
    }

    /** 门户首页：已发布库的统计概览 + 当前用户。卡片列表走 {@code /api/portal/kbs} 按页取。 */
    @GetMapping("/api/portal/home")
    public ApiResponse<PortalHomeVO> home() {
        LoginUser user = currentUserResolver.resolve();
        return ApiResponse.ok(new PortalHomeVO(knowledgeBaseService.portalStats(), user));
    }

    /**
     * 门户知识库列表（滚动加载）：一次给一页，前端滚到底再要下一页。
     *
     * <p>只出已发布的库，与首页统计同源（{@code pagePublished} / {@code portalStats} 共用
     * {@code SharePublicationSupport.publishedExists} 这一份口径）。</p>
     *
     * @param access {@code public}（未加密）/ {@code private}（已加密）；空表示不筛
     */
    @GetMapping("/api/portal/kbs")
    public ApiResponse<PageResult<KbVO>> kbs(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(required = false) String access,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(knowledgeBaseService.pagePublished(keyword, sort, access, page, size));
    }

    /**
     * 知识库阅读数据：目录树 + 正文 + 大纲 + 元信息 + 上下篇。
     *
     * <p>组织段是必需的：slug 自 v2 起只在组织内唯一。访问闸门见 {@link #resolve}：
     * 已发布（加密的还要过口令）或本人可读，二者居其一。</p>
     */
    @GetMapping("/api/portal/kb/{org}/{slug}")
    public ApiResponse<PortalReadVO> read(@PathVariable String org, @PathVariable String slug,
                                          @RequestParam(required = false) String path,
                                          HttpServletRequest request) {
        LoginUser user = currentUserResolver.resolve();
        PortalTarget target = resolve(org, slug, user, request);
        if (target.needPassword()) {
            return ApiResponse.ok(PortalReadVO.needPassword(target.kb().getName()));
        }
        KnowledgeBase kb = target.kb();
        // 分享页与门户共用同一枚 cookie，因此同一发布分享只应算一次访问。这里只补登「本库并非由本人读权放行」的
        // 那次：有自身读权的成员（含 OWNER / 名单成员）打开自己的库不算一次分享访问；游客与无自身读权的成员
        // 走的是分享门，才算。未发布（无 share 行）自然不记。
        if (!target.byOwnRead()) {
            Share published = publicationSupport.find(kb.getId());
            if (published != null) {
                shareService.recordView(published, AccessLogFilter.clientIp(request), request.getHeader("User-Agent"));
            }
        }
        ReadView view = readerService.build(kb, ReaderService.ReadRequest.portal(org, slug, path));
        view.setLoggedIn(user != null);
        view.setOrgSlug(org);
        view.setCanManage(accessService.can(kb, user, KbAction.DOC_WRITE));
        return ApiResponse.ok(PortalReadVO.ok(view));
    }

    /**
     * 校验门户私有库的访问口令；通过后下发免密 Cookie（与分享页同一枚）。
     *
     * <p>已经能按身份读到的库（例如本人可读的私有库）直接放行，不逼人多输一次口令。</p>
     */
    @PostMapping("/api/portal/kb/{org}/{slug}/verify")
    public ApiResponse<Void> verify(@PathVariable String org, @PathVariable String slug,
                                    @RequestBody(required = false) VerifyRequest body,
                                    HttpServletRequest request, HttpServletResponse response) {
        LoginUser user = currentUserResolver.resolve();
        Tenant tenant = tenantService.findBySlug(org);
        if (tenant == null) {
            throw BizException.notFound("知识库不存在或未公开");
        }
        KnowledgeBase kb = knowledgeBaseService.requireBySlug(tenant.getId(), slug);
        if (user != null && accessService.canRead(kb, user)) {
            return ApiResponse.ok();
        }
        Share published = publicationSupport.find(kb.getId());
        if (published == null) {
            throw BizException.notFound("知识库不存在或未公开");
        }
        if (!published.encrypted()) {
            return ApiResponse.ok();
        }

        String limitKey = "portal:" + kb.getId() + ":" + AccessLogFilter.clientIp(request);
        if (!rateLimiter.tryAcquire(limitKey, PASSWORD_LIMIT, PASSWORD_WINDOW)) {
            throw new BizException(ErrorCode.SHARE_PWD_TOO_FREQUENT, "尝试过于频繁，请 60 秒后重试");
        }
        String password = body == null ? null : body.password();
        if (password != null && passwordEncoder.matches(password, published.getPasswordHash())) {
            rateLimiter.reset(limitKey);
            shareAccessSupport.grant(published, request, response);
            return ApiResponse.ok();
        }
        throw BizException.of(ErrorCode.UNAUTHORIZED, "密码错误，请重试");
    }

    /**
     * 门户图片 / 附件代理。
     *
     * <p>该地址会被渲染后的 Markdown 直接写进 {@code <img src>}，故保持在 {@code /kb/**} 下，
     * 不迁入 {@code /api}（浏览器直连时无法附加请求头，依赖 Cookie 桥接鉴权）。
     * 需要口令而尚未通过时按不存在处理，避免用资源地址绕过口令门。</p>
     */
    @GetMapping("/kb/{org}/{slug}/asset/**")
    public ResponseEntity<Resource> asset(@PathVariable String org, @PathVariable String slug,
                                          @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                                          HttpServletRequest request) {
        LoginUser user = currentUserResolver.resolve();
        PortalTarget target = resolve(org, slug, user, request);
        if (target.needPassword()) {
            throw BizException.notFound("知识库不存在或未公开");
        }
        KnowledgeBase kb = target.kb();

        String relative = PathEncoder.tailAfter(request.getRequestURI(), "/asset/");

        AssetService.Asset asset = assetService.load(vaultFileService.rootOf(kb.getStorageKey()), relative);
        return assetService.respond(asset, ifNoneMatch);
    }

    /**
     * 老链接消歧：{@code /kb/{旧slug}} 命中的可见库列表。
     *
     * <p>slug 已降级为组织内唯一，历史链接可能命中多个组织。判定走 {@code visibleBySlug} 的
     * 「已发布 ∪ 可读」：游客命中已发布的库，成员额外拿到自己有读权的库。唯一命中由
     * {@code LegacyKbRedirectController} 直接 301，只有零个或多个命中时前端才会用到这里。</p>
     */
    @GetMapping("/api/portal/locate")
    public ApiResponse<List<KbVO>> locate(@RequestParam String slug) {
        return ApiResponse.ok(knowledgeBaseService.visibleBySlug(slug, currentUserResolver.resolve()));
    }

    /**
     * 门户访问闸门：先组织、再库，两步都不产生读权。
     *
     * <p>放行顺序是「本人可读」优先于「已发布」：成员打开自己的私有库不该被要求输口令。
     * 都不过则：未发布 → 404（不确认存在性）；已发布但加密且未通过口令 → 需要口令。</p>
     *
     * <p>组织不存在与该组织下没有这个 slug 给出同一个 404（§2.5）。</p>
     */
    private PortalTarget resolve(String org, String slug, LoginUser user, HttpServletRequest request) {
        Tenant tenant = tenantService.findBySlug(org);
        if (tenant == null) {
            throw BizException.notFound("知识库不存在或未公开");
        }
        KnowledgeBase kb = knowledgeBaseService.requireBySlug(tenant.getId(), slug);
        if (user != null && accessService.canRead(kb, user)) {
            return new PortalTarget(kb, false, true);
        }
        Share published = publicationSupport.find(kb.getId());
        if (published == null) {
            throw BizException.notFound("知识库不存在或未公开");
        }
        return new PortalTarget(kb, !shareAccessSupport.isVerified(published, request), false);
    }

    /** 门户访问判定结果：{@code needPassword=true} 时只回库名不带正文；{@code byOwnRead=true} 表示由本人读权放行。 */
    private record PortalTarget(KnowledgeBase kb, boolean needPassword, boolean byOwnRead) {
    }

    /** 门户首页聚合响应。 */
    public record PortalHomeVO(PortalStatsVO stats, LoginUser user) {
    }

    /** 门户阅读响应：{@code state=password} 时仅带库名，前端据此渲染口令弹层。 */
    public record PortalReadVO(String state, String kbName, ReadView view) {

        static PortalReadVO needPassword(String kbName) {
            return new PortalReadVO("password", kbName, null);
        }

        static PortalReadVO ok(ReadView view) {
            return new PortalReadVO("ok", view.getKbName(), view);
        }
    }

    /** 口令校验请求体。 */
    public record VerifyRequest(String password) {
    }
}
