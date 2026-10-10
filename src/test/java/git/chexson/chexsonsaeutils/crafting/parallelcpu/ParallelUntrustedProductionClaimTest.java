package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未来计划产量超出 long 时，池内有限实物仍须留给已声明批次。
 * 用真实取料与销账验证：预留阻止重复声明，原欠账可以消费这些实物并清零。
 */
final class ParallelUntrustedProductionClaimTest {

    private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    /** 计划产量超出 long、但池中确有有限实物的键。 */
    private static final AEKey CUBE = AEItemKey.of(new ItemStack(Items.ENDER_EYE));
    /** 把贷方账顶撞顶的那张样板自己吃的键。 */
    private static final AEKey DIRT = AEItemKey.of(new ItemStack(Items.DIRT));
    /** 欠账发数：恰好占用池中全部现货。 */
    private static final long OWED = 1_000L;

    @Test
    void unrepresentableFutureProductionStillReservesExistingStockForDebt() {
        // producer 一发产 Long.MAX_VALUE 枚 CUBE：这本贷方账撞顶 ⇒ CUBE 进"没有可信口径"的名单
        var producer = pattern(Items.GRAVEL, new GenericStack(DIRT, 1L),
                new GenericStack(CUBE, Long.MAX_VALUE));
        // debtor 是申报出去、还欠着 OWED 发材料的那一张；declarer 是同吃 CUBE 的下一张
        var debtor = pattern(Items.STONE, new GenericStack(CUBE, 1L),
                new GenericStack(AEItemKey.of(new ItemStack(Items.COBBLESTONE)), 1L));
        var declarer = pattern(Items.OBSIDIAN, new GenericStack(CUBE, 1L),
                new GenericStack(AEItemKey.of(new ItemStack(Items.STONE)), 1L));

        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(CUBE, OWED, Actionable.MODULATE);
        var job = newJob();
        job.tasks.put(producer, progress(1L));
        job.tasks.put(debtor, progress(OWED + 1L));
        job.tasks.put(declarer, progress(1L));
        job.batchObligations.incur(debtor, BigInteger.valueOf(OWED));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(),
                Map.of(CUBE, OWED, DIRT, 1L), job.materialAllocation);

        assertEquals(1, reservation.untrustedProductionKeyCount(),
                "前提读数：CUBE 这本产出贷方账放不下可信口径（实机同一行的 prodUntrusted=1）");
        assertTrue(reservation.productionHasNoTrustworthyReading(CUBE),
                "确认计划产量投影不可信，以覆盖旧补丁错误豁免的条件");

