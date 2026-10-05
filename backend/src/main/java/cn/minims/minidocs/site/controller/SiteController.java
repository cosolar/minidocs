package cn.minims.minidocs.site.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.service.SiteConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点信息的公开读取。
 *
 * <p>门户首页、阅读页、分享页都要拿品牌名与 Logo，而分享页对匿名访客开放，因此这一组不设门槛。
 * 写入侧在 {@code /api/platform/site}（平台管理员），两边分开 —— 读是「谁都能看」，写是「只有超管能改」。</p>
 */
@Tag(name = "站点配置")
@RestController
@RequiredArgsConstructor
public class SiteController {

    private final SiteConfigService siteConfigService;
    private final AssetService assetService;

    @Operation(summary = "读取站点配置")
    @GetMapping("/api/portal/site")
    public ApiResponse<SiteConfigVO> get() {
        return ApiResponse.ok(siteConfigService.get());
    }

    /**
     * Logo 图片。
     *
     * <p>与头像同理：地址会被直接写进 {@code <img src>}，浏览器直连带不上自定义请求头，
     * 因此必须在 {@code WebMvcConfig} 的鉴权拦截器里放行。</p>
     */
    @Operation(summary = "读取站点 Logo")
    @GetMapping("/api/site-logos/{fileName}")
    public ResponseEntity<Resource> logo(@PathVariable String fileName,
                                         @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        AssetService.Asset asset = siteConfigService.loadLogo(fileName);
        return assetService.respond(asset, ifNoneMatch);
    }
}
