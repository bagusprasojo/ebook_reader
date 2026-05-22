package com.ebook.reader.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class ConfigLoader {
    private static final Path USER_CONFIG_PATH = Paths.get(System.getProperty("user.home"), ".ebook-reader", "config.properties");

    public static AppConfig load() {
        String env = System.getProperty("reader.env", "dev");
        String resource = "/config/application-" + env + ".properties";
        Properties props = new Properties();
        try (InputStream in = ConfigLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing config: " + resource);
            }
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
        applyUserOverride(props);
        String keyFromFile = props.getProperty("drm.masterKeyB64", "").trim();
        String keyFromEnv = System.getenv("READER_MASTER_KEY_B64");
        String resolvedKey = !keyFromFile.isBlank() ? keyFromFile : (keyFromEnv == null ? "" : keyFromEnv.trim());

        return new AppConfig(
            props.getProperty("api.baseUrl").trim(),
            props.getProperty("api.downloadPath").trim(),
            resolvedKey
        );
    }

    public static Path userConfigPath() {
        return USER_CONFIG_PATH;
    }

    public static void saveUserConfig(String baseUrl, String downloadPath, String masterKeyB64) throws IOException {
        Files.createDirectories(USER_CONFIG_PATH.getParent());
        Properties p = new Properties();
        p.setProperty("api.baseUrl", baseUrl == null ? "" : baseUrl.trim());
        p.setProperty("api.downloadPath", downloadPath == null ? "" : downloadPath.trim());
        p.setProperty("drm.masterKeyB64", masterKeyB64 == null ? "" : masterKeyB64.trim());
        try (OutputStream out = Files.newOutputStream(USER_CONFIG_PATH)) {
            p.store(out, "Ebook Reader user-local config");
        }
    }

    private static void applyUserOverride(Properties target) {
        if (!Files.exists(USER_CONFIG_PATH)) {
            return;
        }
        Properties user = new Properties();
        try (InputStream in = Files.newInputStream(USER_CONFIG_PATH)) {
            user.load(in);
            for (String k : user.stringPropertyNames()) {
                String v = user.getProperty(k);
                if (v != null && !v.isBlank()) {
                    target.setProperty(k, v);
                }
            }
        } catch (IOException ignored) {
        }
    }
}
