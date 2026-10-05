package cn.minims.minidocs.share.service;

import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.event.DocumentChangedEvent;
import cn.minims.minidocs.share.dto.ShareDtos.CreateRequest;
import cn.minims.minidocs.share.dto.ShareDtos.ShareVO;
import cn.minims.minidocs.share.dto.ShareDtos.UpdateRequest;
import cn.minims.minidocs.share.entity.Share;

public interface ShareService {

    /**
     * 组织内创建 / 更新分享（规范 §4.2）。
     *
     * @param tenantId 组织上下文，来自 {@code /api/console/{org}/shares} 的路径段
     */
    ShareVO createOrUpdate(Long tenantId, CreateRequest request, LoginUser actor, String baseUrl);

    /**
     * 本组织内「这个人的可见分享」列表。
     *
     * <p>范围由 {@code applyVisibleScope} 决定而不是由创建者决定：分享管理台是组织资产清单，
     * 第二个维护者建的链接同样要能看见（规范 §2.6 不允许拿 owner_id 当过滤权限）。</p>
     *
     * @param baseUrl 出参里拼完整链接用，见 {@link #info}
     */
    PageResult<ShareVO> page(Long tenantId, LoginUser actor, String status, String scope, long page, long size,
                             String baseUrl);

    /**
     * 某目标的分享状态；{@code kbSlug} 只在 {@code tenantId} 组织内解析。
     *
     * <p>列表与查询同样要带 {@code baseUrl}：{@code url} 是「拿来复制」的字段，只有创建那一次
     * 有值的话，重新打开面板看到的链接就是空的（链接本身明明还在）。</p>
     */
    ShareVO info(Long tenantId, LoginUser actor, String kbSlug, String docPath, String baseUrl);

    ShareVO update(Long tenantId, String token, UpdateRequest request, LoginUser actor, String baseUrl);

    void revoke(Long tenantId, String token, LoginUser actor);

    /** 匿名侧按 token 取分享，不做权限判定（口令 / 过期由 ShareApiController 把关）。 */
    Share findByToken(String token);

    /**
     * 记一次访问；返回<b>本次是否真的计入了浏览量</b>。
     *
     * <p>口径：一个访客对同一个知识库，在 {@code ShareServiceImpl.VIEW_SESSION_MINUTES} 的冷却窗口内
     * 只计一次 —— 读者翻文档、切上一篇都不再计数。调用方要用返回值拼「含本次」的累计值。</p>
     */
    boolean recordView(Share share, String ip, String userAgent);

    long countUv(Long shareId);

    /** 文件变更联动：同步 doc_path 或标记失效。 */
    void applyDocumentChange(DocumentChangedEvent event);

    void deleteByKnowledgeBase(Long kbId);
}
