package cn.minims.minidocs.theme.service;

import cn.minims.minidocs.theme.dto.ThemeDtos.ThemePackVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 阅读主题包：安装在 {@code VAULT_HOME/site/themes} 下的 JSON 文件。
 *
 * <p>内置主题在首次启动时从 classpath 复制进来，之后与上传主题走同一套读取与校验；
 * 「内置」只用于界面标注与「恢复内置主题」，不形成第二条加载通路。</p>
 */
public interface ThemeService {

    /** 已安装主题（含内置），默认主题不在列表里 —— 它由前端内置。 */
    List<ThemePackVO> list();

    /** 安装或覆盖一个主题包。 */
    ThemePackVO install(MultipartFile file);

    /** 卸载主题；内置主题卸载后不会在下次启动时自动回来。 */
    void uninstall(String id);

    /** 把 classpath 里的内置主题重新复制回来，已有的同名包会被覆盖。 */
    List<ThemePackVO> reseed();
}
