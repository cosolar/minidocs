package cn.minims.minidocs.site;

import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.support.ImageValidator;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.entity.SiteConfig;
import cn.minims.minidocs.site.mapper.SiteConfigMapper;
import cn.minims.minidocs.site.service.impl.SiteConfigServiceImpl;
import cn.minims.minidocs.site.support.SiteBaseUrlResolver;
import cn.minims.minidocs.theme.dto.ThemeDtos.ThemePackVO;
import cn.minims.minidocs.theme.service.ThemeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Logo 显示参数（宽高 / 只显示图片）的存取契约。
 *
 * <p>不启 Spring 容器：这三项都存在 {@code site_config.config} 那一列 JSON 里，要证明的是
 * 「写进去什么、读出来什么」，用一个内存版 mapper 就够了。上传与删除图片那两条路径
 * 牵扯磁盘，不在本类范围内。</p>
 */
@DisplayName("站点 Logo 显示参数：尺寸、开关与清除")
class SiteLogoDisplayTest {

    private AtomicReference<SiteConfig> row;
    private ThemeService themes;
    private SiteConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        row = new AtomicReference<>();
        SiteConfigMapper mapper = mock(SiteConfigMapper.class);
        when(mapper.selectById(SiteConfig.SINGLETON_ID)).thenAnswer(call -> row.get());
        when(mapper.insert(any(SiteConfig.class))).thenAnswer(call -> {
            row.set(call.getArgument(0));
            return 1;
        });
        when(mapper.updateById(any(SiteConfig.class))).thenAnswer(call -> {
            row.set(call.getArgument(0));
            return 1;
        });
        themes = mock(ThemeService.class);
        service = new SiteConfigServiceImpl(mapper, mock(MiniDocsProperties.class), mock(ImageValidator.class),
                mock(AssetService.class), mock(SiteBaseUrlResolver.class), themes);
    }

    @Test
    @DisplayName("宽高与开关写进去就能原样读回，并落到同一份 JSON")
    void roundTripsDisplaySettings() {
        service.update(patch("logoWidth", 120, "logoHeight", 40, "logoOnly", true));

        SiteConfigVO vo = service.get();
        assertThat(vo.logoWidth()).isEqualTo(120);
        assertThat(vo.logoHeight()).isEqualTo(40);
        assertThat(vo.logoOnly()).isTrue();
        assertThat(JsonUtil.toMap(row.get().getConfig()))
                .containsEntry(SiteConfig.KEY_LOGO_WIDTH, 120)
                .containsEntry(SiteConfig.KEY_LOGO_HEIGHT, 40)
                .containsEntry(SiteConfig.KEY_LOGO_ONLY, true);
    }

    @Test
    @DisplayName("只配一边时另一边保持未配，由品牌位用自己的默认尺寸")
    void partialSizeStaysPartial() {
        SiteConfigVO vo = service.update(patch("logoWidth", 200));

        assertThat(vo.logoWidth()).isEqualTo(200);
        assertThat(vo.logoHeight()).isNull();
    }

    @Test
    @DisplayName("越界与非整数的尺寸直接报参数错，不留到渲染时才发现")
    void rejectsOutOfRangeSizes() {
        assertThatThrownBy(() -> service.update(patch("logoWidth", 8)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Logo 宽度");
        assertThatThrownBy(() -> service.update(patch("logoHeight", 600)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Logo 高度");
        assertThatThrownBy(() -> service.update(patch("logoWidth", "宽一点")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("整数");
    }

    @Test
    @DisplayName("空串是清除回默认尺寸，不是把尺寸改成 0")
    void blankClearsSize() {
        service.update(patch("logoWidth", 120));
        SiteConfigVO vo = service.update(patch("logoWidth", ""));

        assertThat(vo.logoWidth()).isNull();
        assertThat(JsonUtil.toMap(row.get().getConfig())).doesNotContainKey(SiteConfig.KEY_LOGO_WIDTH);
    }

    @Test
    @DisplayName("关掉「只显示 Logo」等价于没配过：默认值不占 JSON 一个键")
    void turningOffRemovesKey() {
        service.update(patch("logoOnly", true));
        SiteConfigVO vo = service.update(patch("logoOnly", false));

        assertThat(vo.logoOnly()).isFalse();
        assertThat(JsonUtil.toMap(row.get().getConfig())).doesNotContainKey(SiteConfig.KEY_LOGO_ONLY);
    }

    @Test
    @DisplayName("库里被手改成越界值时读成未配，而不是让公开接口抛错")
    void corruptStoredSizeFallsBackToDefault() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(SiteConfig.KEY_LOGO_WIDTH, 9999);
        config.put(SiteConfig.KEY_LOGO_HEIGHT, "abc");
        SiteConfig entity = new SiteConfig();
        entity.setId(SiteConfig.SINGLETON_ID);
        entity.setConfig(JsonUtil.toJson(config));
        row.set(entity);

        SiteConfigVO vo = service.get();
        assertThat(vo.logoWidth()).isNull();
        assertThat(vo.logoHeight()).isNull();
    }

    @Test
    @DisplayName("默认阅读主题：写进去原样读回，空串清除，未安装的 id 直接拒绝")
    void readerThemeRoundTripsAndValidates() {
        when(themes.list()).thenReturn(List.of(new ThemePackVO(
                "pine", "松涛", null, null, null, null, Map.of("--md-bg", "#fff"), Map.of("--md-bg", "#111"), true)));

        SiteConfigVO vo = service.update(patch("readerTheme", "pine"));
        assertThat(vo.readerTheme()).isEqualTo("pine");
        assertThat(JsonUtil.toMap(row.get().getConfig()))
                .containsEntry(SiteConfig.KEY_READER_THEME, "pine");

        vo = service.update(patch("readerTheme", ""));
        assertThat(vo.readerTheme()).isNull();
        assertThat(JsonUtil.toMap(row.get().getConfig())).doesNotContainKey(SiteConfig.KEY_READER_THEME);

        assertThatThrownBy(() -> service.update(patch("readerTheme", "no-such-theme")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    private Map<String, Object> patch(Object... pairs) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            patch.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return patch;
    }
}
