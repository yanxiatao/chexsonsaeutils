package git.chexson.chexsonsaeutils.config;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProcessingPatternReplacementFeatureGate {

    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
    private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";
    private static final Pattern PROCESSING_PATTERN_REPLACEMENT_ENABLED_PATTERN = Pattern.compile(
            "(?m)^\\s*processingPatternReplacementEnabled\\s*=\\s*(true|false)\\s*(?:#.*)?$");

    private ProcessingPatternReplacementFeatureGate() {
    }

    public static boolean isEnabledAtStartup() {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED.get();
        }
        return isEnabledAtStartup(resolveCommonConfigPath());
    }

    public static boolean isEnabledAtStartup(Path configFile) {
        Boolean persistedValue = readPersistedEnabled(configFile);
        if (persistedValue != null) {
            return persistedValue;
        }
        return ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED.getDefault();
    }

    private static Path resolveCommonConfigPath() {
        String overriddenConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
        if (overriddenConfigDir != null && !overriddenConfigDir.isBlank()) {
            return Path.of(overriddenConfigDir).resolve(COMMON_CONFIG_FILE);
        }
        return FMLPaths.CONFIGDIR.get().resolve(COMMON_CONFIG_FILE);
    }

    private static Boolean readPersistedEnabled(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return null;
        }

        try {
            String configContent = Files.readString(configFile);
            Matcher matcher = PROCESSING_PATTERN_REPLACEMENT_ENABLED_PATTERN.matcher(configContent);
            if (!matcher.find()) {
                return null;
            }
            return Boolean.parseBoolean(matcher.group(1));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
