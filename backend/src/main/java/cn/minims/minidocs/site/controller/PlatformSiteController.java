package cn.minims.minidocs.site.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.mail.MailService;
import cn.minims.minidocs.site.dto.SiteDtos.MailTestRequest;
import cn.minims.minidocs.site.dto.SiteDtos.SiteConfigVO;
import cn.minims.minidocs.site.dto.SiteDtos.SiteMailVO;
import cn.minims.minidocs.site.service.SiteConfigService;
import cn.minims.minidocs.site.support.SiteBaseUrlResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 站点配置的写入侧：{@code /api/platform/**}，门槛是平台管理员（{@code AdminInterceptor}）。
 *
 * <p>站点品牌是全部署一份的东西，不属于任何组织，所以不能挂在 {@code /api/console/{org}/**} 下 ——
 * 那条通道的门是「组织成员」，会让任一组织的管理员改掉别人的门户名称。</p>
 */
@Tag(name = "站点配置（平台）")
@RestController
@RequestMapping("/api/platform/site")
@RequiredArgsConstructor
public class PlatformSiteController {

    private final SiteConfigService siteConfigService;
    private final MailService mailService;
    private final SiteBaseUrlResolver siteBaseUrlResolver;

    @Operation(summary = "保存站点配置")
    @PutMapping
    public ApiResponse<SiteConfigVO> update(@RequestBody Map<String, Object> patch) {
        return ApiResponse.ok(siteConfigService.update(patch));
    }

    @Operation(summary = "上传站点 Logo")
    @PostMapping("/logo")
    public ApiResponse<SiteConfigVO> uploadLogo(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(siteConfigService.saveLogo(file));
    }

    @Operation(summary = "移除站点 Logo")
    @DeleteMapping("/logo")
    public ApiResponse<SiteConfigVO> removeLogo() {
        return ApiResponse.ok(siteConfigService.removeLogo());
    }

    @Operation(summary = "上传站点图标")
    @PostMapping("/icon")
    public ApiResponse<SiteConfigVO> uploadIcon(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(siteConfigService.saveIcon(file));
    }

    @Operation(summary = "移除站点图标")
    @DeleteMapping("/icon")
    public ApiResponse<SiteConfigVO> removeIcon() {
        return ApiResponse.ok(siteConfigService.removeIcon());
    }

    /**
     * 邮件配置单独一条读路径，不并进 {@code SiteConfigVO}：
     * 品牌配置是公开的（分享页也要读），邮件配置只给平台管理员，混在一个响应里
     * 等于把「谁能看到这台机器的基础设施」交给一个无关的公开接口。
     */
    @Operation(summary = "读取邮件配置（脱敏，不含口令）")
    @GetMapping("/mail")
    public ApiResponse<SiteMailVO> getMail() {
        return ApiResponse.ok(siteConfigService.getMail());
    }

    @Operation(summary = "保存邮件配置")
    @PutMapping("/mail")
    public ApiResponse<SiteMailVO> updateMail(@RequestBody Map<String, Object> patch) {
        return ApiResponse.ok(siteConfigService.updateMail(patch));
    }

    /**
     * 测试发信：把当前存的 SMTP 参数真发一封，成功失败都把邮件服务器给的原因带回界面。
     *
     * <p>同步执行、按管理员限速，理由见 {@code MailService#sendTest}。
     * 这是唯一「填了就能立刻知道对不对」的配置项 —— SMTP 的失败原因（证书、端口、认证、
     * 服务商拦第三方客户端）在看不到报错的注册流程里根本查不出来。</p>
     */
    @Operation(summary = "发送测试邮件")
    @PostMapping("/mail/test")
    public ApiResponse<Void> testMail(@RequestBody MailTestRequest request, HttpServletRequest servletRequest) {
        mailService.sendTest(request.to(), UserContext.requireUserId(),
                siteBaseUrlResolver.resolve(servletRequest));
        return ApiResponse.ok();
    }
}
