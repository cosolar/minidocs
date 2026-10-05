package cn.minims.minidocs.kb.controller;

import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.reader.service.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编辑器预览用的图片代理（按读轴放行：public 库允许匿名，其余由 AccessService 裁决）。
 *
 * <p>地址与文档接口同前缀（{@code /api/console/{org}/kbs/{slug}/asset/**}），渲染出来的 {@code <img src>}
 * 因此与所在库共用一套标识；浏览器直连图片带不上请求头，靠 Cookie 桥接识别登录态。</p>
 */
@Tag(name = "知识库资源")
@RestController
@RequiredArgsConstructor
public class KbAssetController {

    private final AccessService accessService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final VaultFileService vaultFileService;
    private final AssetService assetService;

    @Operation(summary = "图片资源代理")
    @GetMapping("/api/console/{org}/kbs/{slug}/asset/**")
    public ResponseEntity<Resource> asset(@PathVariable String org, @PathVariable String slug,
                                          @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                                          HttpServletRequest request) {
        KnowledgeBase kb = knowledgeBaseService.requireBySlug(TenantContext.requireId(), slug);
        accessService.requireKb(kb.getId(), KbAction.DOC_READ, UserContext.get());
        String relative = PathEncoder.tailAfter(request.getRequestURI(), "/asset/");
        AssetService.Asset asset = assetService.load(vaultFileService.rootOf(kb.getStorageKey()), relative);
        return assetService.respond(asset, ifNoneMatch);
    }
}
