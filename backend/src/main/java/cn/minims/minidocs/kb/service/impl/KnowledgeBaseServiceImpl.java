package cn.minims.minidocs.kb.service.impl;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.event.KnowledgeBaseDeletedEvent;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.CryptoUtil;
import cn.minims.minidocs.common.util.SlugUtil;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.git.GitCloneWorker;
import cn.minims.minidocs.git.GitVaultService;
import cn.minims.minidocs.git.GitVaultService.RepoStatus;
import cn.minims.minidocs.git.GitVaultService.SyncOutcome;
import cn.minims.minidocs.kb.dto.KbDtos.CreateRequest;
import cn.minims.minidocs.kb.dto.KbDtos.GitBindingVO;
import cn.minims.minidocs.kb.dto.KbDtos.GitStatusVO;
import cn.minims.minidocs.kb.dto.KbDtos.GitSyncRequest;
import cn.minims.minidocs.kb.dto.KbDtos.GitSyncVO;
import cn.minims.minidocs.kb.dto.KbDtos.KbVO;
import cn.minims.minidocs.kb.dto.KbDtos.PortalStatsVO;
import cn.minims.minidocs.kb.dto.KbDtos.StatsVO;
import cn.minims.minidocs.kb.dto.KbDtos.UpdateRequest;
import cn.minims.minidocs.kb.entity.KbFavorite;
import cn.minims.minidocs.kb.entity.KbMember;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbFavoriteMapper;
import cn.minims.minidocs.kb.mapper.KbMemberMapper;
import cn.minims.minidocs.kb.mapper.KnowledgeBaseMapper;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.share.entity.ShareViewLog;
import cn.minims.minidocs.share.mapper.ShareViewLogMapper;
import cn.minims.minidocs.share.support.SharePublicationSupport;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.minims.minidocs.kb.dto.KbDtos.splitTags;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    private static final int MAX_TAG_COUNT = 5;
    private static final int MAX_TAG_LENGTH = 12;

    private final KbFavoriteMapper favoriteMapper;
    private final KbMemberMapper kbMemberMapper;
    private final VaultFileService vaultFileService;
    private final UserService userService;
    private final TenantService tenantService;
    private final AccessService accessService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final GitVaultService gitVaultService;
    private final GitCloneWorker gitCloneWorker;
    private final SharePublicationSupport publicationSupport;
    private final ShareViewLogMapper viewLogMapper;
    private final MiniDocsProperties properties;

    @Override
    public PageResult<KbVO> page(Long tenantId, LoginUser viewer, String keyword, String sort, String visibility,
                                 Boolean favored, Boolean mine, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 100);

        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.lambdaQuery();
        accessService.applyVisibleScope(wrapper, viewer, tenantId);
        if (Boolean.TRUE.equals(mine)) {
            accessService.applyParticipatingScope(wrapper, viewer);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(KnowledgeBase::getName, kw)
                    .or().like(KnowledgeBase::getDescription, kw)
                    .or().like(KnowledgeBase::getTags, kw));
        }
        if (visibility != null && !visibility.isBlank()) {
            wrapper.eq(KnowledgeBase::getVisibility, visibility);
        }
        if (Boolean.TRUE.equals(favored)) {
            Set<Long> favIds = favoriteIds(viewer);
            if (favIds.isEmpty()) {
                return PageResult.empty(current, pageSize);
            }
            wrapper.in(KnowledgeBase::getId, favIds);
        }
        applySort(wrapper, sort);

        Page<KnowledgeBase> result = baseMapper.selectPage(new Page<>(current, pageSize), wrapper);
        Set<Long> favIds = favoriteIds(viewer);
        Map<Long, String> ownerNames = ownerNames(result.getRecords());
        Map<Long, Tenant> tenants = tenantFacts(result.getRecords());
        // 发布态一并下发：控制台得能一眼看出「这个库在门户上是什么状态」。少了这一格，
        // 建完库的人只能靠「门户上搜不到」倒推自己没发布 —— 而可见性设成 public 也不改变这件事。
        Map<Long, Share> publishShares = publicationSupport.findOf(
                result.getRecords().stream().map(KnowledgeBase::getId).toList());
        List<KbVO> list = result.getRecords().stream()
                // 逐行走 permissionsOf 而不是自己拼判定：可见集是 SQL 条件，判定才是结论，
                // 两处各写一遍迟早会出现「列表能看到、点进去 404」。分页对象本身已带全列，
                // 所以这里没有额外取数，只有每行若干次主键级判定查询。
                .map(kb -> withPublication(KbVO.from(kb, favIds.contains(kb.getId()),
                                ownerNames.get(kb.getOwnerId()), tenants.get(kb.getTenantId()),
                                accessService.permissionsOf(kb, viewer)),
                        publishShares.get(kb.getId())))
                .collect(Collectors.toList());
        return PageResult.of(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public PageResult<KbVO> pagePublished(String keyword, String sort, String access, Long viewerId, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 100);

        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.lambdaQuery();
        // 门户可见集两道闸：组织未停用 + （已发布且对当前访客可见）。
        // 可见性（public/org/private）仍然不参与 —— 它决定的是「谁能读」，与「是否出现在门户」正交。
        accessService.applyTenantActiveScope(wrapper);
        if (viewerId == null) {
            wrapper.apply(SharePublicationSupport.publishedAndVisibleExists(access, null), TimeUtil.now());
        } else {
            wrapper.apply(SharePublicationSupport.publishedAndVisibleExists(access, viewerId),
                    TimeUtil.now(), viewerId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(KnowledgeBase::getName, kw)
                    .or().like(KnowledgeBase::getDescription, kw)
                    .or().like(KnowledgeBase::getTags, kw));
        }
        applySort(wrapper, sort);

        /*
         * 这里用 selectList + 内存分页，不走 {@code selectPage}。
         *
         * <p>原因：分页插件的 count 语句是 {@code SELECT COUNT(*) FROM (原查询) TOTAL}，
         * 而本查询带 {@code EXISTS} 子查询 + {@code ORDER BY}，插件的 count 优化会在
         * 「能否剥离 ORDER BY」上摇摆 —— 剥离了却不给派生表补别名（{@code Every derived
         * table must have its own alias}），不剥离又把 ORDER BY 留在子查询里（MySQL 语法
         * 错误）。两个分支都试过，登录态与匿名态各炸一个，报错还互相掩盖。</p>
         *
         * <p>门户列表的量级是「已发布库数」，两位数；全量取回再在内存里切片，
         * 省掉一次 count 也彻底绕开这个不稳定的插件行为。控制台那边列表走别的路径，
         * 不受影响。</p>
         */
        List<KnowledgeBase> all = list(wrapper);
        long total = all.size();
        int from = (int) Math.min((current - 1) * pageSize, total);
        int to = (int) Math.min(from + pageSize, total);
        List<KnowledgeBase> rows = from >= to ? List.of() : all.subList(from, to);

        Map<Long, String> ownerNames = ownerNames(rows);
        Map<Long, Tenant> tenants = tenantFacts(rows);
        // 一次批量取回这批库的发布分享，逐行判「公开 / 私有」，避免每行一次查询
        Map<Long, Share> shares = publicationSupport.findOf(
                rows.stream().map(KnowledgeBase::getId).toList());
        List<KbVO> vo = rows.stream()
                .map(kb -> withPublication(KbVO.from(kb, false, ownerNames.get(kb.getOwnerId()),
                        tenants.get(kb.getTenantId())), shares.get(kb.getId())))
                .collect(Collectors.toList());
        return PageResult.of(vo, total, current, pageSize);
    }

    @Override
    public PortalStatsVO portalStats() {
        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.lambdaQuery();
        accessService.applyTenantActiveScope(wrapper);
        wrapper.apply(SharePublicationSupport.publishedExists(null), TimeUtil.now());
        List<KnowledgeBase> list = list(wrapper);

        Map<Long, Share> shares = publicationSupport.findOf(list.stream().map(KnowledgeBase::getId).toList());
        long kbPublic = list.stream()
                .filter(kb -> SharePublicationSupport.ACCESS_PUBLIC.equals(
                        SharePublicationSupport.publishStatusOf(shares.get(kb.getId()))))
                .count();
        long docTotal = list.stream().mapToLong(kb -> kb.getDocCount() == null ? 0 : kb.getDocCount()).sum();
        // 访问量取发布分享的 views 累计：分享页与门户阅读都走 recordView，两条链路自然合并到这里
        long viewTotal = shares.values().stream()
                .mapToLong(s -> s.getViews() == null ? 0L : s.getViews())
                .sum();

        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        List<KnowledgeBase> thisMonth = list.stream()
                .filter(kb -> kb.getCreatedAt() != null && !kb.getCreatedAt().isBefore(monthStart))
                .toList();
        long kbDelta = thisMonth.size();
        long docDelta = thisMonth.stream()
                .mapToLong(kb -> kb.getDocCount() == null ? 0 : kb.getDocCount()).sum();

        // 本月访问量：按 share_view_log 里 view_date ≥ 月初的行数聚合，与 UV 去重口径一致。
        // 该表按日一行，同一 share 同一访客同日仅一行，因此计数近似「本月独立访客数」；
        // 卡片提示语用「本月」而不是「本月新增视图」正是这个原因。
        long viewDelta = 0L;
        if (!list.isEmpty()) {
            List<Long> shareIds = list.stream()
                    .map(kb -> shares.get(kb.getId()))
                    .filter(java.util.Objects::nonNull)
                    .map(Share::getId)
                    .toList();
            if (!shareIds.isEmpty()) {
                Long c = viewLogMapper.selectCount(Wrappers.<ShareViewLog>lambdaQuery()
                        .in(ShareViewLog::getShareId, shareIds)
                        .ge(ShareViewLog::getViewDate, LocalDate.now().withDayOfMonth(1)));
                viewDelta = c == null ? 0L : c;
            }
        }

        // 私有 = 已发布 − 公开：分享只有「加密 / 未加密」两种，不存在第三档，相减不会漏项
        return new PortalStatsVO(list.size(), kbPublic, list.size() - kbPublic, docTotal,
                viewTotal, viewDelta, kbDelta, docDelta);
    }

    @Override
    public KbVO detail(Long id, LoginUser viewer) {
        KnowledgeBase kb = accessService.requireKb(id, KbAction.KB_VIEW, viewer);
        // 详情给的是磁盘实况而不是缓存计数：先校准内存对象，再让 from 统一出参
        kb.setDocCount((int) countDocsSafely(kb));
        // 与列表同一口径：详情页的「发布状态」和列表那一列必须一致，否则两处会各说各话
        return withDisplay(withPublication(KbVO.from(kb, isFavorited(viewer, id), ownerName(kb.getOwnerId()),
                tenantOf(kb), accessService.permissionsOf(kb, viewer)), publicationSupport.find(kb.getId())), kb);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KbVO create(String tenantSlug, CreateRequest request, LoginUser actor) {
        String name = request.name().trim();
        String visibility = normalizeVisibility(request.visibility());
        String tags = normalizeTags(request.tags());
        String sourceType = normalizeSourceType(request.sourceType());

        // 建到哪个组织：留空落到自己的个人组织（v1 语义）。指定组织时由 KB_CREATE 的成员门裁决
        Long ownerId = actor.id();
        Tenant tenant = resolveCreateTenant(tenantSlug, actor);
        accessService.requireTenant(tenant, KbAction.KB_CREATE, actor);
        String slug = generateSlug(tenant.getId(), name);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenant.getId());
        kb.setOwnerId(ownerId);
        kb.setName(name);
        kb.setSlug(slug);
        kb.setStorageKey(StorageKey.of(tenant.getId(), slug));
        kb.setDescription(trimToNull(request.description()));
        kb.setVisibility(visibility);
        kb.setMaintainScope(normalizeMaintainScope(request.maintainScope()));
        kb.setCoverUrl(trimToNull(request.coverUrl()));
        kb.setTags(tags);
        kb.setDocCount(0);
        kb.setSourceType(sourceType);
        boolean git = KnowledgeBase.SOURCE_GIT.equals(sourceType);
        if (git) {
            applyGitBinding(kb, request);
        }
        save(kb);

        // 目录先建好：异步克隆要往里写，缺目录会直接失败
        vaultFileService.ensureRoot(kb.getStorageKey());

        if (git) {
            // 云端库不在这里拉代码。JGit 一次克隆几十秒到两分钟，放在 @Transactional 方法里意味着
            // 这段时间数据库连接与行锁一直被占着、HTTP 请求也一直挂着——用户只能对着转圈等，
            // 网络一抖就超时，刷新也救不回来（服务端那个请求还在跑）。
            // 所以这里只把「已排队」落进状态字段，提交后再由 GitCloneWorker 去做，
            // 前端按 git_last_sync_ok 是否为 null 决定要不要继续轮询。
            markQueued(kb);
            cloneWhenCommitted(kb.getId());
        }
        log.info("创建知识库 id={} slug={} tenant={} owner={} source={} queued={}",
                kb.getId(), slug, tenant.getId(), ownerId, sourceType, git);
        // 建库的人必定是自己的 OWNER，权限向量照算给前端，不要回一个空数组让人以为刚建的库没权限
        return KbVO.from(kb, false, ownerName(ownerId), tenant,
                accessService.permissionsOf(kb, actor));
    }

    /**
     * 事务提交后再触发后台克隆。
     *
     * <p>必须在提交之后：{@code GitCloneWorker} 靠 {@code kbId} 反查库记录，事务没提交时那条记录
     * 对别的连接不可见，异步线程会查个空然后直接放弃。</p>
     *
     * <p>没有活动事务时（单元测试或将来的非事务调用）就地触发，语义不变。</p>
     */
    private void cloneWhenCommitted(Long kbId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            gitCloneWorker.cloneAsync(kbId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gitCloneWorker.cloneAsync(kbId);
            }
        });
    }

    /**
     * 把「已排队、正在拉取」写进状态列。
     *
     * <p>{@code git_last_sync_ok} 留空表示「进行中」——这个字段本来是 {@code Boolean}，而项目里
     * 此前从未写过它，正好拿来承载这个语义。终态由 {@code GitCloneWorker} 落。</p>
     */
    private void markQueued(KnowledgeBase kb) {
        update(Wrappers.<KnowledgeBase>lambdaUpdate()
                .eq(KnowledgeBase::getId, kb.getId())
                .set(KnowledgeBase::getGitLastSyncStatus, "正在拉取仓库…")
                .set(KnowledgeBase::getGitLastSyncOk, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KbVO update(Long id, UpdateRequest request, LoginUser actor) {
        KnowledgeBase kb = accessService.requireKb(id, KbAction.KB_EDIT_META, actor);
        String visibilityBefore = kb.getVisibility();
        String visibilityTarget = request.visibility() == null || request.visibility().isBlank()
                ? null : normalizeVisibility(request.visibility());
        boolean visibilityChanged = visibilityTarget != null && !visibilityTarget.equals(visibilityBefore);
        if (visibilityChanged) {
            // 改可见范围是独立的治理能力：名单里的 EDITOR 能改名字，不能把库放大给全平台
            accessService.requireKb(id, KbAction.KB_SET_VISIBILITY, actor);
            kb.setVisibility(visibilityTarget);
        }
        if (request.name() != null && !request.name().isBlank()) {
            kb.setName(request.name().trim());
        }
        if (request.description() != null) {
            kb.setDescription(trimToNull(request.description()));
        }
        if (request.tags() != null) {
            kb.setTags(normalizeTags(request.tags()));
        }
        if (request.coverUrl() != null) {
            kb.setCoverUrl(trimToNull(request.coverUrl()));
        }
        persistMeta(kb);
        if (visibilityChanged) {
            // 可见性是读轴，改它等于改「谁能看见」，与名单变更同等需要留痕（F9）
            auditService.record(KbAction.KB_SET_VISIBILITY, actor.id(), kb.getTenantId(), kb.getId(), null,
                    Map.of("from", String.valueOf(visibilityBefore), "to", visibilityTarget));
        }
        return KbVO.from(kb, false, ownerName(kb.getOwnerId()), tenantOf(kb),
                accessService.permissionsOf(kb, actor));
    }

    // ------------------------------------------------------------------ 云端库同步

    /**
     * 工作副本状态。只要求读权：能看到这个库的人本来就该知道它「落后远程几个提交」。
     */
    @Override
    public GitStatusVO gitStatus(Long id, LoginUser viewer) {
        KnowledgeBase kb = requireGitKb(id, KbAction.KB_VIEW, viewer);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        RepoStatus status = gitVaultService.status(root, kb.getGitBranch());
        return new GitStatusVO(GitBindingVO.of(kb), status.present(), status.clean(), status.changedCount(),
                status.ahead(), status.behind(), status.headCommit(), status.changedPaths());
    }

    /**
     * 拉取远程更新到工作副本。
     *
     * <p>门用 {@code DOC_WRITE} 而不是读权：拉取会直接改写磁盘上的正文，这与「编辑文档」是同一类
     * 后果，只读成员不该能触发。不套 {@code @Transactional}：这一步改的是文件而不是数据库，
     * 而且失败时必须把「上次同步失败」这行状态留下来 —— 放在事务里会被回滚掉。</p>
     *
     * <p>{@code force} 走的是「丢弃本地未提交改动再拉」，比普通的合并拉取更危险
     * （作者的改动会真的没了），所以 controller 那边要求显式传true 才生效，
     * 且前端必须先弹确认框把「会丢什么」说清楚。</p>
     */
    @Override
    public GitSyncVO gitPull(Long id, boolean force, LoginUser actor) {
        KnowledgeBase kb = requireGitKb(id, KbAction.DOC_WRITE, actor);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        try {
            SyncOutcome outcome = gitVaultService.pull(root, kb.getGitBranch(), kb.getGitUsername(),
                    decryptToken(kb), force);
            // 拉进来的提交会带进新文档与删除，目录树缓存和 doc_count 都得跟着重算
            vaultFileService.invalidateTree(root);
            touch(kb.getId());
            recordSync(kb, true, outcome.message());
            auditSync(kb, actor, "pull", outcome.commitId(), outcome.changedCount());
            return new GitSyncVO(true, outcome.message(), outcome.changedCount(), outcome.commitId(),
                    GitBindingVO.of(getById(kb.getId())));
        } catch (BizException e) {
            recordSync(kb, false, e.getMessage());
            throw e;
        }
    }

    /**
     * 把一条分享解析成「发布态 + 短链 token」填进 VO。
     *
     * <p>门户列表与控制台列表/详情都要走这里，避免两处各写一遍「状态怎么判、token 怎么取」，
     * 而那正是最容易出现「列表说已发布、详情说未发布」的地方。</p>
     */
    private static KbVO withPublication(KbVO vo, Share share) {
        return vo.withPublication(SharePublicationSupport.publishStatusOf(share),
                share == null ? null : share.getToken());
    }

    /**
     * 把库级配置里的展示偏好填进 VO。
     *
     * <p>只有控制台详情走这里：它与 {@link #page} 的区别在于，<b>只有详情会渲染目录树</b>。
     * 列表页每行不读一次配置文件是刻意的 —— 一页20 行就是 20 次小文件读，
     * 而列表里根本没有树要渲染，这个开销换不到任何东西。</p>
     *
     * <p>门户那几条链路同理不填：它们的卡片不显示文件名，不需要这个标志，
     * 硬填反而给人「这个字段在所有链路都有值」的错觉。</p>
     */
    private KbVO withDisplay(KbVO vo, KnowledgeBase kb) {
        return vo.withDisplay(vaultFileService.showMdSuffix(vaultFileService.rootOf(kb.getStorageKey())));
    }

    /**
     * 把工作副本的改动提交并推送到远程。 */
    @Override
    public GitSyncVO gitCommitPush(Long id, GitSyncRequest request, LoginUser actor) {
        KnowledgeBase kb = requireGitKb(id, KbAction.DOC_WRITE, actor);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        String message = request == null ? null : request.message();
        try {
            SyncOutcome outcome = gitVaultService.commitAndPush(root, kb.getGitBranch(), kb.getGitUsername(),
                    decryptToken(kb), actor.nickname(), commitEmail(actor), message);
            recordSync(kb, true, outcome.message());
            auditSync(kb, actor, "push", outcome.commitId(), outcome.changedCount());
            return new GitSyncVO(true, outcome.message(), outcome.changedCount(), outcome.commitId(),
                    GitBindingVO.of(getById(kb.getId())));
        } catch (BizException e) {
            recordSync(kb, false, e.getMessage());
            throw e;
        }
    }

    /** 定位一个云端库：先过权限门，再确认它确实绑了仓库。 */
    private KnowledgeBase requireGitKb(Long id, KbAction action, LoginUser viewer) {
        KnowledgeBase kb = accessService.requireKb(id, action, viewer);
        if (!kb.isGit()) {
            throw BizException.of(ErrorCode.GIT_NOT_BOUND, "该知识库未绑定 Git 仓库");
        }
        if (kb.getGitUrl() == null || kb.getGitUrl().isBlank()) {
            throw BizException.of(ErrorCode.GIT_PARAM_INVALID, "云端知识库缺少仓库地址");
        }
        return kb;
    }

    /**
     * 记录最近一次同步的结果。
     *
     * <p>纯展示字段：写失败也不该把一次成功的拉取变成失败，所以只更新这三列、不做任何校验。</p>
     */
    private void recordSync(KnowledgeBase kb, boolean ok, String message) {
        update(Wrappers.<KnowledgeBase>lambdaUpdate()
                .eq(KnowledgeBase::getId, kb.getId())
                .set(KnowledgeBase::getGitLastSyncAt, TimeUtil.now())
                .set(KnowledgeBase::getGitLastSyncOk, ok)
                .set(KnowledgeBase::getGitLastSyncStatus, truncate(message, 500)));
    }

    private void auditSync(KnowledgeBase kb, LoginUser actor, String op, String commitId, int changed) {
        auditService.record(KbAction.DOC_WRITE, actor.id(), kb.getTenantId(), kb.getId(), null,
                Map.of("git", op, "commit", String.valueOf(commitId), "files", String.valueOf(changed)));
    }

    private static String commitEmail(LoginUser actor) {
        String local = actor.username() == null || actor.username().isBlank() ? "minidocs" : actor.username();
        return local + "@minidocs.local";
    }

    /**
     * 写回这条流程拥有的元数据列。
     *
     * <p>不用 {@code updateById}：它跳过 null，而「清空简介」「去掉封面」这两个动作本身就是写 null ——
     * 跳过的结果是接口回显已清空、库里仍是旧值，刷新一下就又回来了。顺带也不再把读出来那一行的
     * {@code doc_count} 之类原样回填回去，免得和并发改库相互覆盖。</p>
     */
    private void persistMeta(KnowledgeBase kb) {
        update(Wrappers.<KnowledgeBase>lambdaUpdate()
                .eq(KnowledgeBase::getId, kb.getId())
                .set(KnowledgeBase::getName, kb.getName())
                .set(KnowledgeBase::getDescription, kb.getDescription())
                .set(KnowledgeBase::getTags, kb.getTags())
                .set(KnowledgeBase::getVisibility, kb.getVisibility())
                .set(KnowledgeBase::getCoverUrl, kb.getCoverUrl())
                .set(KnowledgeBase::getUpdatedAt, TimeUtil.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id, LoginUser actor) {
        KnowledgeBase kb = accessService.requireKb(id, KbAction.KB_DELETE, actor);
        purge(kb);
        // 删完 kb_id 就是空引用：把名字与 storage_key 钉在 detail 里，事后才查得到删的是哪个库
        auditService.record(KbAction.KB_DELETE, actor.id(), kb.getTenantId(), kb.getId(), null,
                Map.of("name", kb.getName(), "slug", String.valueOf(kb.getSlug()),
                        "storageKey", String.valueOf(kb.getStorageKey())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeOwnedBy(Long userId) {
        List<KnowledgeBase> owned = list(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getOwnerId, userId));
        owned.forEach(this::purge);
        return owned.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean switchFavorite(Long kbId, LoginUser actor, boolean favorite) {
        // 收藏发生在库详情页里，先过读门：不可见的库连存在性都不该确认（v1 缺陷）
        accessService.requireKb(kbId, KbAction.KB_VIEW, actor);
        Long userId = actor.id();
        if (favorite) {
            if (!isFavorited(actor, kbId)) {
                KbFavorite entity = new KbFavorite();
                entity.setUserId(userId);
                entity.setKbId(kbId);
                favoriteMapper.insert(entity);
            }
            return true;
        }
        favoriteMapper.delete(Wrappers.<KbFavorite>lambdaQuery()
                .eq(KbFavorite::getUserId, userId)
                .eq(KbFavorite::getKbId, kbId));
        return false;
    }

    @Override
    public StatsVO stats(LoginUser viewer) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.lambdaQuery();
        accessService.applyVisibleScope(wrapper, viewer, null);
        List<KnowledgeBase> list = list(wrapper);

        long kbTotal = list.size();
        long kbPublic = list.stream().filter(kb -> KnowledgeBase.VISIBILITY_PUBLIC.equals(kb.getVisibility())).count();
        long kbOrg = list.stream().filter(kb -> KnowledgeBase.VISIBILITY_ORG.equals(kb.getVisibility())).count();
        long kbPrivate = list.stream().filter(kb -> KnowledgeBase.VISIBILITY_PRIVATE.equals(kb.getVisibility())).count();
        long publicDocTotal = list.stream().filter(KnowledgeBase::isPublic)
                .mapToLong(kb -> kb.getDocCount() == null ? 0 : kb.getDocCount()).sum();
        long docTotal = list.stream().mapToLong(kb -> kb.getDocCount() == null ? 0 : kb.getDocCount()).sum();

        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        List<KnowledgeBase> thisMonth = list.stream()
                .filter(kb -> kb.getCreatedAt() != null && !kb.getCreatedAt().isBefore(monthStart))
                .toList();
        long kbDelta = thisMonth.size();
        long docDelta = thisMonth.stream()
                .mapToLong(kb -> kb.getDocCount() == null ? 0 : kb.getDocCount()).sum();

        return new StatsVO(kbTotal, kbPublic, kbOrg, kbPrivate, publicDocTotal, docTotal, kbDelta, docDelta);
    }

    @Override
    public KnowledgeBase findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return getOne(Wrappers.<KnowledgeBase>lambdaQuery().eq(KnowledgeBase::getSlug, slug).last("LIMIT 1"), false);
    }

    @Override
    public KnowledgeBase requireBySlug(Long tenantId, String slug) {
        if (tenantId == null || slug == null || slug.isBlank()) {
            // 组织上下文缺失时绝不退回全局 slug 查找：那会把别的组织的同名库当成答案
            throw BizException.notFound("知识库不存在");
        }
        KnowledgeBase kb = getOne(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getTenantId, tenantId)
                .eq(KnowledgeBase::getSlug, slug)
                .last("LIMIT 1"), false);
        if (kb == null) {
            throw BizException.notFound("知识库不存在");
        }
        return kb;
    }

    @Override
    public List<KbVO> visibleBySlug(String slug, LoginUser viewer) {
        if (slug == null || slug.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(KnowledgeBase::getSlug, slug);
        if (viewer == null || !viewer.isAdmin()) {
            // 命中口径 = 已发布 ∪ 可读。已发布那一支让老链接对任何访问者（含游客）都能落到已分享的库上；
            // 可读那一支照顾成员打开自己的库。超管不过滤，与 applyVisibleScope 的超管分支同口径。
            wrapper.and(w -> w.apply(SharePublicationSupport.publishedExists(null), TimeUtil.now())
                    .or(o -> accessService.applyVisibleScope(o, viewer, null)));
        }
        List<KnowledgeBase> kbs = list(wrapper);
        if (kbs.isEmpty()) {
            return List.of();
        }
        Map<Long, Tenant> tenants = tenantFacts(kbs);
        Map<Long, String> ownerNames = ownerNames(kbs);
        return kbs.stream()
                .map(kb -> KbVO.from(kb, false, ownerNames.get(kb.getOwnerId()), tenants.get(kb.getTenantId())))
                .toList();
    }

    /** 单个库所属的组织（slug + 名称一起给）。列表请用 {@link #tenantFacts}，这里就是字面意义的一行一查。 */
    private Tenant tenantOf(KnowledgeBase kb) {
        return kb.getTenantId() == null ? null : tenantService.getById(kb.getTenantId());
    }

    /** 一批库所属的组织：URL 已经带组织段、卡片还要标组织名，逐行查会变成 N+1。 */
    private Map<Long, Tenant> tenantFacts(Collection<KnowledgeBase> kbs) {
        List<Long> tenantIds = kbs.stream().map(KnowledgeBase::getTenantId).filter(Objects::nonNull)
                .distinct().toList();
        Map<Long, Tenant> tenants = new HashMap<>();
        if (!tenantIds.isEmpty()) {
            tenantService.listByIds(tenantIds).forEach(tenant -> tenants.put(tenant.getId(), tenant));
        }
        return tenants;
    }

    @Override
    public void touch(Long kbId) {
        KnowledgeBase kb = getById(kbId);
        if (kb == null) {
            return;
        }
        long count = countDocsSafely(kb);
        KnowledgeBase update = new KnowledgeBase();
        update.setId(kbId);
        update.setDocCount((int) count);
        updateById(update);
    }

    @Override
    public int refreshAllDocCounts() {
        int changed = 0;
        for (KnowledgeBase kb : list()) {
            long count = countDocsSafely(kb);
            if (kb.getDocCount() == null || kb.getDocCount() != count) {
                KnowledgeBase update = new KnowledgeBase();
                update.setId(kb.getId());
                update.setDocCount((int) count);
                updateById(update);
                changed++;
            }
        }
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearRosterOf(Long tenantId, Long userId) {        if (tenantId == null || userId == null) {
            return;
        }
        List<Long> kbIds = list(Wrappers.<KnowledgeBase>lambdaQuery()
                .select(KnowledgeBase::getId)
                .eq(KnowledgeBase::getTenantId, tenantId))
                .stream().map(KnowledgeBase::getId).toList();
        if (kbIds.isEmpty()) {
            return;
        }
        // 判定层本就不认组织外的名单行（§9），物理删除是为了「重新加入」不复活旧授权
        kbMemberMapper.delete(Wrappers.<KbMember>lambdaQuery()
                .eq(KbMember::getUserId, userId)
                .in(KbMember::getKbId, kbIds));
    }

    @Override
    public void purgeFavoritesOf(Long userId) {
        favoriteMapper.delete(Wrappers.<KbFavorite>lambdaQuery().eq(KbFavorite::getUserId, userId));
    }

    // ------------------------------------------------------------------ 内部实现

    private void purge(KnowledgeBase kb) {
        removeById(kb.getId());
        favoriteMapper.delete(Wrappers.<KbFavorite>lambdaQuery().eq(KbFavorite::getKbId, kb.getId()));
        kbMemberMapper.delete(Wrappers.<KbMember>lambdaQuery().eq(KbMember::getKbId, kb.getId()));
        eventPublisher.publishEvent(new KnowledgeBaseDeletedEvent(kb.getId()));
        deleteRootWhenCommitted(kb.getStorageKey());
        log.info("删除知识库 id={} slug={} storageKey={}", kb.getId(), kb.getSlug(), kb.getStorageKey());
    }

    /**
     * 提交之后才删目录。
     *
     * <p>磁盘是内容的唯一权威，所以「库还在、目录没了」是本架构里真正不可恢复的那种失败：
     * 先删盘再写库时，同一事务里后面的任何一步（审计、分享级联）失败都会把库记录回滚回来，
     * 留着一个人人可见、点开却是空的内容废墟。反过来做只有一种后果 —— 提交成功后进程被杀，
     * 留下一个没有库记录的孤儿目录，它谁都读不到，删掉即可。</p>
     */
    private void deleteRootWhenCommitted(String storageKey) {
        if (storageKey == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            vaultFileService.deleteRoot(storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                vaultFileService.deleteRoot(storageKey);
            }
        });
    }

    private long countDocsSafely(KnowledgeBase kb) {
        try {
            Path root = vaultFileService.rootOf(kb.getStorageKey());
            return vaultFileService.countDocs(root);
        } catch (Exception e) {
            log.warn("统计文档数失败 kbId={}", kb.getId(), e);
            return kb.getDocCount() == null ? 0 : kb.getDocCount();
        }
    }

    private boolean isFavorited(LoginUser viewer, Long kbId) {
        if (viewer == null || kbId == null) {
            return false;
        }
        return favoriteMapper.selectCount(Wrappers.<KbFavorite>lambdaQuery()
                .eq(KbFavorite::getUserId, viewer.id())
                .eq(KbFavorite::getKbId, kbId)) > 0;
    }

    private Set<Long> favoriteIds(LoginUser viewer) {
        if (viewer == null) {
            return Set.of();
        }
        return favoriteMapper.selectList(Wrappers.<KbFavorite>lambdaQuery().eq(KbFavorite::getUserId, viewer.id()))
                .stream().map(KbFavorite::getKbId).collect(Collectors.toSet());
    }

    private Map<Long, String> ownerNames(List<KnowledgeBase> list) {
        Set<Long> ids = list.stream().map(KnowledgeBase::getOwnerId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userService.listByIds(ids)) {
            names.put(user.getId(), user.getDisplayName() == null || user.getDisplayName().isBlank()
                    ? user.getUsername() : user.getDisplayName());
        }
        return names;
    }

    private String ownerName(Long ownerId) {
        User user = userService.getById(ownerId);
        if (user == null) {
            return null;
        }
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? user.getUsername() : user.getDisplayName();
    }

    private void applySort(LambdaQueryWrapper<KnowledgeBase> wrapper, String sort) {
        String value = sort == null ? "updated" : sort.toLowerCase();
        switch (value) {
            case "created" -> wrapper.orderByDesc(KnowledgeBase::getCreatedAt);
            case "name" -> wrapper.orderByAsc(KnowledgeBase::getName);
            default -> wrapper.orderByDesc(KnowledgeBase::getUpdatedAt);
        }
    }

    /**
     * 建库落点：留空即个人组织（v1「一人一库」的兼容入口），指定组织则由 {@code KB_CREATE} 的成员门
     * 裁决；非成员拿到 404，不确认组织存在（§2.5）。
     */
    private Tenant resolveCreateTenant(String tenantSlug, LoginUser actor) {
        return tenantSlug == null || tenantSlug.isBlank()
                ? tenantService.ensurePersonalTenant(actor.id())
                : tenantService.requireBySlug(tenantSlug);
    }

    private String generateSlug(Long tenantId, String name) {
        String base = SlugUtil.slugify(name);
        String candidate = base;
        int index = 2;
        while (existsSlug(tenantId, candidate)) {
            candidate = SlugUtil.withSuffix(base, index++);
        }
        return candidate;
    }

    /** slug 自 v2 起只在组织内唯一（磁盘目录由 storage_key 定位，跨组织重名不再有歧义）。 */
    private boolean existsSlug(Long tenantId, String slug) {
        return count(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getTenantId, tenantId)
                .eq(KnowledgeBase::getSlug, slug)) > 0;
    }

    /**
     * 建库时的写轴档位：省略即 {@code owner_only}（规范 §2.3 的保守默认）。
     *
     * <p>值域判定在 {@link KnowledgeBase#normalizeMaintainScope}，与改档位的接口共用一份规则；
     * 这里只是补上「新建时可以省略」这一条默认值策略。</p>
     */
    private String normalizeMaintainScope(String maintainScope) {
        return KnowledgeBase.normalizeMaintainScope(maintainScope, KnowledgeBase.MAINTAIN_OWNER_ONLY);
    }

    private String normalizeVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            // 规范 §2.2：'org' 是新默认值。个人组织只有一名成员，落到 org 与 private 等价，
            // 因此这条改动不会把任何人的库放大
            return KnowledgeBase.VISIBILITY_ORG;
        }
        String value = visibility.trim().toLowerCase();
        if (!KnowledgeBase.VISIBILITY_PUBLIC.equals(value)
                && !KnowledgeBase.VISIBILITY_ORG.equals(value)
                && !KnowledgeBase.VISIBILITY_PRIVATE.equals(value)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "可见性只能是 public / org / private");
        }
        return value;
    }

    private String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String value = tag.trim();
            if (value.length() > MAX_TAG_LENGTH) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "单个标签长度不能超过 " + MAX_TAG_LENGTH + " 个字符");
            }
            if (!cleaned.contains(value)) {
                cleaned.add(value);
            }
        }
        if (cleaned.size() > MAX_TAG_COUNT) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "标签数量不能超过 " + MAX_TAG_COUNT + " 个");
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 把请求里的 Git 绑定落到实体上。
     *
     * <p>URL 里若内联了 {@code user:token@}，入库前剥掉：凭证只走 {@code git_username} / {@code git_token}
     * 两列，留在地址里既会进日志，也会随绑定信息回显给前端。令牌本身落 AES-GCM 密文。</p>
     */
    private void applyGitBinding(KnowledgeBase kb, CreateRequest request) {
        String url = sanitizeGitUrl(request.gitUrl());
        if (url == null) {
            throw BizException.of(ErrorCode.GIT_PARAM_INVALID, "云端知识库必须填写 Git 仓库地址");
        }
        kb.setGitUrl(url);
        kb.setGitBranch(branchOrDefault(request.gitBranch()));
        kb.setGitUsername(trimToNull(request.gitUsername()));
        kb.setGitToken(CryptoUtil.encrypt(trimToNull(request.gitToken()), properties.getJwtSecret()));
    }

    /** 取回明文令牌。密文损坏或密钥已变更时抛 500 —— 那是需要人重新填一次令牌的状态，不能静默当没配。 */
    private String decryptToken(KnowledgeBase kb) {
        return CryptoUtil.decrypt(kb.getGitToken(), properties.getJwtSecret());
    }

    private String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return KnowledgeBase.SOURCE_LOCAL;
        }
        String value = sourceType.trim().toLowerCase(Locale.ROOT);
        if (!KnowledgeBase.SOURCE_LOCAL.equals(value) && !KnowledgeBase.SOURCE_GIT.equals(value)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "来源类型只能是 local / git");
        }
        return value;
    }

    private static String branchOrDefault(String branch) {
        String value = trimToNull(branch);
        return value == null ? "main" : value;
    }

    /**
     * 剥掉 URL 里内联的凭据。
     *
     * <p>只在 scheme 之后的 authority 段里找 {@code @}，所以路径里出现 {@code @}（如 {@code git@github.com}
     * 这类 SSH 写法被误填进 HTTPS 字段）不会被当成凭据切断。</p>
     */
    private static String sanitizeGitUrl(String raw) {
        String url = trimToNull(raw);
        if (url == null) {
            return null;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return url;
        }
        return url.substring(0, start) + authority.substring(at + 1) + url.substring(end);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
