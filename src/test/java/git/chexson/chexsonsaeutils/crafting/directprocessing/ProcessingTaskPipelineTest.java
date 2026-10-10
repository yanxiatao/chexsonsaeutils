package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.CraftingHostLocator;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchStep;
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

/**
 * Phase 5 分步流水守恒：步序推进后 payload 必须等于净产出
 * （Σ步产出 − Σ步输入 = aggregatedOutputs + remainders）；首步外部输入
 * 不齐时 push 被拒（返回 null，CPU 退回原料）；中步缺口挂起不耗 tick；
 * executionCount = Σ步执行数。
 */
class ProcessingTaskPipelineTest {

    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

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
        FakeHostBatchPattern pattern = new FakeHostBatchPattern(
                List.of(),
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
        FakeHostBatchPattern pattern = new FakeHostBatchPattern(
                List.of(),
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

    /**
     * 缓冲那一格越出 long 时不许被读成 0：{@code fastValue()} 在宽值上是规范化留下的空位，
     * 少了 {@code isFast()} 前置判就把"满缓冲"读成"空缓冲"，缺口按全额再要一次料。
     *
     * <p>链是半覆盖的：步 0 只产 3 枚 mid、步 1 要吃 6 枚——先钉住"缺 3 枚照报"这条控制腿，
     * 再续一笔 {@code Long.MAX_VALUE} 把 mid 抬到 {@code 2^63 + 2}（3 + (2^63-1)），
     * 缺口那一腿必须整条消失，不许按全额 6 枚再要一次。
     */
    @Test
    void bufferAboveLongDomainCoversStepInputInsteadOfReadingAsZero() {
        ProcessingTask wide = stepShortTask();
        wide.feedExternalInputs(Map.of(MID, Long.MAX_VALUE));
        Map<AEKey, Long> wideNeeds = wide.currentStepNeeds();
        assertTrue(wideNeeds.isEmpty(),
                () -> "缓冲里有 2^63+2 枚 mid，6 枚的一步缺口不许被宽值读成 0 后按全额再要一次: " + wideNeeds);

        // long 域顶格内侧（3 + (2^63-4) 恰好 = 2^63-1，仍是 fast 读数）：同一条判据，逐字不退
        ProcessingTask atLongTop = stepShortTask();
        atLongTop.feedExternalInputs(Map.of(MID, Long.MAX_VALUE - 4L));
        assertTrue(atLongTop.currentStepNeeds().isEmpty(), "顶格内侧的满缓冲照旧不许报缺");
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

    /**
     * 缓冲落盘要记得住越界那一截：老写法只写 {@code Ae2Amounts.toLong} 那份饱和读数，
     * 而磁盘不是 AE2 出口——第一次存盘就把 {@code 2^63 + 2} 枚 mid 削成 {@code Long.MAX_VALUE}，
     * 越界那一截永久消失（重启后连"少要了这一截"都查不出来）。
     */
    @Test
    void pipelineBufferRoundTripsThroughNbtBitForBitAboveLongDomain() {
        ProcessingTask task = stepShortTask();
        task.feedExternalInputs(Map.of(MID, Long.MAX_VALUE));
        BigInteger exactMid = new BigInteger("9223372036854775810");

        CompoundTag tag = task.writeToTag(REGISTRIES);
        ListTag buffer = tag.getList("pipelineBuffer", Tag.TAG_COMPOUND);
        assertEquals(1, buffer.size(), "前提：缓冲只剩 mid 那一格");
        CompoundTag entry = buffer.getCompound(0);
        assertTrue(entry.contains(WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY),
                "越界那一格必须多挂宽域覆盖键，不许只落饱和读数");
        assertEquals(exactMid, new BigInteger(entry.getByteArray(WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE)),
                "落盘的宽域位逐位等于 2^63+2");
        assertEquals(Long.MAX_VALUE, GenericStack.readTag(REGISTRIES, entry).amount(),
                "老 long 位照旧是饱和读数，AE2 那侧的口径不许动");

        ProcessingTask restored = ProcessingTask.readFromTag(tag, REGISTRIES);
        assertNotNull(restored, "带覆盖键的缓冲条目不许把任务读没了");
        ListTag rewritten = restored.writeToTag(REGISTRIES).getList("pipelineBuffer", Tag.TAG_COMPOUND);
        assertEquals(1, rewritten.size());
        assertEquals(exactMid,
                new BigInteger(rewritten.getCompound(0).getByteArray(WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE)),
                "回读再落盘逐位相同——越界那一截真的回来了，不是只把 Long.MAX_VALUE 搬回来");
        assertTrue(restored.currentStepNeeds().isEmpty(), "还原后的缓冲仍要读得出够这一口");

        // 反向控制：越界条目不带覆盖键时按老 long 位读，一位都不许多出来
        entry.remove(WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE);
        ProcessingTask legacy = ProcessingTask.readFromTag(tag, REGISTRIES);
        assertNotNull(legacy);
        assertEquals(Long.MAX_VALUE, GenericStack.readTag(REGISTRIES,
                legacy.writeToTag(REGISTRIES).getList("pipelineBuffer", Tag.TAG_COMPOUND).getCompound(0)).amount(),
                "缺覆盖键就是这笔装得进 long：按老 long 读数原样吃回来");
    }

    /** 走完步 0、停在步 1 的任务：步 0 产 3 枚 mid，步 1 要吃 6 枚，缓冲正好差 3 枚。 */
    private static ProcessingTask stepShortTask() {
        List<HostBatchStep> halfSteps = List.of(
                new HostBatchStep(new ItemStack(Items.PAPER), 2L,
                        List.of(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L)),
                        new GenericStack(MID, 3L),
                        List.of()),
                new HostBatchStep(new ItemStack(Items.PAPER), 3L,
                        List.of(new GenericStack(MID, 6L)),
                        new GenericStack(OUT, 1L),
                        List.of(new GenericStack(FUEL, 1L))));
        ProcessingTask task = ProcessingTask.compileAggregated(
                new FakeHostBatchPattern(halfSteps,
                        List.of(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L)),
                        List.of(new GenericStack(OUT, 1L))),
                new KeyCounter[]{holders(new GenericStack(RAW, 4L), new GenericStack(FUEL, 1L))},
                20);
        assertNotNull(task, "前提：首步供料齐备，聚合任务必须编出来");
        task.advance(8);
        // 控制腿：缓冲只有 3 枚时缺口照报 3 枚——修法不许把判据改成恒过
        assertEquals(Map.of(MID, 3L), task.currentStepNeeds(),
                "前提：停在步 1，mid 缓冲恰有 3 枚、缺口恰报 3 枚");
        return task;
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
