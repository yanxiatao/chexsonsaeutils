package git.chexson.chexsonsaeutils.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StartupConfigIntReader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
    private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";

    private StartupConfigIntReader() {
    }

    static int read(String key, int defaultValue) {
        return read(resolveCommonConfigPath(), key, defaultValue);
    }

    static int read(Path configFile, String key, int defaultValue) {
        Integer persistedValue = readPersistedValue(configFile, key);
        if (persistedValue != null) {
            return persistedValue;
        }
        return defaultValue;
    }

    private static Path resolveCommonConfigPath() {
        String overriddenConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
        if (overriddenConfigDir != null && !overriddenConfigDir.isBlank()) {
            return Path.of(overriddenConfigDir).resolve(COMMON_CONFIG_FILE);
        }
        return FMLPaths.CONFIGDIR.get().resolve(COMMON_CONFIG_FILE);
    }

    private static Integer readPersistedValue(Path configFile, String key) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return null;
        }

        Pattern pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(-?\\d+)\\s*(?:#.*)?$");
        try {
            String configContent = Files.readString(configFile);
            Matcher matcher = pattern.matcher(configContent);
            if (!matcher.find()) {
                return null;
            }
            return Integer.parseInt(matcher.group(1));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to read startup config key {} from {}", key, configFile, exception);
            return null;
        }
    }
}
