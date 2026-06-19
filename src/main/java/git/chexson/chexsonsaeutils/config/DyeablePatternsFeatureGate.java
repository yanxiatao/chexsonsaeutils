package git.chexson.chexsonsaeutils.config;

import java.nio.file.Path;

public final class DyeablePatternsFeatureGate {

    private static final String CONFIG_KEY = "dyeablePatternsEnabled";

    private DyeablePatternsFeatureGate() {
    }

    public static boolean isEnabledAtStartup() {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED.get();
        }
        return StartupConfigBooleanReader.read(
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED.getDefault()
        );
    }

    public static boolean isEnabledAtStartup(Path configFile) {
        return StartupConfigBooleanReader.read(
                configFile,
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED.getDefault()
        );
    }
}
