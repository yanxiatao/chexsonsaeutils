package git.chexson.chexsonsaeutils.crafting.directprocessing.extendedcrafting;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineAdapterRegistry.MachineRecipeAdapter;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;

import java.util.List;

// ponytail: stub - ExtendedCrafting not available on 1.20.1. Add when ported.
public final class ExtendedCraftingMachineRecipeTypeAdapter implements MachineRecipeAdapter {

    @Override
    public List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity) {
        return List.of();
    }
}