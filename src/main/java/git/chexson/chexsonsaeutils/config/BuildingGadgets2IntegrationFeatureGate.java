package git.chexson.chexsonsaeutils.config;

import java.nio.file.Path;

public final class BuildingGadgets2IntegrationFeatureGate {

    private static final String CONFIG_KEY = "buildingGadgets2IntegrationEnabled";

    private BuildingGadgets2IntegrationFeatureGate() {
    }

    public static boolean isEnabledAtStartup() {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return ChexsonsaeutilsCompatibilityConfig.BUILDING_GADGETS2_INTEGRATION_ENABLED.get();
        }
        return StartupConfigBooleanReader.read(
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.BUILDING_GADGETS2_INTEGRATION_ENABLED.getDefault()
        );
    }

    public static boolean isEnabledAtStartup(Path configFile) {
        return StartupConfigBooleanReader.read(
                configFile,
                CONFIG_KEY,
                ChexsonsaeutilsCompatibilityConfig.BUILDING_GADGETS2_INTEGRATION_ENABLED.getDefault()
        );
    }
}
