package git.chexson.chexsonsaeutils.config;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FeatureGateHelper {

    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
    private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";

    private FeatureGateHelper() {
    }

    static boolean isEnabledAtStartup(
            String configKey,
            Supplier<Boolean> configValue,
            Supplier<Boolean> defaultValue
    ) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return configValue.get();
        }
        return isEnabledAtStartup(resolveCommonConfigPath(), configKey, defaultValue);
    }

    static boolean isEnabledAtStartup(Path configFile, String configKey, Supplier<Boolean> defaultValue) {
        Boolean persistedValue = readPersistedEnabled(configFile, configKey);
        if (persistedValue != null) {
            return persistedValue;
        }
        return defaultValue.get();
    }

    private static Path resolveCommonConfigPath() {
        String overriddenConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
        if (overriddenConfigDir != null && !overriddenConfigDir.isBlank()) {
            return Path.of(overriddenConfigDir).resolve(COMMON_CONFIG_FILE);
        }
        return FMLPaths.CONFIGDIR.get().resolve(COMMON_CONFIG_FILE);
    }

    private static Boolean readPersistedEnabled(Path configFile, String configKey) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return null;
        }

        try {
            String configContent = Files.readString(configFile);
            Pattern pattern = Pattern.compile(
                    "(?m)^\\s*" + Pattern.quote(configKey) + "\\s*=\\s*(true|false)\\s*(?:#.*)?$");
            Matcher matcher = pattern.matcher(configContent);
            if (!matcher.find()) {
                return null;
            }
            return Boolean.parseBoolean(matcher.group(1));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
