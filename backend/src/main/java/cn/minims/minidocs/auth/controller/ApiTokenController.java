package cn.minims.minidocs.auth.controller;

import cn.minims.minidocs.auth.dto.ApiTokenDtos.CreateRequest;
import cn.minims.minidocs.auth.dto.ApiTokenDtos.CreatedVO;
import cn.minims.minidocs.auth.dto.ApiTokenDtos.TokenVO;
import cn.minims.minidocs.auth.service.ApiTokenService;
import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 第三方应用访问令牌（GitHub Personal Access Token 的做法）。
 *
 * <p>路径刻意不带组织段（{@code /api/tokens} 而不是 {@code /api/console/{org}/tokens}）：
 * 令牌属于「人」，不属于某个组织 —— 换组织不换令牌，这跟账号设置是同一层的事。</p>
 *
 * <p>这组接口<b>只接受登录态</b>，不接受令牌认证：令牌不能自己签发令牌，
 * 否则一枚被复制出去的令牌就能无限增发。</p>
 */
@Tag(name = "第三方应用授权")
@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenService apiTokenService;

    @Operation(summary = "我的访问令牌列表（不含令牌原文）")
    @GetMapping
    public ApiResponse<List<TokenVO>> list() {
        return ApiResponse.ok(apiTokenService.list(UserContext.requireUserId()));
    }

    @Operation(summary = "签发访问令牌（令牌原文只回吐这一次）")
    @PostMapping
    public ApiResponse<CreatedVO> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(apiTokenService.create(UserContext.requireUserId(), request));
    }

    @Operation(summary = "吊销访问令牌")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        apiTokenService.revoke(UserContext.requireUserId(), id);
        return ApiResponse.ok();
    }
}
