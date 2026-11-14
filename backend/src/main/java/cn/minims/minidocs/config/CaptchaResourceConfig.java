package cn.minims.minidocs.config;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.resource.CrudResourceStore;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.common.model.dto.ResourceMap;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 验证码资源：青绿水彩背景 + 拼图模板。
 *
 * <p>为什么不直接用库自带资源（{@code captcha.init-default-resource: true}）：它只带一张
 * 风景照背景，与站点的薄荷青绿调子格格不入；而验证码是登录页的第一眼，一张突兀的照片
 * 会让整页看起来像别人的网站。</p>
 *
 * <p>背景图在 {@code resources/captcha/bg-*.png}，由脚本生成：薄荷渐变底 + 水彩晕染 +
 * 纸纹噪点，边上零星几个墨点。淡雅但不单调 —— 纯色或规则渐变会让缺口边缘太好找，
 * 纹理与晕染正好给机器识别添点噪声。六张轮换，避免「一张图被记住」。</p>
 *
 * <p>拼图形状沿用库内置的两套滑块模板（{@code META-INF/cut-image/template/slider_*}）：
 * 那是拼图缺口的几何形状，不涉及配色，样式由前端外框承担。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CaptchaResourceConfig {

    /** 与水彩背景图同名的数量，见 {@code resources/captcha/bg-N.png}。 */
    private static final int BACKGROUNDS = 6;
    /** 资源标签；生成时按类型取，tag 保持一致即可。 */
    private static final String TAG = "default";
    /** 库内置的两套滑块拼图形状，轮换使用，免得每次缺口都一个样。 */
    private static final String[] TEMPLATE_DIRS = {"slider_1", "slider_2"};

    private final ResourceStore resourceStore;

    @PostConstruct
    void registerBrandResources() {
        if (!(resourceStore instanceof CrudResourceStore store)) {
            log.warn("验证码资源存储不可写（{}），沿用库默认资源", resourceStore.getClass().getName());
            return;
        }
        // 配置里已关掉内置资源，这里再清一次，保证「池子里只有我们的图」
        store.clearAllResources();
        store.clearAllTemplates();

        for (int i = 1; i <= BACKGROUNDS; i++) {
            store.addResource(CaptchaTypeConstant.SLIDER,
                    new Resource("classpath", "captcha/bg-" + i + ".png", TAG));
        }
        for (String dir : TEMPLATE_DIRS) {
            ResourceMap template = new ResourceMap(TAG);
            // 键名必须带 .png 后缀：生成器按 "active.png" / "fixed.png" 取图，
            // 写成 "active" / "fixed" 会在生成时报「该模板下未找到 fixed.png」
            template.put("active.png", new Resource("classpath", "META-INF/cut-image/template/" + dir + "/active.png"));
            template.put("fixed.png", new Resource("classpath", "META-INF/cut-image/template/" + dir + "/fixed.png"));
            store.addTemplate(CaptchaTypeConstant.SLIDER, template);
        }
        log.info("验证码资源就绪：{} 张品牌背景，{} 套拼图模板", BACKGROUNDS, TEMPLATE_DIRS.length);
    }
}
