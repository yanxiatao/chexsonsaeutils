package git.chexson.chexsonsaeutils.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ChexsonsaeutilsCompatibilityConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue CRAFTING_CONTINUATION_ENABLED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CRAFTING_CONTINUATION_ENABLED = builder
                .comment("Disable the AE2 crafting continuation / ignore-missing feature bundle. Takes effect after restart.")
                .define("craftingContinuationEnabled", true);
        SPEC = builder.build();
    }

    private ChexsonsaeutilsCompatibilityConfig() {
    }
}
