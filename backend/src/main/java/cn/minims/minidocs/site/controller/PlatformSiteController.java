package cn.minims.minidocs.site.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.service.SiteConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 站点配置的写入侧：{@code /api/platform/**}，门槛是平台管理员（{@code AdminInterceptor}）。
 *
 * <p>站点品牌是全部署一份的东西，不属于任何组织，所以不能挂在 {@code /api/console/{org}/**} 下 ——
 * 那条通道的门是「组织成员」，会让任一组织的管理员改掉别人的门户名称。</p>
 */
@Tag(name = "站点配置（平台）")
@RestController
@RequestMapping("/api/platform/site")
@RequiredArgsConstructor
public class PlatformSiteController {

    private final SiteConfigService siteConfigService;

    @Operation(summary = "保存站点配置")
    @PutMapping
    public ApiResponse<SiteConfigVO> update(@RequestBody Map<String, Object> patch) {
        return ApiResponse.ok(siteConfigService.update(patch));
    }

    @Operation(summary = "上传站点 Logo")
    @PostMapping("/logo")
    public ApiResponse<SiteConfigVO> uploadLogo(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(siteConfigService.saveLogo(file));
    }

    @Operation(summary = "移除站点 Logo")
    @DeleteMapping("/logo")
    public ApiResponse<SiteConfigVO> removeLogo() {
        return ApiResponse.ok(siteConfigService.removeLogo());
    }
}
