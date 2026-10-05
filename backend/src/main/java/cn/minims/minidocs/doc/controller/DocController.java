package cn.minims.minidocs.doc.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.doc.dto.DocDtos.CreateDirRequest;
import cn.minims.minidocs.doc.dto.DocDtos.CreateDocRequest;
import cn.minims.minidocs.doc.dto.DocDtos.DocContentVO;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.doc.dto.DocDtos.DownloadPayload;
import cn.minims.minidocs.doc.dto.DocDtos.ImportResult;
import cn.minims.minidocs.doc.dto.DocDtos.LockVO;
import cn.minims.minidocs.doc.dto.DocDtos.MoveRequest;
import cn.minims.minidocs.doc.dto.DocDtos.PathChangeVO;
import cn.minims.minidocs.doc.dto.DocDtos.RenameRequest;
import cn.minims.minidocs.doc.dto.DocDtos.RenderVO;
import cn.minims.minidocs.doc.dto.DocDtos.ReorderRequest;
import cn.minims.minidocs.doc.dto.DocDtos.SaveDocRequest;
import cn.minims.minidocs.doc.service.DocLockService;
import cn.minims.minidocs.doc.service.DocService;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderContext;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderResult;
import cn.minims.minidocs.markdown.model.MarkdownModels.Variant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 组织内文档接口（规范 §4.2 / §4.3）：{@code /api/console/{org}/kbs/{slug}/**}。
 *
 * <p>定位一律走「组织上下文 + 库 slug」，与阅读页 URL 和磁盘目录
 * （{@code vaults/o{tenantId}/{kbSlug}}）同标识。成员门已由 {@code TenantInterceptor} 判过，
 * 这里的 {@link #locate} 只回答「这个组织下这个 slug 是哪条记录」；库在该用户是否可读、
 * 可写仍由 {@code DocService} 内部的 {@code AccessService.requireKb} 裁决。</p>
 */
@Tag(name = "文档管理")
@RestController
@RequestMapping("/api/console/{org}/kbs/{slug}")
@RequiredArgsConstructor
public class DocController {

    private final DocService docService;
    private final DocLockService docLockService;
    private final KnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "目录树")
    @GetMapping("/tree")
    public ApiResponse<List<DocNode>> tree(@PathVariable String slug) {
        return ApiResponse.ok(docService.tree(locate(slug), UserContext.require()));
    }

    @Operation(summary = "读取文档原文（响应体带 size / modifiedAt 作为保存基线）")
    @GetMapping("/doc")
    public ApiResponse<DocContentVO> read(@PathVariable String slug, @RequestParam String path) {
        return ApiResponse.ok(docService.read(locate(slug), path, UserContext.require()));
    }

    @Operation(summary = "新建文档")
    @PostMapping("/doc")
    public ApiResponse<PathChangeVO> create(@PathVariable String slug, @Valid @RequestBody CreateDocRequest request) {
        return ApiResponse.ok(docService.createDoc(locate(slug), request.path(), request.content(),
                UserContext.require()));
    }

    @Operation(summary = "保存文档（无有效锁 → 403；基线不符 → 412）")
    @PutMapping("/doc")
    public ApiResponse<PathChangeVO> save(@PathVariable String slug, @Valid @RequestBody SaveDocRequest request) {
        return ApiResponse.ok(docService.saveDoc(locate(slug), request.path(), request.content(),
                request.baseSize(), request.baseMtime(), UserContext.require()));
    }

    @Operation(summary = "获取 / 续期编辑锁（幂等；他人持有 → 409 + 持有者信息）")
    @PostMapping("/lock")
    public ApiResponse<LockVO> acquireLock(@PathVariable String slug, @RequestParam String path) {
        return ApiResponse.ok(docLockService.acquire(locate(slug), path, UserContext.require()));
    }

    @Operation(summary = "释放编辑锁（持有者本人，或知识库管理员强制解锁）")
    @DeleteMapping("/lock")
    public ApiResponse<Void> releaseLock(@PathVariable String slug, @RequestParam String path) {
        docLockService.release(locate(slug), path, UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "编辑锁状态（编辑器轮询，锁空出来即恢复可写）")
    @GetMapping("/lock")
    public ApiResponse<LockVO> lockState(@PathVariable String slug, @RequestParam String path) {
        return ApiResponse.ok(docLockService.probe(locate(slug), path, UserContext.require()));
    }

    @Operation(summary = "删除文档或目录")
    @DeleteMapping("/doc")
    public ApiResponse<Void> delete(@PathVariable String slug, @RequestParam String path) {
        docService.delete(locate(slug), path, UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "重命名")
    @PostMapping("/doc/rename")
    public ApiResponse<PathChangeVO> rename(@PathVariable String slug, @Valid @RequestBody RenameRequest request) {
        return ApiResponse.ok(docService.rename(locate(slug), request.path(), request.name(),
                UserContext.require()));
    }

    @Operation(summary = "移动")
    @PostMapping("/doc/move")
    public ApiResponse<PathChangeVO> move(@PathVariable String slug, @Valid @RequestBody MoveRequest request) {
        return ApiResponse.ok(docService.move(locate(slug), request.path(), request.targetDir(),
                UserContext.require()));
    }

    @Operation(summary = "调整同目录内顺序")
    @PostMapping("/doc/reorder")
    public ApiResponse<Void> reorder(@PathVariable String slug, @Valid @RequestBody ReorderRequest request) {
        docService.reorder(locate(slug), request.path(), request.targetPath(), request.position(),
                UserContext.require());
        return ApiResponse.ok();
    }

    @Operation(summary = "新建目录")
    @PostMapping("/dir")
    public ApiResponse<PathChangeVO> createDir(@PathVariable String slug,
                                               @Valid @RequestBody CreateDirRequest request) {
        return ApiResponse.ok(docService.createDirectory(locate(slug), request.path(), UserContext.require()));
    }

    @Operation(summary = "下载 Markdown 原文")
    @GetMapping("/doc/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String slug,
                                                                        @RequestParam String path) {
        DownloadPayload payload = docService.download(locate(slug), path, UserContext.require());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(payload.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(payload.resource());
    }

    @Operation(summary = "上传图片")
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> upload(@PathVariable String slug,
                                                   @RequestParam("file") MultipartFile file) {
        String path = docService.uploadImage(locate(slug), file, UserContext.require());
        return ApiResponse.ok(Map.of("path", path));
    }

    @Operation(summary = "导入 Markdown / ZIP")
    @PostMapping("/import")
    public ApiResponse<ImportResult> importFiles(@PathVariable String slug,
                                                 @RequestParam(value = "targetDir", required = false) String targetDir,
                                                 @RequestParam(value = "paths", required = false) String paths,
                                                 @RequestParam("files") MultipartFile[] files) {
        return ApiResponse.ok(docService.importFiles(locate(slug), targetDir, files, paths, UserContext.require()));
    }

    @Operation(summary = "渲染 Markdown（HTML + 大纲 + frontmatter）")
    @GetMapping("/render")
    public ResponseEntity<ApiResponse<RenderVO>> render(
            @PathVariable String org, @PathVariable String slug,
            @RequestParam String path,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        LoginUser user = UserContext.require();
        Long kbId = locate(slug);
        // 编辑器预览里的两类链接都留在工作区上下文：图片走同前缀资源代理，站内 .md 链接回工作区。
        // 绝对路径要带上下文前缀，否则浏览器会解析到 /minidocs 之外（见 AppPaths）。
        RenderContext context = RenderContext.of(
                AppPaths.of("/api/console/" + org + "/kbs/" + slug + "/asset/"),
                AppPaths.of("/console/" + org + "/kbs/" + slug + "?path="), false);
        RenderResult result = docService.render(kbId, path, user, Variant.KB, context);
        if (result.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(result.etag()).build();
        }
        String rawContent = docService.read(kbId, path, user).content();
        RenderVO vo = new RenderVO(result.html(), result.outline(), result.frontmatter(),
                result.meta(), result.etag(), rawContent);
        return ResponseEntity.ok().eTag(result.etag()).body(ApiResponse.ok(vo));
    }

    /** slug → 库 id：只在本组织内查找，跨组织同名 slug 不会命中（§4.2）。 */
    private Long locate(String slug) {
        return knowledgeBaseService.requireBySlug(TenantContext.requireId(), slug).getId();
    }
}
