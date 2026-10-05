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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    /** 单行表的固定主键 */
    private static final int SINGLETON_ID = 1;

    private static final String KEY_NAME = "name";
    private static final String KEY_SUBTITLE = "subtitle";
    private static final String KEY_LOGO = "logo";

    private static final String DEFAULT_NAME = "MiniDocs";
    private static final String DEFAULT_SUBTITLE = "极简知识库";

    private static final int MAX_NAME = 32;
    private static final int MAX_SUBTITLE = 48;

    private final SiteConfigMapper siteConfigMapper;
    private final MiniDocsProperties properties;
    private final ImageValidator imageValidator;
    private final AssetService assetService;

    @Override
    public SiteConfigVO get() {
        return toVO(readConfig());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO update(Map<String, Object> patch) {
        Map<String, Object> config = readConfig();
        if (patch != null) {
            applyText(config, patch, KEY_NAME, MAX_NAME, "站点名称");
            applyText(config, patch, KEY_SUBTITLE, MAX_SUBTITLE, "站点副标题");
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
        deleteLogoFile(text(config.get(KEY_LOGO)), fileName);
        config.put(KEY_LOGO, fileName);
        save(config);
        return toVO(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteConfigVO removeLogo() {
        Map<String, Object> config = readConfig();
        deleteLogoFile(text(config.get(KEY_LOGO)), null);
        config.remove(KEY_LOGO);
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

    private Map<String, Object> readConfig() {
        SiteConfig entity = siteConfigMapper.selectById(SINGLETON_ID);
        return entity == null ? new LinkedHashMap<>() : JsonUtil.toMap(entity.getConfig());
    }

    private void save(Map<String, Object> config) {
        String json = JsonUtil.toJson(config);
        SiteConfig entity = siteConfigMapper.selectById(SINGLETON_ID);
        if (entity == null) {
            entity = new SiteConfig();
            entity.setId(SINGLETON_ID);
            entity.setConfig(json);
            entity.setUpdatedAt(TimeUtil.now());
            siteConfigMapper.insert(entity);
        } else {
            entity.setConfig(json);
            entity.setUpdatedAt(TimeUtil.now());
            siteConfigMapper.updateById(entity);
        }
    }

    private SiteConfigVO toVO(Map<String, Object> config) {
        String name = text(config.get(KEY_NAME));
        String subtitle = text(config.get(KEY_SUBTITLE));
        String logo = text(config.get(KEY_LOGO));
        String logoSrc = logo == null ? null : AppPaths.of(SiteConfigVO.LOGO_PREFIX + logo);
        return new SiteConfigVO(
                name == null ? DEFAULT_NAME : name,
                subtitle == null ? DEFAULT_SUBTITLE : subtitle,
                logo,
                logoSrc);
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
