package com.example.util;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PathUtil {

    /**
     * 시스템 프로퍼티(propertyKey)로 기본 디렉토리를 오버라이드할 수 있는 경로 해석.
     * 프로퍼티가 설정되어 있으면 해당 값을 base로, 없으면 JAR 디렉토리를 base로
     * 사용하여 defaultPath를 resolve한다.
     */
    public static Path resolve(String propertyKey, String defaultPath) {
        String override = System.getProperty(propertyKey);
        Path base = (override != null && !override.isBlank())
                ? Path.of(override)
                : getJarDir();
        return base.resolve(defaultPath);
    }

    public static Path getJarDir() {
        try {
            Path jarPath = Path.of(
                    PathUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(jarPath) ? jarPath : jarPath.getParent();
        } catch (URISyntaxException | NullPointerException e) {
            return Path.of(".");
        }
    }
}
