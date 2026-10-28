package git.chexson.chexsonsaeutils.integration.directprocessing;

import git.chexson.chexsonsaeutils.modid.ModIds;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SDK 的同类行为统一验证，按原有集成环境显式运行。 */
final class ProcessingChemicalCategoryTest {
    @Test
    void verifyLoadedIntegration() {
        org.junit.jupiter.api.Assertions.assertAll("ProcessingChemicalCategoryTest",
                DirectProcessingOptionalChemicalRuntimeCases::verify
        );
    }

    private static final class DirectProcessingOptionalChemicalRuntimeCases {
        void realLoadedChemicalModsEnableDefaultAssemblyConversion() {
            ModList mods = ModList.get();
            assertNotNull(mods, "必须由现有 NeoForge testedMod 测试入口启动");
            assertTrue(mods.isLoaded(ModIds.MODID), "本模组未加载");
            assertTrue(mods.isLoaded("mekanism"), "Mekanism 必须真实加载");
            assertTrue(mods.isLoaded("appmek"), "Applied Mekanistics 必须真实加载");
            var hydrogenId = ResourceLocation.fromNamespaceAndPath("mekanism", "hydrogen");
            assertTrue(MekanismAPI.CHEMICAL_REGISTRY.containsKey(hydrogenId), "氢必须完成真实注册");
            var hydrogen = MekanismAPI.CHEMICAL_REGISTRY.get(hydrogenId);
            assertNotNull(hydrogen);
            long amount = 4_294_967_311L;
            var input = new ChemicalStack(hydrogen, amount);

            var converters = DirectProcessingAssembly.createStackConverters();
            var converted = converters.convert(input);

            assertNotNull(converted, "生产默认装配不能降级为内建通道");
            var key = assertInstanceOf(MekanismKey.class, converted.what());
            assertEquals(MekanismKey.of(input), key);
            assertSame(hydrogen, key.getStack().getChemical());
            assertEquals(amount, converted.amount());
            assertEquals(amount, input.getAmount());
            assertSame(converters, DirectProcessingAssembly.createStackConverters());
            assertNotNull(DirectProcessingAssembly.createMachineRecipeIndexBuilder());
        }

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("DirectProcessingOptionalChemicalRuntimeCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("realLoadedChemicalModsEnableDefaultAssemblyConversion", () -> new DirectProcessingOptionalChemicalRuntimeCases().realLoadedChemicalModsEnableDefaultAssemblyConversion())
            );
        }
    }
}
