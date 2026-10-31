package cn.minims.minidocs.theme.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.theme.dto.ThemeDtos.ThemePackVO;
import cn.minims.minidocs.theme.service.ThemeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 主题包管理：{@code /api/platform/**} 之下，门槛是平台管理员。
 *
 * <p>主题影响整个部署的阅读版面，不属于某个组织，因此不能放进
 * {@code /api/console/{org}/**}（那条通道是组织成员门）。</p>
 */
@Tag(name = "阅读主题（平台）")
@RestController
@RequestMapping("/api/platform/site/themes")
@RequiredArgsConstructor
public class PlatformThemeController {

    private final ThemeService themeService;

    @Operation(summary = "列出已安装主题（含内置标记）")
    @GetMapping
    public ApiResponse<List<ThemePackVO>> list() {
        return ApiResponse.ok(themeService.list());
    }

    @Operation(summary = "安装或覆盖一个主题包")
    @PostMapping
    public ApiResponse<ThemePackVO> install(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(themeService.install(file));
    }

    @Operation(summary = "卸载主题")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> uninstall(@PathVariable String id) {
        themeService.uninstall(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "恢复内置主题")
    @PostMapping("/reseed")
    public ApiResponse<List<ThemePackVO>> reseed() {
        return ApiResponse.ok(themeService.reseed());
    }
}
