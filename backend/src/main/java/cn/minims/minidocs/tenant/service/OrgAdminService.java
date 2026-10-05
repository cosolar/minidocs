package cn.minims.minidocs.tenant.service;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KnowledgeBaseMapper;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.tenant.dto.TenantDtos.AdminOrgVO;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 平台侧组织治理：组织列表与停用 / 恢复（规范 §4.4）。
 *
 * <p>与 {@code TenantService} 分开，是因为这里的门槛是平台角色而不是任何组织身份：
 * {@code /api/platform/**} 由 {@code AdminInterceptor} 把关，判定不经过
 * {@code AccessService.requireTenant}（那会让组织 OWNER 看起来有权停用组织自己）。
 * 停用的效果不在这里实现，而在 {@code AccessService} 的那条 {@code status='active'} 条件上 ——
 * 停用即整库下线，磁盘一个字节都不动，恢复即原样回来。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgAdminService {

    private static final List<String> VALID_STATUS = List.of(Tenant.STATUS_ACTIVE, Tenant.STATUS_DISABLED);

    private final TenantService tenantService;
    private final TenantMemberMapper memberMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final UserService userService;
    private final AuditService auditService;

    public PageResult<AdminOrgVO> page(String keyword, String type, String status, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<Tenant> wrapper = Wrappers.lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase(Locale.ROOT);
            wrapper.and(w -> w.like(Tenant::getSlug, kw).or().like(Tenant::getName, keyword.trim()));
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Tenant::getType, type.trim().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Tenant::getStatus, status.trim());
        }
        wrapper.orderByAsc(Tenant::getId);
        Page<Tenant> result = tenantService.page(new Page<>(current, pageSize), wrapper);
        List<AdminOrgVO> list = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminOrgVO changeStatus(String slug, String status, LoginUser actor) {
        if (status == null || !VALID_STATUS.contains(status)) {
            throw BizException.param("非法的组织状态");
        }
        Tenant tenant = tenantService.findBySlug(slug);
        if (tenant == null) {
            throw BizException.notFound("组织不存在");
        }
        if (tenant.isPersonal() && !Tenant.STATUS_ACTIVE.equals(status)) {
            // 个人组织的可用性只在 users.status 表达一次；在这里再关一道会出现
            // 「账号已放行却看不见自己的库」这种两处状态不一致的坑（与 ensurePersonalTenant 同口径）
            throw BizException.param("个人组织不可停用，请改用账号禁用");
        }
        if (Objects.equals(tenant.getStatus(), status)) {
            return toVO(tenant);
        }
        tenantService.update(Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, tenant.getId())
                .set(Tenant::getStatus, status)
                .set(Tenant::getUpdatedAt, TimeUtil.now()));
        tenant.setStatus(status);
        auditService.record(KbAction.TENANT_SUSPEND, actor.id(), tenant.getId(), null, null,
                Map.of("slug", tenant.getSlug(), "status", status));
        log.info("平台{}组织 id={} slug={} by={}", Tenant.STATUS_DISABLED.equals(status) ? "停用" : "恢复",
                tenant.getId(), tenant.getSlug(), actor.username());
        return toVO(tenant);
    }

    private AdminOrgVO toVO(Tenant tenant) {
        String ownerName = null;
        if (tenant.getOwnerUserId() != null) {
            var owner = userService.getById(tenant.getOwnerUserId());
            ownerName = owner == null ? "(已注销)" : owner.getUsername();
        }
        return new AdminOrgVO(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getType(),
                tenant.getStatus(), tenant.getOwnerUserId(), ownerName,
                memberMapper.selectCount(Wrappers.<TenantMember>lambdaQuery()
                        .eq(TenantMember::getTenantId, tenant.getId())),
                kbMapper.selectCount(Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getTenantId, tenant.getId())),
                tenant.getCreatedAt(), TimeUtil.display(tenant.getCreatedAt()));
    }
}
