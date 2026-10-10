package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 普通批处理的实收输入、精确份数、产出和拒收守恒回归。 */
class ProcessingTaskBatchTest {

    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static final AEItemKey RAW = AEItemKey.of(new ItemStack(Items.IRON_ORE));
    private static final AEItemKey MID = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
    private static final AEItemKey FUEL = AEItemKey.of(new ItemStack(Items.COAL));
    private static final AEItemKey OUT = AEItemKey.of(new ItemStack(Items.IRON_BLOCK));

    private static KeyCounter holders(GenericStack... stacks) {
        KeyCounter holder = new KeyCounter();
        for (GenericStack stack : stacks) {
            holder.add(stack.what(), stack.amount());
        }
        return holder;
    }

    /**
     * K2 调用点钉子（夹顶值就是顶）：{@link ProcessingLatencyOrigin#merge} 的 tick 总和累加已委托
     * 共享双向档 {@code ClampedMath.add}——两份 {@link Long#MAX_VALUE} 相加必须停在顶上，不许回绕成负数。
     * 同文件的样本数累加那一档夹的是 0 底（业务底不是数域顶），按 K2 派工单留在原位，本条同时钉它没被顺手换掉。
     */
    @Test
    void mergeOfTwoTopTickSumsStaysAtTop() {
        var merged = new ProcessingLatencyOrigin(Long.MAX_VALUE, 1)
                .merge(new ProcessingLatencyOrigin(Long.MAX_VALUE, 1));

        assertEquals(Long.MAX_VALUE, merged.acceptedTickSum(),
                "两份顶值 tick 总和相加必须停在顶上，不许回绕成负数");
        assertEquals(2, merged.acceptedPushCount(), "样本数累加仍走本文件的夹 0 底 int 档");

    }

    @Test
    void ordinaryCompileValidationFailureRestoresEveryHolder() {
        IPatternDetails pattern = FakePlanGrid.FakePattern.withOutputs(
                new IPatternDetails.IInput[]{
                        new FakePlanGrid.FakeInput(RAW, 4L),
                        new FakePlanGrid.FakeInput(FUEL, 1L)
                },
                new GenericStack(OUT, 1L)
        );
        RecipeSignature incompatible = new RecipeSignature(
                MachineRecipeKind.FURNACE,
                List.of(new RecipeSignatureInput(RAW, 4L)),
                List.of(new GenericStack(OUT, 1L))
        );
        KeyCounter rawHolder = holders(new GenericStack(RAW, 4L));
        KeyCounter fuelHolder = holders(new GenericStack(FUEL, 1L));

        assertNull(ProcessingTask.compile(
                pattern,
                incompatible,
                new KeyCounter[]{rawHolder, fuelHolder},
                20
        ));
        assertEquals(4L, rawHolder.get(RAW));
        assertEquals(1L, fuelHolder.get(FUEL));
    }

    @Test
    void neoEcoBatchCompilesOneQueueTaskWithCountedPayloadAndSource() {
        RecipeSignature signature = new RecipeSignature(
                MachineRecipeKind.FURNACE,
                List.of(new RecipeSignatureInput(RAW, 2L)),
                List.of(new GenericStack(OUT, 3L))
        );
        UUID sourceCraftingId = UUID.randomUUID();

        ProcessingTask task = ProcessingTask.compileNeoEcoBatch(
                new BatchPattern(
                        List.of(new GenericStack(RAW, 2L)),
                        List.of(new GenericStack(OUT, 3L))),
                signature,
                List.of(new GenericStack(RAW, 2L)),
                List.of(new GenericStack(OUT, 3L)),
                List.of(new GenericStack(FUEL, 1L)),
                4L,
                20,
                sourceCraftingId
        );

        assertNotNull(task);
        assertEquals(4, task.executionCount());
        assertEquals(sourceCraftingId, task.sourceCraftingId());
        assertEquals(Map.of(OUT, 12L, FUEL, 4L), totals(task.buildOutputPayload()));
        ProcessingExecutionQueue queue = new ProcessingExecutionQueue(1);
        assertEquals(1, queue.remainingCapacity());
        assertTrue(queue.offer(task));
        assertEquals(1, queue.queuedTaskCount());
        assertEquals(0, queue.remainingCapacity());
        assertFalse(queue.offer(task), "counted batch 只占一个 task，满槽后不得重复接收");
        advanceSteps(task, 79);
        assertFalse(task.isReadyToComplete());
        task.advance(1);
        assertTrue(task.isReadyToComplete());
    }

