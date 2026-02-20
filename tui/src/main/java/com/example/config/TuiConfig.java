package com.example.config;

import com.example.err.ErrorHandler;
import com.example.util.PathUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public class TuiConfig {

    private static final String PROPERTY_KEY = "tui.config.dir";
    private static final String DEFAULT_PATH = "config.properties";
    private static final String LANG_KEY = "lang";

    private final Properties properties;
    private final Path configPath;
    private final ErrorHandler errorHandler;

    private TuiConfig(Properties properties, Path configPath) {
        this.properties = properties;
        this.configPath = configPath;
        this.errorHandler = new ErrorHandler();
    }

    public static TuiConfig load() {
        Path path = PathUtil.resolve(PROPERTY_KEY, DEFAULT_PATH);
        Properties props = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                new ErrorHandler().handle(e, "TuiConfig.load");
            }
        }
        return new TuiConfig(props, path);
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStream out = Files.newOutputStream(configPath)) {
                properties.store(out, "Tui Configuration");
            }
        } catch (IOException e) {
            errorHandler.handle(e, "TuiConfig.save");
        }
    }

    public Locale getLocale() {
        String lang = properties.getProperty(LANG_KEY);
        if (lang == null) {
            return null;
        }
        return Locale.forLanguageTag(lang);
    }

    public void setLocale(Locale locale) {
        properties.setProperty(LANG_KEY, locale.toLanguageTag());
    }

}
