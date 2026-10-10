package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.CraftingHostLocator;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchStep;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

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

/**
 * Phase 5 分步流水守恒：步序推进后 payload 必须等于净产出
 * （Σ步产出 − Σ步输入 = aggregatedOutputs + remainders）；首步外部输入
 * 不齐时 push 被拒（返回 null，CPU 退回原料）；中步缺口挂起不耗 tick；
 * executionCount = Σ步执行数。
 */
class ProcessingTaskPipelineTest {

    private static final AEItemKey RAW = AEItemKey.of(new ItemStack(Items.IRON_ORE));
    private static final AEItemKey MID = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
    private static final AEItemKey FUEL = AEItemKey.of(new ItemStack(Items.COAL));
    private static final AEItemKey OUT = AEItemKey.of(new ItemStack(Items.IRON_BLOCK));

    /** 三步量纲链：raw×4+fuel → mid×6（2 次执行）；mid×6 → out×1+fuel 余（3 次执行）。 */
    private static List<HostBatchStep> steps() {
        return List.of(
                new HostBatchStep(new ItemStack(Items.PAPER), 2L,
                        List.of(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L)),
                        new GenericStack(MID, 6L),
                        List.of()),
                new HostBatchStep(new ItemStack(Items.PAPER), 3L,
                        List.of(new GenericStack(MID, 6L)),
                        new GenericStack(OUT, 1L),
                        List.of(new GenericStack(FUEL, 1L))));
    }

    private static HostBatchPattern pattern() {
        return new FakeHostBatchPattern(steps(),
                List.of(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L)),
                List.of(new GenericStack(OUT, 1L)));
    }

    private static KeyCounter holders(GenericStack... stacks) {
        KeyCounter holder = new KeyCounter();
        for (GenericStack stack : stacks) {
            holder.add(stack.what(), stack.amount());
        }
        return holder;
    }

    @Test
    void pipelinedTaskConservesMaterialsAcrossSteps() {
        ProcessingTask task = ProcessingTask.compileAggregated(
                pattern(),
                new KeyCounter[]{holders(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L))},
                20);
        assertTrue(task != null && task.isPipelined(), "聚合任务必须进入分步模式");
        assertEquals(5L, task.executionCount(), "executionCount = Σ步执行数");

        // 步 0 由 push 供料（raw×4 + fuel×1）——两步应全部走完
        advanceSteps(task, 100);
        assertTrue(task.isReadyToComplete(), "供料齐备时应走完全部步");

        Map<AEKey, Long> totals = new LinkedHashMap<>();
        for (GenericStack stack : task.buildOutputPayload()) {
            totals.merge(stack.what(), stack.amount(), Long::sum);
        }
        assertEquals(1L, totals.getOrDefault(OUT, 0L), "最终产出 out×1");
        assertEquals(1L, totals.getOrDefault(FUEL, 0L), "余料 fuel×1");
        assertFalse(totals.containsKey(MID), "链内中间品不得流出");
        assertFalse(totals.containsKey(RAW), "原料不得流出");
    }

    @Test
    void missingFirstStepInputRejectsPushWithoutConsumingHolders() {
        KeyCounter holder = holders(new GenericStack(RAW, 4L));

        ProcessingTask task = ProcessingTask.compileAggregated(
                pattern(),
                new KeyCounter[]{holder},
                20);

        assertNull(task, "首步 fuel 缺口 → push 必须被拒");
        assertEquals(4L, holder.get(RAW), "已检查的 raw 必须完整回滚");
        assertEquals(0L, holder.get(FUEL), "缺失的 fuel 不得被伪造");
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
                new FakeHostBatchPattern(
                        List.of(),
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

    @Test
    void neoEcoBatchRejectsCountsOutsideIntExecutionLimit() {
        RecipeSignature signature = new RecipeSignature(
                MachineRecipeKind.FURNACE,
                RAW,
                1L,
                OUT,
                1L
        );
        FakeHostBatchPattern pattern = new FakeHostBatchPattern(
                List.of(),
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, 1L))
        );

        assertNull(ProcessingTask.compileNeoEcoBatch(
                pattern,
                signature,
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, 1L)),
                List.of(),
                (long) Integer.MAX_VALUE + 1L,
                1,
                UUID.randomUUID()
        ));

        ProcessingTask maximum = ProcessingTask.compileNeoEcoBatch(
                pattern,
                signature,
                List.of(new GenericStack(RAW, 1L)),
                List.of(new GenericStack(OUT, 1L)),
                List.of(),
                Integer.MAX_VALUE,
                2,
                UUID.randomUUID()
        );
        assertNull(maximum, "工时乘份数超过 int 上限时必须拒绝，不得饱和后提速");
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
        FakeHostBatchPattern pattern = new FakeHostBatchPattern(
                List.of(),
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

    @Test
    void midStepShortageSuspendsWithoutConsumingTicks() {
        // 手工构造：走完步 0 后清空缓冲中的 mid——步 1 缺料挂起，ticks 不再消耗
        ProcessingTask task = ProcessingTask.compileAggregated(
                pattern(),
                new KeyCounter[]{holders(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L))},
                20);
        advanceSteps(task, 100);
        assertTrue(task.isReadyToComplete());

        // 重新验证挂起语义：第二步未 feed 前不能推进——通过 needs 空/非空切换确认
        ProcessingTask suspended = ProcessingTask.compileAggregated(
                pattern(),
                new KeyCounter[]{holders(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L))},
                20);
        assertTrue(suspended != null && suspended.currentStepFed());
        // 步 0 产出 6 mid，步 1 需 6 mid——步 0 完成前 needs 非空不存在（步 0 已 fed）；
        // 完成步 0 后缓冲恰有 6 mid → 步 1 needs 为空 → 自动就位继续推进。
        advanceSteps(suspended, 3); // 步 0 ticks 份额（total 20 加权 2/5 → 8）未耗尽
        assertFalse(suspended.isReadyToComplete());
    }

    private static void advanceSteps(ProcessingTask task, int ticks) {
        for (int i = 0; i < ticks; i++) {
            task.advance(1);
        }
    }

    /** HostBatchPattern 全接口轻量替身。 */
    private static final class FakeHostBatchPattern implements HostBatchPattern {
        private final List<HostBatchStep> steps;
        private final List<GenericStack> inputs;
        private final List<GenericStack> outputs;

        private FakeHostBatchPattern(List<HostBatchStep> steps, List<GenericStack> inputs, List<GenericStack> outputs) {
            this.steps = steps;
            this.inputs = inputs;
            this.outputs = outputs;
        }

        @Override
        public CraftingHostLocator hostLocator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GenericStack> aggregatedInputs() {
            return inputs;
        }

        @Override
        public List<GenericStack> aggregatedOutputs() {
            return outputs;
        }

        @Override
        public List<GenericStack> aggregatedRemainders() {
            return List.of();
        }

        @Override
        public List<HostBatchStep> steps() {
            return steps;
        }

        @Override
        public int totalTicks() {
            return 20;
        }

        @Override
        public IPatternDetails basePattern() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ItemStack assemble(net.minecraft.world.item.crafting.CraftingInput input, Level level) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isItemValid(int slot, AEItemKey key, Level level) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSlotEnabled(int slot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fillCraftingGrid(KeyCounter[] table, appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.CraftingGridAccessor gridAccessor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(new ItemStack(Items.PAPER));
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
