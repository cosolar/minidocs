package cn.minims.minidocs.share.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.reader.support.CurrentUserResolver;
import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.support.RateLimiter;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.common.web.AccessLogFilter;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.markdown.model.MarkdownModels.Variant;
import cn.minims.minidocs.reader.model.NavMenuItem;
import cn.minims.minidocs.reader.model.ReadView;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.reader.service.ReaderService;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.share.service.ShareService;
import cn.minims.minidocs.share.support.ShareAccessSupport;
import cn.minims.minidocs.share.support.ShareTokenUtil;
import cn.minims.minidocs.site.support.SiteBaseUrlResolver;
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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部分享（用户侧）API：口令校验 / 取阅读数据 / 资源代理。
 *
 * <p>页面由 {@code front/} 的用户侧 SPA 渲染，这里只输出 JSON 与二进制资源。
 * 状态语义通过 HTTP 状态码表达：</p>
 *
 * <ul>
 *   <li>{@code 200} — 可读（{@code state=ok}）或需要口令（{@code state=password}）</li>
 *   <li>{@code 401} — 口令错误</li>
 *   <li>{@code 404} — 分享不存在 / 已撤销 / 关联内容已删除</li>
 *   <li>{@code 410} — 分享已过期</li>
 *   <li>{@code 429} — 口令尝试过于频繁</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ShareApiController {

    private static final int PASSWORD_LIMIT = 10;
    private static final Duration PASSWORD_WINDOW = Duration.ofSeconds(60);

    private final ShareService shareService;
    private final SiteBaseUrlResolver siteBaseUrlResolver;
    private final KnowledgeBaseService knowledgeBaseService;
    private final VaultFileService vaultFileService;
    private final AssetService assetService;
    private final AccessService accessService;
    private final CurrentUserResolver currentUserResolver;
    private final ReaderService readerService;
    private final ShareAccessSupport accessSupport;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;

    /** 取分享阅读数据；需要口令且未通过时返回 {@code state=password}。 */
    @GetMapping("/api/share/{token}")
    public ApiResponse<ShareReadVO> read(@PathVariable String token,
                                        @RequestParam(required = false) String path,
                                        HttpServletRequest request) {
        Share share = requireLive(token);

        KnowledgeBase kb = knowledgeBaseService.getById(share.getKbId());
        if (kb == null) {
            throw BizException.notFound("分享不存在或已被撤销");
        }
        /*
         * 门户曝光范围收窄到「登录用户」或「维护者」时，匿名访客即使拿到链接也读不到。
         *
         * <p>不静默跳登录、也不报 403：返回 login 状态让前端引导，理由与「口令」那行相同 ——
         * 读者需要知道下一步做什么，而 403 在浏览器里表现为一句英文报错。</p>
         *
         * <p>校验放在口令判定之前：受众是更外层的闸，先过它再问密码。反过来会让一个不该知道
         * 这个库存在的访客，先看到「需要口令」—— 那本身就泄露了库里有东西。</p>
         */
        String portalScope = share.getPortalScope() == null ? Share.PORTAL_ALL : share.getPortalScope();
        if (!Share.PORTAL_ALL.equals(portalScope)) {
            LoginUser viewer = currentUserResolver.resolve();
            boolean allowed = viewer != null && (Share.PORTAL_MEMBER.equals(portalScope)
                    || accessService.can(kb, viewer, KbAction.KB_EDIT_META));
            if (!allowed) {
                return ApiResponse.ok(ShareReadVO.needLogin(token, kb.getName()));
            }
        }

        if (!accessSupport.isVerified(share, request)) {
            return ApiResponse.ok(ShareReadVO.needPassword(token, kb.getName()));
        }


        boolean singleDoc = !share.kbScope();
        if (singleDoc) {
            Path root = vaultFileService.rootOf(kb.getStorageKey());
            if (share.getDocPath() == null || !vaultFileService.exists(root, share.getDocPath())) {
                throw BizException.notFound("内容已删除");
            }
            // 单篇分享不走目录树（整库分享那份走 ReaderService 的已过滤文档列表），
            // 所以作者把文档隐藏之后，这条早先发出的链接仍能打开 —— 隐藏等于没生效。
            // 这里与「已删除」同一种说法：读者无从分辨，也少一条存在性侧信道。
            if (vaultFileService.isHiddenPath(root, share.getDocPath())) {
                throw BizException.notFound("内容已删除");
            }
        }

        /*
         * 先记一次访问再组装视图，但浏览量只在「新会话」那次才 +1（见 ShareService#recordView），
         * 所以这里要按返回值补：读者切文档时这个值是 0，页面上看到的数字才不会跟着跳。
         */
        boolean counted = shareService.recordView(share, AccessLogFilter.clientIp(request),
                request.getHeader("User-Agent"));

        ReaderService.ReadRequest readRequest = new ReaderService.ReadRequest("share",
                AppPaths.of("/share/" + token + "/asset/"), AppPaths.of("/share/" + token + "?path="),
                singleDoc ? Variant.DOC : Variant.KB, singleDoc, !singleDoc,
                singleDoc ? share.getDocPath() : null, path,
                token, expiresText(share),
                (long) (share.getViews() == null ? 0 : share.getViews()) + (counted ? 1 : 0));
        ReadView view = readerService.build(kb, readRequest);
        if (view.isEmpty()) {
            throw BizException.notFound("内容已删除");
        }
        view.setMenu(resolveMenu(share, view));
        /*
         * 顶栏「分享」按钮复制的绝对地址与后台管理台那条 URL 同源，都出自 SiteBaseUrlResolver
         */
        view.setSiteBase(siteBaseUrlResolver.resolve(request));
        /*
         * 开源仓库入口。
         *
         * <p>与后台那条「分享信息」接口同源（同一个 Share 行），但这里多一层判断：
         * 只有 showRepo 为真且地址非空才下发。分享页是匿名可读的，把一个作者只想
         * 自己看见的地址泄给所有访客是最容易被忽略的一种越界。</p>
         */
        if (Integer.valueOf(1).equals(share.getShowRepo()) && share.getRepoUrl() != null) {
            view.setRepoUrl(share.getRepoUrl());
            view.setShowRepo(true);
        }

        return ApiResponse.ok(ShareReadVO.ok(view));
    }

    /** 校验访问口令；通过后下发免密 Cookie。 */
    @PostMapping("/api/share/{token}/verify")
    public ApiResponse<Void> verify(@PathVariable String token,
                                    @RequestBody(required = false) VerifyRequest body,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        String ip = AccessLogFilter.clientIp(request);
        String limitKey = "share:" + token + ":" + ip;
        if (!rateLimiter.tryAcquire(limitKey, PASSWORD_LIMIT, PASSWORD_WINDOW)) {
            throw new BizException(ErrorCode.SHARE_PWD_TOO_FREQUENT, "尝试过于频繁，请 60 秒后重试");
        }

        Share share = requireLive(token);
        String password = body == null ? null : body.password();
        if (!share.encrypted() || (password != null && passwordEncoder.matches(password, share.getPasswordHash()))) {
            rateLimiter.reset(limitKey);
            accessSupport.grant(share, request, response);
            return ApiResponse.ok();
        }
        throw BizException.of(ErrorCode.UNAUTHORIZED, "密码错误，请重试");
    }

    /**
     * 分享资源代理。
     *
     * <p>地址会被渲染后的 Markdown 直接引用，故保留在 {@code /share/**} 下，
     * 鉴权依赖分享访问 Cookie。</p>
     */
    @GetMapping("/share/{token}/asset/**")
    public ResponseEntity<Resource> asset(@PathVariable String token,
                                          @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                                          HttpServletRequest request) {
        Share share = shareService.findByToken(ShareTokenUtil.isValid(token) ? token : null);
        if (share == null || share.revoked() || share.invalid() || share.expired()) {
            throw BizException.notFound("分享不存在或已被撤销");
        }
        if (!accessSupport.isVerified(share, request)) {
            throw BizException.notFound("分享不存在或已被撤销");
        }
        KnowledgeBase kb = knowledgeBaseService.getById(share.getKbId());
        if (kb == null) {
            throw BizException.notFound("分享不存在或已被撤销");
        }

        String relative = PathEncoder.tailAfter(request.getRequestURI(), "/asset/");

        AssetService.Asset asset = assetService.load(vaultFileService.rootOf(kb.getStorageKey()), relative);
        return assetService.respond(asset, ifNoneMatch);
    }

    /** 分享存活校验：撤销 / 内容缺失 → 404，过期 → 410。 */
    private Share requireLive(String token) {
        Share share = shareService.findByToken(ShareTokenUtil.isValid(token) ? token : null);
        if (share == null || share.revoked()) {
            throw BizException.notFound("分享不存在或已被撤销");
        }
        if (share.invalid()) {
            throw BizException.notFound("内容已删除");
        }
        if (share.expired()) {
            throw new BizException(ErrorCode.GONE, "分享已过期");
        }
        return share;
    }

    private String expiresText(Share share) {
        if (share.getExpiresAt() == null) {
            return "永久有效";
        }
        return "有效期至 " + TimeUtil.display(share.getExpiresAt());
    }

    /**
     * 导航菜单下发前的最后一关：只留下树里确实存在的条目，并按树上的名字补显示名。
     *
     * <p>存库时已经校验过一次，这里再校验一次，是因为读侧面对的是「保存之后又变了」的世界 ——
     * 配置是分享者按下按钮那一刻的快照，之后目录可能被删或改名，直接照发只会给读者一个
     * 点下去没反应的入口。</p>
     */
    private List<NavMenuItem> resolveMenu(Share share, ReadView view) {
        List<NavMenuItem> configured = JsonUtil.readList(share.getMenuConfig(), NavMenuItem.class);
        if (configured.isEmpty() || view.getTree().isEmpty()) {
            return List.of();
        }
        Map<String, DocNode> index = new HashMap<>();
        indexTree(view.getTree(), index);

        List<NavMenuItem> menu = new ArrayList<>();
        for (NavMenuItem item : configured) {
            DocNode node = index.get(item.path());
            if (node == null) {
                continue;
            }
            String type = "dir".equals(node.type()) ? NavMenuItem.TYPE_DIR : NavMenuItem.TYPE_DOC;
               // name 保持推导原名：顶栏渲染时用 alias || name 合成生效名。
               // 在这里合并会让配置器丢失「原始名」这一列，作者就看不到自己在给哪一项取别名。
             menu.add(new NavMenuItem(type, item.path(), FileNameUtil.stripMarkdownExt(node.name()),
                    item.alias(), item.icon()));
                    }
        return menu;
    }

    private void indexTree(List<DocNode> nodes, Map<String, DocNode> index) {
        for (DocNode node : nodes) {
            index.put(node.path(), node);
            if (node.children() != null && !node.children().isEmpty()) {
                indexTree(node.children(), index);
            }
        }
    }

    /** 口令校验请求体。 */
    public record VerifyRequest(String password) {
    }

    /** 分享阅读响应：{@code state} 为 {@code ok} 时 {@code view} 有值，为 {@code password} 时仅返回库名。 */
    public record ShareReadVO(String state, String token, String kbName, ReadView view) {

        /**
         * 需要登录才能读。
         *
         * <p>与 {@code password} 分开而不是合并：口令是「你知道的那串字符」，登录是「你在这个平台
         * 有账号」。两者的引导去处不同（输密码 vs 跳登录页），合成一个状态前端就得自己猜
         * 该弹框还是该跳转。</p>
         */
        static ShareReadVO needLogin(String token, String kbName) {
            return new ShareReadVO("login", token, kbName, null);
        }

        static ShareReadVO needPassword(String token, String kbName) {
            return new ShareReadVO("password", token, kbName, null);
        }

        static ShareReadVO ok(ReadView view) {
            return new ShareReadVO("ok", view.getShareToken(), view.getKbName(), view);
        }
    }
}
