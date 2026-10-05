package cn.minims.minidocs.common.event;

/**
 * 文档变更事件：驱动分享记录的 doc_path 同步与失效标记。
 *
 * <p>用事件解耦 doc 与 share 两个模块，避免循环依赖。</p>
 */
public record DocumentChangedEvent(Long kbId, String oldPath, String newPath, Type type) {

    public enum Type {
        /** 重命名：oldPath -> newPath */
        RENAMED,
        /** 移动：oldPath -> newPath */
        MOVED,
        /** 删除：oldPath 失效 */
        DELETED
    }

    public static DocumentChangedEvent renamed(Long kbId, String oldPath, String newPath) {
        return new DocumentChangedEvent(kbId, oldPath, newPath, Type.RENAMED);
    }

    public static DocumentChangedEvent moved(Long kbId, String oldPath, String newPath) {
        return new DocumentChangedEvent(kbId, oldPath, newPath, Type.MOVED);
    }

    public static DocumentChangedEvent deleted(Long kbId, String oldPath) {
        return new DocumentChangedEvent(kbId, oldPath, null, Type.DELETED);
    }
}
