package git.chexson.chexsonsaeutils.client.integration.jei;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedSignature;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedStack;
import git.chexson.chexsonsaeutils.modid.ModIds;
import java.util.List;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SDK 的同类行为统一验证，按原有集成环境显式运行。 */
final class JeiChemicalCategoryTest {
    @Test
    void verifyLoadedIntegration() {
        org.junit.jupiter.api.Assertions.assertAll("JeiChemicalCategoryTest",
                JeiOptionalChemicalRuntimeCases::verify
        );
    }

    private static final class JeiOptionalChemicalRuntimeCases {
        void defaultJeiCapturePreservesRealChemicalInputAndOutputAmounts() {
            ModList mods = ModList.get();
            assertNotNull(mods, "必须由现有 NeoForge testedMod 测试入口启动");
            assertTrue(mods.isLoaded(ModIds.MODID), "本模组未加载");
            assertTrue(mods.isLoaded("mekanism"), "Mekanism 必须真实加载");
            assertTrue(mods.isLoaded("appmek"), "Applied Mekanistics 必须真实加载");
            var hydrogenId = ResourceLocation.fromNamespaceAndPath("mekanism", "hydrogen");
            assertTrue(MekanismAPI.CHEMICAL_REGISTRY.containsKey(hydrogenId), "氢必须完成真实注册");
            var hydrogen = MekanismAPI.CHEMICAL_REGISTRY.get(hydrogenId);
            assertNotNull(hydrogen);
            long inputAmount = 4_294_967_311L;
            long outputAmount = 8_589_934_623L;
            var input = new ChemicalStack(hydrogen, inputAmount);
            var output = new ChemicalStack(hydrogen, outputAmount);
            var category = new ChemicalCategory();

            var hints = new JeiRecipeSignatureHintBridge().collectRecipeSignatures(
                    category, new ChemicalRecipe(input, output), ChemicalCategory.TYPE.getUid());

            assertEquals(List.of(new MachineRecipeImportedSignature(ChemicalCategory.TYPE.getUid(),
                    List.of(new MachineRecipeImportedStack(MekanismKey.of(input), inputAmount)),
                    List.of(new MachineRecipeImportedStack(MekanismKey.of(output), outputAmount)))), hints);
            assertEquals(inputAmount, input.getAmount());
            assertEquals(outputAmount, output.getAmount());
        }

        private record ChemicalRecipe(ChemicalStack input, ChemicalStack output) {
        }

        /** 只公开采集必需的配方布局；意外 UI 调用立即失败。 */
        private static final class ChemicalCategory implements IRecipeCategory<ChemicalRecipe> {
            private static final RecipeType<ChemicalRecipe> TYPE =
                    RecipeType.create("test", "real_chemical_channel", ChemicalRecipe.class);

            @Override
            public RecipeType<ChemicalRecipe> getRecipeType() {
                return TYPE;
            }

            @Override
            public Component getTitle() {
                throw new AssertionError("配方采集不应读取界面标题");
            }

            @Override
            public IDrawable getIcon() {
                throw new AssertionError("配方采集不应读取界面图标");
            }

            @Override
            public int getWidth() {
                throw new AssertionError("配方采集不应读取界面宽度");
            }

            @Override
            public int getHeight() {
                throw new AssertionError("配方采集不应读取界面高度");
            }

            @Override
            public void setRecipe(IRecipeLayoutBuilder builder, ChemicalRecipe recipe, IFocusGroup focuses) {
                builder.addSlot(RecipeIngredientRole.INPUT).addIngredientsUnsafe(List.of(recipe.input()));
                builder.addSlot(RecipeIngredientRole.OUTPUT).addIngredientsUnsafe(List.of(recipe.output()));
            }
        }

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("JeiOptionalChemicalRuntimeCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("defaultJeiCapturePreservesRealChemicalInputAndOutputAmounts", () -> new JeiOptionalChemicalRuntimeCases().defaultJeiCapturePreservesRealChemicalInputAndOutputAmounts())
            );
        }
    }
}
