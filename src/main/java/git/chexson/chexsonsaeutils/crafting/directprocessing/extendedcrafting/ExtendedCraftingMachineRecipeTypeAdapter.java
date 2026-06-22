package git.chexson.chexsonsaeutils.crafting.directprocessing.extendedcrafting;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineAdapterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeCandidateSource;
import git.chexson.chexsonsaeutils.crafting.directprocessing.RecipeTypeCandidate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;

import java.util.List;

public final class ExtendedCraftingMachineRecipeTypeAdapter
        implements MachineAdapterRegistry.MachineRecipeAdapter {

    @Override
    public List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity) {
        if (ModList.get() == null
                || !ModList.get().isLoaded(ExtendedCraftingDirectProcessingSupport.MOD_ID)
                || identity == null) {
            return List.of();
        }
        ResourceLocation machineId = identity.machineItemId();
        if (machineId == null
                || !machineId.equals(ExtendedCraftingDirectProcessingSupport.COMPRESSOR_BLOCK_ID)) {
            return List.of();
        }
        List<RecipeType<?>> types = ExtendedCraftingDirectProcessingSupport.resolveRecipeTypes(machineId);
        if (types.isEmpty()) {
            return List.of();
        }
        return types.stream()
                .map(t -> new RecipeTypeCandidate(
                        null,
                        t,
                        MachineRecipeCandidateSource.EXPLICIT_ADAPTER,
                        ExtendedCraftingDirectProcessingSupport.DEFAULT_TICKS))
                .toList();
    }
}
