package cn.minims.minidocs.site.service;

import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.dto.SiteDtos.SiteMailVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 站点级配置：品牌名称 / 副标题 / Logo / 图标 / 站点基址 / 邮件发送。
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

    /** 上传站点图标（正方形）：落盘、换新名、删旧图，返回更新后的配置。 */
    SiteConfigVO saveIcon(MultipartFile file);

    /** 移除站点图标（删文件 + 清配置项）。 */
    SiteConfigVO removeIcon();

    /** 读取站点图标文件；口径与 {@link #loadLogo} 一致。 */
    AssetService.Asset loadIcon(String fileName);

    /**
     * 读取邮件配置（脱敏）。
     *
     * <p>与 {@link #get()} 分开：品牌配置谁都能读，邮件配置只有平台管理员能读 ——
     * 主机名、发件地址、有没有存口令，都是部署方的基础设施信息。</p>
     */
    SiteMailVO getMail();

    /**
     * 浅合并保存邮件配置：只认 patch 里显式带了的那几项，值为空串即清除。
     *
     * <p>口令一项的口径不同 —— 空串/缺键都是<b>沿用库里已有的那份</b>，因为表单永不回显口令，
     * 「没填」无法区分「不想改」和「想清空」。想清空请显式传 {@code smtpPasswordClear: true}。</p>
     *
     * <p>开启「要求注册邮箱验证」的前提是传输参数齐备，否则直接报参数错：
     * 存一个发不出信的开关，等于让后面每一个注册者收不到邮件又进不去站点。</p>
     */
    SiteMailVO updateMail(Map<String, Object> patch);
}
