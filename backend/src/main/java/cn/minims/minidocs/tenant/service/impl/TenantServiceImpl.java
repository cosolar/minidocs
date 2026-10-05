package cn.minims.minidocs.tenant.service.impl;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.SlugUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KnowledgeBaseMapper;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.tenant.dto.TenantDtos.CreateOrgRequest;
import cn.minims.minidocs.tenant.dto.TenantDtos.DiscoverVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.OrgVO;
import cn.minims.minidocs.tenant.dto.TenantDtos.TenantBrief;
import cn.minims.minidocs.tenant.dto.TenantDtos.UpdateOrgRequest;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl extends ServiceImpl<TenantMapper, Tenant> implements TenantService {

    private static final int MAX_SLUG_LENGTH = 48;

    private final TenantMemberMapper memberMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final UserService userService;
    private final AccessService accessService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Tenant ensurePersonalTenant(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("查询个人组织需要用户 ID");
        }
        Tenant existing = personalTenant(userId);
        if (existing != null) {
            ensureOwnerMembership(existing.getId(), userId);
            return existing;
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        Tenant tenant = new Tenant();
        tenant.setSlug(generatePersonalSlug(user.getUsername()));
        tenant.setName(displayName(user));
        tenant.setType(Tenant.TYPE_PERSONAL);
        tenant.setOwnerUserId(userId);
        tenant.setJoinPolicy(Tenant.JOIN_INVITE_ONLY);
        tenant.setDiscoverable(false);
        // 状态恒为 active：账号可用性只在 users.status 表达一次，个人组织不重复表达，
        // 否则「审批通过」要同时改两处，漏一条路径就会出现「用户已放行却看不见自己的库」
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        save(tenant);
        ensureOwnerMembership(tenant.getId(), userId);
        log.info("已初始化个人组织 id={} slug={} owner={}", tenant.getId(), tenant.getSlug(), user.getUsername());
        return tenant;
    }

    @Override
    public Tenant findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return getOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getSlug, slug)
                .last("LIMIT 1"), false);
    }

    @Override
    public Tenant requireBySlug(String slug) {
        Tenant tenant = slug == null ? null : findBySlug(slug.trim().toLowerCase(Locale.ROOT));
        if (tenant == null) {
            throw BizException.notFound("组织不存在");
        }
        return tenant;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrgVO createTeam(CreateOrgRequest request, LoginUser actor) {
        String name = request.name().trim();
        Tenant tenant = new Tenant();
        tenant.setSlug(resolveOrgSlug(request.slug(), name));
        tenant.setName(name);
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setOwnerUserId(actor.id());
        tenant.setJoinPolicy(request.joinPolicy() == null ? Tenant.JOIN_REQUEST : request.joinPolicy());
        tenant.setDescription(trimToNull(request.description()));
        tenant.setDiscoverable(request.discoverable() == null || request.discoverable());
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        save(tenant);

        TenantMember ownerRow = new TenantMember();
        ownerRow.setTenantId(tenant.getId());
        ownerRow.setUserId(actor.id());
        ownerRow.setRole(TenantMember.ROLE_OWNER);
        ownerRow.setJoinedFrom("bootstrap");
        memberMapper.insert(ownerRow);
        log.info("已创建组织 id={} slug={} owner={}", tenant.getId(), tenant.getSlug(), actor.username());
        return OrgVO.of(tenant, TenantMember.ROLE_OWNER,
                accessService.permissionsOfTenant(new AccessService.OrgRole(true, true, true)), 1, 0);
    }

    @Override
    public OrgVO detail(String slug, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(requireBySlug(slug), KbAction.TENANT_VIEW, actor);
        AccessService.OrgRole role = accessService.roleOf(tenant, actor);
        return OrgVO.of(tenant, roleString(role),
                accessService.permissionsOfTenant(role), countMembers(tenant.getId()),
                countVisibleKbs(tenant.getId(), actor));
    }

    /** 档位回显成角色名：只显示用，权限判定一律走动作集。 */
    private static String roleString(AccessService.OrgRole role) {
        if (!role.member()) {
            return null;
        }
        return role.owner() ? TenantMember.ROLE_OWNER : role.orgAdmin()
                ? TenantMember.ROLE_ADMIN : TenantMember.ROLE_MEMBER;
    }

    @Override
    public List<TenantBrief> myTenants(LoginUser actor) {
        List<TenantMember> rows = memberMapper.selectList(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getUserId, actor.id()));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Tenant> active = listByIds(rows.stream().map(TenantMember::getTenantId).toList()).stream()
                .filter(Tenant::isActive)
                .collect(Collectors.toMap(Tenant::getId, tenant -> tenant));
        // 个人组织置顶、其余按 slug 字典序：切换器的顺序要稳定，且老用户第一眼看到的就是自己原来的库。
        // 成员行留着但组织已停用的不入列 —— 那是一个点进去只会 404 的入口
        return rows.stream()
                .filter(row -> active.containsKey(row.getTenantId()))
                .map(row -> {
                    Tenant tenant = active.get(row.getTenantId());
                    return new TenantBrief(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getType(),
                            row.getRole(), row.isOwner(),
                            // 档位表是纯函数，这里零额外查询：切换器里的每个组织都直接带上它能做什么
                            OrgVO.actionsOf(accessService.permissionsOfTenant(
                                    AccessService.OrgRole.of(row.getRole(), true))));
                })
                .sorted(Comparator.comparing((TenantBrief brief) ->
                        Tenant.TYPE_PERSONAL.equalsIgnoreCase(brief.type()) ? 0 : 1)
                        .thenComparing(TenantBrief::slug))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrgVO update(String slug, UpdateOrgRequest request, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(requireBySlug(slug), KbAction.TENANT_RENAME, actor);
        if (request.name() != null && !request.name().isBlank()) {
            tenant.setName(request.name().trim());
        }
        if (request.description() != null) {
            tenant.setDescription(trimToNull(request.description()));
        }
        if (request.logoUrl() != null) {
            tenant.setLogoUrl(trimToNull(request.logoUrl()));
        }
        if (request.joinPolicy() != null && !request.joinPolicy().isBlank()) {
            tenant.setJoinPolicy(request.joinPolicy());
        }
        if (request.discoverable() != null) {
            tenant.setDiscoverable(!tenant.isPersonal() && request.discoverable());
        }
        persistMeta(tenant);
        AccessService.OrgRole role = accessService.roleOf(tenant, actor);
        return OrgVO.of(tenant, roleString(role), accessService.permissionsOfTenant(role),
                countMembers(tenant.getId()), countVisibleKbs(tenant.getId(), actor));
    }

    /**
     * 写回组织资料。
     *
     * <p>与 {@code ShareServiceImpl} / {@code KnowledgeBaseServiceImpl} 同一处坑：
     * {@code updateById} 跳过 null，而「清空简介」「去掉 logo」要写的正是 null，
     * 于是页面显示已清掉、刷新后还在。</p>
     */
    private void persistMeta(Tenant tenant) {
        update(Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, tenant.getId())
                .set(Tenant::getName, tenant.getName())
                .set(Tenant::getDescription, tenant.getDescription())
                .set(Tenant::getLogoUrl, tenant.getLogoUrl())
                .set(Tenant::getJoinPolicy, tenant.getJoinPolicy())
                .set(Tenant::getDiscoverable, tenant.getDiscoverable())
                .set(Tenant::getUpdatedAt, TimeUtil.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String slug, LoginUser actor) {
        Tenant tenant = accessService.requireTenant(requireBySlug(slug), KbAction.TENANT_DELETE, actor);
        if (tenant.isPersonal()) {
            throw BizException.param("个人组织不可删除");
        }
        long remaining = kbMapper.selectCount(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getTenantId, tenant.getId()));
        if (remaining > 0) {
            // 删组织要连带删磁盘目录，那是全流程里唯一不可逆的一步 —— 先把库清出去，别让一次点击顺手抹掉内容
            throw BizException.of(ErrorCode.TENANT_NOT_EMPTY,
                    "组织下仍有 " + remaining + " 个知识库，请先迁移或删除");
        }
        removeById(tenant.getId());
        memberMapper.delete(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenant.getId()));
        log.info("已删除组织 id={} slug={} by={}", tenant.getId(), tenant.getSlug(), actor.username());
    }

    @Override
    public PageResult<DiscoverVO> discover(long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 50);
        Page<Tenant> result = page(new Page<>(current, pageSize),
                // 「可被发现」的唯一读出口：只有这三项条件。要加第四条就来这里，别在调用方另起一套口径（I3）
                Wrappers.<Tenant>lambdaQuery()
                        .eq(Tenant::getType, Tenant.TYPE_TEAM)
                        .eq(Tenant::getDiscoverable, true)
                        .eq(Tenant::getStatus, Tenant.STATUS_ACTIVE)
                        .orderByAsc(Tenant::getSlug));
        List<DiscoverVO> list = result.getRecords().stream().map(this::toDiscoverVO).toList();
        return PageResult.of(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    // ------------------------------------------------------------------ 内部

    private DiscoverVO toDiscoverVO(Tenant tenant) {
        return new DiscoverVO(tenant.getSlug(), tenant.getName(), tenant.getDescription(), tenant.getLogoUrl(),
                tenant.getJoinPolicy(), countMembers(tenant.getId()),
                kbMapper.selectCount(Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getTenantId, tenant.getId())
                        .eq(KnowledgeBase::getVisibility, KnowledgeBase.VISIBILITY_PUBLIC)));
    }

    private long countMembers(Long tenantId) {
        return memberMapper.selectCount(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId));
    }

    private long countVisibleKbs(Long tenantId, LoginUser actor) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = Wrappers.<KnowledgeBase>lambdaQuery();
        accessService.applyVisibleScope(wrapper, actor, tenantId);
        return kbMapper.selectCount(wrapper);
    }

    private Tenant personalTenant(Long userId) {
        return getOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getOwnerUserId, userId)
                .eq(Tenant::getType, Tenant.TYPE_PERSONAL)
                .last("LIMIT 1"), false);
    }

    private void ensureOwnerMembership(Long tenantId, Long userId) {
        boolean present = memberMapper.selectCount(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId)
                .eq(TenantMember::getUserId, userId)) > 0;
        if (present) {
            return;
        }
        TenantMember member = new TenantMember();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setRole(TenantMember.ROLE_OWNER);
        member.setJoinedFrom("bootstrap");
        memberMapper.insert(member);
    }

    /**
     * 组织 slug：请求里给了就校验后照用，没给则从名称生成并避让重名。
     *
     * <p>两种来源处置不同是刻意的：名称生成允许 {@code slugify} 的静默清洗，用户手填的 slug 却只能报错 ——
     * slug 会进 URL 与分享链接，悄悄改掉等于给用户一条打不开的链接。</p>
     */
    private String resolveOrgSlug(String requested, String name) {
        if (requested == null || requested.isBlank()) {
            String base = SlugUtil.slugify(name);
            if (!SlugUtil.isAvailableOrgSlug(base, Tenant.PERSONAL_SLUG_PREFIX)) {
                // slugify 的兜底值 "kb" 本身就是保留字，加后缀让它可用，而不是把内部规则抛给用户
                base = SlugUtil.withSuffix(base, 2);
            }
            return uniqueSlug(base);
        }
        String slug = requested.trim().toLowerCase(Locale.ROOT);
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw BizException.param("组织标识不能超过 " + MAX_SLUG_LENGTH + " 个字符");
        }
        if (!slug.equals(SlugUtil.slugify(slug))) {
            throw BizException.param("组织标识只能由字母、数字、中文、- 或 _ 组成，且不能以它们以外字符开头结尾");
        }
        if (!SlugUtil.isAvailableOrgSlug(slug, Tenant.PERSONAL_SLUG_PREFIX)) {
            throw BizException.param("该组织标识不可用：属保留字或个人组织前缀");
        }
        if (findBySlug(slug) != null) {
            throw BizException.exists("该组织标识已被占用");
        }
        return slug;
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int index = 2;
        while (findBySlug(candidate) != null) {
            candidate = SlugUtil.withSuffix(base, index++);
        }
        return candidate;
    }

    /**
     * 个人组织 slug：{@code u-{用户名}}，与 V2 迁移的回填规则一致。
     * {@code u-} 前缀为个人组织保留，TEAM 组织不得占用（规范 §3.2）。
     */
    private String generatePersonalSlug(String username) {
        String base = Tenant.PERSONAL_SLUG_PREFIX
                + SlugUtil.slugify(username == null ? "user" : username.toLowerCase(Locale.ROOT));
        String candidate = base;
        int index = 2;
        while (count(Wrappers.<Tenant>lambdaQuery().eq(Tenant::getSlug, candidate)) > 0) {
            candidate = SlugUtil.withSuffix(base, index++);
        }
        return candidate;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String displayName(User user) {
        String name = user.getDisplayName();
        return name == null || name.isBlank() ? user.getUsername() : name.trim();
    }
}
