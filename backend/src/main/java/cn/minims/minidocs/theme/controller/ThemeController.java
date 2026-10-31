package cn.minims.minidocs.theme.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.theme.dto.ThemeDtos.ThemePackVO;
import cn.minims.minidocs.theme.service.ThemeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 已安装阅读主题的公开读取。
 *
 * <p>分享页对匿名访客开放，读者要在这里挑主题，因此与站点品牌同一条公开通道
 * （{@code /api/portal/**}，见 {@code WebMvcConfig} 的免鉴权名单）。写入侧在
 * {@code /api/platform/site/themes}，只有平台管理员能改。</p>
 */
@Tag(name = "阅读主题")
@RestController
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeService themeService;

    @Operation(summary = "列出已安装的阅读主题")
    @GetMapping("/api/portal/themes")
    public ApiResponse<List<ThemePackVO>> list() {
        return ApiResponse.ok(themeService.list());
    }
}
