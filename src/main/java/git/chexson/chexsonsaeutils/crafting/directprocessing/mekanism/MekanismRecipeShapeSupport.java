package git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism;

import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingExternalRecipeShapeRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.recipes.ChemicalChemicalToChemicalRecipe;
import mekanism.api.recipes.ChemicalToChemicalRecipe;
import mekanism.api.recipes.FluidChemicalToChemicalRecipe;
import mekanism.api.recipes.ItemStackChemicalToItemStackRecipe;
import mekanism.api.recipes.ItemStackToChemicalRecipe;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.PressurizedReactionRecipe;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient;
import mekanism.api.recipes.ingredients.FluidStackIngredient;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

public final class MekanismRecipeShapeSupport
        implements DirectProcessingExternalRecipeShapeRegistry.ExternalRecipeShapeSupport {

    private static final long ITEM_CHEMICAL_PER_OPERATION_USAGE = 200L;
    private final DirectProcessingStackConverterRegistry stackConverters;

    public MekanismRecipeShapeSupport(DirectProcessingStackConverterRegistry stackConverters) {
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
        ResourceLocation recipeTypeId = recipeType == null ? null : BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
        if (recipeTypeId == null || !MekanismDirectProcessingSupport.isSupportedRecipeType(recipeTypeId)) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unhandled();
        }
        if (recipe instanceof ItemStackToItemStackRecipe mekanismRecipe) {
            return readItemToItemRecipe(mekanismRecipe);
        }
        if (recipe instanceof ItemStackToChemicalRecipe mekanismRecipe) {
            return readItemToChemicalRecipe(mekanismRecipe);
        }
        if (recipe instanceof ChemicalToChemicalRecipe mekanismRecipe) {
            return readChemicalToChemicalRecipe(mekanismRecipe);
        }
        if (recipe instanceof ItemStackChemicalToItemStackRecipe mekanismRecipe) {
            return readItemChemicalToItemRecipe(mekanismRecipe);
        }
        if (recipe instanceof FluidChemicalToChemicalRecipe mekanismRecipe) {
            return readFluidChemicalToChemicalRecipe(mekanismRecipe);
        }
        if (recipe instanceof ChemicalChemicalToChemicalRecipe mekanismRecipe) {
            return readChemicalChemicalToChemicalRecipe(mekanismRecipe);
        }
        if (recipe instanceof PressurizedReactionRecipe mekanismRecipe) {
            return readPressurizedReactionRecipe(mekanismRecipe);
        }
        return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult readItemToItemRecipe(
            ItemStackToItemStackRecipe recipe
    ) {
        List<GenericStack> inputs = readItemIngredient(recipe.getInput());
        List<GenericStack> outputs = readSingleItemOutput(recipe.getOutputDefinition());
        return finalizeSingleInputShape(inputs, outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult readItemToChemicalRecipe(
            ItemStackToChemicalRecipe recipe
    ) {
        List<GenericStack> inputs = readItemIngredient(recipe.getInput());
        List<GenericStack> outputs = readSingleChemicalOutput(recipe.getOutputDefinition());
        return finalizeSingleInputShape(inputs, outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult readChemicalToChemicalRecipe(
            ChemicalToChemicalRecipe recipe
    ) {
        List<GenericStack> inputs = readChemicalIngredient(recipe.getInput());
        List<GenericStack> outputs = readSingleChemicalOutput(recipe.getOutputDefinition());
        return finalizeSingleInputShape(inputs, outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult readItemChemicalToItemRecipe(
            ItemStackChemicalToItemStackRecipe recipe
    ) {
        List<List<GenericStack>> inputChoices = List.of(
                readItemIngredient(recipe.getItemInput()),
                readChemicalIngredient(recipe.getChemicalInput(), recipe.perTickUsage())
        );
        List<GenericStack> outputs = readSingleItemOutput(recipe.getOutputDefinition());
        return finalizeMultiInputShape(inputChoices, outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult readFluidChemicalToChemicalRecipe(
            FluidChemicalToChemicalRecipe recipe
    ) {
        List<List<GenericStack>> inputChoices = List.of(
                readFluidIngredient(recipe.getFluidInput()),
                readChemicalIngredient(recipe.getChemicalInput())
        );
        List<GenericStack> outputs = readSingleChemicalOutput(recipe.getOutputDefinition());
        return finalizeMultiInputShape(inputChoices, outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult readChemicalChemicalToChemicalRecipe(
            ChemicalChemicalToChemicalRecipe recipe
    ) {
        List<List<GenericStack>> inputChoices = List.of(
                readChemicalIngredient(recipe.getLeftInput()),
                readChemicalIngredient(recipe.getRightInput())
        );
        List<GenericStack> outputs = readSingleChemicalOutput(recipe.getOutputDefinition());
        return finalizeMultiInputShape(inputChoices, outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult readPressurizedReactionRecipe(
            PressurizedReactionRecipe recipe
    ) {
        List<List<GenericStack>> inputChoices = List.of(
                readItemIngredient(recipe.getInputSolid()),
                readFluidIngredient(recipe.getInputFluid()),
                readChemicalIngredient(recipe.getInputChemical())
        );
        List<GenericStack> outputs = readReactionOutput(recipe.getOutputDefinition());
        return finalizeMultiInputShape(inputChoices, outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult finalizeSingleInputShape(
            List<GenericStack> singleInputChoice,
            List<GenericStack> outputs
    ) {
        return finalizeMultiInputShape(List.of(singleInputChoice), outputs);
    }

    private DirectProcessingExternalRecipeShapeRegistry.ShapeResult finalizeMultiInputShape(
            List<List<GenericStack>> inputChoices,
            List<GenericStack> outputs
    ) {
        if (inputChoices == null || inputChoices.isEmpty() || outputs == null || outputs.isEmpty()) {
            return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        }
        for (List<GenericStack> choice : inputChoices) {
            if (choice == null || choice.isEmpty()) {
                return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
            }
        }
        return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.supported(inputChoices, outputs);
    }

    private List<GenericStack> readItemIngredient(ItemStackIngredient ingredient) {
        if (ingredient == null || ingredient.hasNoMatchingInstances()) {
            return List.of();
        }
        List<ItemStack> representations = ingredient.getRepresentations();
        if (representations == null || representations.isEmpty()) {
            return List.of();
        }
        List<GenericStack> normalized = new ArrayList<>();
        for (ItemStack representation : representations) {
            if (representation == null || representation.isEmpty()) {
                continue;
            }
            long neededAmount = ingredient.getNeededAmount(representation);
            if (neededAmount <= 0L || neededAmount > Integer.MAX_VALUE) {
                continue;
            }
            ItemStack copy = representation.copy();
            copy.setCount((int) neededAmount);
            GenericStack stack = stackConverters.convert(copy);
            if (stack != null) {
                normalized.add(stack);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private List<GenericStack> readFluidIngredient(FluidStackIngredient ingredient) {
        if (ingredient == null || ingredient.hasNoMatchingInstances()) {
            return List.of();
        }
        List<FluidStack> representations = ingredient.getRepresentations();
        if (representations == null || representations.isEmpty()) {
            return List.of();
        }
        List<GenericStack> normalized = new ArrayList<>();
        for (FluidStack representation : representations) {
            if (representation == null || representation.isEmpty()) {
                continue;
            }
            long neededAmount = ingredient.getNeededAmount(representation);
            if (neededAmount <= 0L || neededAmount > Integer.MAX_VALUE) {
                continue;
            }
            FluidStack copy = representation.copy();
            copy.setAmount((int) neededAmount);
            GenericStack stack = stackConverters.convert(copy);
            if (stack != null) {
                normalized.add(stack);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private List<GenericStack> readChemicalIngredient(ChemicalStackIngredient ingredient) {
        return readChemicalIngredient(ingredient, false);
    }

    private List<GenericStack> readChemicalIngredient(ChemicalStackIngredient ingredient, boolean perTickUsage) {
        if (ingredient == null || ingredient.hasNoMatchingInstances()) {
            return List.of();
        }
        List<ChemicalStack> representations = ingredient.getRepresentations();
        if (representations == null || representations.isEmpty()) {
            return List.of();
        }
        List<GenericStack> normalized = new ArrayList<>();
        for (ChemicalStack representation : representations) {
            if (representation == null || representation.isEmpty()) {
                continue;
            }
            long neededAmount = scaleItemChemicalInputAmount(
                    ingredient.getNeededAmount(representation),
                    perTickUsage
            );
            if (neededAmount <= 0L) {
                continue;
            }
            ChemicalStack copy = representation.copy();
            copy.setAmount(neededAmount);
            GenericStack stack = stackConverters.convert(copy);
            if (stack != null) {
                normalized.add(stack);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    static long scaleItemChemicalInputAmount(long amount, boolean perTickUsage) {
        if (amount <= 0L) {
            return 0L;
        }
        return perTickUsage ? Math.multiplyExact(amount, ITEM_CHEMICAL_PER_OPERATION_USAGE) : amount;
    }

    private List<GenericStack> readSingleItemOutput(List<ItemStack> outputDefinition) {
        if (outputDefinition == null || outputDefinition.size() != 1) {
            return List.of();
        }
        GenericStack output = stackConverters.convert(outputDefinition.getFirst());
        return output == null ? List.of() : List.of(output);
    }

    private List<GenericStack> readSingleChemicalOutput(List<ChemicalStack> outputDefinition) {
        if (outputDefinition == null || outputDefinition.size() != 1) {
            return List.of();
        }
        GenericStack output = stackConverters.convert(outputDefinition.getFirst());
        return output == null ? List.of() : List.of(output);
    }

    private List<GenericStack> readReactionOutput(List<PressurizedReactionRecipe.PressurizedReactionRecipeOutput> outputDefinition) {
        if (outputDefinition == null || outputDefinition.size() != 1) {
            return List.of();
        }
        PressurizedReactionRecipe.PressurizedReactionRecipeOutput output = outputDefinition.getFirst();
        if (output == null) {
            return List.of();
        }
        List<GenericStack> normalized = new ArrayList<>(2);
        GenericStack itemOutput = stackConverters.convert(output.item());
        if (itemOutput != null) {
            normalized.add(itemOutput);
        }
        GenericStack chemicalOutput = stackConverters.convert(output.chemical());
        if (chemicalOutput != null) {
            normalized.add(chemicalOutput);
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }
}
