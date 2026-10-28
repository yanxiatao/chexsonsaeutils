package git.chexson.chexsonsaeutils.integration.directprocessing;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.modid.ModIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直连加工的行为场景；由类别测试统一调用。 */
public final class ProcessingScenarios {
    private ProcessingScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.integration.directprocessing",
                () -> DirectProcessingAssemblyCases.verify(temporaryRoot)
        );
    }

    /** DirectProcessingAssemblyCases 的独立场景夹具，按类别统一执行。 */
    public static final class DirectProcessingAssemblyCases {
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

        void eitherMissingChemicalModKeepsOnlyBuiltinConverters() {
            for (Set<String> loaded : List.of(Set.of("mekanism"), Set.of("appmek"))) {
                var converters = DirectProcessingAssembly.createStackConverters(loaded::contains);

                assertSame(DirectProcessingStackConverterRegistry.builtins(), converters);
                assertNull(converters.convert(new Object()));
                assertEquals(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1),
                        converters.convert(Items.IRON_INGOT));
            }
        }

        void brokenLoadedModProbePropagatesWithoutSilentFallback() {
            IllegalStateException failure = new IllegalStateException("测试加载状态不可读取");

            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> DirectProcessingAssembly.createStackConverters(id -> {
                        throw failure;
                    })));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("DirectProcessingAssemblyCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingAssemblyCases.productionDefaultsAssembleAfterTestedModHasLoaded", () -> new DirectProcessingAssemblyCases().productionDefaultsAssembleAfterTestedModHasLoaded()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingAssemblyCases.absentModsLeaveAdaptersEmptyAndDoNotLinkOptionalSupports", () -> new DirectProcessingAssemblyCases().absentModsLeaveAdaptersEmptyAndDoNotLinkOptionalSupports()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingAssemblyCases.eitherMissingChemicalModKeepsOnlyBuiltinConverters", () -> new DirectProcessingAssemblyCases().eitherMissingChemicalModKeepsOnlyBuiltinConverters()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingAssemblyCases.brokenLoadedModProbePropagatesWithoutSilentFallback", () -> new DirectProcessingAssemblyCases().brokenLoadedModProbePropagatesWithoutSilentFallback())
            );
        }
    }
}
