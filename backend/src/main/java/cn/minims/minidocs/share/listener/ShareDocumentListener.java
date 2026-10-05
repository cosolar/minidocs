package cn.minims.minidocs.share.listener;

import cn.minims.minidocs.common.event.DocumentChangedEvent;
import cn.minims.minidocs.common.event.KnowledgeBaseDeletedEvent;
import cn.minims.minidocs.share.service.ShareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文件变更 / 知识库删除事件的分享侧联动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShareDocumentListener {

    private final ShareService shareService;

    @EventListener
    public void onDocumentChanged(DocumentChangedEvent event) {
        shareService.applyDocumentChange(event);
    }

    @EventListener
    public void onKnowledgeBaseDeleted(KnowledgeBaseDeletedEvent event) {
        shareService.deleteByKnowledgeBase(event.kbId());
        log.info("知识库 {} 已删除，级联清理其分享", event.kbId());
    }
}
