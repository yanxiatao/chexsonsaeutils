package git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism;

import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingExternalRecipeShapeRegistry.ExternalRecipeShapeSupport;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingExternalRecipeShapeRegistry.ShapeResult;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

// ponytail: stub - Mekanism 1.20.1 API differs. Add when ported.
public final class MekanismRecipeShapeSupport implements ExternalRecipeShapeSupport {

    private final DirectProcessingStackConverterRegistry stackConverterRegistry;

    public MekanismRecipeShapeSupport(DirectProcessingStackConverterRegistry stackConverterRegistry) {
        this.stackConverterRegistry = stackConverterRegistry;
    }

    @Override
    public @Nullable ShapeResult readShape(RecipeTypeCandidate candidate, Recipe<?> recipe) {
        return ShapeResult.unhandled();
    }
}