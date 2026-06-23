package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record MachineRecipeConfigImportRequest(
        ResourceLocation machineItemId,
        ResourceLocation machineBlockId,
        List<ResourceLocation> recipeTypeIds,
        int defaultTicks,
        String ioMode,
        String keyTypes,
        boolean enabled,
        String signatureHintsJson
) {
    public static final int MAX_NETWORK_RECIPE_TYPE_IDS = 256;
    public static final int MAX_NETWORK_SIGNATURES = 8_192;

    public MachineRecipeConfigImportRequest {
        recipeTypeIds = recipeTypeIds == null ? List.of() : List.copyOf(recipeTypeIds);
        defaultTicks = Math.max(1, defaultTicks);
        ioMode = ioMode == null || ioMode.isBlank() ? "generic" : ioMode;
        keyTypes = keyTypes == null || keyTypes.isBlank() ? "any" : keyTypes;
        signatureHintsJson = signatureHintsJson == null ? "" : signatureHintsJson;
    }
}
