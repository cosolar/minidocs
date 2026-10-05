package cn.minims.minidocs.audit.dto;

import cn.minims.minidocs.audit.entity.AuditLog;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.common.util.TimeUtil;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计模块 DTO 集合。
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    /**
     * 审计条目视图。
     *
     * <p>{@code detail} 由存储用的 JSON 文本解回成对象，前端直接渲染；解不开时原样给回文本，
     * 一条脏数据不该让整个审计页报错。</p>
     */
    public record AuditVO(Long id, Long actorUserId, String actor, String action, Long kbId, String kbName,
                          String docPath, Object detail, LocalDateTime createdAt, String createdText) {

        public static AuditVO of(AuditLog entry, String actor, String kbName) {
            return new AuditVO(entry.getId(), entry.getActorUserId(), actor, entry.getAction(),
                    entry.getKbId(), kbName, entry.getDocPath(), parseDetail(entry.getDetail()),
                    entry.getCreatedAt(), TimeUtil.display(entry.getCreatedAt()));
        }

        private static Object parseDetail(String json) {
            if (json == null || json.isBlank()) {
                return null;
            }
            Map<String, Object> parsed = JsonUtil.toMap(json);
            return parsed.isEmpty() ? json : parsed;
        }
    }
}
