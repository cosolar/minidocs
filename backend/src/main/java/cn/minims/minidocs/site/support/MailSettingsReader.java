package cn.minims.minidocs.site.support;

import cn.minims.minidocs.common.util.CryptoUtil;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.mapper.SiteConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 邮件配置的读取处：把 {@code site_config} 里的邮件键合成一份 {@link MailSettings}。
 *
 * <p>与 {@code SiteBaseUrlResolver} 一样只依赖 Mapper 而不依赖服务层 —— 服务层保存配置后
 * 可能反过来要用这里，依赖成环会被 Spring 直接拒掉。</p>
 *
 * <p><b>为什么不缓存</b>：这一项只在注册、登录闸门判定与发信时被读，频率和登录限速同量级，
 * 而它是一次主键查询；不缓存换来「站点设置改完立刻生效」，省掉一层失效通知与一个 TTL 窗口。
 * 相比之下基址每次分享页打开都要读，才值得缓存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailSettingsReader {

    private final SiteConfigMapper siteConfigMapper;
    private final MiniDocsProperties properties;

    /** 当前生效的邮件配置；从未配置过返回全空、{@code verifyEnabled = false} 的实例。 */
    public MailSettings read() {
        return read(load());
    }

    /**
     * 从一份已经读出来的站点配置合成邮件配置。
     *
     * <p>存在的理由是让「读一次库、多处用同一份判定」成为可能：公开的站点信息接口本来就
     * 要读这一行，再调一次 {@link #read()} 等于同一件事查两遍库。注册页那个「邮箱是否必填」
     * 的布尔和后端真正的闸门判断必须出自同一个判断，各查各的会给出两个答案。</p>
     */
    public MailSettings read(Map<String, Object> config) {
        String password = null;
        boolean credentialUnusable = false;
        String stored = text(config.get(SiteConfig.KEY_SMTP_PASSWORD));
        if (stored != null) {
            try {
                password = CryptoUtil.decrypt(stored, properties.getJwtSecret());
            } catch (IllegalStateException e) {
                credentialUnusable = true;
                log.warn("SMTP 口令无法解密（加密密钥可能已变更），邮件发送按未配置处理：{}", e.getMessage());
            }
        }
        return new MailSettings(
                text(config.get(SiteConfig.KEY_SMTP_HOST)),
                integer(config.get(SiteConfig.KEY_SMTP_PORT)),
                security(config.get(SiteConfig.KEY_SMTP_SECURITY)),
                text(config.get(SiteConfig.KEY_SMTP_USERNAME)),
                password,
                text(config.get(SiteConfig.KEY_SMTP_FROM)),
                Boolean.parseBoolean(text(config.get(SiteConfig.KEY_EMAIL_VERIFY_ENABLED))),
                credentialUnusable);
    }

    /** 站点配置里邮件相关的那几个键的原始值；没有行时返回空 Map。 */
    public Map<String, Object> load() {
        try {
            SiteConfig entity = siteConfigMapper.selectById(SiteConfig.SINGLETON_ID);
            if (entity == null || entity.getConfig() == null) {
                return Map.of();
            }
            return JsonUtil.toMap(entity.getConfig());
        } catch (Exception e) {
            // 读配置失败不能把注册接口带崩：降级成「没配邮件」，注册按现状放行
            log.warn("读取站点邮件配置失败，降级为未配置：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 连接保护方式统一成小写并只认三种取值；库里被人手改出别的值时按「没选」处理，交给端口推断。 */
    private String security(Object raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        String lowered = value.toLowerCase();
        return MailSettings.isKnownSecurity(lowered) ? lowered : null;
    }

    /** 端口只认合法区间；越界或非整数（有人直接改了库）当成没配，别让一次脏数据把闸门卡死。 */
    private Integer integer(Object raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= 65535 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
