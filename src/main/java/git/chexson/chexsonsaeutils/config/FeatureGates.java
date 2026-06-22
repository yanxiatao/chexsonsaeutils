package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class FeatureGates {

    private FeatureGates() {
    }

    public static boolean isEnabled(ModConfigSpec.BooleanValue spec, String key) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return spec.get();
        }
        return StartupConfigReader.readBoolean(key, spec.getDefault());
    }
}