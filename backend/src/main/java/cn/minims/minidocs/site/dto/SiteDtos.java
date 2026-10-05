package cn.minims.minidocs.site.dto;

/**
 * 站点配置 DTO。
 */
public final class SiteDtos {

    private SiteDtos() {
    }

    /**
     * 对外站点信息。
     *
     * <p>{@code logo} 是库里存的文件名（仅用于「有没有配 Logo」这类判断），{@code logoSrc}
     * 才是可直接放进 {@code <img src>} 的地址 —— 与头像同一套口径：换部署目录时不必洗库。</p>
     */
    public record SiteConfigVO(String name, String subtitle, String logo, String logoSrc) {

        /** Logo 接口在上下文内的路径，对外地址要再补上上下文前缀（见 {@code AppPaths}） */
        public static final String LOGO_PREFIX = "/api/site-logos/";
    }
}
