package cn.minims.minidocs.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.stp.StpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Sa-Token + JWT（HS256，无状态）以及密码编码器。
 *
 * <p>仅引入 spring-security-crypto 提供 BCrypt，不引入 Spring Security 全家桶。</p>
 */
@Configuration
public class SaTokenConfig {

    @Bean
    public StpLogic stpLogicJwt() {
        return new StpLogicJwtForStateless();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
