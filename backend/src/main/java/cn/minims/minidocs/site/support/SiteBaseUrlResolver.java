package cn.minims.minidocs.site.support;

import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.mapper.SiteConfigMapper;
import cn.minims.minidocs.share.support.ShareLinks;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 站点基址的取值处：分享链接、管理台里那条 URL、分享页顶栏「分享」按钮，全部走这里。
 *
 * <p>优先级（从高到低）：</p>
 * <ol>
 *   <li><b>站点设置页配置的基址</b>（{@code site_config.config.baseUrl}）——
 *       管理员在界面上改即可生效，不必重启后端、不必改环境变量。这是首选。</li>
 *   <li>{@code minidocs.page-base-url}（环境变量 {@code PAGE_BASE_URL}）——
 *       部署期兜底，也是「用容器编排、配置由平台注入」这类不便进数据库的场景下的正规写法。</li>
 *   <li>按当前请求推导（协议 + 主机 + 端口）—— 仅适合同源部署，
 *       且需要开启 {@code server.forward-headers-strategy=framework} 才能识别反代传来的
 *       {@code X-Forwarded-Proto} / {@code X-Forwarded-Host}。</li>
 * </ol>
 *
 * <p><b>为什么缓存</b>：分享页每次打开都会要一次基址，而它可能被并发打开；配置本身几乎不变。
 * 用 Caffeine 缓存这一项，避免每次读都打一次库。缓存里存 {@link Optional} 而不是裸值 ——
 * 「管理员压根没配过」是最常见的状态，裸 {@code null} 不进缓存的话等于没缓存。</p>
 *
 * <p><b>为什么还要 TTL</b>：多实例部署时，{@link #invalidate()} 只清得到本进程的缓存，
 * 别的进程要等 TTL 到期。多留一条短 TTL 是为了让「改了配置但没走到本进程」的情形也能收敛，
 * 一分钟足够快，又不至于让每个分享请求都读库。</p>
 *
 * <p>本类只依赖 {@link SiteConfigMapper} 而不依赖 {@code SiteConfigService}：
 * 后者保存配置后要回调 {@link #invalidate()}，若这里再依赖服务层就成环了。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteBaseUrlResolver {

    private static final String CACHE_KEY = "base-url";

    /** 缓存 TTL 见类注释：给多实例场景留的收敛窗口 */
    private static final Duration TTL = Duration.ofMinutes(1);

    private final SiteConfigMapper siteConfigMapper;
    private final MiniDocsProperties properties;

    private final Cache<String, Optional<String>> cache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(TTL)
            .build();

    /**
     * 解析出用于拼接的站点基址（无尾斜杠）。
     *
     * @param request 仅在「前两级都没配」时才用来推导协议与主机，允许为 null
     */
    public String resolve(HttpServletRequest request) {
        Optional<String> configured = configuredBaseUrl();
        if (configured.isPresent()) {
            return ShareLinks.normalize(configured.get());
        }
        String fromProperties = properties == null ? null : properties.getPageBaseUrl();
        if (fromProperties != null && !fromProperties.isBlank()) {
            return ShareLinks.normalize(fromProperties);
        }
        return ShareLinks.fromRequest(request);
    }

    /**
     * 站点设置页里配的基址；没配（或配了空串）时为 {@link Optional#empty()}。
     *
     * <p>注意这是「配置值」而非「最终生效值」：最终生效值还可能是环境变量或请求推导来的，
     * 拿这一点去回显给管理员，会让他以为自己配的东西生效了。</p>
     */
    public Optional<String> configuredBaseUrl() {
        return cache.get(CACHE_KEY, key -> load());
    }

    /** 配置被改动后清缓存，由写入侧在事务提交路径上调用。 */
    public void invalidate() {
        cache.invalidateAll();
    }

    private Optional<String> load() {
        try {
            SiteConfig entity = siteConfigMapper.selectById(SiteConfig.SINGLETON_ID);
            if (entity == null || entity.getConfig() == null) {
                return Optional.empty();
            }
            Map<String, Object> config = JsonUtil.toMap(entity.getConfig());
            Object raw = config.get(SiteConfig.KEY_BASE_URL);
            if (raw == null) {
                return Optional.empty();
            }
            String value = String.valueOf(raw).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (Exception e) {
            // 读站点配置失败不该让分享页打不开：降级成「没配」，由调用方回落到环境变量或请求推导
            log.warn("读取站点基址配置失败，降级为按请求推导：{}", e.getMessage());
            return Optional.empty();
        }
    }
}