    /**
     * 份数不再夹在 int 上：越过 {@code Integer.MAX_VALUE} 必须照收，工时随份数同宽；
     * 唯一的拒收理由是「份数 × 每发 tick」越过 long 上界。
     */
    @Test
    void neoEcoBatchKeepsWideCountsAndRejectsOnlyLongOverflowWorkBudget() {
        RecipeSignature signature = new RecipeSignature(
                MachineRecipeKind.FURNACE,
                RAW,
                1L,
                OUT,
                1L
        );
        BatchPattern pattern = new BatchPattern(
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, 1L))
        );

        ProcessingTask aboveInt = ProcessingTask.compileNeoEcoBatch(
                pattern,
                signature,
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, 1L)),
                List.of(),
                (long) Integer.MAX_VALUE + 1L,
                1,
                UUID.randomUUID()
        );
        assertNotNull(aboveInt, "份数越过 int 上限不得再被拒收");
        assertEquals((long) Integer.MAX_VALUE + 1L, aboveInt.executionCount(), "执行计数不得被截断");

        ProcessingTask wideTicks = ProcessingTask.compileNeoEcoBatch(
                pattern,
                signature,
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, 1L)),
                List.of(),
                Integer.MAX_VALUE,
                2,
                UUID.randomUUID()
        );
        assertNotNull(wideTicks, "工时乘份数越 int 不再是拒收理由：工时已随份数同宽");
        // 整批工时 = 2 × Integer.MAX_VALUE 发：推一发 Integer.MAX_VALUE 还差一半
        wideTicks.advance(Integer.MAX_VALUE);
        assertFalse(wideTicks.isReadyToComplete(), "工时没推满就不得提前完成（防饱和提速）");
        wideTicks.advance(Integer.MAX_VALUE);
        assertTrue(wideTicks.isReadyToComplete(), "推满 long 倍工时后才允许完成");

        assertNull(ProcessingTask.compileNeoEcoBatch(
                pattern,
                signature,
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, 1L)),
                List.of(),
                Long.MAX_VALUE,
                2,
                UUID.randomUUID()
        ), "份数 × 每发 tick 越过 long 上界才是真拒收");
    }

    /**
     * 直连机 2.1T 读数的回归口径：每发 1000 mB 流体的处理任务，份数越过
     * {@code Integer.MAX_VALUE} 时不拒收、计数不截断，实际推进份数与申报一致
     * （2.1T = Integer.MAX_VALUE × 1000 就是那条 int 链夹出来的读数）。
     */
    @Test
    void fluidBatchAboveIntExecutionLimitAdvancesEveryDeclaredExecution() {
        AEKey lava = AEFluidKey.of(Fluids.LAVA);
        long declared = (long) Integer.MAX_VALUE + 1L;
        RecipeSignature signature = new RecipeSignature(
                MachineRecipeKind.FURNACE,
                RAW,
                1L,
                lava,
                1000L
        );
        BatchPattern pattern = new BatchPattern(
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(lava, 1000L))
        );

        ProcessingTask task = ProcessingTask.compileNeoEcoBatch(
                pattern,
                signature,
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(lava, 1000L)),
                List.of(),
                declared,
                1,
                UUID.randomUUID()
        );

        assertNotNull(task, "每发 1000 mB 的流体批越过 int 份数不得被拒收");
        assertEquals(declared, task.executionCount(), "执行计数不得夹在 2.1e9");
        // 一发一 tick：申报 2.1e9+1 份就要推满 2.1e9+1 发，不被 int 工时夹成提前完成
        task.advance(Integer.MAX_VALUE);
        assertFalse(task.isReadyToComplete(), "只差一份时不得提前完成");
        task.advance(1);
        assertTrue(task.isReadyToComplete(), "推满申报份数才允许完成");
        assertEquals(Map.of(lava, declared * 1000L), totals(task.buildOutputPayload()),
                "产出量 = 每发 1000 mB × 申报份数，不得停在 2.1T");
    }

    @Test
    void neoEcoBatchRejectsMergedPayloadOverflow() {
        RecipeSignature signature = new RecipeSignature(
                MachineRecipeKind.FURNACE,
                RAW,
                1L,
                OUT,
                Long.MAX_VALUE
        );
        BatchPattern pattern = new BatchPattern(
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, Long.MAX_VALUE))
        );

        assertNull(ProcessingTask.compileNeoEcoBatch(
                pattern,
                signature,
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, Long.MAX_VALUE)),
                List.of(new GenericStack(OUT, 1L)),
                1L,
                1,
                UUID.randomUUID()
        ));
    }

    @Test
    void componentSpecificSignatureIsPermutationInvariantAndRejectsSiblingVariant() {
        AEItemKey variantA = namedKey(Items.AMETHYST_SHARD, "variant-a");
        AEItemKey variantB = namedKey(Items.AMETHYST_SHARD, "variant-b");
        List<RecipeSignatureInput> ordered = List.of(
                new RecipeSignatureInput(variantA, 1L),
                new RecipeSignatureInput(variantB, 1L));
        List<RecipeSignatureInput> reversed = List.of(
                new RecipeSignatureInput(variantB, 1L),
                new RecipeSignatureInput(variantA, 1L));
        var signature = new RecipeSignature(
                MachineRecipeKind.FURNACE,
                ordered,
                List.of(new GenericStack(OUT, 1L)));
        var index = new MachineRecipeIndex(
                new MachineIdentity(
                        ResourceLocation.fromNamespaceAndPath("test", "machine"),
                        null, null, "test", null, null),
                Set.of(MachineRecipeKind.FURNACE),
                List.of(),
                Set.of(signature),
                MachineSupportStatus.SUPPORTED_EXPLICIT,
                MachineSupportReasonCode.NONE,
                1L);

        assertEquals(RecipeSignatureKey.of(ordered, signature.outputs()),
                RecipeSignatureKey.of(reversed, signature.outputs()));
        assertEquals(signature, index.findSignature(reversed, signature.outputs()));
        assertNull(index.findSignature(
                List.of(new RecipeSignatureInput(variantA, 2L)), signature.outputs()));
        assertNotEquals(variantA, variantB);
    }

    private static AEItemKey namedKey(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
    }

    private static Map<AEKey, Long> totals(List<GenericStack> stacks) {
        Map<AEKey, Long> totals = new LinkedHashMap<>();
        for (GenericStack stack : stacks) {
            totals.merge(stack.what(), stack.amount(), Long::sum);
        }
        return totals;
    }

    /**
     * K2 钳位调用点钉子：{@link DirectProcessingStackSupport#normalizeStacks} 的合并口"夹顶值就是顶"——
     * 同键两份栈量相加越过 long 域顶时读数夹在 Long.MAX_VALUE，不回绕成负数、也不读成 0。
     * 算术契约（含负向夹底那一半）由 bigint/ClampedMathTest 钉；本条只钉这个调用点走的是同一份钳位，
     * 因为该口按 amount&lt;=0 过滤，负方向在这条腿上走不到。
     */
    @Test
    void normalizeStacksClampsMergedAmountAtLongTopInsteadOfWrapping() {
        List<GenericStack> merged = DirectProcessingStackSupport.normalizeStacks(List.of(
                new GenericStack(MID, Long.MAX_VALUE - 1L),
                new GenericStack(MID, 5L)));
        assertEquals(1, merged.size(), "同键两份要并成一份");
        assertEquals(Long.MAX_VALUE, merged.get(0).amount(),
                () -> "越过 long 域顶的合并量必须夹在 Long.MAX_VALUE, 实际=" + merged.get(0).amount());
    }

    private static void advanceSteps(ProcessingTask task, int ticks) {
        for (int i = 0; i < ticks; i++) {
            task.advance(1);
        }
    }

    /** 普通样板替身，批处理使用显式输入/输出契约。 */
    private static final class BatchPattern implements IPatternDetails {
        private final IInput[] inputs;
        private final List<GenericStack> outputs;
        BatchPattern(List<GenericStack> inputs, List<GenericStack> outputs) {
            this.inputs = inputs.stream().map(stack -> new FakePlanGrid.FakeInput(stack.what(), stack.amount()))
                    .toArray(IInput[]::new);
            this.outputs = outputs;
        }
        @Override public AEItemKey getDefinition() { return AEItemKey.of(new ItemStack(Items.PAPER)); }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public List<GenericStack> getOutputs() { return outputs; }
    }
}
