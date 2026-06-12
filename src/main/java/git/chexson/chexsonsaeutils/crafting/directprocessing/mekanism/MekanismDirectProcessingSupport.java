package git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism;

import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.MekanismRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

public final class MekanismDirectProcessingSupport {

    public static final String MOD_ID = "mekanism";
    public static final ResourceLocation CRUSHER_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "crusher");
    public static final ResourceLocation ENRICHMENT_CHAMBER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "enrichment_chamber");
    public static final ResourceLocation ENERGIZED_SMELTER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "energized_smelter");
    public static final ResourceLocation CRUSHING_RECIPE_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "crushing");
    public static final ResourceLocation ENRICHING_RECIPE_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "enriching");
    public static final ResourceLocation SMELTING_RECIPE_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "smelting");
    public static final int DEFAULT_TICKS = 20;

    private MekanismDirectProcessingSupport() {
    }

    public static boolean isSupportedMachine(ResourceLocation machineId) {
        return CRUSHER_ID.equals(machineId)
                || ENRICHMENT_CHAMBER_ID.equals(machineId)
                || ENERGIZED_SMELTER_ID.equals(machineId);
    }

    public static boolean isSupportedRecipeType(ResourceLocation recipeTypeId) {
        return CRUSHING_RECIPE_TYPE_ID.equals(recipeTypeId)
                || ENRICHING_RECIPE_TYPE_ID.equals(recipeTypeId)
                || SMELTING_RECIPE_TYPE_ID.equals(recipeTypeId);
    }

    public static boolean isSupportedRecipeType(RecipeType<?> recipeType) {
        return recipeType == MekanismRecipeTypes.TYPE_CRUSHING.value()
                || recipeType == MekanismRecipeTypes.TYPE_ENRICHING.value()
                || recipeType == MekanismRecipeTypes.TYPE_SMELTING.value();
    }

    public static RecipeType<ItemStackToItemStackRecipe> resolveRecipeType(ResourceLocation machineId) {
        if (CRUSHER_ID.equals(machineId)) {
            return MekanismRecipeTypes.TYPE_CRUSHING.value();
        }
        if (ENRICHMENT_CHAMBER_ID.equals(machineId)) {
            return MekanismRecipeTypes.TYPE_ENRICHING.value();
        }
        if (ENERGIZED_SMELTER_ID.equals(machineId)) {
            return MekanismRecipeTypes.TYPE_SMELTING.value();
        }
        return null;
    }
}
