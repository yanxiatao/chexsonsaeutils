package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/** 直接执行注册器契约，保证外部装配后仍保留优先级、短路与内建通道。 */
class DirectProcessingRegistryContractTest {

    private static final RecipeTypeCandidate CANDIDATE =
            new RecipeTypeCandidate(null, null, MachineRecipeCandidateSource.EXPLICIT_ADAPTER, 20);
    private static final MachineIdentity MACHINE = new MachineIdentity(
            ResourceLocation.fromNamespaceAndPath("test", "machine"), null, null, "test", null, null);
    private static final Recipe<?> RECIPE = new UnreadRecipe();

    @Test
    void machineAdaptersKeepFirstNonemptyResultAndSnapshotTheirRoster() {
        List<String> calls = new ArrayList<>();
        List<MachineAdapterRegistry.MachineRecipeAdapter> roster = new ArrayList<>(List.of(
                machine -> {
                    assertSame(MACHINE, machine);
                    calls.add("empty");
                    return List.of();
                },
                machine -> {
                    calls.add("matched");
                    return List.of(CANDIDATE);
                },
                machine -> {
                    fail("首命中后不应再调用后续机器适配器");
                    return List.of();
                }
        ));
        MachineAdapterRegistry registry = new MachineAdapterRegistry(roster);
        roster.clear();

        assertEquals(List.of(CANDIDATE), registry.resolveCandidates(MACHINE));
        assertEquals(List.of("empty", "matched"), calls);
    }

    @Test
    void handledUnreadableShapeStopsAllLaterSupports() {
        List<String> calls = new ArrayList<>();
        var unreadable = DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
        var registry = new DirectProcessingExternalRecipeShapeRegistry(List.of(
                (candidate, recipe) -> {
                    assertSame(CANDIDATE, candidate);
                    assertSame(RECIPE, recipe);
                    calls.add("unhandled");
                    return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unhandled();
                },
                (candidate, recipe) -> {
                    calls.add("unreadable");
                    return unreadable;
                },
                (candidate, recipe) -> {
                    fail("已处理的不可读配方不能继续交给其他支持器");
                    return null;
                }
        ));

        assertSame(unreadable, registry.readShape(CANDIDATE, RECIPE));
        assertEquals(List.of("unhandled", "unreadable"), calls);
    }

    @Test
    void supportedShapeKeepsTheFirstResult() {
        var supported = DirectProcessingExternalRecipeShapeRegistry.ShapeResult.supported(List.of(), List.of());
        var registry = new DirectProcessingExternalRecipeShapeRegistry(List.of(
                (candidate, recipe) -> null,
                (candidate, recipe) -> supported,
                (candidate, recipe) -> {
                    fail("首个受支持结果必须终止后续形状读取");
                    return null;
                }
        ));

        assertSame(supported, registry.readShape(CANDIDATE, RECIPE));
    }

    @Test
    void extensionConvertersKeepFirstValidResultAndOptionalUnwrapping() {
        Object value = new Object();
        GenericStack expected = new GenericStack(AEItemKey.of(Items.IRON_INGOT), 7);
        List<String> calls = new ArrayList<>();
        var registry = new DirectProcessingStackConverterRegistry(List.of(
                input -> {
                    assertSame(value, input);
                    calls.add("unhandled");
                    return null;
                },
                input -> {
                    calls.add("matched");
                    return expected;
                },
                input -> {
                    fail("首个有效转换应终止后续通道");
                    return null;
                }
        ));

        assertEquals(expected, registry.convert(Optional.of(value)));
        assertEquals(List.of("unhandled", "matched"), calls);
    }

    @Test
    void builtinItemAndFluidChannelsDoNotVisitExtensions() {
        var registry = new DirectProcessingStackConverterRegistry(List.of(value -> {
            fail("内建物品和流体不能进入扩展转换器");
            return null;
        }));
        ItemStack item = new ItemStack(Items.IRON_INGOT, 3);
        FluidStack fluid = new FluidStack(Fluids.WATER, 250);

        assertEquals(new GenericStack(AEItemKey.of(item), 3), registry.convert(item));
        assertEquals(new GenericStack(AEFluidKey.of(fluid), 250), registry.convert(fluid));
        assertEquals(new MachineRecipeImportedStack(AEItemKey.of(item), 3),
                MachineRecipeImportedStack.fromItemStack(item));
        assertEquals(new MachineRecipeImportedStack(AEFluidKey.of(fluid), 250),
                MachineRecipeImportedStack.fromFluidStack(fluid));
    }

    @Test
    void missingInjectedCollaboratorsFailDuringConstruction() {
        assertThrows(NullPointerException.class, () -> new MachineAdapterRegistry(null));
        assertThrows(NullPointerException.class, () -> new DirectProcessingStackConverterRegistry(null));
        assertThrows(NullPointerException.class, () -> new DirectProcessingExternalRecipeShapeRegistry(null));
        assertThrows(NullPointerException.class, () -> new GenericRecipeShapeReader(null,
                new DirectProcessingExternalRecipeShapeRegistry(List.of())));
        assertThrows(NullPointerException.class, () -> new MachineRecipeIndexBuilder(MachineAdapterRegistry.empty(), null));
    }

    /** 注册器只传递配方引用，任何配方数据读取都是本测试的意外调用。 */
    private static final class UnreadRecipe implements Recipe<RecipeInput> {
        @Override
        public boolean matches(RecipeInput input, Level level) {
            throw new AssertionError("注册器不应读取匹配条件");
        }

        @Override
        public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
            throw new AssertionError("注册器不应执行合成");
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            throw new AssertionError("注册器不应读取合成尺寸");
        }

        @Override
        public ItemStack getResultItem(HolderLookup.Provider registries) {
            throw new AssertionError("注册器不应读取产物");
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            throw new AssertionError("注册器不应读取序列化器");
        }

        @Override
        public RecipeType<?> getType() {
            throw new AssertionError("注册器不应读取配方类型");
        }
    }
}
