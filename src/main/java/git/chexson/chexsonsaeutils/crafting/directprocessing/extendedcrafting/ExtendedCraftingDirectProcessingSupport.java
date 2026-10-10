package git.chexson.chexsonsaeutils.crafting.directprocessing.extendedcrafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

public final class ExtendedCraftingDirectProcessingSupport {

    public static final String MOD_ID = "extendedcrafting";
    public static final ResourceLocation COMPRESSOR_BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "compressor");
    public static final ResourceLocation COMPRESSOR_RECIPE_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "compressor");
    public static final int DEFAULT_TICKS = 200;

    private ExtendedCraftingDirectProcessingSupport() {
    }

    public static boolean isSupportedMachine(ResourceLocation machineId) {
        return COMPRESSOR_BLOCK_ID.equals(machineId);
    }

    public static List<RecipeType<?>> resolveRecipeTypes(ResourceLocation machineId) {
        if (!isSupportedMachine(machineId)) {
            return List.of();
        }
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(COMPRESSOR_RECIPE_TYPE_ID);
        return type != null ? List.of(type) : List.of();
    }
}
