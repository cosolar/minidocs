package cn.minims.minidocs.kb.service;

import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.kb.dto.KbDtos.CreateRequest;
import cn.minims.minidocs.kb.dto.KbDtos.GitStatusVO;
import cn.minims.minidocs.kb.dto.KbDtos.GitSyncRequest;
import cn.minims.minidocs.kb.dto.KbDtos.GitSyncVO;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.dto.KbDtos.PortalStatsVO;
import cn.minims.minidocs.kb.dto.KbDtos.StatsVO;
import cn.minims.minidocs.kb.dto.KbDtos.UpdateRequest;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    /**
     * 知识库分页列表。可见集由 AccessService 决定，本方法不再自行判断权限。
     *
     * <p>不带组织上下文的那条跨组织口径只留给门户首页的「我的全部库」；组织内列表必须走
     * {@link #page(Long, LoginUser, String, String, String, Boolean, Boolean, long, long)}。</p>
     */
    default PageResult<KbVO> page(LoginUser viewer, String keyword, String sort, String visibility,
                                  Boolean favored, Boolean mine, long page, long size) {
        return page(null, viewer, keyword, sort, visibility, favored, mine, page, size);
    }

    /**
     * 组织内列表（规范 §4.2）。
     *
     * @param tenantId 组织上下文，非空时结果只可能是该组织的库；null 表示跨组织
     * @param viewer   当前用户；为 null 表示游客（仅 public 库）
     * @param mine     true 时只看「我创建或我维护」的库
     */
    PageResult<KbVO> page(Long tenantId, LoginUser viewer, String keyword, String sort, String visibility,
                          Boolean favored, Boolean mine, long page, long size);

    /**
     * 门户列表：只出「已发布」（有效整库分享）的库，对所有访问者一视同仁 —— 分享等同于发布，
     * 库是否出现在门户不再取决于可见性，也不再因为登录而多出未发布的库。
     *
     * @param access {@code public}（未加密）/ {@code private}（已加密）；null 不筛
     */
    PageResult<KbVO> pagePublished(String keyword, String sort, String access, long page, long size);

    /** 门户统计：已发布库的总数、公开 / 私有拆分与文档量。 */
    PortalStatsVO portalStats();

    KbVO detail(Long id, LoginUser viewer);

    /**
     * 建库。
     *
     * @param tenantSlug 目标组织；null 表示访问者的个人组织。成员门由 {@code KB_CREATE} 裁决，
     *                   非成员拿到 404（§2.5）
     */
    KbVO create(String tenantSlug, CreateRequest request, LoginUser actor);

    KbVO update(Long id, UpdateRequest request, LoginUser actor);

    /**
     * 云端库工作副本状态（拉取/推送按钮的提示都出自这里）。
     *
     * @throws cn.minims.minidocs.common.exception.BizException 库不存在 / 无读权 → 404、未绑定 Git → 400
     */
    GitStatusVO gitStatus(Long id, LoginUser viewer);

    /**
     * 拉取远程更新到工作副本。要求 {@code DOC_WRITE}：它会改写磁盘上的正文。
     *
     * @throws cn.minims.minidocs.common.exception.BizException 本地有未提交改动或远程冲突 → 409，网络/认证失败 → 502
     */
    GitSyncVO gitPull(Long id, LoginUser actor);

    /**
     * 把工作副本的改动提交并推送到远程。
     *
     * @param request 可为 null（省略即用默认提交说明）
     */
    GitSyncVO gitCommitPush(Long id, GitSyncRequest request, LoginUser actor);

    /** 删除知识库（目录 + 记录 + 级联分享）。 */
    void deleteKnowledgeBase(Long id, LoginUser actor);

    boolean switchFavorite(Long kbId, LoginUser actor, boolean favorite);

    StatsVO stats(LoginUser viewer);

    /**
     * 注销用户时清空其名下知识库。
     *
     * <p>刻意不提供「跳过权限的 purge」入口：绕过裁决的分支留在业务代码里迟早被复用，
     * 因此把这条无主流程收敛成唯一的一个按用户清理方法。</p>
     */
    int purgeOwnedBy(Long userId);

    KnowledgeBase findBySlug(String slug);

    /**
     * 组织内按 slug 定位（规范 §4.2 的 {@code /api/console/{org}/kbs/{slug}}）。
     *
     * <p>slug 自 v2 起只在组织内唯一，所以脱离组织名的 slug 不再能定位到唯一的库。与
     * {@link #findBySlug(String)} 一样只是定位手段，不产生任何读权：读不读得到仍由
     * {@code AccessService.requireKb} 判。</p>
     *
     * @throws cn.minims.minidocs.common.exception.BizException 该组织下没有这个 slug → 404
     */
    KnowledgeBase requireBySlug(Long tenantId, String slug);

    /**
     * 某个人可读的、指定 slug 的全部库（老公开链接消歧用，规范 §7.1.3）。
     *
     * <p>slug 已降级为组织内唯一，因此 {@code /kb/{oldSlug}} 这类历史链接可能命中多个组织。
     * 命中口径是「已发布 ∪ 可读」：已发布（有效整库分享）的库任何访问者都能命中 —— 分享即发布后
     * 老链接仍要能走通；在此之上再并上 {@code applyVisibleScope} 的可见集，成员打开自己的库、
     * 消歧列表都不受影响。于是这份列表本身就不泄露无读权者的库存在性。</p>
     */
    List<KbVO> visibleBySlug(String slug, LoginUser viewer);

    /** 更新 updated_at（文档变更时调用）。 */
    void touch(Long kbId);

    /** 全量校准（定时任务）。 */
    int refreshAllDocCounts();

    /**
     * 清空某人在该组织全部知识库上的维护名单。
     *
     * <p>判定层本来就不认组织外的名单行（§9），这里仍要物理删除，是因为「重新加入同一组织」会把旧行重新激活
     * —— 那等于一次无人批准的授权恢复。移出组织必须是一次完整的撤销。</p>
     */
    void clearRosterOf(Long tenantId, Long userId);

    /**
     * 清掉某人全部收藏行（删号时调用）。
     *
     * <p>收藏只参与渲染他自己的视图，没有任何越权面，但留着就是永不自愈的孤儿行；
     * 而且用户名可释放重用，按 id 清的这一刀保证下一个拿到该 id 的人不会继承别人的收藏。</p>
     */
    void purgeFavoritesOf(Long userId);
}
