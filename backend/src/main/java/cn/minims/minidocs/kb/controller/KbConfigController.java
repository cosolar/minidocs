package cn.minims.minidocs.kb.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.TenantContext;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * 库级配置（{@code .minidocs.json} 的可见部分）：隐藏规则。
 *
 * <p>与 {@code DocController} 的目录树接口分开，因为两者要的东西<b>正好相反</b>：
 * 那边给的是「读者该看到的」（已过滤），这边给的是「作者该配置的」（未过滤）。
 * 合成一个接口就会出现「已隐藏的项无法取消隐藏」这种只能单向操作的面板。</p>
 *
 * <p>门用 {@code KB_EDIT_META}：改隐藏规则本质是改这个库的组织方式，与改名字同级；
 * 而读轴的 {@code KB_VIEW} 只该放行「看得到树」，不包含「改配置」。</p>
 */
@Tag(name = "知识库配置")
@RestController
@RequiredArgsConstructor
public class KbConfigController {

    private final AccessService accessService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final VaultFileService vaultFileService;

    /**
     * 设置面板的初始数据：隐藏规则 + 未过滤的完整树。
     *
     * <p>两者必须同源：树要能看到已隐藏项（否则没法取消），规则要能看到当前值（否则没法回填勾选）。</p>
     */
    @Operation(summary = "读取库级配置（隐藏规则 + 未过滤目录树 + 展示偏好）")
    @GetMapping("/api/console/{org}/kbs/{slug}/config")
    public ApiResponse<ConfigVO> config(@PathVariable String org, @PathVariable String slug) {
        KnowledgeBase kb = locate(slug);
        Path root = root(kb);
        return ApiResponse.ok(new ConfigVO(vaultFileService.hiddenRules(root),
                vaultFileService.treeIncludingHidden(root), vaultFileService.displaySettings(root)));
    }

    @Operation(summary = "保存库级配置（隐藏规则整份覆盖 + 展示偏好）")
    @PutMapping("/api/console/{org}/kbs/{slug}/config")
    public ApiResponse<List<String>> saveConfig(@PathVariable String org, @PathVariable String slug,
                                                @Valid @RequestBody ConfigRequest request) {
        KnowledgeBase kb = accessService.requireKb(
                knowledgeBaseService.requireBySlug(TenantContext.requireId(), slug).getId(),
                KbAction.KB_EDIT_META, UserContext.get());
        Path root = root(kb);
        vaultFileService.saveHiddenRules(root, request.hidden());
        // display 为 null 表示「这次没带」。与 hidden 不同，它允许缺省：
        // 旧客户端（以及重命名 / 移动那些内部写入路径）只关心隐藏规则，
        // 若把缺省当成 false，一份不带 display 的请求就会把作者的展示偏好静默关掉。
        if (request.display() != null) {
            vaultFileService.saveDisplay(root, request.display().showMdSuffix());
        }
        // 规则一改，库级元信息里的文档数就变了（被隐藏的不再计入）。
        // 详情接口会实时校准，但列表与门户读的是这一列，所以要在这里落一次。
        knowledgeBaseService.touch(kb.getId());
        return ApiResponse.ok(vaultFileService.hiddenRules(root));
    }

    private KnowledgeBase locate(String slug) {
        return accessService.requireKb(
                knowledgeBaseService.requireBySlug(TenantContext.requireId(), slug).getId(),
                KbAction.KB_VIEW, UserContext.get());
    }

    private Path root(KnowledgeBase kb) {
        return vaultFileService.rootOf(kb.getStorageKey());
    }

    /**
     * 保存请求。
     *
     * <p>{@code hidden} 允许为空数组（等于「全部显示」），但不允许 null —— 那是「没传」，
     * 与「清空」在语义上必须区分，否则一次漏传就会把作者的规则全清掉。</p>
     *
     * <p>{@code display} 相反：<b>允许为 null</b>，含义是「这次不碰展示偏好」。
     * 两个分区的缺省方向相反不是疏忽 —— 隐藏规则少传一次会清空作者的成果，必须报错；
     * 展示偏好少传一次按「不动」处理，才让只改隐藏规则的旧客户端能继续用。</p>
     */
    public record ConfigRequest(@NotNull(message = "hidden 不能为 null；清空请传空数组") List<String> hidden,
                                DisplayRequest display) {
    }

    /** 展示偏好。字段用原始 boolean：缺省不是「关」而是「保持原样」，由上层判null。 */
    public record DisplayRequest(boolean showMdSuffix) {
    }

    /**
     * 配置面板的读模型。
     *
     * <p>{@code tree} 是<b>未过滤</b>的完整树（见类注释）；{@code hidden} 是当前的规则原文，
     * 前端据此回填勾选 —— 注意规则里可能是目录也可能是文件，勾选状态要按类型分别处理。</p>
     *
     * <p>{@code display} 独立于隐藏规则：它管的是「名字怎么显示」，而 hidden 管「哪些东西出现」。
     * 两者合成一个「配置」概念是对的（都在 .minidocs.json 里），但UI 上必须分开摆，
     * 否则「取消隐藏」会被读成「恢复默认显示」。</p>
     */
    public record ConfigVO(List<String> hidden, List<DocNode> tree, VaultFileService.DisplayVO display) {
    }
}
