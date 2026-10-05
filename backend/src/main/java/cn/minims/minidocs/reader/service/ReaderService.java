package cn.minims.minidocs.reader.service;

import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.PathEncoder;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.markdown.MarkdownService;
import cn.minims.minidocs.markdown.model.MarkdownModels.DocMeta;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderContext;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderResult;
import cn.minims.minidocs.markdown.model.MarkdownModels.Variant;
import cn.minims.minidocs.reader.model.ReadView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

import static cn.minims.minidocs.kb.dto.KbDtos.splitTags;

/**
 * 阅读渲染服务：一次组装「树 + 正文 + 大纲 + 元信息」，门户与分享复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReaderService {

    private final VaultFileService vaultFileService;
    private final MarkdownService markdownService;

    /**
     * 阅读页渲染参数。
     *
     * @param fixedDocPath 固定文档（单篇分享），为 null 时按 requestedPath / 首篇解析
     */
    public record ReadRequest(String mode, String assetPrefix, String docLinkPrefix, Variant variant,
                              boolean singleDoc, boolean includeTree, String fixedDocPath, String requestedPath,
                              String shareToken, String expiresText, Long views) {

        /**
         * 门户阅读链接。
         *
         * <p>两个前缀都必须带组织段：slug 只在组织内唯一（§4.2），而 {@code assetPrefix} 会被写进
         * 渲染后的 {@code <img src>}，一旦与资源路由不一致，图片就在浏览器里静默 404。</p>
         */
        public static ReadRequest portal(String orgSlug, String slug, String requestedPath) {
            // 前缀要带上下文路径：它会写进渲染后的 <img src> 与站内链接，少一段就整片 404
            String base = AppPaths.of("/kb/" + orgSlug + "/" + slug);
            return new ReadRequest("portal", base + "/asset/", base + "?path=",
                    Variant.KB, false, true, null, requestedPath, null, null, null);
        }

        public static ReadRequest share(String token, boolean singleDoc) {
            return new ReadRequest("share", AppPaths.of("/share/" + token + "/asset/"),
                    AppPaths.of("/share/" + token + "?path="),
                    singleDoc ? Variant.DOC : Variant.KB, singleDoc, !singleDoc, null, null, token, null, null);
        }
    }

    public ReadView build(KnowledgeBase kb, ReadRequest request) {
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        List<String> docPaths = vaultFileService.listDocPaths(root);

        ReadView view = new ReadView();
        view.setMode(request.mode());
        view.setKbId(kb.getId());
        view.setKbName(kb.getName());
        view.setKbSlug(kb.getSlug());
        view.setKbDescription(kb.getDescription());
        view.setKbCoverSrc(coverSrc(kb.getCoverUrl(), request.assetPrefix()));
        view.setKbUpdatedText(TimeUtil.display(kb.getUpdatedAt()));
        view.setKbTags(splitTags(kb.getTags()));
        view.setVisibility(kb.getVisibility());
        view.setPublicKb(kb.isPublic());
        view.setDocCount(docPaths.size());
        view.setTagCount(splitTags(kb.getTags()).size());
        view.setShowTree(request.includeTree());
        view.setSingleDoc(request.singleDoc());
        view.setShareToken(request.shareToken());
        view.setExpiresText(request.expiresText());
        view.setViews(request.views());
        view.setAssetPrefix(request.assetPrefix());
        view.setDocLinkPrefix(request.docLinkPrefix());
        if (request.includeTree()) {
            view.setTree(vaultFileService.tree(root));
        }

        String docPath = resolveDocPath(docPaths, request);
        if (docPath == null) {
            view.setEmpty(true);
            return view;
        }
        view.setCurrentPath(docPath);
        view.setCurrentName(FileNameUtil.stripMarkdownExt(PathGuard.fileNameOf(docPath)));

        RenderResult result = markdownService.render(root, kb.getId(), docPath, request.variant(),
                RenderContext.of(request.assetPrefix(), request.docLinkPrefix(), request.singleDoc()));
        view.setHtml(result.html());
        view.setOutline(result.outline());
        view.setEtag(result.etag());

        DocMeta meta = result.meta();
        view.setTitle(meta.title());
        view.setSummary(meta.summary());
        view.setTags(meta.tags());
        view.setAuthor(meta.author());
        view.setPublishedText(TimeUtil.display(meta.publishedAt()));
        view.setUpdatedText(TimeUtil.display(meta.modifiedAt()));
        view.setWordCount(meta.wordCount());
        view.setReadingMinutes(meta.readingMinutes());
        view.setLineCount(meta.lineCount());

        fillNeighbours(view, docPaths, docPath);
        return view;
    }

    /**
     * 封面取图地址。
     *
     * <p>封面是库 vault 里的普通文件，所以借当前场景自己的资源前缀取；没有封面就留空，
     * 由前端退回首字母徽标——拼一个不存在的地址只会换来一个坏图图标。</p>
     */
    private String coverSrc(String coverUrl, String assetPrefix) {
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        return assetPrefix + PathEncoder.encodePath(coverUrl);
    }

    private String resolveDocPath(List<String> docPaths, ReadRequest request) {
        if (request.fixedDocPath() != null && !request.fixedDocPath().isBlank()) {
            String normalized = PathGuard.normalizeRelative(request.fixedDocPath());
            return docPaths.contains(normalized) ? normalized : null;
        }
        if (request.requestedPath() != null && !request.requestedPath().isBlank()) {
            try {
                String normalized = PathGuard.normalizeRelative(request.requestedPath());
                if (docPaths.contains(normalized)) {
                    return normalized;
                }
            } catch (RuntimeException ignored) {
                // 非法 path 直接回退首篇
            }
        }
        return docPaths.isEmpty() ? null : docPaths.get(0);
    }

    private void fillNeighbours(ReadView view, List<String> docPaths, String docPath) {
        int index = docPaths.indexOf(docPath);
        if (index > 0) {
            String prev = docPaths.get(index - 1);
            view.setPrevPath(prev);
            view.setPrevName(FileNameUtil.stripMarkdownExt(PathGuard.fileNameOf(prev)));
            view.setPrevLink(view.getDocLinkPrefix() + PathEncoder.encodePath(prev));
        }
        if (index >= 0 && index < docPaths.size() - 1) {
            String next = docPaths.get(index + 1);
            view.setNextPath(next);
            view.setNextName(FileNameUtil.stripMarkdownExt(PathGuard.fileNameOf(next)));
            view.setNextLink(view.getDocLinkPrefix() + PathEncoder.encodePath(next));
        }
    }
}
