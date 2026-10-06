package cn.minims.minidocs.site.service.impl;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.common.web.AppPaths;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.support.ImageValidator;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.mapper.SiteConfigMapper;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.site.support.SiteBaseUrlResolver;
import cn.minims.minidocs.site.support.SiteBaseUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    private static final String DEFAULT_SUBTITLE = "极简知识库";

    private static final int MAX_NAME = 32;
    private static final int MAX_SUBTITLE = 48;

    private final SiteConfigMapper siteConfigMapper;
    private final MiniDocsProperties properties;
    private final ImageValidator imageValidator;
    private final AssetService assetService;
    private final SiteBaseUrlResolver siteBaseUrlResolver;

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
            applyBaseUrl(config, patch);
        }
        save(config);
        return toVO(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO saveLogo(MultipartFile file) {
        String extension = imageValidator.validate(file, properties.getCoverMaxSize());
        String fileName = FileNameUtil.buildImageName(extension);
        write(properties.siteDir().resolve(fileName), file, extension);
        Map<String, Object> config = readConfig();
        // 先落新图再删旧图：中途失败最坏是留个孤儿文件，不会把站点 Logo 弄没
        deleteLogoFile(text(config.get(SiteConfig.KEY_LOGO)), fileName);
        config.put(SiteConfig.KEY_LOGO, fileName);
        save(config);
        return toVO(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO removeLogo() {
        Map<String, Object> config = readConfig();
        deleteLogoFile(text(config.get(SiteConfig.KEY_LOGO)), null);
        config.remove(SiteConfig.KEY_LOGO);
        save(config);
        return toVO(config);
    }

    @Override
    public AssetService.Asset loadLogo(String fileName) {
        return assetService.load(properties.siteDir(), fileName);
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
        return new SiteConfigVO(
                name == null ? DEFAULT_NAME : name,
                subtitle == null ? DEFAULT_SUBTITLE : subtitle,
                logo,
                logoSrc,
                text(config.get(SiteConfig.KEY_BASE_URL)));
    }

    /** 取字符串，空白一律归一为 null。 */
    private String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private void write(Path target, MultipartFile file, String extension) {
        try {
            Files.createDirectories(target.getParent());
            byte[] content = file.getBytes();
            if ("svg".equals(extension)) {
                content = imageValidator.sanitizeSvg(content);
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "Logo 保存失败");
        }
    }

    /** 删掉站点目录下的旧 Logo（保留 {@code keep}）；失败只记日志，不影响换图这件事本身。 */
    private void deleteLogoFile(String stale, String keep) {
        if (stale == null || stale.equals(keep)) {
            return;
        }
        try {
            Files.deleteIfExists(properties.siteDir().resolve(stale));
        } catch (IOException e) {
            log.warn("清理旧站点 Logo 失败 fileName={}", stale, e);
        }
    }
}
