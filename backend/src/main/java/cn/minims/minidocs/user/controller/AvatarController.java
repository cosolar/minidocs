package cn.minims.minidocs.user.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import cn.minims.minidocs.user.service.AvatarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 账号头像：上传 / 移除 / 读取。
 *
 * <p>上传与移除走登录态（{@code /api/avatar}）；读取走 {@code /api/avatars/{fileName}}，
 * 已在 {@code WebMvcConfig} 中从鉴权拦截器放行 —— 头像要能出现在门户、分享页等匿名可见的
 * 页面上，浏览器直连 {@code <img src>} 也带不上自定义请求头。</p>
 */
@Tag(name = "账号头像")
@RestController
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatarService;
    private final AssetService assetService;

    @Operation(summary = "上传头像")
    @PostMapping("/api/avatar")
    public ApiResponse<UserVO> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(avatarService.save(UserContext.requireUserId(), file));
    }

    @Operation(summary = "移除头像")
    @DeleteMapping("/api/avatar")
    public ApiResponse<UserVO> remove() {
        return ApiResponse.ok(avatarService.remove(UserContext.requireUserId()));
    }

    @Operation(summary = "读取头像")
    @GetMapping("/api/avatars/{fileName}")
    public ResponseEntity<Resource> avatar(@PathVariable String fileName,
                                           @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        AssetService.Asset asset = avatarService.load(fileName);
        return assetService.respond(asset, ifNoneMatch);
    }
}
