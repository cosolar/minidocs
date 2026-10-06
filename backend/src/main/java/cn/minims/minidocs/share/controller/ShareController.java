package cn.minims.minidocs.share.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.share.dto.ShareDtos.CreateRequest;
import cn.minims.minidocs.share.dto.ShareDtos.ShareVO;
import cn.minims.minidocs.share.dto.ShareDtos.UpdateRequest;
import cn.minims.minidocs.share.service.ShareService;
import cn.minims.minidocs.site.support.SiteBaseUrlResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织内分享管理（规范 §4.2）。
 *
 * <p>{@code shares} 表刻意没有 {@code tenant_id} 列（V3 契约）：组织归属由 {@code kb_id} 那一跳决定，
 * 少一份可以漂移的副本。因此这里每入口都先用 {@code TenantContext} 把库收敛到本组织，
 * 别的组织的 token 一律按「不存在」处理。</p>
 *
 * <p>匿名访问入口 {@code /api/share/{token}} 不在此列，见 {@link ShareApiController} —— token 自身
 * 就是寻址手段，不带组织段。</p>
 */
@Tag(name = "分享管理")
@RestController
@RequestMapping("/api/console/{org}/shares")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final SiteBaseUrlResolver siteBaseUrlResolver;

    @Operation(summary = "创建 / 更新分享")
    @PostMapping
    public ApiResponse<ShareVO> createOrUpdate(@PathVariable String org,
                                              @Valid @RequestBody CreateRequest request,
                                              HttpServletRequest servletRequest) {
        return ApiResponse.ok(shareService.createOrUpdate(TenantContext.requireId(), request,
                UserContext.require(), baseUrl(servletRequest)));
    }

    @Operation(summary = "本组织可见的分享列表")
    @GetMapping
    public ApiResponse<PageResult<ShareVO>> page(@PathVariable String org,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String scope,
                                                 HttpServletRequest servletRequest) {
        return ApiResponse.ok(shareService.page(TenantContext.requireId(), UserContext.require(),
                status, scope, page, size, baseUrl(servletRequest)));
    }

    @Operation(summary = "查询某目标的分享状态")
    @GetMapping("/info")
    public ApiResponse<ShareVO> info(@PathVariable String org,
                                     @RequestParam String kbSlug,
                                     @RequestParam(required = false) String docPath,
                                     HttpServletRequest servletRequest) {
        return ApiResponse.ok(shareService.info(TenantContext.requireId(), UserContext.require(),
                kbSlug, docPath, baseUrl(servletRequest)));
    }

    @Operation(summary = "修改密码 / 有效期")
    @PutMapping("/{token}")
    public ApiResponse<ShareVO> update(@PathVariable String org, @PathVariable String token,
                                       @Valid @RequestBody UpdateRequest request,
                                       HttpServletRequest servletRequest) {
        return ApiResponse.ok(shareService.update(TenantContext.requireId(), token, request,
                UserContext.require(), baseUrl(servletRequest)));
    }

    @Operation(summary = "撤销分享")
    @DeleteMapping("/{token}")
    public ApiResponse<Void> revoke(@PathVariable String org, @PathVariable String token) {
        shareService.revoke(TenantContext.requireId(), token, UserContext.require());
        return ApiResponse.ok();
    }

    /**
     * 分享链接的基址。
     *
     * <p>分享链接指向的是<b>页面</b>，而页面与接口未必同前缀：前端由 Nginx 伺服在站点根时，
     * 链接不能带 {@code /minidocs}（那是接口前缀，页面路由匹配不上，深链接会白屏）。
     * 取值优先级（站点设置页 &gt; {@code PAGE_BASE_URL} &gt; 按请求推导）见 {@link SiteBaseUrlResolver}。</p>
     */
    private String baseUrl(HttpServletRequest request) {
        return siteBaseUrlResolver.resolve(request);
    }
}
