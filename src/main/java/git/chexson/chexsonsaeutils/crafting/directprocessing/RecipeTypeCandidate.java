package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.world.item.crafting.RecipeType;

public record RecipeTypeCandidate(
        MachineRecipeKind kind,
        RecipeType<?> recipeType,
        MachineRecipeCandidateSource source,
        int defaultTicks
) {
    public RecipeTypeCandidate {
        if (source == null) {
            source = MachineRecipeCandidateSource.UNSUPPORTED;
        }
        defaultTicks = Math.max(1, defaultTicks);
    }
}
