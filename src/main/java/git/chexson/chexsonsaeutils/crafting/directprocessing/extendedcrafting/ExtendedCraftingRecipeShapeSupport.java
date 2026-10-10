package git.chexson.chexsonsaeutils.crafting.directprocessing.extendedcrafting;

import appeng.api.stacks.GenericStack;
import com.blakebr0.extendedcrafting.api.crafting.ICompressorRecipe;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingExternalRecipeShapeRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;

public final class ExtendedCraftingRecipeShapeSupport
        implements DirectProcessingExternalRecipeShapeRegistry.ExternalRecipeShapeSupport {

    private final DirectProcessingStackConverterRegistry stackConverters;

    public ExtendedCraftingRecipeShapeSupport(DirectProcessingStackConverterRegistry stackConverters) {
        this.stackConverters = stackConverters == null
                ? DirectProcessingStackConverterRegistry.directProcessingDefaults()
                : stackConverters;
    }

    @Override
    public DirectProcessingExternalRecipeShapeRegistry.ShapeResult readShape(
            RecipeTypeCandidate candidate,
            Recipe<?> recipe
    ) {
        if (candidate == null || recipe == null) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unhandled();
        }
        RecipeType<?> recipeType = candidate.recipeType();
        ResourceLocation typeId = recipeType == null
                ? null
                : BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
        if (typeId == null
                || !typeId.equals(ExtendedCraftingDirectProcessingSupport.COMPRESSOR_RECIPE_TYPE_ID)) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unhandled();
        }
        if (!(recipe instanceof ICompressorRecipe compressor)) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        }
        int count = compressor.getCount(0);
        if (count <= 0) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        }
        var ingredients = compressor.getIngredients();
        if (ingredients.isEmpty()) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        }
        Ingredient materialIngredient = ingredients.getFirst();
        List<GenericStack> inputs = new ArrayList<>();
        for (ItemStack item : materialIngredient.getItems()) {
            if (item.isEmpty()) {
                continue;
            }
            ItemStack copy = item.copy();
            copy.setCount(count);
            GenericStack stack = this.stackConverters.convert(copy);
            if (stack != null) {
                inputs.add(stack);
            }
        }
        if (inputs.isEmpty()) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        }
        ItemStack result = compressor.getResultItem(null);
        if (result.isEmpty()) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        }
        GenericStack output = this.stackConverters.convert(result);
        if (output == null) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        }
        return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.supported(
                List.of(inputs),
                List.of(output)
        );
    }
}
