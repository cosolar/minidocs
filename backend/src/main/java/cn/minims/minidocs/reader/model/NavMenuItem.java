package cn.minims.minidocs.reader.model;

/**
 * 分享页顶部导航条的一项。
 *
 * <p>{@code type} 为 {@code dir} 或 {@code doc}，{@code path} 是知识库内相对路径。</p>
 *
 * <p><b>{@code name} 只在出参里有值</b>（由路径末段推导），入参不必给：存库的 JSON 因此
 * 只有 {@code type} 与 {@code path} 两个键，目录改名后靠路径前缀重写就能保住整份配置，
 * 少一个会与路径漂移的显示名字段。</p>
 *
 * <p><b>{@code alias} 是作者显式取的别名</b>，与 {@code name} 相反：它<b>要存</b>。
 * 两者分工明确 —— {@code name} 回答「这个东西叫什么」（推导名，跟着改名自动变），
 * {@code alias} 回答「在导航条上想让人看到什么」（作者说了算，改名也不动）。
 * 目录叫 {@code docs} 而作者希望读者看到「文档」时，需要的就是后者；
 * 若把别名塞进 {@code name}，一次改名就会把作者取的名字冲掉。</p>
 *
 * <p><b>{@code icon} 是作者给这一项挑的图标名</b>，留空则按类型用默认（目录书架、
 * 文档单篇）。它与 {@code alias} 一样要存：菜单里那个小图标是入口辨识的一部分，
 * 而目录名千篇一律（「文档」「笔记」），没有图标就分不出入口。合法字符见
 * {@link #normalizeIcon}。</p>
 *
 * <p><b>出参的 {@code name} 保持推导原名，不做合并</b>：配置器要并排显示
 * 「原始名 → 别名」两列，合并掉原名作者就不知道自己在给哪一项取别名。
 * 生效名的合并在顶栏渲染那一处（{@code alias || name}）—— 那里是唯一
 * 只需要「一个名字」的地方，把判断放在使用现场而不是数据里。</p>
 */
public record NavMenuItem(String type, String path, String name, String alias, String icon) {

    public static final String TYPE_DIR = "dir";
    public static final String TYPE_DOC = "doc";

    /**
     * 别名长度上限。
     *
     * <p>导航条是顶栏一行，40 项时宽度已经紧张；再长的别名会把后面的项挤出视口，
     * 而读者真正需要的是「知道这一项是干什么的」，不是一段完整的标题。</p>
     */
    public static final int ALIAS_MAX = 32;

    /** 入库形态：只有类型与路径，无别名。 */
    public static NavMenuItem of(String type, String path) {
        return new NavMenuItem(type, path, null, null, null);
    }

    /**
     * 入库形态：带别名与图标。
     *
     * <p>别名在此归一（去空白、过长度），因为它一路存进 JSON 而读侧不再校验 ——
     * 校验放在唯一的写入口，才不会出现「读侧兜底、放行了超长值」。</p>
     */
    public static NavMenuItem of(String type, String path, String alias, String icon) {
        return new NavMenuItem(type, path, null, normalizeAlias(alias), normalizeIcon(icon));
    }

    /**
     * 别名归一：空白一律视为「没取别名」，超长截断。
     *
     * <p>不抛异常而是截断：这是展示文案，不是标识符，截掉一半仍然可读，
     * 而抛异常会让整个保存失败 —— 用户改个菜单名却连带存不下有效期设置。</p>
     */
    public static String normalizeAlias(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > ALIAS_MAX ? trimmed.substring(0, ALIAS_MAX) : trimmed;
    }

    /**
     * 图标名归一：只放行「小写字母 + 数字 + 连字符」，其余一律当作没设。
     *
     * <p>不校验「这个图标名是否真的存在」：那份清单在前端（{@code shared/navIcons.ts}），
     * 后端拿不到也不该复制一份 —— 两份清单必然漂移。写坏的名字在前端会回退成默认图标，
     * 是一张图点不亮，而不是报错或白屏。</p>
     */
    public static String normalizeIcon(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.matches("[a-z0-9-]{1,24}") ? trimmed : null;
    }

    /** 出参形态：补上显示名。别名与图标原样带过去。 */
    public NavMenuItem withName(String value) {
        return new NavMenuItem(type, path, value, alias, icon);
    }

    /** 出参形态：只改路径（重命名 / 移动时用），其余字段一律保住。 */
    public NavMenuItem withPath(String value) {
        return new NavMenuItem(type, value, name, alias, icon);
    }

    public boolean dir() {
        return TYPE_DIR.equals(type);
    }
}