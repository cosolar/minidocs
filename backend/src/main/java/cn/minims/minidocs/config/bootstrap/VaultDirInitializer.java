package cn.minims.minidocs.config.bootstrap;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 在容器启动前创建 VAULT_HOME 及其子目录（vaults / logs）。
 * SQLite 数据源不会自动创建缺失的父目录，因此需要提前准备。
 */
public class VaultDirInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment env = applicationContext.getEnvironment();
        String home = env.getProperty("minidocs.vault-home", "./data");
        Path root = Paths.get(home).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("vaults"));
            Files.createDirectories(root.resolve("logs"));
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建应用数据目录：" + root, e);
        }
    }
}
