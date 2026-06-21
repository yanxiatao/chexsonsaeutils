package git.chexson.chexsonsaeutils.config;

import java.nio.file.Path;

public final class ProcessingPatternReplacementFeatureGate {

    private ProcessingPatternReplacementFeatureGate() {
    }

    public static boolean isEnabledAtStartup() {
        return FeatureGateHelper.isEnabledAtStartup(
                "processingPatternReplacementEnabled",
                () -> ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED.get(),
                () -> ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED.getDefault()
        );
    }

    public static boolean isEnabledAtStartup(Path configFile) {
        return FeatureGateHelper.isEnabledAtStartup(
                configFile,
                "processingPatternReplacementEnabled",
                () -> ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED.getDefault()
        );
    }
}
