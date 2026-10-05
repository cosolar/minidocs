package cn.minims.minidocs.doc.dto;

import cn.minims.minidocs.common.util.PathEncoder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档模块 DTO 集合。
 */
public final class DocDtos {

    private DocDtos() {
    }

    /** 目录树节点。type = dir | doc */
    public record DocNode(String name, String path, String encodedPath, String type, long size,
                          LocalDateTime modifiedAt, List<DocNode> children) {

        public static DocNode dir(String name, String path, List<DocNode> children) {
            return new DocNode(name, path, PathEncoder.encodePath(path), "dir", 0L, null, children);
        }

        public static DocNode doc(String name, String path, long size, LocalDateTime modifiedAt) {
            return new DocNode(name, path, PathEncoder.encodePath(path), "doc", size, modifiedAt, List.of());
        }
    }

    /** 文档原文 + 元信息。 */
    public record DocContentVO(String path, String name, String title, String content,
                               long size, LocalDateTime modifiedAt) {
    }

    public record CreateDocRequest(
            @NotBlank(message = "路径不能为空") String path,
            String content) {
    }

    public record SaveDocRequest(
            @NotBlank(message = "路径不能为空") String path,
            String content,
            /** 保存基线：读取时拿到的 size（规范 §4.3），缺失或与磁盘不符即 412 */
            Long baseSize,
            /** 保存基线：读取时拿到的 modifiedAt，缺失或与磁盘不符即 412 */
            LocalDateTime baseMtime) {
    }

    /**
     * 编辑锁状态。
     *
     * <p>{@code locked=false} 时持有者字段全为 null，但 {@code ttlSeconds} 照旧下发：
     * 心跳间隔由它推导（TTL/3），前端不该再抄一份 60/20 的常量。</p>
     */
    public record LockVO(String path, boolean locked, Long holderUserId, String holder, String nickname,
                         LocalDateTime acquiredAt, LocalDateTime expiresAt, boolean mine, long ttlSeconds) {

        public static LockVO free(String path, long ttlSeconds) {
            return new LockVO(path, false, null, null, null, null, null, false, ttlSeconds);
        }
    }

    public record RenameRequest(
            @NotBlank(message = "路径不能为空") String path,
            @NotBlank(message = "名称不能为空")
            @Size(max = 120, message = "名称长度不能超过 120") String name) {
    }

    public record MoveRequest(
            @NotBlank(message = "路径不能为空") String path,
            String targetDir) {
    }

    public record CreateDirRequest(
            @NotBlank(message = "路径不能为空") String path) {
    }

    /**
     * 同目录内自定义排序请求。
     *
     * @param path       被拖动的项
     * @param targetPath 同目录内的锚点项
     * @param position   {@code before} / {@code after}，缺省按 before
     */
    public record ReorderRequest(
            @NotBlank(message = "路径不能为空") String path,
            @NotBlank(message = "目标路径不能为空") String targetPath,
            String position) {
    }

    /** 导入结果。 */
    public record ImportResult(int imported, int skipped, int failed, List<String> messages) {
    }

    /** 重命名 / 移动后的返回。 */
    public record PathChangeVO(String path, String name) {
    }

    /** 下载载荷。 */
    public record DownloadPayload(org.springframework.core.io.Resource resource, String filename) {
    }

    /** 渲染接口返回。 */
    public record RenderVO(String html,
                           java.util.List<cn.minims.minidocs.markdown.model.MarkdownModels.OutlineNode> outline,
                           java.util.Map<String, Object> frontmatter,
                           cn.minims.minidocs.markdown.model.MarkdownModels.DocMeta meta,
                           String etag, String rawContent) {
    }
}
