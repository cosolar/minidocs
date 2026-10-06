package cn.minims.minidocs.site.service;

import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 站点级配置：品牌名称 / 副标题 / Logo / 站点基址。
 *
 * <p>只有一个部署一份配置，故无「谁的」这一维；写入侧由平台管理员门控（见控制器）。</p>
 */
public interface SiteConfigService {

    /** 读取站点配置；从未配置过时回落内置默认品牌。 */
    SiteConfigVO get();

    /**
     * 浅合并保存名称 / 副标题 / 站点基址（值为空串即清除该项，回落到默认）。
     *
     * <p>站点基址写进去即归一化过（补协议、去尾斜杠），并经 {@code SiteBaseUrls} 校验；
     * 保存成功后站点基址缓存立即失效，改完刷新页面就生效，不必重启后端。</p>
     */
    SiteConfigVO update(Map<String, Object> patch);

    /** 上传 Logo：落盘、换新名、删旧图，返回更新后的配置。 */
    SiteConfigVO saveLogo(MultipartFile file);

    /** 移除 Logo（删文件 + 清配置项）。 */
    SiteConfigVO removeLogo();

    /** 读取 Logo 文件；类型、大小、ETag 与缓存交给 {@link AssetService} 统一判定。 */
    AssetService.Asset loadLogo(String fileName);
}
