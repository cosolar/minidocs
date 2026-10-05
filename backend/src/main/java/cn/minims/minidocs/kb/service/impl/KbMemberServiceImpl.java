package cn.minims.minidocs.kb.service.impl;

import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.kb.dto.KbDtos.RosterEntryVO;
import cn.minims.minidocs.kb.dto.KbDtos.RosterGrantRequest;
import cn.minims.minidocs.kb.entity.KbMember;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbMemberMapper;
import cn.minims.minidocs.kb.mapper.KnowledgeBaseMapper;
import cn.minims.minidocs.kb.service.KbMemberService;
import cn.minims.minidocs.permission.AccessService;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbMemberServiceImpl implements KbMemberService {

    private final KbMemberMapper rosterMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final TenantMemberMapper orgMemberMapper;
    private final UserService userService;
    private final AccessService accessService;
    private final AuditService auditService;

    @Override
    public List<RosterEntryVO> list(Long kbId, LoginUser actor) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.KB_VIEW, actor);
        return rosterMapper.selectList(Wrappers.<KbMember>lambdaQuery()
                        .eq(KbMember::getKbId, kb.getId())
                        .orderByAsc(KbMember::getCreatedAt))
                .stream()
                .map(row -> toVO(kb, row))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RosterEntryVO grant(Long kbId, RosterGrantRequest request, LoginUser actor) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.KB_MEMBER_MANAGE, actor);
        if (request.role() == null || request.role().isBlank()) {
            // 撤销是删除而不是改角色：留一行「无角色」会让「在名单里」这件事本身失去含义
            int removed = rosterMapper.delete(Wrappers.<KbMember>lambdaQuery()
                    .eq(KbMember::getKbId, kb.getId())
                    .eq(KbMember::getUserId, request.userId()));
            if (removed > 0) {
                audit(actor, kb, Map.of("op", "rosterRevoke", "userId", request.userId()));
            }
            return null;
        }
        String role = normalizeRole(request.role());
        requireOrgMember(kb, request.userId());
        KbMember row = rosterMapper.selectOne(Wrappers.<KbMember>lambdaQuery()
                .eq(KbMember::getKbId, kb.getId())
                .eq(KbMember::getUserId, request.userId())
                .last("LIMIT 1"));
        if (row == null) {
            row = new KbMember();
            row.setKbId(kb.getId());
            row.setUserId(request.userId());
            row.setRole(role);
            row.setGrantedBy(actor.id());
            rosterMapper.insert(row);
        } else {
            row.setRole(role);
            row.setGrantedBy(actor.id());
            rosterMapper.updateById(row);
        }
        audit(actor, kb, Map.of("op", "rosterGrant", "userId", request.userId(), "role", role));
        log.info("维护名单已更新 kb={} user={} role={} by={}", kb.getId(), request.userId(), role, actor.username());
        return toVO(kb, row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String updateMaintainScope(Long kbId, String scope, LoginUser actor) {
        KnowledgeBase kb = accessService.requireKb(kbId, KbAction.KB_MEMBER_MANAGE, actor);
        String value = normalizeScope(scope);
        if (value.equals(kb.getMaintainScope())) {
            return value;
        }
        // 只更新这一列：整行 updateById 会把并发改名一起覆盖掉
        kbMapper.update(null, Wrappers.<KnowledgeBase>lambdaUpdate()
                .eq(KnowledgeBase::getId, kb.getId())
                .set(KnowledgeBase::getMaintainScope, value));
        // 档位决定「谁能写这个库」，改档位即改权限，必须留痕（F9）
        audit(actor, kb, Map.of("op", "maintainScope", "from", String.valueOf(kb.getMaintainScope()),
                "to", value));
        return value;
    }

    // ------------------------------------------------------------------ 内部

    private void audit(LoginUser actor, KnowledgeBase kb, Map<String, Object> detail) {
        auditService.record(KbAction.KB_MEMBER_MANAGE, actor.id(), kb.getTenantId(), kb.getId(), null, detail);
    }

    private RosterEntryVO toVO(KnowledgeBase kb, KbMember row) {
        User user = userService.getById(row.getUserId());
        return new RosterEntryVO(row.getUserId(), user == null ? "(已注销)" : user.getUsername(),
                user == null ? null : user.getDisplayName(), row.getRole(), row.getGrantedBy(),
                orgMember(kb.getTenantId(), row.getUserId()) != null, row.getCreatedAt());
    }

    /** 名单只在组织内生效（§9）：写入前先挡住「授权给组织外的人」这种静默无效的操作。 */
    private void requireOrgMember(KnowledgeBase kb, Long userId) {
        if (orgMember(kb.getTenantId(), userId) == null) {
            throw BizException.param("该用户不是本库所属组织的成员；请先把他加入组织再授权");
        }
    }

    private TenantMember orgMember(Long tenantId, Long userId) {
        if (userId == null) {
            return null;
        }
        return orgMemberMapper.selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId)
                .eq(TenantMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    private static String normalizeRole(String role) {
        String value = role.trim().toUpperCase(Locale.ROOT);
        if (!KbMember.ROLE_EDITOR.equals(value) && !KbMember.ROLE_VIEWER.equals(value)) {
            throw BizException.param("名单角色只能是 EDITOR / VIEWER");
        }
        return value;
    }

    /** 值域规则在实体上，与建库时给定档位共用一份；这里缺省不给默认值，省略就是错误。 */
    private static String normalizeScope(String scope) {
        return KnowledgeBase.normalizeMaintainScope(scope, null);
    }
}
