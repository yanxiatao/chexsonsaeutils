package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * 鎷掔粷鎶?AE 鍘熺敓鍚堟垚鏍锋澘宸茬粡鏀寔鐨勯厤鏂圭被鍨嬪鍏ョ洿杩炴満銆? */
public final class DirectProcessingJeiImportRecipeTypeGuard {

    private static final ResourceLocation CRAFTING = ResourceLocation.withDefaultNamespace("crafting");
    private static final ResourceLocation SMITHING = ResourceLocation.withDefaultNamespace("smithing");
    private static final ResourceLocation STONECUTTING = ResourceLocation.withDefaultNamespace("stonecutting");
    private static final Set<ResourceLocation> AE_NATIVE_CRAFTING_RECIPE_TYPES = Set.of(
            CRAFTING,
            SMITHING,
            STONECUTTING
    );

    private DirectProcessingJeiImportRecipeTypeGuard() {
    }

    public static boolean isRejectedRecipeType(ResourceLocation recipeTypeId) {
        return recipeTypeId != null && AE_NATIVE_CRAFTING_RECIPE_TYPES.contains(recipeTypeId);
    }

    public static boolean isSupportedRecipeType(ResourceLocation recipeTypeId) {
        return recipeTypeId != null && !isRejectedRecipeType(recipeTypeId);
    }
}
