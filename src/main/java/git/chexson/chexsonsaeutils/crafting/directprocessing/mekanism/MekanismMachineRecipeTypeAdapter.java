package git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineAdapterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeCandidateSource;
import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

public final class MekanismMachineRecipeTypeAdapter implements MachineAdapterRegistry.MachineRecipeAdapter {

    @Override
    public List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity) {
        if (ModList.get() == null
                || !ModList.get().isLoaded(MekanismDirectProcessingSupport.MOD_ID)
                || identity == null) {
            return List.of();
        }
        ResourceLocation machineId = identity.machineItemId();
        if (machineId == null || !MekanismDirectProcessingSupport.MOD_ID.equals(machineId.getNamespace())) {
            return List.of();
        }
        List<RecipeType<?>> recipeTypes = MekanismDirectProcessingSupport.resolveRecipeTypes(machineId);
        if (recipeTypes.isEmpty()) {
            return List.of();
        }
        List<RecipeTypeCandidate> candidates = new ArrayList<>(recipeTypes.size());
        for (RecipeType<?> recipeType : recipeTypes) {
            if (recipeType != null) {
                candidates.add(new RecipeTypeCandidate(
                        null,
                        recipeType,
                        MachineRecipeCandidateSource.EXPLICIT_ADAPTER,
                        MekanismDirectProcessingSupport.DEFAULT_TICKS
                ));
            }
        }
        return candidates.isEmpty() ? List.of() : List.copyOf(candidates);
    }
}
