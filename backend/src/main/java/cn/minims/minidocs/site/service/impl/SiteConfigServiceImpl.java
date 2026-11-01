package cn.minims.minidocs.site.service.impl;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.CryptoUtil;
import cn.minims.minidocs.common.util.EmailUtil;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.support.ImageValidator;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.dto.SiteDtos.SiteMailVO;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.mapper.SiteConfigMapper;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.site.support.MailSettings;
import cn.minims.minidocs.site.support.MailSettingsReader;
import cn.minims.minidocs.site.support.SiteBaseUrlResolver;
import cn.minims.minidocs.site.support.SiteBaseUrls;
import cn.minims.minidocs.theme.service.ThemeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点配置读写。
 *
 * <p>整张 {@code site_config} 表只有 {@code id = 1} 一行；没有行时读出来就是默认品牌，
 * 不预先插一条空记录 —— 少一次「先有鸡还是先有蛋」的初始化，也让「恢复默认」等价于清空字段。</p>
 *
 * <p>「站点基址」也在这张表里（{@code baseUrl} 键），管理员在站点设置页改完即生效。
 * 之所以不新发一版迁移：{@code config} 是 JSON 列，加键不动 schema ——
 * 这正是当初选 JSON 而不是逐列建列的原因。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    private static final String DEFAULT_NAME = "MiniDocs";
    private static final String DEFAULT_SUBTITLE = "极简智库";

    private static final int MAX_NAME = 32;
    private static final int MAX_SUBTITLE = 48;

    /** Logo 尺寸的允许区间：再小看不清，再大顶栏装不下（品牌位靠 flex 收缩，不是无限画布） */
    private static final int LOGO_SIZE_MIN = 16;
    private static final int LOGO_SIZE_MAX = 512;

    /** 邮件字段的长度上限：与库里 VARCHAR 口径一致，超长直接拒，不截断（截断后的地址是个能发出去但收不到的地址） */
    private static final int SMTP_HOST_MAX = 255;
    private static final int SMTP_USER_MAX = 320;
    private static final int SMTP_PASSWORD_MAX = 512;
    private static final int SMTP_FROM_MAX = 320;

    /** patch 里的一次性指令键：显式清除已存的 SMTP 口令（不是站点配置的组成部分，故不入 SiteConfig 常量） */
    private static final String PATCH_SMTP_PASSWORD_CLEAR = "smtpPasswordClear";

    /** 用户名与口令只填一半。报错要说清两条出路，否则人只会去改主机名。 */
    private static final String CREDENTIAL_INCOMPLETE =
            "填了 SMTP 用户名就要填口令（多数服务商要求先认证，缺口令会回 503 need EHLO and AUTH first）；"
                    + "不需要认证的中继请把用户名也清空";

    private final SiteConfigMapper siteConfigMapper;
    private final MiniDocsProperties properties;
    private final ImageValidator imageValidator;
    private final AssetService assetService;
    private final SiteBaseUrlResolver siteBaseUrlResolver;
    private final ThemeService themeService;
    private final MailSettingsReader mailSettingsReader;

    @Override
    public SiteConfigVO get() {
        return toVO(readConfig());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO update(Map<String, Object> patch) {
        Map<String, Object> config = readConfig();
        if (patch != null) {
            applyText(config, patch, SiteConfig.KEY_NAME, MAX_NAME, "站点名称");
            applyText(config, patch, SiteConfig.KEY_SUBTITLE, MAX_SUBTITLE, "站点副标题");
            applyLogoSize(config, patch, SiteConfig.KEY_LOGO_WIDTH, "Logo 宽度");
            applyLogoSize(config, patch, SiteConfig.KEY_LOGO_HEIGHT, "Logo 高度");
            applyLogoOnly(config, patch);
            applyBaseUrl(config, patch);
            applyReaderTheme(config, patch);
        }
        save(config);
        return toVO(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO saveLogo(MultipartFile file) {
        return saveImage(file, SiteConfig.KEY_LOGO, "Logo");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO removeLogo() {
        return removeImage(SiteConfig.KEY_LOGO);
    }

    @Override
    public AssetService.Asset loadLogo(String fileName) {
        return assetService.load(properties.siteDir(), fileName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO saveIcon(MultipartFile file) {
        return saveImage(file, SiteConfig.KEY_ICON, "站点图标");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO removeIcon() {
        return removeImage(SiteConfig.KEY_ICON);
    }

    @Override
    public AssetService.Asset loadIcon(String fileName) {
        return assetService.load(properties.siteDir(), fileName);
    }

    @Override
    public SiteMailVO getMail() {
        return toMailVO(mailSettingsReader.load());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteMailVO updateMail(Map<String, Object> patch) {
        Map<String, Object> config = readConfig();
        if (patch != null) {
            applySmtpHost(config, patch);
            applySmtpPort(config, patch);
            applySmtpSecurity(config, patch);
            applySmtpText(config, patch, SiteConfig.KEY_SMTP_USERNAME, SMTP_USER_MAX, "SMTP 用户名");
            applySmtpFrom(config, patch);
            applySmtpPassword(config, patch);
            applyEmailVerifyEnabled(config, patch);
        }
        // 放在所有字段落完之后：patch 可以同时改开关与参数，只看 patch 会把「一次填齐并开启」误判成非法
        requireCompleteCredential(config);
        requireTransportWhenVerifyEnabled(config);
        save(config);
        return toMailVO(config);
    }

    /**
     * 允许保存「用户名 + 口令」或「都不填」，不允许只填一半。
     *
     * <p>半截凭据在开关关着的时候不出声，等开启验证才发现每个注册的人都收不到信 ——
     * 而服务器那边只会回一句 503，读起来像主机填错了。</p>
     */
    private void requireCompleteCredential(Map<String, Object> config) {
        MailSettings settings = mailSettingsReader.read(config);
        if (settings.credentialIncomplete() && !settings.credentialUnusable()) {
            throw BizException.param(CREDENTIAL_INCOMPLETE);
        }
    }

    /** Logo 与站点图标同一套落盘流程：先落新图再删旧图，中途失败最坏是留个孤儿文件。 */
    private SiteConfigVO saveImage(MultipartFile file, String key, String label) {
        String extension = imageValidator.validate(file, properties.getCoverMaxSize());
        String fileName = FileNameUtil.buildImageName(extension);
        write(properties.siteDir().resolve(fileName), file, extension, label);
        Map<String, Object> config = readConfig();
        deleteImageFile(text(config.get(key)), fileName);
        config.put(key, fileName);
        save(config);
        return toVO(config);
    }

    private SiteConfigVO removeImage(String key) {
        Map<String, Object> config = readConfig();
        deleteImageFile(text(config.get(key)), null);
        config.remove(key);
        save(config);
        return toVO(config);
    }

    /** 只认「patch 里显式带了这一项」：没带就不动，空串则清除。 */
    private void applyText(Map<String, Object> config, Map<String, Object> patch,
                           String key, int maxLength, String label) {
        if (!patch.containsKey(key)) {
            return;
        }
        Object raw = patch.get(key);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.length() > maxLength) {
            throw BizException.param(label + "不能超过 " + maxLength + " 个字符");
        }
        if (value.isEmpty()) {
            config.remove(key);
        } else {
            config.put(key, value);
        }
    }

    /**
     * 站点基址：校验 + 归一化后写入，空串表示清除（回落到环境变量或按请求推导）。
     *
     * <p>不套用 {@link #applyText} 的长度上限：基址要交给 {@code SiteBaseUrls} 一起判协议与主机名，
     * 它的报错比「不能超过 N 个字符」更能让人知道该怎么填。</p>
     */
    private void applyBaseUrl(Map<String, Object> config, Map<String, Object> patch) {
        if (!patch.containsKey(SiteConfig.KEY_BASE_URL)) {
            return;
        }
        Object raw = patch.get(SiteConfig.KEY_BASE_URL);
        String value = SiteBaseUrls.require(raw == null ? null : String.valueOf(raw));
        if (value.isEmpty()) {
            config.remove(SiteConfig.KEY_BASE_URL);
        } else {
            config.put(SiteConfig.KEY_BASE_URL, value);
        }
    }

    /**
     * Logo 显示尺寸：只认「patch 里显式带了这一项」。
     *
     * <p>null / 空串是清除（回到各品牌位的默认尺寸），不是 0；越界与非整数直接报参数错，
     * 因为一个 5000px 的 Logo 会把顶栏撑坏，而顶栏坏掉的样子不像「配置没生效」，更像站点有 bug。</p>
     */
    private void applyLogoSize(Map<String, Object> config, Map<String, Object> patch, String key, String label) {
        if (!patch.containsKey(key)) {
            return;
        }
        Object raw = patch.get(key);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty()) {
            config.remove(key);
            return;
        }
        int pixels;
        try {
            pixels = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw BizException.param(label + "必须是整数像素值");
        }
        if (pixels < LOGO_SIZE_MIN || pixels > LOGO_SIZE_MAX) {
            throw BizException.param(label + "需在 " + LOGO_SIZE_MIN + "–" + LOGO_SIZE_MAX + " 像素之间");
        }
        config.put(key, pixels);
    }

    /** 「只显示 Logo」：false 与清除等价 —— 默认值不占 JSON 一个键，读出来永远是 false。 */
    private void applyLogoOnly(Map<String, Object> config, Map<String, Object> patch) {
        if (!patch.containsKey(SiteConfig.KEY_LOGO_ONLY)) {
            return;
        }
        Object raw = patch.get(SiteConfig.KEY_LOGO_ONLY);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty() || "false".equalsIgnoreCase(value)) {
            config.remove(SiteConfig.KEY_LOGO_ONLY);
            return;
        }
        if (!"true".equalsIgnoreCase(value)) {
            throw BizException.param("只显示 Logo 的取值只能是 true 或 false");
        }
        config.put(SiteConfig.KEY_LOGO_ONLY, true);
    }

    /**
     * 默认阅读主题：只认「patch 里显式带了这一项」，空串清除（回到内置默认）。
     *
     * <p>id 必须是已安装的主题或内置 default：写一个不存在的 id，新访客会静默落到内置默认，
     * 管理员看到的设置项却显示着那个值 —— 「明明配了却不生效」比报错难受得多，所以这里就拦。</p>
     */
    private void applyReaderTheme(Map<String, Object> config, Map<String, Object> patch) {
        if (!patch.containsKey(SiteConfig.KEY_READER_THEME)) {
            return;
        }
        Object raw = patch.get(SiteConfig.KEY_READER_THEME);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty()) {
            config.remove(SiteConfig.KEY_READER_THEME);
            return;
        }
        boolean installed = themeService.list().stream().anyMatch(pack -> value.equals(pack.id()));
        if (!installed) {
            throw BizException.param("默认阅读主题不存在或已卸载: " + value);
        }
        config.put(SiteConfig.KEY_READER_THEME, value);
    }

    /**
     * SMTP 主机：去空白、拒带协议前缀与端口。
     *
     * <p>管理员最常见的两种贴法是 {@code https://smtp.qq.com} 和 {@code smtp.qq.com:465}。
     * 前者连不上、后者会把端口带进主机名里，两边报错都指向协议或 DNS，不像「你填的内容格式不对」。
     * 所以在写入时就明确拒绝，并把该怎么填写进报错里。</p>
     */
    private void applySmtpHost(Map<String, Object> config, Map<String, Object> patch) {
        String value = patchText(config, patch, SiteConfig.KEY_SMTP_HOST, SMTP_HOST_MAX, "SMTP 主机");
        if (value == null) {
            return;
        }
        if (value.contains("://") || value.contains(":")) {
            throw BizException.param("SMTP 主机只填域名，不要带 https:// 前缀或端口；端口请单独填");
        }
        config.put(SiteConfig.KEY_SMTP_HOST, value);
    }

    private void applySmtpPort(Map<String, Object> config, Map<String, Object> patch) {
        if (!patch.containsKey(SiteConfig.KEY_SMTP_PORT)) {
            return;
        }
        Object raw = patch.get(SiteConfig.KEY_SMTP_PORT);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty()) {
            config.remove(SiteConfig.KEY_SMTP_PORT);
            return;
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw BizException.param("SMTP 端口必须是整数，例如 465 或 587");
        }
        if (port < 1 || port > 65535) {
            throw BizException.param("SMTP 端口需在 1–65535 之间");
        }
        config.put(SiteConfig.KEY_SMTP_PORT, port);
    }

    /** 连接保护方式：只认 ssl / starttls / none；空串 = 不指定，由端口推断（见 {@link MailSettings#effectiveSecurity()}）。 */
    private void applySmtpSecurity(Map<String, Object> config, Map<String, Object> patch) {
        if (!patch.containsKey(SiteConfig.KEY_SMTP_SECURITY)) {
            return;
        }
        Object raw = patch.get(SiteConfig.KEY_SMTP_SECURITY);
        String value = raw == null ? "" : String.valueOf(raw).trim().toLowerCase();
        if (value.isEmpty()) {
            config.remove(SiteConfig.KEY_SMTP_SECURITY);
            return;
        }
        if (!MailSettings.isKnownSecurity(value)) {
            throw BizException.param("SMTP 加密方式只能是 ssl、starttls 或 none");
        }
        config.put(SiteConfig.KEY_SMTP_SECURITY, value);
    }

    /** 只判长度与空白的文本项（用户名这类）；主机要额外拒协议前缀，另走 {@link #applySmtpHost}。 */
    private void applySmtpText(Map<String, Object> config, Map<String, Object> patch,
                               String key, int maxLength, String label) {
        String value = patchText(config, patch, key, maxLength, label);
        if (value != null) {
            config.put(key, value);
        }
    }

    /** 发件地址：形状校验走 {@link EmailUtil}，与「测试发信」的收件地址同一套规则。 */
    private void applySmtpFrom(Map<String, Object> config, Map<String, Object> patch) {
        String value = patchText(config, patch, SiteConfig.KEY_SMTP_FROM, SMTP_FROM_MAX, "发件地址");
        if (value == null) {
            return;
        }
        if (!EmailUtil.isLikely(value)) {
            throw BizException.param("发件地址格式不正确，例如 no-reply@example.com");
        }
        config.put(SiteConfig.KEY_SMTP_FROM, value);
    }

    /**
     * SMTP 口令：落 AES-GCM 密文，且<b>留空 = 保持库里原值</b>。
     *
     * <p>表单不回显口令（见 {@link SiteMailVO}），保存时这一项必然常为空，
     * 若按「空串即清除」实现，改一次主机名就会把口令抹掉，而界面看着一切正常。
     * 清除只能显式发起，靠 {@code smtpPasswordClear} 这个一次性指令键。</p>
     */
    private void applySmtpPassword(Map<String, Object> config, Map<String, Object> patch) {
        if (Boolean.parseBoolean(String.valueOf(patch.get(PATCH_SMTP_PASSWORD_CLEAR)))) {
            config.remove(SiteConfig.KEY_SMTP_PASSWORD);
            return;
        }
        if (!patch.containsKey(SiteConfig.KEY_SMTP_PASSWORD)) {
            return;
        }
        Object raw = patch.get(SiteConfig.KEY_SMTP_PASSWORD);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.length() > SMTP_PASSWORD_MAX) {
            throw BizException.param("SMTP 口令不能超过 " + SMTP_PASSWORD_MAX + " 个字符");
        }
        config.put(SiteConfig.KEY_SMTP_PASSWORD, CryptoUtil.encrypt(value, properties.getJwtSecret()));
    }

    /** 开关本身：与 {@code logoOnly} 同一口径 —— false 与清除等价，默认值不占 JSON 一个键。 */
    private void applyEmailVerifyEnabled(Map<String, Object> config, Map<String, Object> patch) {
        if (!patch.containsKey(SiteConfig.KEY_EMAIL_VERIFY_ENABLED)) {
            return;
        }
        Object raw = patch.get(SiteConfig.KEY_EMAIL_VERIFY_ENABLED);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty() || "false".equalsIgnoreCase(value)) {
            config.remove(SiteConfig.KEY_EMAIL_VERIFY_ENABLED);
            return;
        }
        if (!"true".equalsIgnoreCase(value)) {
            throw BizException.param("邮箱验证开关的取值只能是 true 或 false");
        }
        config.put(SiteConfig.KEY_EMAIL_VERIFY_ENABLED, true);
    }

    /**
     * 开关打开的前提：主机、端口、发件地址齐备且口令可用。
     *
     * <p>存一个「开着但发不出信」的开关，后面每个注册者都会卡在收不到的那封邮件上 ——
     * 邮箱没验证、登不进来，也退不回未验证放行的状态。那比保存时报错难受得多。</p>
     *
     * <p>读的是<b>合并后的整份配置</b>而不是 patch：参数和开关常常在一次请求里一起提交。</p>
     */
    private void requireTransportWhenVerifyEnabled(Map<String, Object> config) {
        MailSettings settings = mailSettingsReader.read(config);
        if (!settings.verifyEnabled() || settings.transportReady()) {
            return;
        }
        if (settings.credentialUnusable()) {
            throw BizException.param("已存的 SMTP 口令解不开（加密密钥可能已变更），请重新填写口令");
        }
        if (settings.credentialIncomplete()) {
            throw BizException.param(CREDENTIAL_INCOMPLETE);
        }
        List<String> missing = new ArrayList<>();
        if (settings.host() == null) {
            missing.add("SMTP 主机");
        }
        if (settings.port() == null) {
            missing.add("SMTP 端口");
        }
        if (settings.from() == null) {
            missing.add("发件地址");
        }
        throw BizException.param("开启邮箱验证前需要先填齐：" + String.join("、", missing));
    }

    /**
     * 取 patch 里的文本项并落到 config：缺键不动，空串清除。
     *
     * @return 归一化后待写入的值；返回 null 表示本项已处理完（未提供，或已清除）
     */
    private String patchText(Map<String, Object> config, Map<String, Object> patch,
                             String key, int maxLength, String label) {
        if (!patch.containsKey(key)) {
            return null;
        }
        Object raw = patch.get(key);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.length() > maxLength) {
            throw BizException.param(label + "不能超过 " + maxLength + " 个字符");
        }
        if (value.isEmpty()) {
            config.remove(key);
            return null;
        }
        if (value.contains(" ")) {
            throw BizException.param(label + "不能包含空格");
        }
        return value;
    }

    private Map<String, Object> readConfig() {
        SiteConfig entity = siteConfigMapper.selectById(SiteConfig.SINGLETON_ID);
        return entity == null ? new LinkedHashMap<>() : JsonUtil.toMap(entity.getConfig());
    }

    private void save(Map<String, Object> config) {
        String json = JsonUtil.toJson(config);
        SiteConfig entity = siteConfigMapper.selectById(SiteConfig.SINGLETON_ID);
        if (entity == null) {
            entity = new SiteConfig();
            entity.setId(SiteConfig.SINGLETON_ID);
            entity.setConfig(json);
            entity.setUpdatedAt(TimeUtil.now());
            siteConfigMapper.insert(entity);
        } else {
            entity.setConfig(json);
            entity.setUpdatedAt(TimeUtil.now());
            siteConfigMapper.updateById(entity);
        }
        // 放在最后：只有真落库了才让缓存失效，否则事务回滚后缓存已被清、下次读又拿到旧值，
        // 虽然结果一样，但白白多打一次库。TTL 会兜住漏掉的失效，不依赖这一步。
        siteBaseUrlResolver.invalidate();
    }

    private SiteConfigVO toVO(Map<String, Object> config) {
        String name = text(config.get(SiteConfig.KEY_NAME));
        String subtitle = text(config.get(SiteConfig.KEY_SUBTITLE));
        String logo = text(config.get(SiteConfig.KEY_LOGO));
        String logoSrc = logo == null ? null : AppPaths.of(SiteConfigVO.LOGO_PREFIX + logo);
        String icon = text(config.get(SiteConfig.KEY_ICON));
        String iconSrc = icon == null ? null : AppPaths.of(SiteConfigVO.ICON_PREFIX + icon);
        return new SiteConfigVO(
                name == null ? DEFAULT_NAME : name,
                subtitle == null ? DEFAULT_SUBTITLE : subtitle,
                logo,
                logoSrc,
                icon,
                iconSrc,
                text(config.get(SiteConfig.KEY_BASE_URL)),
                logoSize(config.get(SiteConfig.KEY_LOGO_WIDTH)),
                logoSize(config.get(SiteConfig.KEY_LOGO_HEIGHT)),
                Boolean.parseBoolean(text(config.get(SiteConfig.KEY_LOGO_ONLY))),
                text(config.get(SiteConfig.KEY_READER_THEME)),
                // 与注册/登录闸门同一个判断，且复用这次已经读出来的配置：不另查一次库，
                // 也不允许前端拿到一个和后端行为不一致的布尔
                mailSettingsReader.read(config).verificationRequired());
    }

    /** 邮件配置的脱敏视图：口令只以「有没有存过」回显，明文与密文都不出服务端。 */
    private SiteMailVO toMailVO(Map<String, Object> config) {
        MailSettings settings = mailSettingsReader.read(config);
        boolean passwordSet = settings.password() != null || settings.credentialUnusable();
        return new SiteMailVO(
                settings.host(),
                settings.port(),
                settings.security(),
                settings.host() == null ? null : settings.effectiveSecurity(),
                settings.username(),
                settings.from(),
                passwordSet,
                settings.credentialUnusable(),
                settings.transportReady(),
                settings.verifyEnabled(),
                settings.verificationRequired());
    }

    /**
     * 读尺寸：写入侧已校验过区间，这里越界只可能是有人直接改了库 ——
     * 当成「没配」回落默认值，好过让公开的站点信息接口抛 500 把整个门户带崩。
     */
    private Integer logoSize(Object raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            int pixels = Integer.parseInt(value);
            return pixels >= LOGO_SIZE_MIN && pixels <= LOGO_SIZE_MAX ? pixels : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取字符串，空白一律归一为 null。 */
    private String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private void write(Path target, MultipartFile file, String extension, String label) {
        try {
            Files.createDirectories(target.getParent());
            byte[] content = file.getBytes();
            if ("svg".equals(extension)) {
                content = imageValidator.sanitizeSvg(content);
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, label + "保存失败");
        }
    }

    /** 删掉站点目录下的旧图（保留 {@code keep}）；失败只记日志，不影响换图这件事本身。 */
    private void deleteImageFile(String stale, String keep) {
        if (stale == null || stale.equals(keep)) {
            return;
        }
        try {
            Files.deleteIfExists(properties.siteDir().resolve(stale));
        } catch (IOException e) {
            log.warn("清理旧站点图片失败 fileName={}", stale, e);
        }
    }
}
