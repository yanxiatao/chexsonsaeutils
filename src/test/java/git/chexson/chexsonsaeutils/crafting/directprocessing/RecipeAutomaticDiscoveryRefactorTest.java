package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** 无 JEI 时直接执行已有注册类型的自动发现与显式映射合并。 */
class RecipeAutomaticDiscoveryRefactorTest {
    @Test
    void itemAndBlockNamesBothContributeWithoutJeiAndKeepConfiguredType() {
        var mappings = MachineRecipeConfigMappingRegistry.instance();
        var snapshot = mappings.snapshot();
        var item = ResourceLocation.parse("test:heater");
        var machine = new MachineIdentity(item, ResourceLocation.parse("minecraft:furnace"), null, "test", null, null);
        try {
            mappings.registerMapping(item, RecipeType.BLASTING, 20);
            var resolver = new RecipeTypeCandidateResolver(MachineAdapterRegistry.empty(), mappings, true);
            var result = resolver.resolve(machine);
            assertEquals(Set.of(RecipeType.SMELTING, RecipeType.BLASTING),
                    result.candidates().stream().map(RecipeTypeCandidate::recipeType).collect(Collectors.toSet()));
            assertEquals(MachineRecipeCandidateSource.CONFIG_MAPPING, result.source());
            assertFalse(new RecipeTypeCandidateResolver(MachineAdapterRegistry.empty(), mappings, false)
                    .resolve(machine).candidates().stream().anyMatch(candidate -> candidate.recipeType() == RecipeType.SMELTING));
        } finally { mappings.restore(snapshot); }
    }

    @Test
    void explicitAdaptersKeepPriorityWhileIndependentConfiguredTypesRemainAvailable() {
        var mappings = MachineRecipeConfigMappingRegistry.instance();
        var snapshot = mappings.snapshot();
        var item = ResourceLocation.parse("test:machine");
        var machine = new MachineIdentity(item, null, null, "test", null, null);
        var explicit = new RecipeTypeCandidate(null, RecipeType.SMELTING, MachineRecipeCandidateSource.EXPLICIT_ADAPTER, 10);
        try {
            mappings.registerMapping(item, RecipeType.BLASTING, 20);
            mappings.registerMapping(item, RecipeType.SMELTING, 30);
            var adapters = new MachineAdapterRegistry(List.of(identity -> List.of(explicit)));
            var result = new RecipeTypeCandidateResolver(adapters, mappings, true).resolve(machine);
            assertEquals(2, result.candidates().size());
            assertSame(explicit, result.candidates().getFirst());
            assertEquals(MachineRecipeCandidateSource.EXPLICIT_ADAPTER, result.source());
        } finally { mappings.restore(snapshot); }
    }
}
