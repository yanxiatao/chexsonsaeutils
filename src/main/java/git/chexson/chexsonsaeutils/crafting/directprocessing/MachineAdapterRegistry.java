package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

public final class MachineAdapterRegistry {

    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final String EXTENDED_CRAFTING_MOD_ID = "extendedcrafting";
    private static final MachineAdapterRegistry EMPTY = new MachineAdapterRegistry(List.of());

    private final List<MachineRecipeAdapter> adapters;

    public MachineAdapterRegistry(List<MachineRecipeAdapter> adapters) {
        this.adapters = List.copyOf(adapters == null ? List.of() : adapters);
    }

    public static MachineAdapterRegistry empty() {
        return EMPTY;
    }

    public static MachineAdapterRegistry directProcessingDefaults() {
        List<MachineRecipeAdapter> adapters = new ArrayList<>();
        ModList modList = ModList.get();
        if (modList != null && modList.isLoaded(MEKANISM_MOD_ID)) {
            adapters.add(MekanismAdapterHolder.create());
        }
        if (modList != null && modList.isLoaded(EXTENDED_CRAFTING_MOD_ID)) {
            adapters.add(ExtendedCraftingAdapterHolder.create());
        }
        return adapters.isEmpty() ? EMPTY : new MachineAdapterRegistry(adapters);
    }

    public List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity) {
        if (identity == null || adapters.isEmpty()) {
            return List.of();
        }
        for (MachineRecipeAdapter adapter : adapters) {
            List<RecipeTypeCandidate> candidates = adapter.resolveCandidates(identity);
            if (candidates != null && !candidates.isEmpty()) {
                return List.copyOf(candidates);
            }
        }
        return List.of();
    }

    public interface MachineRecipeAdapter {
        List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity);
    }

    private static final class MekanismAdapterHolder {
        private MekanismAdapterHolder() {
        }

        private static MachineRecipeAdapter create() {
            return new git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism
                    .MekanismMachineRecipeTypeAdapter();
        }
    }

    private static final class ExtendedCraftingAdapterHolder {
        private ExtendedCraftingAdapterHolder() {
        }

        private static MachineRecipeAdapter create() {
            return new git.chexson.chexsonsaeutils.crafting.directprocessing.extendedcrafting
                    .ExtendedCraftingMachineRecipeTypeAdapter();
        }
    }
}
