package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class DirectProcessingExternalRecipeShapeRegistry {

    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final DirectProcessingExternalRecipeShapeRegistry DEFAULT = createDefaultRegistry();

    private final List<ExternalRecipeShapeSupport> shapeSupports;

    public DirectProcessingExternalRecipeShapeRegistry(List<ExternalRecipeShapeSupport> shapeSupports) {
        this.shapeSupports = List.copyOf(shapeSupports == null ? List.of() : shapeSupports);
    }

    public static DirectProcessingExternalRecipeShapeRegistry directProcessingDefaults() {
        return DEFAULT;
    }

    @Nullable
    public ShapeResult readShape(@Nullable RecipeTypeCandidate candidate, @Nullable Recipe<?> recipe) {
        if (candidate == null || recipe == null) {
            return null;
        }
        for (ExternalRecipeShapeSupport support : shapeSupports) {
            ShapeResult result = support == null ? null : support.readShape(candidate, recipe);
            if (result != null && result.handled()) {
                return result;
            }
        }
        return null;
    }

    private static DirectProcessingExternalRecipeShapeRegistry createDefaultRegistry() {
        List<ExternalRecipeShapeSupport> supports = new ArrayList<>();
        ModList modList = ModList.get();
        if (modList != null && modList.isLoaded(MEKANISM_MOD_ID)) {
            supports.add(new git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism
                    .MekanismRecipeShapeSupport(DirectProcessingStackConverterRegistry.directProcessingDefaults()));
        }
        return new DirectProcessingExternalRecipeShapeRegistry(supports);
    }

    public interface ExternalRecipeShapeSupport {
        @Nullable
        ShapeResult readShape(RecipeTypeCandidate candidate, Recipe<?> recipe);
    }

    public record ShapeResult(
            boolean handled,
            boolean supported,
            List<List<appeng.api.stacks.GenericStack>> inputChoices,
            List<appeng.api.stacks.GenericStack> outputs
    ) {
        public ShapeResult {
            inputChoices = inputChoices == null ? List.of() : List.copyOf(inputChoices);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        public static ShapeResult unhandled() {
            return new ShapeResult(false, false, List.of(), List.of());
        }

        public static ShapeResult unreadable() {
            return new ShapeResult(true, false, List.of(), List.of());
        }

        public static ShapeResult supported(
                List<List<appeng.api.stacks.GenericStack>> inputChoices,
                List<appeng.api.stacks.GenericStack> outputs
        ) {
            return new ShapeResult(true, true, inputChoices, outputs);
        }
    }
}
