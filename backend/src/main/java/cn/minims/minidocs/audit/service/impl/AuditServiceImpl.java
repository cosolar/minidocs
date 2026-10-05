package cn.minims.minidocs.audit.service.impl;

import cn.minims.minidocs.audit.dto.AuditDtos.AuditVO;
import cn.minims.minidocs.audit.entity.AuditLog;
import cn.minims.minidocs.audit.mapper.AuditLogMapper;
import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KnowledgeBaseMapper;
import cn.minims.minidocs.permission.KbAction;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogMapper auditLogMapper;
    // 读取路径直连 Mapper：这里若注入 KnowledgeBaseService / UserService，会绕成
    // AuditService ← AccessService ← KnowledgeBaseService ← AuditService 的循环依赖，容器起不来
    private final UserMapper userMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public void record(KbAction action, Long actorUserId, Long tenantId, Long kbId, String docPath,
                       Map<String, Object> detail) {
        if (actorUserId == null) {
            // 匿名访问没有可追责的主体，也不该出现治理动作；留一行 warn 便于排查
            log.warn("跳过审计写入：action={} 无操作者", action);
            return;
        }
        AuditLog entry = new AuditLog();
        entry.setTenantId(tenantId);
        entry.setActorUserId(actorUserId);
        entry.setAction(action.name());
        entry.setKbId(kbId);
        entry.setDocPath(docPath);
        entry.setDetail(detail == null || detail.isEmpty() ? null : JsonUtil.toJson(detail));
        auditLogMapper.insert(entry);
    }

    @Override
    public void superBypass(KbAction action, Long actorUserId, Long tenantId, Long kbId, String docPath) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bypass", "SUPER_ADMIN");
        record(action, actorUserId, tenantId, kbId, docPath, detail);
    }

    @Override
    public PageResult<AuditVO> page(Long tenantId, String action, Long actorUserId,
                                    LocalDateTime from, LocalDateTime to, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        if (tenantId == null) {
            // 组织上下文缺失时绝不退化成全表读：审计跨组织可见是最不该出的问题
            return PageResult.empty(current, pageSize);
        }
        LambdaQueryWrapper<AuditLog> wrapper = Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getTenantId, tenantId);
        if (action != null && !action.isBlank()) {
            wrapper.eq(AuditLog::getAction, action.trim());
        }
        if (actorUserId != null) {
            wrapper.eq(AuditLog::getActorUserId, actorUserId);
        }
        if (from != null) {
            wrapper.ge(AuditLog::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.lt(AuditLog::getCreatedAt, to);
        }
        // created_at 只到秒，同一秒内的多条靠 id 定序，翻页才不会重复或漏行
        wrapper.orderByDesc(AuditLog::getCreatedAt).orderByDesc(AuditLog::getId);

        Page<AuditLog> result = auditLogMapper.selectPage(new Page<>(current, pageSize), wrapper);
        List<AuditLog> entries = result.getRecords();
        Map<Long, String> actors = actorNames(entries);
        Map<Long, String> kbNames = kbNames(entries);
        List<AuditVO> list = entries.stream()
                .map(entry -> AuditVO.of(entry, actors.get(entry.getActorUserId()),
                        kbNames.get(entry.getKbId())))
                .toList();
        return PageResult.of(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public int purgeOlderThan(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return auditLogMapper.delete(Wrappers.<AuditLog>lambdaQuery().lt(AuditLog::getCreatedAt, cutoff));
    }

    private Map<Long, String> actorNames(List<AuditLog> entries) {
        List<Long> ids = distinctIds(entries, AuditLog::getActorUserId);
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User user : userMapper.selectBatchIds(ids)) {
                names.put(user.getId(), displayOf(user));
            }
        }
        // 注销用户的痕迹仍要可读：留一行比空白更诚实
        ids.forEach(id -> names.putIfAbsent(id, "(已注销)"));
        return names;
    }

    private Map<Long, String> kbNames(List<AuditLog> entries) {
        List<Long> ids = distinctIds(entries, AuditLog::getKbId);
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (KnowledgeBase kb : knowledgeBaseMapper.selectBatchIds(ids)) {
                names.put(kb.getId(), kb.getName());
            }
        }
        return names;
    }

    private List<Long> distinctIds(List<AuditLog> entries, Function<AuditLog, Long> getter) {
        return entries.stream().map(getter).filter(Objects::nonNull).distinct().toList();
    }

    private String displayOf(User user) {
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? user.getUsername() : user.getDisplayName();
    }
}
