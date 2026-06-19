package git.chexson.chexsonsaeutils.config;

import java.nio.file.Path;

public final class EnhancedCraftingStatusFeatureGate {

    private static final String CONFIG_KEY = "enhancedCraftingStatusEnabled";

    private EnhancedCraftingStatusFeatureGate() {
    }

    public static boolean isEnabledAtStartup() {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED.get();
        }
        return StartupConfigBooleanReader.read(
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED.getDefault()
        );
    }

    public static boolean isEnabledAtStartup(Path configFile) {
        return StartupConfigBooleanReader.read(
                configFile,
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED.getDefault()
        );
    }
}
