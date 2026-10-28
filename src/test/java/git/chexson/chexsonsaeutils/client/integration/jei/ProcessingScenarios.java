package git.chexson.chexsonsaeutils.client.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedSignature;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedStack;
import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 直连加工的行为场景；由类别测试统一调用。 */
public final class ProcessingScenarios {
    private ProcessingScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.client.integration.jei",
                () -> JeiRecipeSignatureHintBridgeCases.verify(temporaryRoot)
        );
    }

    /** JeiRecipeSignatureHintBridgeCases 的独立场景夹具，按类别统一执行。 */
    public static final class JeiRecipeSignatureHintBridgeCases {
        void defaultConstructorUsesProductionAssemblyForRealRecipeCapture() {
            var item = new ItemStack(Items.IRON_INGOT, 4);
            var fluid = new FluidStack(Fluids.WATER, 250);
            var bridge = new JeiRecipeSignatureHintBridge();

            var hints = bridge.collectRecipeSignatures(new FixtureCategory(), new Fixture(item, fluid),
                    FixtureCategory.TYPE.getUid());

            assertEquals(List.of(new MachineRecipeImportedSignature(FixtureCategory.TYPE.getUid(),
                    List.of(new MachineRecipeImportedStack(AEItemKey.of(item), 4)),
                    List.of(new MachineRecipeImportedStack(AEFluidKey.of(fluid), 250)))), hints);
        }

        void injectedExtensionChannelContributesBothInputsAndOutputs() {
            Object input = new Object();
            Object output = new Object();
            var inputKey = AEItemKey.of(Items.IRON_INGOT);
            var outputKey = AEFluidKey.of(Fluids.WATER);
            var converters = new DirectProcessingStackConverterRegistry(List.of(value -> {
                if (value == input) {
                    return new GenericStack(inputKey, 6);
                }
                if (value == output) {
                    return new GenericStack(outputKey, 750);
                }
                return null;
            }));
            var bridge = new JeiRecipeSignatureHintBridge(converters);
            var category = new FixtureCategory();

            var hints = bridge.collectRecipeSignatures(category, new Fixture(input, output),
                    FixtureCategory.TYPE.getUid());

            assertEquals(List.of(new MachineRecipeImportedSignature(FixtureCategory.TYPE.getUid(),
                    List.of(new MachineRecipeImportedStack(inputKey, 6)),
                    List.of(new MachineRecipeImportedStack(outputKey, 750)))), hints);
        }

        void builtinItemAndFluidIngredientsUseTheSameCapturePath() {
            var item = new ItemStack(Items.IRON_INGOT, 4);
            var fluid = new FluidStack(Fluids.WATER, 250);
            var bridge = new JeiRecipeSignatureHintBridge(DirectProcessingStackConverterRegistry.builtins());

            var hints = bridge.collectRecipeSignatures(new FixtureCategory(), new Fixture(item, fluid),
                    FixtureCategory.TYPE.getUid());

            assertEquals(List.of(new MachineRecipeImportedSignature(FixtureCategory.TYPE.getUid(),
                    List.of(new MachineRecipeImportedStack(AEItemKey.of(item), 4)),
                    List.of(new MachineRecipeImportedStack(AEFluidKey.of(fluid), 250)))), hints);
        }

        void explicitConstructorRejectsMissingConverterInsteadOfChoosingDefaults() {
            assertThrows(NullPointerException.class, () -> new JeiRecipeSignatureHintBridge(null));
        }

        private record Fixture(Object input, Object output) {
        }

        /** 只提供采集所需的配方布局，其余 UI 查询属于测试的意外调用。 */
        private static final class FixtureCategory implements IRecipeCategory<Fixture> {
            private static final RecipeType<Fixture> TYPE = RecipeType.create("test", "external_channel", Fixture.class);

            @Override
            public RecipeType<Fixture> getRecipeType() {
                return TYPE;
            }

            @Override
            public Component getTitle() {
                throw new AssertionError("采集配方不应读取界面标题");
            }

            @Override
            public IDrawable getIcon() {
                throw new AssertionError("采集配方不应读取界面图标");
            }

            @Override
            public int getWidth() {
                throw new AssertionError("采集配方不应读取界面宽度");
            }

            @Override
            public int getHeight() {
                throw new AssertionError("采集配方不应读取界面高度");
            }

            @Override
            public void setRecipe(IRecipeLayoutBuilder builder, Fixture recipe, IFocusGroup focuses) {
                builder.addSlot(RecipeIngredientRole.INPUT).addIngredientsUnsafe(List.of(recipe.input()));
                builder.addSlot(RecipeIngredientRole.OUTPUT).addIngredientsUnsafe(List.of(recipe.output()));
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("JeiRecipeSignatureHintBridgeCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("JeiRecipeSignatureHintBridgeCases.defaultConstructorUsesProductionAssemblyForRealRecipeCapture", () -> new JeiRecipeSignatureHintBridgeCases().defaultConstructorUsesProductionAssemblyForRealRecipeCapture()),
                () -> org.junit.jupiter.api.Assertions.assertAll("JeiRecipeSignatureHintBridgeCases.injectedExtensionChannelContributesBothInputsAndOutputs", () -> new JeiRecipeSignatureHintBridgeCases().injectedExtensionChannelContributesBothInputsAndOutputs()),
                () -> org.junit.jupiter.api.Assertions.assertAll("JeiRecipeSignatureHintBridgeCases.builtinItemAndFluidIngredientsUseTheSameCapturePath", () -> new JeiRecipeSignatureHintBridgeCases().builtinItemAndFluidIngredientsUseTheSameCapturePath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("JeiRecipeSignatureHintBridgeCases.explicitConstructorRejectsMissingConverterInsteadOfChoosingDefaults", () -> new JeiRecipeSignatureHintBridgeCases().explicitConstructorRejectsMissingConverterInsteadOfChoosingDefaults())
            );
        }
    }
}
