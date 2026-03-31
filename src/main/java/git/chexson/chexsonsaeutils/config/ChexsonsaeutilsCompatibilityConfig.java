package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ChexsonsaeutilsCompatibilityConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CRAFTING_CONTINUATION_ENABLED;
    public static final ModConfigSpec.BooleanValue PROCESSING_PATTERN_REPLACEMENT_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CRAFTING_CONTINUATION_ENABLED = builder
                .comment("Disable the AE2 crafting continuation / ignore-missing feature bundle. Takes effect after restart.")
                .define("craftingContinuationEnabled", true);
        PROCESSING_PATTERN_REPLACEMENT_ENABLED = builder
                .comment("Disable the AE2 processing pattern replacement feature bundle. Takes effect after restart.")
                .define("processingPatternReplacementEnabled", true);
        SPEC = builder.build();
    }

    private ChexsonsaeutilsCompatibilityConfig() {
    }
}
