package git.chexson.chexsonsaeutils.config;

import java.nio.file.Path;

public final class ContinuationFeatureGate {

    private ContinuationFeatureGate() {
    }

    public static boolean isEnabledAtStartup() {
        return FeatureGateHelper.isEnabledAtStartup(
                "craftingContinuationEnabled",
                () -> ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED.get(),
                () -> ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED.getDefault()
        );
    }

    public static boolean isEnabledAtStartup(Path configFile) {
        return FeatureGateHelper.isEnabledAtStartup(
                configFile,
                "craftingContinuationEnabled",
                () -> ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED.getDefault()
        );
    }
}
