package cn.minims.minidocs.kb.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.doc.service.DocService;
import cn.minims.minidocs.kb.dto.KbDtos.CreateRequest;
import cn.minims.minidocs.kb.dto.KbDtos.GitStatusVO;
import cn.minims.minidocs.kb.dto.KbDtos.GitSyncRequest;
import cn.minims.minidocs.kb.dto.KbDtos.GitSyncVO;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.dto.KbDtos.MaintainScopeRequest;
import cn.minims.minidocs.kb.dto.KbDtos.RosterEntryVO;
import cn.minims.minidocs.kb.dto.KbDtos.RosterGrantRequest;
import cn.minims.minidocs.kb.dto.KbDtos.UpdateRequest;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KbMemberService;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 组织内知识库管理（规范 §4.2）。
 *
 * <p>路径参数是 {@code {org}/{slug}} 而不是 {@code {kbId}}：与磁盘目录
 * （{@code vaults/o{tenantId}/{slug}}）和阅读页 URL 同一套标识，链接抄来抄去不会错位。
 * {@code {org}} 由 {@code TenantInterceptor} 判过成员身份，这里只按组织取那一条 slug。</p>
 *
 * <p>slug 定位只解决「找得到」，读写治理仍由 {@code AccessService.requireKb} 按 id 裁决 ——
 * 本组织内不可见的 private 库在这里同样拿 404。</p>
 */
@Tag(name = "组织知识库")
@RestController
@RequestMapping("/api/console/{org}/kbs")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbMemberService kbMemberService;
    private final DocService docService;

    @Operation(summary = "组织内可见库（分页 + 筛选）")
    @GetMapping
    public ApiResponse<PageResult<KbVO>> page(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String sort,
                                              @RequestParam(required = false) String visibility,
                                              @RequestParam(required = false) Boolean favored,
                                              @RequestParam(required = false) Boolean mine) {
        return ApiResponse.ok(knowledgeBaseService.page(TenantContext.requireId(), UserContext.require(),
                keyword, sort, visibility, favored, mine, page, size));
    }

    @Operation(summary = "新建知识库")
    @PostMapping
    public ApiResponse<KbVO> create(@PathVariable String org, @Valid @RequestBody CreateRequest request) {
        // 建在哪个组织只认路径（§4.2）：正文里再带一份会造出两可请求
        return ApiResponse.ok(knowledgeBaseService.create(org, request, UserContext.require()));
    }

    @Operation(summary = "知识库详情（含 myPermissions[]）")
    @GetMapping("/{slug}")
    public ApiResponse<KbVO> detail(@PathVariable String org, @PathVariable String slug) {
        return ApiResponse.ok(knowledgeBaseService.detail(locate(slug), UserContext.get()));
    }

    @Operation(summary = "修改知识库")
    @PutMapping("/{slug}")
    public ApiResponse<KbVO> update(@PathVariable String org, @PathVariable String slug,
                                    @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(knowledgeBaseService.update(locate(slug), request, UserContext.require()));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{slug}")
    public ApiResponse<Void> delete(@PathVariable String org, @PathVariable String slug) {
        knowledgeBaseService.deleteKnowledgeBase(locate(slug), UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "云端库工作副本状态（改动数 / 领先落后 / HEAD）")
    @GetMapping("/{slug}/git")
    public ApiResponse<GitStatusVO> gitStatus(@PathVariable String org, @PathVariable String slug) {
        return ApiResponse.ok(knowledgeBaseService.gitStatus(locate(slug), UserContext.get()));
    }

    @Operation(summary = "拉取远程更新到工作副本（云端库）")
    @PostMapping("/{slug}/git/pull")
    public ApiResponse<GitSyncVO> gitPull(@PathVariable String org, @PathVariable String slug) {
        return ApiResponse.ok(knowledgeBaseService.gitPull(locate(slug), UserContext.require()));
    }

    @Operation(summary = "提交并推送工作副本的改动（云端库）")
    @PostMapping("/{slug}/git/commit")
    public ApiResponse<GitSyncVO> gitCommit(@PathVariable String org, @PathVariable String slug,
                                            @RequestBody(required = false) GitSyncRequest request) {
        return ApiResponse.ok(knowledgeBaseService.gitCommitPush(locate(slug), request, UserContext.require()));
    }

    @Operation(summary = "维护名单")
    @GetMapping("/{slug}/members")
    public ApiResponse<List<RosterEntryVO>> members(@PathVariable String org, @PathVariable String slug) {
        return ApiResponse.ok(kbMemberService.list(locate(slug), UserContext.require()));
    }

    @Operation(summary = "授权 / 改角色 / 撤销维护名单（role 传 null 即撤销）")
    @PutMapping("/{slug}/members")
    public ApiResponse<RosterEntryVO> grantMember(@PathVariable String org, @PathVariable String slug,
                                                  @Valid @RequestBody RosterGrantRequest request) {
        return ApiResponse.ok(kbMemberService.grant(locate(slug), request, UserContext.require()));
    }

    @Operation(summary = "改维护档位（owner_only / members / org_all）")
    @PutMapping("/{slug}/maintain")
    public ApiResponse<Map<String, String>> updateMaintain(@PathVariable String org, @PathVariable String slug,
                                                           @Valid @RequestBody MaintainScopeRequest request) {
        String scope = kbMemberService.updateMaintainScope(locate(slug), request.maintainScope(),
                UserContext.require());
        return ApiResponse.ok(Map.of("maintainScope", scope));
    }

    @Operation(summary = "上传封面图")
    @PostMapping("/{slug}/cover")
    public ApiResponse<Map<String, String>> uploadCover(@PathVariable String org, @PathVariable String slug,
                                                        @RequestParam("file") MultipartFile file) {
        String path = docService.uploadCover(locate(slug), file, UserContext.require());
        return ApiResponse.ok(Map.of("coverUrl", path));
    }

    @Operation(summary = "收藏知识库")
    @PostMapping("/{slug}/favorite")
    public ApiResponse<Map<String, Boolean>> favorite(@PathVariable String org, @PathVariable String slug) {
        boolean favored = knowledgeBaseService.switchFavorite(locate(slug), UserContext.require(), true);
        return ApiResponse.ok(Map.of("favored", favored));
    }

    @Operation(summary = "取消收藏")
    @DeleteMapping("/{slug}/favorite")
    public ApiResponse<Map<String, Boolean>> unfavorite(@PathVariable String org, @PathVariable String slug) {
        boolean favored = knowledgeBaseService.switchFavorite(locate(slug), UserContext.require(), false);
        return ApiResponse.ok(Map.of("favored", favored));
    }

    /** {@code {org}} 只用于成员门与定位，取 id 走组织上下文，不在每个方法里重复解析。 */
    private Long locate(String slug) {
        return knowledgeBaseService.requireBySlug(TenantContext.requireId(), slug).getId();
    }
}