        var claims = ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                job.tasks.entrySet(), pool, reservation);
        assertEquals(BigInteger.valueOf(OWED), claims.getExact(CUBE),
                "未来产量超出 long 不改变已经存在的实物：欠账仍须预留池内口粮");

        assertEquals(BigInteger.ZERO, ParallelBatchExtraction.declaredPayableCeiling(
                declarer, pool, null, reservation, BIG_LONG, claims),
                "不能把同一份池内实物再次申报给新批次");
        assertEquals(OWED, ParallelCraftingCpuLogic.payObligationBatch(job, debtor, OWED,
                pool, null, reservation, new ParallelPoolLedger(), null, null));
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(debtor));
        assertEquals(BigInteger.ZERO, pool.amount(CUBE), "已预留实物完整用于原欠账交割");
    }

    @Test
    void continuingDebtsRotateSoTheFifthPatternGetsPaidNextRound() {
        var job = newJob();
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(CUBE, BIG_LONG.multiply(BIG_LONG).multiply(BigInteger.valueOf(100L)), Actionable.MODULATE);
        for (Item definition : List.of(Items.STONE, Items.GRAVEL, Items.OBSIDIAN,
                Items.COBBLESTONE, Items.SAND)) {
            var debtor = pattern(definition, new GenericStack(CUBE, Long.MAX_VALUE),
                    new GenericStack(DIRT, 1L));
            job.tasks.put(debtor, progress(10L));
            job.batchObligations.incur(debtor, BIG_LONG.multiply(BigInteger.valueOf(20L)));
        }
        var fifth = job.batchObligations.pendingPatterns().get(4);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(),
                Map.of(CUBE, Long.MAX_VALUE), job.materialAllocation);
        for (int round = 0; round < 2; round++) {
            assertEquals(Long.MAX_VALUE, ParallelCraftingCpuLogic.payObligationBatches(job, pool, null,
                    reservation, new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(),
                    () -> true));
        }
        assertEquals(BIG_LONG.multiply(BigInteger.valueOf(19L)), job.batchObligations.pendingFirings(fifth),
                "每轮限四批时，第五张必须在下一轮取得交割机会");
        assertEquals(BIG_LONG.multiply(BigInteger.valueOf(92L)), job.batchObligations.totalPending());
        assertEquals(BIG_LONG.multiply(BIG_LONG).multiply(BigInteger.valueOf(92L)), pool.amount(CUBE));
    }

    @Test
    void exhaustedBudgetLeavesUnattemptedDebtAtTheFront() {
        var job = newJob();
        for (Item definition : List.of(Items.STONE, Items.GRAVEL)) {
            var debtor = pattern(definition, new GenericStack(CUBE, Long.MAX_VALUE), new GenericStack(DIRT, 1L));
            job.tasks.put(debtor, progress(10L));
            job.batchObligations.incur(debtor, BigInteger.TEN);
        }
        var initial = job.batchObligations.pendingPatterns();
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(CUBE, BIG_LONG.multiply(BigInteger.valueOf(100L)), Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(),
                Map.of(CUBE, Long.MAX_VALUE), job.materialAllocation);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(10L, ParallelCraftingCpuLogic.payObligationBatches(job, pool, null,
                reservation, new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(),
                () -> checks.getAndIncrement() == 0));
        assertEquals(initial.get(1), job.batchObligations.pendingPatterns().getFirst());
        assertEquals(BigInteger.TEN, job.batchObligations.pendingFirings(initial.get(1)));
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(initial.getFirst()));
        assertEquals(BIG_LONG.multiply(BigInteger.valueOf(90L)), pool.amount(CUBE));
    }

    @Test
    void failedSettlementRotatesWithoutChangingDebtOrStock() {
        var job = newJob();
        var debtor = pattern(Items.STONE, new GenericStack(CUBE, 1L), new GenericStack(DIRT, 1L));
        var other = pattern(Items.GRAVEL, new GenericStack(CUBE, 1L), new GenericStack(DIRT, 1L));
        job.tasks.put(debtor, progress(10L));
        job.tasks.put(other, progress(10L));
        job.batchObligations.incur(debtor, BigInteger.TEN);
        job.batchObligations.incur(other, BigInteger.TEN);
        var first = job.batchObligations.pendingPatterns().getFirst();
        var pool = new WideCraftingInventory(ignored -> {});
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        assertEquals(0L, ParallelCraftingCpuLogic.payObligationBatch(job, first, 1L,
                pool, null, reservation, new ParallelPoolLedger(), null, null));
        assertEquals(first, job.batchObligations.pendingPatterns().getLast());
        assertEquals(BigInteger.valueOf(20L), job.batchObligations.totalPending());
        assertEquals(BigInteger.ZERO, pool.amount(CUBE));
    }

    private static ParallelExecutingCraftingJob newJob() {
        ICraftingPlan plan = new CraftingPlan(new GenericStack(CUBE, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        return new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    /** 一张只有一个候选、原样吃掉给定输入的单槽样板（退料键为 null ⇒ 不是模具槽）。 */
    private static FakePattern pattern(Item definition, GenericStack input, GenericStack output) {
        return new FakePattern(AEItemKey.of(new ItemStack(definition)),
                new GenericStack[] {output},
                new IPatternDetails.IInput[] {new FakeInput(input)});
    }

    private record FakePattern(AEItemKey definition, GenericStack[] outputs,
            IPatternDetails.IInput[] slots) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(outputs);
        }
    }

    private record FakeInput(GenericStack candidate) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {candidate};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return candidate != null && candidate.what().equals(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
