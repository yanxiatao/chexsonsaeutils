package git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineAdapterRegistry.MachineRecipeAdapter;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;

import java.util.List;

// ponytail: stub - Mekanism 1.20.1 API differs. Add when ported.
public final class MekanismMachineRecipeTypeAdapter implements MachineRecipeAdapter {

    @Override
    public List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity) {
        return List.of();
    }
}