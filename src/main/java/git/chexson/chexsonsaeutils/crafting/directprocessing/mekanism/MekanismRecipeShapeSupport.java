package git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism;

import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;

public final class MekanismRecipeShapeSupport {

    private MekanismRecipeShapeSupport() {
    }

    public static ShapeResult readStaticItemRecipeShape(RecipeTypeCandidate candidate, Recipe<?> recipe) {
        if (candidate == null || recipe == null) {
            return ShapeResult.unhandled();
        }
        RecipeType<?> recipeType = candidate.recipeType();
        ResourceLocation recipeTypeId = recipeType == null ? null : BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
        if (recipeTypeId == null || !MekanismDirectProcessingSupport.isSupportedRecipeType(recipeTypeId)) {
            return ShapeResult.unhandled();
        }
        if (!(recipe instanceof ItemStackToItemStackRecipe mekanismRecipe)) {
            return ShapeResult.unreadable();
        }
        ItemStackIngredient input = mekanismRecipe.getInput();
        if (input == null || input.hasNoMatchingInstances()) {
            return ShapeResult.unreadable();
        }
        List<ItemStack> representations = input.getRepresentations();
        if (representations.isEmpty()) {
            return ShapeResult.unreadable();
        }
        List<ItemStack> normalizedInputs = new ArrayList<>();
        for (ItemStack representation : representations) {
            if (representation == null || representation.isEmpty()) {
                continue;
            }
            long neededAmount = input.getNeededAmount(representation);
            if (neededAmount <= 0L || neededAmount > Integer.MAX_VALUE) {
                continue;
            }
            ItemStack normalized = representation.copy();
            normalized.setCount((int) neededAmount);
            normalizedInputs.add(normalized);
        }
        if (normalizedInputs.isEmpty()) {
            return ShapeResult.unreadable();
        }
        List<ItemStack> outputDefinition = mekanismRecipe.getOutputDefinition();
        if (outputDefinition.size() != 1) {
            return ShapeResult.unreadable();
        }
        ItemStack output = outputDefinition.getFirst();
        if (output == null || output.isEmpty()) {
            return ShapeResult.unreadable();
        }
        return ShapeResult.supported(List.of(List.copyOf(normalizedInputs)), output.copy());
    }

    public record ShapeResult(
            boolean handled,
            boolean supported,
            List<List<ItemStack>> inputChoices,
            ItemStack output
    ) {
        public ShapeResult {
            inputChoices = inputChoices == null ? List.of() : List.copyOf(inputChoices);
            output = output == null ? ItemStack.EMPTY : output.copy();
        }

        public static ShapeResult unhandled() {
            return new ShapeResult(false, false, List.of(), ItemStack.EMPTY);
        }

        public static ShapeResult unreadable() {
            return new ShapeResult(true, false, List.of(), ItemStack.EMPTY);
        }

        public static ShapeResult supported(List<List<ItemStack>> inputChoices, ItemStack output) {
            return new ShapeResult(true, true, inputChoices, output);
        }
    }
}
