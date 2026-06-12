package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;

public enum MachineRecipeKind {
    FURNACE(Items.FURNACE, RecipeType.SMELTING, 200),
    SMOKER(Items.SMOKER, RecipeType.SMOKING, 100),
    BLAST_FURNACE(Items.BLAST_FURNACE, RecipeType.BLASTING, 100),
    STONECUTTER(Items.STONECUTTER, RecipeType.STONECUTTING, 20);

    private final Item machineItem;
    private final RecipeType<?> recipeType;
    private final int defaultTicks;

    MachineRecipeKind(Item machineItem, RecipeType<?> recipeType, int defaultTicks) {
        this.machineItem = machineItem;
        this.recipeType = recipeType;
        this.defaultTicks = Math.max(1, defaultTicks);
    }

    public RecipeType<?> recipeType() {
        return recipeType;
    }

    public int defaultTicks() {
        return defaultTicks;
    }

    public boolean matches(MachineIdentity identity) {
        if (identity == null) {
            return false;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(machineItem);
        return itemId != null && itemId.equals(identity.machineItemId());
    }
}
