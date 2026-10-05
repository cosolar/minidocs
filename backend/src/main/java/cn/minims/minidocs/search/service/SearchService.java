package cn.minims.minidocs.search.service;

import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.share.support.SharePublicationSupport;
import cn.minims.minidocs.tenant.service.TenantService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.minims.minidocs.kb.dto.KbDtos.splitTags;

/**
 * 全局搜索。
 *
 * <p>L1 名称搜索（必做）：知识库名称 / 描述 / 标签 + 文档文件名；
 * L2 全文检索（P1）：定长扫描文档正文并返回命中片段（有界扫描，避免内存索引的构建与一致性问题）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_KB_SCAN = 50;
    private static final int MAX_DOC_SCAN = 2000;
    private static final int SNIPPET_RADIUS = 60;
    private static final int GROUP_LIMIT = 5;

    private final KnowledgeBaseService knowledgeBaseService;
    private final AccessService accessService;
    private final VaultFileService vaultFileService;
    private final TenantService tenantService;

    public record SuggestItem(String type, String title, String subtitle, String url) {
    }

    /**
     * 全文命中的行。
     *
     * <p>{@code url} 与 L1 联想同源（{@link #kbUrl}）：跳转地址由服务端一处拼，前端拿到什么就跳什么。
     * 只给 {@code kbId + path} 等于让前端自己反查组织 slug 再拼路由，那条拼装规则早晚和门户路由漂移。</p>
     */
    public record SearchHit(String kbId, String kbName, String path, String title, String snippet, String url) {
    }

    /** L1 联想：知识库 / 文档 / 标签。 */
    public Map<String, List<SuggestItem>> suggest(String query, LoginUser viewer) {
        Map<String, List<SuggestItem>> result = new LinkedHashMap<>();
        result.put("kbs", List.of());
        result.put("docs", List.of());
        result.put("tags", List.of());
        if (query == null || query.isBlank()) {
            return result;
        }
        String keyword = query.trim();
        String lower = keyword.toLowerCase(Locale.ROOT);

        List<KnowledgeBase> bases = visibleBases(viewer);
        Map<Long, String> tenantSlugs = tenantSlugs(bases);

        List<SuggestItem> kbItems = new ArrayList<>();
        List<SuggestItem> tagItems = new ArrayList<>();
        Set<String> matchedTags = new LinkedHashSet<>();
        for (KnowledgeBase kb : bases) {
            if (kbItems.size() < GROUP_LIMIT && matches(kb.getName(), lower, true)
                    || kbItems.size() < GROUP_LIMIT && matches(kb.getDescription(), lower, true)) {
                kbItems.add(new SuggestItem("kb", kb.getName(), kb.getDescription(),
                        kbUrl(kb, tenantSlugs)));
            }
            for (String tag : splitTags(kb.getTags())) {
                if (tag.toLowerCase(Locale.ROOT).contains(lower) && matchedTags.add(tag) && tagItems.size() < GROUP_LIMIT) {
                    tagItems.add(new SuggestItem("tag", tag, kb.getName(), kbUrl(kb, tenantSlugs)));
                }
            }
        }

        List<SuggestItem> docItems = new ArrayList<>();
        int scanned = 0;
        for (KnowledgeBase kb : bases) {
            if (docItems.size() >= GROUP_LIMIT || scanned >= MAX_DOC_SCAN) {
                break;
            }
            List<DocNode> nodes = vaultFileService.tree(vaultFileService.rootOf(kb.getStorageKey()));
            List<DocNode> flat = new ArrayList<>();
            flatten(nodes, flat);
            for (DocNode node : flat) {
                scanned++;
                String name = node.name() == null ? "" : node.name();
                if (name.toLowerCase(Locale.ROOT).contains(lower)) {
                    docItems.add(new SuggestItem("doc", stripExt(name), kb.getName() + " / " + node.path(),
                            kbUrl(kb, tenantSlugs) + "?path=" + encode(node.path())));
                    if (docItems.size() >= GROUP_LIMIT) {
                        break;
                    }
                }
            }
        }

        result.put("kbs", kbItems);
        result.put("docs", docItems);
        result.put("tags", tagItems);
        return result;
    }

    /** L2 全文检索（有界扫描）。 */
    public PageResult<SearchHit> search(String query, Long kbId, LoginUser viewer, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 50);
        if (query == null || query.isBlank()) {
            return PageResult.empty(current, pageSize);
        }
        String keyword = query.trim();
        String lower = keyword.toLowerCase(Locale.ROOT);

        List<KnowledgeBase> bases = visibleBases(viewer).stream()
                .filter(kb -> kbId == null || kb.getId().equals(kbId))
                .toList();
        Map<Long, String> tenantSlugs = tenantSlugs(bases);

        List<SearchHit> hits = new ArrayList<>();
        int scanned = 0;
        for (KnowledgeBase kb : bases) {
            Path root = vaultFileService.rootOf(kb.getStorageKey());
            List<String> docPaths = vaultFileService.listDocPaths(root);
            for (String docPath : docPaths) {
                if (scanned++ >= MAX_DOC_SCAN) {
                    break;
                }
                String content;
                try {
                    content = vaultFileService.read(root, docPath);
                } catch (RuntimeException e) {
                    continue;
                }
                int index = content.toLowerCase(Locale.ROOT).indexOf(lower);
                if (index < 0) {
                    continue;
                }
                hits.add(new SearchHit(String.valueOf(kb.getId()), kb.getName(), docPath,
                        stripExt(nameOf(docPath)), snippet(content, index, keyword.length()),
                        kbUrl(kb, tenantSlugs) + "?path=" + encode(docPath)));
            }
        }

        int total = hits.size();
        int from = (int) Math.min((current - 1) * pageSize, total);
        int to = (int) Math.min(from + pageSize, total);
        return PageResult.of(hits.subList(from, to), total, current, pageSize);
    }

    private List<KnowledgeBase> visibleBases(LoginUser viewer) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.<KnowledgeBase>lambdaQuery()
                .orderByDesc(KnowledgeBase::getUpdatedAt)
                .last("LIMIT " + MAX_KB_SCAN);
        accessService.applyVisibleScope(wrapper, viewer, null);
        if (viewer == null) {
            // 分享即发布：游客的可见集只剩已发布的库。不补这一刀，搜索会把未发布的公开库也吐出来，
            // 而结果链接落到门户阅读页会 404 —— 列表与阅读页必须同一份口径。
            wrapper.apply(SharePublicationSupport.publishedExists(null), TimeUtil.now());
        }
        return knowledgeBaseService.list(wrapper);
    }

    /** 联想结果的链接落在门户路由上，门户按 {@code /kb/{org}/{slug}} 定位。 */
    private String kbUrl(KnowledgeBase kb, Map<Long, String> tenantSlugs) {
        return AppPaths.of("/kb/" + tenantSlugs.get(kb.getTenantId()) + "/" + kb.getSlug());
    }

    /** 一批库的组织 slug：逐行查会变成 N+1。 */
    private Map<Long, String> tenantSlugs(List<KnowledgeBase> kbs) {
        List<Long> tenantIds = kbs.stream().map(KnowledgeBase::getTenantId).filter(Objects::nonNull)
                .distinct().toList();
        Map<Long, String> slugs = new HashMap<>();
        if (!tenantIds.isEmpty()) {
            tenantService.listByIds(tenantIds).forEach(tenant -> slugs.put(tenant.getId(), tenant.getSlug()));
        }
        return slugs;
    }

    private void flatten(List<DocNode> nodes, List<DocNode> collector) {
        for (DocNode node : nodes) {
            if ("doc".equals(node.type())) {
                collector.add(node);
            } else if (node.children() != null) {
                flatten(node.children(), collector);
            }
        }
    }

    private boolean matches(String value, String lowerKeyword, boolean ignoreCase) {
        if (value == null) {
            return false;
        }
        return ignoreCase
                ? value.toLowerCase(Locale.ROOT).contains(lowerKeyword)
                : value.contains(lowerKeyword);
    }

    private String snippet(String content, int index, int keywordLength) {
        int start = Math.max(0, index - SNIPPET_RADIUS);
        int end = Math.min(content.length(), index + keywordLength + SNIPPET_RADIUS);
        String text = content.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + text + (end < content.length() ? "…" : "");
    }

    private String nameOf(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    private String stripExt(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".md", ".markdown")) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
