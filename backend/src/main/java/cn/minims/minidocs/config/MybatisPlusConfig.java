package cn.minims.minidocs.config;

import cn.minims.minidocs.common.type.FlexibleLocalDateTypeHandler;
import cn.minims.minidocs.common.type.FlexibleLocalDateTimeTypeHandler;
import cn.minims.minidocs.common.util.TimeUtil;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 *
 * <p>分页插件不硬编码 DbType，交由 JDBC URL 自动识别，保证 SQLite / MySQL 行为一致。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }

    @Bean
    public ConfigurationCustomizer miniDocsTypeHandlerCustomizer() {
        return configuration -> {
            configuration.getTypeHandlerRegistry().register(LocalDateTime.class, new FlexibleLocalDateTimeTypeHandler());
            configuration.getTypeHandlerRegistry().register(LocalDate.class, new FlexibleLocalDateTypeHandler());
        };
    }

    @Bean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = TimeUtil.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "joinedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, TimeUtil.now());
            }
        };
    }

    /** 便于业务代码引用的填充策略常量。 */
    public static final FieldFill INSERT_FILL = FieldFill.INSERT;
}
