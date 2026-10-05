package cn.minims.minidocs.user.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.user.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "个人设置")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @Operation(summary = "读取偏好")
    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(settingsService.getSettings(UserContext.requireUserId()));
    }

    @Operation(summary = "保存偏好（浅合并）")
    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> patch) {
        return ApiResponse.ok(settingsService.mergeSettings(UserContext.requireUserId(), patch));
    }
}
