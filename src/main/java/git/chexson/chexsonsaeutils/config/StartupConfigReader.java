package git.chexson.chexsonsaeutils.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StartupConfigReader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
    private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";

    private StartupConfigReader() {
    }

    public static boolean readBoolean(String key, boolean defaultValue) {
        return read(resolveCommonConfigPath(), key, "true|false", defaultValue, Boolean::parseBoolean);
    }

    public static boolean readBoolean(Path configFile, String key, boolean defaultValue) {
        return read(configFile, key, "true|false", defaultValue, Boolean::parseBoolean);
    }

    public static int readInt(String key, int defaultValue) {
        return read(resolveCommonConfigPath(), key, "-?\\d+", defaultValue, Integer::parseInt);
    }

    public static int readInt(Path configFile, String key, int defaultValue) {
        return read(configFile, key, "-?\\d+", defaultValue, Integer::parseInt);
    }

    public static int readIntClamped(String key, int defaultValue, int min, int max) {
        return clamp(readInt(key, defaultValue), min, max);
    }

    private static <T> T read(Path configFile, String key, String valuePattern, T defaultValue,
            Function<String, T> parser) {
        T persistedValue = readPersistedValue(configFile, key, valuePattern, parser);
        return persistedValue != null ? persistedValue : defaultValue;
    }

    private static Path resolveCommonConfigPath() {
        String overriddenConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
        if (overriddenConfigDir != null && !overriddenConfigDir.isBlank()) {
            return Path.of(overriddenConfigDir).resolve(COMMON_CONFIG_FILE);
        }
        return FMLPaths.CONFIGDIR.get().resolve(COMMON_CONFIG_FILE);
    }

    private static <T> T readPersistedValue(Path configFile, String key, String valuePattern,
            Function<String, T> parser) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return null;
        }

        Pattern pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(" + valuePattern + ")\\s*(?:#.*)?$");
        try {
            String configContent = Files.readString(configFile);
            Matcher matcher = pattern.matcher(configContent);
            if (!matcher.find()) {
                return null;
            }
            return parser.apply(matcher.group(1));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to read startup config key {} from {}", key, configFile, exception);
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}