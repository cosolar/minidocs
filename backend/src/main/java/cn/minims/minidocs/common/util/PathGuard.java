package cn.minims.minidocs.common.util;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 路径安全守卫：所有文件读写、删除、代理必须经过本类。
 *
 * <ol>
 *   <li>入参相对路径规范化，拒绝含 .. / 绝对路径 / 盘符 / 控制字符</li>
 *   <li>拼接知识库根得到绝对路径</li>
 *   <li>再次规范化后校验 startsWith(知识库根)，否则抛 40001</li>
 * </ol>
 */
public final class PathGuard {

    private PathGuard() {
    }

    /**
     * 规范化相对路径（以知识库根为基准）。空串表示知识库根目录。
     */
    public static String normalizeRelative(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replace('\\', '/').trim();
        if (value.isEmpty() || "/".equals(value)) {
            return "";
        }
        if (value.startsWith("/")) {
            throw BizException.of(ErrorCode.PATH_INVALID, "不允许使用绝对路径");
        }
        if (value.matches("^[A-Za-z]:.*")) {
            throw BizException.of(ErrorCode.PATH_INVALID, "不允许使用盘符路径");
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw BizException.of(ErrorCode.PATH_INVALID, "路径不允许包含 ..");
            }
            if (containsControlChar(segment)) {
                throw BizException.of(ErrorCode.PATH_INVALID, "路径包含非法控制字符");
            }
            segments.addLast(segment);
        }
        return String.join("/", segments);
    }

    /**
     * 解析为知识库内的绝对路径，越界抛 40001。
     */
    public static Path resolve(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        String normalized = normalizeRelative(relative);
        Path target = normalized.isEmpty() ? base : base.resolve(normalized).normalize();
        if (!target.startsWith(base)) {
            throw BizException.of(ErrorCode.PATH_INVALID, "路径越权：" + relative);
        }
        return target;
    }

    /**
     * 校验层级深度：目录层级（不含文件自身）不得超过 maxDepth。
     */
    public static void checkDepth(String relative, boolean file, int maxDepth) {
        String normalized = normalizeRelative(relative);
        if (normalized.isEmpty()) {
            return;
        }
        int segments = normalized.split("/").length;
        int levels = file ? segments - 1 : segments;
        if (levels > maxDepth) {
            throw BizException.of(ErrorCode.DEPTH_EXCEEDED,
                    "路径深度超过 " + maxDepth + " 层：" + normalized);
        }
    }

    /**
     * 计算某文件路径的父目录（相对路径），根目录返回空串。
     */
    public static String parentOf(String relative) {
        String normalized = normalizeRelative(relative);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? "" : normalized.substring(0, index);
    }

    /**
     * 取得路径中最后一段的名称。
     */
    public static String fileNameOf(String relative) {
        String normalized = normalizeRelative(relative);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    /**
     * 拼接父目录与子项名称。
     */
    public static String join(String parent, String name) {
        String normalizedParent = normalizeRelative(parent);
        return normalizedParent.isEmpty() ? name : normalizedParent + "/" + name;
    }

    public static boolean containsControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
