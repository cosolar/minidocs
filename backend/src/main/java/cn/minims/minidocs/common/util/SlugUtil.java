package cn.minims.minidocs.common.util;

import java.util.Locale;
import java.util.Set;

/**
 * 知识库目录名（slug）生成：保留字母 / 数字 / 中文 / - / _，其余字符去除。
 */
public final class SlugUtil {

    private static final Set<String> RESERVED =
            Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5",
                    "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4",
                    "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "vaults", "assets");

    /**
     * 组织 slug 保留字（规范 §3.2）：这些值是路由前缀，占用会让 {@code /console/{org}/**} 与真实端点撞车。
     *
     * <p>与 {@link #RESERVED} 分开，是因为两者对「撞上」的处置不同：库名撞上设备名可以静默加前缀，
     * 组织 slug 撞上路由却必须让用户换一个 —— 静默改写 {@code admin} 会得到 {@code kb-admin}，
     * 而管理员大概率照原样分享链接。</p>
     *
     * <p>{@code u-} 前缀为个人组织保留，那条规则在 {@code TenantServiceImpl} 里判：前缀常量属于
     * {@link cn.minims.minidocs.tenant.entity.Tenant}，反向引用会让 common 包依赖 tenant 包。</p>
     */
    private static final Set<String> ORG_RESERVED =
            Set.of("w", "api", "share", "kb", "kbs", "admin", "platform", "me", "login", "logout", "register", "u");

    private SlugUtil() {
    }

    /**
     * 组织 slug 的字符与保留字校验（规范 §3.2）。大小写不敏感 ——
     * {@code uk_tenant_slug} 建在 {@code COLLATE NOCASE} 上，按大小写区分会让 {@code Admin} 过校验却与
     * {@code admin} 争同一索引位。
     *
     * <p>{@code personalPrefix} 由调用方传入而不是从这里读实体常量：common 反向依赖 tenant 会成环。</p>
     */
    public static boolean isAvailableOrgSlug(String slug, String personalPrefix) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        String value = slug.trim().toLowerCase(Locale.ROOT);
        if (ORG_RESERVED.contains(value) || (personalPrefix != null && value.startsWith(personalPrefix))) {
            return false;
        }
        for (char c : value.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    public static String slugify(String name) {
        String source = name == null ? "" : name.trim();
        StringBuilder builder = new StringBuilder();
        for (char c : source.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                builder.append(c);
            } else if (Character.isWhitespace(c)) {
                builder.append('-');
            }
        }
        String slug = builder.toString().toLowerCase(Locale.ROOT).replaceAll("-{2,}", "-");
        slug = trimEdge(slug);
        if (slug.isEmpty()) {
            slug = "kb";
        }
        if (RESERVED.contains(slug)) {
            slug = "kb-" + slug;
        }
        return slug.length() > 48 ? trimEdge(slug.substring(0, 48)) : slug;
    }

    /** 追加 -2 / -3 ... 生成不重名的 slug。 */
    public static String withSuffix(String slug, int index) {
        return slug + "-" + index;
    }

    private static String trimEdge(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '-' || value.charAt(start) == '_')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == '-' || value.charAt(end - 1) == '_')) {
            end--;
        }
        return value.substring(start, end);
    }
}
