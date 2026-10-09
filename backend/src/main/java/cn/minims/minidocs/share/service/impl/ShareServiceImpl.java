package cn.minims.minidocs.share.service.impl;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.event.DocumentChangedEvent;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.HashUtil;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.reader.model.NavMenuItem;
import cn.minims.minidocs.share.dto.ShareDtos.CreateRequest;
import cn.minims.minidocs.share.dto.ShareDtos.ShareVO;
import cn.minims.minidocs.share.dto.ShareDtos.UpdateRequest;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.share.entity.ShareViewLog;
import cn.minims.minidocs.share.mapper.ShareMapper;
import cn.minims.minidocs.share.mapper.ShareViewLogMapper;
import cn.minims.minidocs.share.service.ShareService;
import cn.minims.minidocs.share.support.ShareTokenUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private static final int MIN_PASSWORD_LENGTH = 4;
    private static final int MAX_PASSWORD_LENGTH = 64;

    /**
     * 访问计数的会话窗口（分钟）：同一访客在窗口内反复取正文只算一次访问。
     *
     * <p>见 {@link #recordView} 的口径说明。</p>
     */
    private static final int VIEW_SESSION_MINUTES = 30;
    /** 导航条放不下更多了：横向一条能滚动的菜单超过这个数就已经不好用 */
    private static final int MAX_MENU_ITEMS = 40;

    private final ShareMapper shareMapper;
    private final ShareViewLogMapper viewLogMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AccessService accessService;
    private final VaultFileService vaultFileService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareVO createOrUpdate(Long tenantId, CreateRequest request, LoginUser actor, String baseUrl) {
        KnowledgeBase kb = accessService.requireKb(locateSlug(tenantId, request.kbSlug()),
                KbAction.SHARE_CREATE, actor);
        String scope = normalizeScope(request.scope());
        String docPath = null;
        if (Share.SCOPE_DOC.equals(scope)) {
            docPath = requireExistingDoc(kb, request.docPath());
        }
        String targetKey = docPath == null ? "" : docPath;

        // 一个库的一个目标只有一条分享（uk_shares_target），与创建者无关：
        // 按 owner 查会让第二个维护者插入重复键，也会让他人撤销过的链接被悄悄复活
        Share existing = shareMapper.selectOne(Wrappers.<Share>lambdaQuery()
                .eq(Share::getKbId, kb.getId())
                .apply("IFNULL(doc_path, '') = {0}", targetKey)
                .last("LIMIT 1"));

        Share share;
        boolean created;
        // 短链是否被换过：审计里只记「换过」这个事实，绝不记 token 本身（那是外链的钥匙）
        boolean tokenChanged = false;
        if (existing == null) {
            created = true;
            share = new Share();
            share.setToken(resolveToken(request.token(), null));
            share.setOwnerId(actor.id());
            share.setKbId(kb.getId());
            share.setDocPath(docPath);
            share.setScope(scope);
            share.setViews(0);
            share.setRevoked(0);
            share.setInvalid(0);
            applyPassword(share, request.password(), request.encrypted(), null);
            share.setExpiresAt(resolveExpiresAt(request.expiresIn()));
            share.setMenuConfig(buildMenuConfig(kb, request.menu()));
            applyPortalScope(share, request.portalScope(), null);
            applyRepo(share, request.repoUrl(), request.showRepo());
            try {
                shareMapper.insert(share);
            } catch (DuplicateKeyException e) {
                // 撞 uk_shares_token：两个维护者同时用同一个自定义短链，或与并发插入的自动 token 撞了
                throw BizException.param(request.token() == null || request.token().isBlank()
                        ? "分享链接生成冲突，请重试" : "这个短链已被占用，换一个试试");
            }
        } else {
            share = existing;
            created = false;
            /*
             * 短链可以改（用户改主意了就该能改），但旧链接会当场失效：改之前界面必须确认过一次。
             * 没填 token 就是「这次不改」，沿用原来的；已撤销的记录复用时不能沿用 ——
             * 那个 token 可能已经外泄，重新生成一个。
             */
            String before = share.getToken();
            share.setToken(resolveToken(request.token(), share.revoked() ? null : before));
            if (share.revoked()) {
                share.setRevoked(0);
            }
            tokenChanged = !share.getToken().equals(before);
            share.setInvalid(0);
            applyPassword(share, request.password(), request.encrypted(), existing.getPasswordHash());
            share.setExpiresAt(resolveExpiresAt(request.expiresIn()));
            // null = 本次不动菜单（只改口令 / 有效期的入口不发这个字段），空数组 = 清掉菜单
            applyPortalScope(share, request.portalScope(), existing.getPortalScope());
            applyRepo(share, request.repoUrl(), request.showRepo());
            if (request.menu() != null) {
                share.setMenuConfig(buildMenuConfig(kb, request.menu()));
            }
            persistEditable(share);
        }
        // 分享等于对外可读，F9 要求留痕。只记 id 与开关，绝不记 token 与密码：
        // 审计页能读到什么就该和公开链接能打开什么一样少
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", created ? "shareCreate" : "shareUpdate");
        detail.put("shareId", share.getId());
        detail.put("scope", share.getScope());
        detail.put("docPath", share.getDocPath() == null ? "" : share.getDocPath());
        detail.put("encrypted", share.getPasswordHash() != null);
        detail.put("expiresAt", share.getExpiresAt() == null ? "" : TimeUtil.format(share.getExpiresAt()));
        detail.put("menuItems", parseMenu(share.getMenuConfig()).size());
        if (tokenChanged) {
            detail.put("tokenChanged", true);
        }
        auditService.record(KbAction.SHARE_CREATE, actor.id(), kb.getTenantId(), kb.getId(), null, detail);
        return toVO(share, baseUrl, kb, actor);
    }

    @Override
    public PageResult<ShareVO> page(Long tenantId, LoginUser actor, String status, String scope,
                                    long page, long size, String baseUrl) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 100);
        List<Long> visibleKbIds = visibleKbIds(tenantId, actor);
        if (visibleKbIds.isEmpty()) {
            // 一个库都看不到时不必查分享：空列表的口径与 page 查询一致
            return PageResult.of(List.of(), 0, current, pageSize);
        }

        LambdaQueryWrapper<Share> wrapper = Wrappers.<Share>lambdaQuery().in(Share::getKbId, visibleKbIds);
        if (scope != null && !scope.isBlank()) {
            wrapper.eq(Share::getScope, scope.trim());
        }
        applyStatusFilter(wrapper, status);
        wrapper.orderByDesc(Share::getCreatedAt);

        Page<Share> result = shareMapper.selectPage(new Page<>(current, pageSize), wrapper);
        Map<Long, KnowledgeBase> kbMap = new HashMap<>();
        List<ShareVO> list = result.getRecords().stream().map(share -> {
            KnowledgeBase kb = kbMap.computeIfAbsent(share.getKbId(), knowledgeBaseService::getById);
            return toVO(share, baseUrl, kb, actor);
        }).toList();
        return PageResult.of(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public ShareVO info(Long tenantId, LoginUser actor, String kbSlug, String docPath, String baseUrl) {
        Long kbId = accessService.requireKb(locateSlug(tenantId, kbSlug), KbAction.SHARE_CREATE, actor).getId();
        String targetKey = docPath == null || docPath.isBlank() ? "" : PathGuard.normalizeRelative(docPath);
        Share share = shareMapper.selectOne(Wrappers.<Share>lambdaQuery()
                .eq(Share::getKbId, kbId)
                .apply("IFNULL(doc_path, '') = {0}", targetKey)
                .last("LIMIT 1"));
        if (share == null) {
            return null;
        }
        return toVO(share, baseUrl, knowledgeBaseService.getById(kbId), actor);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareVO update(Long tenantId, String token, UpdateRequest request, LoginUser actor, String baseUrl) {
        Share share = findByToken(token);
        KnowledgeBase kb = requireKbOfShare(share, tenantId);
        accessService.requireShare(share, KbAction.SHARE_UPDATE, actor);
        if (Boolean.TRUE.equals(request.encrypted()) && share.getPasswordHash() == null
                && (request.password() == null || request.password().isBlank())) {
            throw BizException.param("请设置访问密码");
        }
        applyPassword(share, request.password(), request.encrypted(), share.getPasswordHash());
        if (request.expiresIn() != null) {
            share.setExpiresAt(resolveExpiresAt(request.expiresIn()));
        }
            applyPortalScope(share, request.portalScope(), share.getPortalScope());
            applyRepo(share, request.repoUrl(), request.showRepo());
        persistEditable(share);
        return toVO(share, baseUrl, kb, actor);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long tenantId, String token, LoginUser actor) {
        Share share = findByToken(token);
        KnowledgeBase kb = requireKbOfShare(share, tenantId);
        accessService.requireShare(share, KbAction.SHARE_REVOKE, actor);
        Share update = new Share();
        update.setId(share.getId());
        update.setRevoked(1);
        shareMapper.updateById(update);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "shareRevoke");
        detail.put("shareId", share.getId());
        detail.put("scope", share.getScope());
        detail.put("docPath", share.getDocPath() == null ? "" : share.getDocPath());
        auditService.record(KbAction.SHARE_REVOKE, actor.id(), kb.getTenantId(), share.getKbId(), null, detail);
        log.info("撤销分享 token={} actor={}", token, actor.id());
    }

    @Override
    public Share findByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return shareMapper.selectOne(Wrappers.<Share>lambdaQuery().eq(Share::getToken, token).last("LIMIT 1"));
    }

    @Override
    public boolean recordView(Share share, String ip, String userAgent) {
        String visitorHash = HashUtil.sha256Prefix(ip + "|" + (userAgent == null ? "" : userAgent), 16);

        /*
         * 一次「访问」= 一个会话，不是每一次取正文。
         *
         * <p>原先每次读取正文都 views +1，读者在这个库里翻几篇文档、或者切回上一篇，数字就跟着跳 ——
         * 产品上「访问一次知识库」就是一次，翻几篇不该算几次。可视图是分栏的：同一个读者可能一进来
         * 就点开目录里的一篇，这一篇算不算本次访问？不该由他点到了哪篇决定，所以只问「距他上一次
         * 被计数过了多久」。</p>
         *
         * <p>冷却窗口取 {@value #VIEW_SESSION_MINUTES} 分钟：够读完一篇长文、顺手点开目录里相邻几篇，
         * 又短到「下班前刷一眼、明天再看」这种间隔必然重新计一次。窗口只影响 PV；UV 由
         * {@code uk_view_dedup(share_id, visitor_hash, view_date)} 按天去重，是另一条轴。</p>
         */
        ShareViewLog last = viewLogMapper.selectOne(Wrappers.<ShareViewLog>lambdaQuery()
                .eq(ShareViewLog::getShareId, share.getId())
                .eq(ShareViewLog::getVisitorHash, visitorHash)
                .orderByDesc(ShareViewLog::getId)
                .last("LIMIT 1"));
        if (last != null && last.getCreatedAt() != null
                && last.getCreatedAt().isAfter(TimeUtil.now().minusMinutes(VIEW_SESSION_MINUTES))) {
            return false;
        }

        ShareViewLog viewLog = new ShareViewLog();
        viewLog.setShareId(share.getId());
        viewLog.setVisitorHash(visitorHash);
        viewLog.setViewDate(TimeUtil.today());
        try {
            viewLogMapper.insert(viewLog);
        } catch (DataIntegrityViolationException e) {
            // 今天已为这个访客记过：UV 不重复加，但这一天里的首次会话仍算一次 PV
            shareMapper.update(null, Wrappers.<Share>lambdaUpdate()
                    .eq(Share::getId, share.getId())
                    .setSql("views = views + 1"));
            return true;
        } catch (Exception e) {
            // 去重日志写不进去时不能继续加：视图侧紧接着要按「本次是否计数」拼累计值，
            // 两边不一致会让页面上「浏览量」比后台列表多 1，比少记一次更难解释。
            log.warn("访问记录跳过：{}", e.getMessage());
            return false;
        }

        shareMapper.update(null, Wrappers.<Share>lambdaUpdate()
                .eq(Share::getId, share.getId())
                .setSql("views = views + 1"));
        return true;
    }

    @Override
    public long countUv(Long shareId) {
        return viewLogMapper.selectCount(Wrappers.<ShareViewLog>lambdaQuery().eq(ShareViewLog::getShareId, shareId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDocumentChange(DocumentChangedEvent event) {
        List<Share> shares = shareMapper.selectList(Wrappers.<Share>lambdaQuery()
                .eq(Share::getKbId, event.kbId())
                .eq(Share::getScope, Share.SCOPE_DOC));
        for (Share share : shares) {
            String path = share.getDocPath();
            if (path == null) {
                continue;
            }
            if (event.type() == DocumentChangedEvent.Type.DELETED) {
                if (path.equals(event.oldPath()) || path.startsWith(event.oldPath() + "/")) {
                    share.setInvalid(1);
                    shareMapper.updateById(share);
                }
                continue;
            }
            String newPath = event.newPath();
            if (newPath == null) {
                continue;
            }
            if (path.equals(event.oldPath())) {
                share.setDocPath(newPath);
                shareMapper.updateById(share);
            } else if (path.startsWith(event.oldPath() + "/")) {
                share.setDocPath(newPath + path.substring(event.oldPath().length()));
                shareMapper.updateById(share);
            }
        }

        // 菜单里存的也是路径快照，同一条事件必须一起重写：否则文档改名后，分享页那条导航会
                // 指向一个已经不存在的目录，读侧只能把它剔除，用户看到的就是菜单凭空少了一项。
                // 只改路径、其余字段原样带上：作者给菜单项取的别名是他自己写的，
                // 目录改名不该把它冲掉（路径会变，那正是重写这一段的原因）。
        List<Share> withMenu = shareMapper.selectList(Wrappers.<Share>lambdaQuery()
                .eq(Share::getKbId, event.kbId())
                .isNotNull(Share::getMenuConfig));
        for (Share share : withMenu) {
            List<NavMenuItem> menu = parseMenu(share.getMenuConfig());
            if (menu.isEmpty()) {
                continue;
            }
            List<NavMenuItem> remapped = new ArrayList<>(menu.size());
            boolean changed = false;
            for (NavMenuItem item : menu) {
                String next = remapMenuPath(item.path(), event);
                if (next == null) {
                    changed = true;
                    continue;
                }
                if (!next.equals(item.path())) {
                    changed = true;
                }
                remapped.add(item.withPath(next));
            }
            if (!changed) {
                continue;
            }
            // 走 update(null, wrapper)：菜单被清空时要真的写成 null，updateById 会跳过 null 字段
            shareMapper.update(null, Wrappers.<Share>lambdaUpdate()
                    .eq(Share::getId, share.getId())
                    .set(Share::getMenuConfig, remapped.isEmpty() ? null : JsonUtil.toJson(remapped)));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBase(Long kbId) {
        shareMapper.delete(Wrappers.<Share>lambdaQuery().eq(Share::getKbId, kbId));
    }

    // ------------------------------------------------------------------ 内部实现

    /** slug → 库 id：只在本组织内解析，跨组织同名 slug 不会命中（§4.2）。 */
    private Long locateSlug(Long tenantId, String kbSlug) {
        return knowledgeBaseService.requireBySlug(tenantId, kbSlug).getId();
    }

    /**
     * 分享所属的库，并且必须属于当前组织。
     *
     * <p>别的组织的 token 在这里与「不存在」同形（§2.5）：既不给 403 承认它存在，也不去动它的
     * 撤销 / 修改判定。真正的撤销权仍由 {@code requireShare} 裁决。</p>
     */
    private KnowledgeBase requireKbOfShare(Share share, Long tenantId) {
        if (share == null) {
            throw BizException.notFound("分享不存在");
        }
        KnowledgeBase kb = knowledgeBaseService.getById(share.getKbId());
        if (kb == null || !tenantId.equals(kb.getTenantId())) {
            throw BizException.notFound("分享不存在");
        }
        return kb;
    }

    /** 这个人在该组织内看得见的库 id：分享列表的范围就是它，判定唯一出口在 AccessService。 */
    private List<Long> visibleKbIds(Long tenantId, LoginUser actor) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.<KnowledgeBase>lambdaQuery()
                .select(KnowledgeBase::getId);
        accessService.applyVisibleScope(wrapper, actor, tenantId);
        return knowledgeBaseService.list(wrapper).stream().map(KnowledgeBase::getId).toList();
    }

    private void applyStatusFilter(LambdaQueryWrapper<Share> wrapper, String status) {
        LocalDateTime now = TimeUtil.now();
        String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "active" -> wrapper.eq(Share::getRevoked, 0)
                    .eq(Share::getInvalid, 0)
                    .and(w -> w.isNull(Share::getExpiresAt).or().gt(Share::getExpiresAt, now));
            case "expired" -> wrapper.eq(Share::getRevoked, 0)
                    .eq(Share::getInvalid, 0)
                    .isNotNull(Share::getExpiresAt)
                    .le(Share::getExpiresAt, now);
            case "revoked" -> wrapper.eq(Share::getRevoked, 1);
            case "invalid" -> wrapper.eq(Share::getRevoked, 0).eq(Share::getInvalid, 1);
            default -> {
                // 不筛选
            }
        }
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return Share.SCOPE_KB;
        }
        String value = scope.trim().toLowerCase(Locale.ROOT);
        if (!Share.SCOPE_KB.equals(value) && !Share.SCOPE_DOC.equals(value)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "范围只能是 kb 或 doc");
        }
        return value;
    }

    private String requireExistingDoc(KnowledgeBase kb, String docPath) {
        if (docPath == null || docPath.isBlank()) {
            throw BizException.param("单篇分享必须指定文档");
        }
        String normalized = PathGuard.normalizeRelative(docPath);
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        if (!vaultFileService.exists(root, normalized)) {
            throw BizException.notFound("文档不存在：" + normalized);
        }
        return normalized;
    }

    /**
     * 写回分享的可编辑列。
     *
     * <p>刻意不用 {@code updateById}：MyBatis-Plus 默认跳过 null 字段，而 {@code password_hash=null}
     * 就是「取消口令」、{@code expires_at=null} 就是「永久有效」—— 跳过等于这两个动作从未发生，
     * 只会以「列表显示已关闭、链接仍然要口令」的形式露出来，而且口令是旧的那个。</p>
     */
    private void persistEditable(Share share) {
        shareMapper.update(null, Wrappers.<Share>lambdaUpdate()
                .eq(Share::getId, share.getId())
                .set(Share::getToken, share.getToken())
                .set(Share::getPasswordHash, share.getPasswordHash())
                .set(Share::getExpiresAt, share.getExpiresAt())
                .set(Share::getRevoked, share.getRevoked())
                .set(Share::getInvalid, share.getInvalid())
                .set(Share::getMenuConfig, share.getMenuConfig())
                // 门户曝光范围：与 menu / token 同一个持久化出口，创建与更新两条路径一次覆盖
                .set(Share::getPortalScope, share.getPortalScope() == null ? Share.PORTAL_ALL : share.getPortalScope())
                .set(Share::getRepoUrl, share.getRepoUrl())
                .set(Share::getShowRepo, share.getShowRepo() == null ? 0 : share.getShowRepo())
                .set(Share::getUpdatedAt, TimeUtil.now()));
    }

    /**
     * 门户曝光范围。
     *
     * <p>null = 不动现有值：更新时作者没碰这一项就保持原样，与 menu / token 的处理同口径。
     * 创建时传 null 落 anonymous，与迁移默认值一致，所以老前端不带这个字段也不会出错。</p>
     *
     * <p>只接受三个已知值。DTO 上已有 @Pattern 兜着，这里再挡一层是为了将来新增调用点
     * 绕过 DTO 时不至于把脏值写进库 —— 而脏值会被 COALESCE 悄悄当成 anonymous，
     * 也就是「作者以为收窄了、实际是最宽的一档」。</p>
     */
    private void applyPortalScope(Share share, String value, String current) {
        if (Share.PORTAL_ALL.equals(value) || Share.PORTAL_MEMBER.equals(value)
                || Share.PORTAL_MAINTAINER.equals(value)) {
            share.setPortalScope(value);
            return;
        }
        share.setPortalScope(current == null ? Share.PORTAL_ALL : current);
    }

    /**
     * 开源仓库入口：地址与显示开关。
     *
     * <p>地址在 DTO 上已用 {@code @Pattern} 挡掉非 http/https 协议，这里再做一次
     * 归一（去空白）。两道看着重复：注解挡的是「协议不对」，这里挡的是「空白 /
     * 超长 / 拼错」这类脏值，两边失败的原因不同，错误信息也不同。</p>
     *
     * <p>{@code showRepo} 只在地址非空时允许为真：配了开关但没地址时按钮无处可去，
     * 而「按钮在但不跳转」是最容易被当成 bug 的那种状态。</p>
     */
    private void applyRepo(Share share, String repoUrl, Boolean showRepo) {
        String url = repoUrl == null ? null : repoUrl.trim();
        share.setRepoUrl(url == null || url.isEmpty() ? null : url);
        boolean want = Boolean.TRUE.equals(showRepo) && share.getRepoUrl() != null;
        share.setShowRepo(want ? 1 : 0);
    }

    private void applyPassword(Share share, String password, Boolean encrypted, String currentHash) {
        if (Boolean.FALSE.equals(encrypted)) {
            share.setPasswordHash(null);
            return;
        }
        if (password != null && !password.isBlank()) {
            if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
                throw BizException.param("访问密码长度需在 " + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " 之间");
            }
            share.setPasswordHash(passwordEncoder.encode(password));
            return;
        }
        if (currentHash != null) {
            share.setPasswordHash(currentHash);
        }
    }

    private LocalDateTime resolveExpiresAt(String expiresIn) {
        if (expiresIn == null || expiresIn.isBlank()) {
            return null;
        }
        String value = expiresIn.trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = TimeUtil.now();
        return switch (value) {
            case "1", "1d" -> now.plusDays(1);
            case "7", "7d" -> now.plusDays(7);
            case "30", "30d" -> now.plusDays(30);
            case "forever", "permanent", "0" -> null;
            default -> throw BizException.param("有效期只能是 1d / 7d / 30d / forever");
        };
    }

    private String expiresInLabel(LocalDateTime expiresAt) {
        if (expiresAt == null) {
            return "forever";
        }
        long days = java.time.Duration.between(TimeUtil.now(), expiresAt).toDays();
        if (days <= 1) {
            return "1d";
        }
        if (days <= 7) {
            return "7d";
        }
        return "30d";
    }

    private String generateUniqueToken() {
        for (int i = 0; i < 5; i++) {
            String token = ShareTokenUtil.generate();
            if (shareMapper.selectCount(Wrappers.<Share>lambdaQuery().eq(Share::getToken, token)) == 0) {
                return token;
            }
        }
        throw BizException.of(ErrorCode.SERVER_ERROR, "分享 token 生成失败");
    }

    /**
     * 定下这一条分享的 token。
     *
     * <p>三种情况：用户没填 → 沿用 {@code current}（{@code current} 为 null 时才自动生成，
     * 那是「这条分享刚创建」）；填了且与当前相同 → 原样；填了个新的 → 校验后<b>改掉</b>它
     * （旧链接随即失效，调用方负责在界面上说清这件事并让用户确认）。</p>
     *
     * <p>查重是<b>全局</b>的，不按组织隔离：token 是地址里的唯一寻址手段，
     * {@code /share/{token}} 这一层没有组织段，两边撞了就有一条打不开。
     * 并发下最终由 {@code uk_shares_token} 与 {@code insert} 的重复键异常兜底。</p>
     *
     * @param custom  用户填的自定义短链（原始值，未规整）
     * @param current 这条分享当前的 token；为 null 表示「还没有，按新建处理」
     */
    private String resolveToken(String custom, String current) {
        String token = ShareTokenUtil.normalizeCustom(custom);
        if (token == null) {
            return current != null ? current : generateUniqueToken();
        }
        if (!ShareTokenUtil.isValidCustom(token)) {
            throw BizException.param("短链只能用字母、数字、下划线和连字符，长度 "
                    + ShareTokenUtil.CUSTOM_MIN_LENGTH + "-" + ShareTokenUtil.CUSTOM_MAX_LENGTH + " 位");
        }
        if (token.equals(current)) {
            return current;
        }
        if (shareMapper.selectCount(Wrappers.<Share>lambdaQuery().eq(Share::getToken, token)) > 0) {
            throw BizException.param("这个短链已被占用，换一个试试");
        }
        return token;
    }

    /**
     * 出参装配。{@code actor} 只用来问一句「这条他能不能治理」，判定本身在裁决层（§2.6）。
     */
    private ShareVO toVO(Share share, String baseUrl, KnowledgeBase kb, LoginUser actor) {
        String url = baseUrl == null ? null : baseUrl + "/share/" + share.getToken();
        String docName = share.getDocPath() == null ? null
                : cn.minims.minidocs.common.util.FileNameUtil.stripMarkdownExt(
                        PathGuard.fileNameOf(share.getDocPath()));
        return ShareVO.of(share, url, kb, docName, countUv(share.getId()), expiresInLabel(share.getExpiresAt()),
                accessService.canGovernShare(share, kb, actor), menuWithNames(parseMenu(share.getMenuConfig())));
    }

    // ------------------------------------------------------------------ 导航菜单

    /**
     * 导航菜单入库前的清洗：逐项归一化路径、按 vault 现状定类型、去重、限量，最后序列化成 JSON 数组。
     *
     * <p>返回 null 表示「没有菜单」—— 空清单与缺列在语义上是一回事，存 null 让读侧只有一种判空口径。</p>
     *
     * <p>类型以磁盘为准而不是听请求的：前端勾选那一刻与实际保存之间，目录可能已经被改成文档，
     * 存下一个点不开的类型只会在分享页露出一个空入口。</p>
     */
    private String buildMenuConfig(KnowledgeBase kb, List<NavMenuItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        Path root = vaultFileService.rootOf(kb.getStorageKey());
        Map<String, NavMenuItem> unique = new LinkedHashMap<>();
        for (NavMenuItem item : items) {
            if (item == null || item.path() == null || item.path().isBlank()) {
                continue;
            }
            String path;
            try {
                path = PathGuard.normalizeRelative(item.path());
            } catch (RuntimeException e) {
                continue;
            }
            String type = resolveMenuType(root, path);
            if (type == null) {
                continue;
            }
            unique.putIfAbsent(type + ":" + path, NavMenuItem.of(type, path, item.alias(), item.icon()));
            if (unique.size() >= MAX_MENU_ITEMS) {
                break;
            }
        }
        return unique.isEmpty() ? null : JsonUtil.toJson(List.copyOf(unique.values()));
    }

    /** 目录优先于文档判定：目录本身也「存在」，先判文档会把目录认成文档。 */
    private String resolveMenuType(Path root, String path) {
        if (vaultFileService.isDirectory(root, path)) {
            return NavMenuItem.TYPE_DIR;
        }
        return vaultFileService.exists(root, path) ? NavMenuItem.TYPE_DOC : null;
    }

    private List<NavMenuItem> parseMenu(String json) {
        return JsonUtil.readList(json, NavMenuItem.class);
    }

    /**
     * 补显示名。
     *
     * <p>推导名每次由路径末段算，改名后不会留下一份旧名字。<b>别名不在这里合成</b>：
     * {@code name} 必须保持「文件系统里叫什么」的原义，配置器靠它显示参考列 ——
     * 若在这里就合并成生效名，作者打开配置器看到的是自己取的别名，
     * 「原始名 → 别名」的对照就没了，等于别名把原名藏了起来。
     * 生效名的合并放在顶栏渲染那一处（它只需要一个名字）。</p>
     */
    private List<NavMenuItem> menuWithNames(List<NavMenuItem> items) {
        return items.stream()
                .map(item -> item.withName(FileNameUtil.stripMarkdownExt(PathGuard.fileNameOf(item.path()))))
                .toList();
    }

    /**
     * 文件重命名 / 移动后重写菜单里的路径。
     *
     * <p>菜单存的是路径快照，不跟着改的话，分享页那条导航就会指向一个已经不存在的目录。规则与
     * {@code doc_path} 完全一致：命中即替换，落在子树下做前缀替换，被删除的整项剔除。</p>
     */
    private String remapMenuPath(String path, DocumentChangedEvent event) {
        if (path == null) {
            return null;
        }
        if (event.type() == DocumentChangedEvent.Type.DELETED) {
            return path.equals(event.oldPath()) || path.startsWith(event.oldPath() + "/") ? null : path;
        }
        String newPath = event.newPath();
        if (newPath == null) {
            return path;
        }
        if (path.equals(event.oldPath())) {
            return newPath;
        }
        if (path.startsWith(event.oldPath() + "/")) {
            return newPath + path.substring(event.oldPath().length());
        }
        return path;
    }
}
