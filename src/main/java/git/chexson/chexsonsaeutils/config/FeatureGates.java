package git.chexson.chexsonsaeutils.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

public final class FeatureGates {

    private FeatureGates() {
    }

    public static boolean isEnabled(ForgeConfigSpec.BooleanValue spec, String key) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return spec.get();
        }
        return StartupConfigReader.readBoolean(key, spec.getDefault());
    }
}
