package cn.minims.minidocs.reader.model;

/**
 * 分享页顶部导航条的一项。
 *
 * <p>{@code type} 为 {@code dir} 或 {@code doc}，{@code path} 是知识库内相对路径。</p>
 *
 * <p>{@code name} 只在出参里有值（由路径末段推导），入参不必给：存库的 JSON 因此只有
 * {@code type} 与 {@code path} 两个键，目录改名后靠路径前缀重写就能保住整份配置，
 * 少一个会与路径漂移的显示名字段。</p>
 */
public record NavMenuItem(String type, String path, String name) {

    public static final String TYPE_DIR = "dir";
    public static final String TYPE_DOC = "doc";

    /** 入库形态：只有类型与路径。 */
    public static NavMenuItem of(String type, String path) {
        return new NavMenuItem(type, path, null);
    }

    /** 出参形态：补上显示名。 */
    public NavMenuItem withName(String value) {
        return new NavMenuItem(type, path, value);
    }

    public boolean dir() {
        return TYPE_DIR.equals(type);
    }
}
