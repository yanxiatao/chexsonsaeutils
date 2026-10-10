package git.chexson.chexsonsaeutils.client.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedSignature;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedStack;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 调用真实 JEI 配方捕获路径，验证显式通道注入不会丢失扩展堆栈。 */
class JeiRecipeSignatureHintBridgeTest {

    @Test
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

    @Test
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

    @Test
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

    @Test
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
}
