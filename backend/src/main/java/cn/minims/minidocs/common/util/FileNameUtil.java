package cn.minims.minidocs.common.util;

import cn.minims.minidocs.config.properties.MiniDocsProperties;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 文件/目录名称工具。
 */
public final class FileNameUtil {

    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Pattern WINDOWS_RESERVED =
            Pattern.compile("^(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static final int MAX_NAME_LENGTH = 120;

    private FileNameUtil() {
    }

    /**
     * 校验并返回安全的文件/目录名称。
     */
    public static String validateName(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("名称不能为空");
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名称长度不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        if (name.startsWith(".")) {
            throw new IllegalArgumentException("不允许以 . 开头的隐藏文件或目录");
        }
        if ("..".equals(name) || ".".equals(name)) {
            throw new IllegalArgumentException("名称非法");
        }
        if (ILLEGAL.matcher(name).find()) {
            throw new IllegalArgumentException("名称包含非法字符（/ \\ : * ? \" < > |）");
        }
        if (PathGuard.containsControlChar(name)) {
            throw new IllegalArgumentException("名称包含控制字符");
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            throw new IllegalArgumentException("名称不能以点或空格结尾");
        }
        if (WINDOWS_RESERVED.matcher(name).matches()) {
            throw new IllegalArgumentException("名称不能使用系统保留字");
        }
        return name;
    }

    public static boolean isMarkdown(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return MiniDocsProperties.DOC_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public static boolean isImage(String name) {
        String ext = extensionOf(name);
        return MiniDocsProperties.IMAGE_EXTENSIONS.contains(ext);
    }

    /** 扩展名（小写，不含点）；无扩展名返回空串。 */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    /** 去除 Markdown 扩展名的标题文本。 */
    public static String stripMarkdownExt(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : MiniDocsProperties.DOC_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    /** 确保以 .md 结尾。 */
    public static String ensureMarkdownExt(String name) {
        return isMarkdown(name) ? name : name + ".md";
    }

    /** 图片文件名：yyyyMMddHHmmss + 6 位随机 + 扩展名。 */
    public static String buildImageName(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        String suffix = ext.isEmpty() ? "" : "." + ext;
        return STAMP.format(TimeUtil.now()) + randomDigits(6) + suffix;
    }

    public static String randomDigits(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    public static String randomAlphanumeric(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }
}
