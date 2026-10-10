package git.chexson.chexsonsaeutils.integration.directprocessing;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接执行装配门控，并在既有 NeoForge 测试启动中验证生产默认入口。 */
class DirectProcessingAssemblyTest {

    @Test
    void productionDefaultsAssembleAfterTestedModHasLoaded() {
        ModList mods = ModList.get();
        assertNotNull(mods, "测试必须由现有 NeoForge testedMod 入口启动");
        assertTrue(mods.isLoaded(ModIds.MODID), "当前测试未加载本模组");

        var converters = DirectProcessingAssembly.createStackConverters();

        assertEquals(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2),
                converters.convert(new ItemStack(Items.IRON_INGOT, 2)));
        assertSame(converters, DirectProcessingAssembly.createStackConverters());
        assertNotNull(DirectProcessingAssembly.createMachineRecipeIndexBuilder());
    }

    @Test
    void absentModsLeaveAdaptersEmptyAndDoNotLinkOptionalSupports() {
        List<String> probes = new ArrayList<>();
        Predicate<String> noMods = id -> {
            probes.add(id);
            return false;
        };
        var converters = DirectProcessingAssembly.createStackConverters(noMods);
        var adapters = DirectProcessingAssembly.createMachineAdapterRegistry(noMods);
        var shapes = DirectProcessingAssembly.createExternalRecipeShapeRegistry(noMods, converters);
        var identity = new MachineIdentity(
                ResourceLocation.fromNamespaceAndPath("test", "machine"), null, null, "test", null, null);

        assertSame(DirectProcessingStackConverterRegistry.builtins(), converters);
        assertEquals(List.of(), adapters.resolveCandidates(identity));
        assertNotNull(shapes);
        assertEquals(List.of("mekanism", "mekanism", "extendedcrafting", "mekanism", "extendedcrafting"), probes);
        assertEquals(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2),
                converters.convert(new ItemStack(Items.IRON_INGOT, 2)));
    }

    @Test
    void eitherMissingChemicalModKeepsOnlyBuiltinConverters() {
        for (Set<String> loaded : List.of(Set.of("mekanism"), Set.of("appmek"))) {
            var converters = DirectProcessingAssembly.createStackConverters(loaded::contains);

            assertSame(DirectProcessingStackConverterRegistry.builtins(), converters);
            assertNull(converters.convert(new Object()));
            assertEquals(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1),
                    converters.convert(Items.IRON_INGOT));
        }
    }

    @Test
    void brokenLoadedModProbePropagatesWithoutSilentFallback() {
        IllegalStateException failure = new IllegalStateException("测试加载状态不可读取");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> DirectProcessingAssembly.createStackConverters(id -> {
                    throw failure;
                })));
    }
}
