package cn.minims.minidocs.auth.bootstrap;

import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.tenant.service.TenantService;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时初始化内置管理员（ADMIN_USER / ADMIN_PASS）。
 *
 * <p>管理员不可被禁用/拒绝/删除，角色不可变更。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private final MiniDocsProperties properties;
    private final UserService userService;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.getAdminUser();
        if (username == null || username.isBlank()) {
            log.warn("未配置 ADMIN_USER，跳过内置管理员初始化");
            return;
        }
        User existing = userService.findByUsername(username);
        if (existing == null) {
            if (properties.getAdminPass() == null || properties.getAdminPass().isBlank()) {
                log.warn("未配置 ADMIN_PASS，跳过内置管理员初始化");
                return;
            }
            User admin = new User();
            admin.setUsername(username.trim().toLowerCase(java.util.Locale.ROOT));
            admin.setPasswordHash(passwordEncoder.encode(properties.getAdminPass()));
            admin.setDisplayName("超级管理员");
            admin.setRole(User.ROLE_ADMIN);
            admin.setStatus(User.STATUS_ACTIVE);
            userService.save(admin);
            tenantService.ensurePersonalTenant(admin.getId());
            log.info("已初始化内置管理员：{}", admin.getUsername());
            return;
        }
        if (!existing.isAdmin() || !existing.isActive()) {
            User update = new User();
            update.setId(existing.getId());
            update.setRole(User.ROLE_ADMIN);
            update.setStatus(User.STATUS_ACTIVE);
            userService.updateById(update);
            log.info("已修正内置管理员角色与状态：{}", existing.getUsername());
        }
    }
}
