package com.szh.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 简单配置管理器，使用 JDK 内置 Properties 文件
 * 文件位置：程序运行目录下的 app_config.properties
 */
public class ConfigManager {

    private final File file;
    private final Properties props = new Properties();

    public ConfigManager(String filename) {
        this.file = new File(filename);
        load();
    }

    private void load() {
        if (file.exists()) {
            try (Reader reader = new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException ignored) {}
        }
    }

    public String get(String key) {
        return props.getProperty(key, "");
    }

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public void set(String key, String value) {
        if (value == null || value.isEmpty()) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
    }

    public void save() {
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            props.store(writer, "coreTools config");
        } catch (IOException ignored) {}
    }
}
