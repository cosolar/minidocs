package cn.minims.minidocs;

import cn.minims.minidocs.config.bootstrap.VaultDirInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MiniDocs · 极简知识库平台 启动类。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MiniDocsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(MiniDocsApplication.class);
        // 数据源（SQLite 单文件）依赖 VAULT_HOME 目录存在，必须在容器启动前创建
        application.addInitializers(new VaultDirInitializer());
        application.run(args);
    }
}
