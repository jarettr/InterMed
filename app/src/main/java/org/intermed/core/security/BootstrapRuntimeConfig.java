package org.intermed.core.security;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Bootstrap-safe view of the small runtime config subset needed by security
 * helpers that are appended to the bootstrap class loader.
 */
final class BootstrapRuntimeConfig {

    private static final String RESOURCE_NAME = "intermed-runtime.properties";

    private BootstrapRuntimeConfig() {
    }

    static boolean isSecurityStrictMode() {
        return getBoolean("security.strict.mode", true);
    }

    static boolean isLegacyBroadPermissionDefaultsEnabled() {
        return getBoolean("security.legacy.broad.permissions.enabled", false);
    }

    static Path getGameDir() {
        Properties properties = loadProperties();
        String configured = firstNonBlank(
            System.getProperty("runtime.game.dir"),
            properties.getProperty("runtime.game.dir")
        );
        Path fallback = Paths.get(".");
        return resolvePath(configured, fallback, fallback);
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        Properties properties = loadProperties();
        String value = System.getProperty(key, properties.getProperty(key));
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        loadBundledDefaults(properties);

        Path bootstrapGameDir = resolvePath(
            firstNonBlank(System.getProperty("runtime.game.dir"), properties.getProperty("runtime.game.dir")),
            Paths.get("."),
            Paths.get(".")
        );
        Path bootstrapConfigDir = resolvePath(
            firstNonBlank(System.getProperty("runtime.config.dir"), properties.getProperty("runtime.config.dir")),
            bootstrapGameDir.resolve("config"),
            bootstrapGameDir
        );
        loadExternalOverrides(properties, bootstrapConfigDir.resolve(RESOURCE_NAME).toAbsolutePath().normalize());
        return properties;
    }

    private static void loadBundledDefaults(Properties properties) {
        try (InputStream input = resourceStream(RESOURCE_NAME)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (Exception e) {
            System.err.println("[RuntimeConfig] Failed to load " + RESOURCE_NAME + ": " + e.getMessage());
        }
    }

    private static InputStream resourceStream(String name) {
        ClassLoader loader = BootstrapRuntimeConfig.class.getClassLoader();
        return loader == null ? ClassLoader.getSystemResourceAsStream(name) : loader.getResourceAsStream(name);
    }

    private static void loadExternalOverrides(Properties properties, Path runtimeConfigFile) {
        if (runtimeConfigFile == null || !Files.isRegularFile(runtimeConfigFile)) {
            return;
        }
        try (InputStream input = Files.newInputStream(runtimeConfigFile)) {
            Properties overrides = new Properties();
            overrides.load(input);
            properties.putAll(overrides);
        } catch (Exception e) {
            System.err.println("[RuntimeConfig] Failed to load external overrides from "
                + runtimeConfigFile + ": " + e.getMessage());
        }
    }

    private static Path resolvePath(String configured, Path fallback, Path baseDir) {
        String value = firstNonBlank(configured);
        Path path = value == null ? fallback : Paths.get(value);
        if (!path.isAbsolute() && baseDir != null) {
            path = baseDir.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
