package git.chexson.chexsonsaeutils.client.integration.jei;

import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JeiRecipeVisibilityBridge {

    public List<JeiMachineRecipeTypeHint> collectVisibleHintsForMachine(
            @Nullable IJeiRuntime runtime,
            @Nullable ResourceLocation machineItemId,
            @Nullable ResourceLocation machineBlockId,
            JeiMachineRecipeTypeHintBridge hintBridge
    ) {
        if (runtime == null || hintBridge == null) {
            return List.of();
        }
        IRecipeManager recipeManager = runtime.getRecipeManager();
        if (recipeManager == null) {
            return List.of();
        }
        List<JeiMachineRecipeTypeHint> machineHints =
                hintBridge.collectHintsForMachine(runtime, machineItemId, machineBlockId);
        if (machineHints.isEmpty()) {
            return List.of();
        }
        List<JeiMachineRecipeTypeHint> visibleHints = new ArrayList<>();
        for (JeiMachineRecipeTypeHint hint : machineHints) {
            if (hint != null && hasVisibleRecipes(recipeManager, hint.recipeTypeId())) {
                visibleHints.add(hint);
            }
        }
        return visibleHints.isEmpty() ? List.of() : List.copyOf(visibleHints);
    }

    private boolean hasVisibleRecipes(IRecipeManager recipeManager, ResourceLocation recipeTypeId) {
        if (recipeManager == null || recipeTypeId == null) {
            return false;
        }
        Optional<RecipeType<?>> recipeType = recipeManager.getRecipeType(recipeTypeId);
        if (recipeType.isEmpty()) {
            return false;
        }
        return hasVisibleRecipes(recipeManager, recipeType.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean hasVisibleRecipes(IRecipeManager recipeManager, RecipeType recipeType) {
        IRecipeLookup lookup = recipeManager.createRecipeLookup(recipeType);
        return lookup != null && lookup.get().findAny().isPresent();
    }
}
