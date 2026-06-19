package git.chexson.chexsonsaeutils.config;

import java.nio.file.Path;

public final class FtbUltimineMemoryCardFeatureGate {

    private static final String CONFIG_KEY = "ftbUltimineMemoryCardEnabled";

    private FtbUltimineMemoryCardFeatureGate() {
    }

    public static boolean isEnabledAtStartup() {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return ChexsonsaeutilsCompatibilityConfig.FTB_ULTIMINE_MEMORY_CARD_ENABLED.get();
        }
        return StartupConfigBooleanReader.read(
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.FTB_ULTIMINE_MEMORY_CARD_ENABLED.getDefault()
        );
    }

    public static boolean isEnabledAtStartup(Path configFile) {
        return StartupConfigBooleanReader.read(
                configFile,
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.FTB_ULTIMINE_MEMORY_CARD_ENABLED.getDefault()
        );
    }
}
