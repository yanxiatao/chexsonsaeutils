package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * 拒绝把 AE 原生合成样板已经支持的配方类型导入直连机。
 */
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
