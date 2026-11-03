package cn.minims.minidocs.kb.support;

import cn.minims.minidocs.common.util.PathEncoder;

/**
 * 封面地址的唯一解析口径。
 *
 * <p>{@code knowledge_base.cover_url} 有三种形态：</p>
 * <ol>
 *   <li>外链：{@code http(s)://...}，原样下发；</li>
 *   <li>平台封面：只存文件名（如 {@code k12-cover-20261103....png}），文件在
 *       {@code VAULT_HOME/platform/covers} 下，由当前场景的封面接口读出；</li>
 *   <li>旧版库内封面：{@code assets/cover-...}（带斜杠）。升级时会被
 *       {@code CoverAssetMigrator} 搬到平台目录，这里保留一段兜底，避免启动迁移
 *       与页面请求之间的窗口期裂图。</li>
 * </ol>
 *
 * <p>把判断收在一处，是因为后台列表、门户与分享页以前各拼一次地址；三种形态的判定
 * 一旦分叉，就会出现「后台能看、门户空白」这种只在一侧复现的 bug。</p>
 */
public final class CoverAssets {

    private CoverAssets() {
    }

    public static boolean isExternal(String coverUrl) {
        return coverUrl != null && (coverUrl.startsWith("http://") || coverUrl.startsWith("https://"));
    }

    /**
     * @param coverUrl         数据库里的原值
     * @param coverPrefix      当前场景的封面接口（已带上下文前缀），如 {@code /kb/{org}/{slug}/cover}
     * @param legacyAssetPrefix 旧版库内资源前缀（已带上下文前缀），用于兜底迁移前的值
     */
    public static String src(String coverUrl, String coverPrefix, String legacyAssetPrefix) {
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        if (isExternal(coverUrl)) {
            return coverUrl;
        }
        if (coverUrl.indexOf('/') >= 0) {
            return legacyAssetPrefix == null ? null : legacyAssetPrefix + PathEncoder.encodePath(coverUrl);
        }
        return coverPrefix;
    }

    /** 是否为平台目录里的封面文件名（不含路径分隔符、不是外链）。 */
    public static boolean isStoredName(String coverUrl) {
        return coverUrl != null && !coverUrl.isBlank() && !isExternal(coverUrl) && coverUrl.indexOf('/') < 0;
    }
}
