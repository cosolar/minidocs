package cn.minims.minidocs.common.event;

/**
 * 知识库删除事件：通知分享模块级联清理。
 */
public record KnowledgeBaseDeletedEvent(Long kbId) {
}
