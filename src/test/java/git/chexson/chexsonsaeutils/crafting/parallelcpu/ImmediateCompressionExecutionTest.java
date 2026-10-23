package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ImmediateBatchOutputHost;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 直接运行 CPU 的申报、取料、交割与真实回流，链长不再等于必须等待的 tick 数。 */
class ImmediateCompressionExecutionTest {
    @Test
    void executes4000CompressionStagesWithExactPaymentAndImmediateRealOutputs() {
        int depth = 4000;
        var keys = new ArrayList<AEItemKey>();
        for (int index = 0; index <= depth; index++) keys.add(FakePlanGrid.dyedKey(Items.PAPER, index));
        var builder = FakeGrid.builder().storage(Map.of(keys.getLast(), Long.MAX_VALUE));
        var patterns = new ArrayList<FakePattern>();
        for (int index = 0; index < depth; index++) {
            var pattern = FakePattern.of(keys.get(index), 1, new FakeInput(keys.get(index + 1), 9));
            patterns.add(pattern);
            builder.pattern(keys.get(index), pattern);
        }
        var grid = builder.build();
        var required = BigInteger.valueOf(9).pow(depth).multiply(BigInteger.TWO);
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), keys.getFirst(), null);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(keys.getLast(), PlanAmount.of(required))));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
        assertFalse(result.hasDeclined(), result::declineReason);
        var plan = Ae2PlanConverter.toPlan(result, graph);
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false), (ICraftingCPU) null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        assertEquals(patterns.reversed(), new ArrayList<>(job.tasks.keySet()), "实际任务表必须先原料后上级");
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(keys.getLast(), required, Actionable.MODULATE);
        var assigned = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
        var firings = BigInteger.TWO;
        for (int index = 0; index < depth; index++) {
            var quota = new WideKeyCounter();
            quota.add(keys.get(index + 1), firings.multiply(BigInteger.valueOf(9)));
            assigned.put(patterns.get(index), quota);
            firings = firings.multiply(BigInteger.valueOf(9));
        }
        job.materialAllocation.adopt(assigned, Map.of());
        assertTrue(ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job).isEmpty());
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                job.unboundedSupplyKeys, job.ringSeeds.seedDemandsByFamily(), job.batchObligations);
        var ledger = new ParallelPoolLedger();
        var host = new ImmediateHost(grid);
        long start = System.nanoTime();
        for (int index = depth - 1; index >= 0; index--) {
            if (index % 128 == 0) {
                assertTrue(ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job).isEmpty());
                reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                        job.unboundedSupplyKeys, job.ringSeeds.seedDemandsByFamily(), job.batchObligations);
            }
            var pattern = patterns.get(index);
            var pushed = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, pattern, job,
                    job.tasks.entrySet(), pool, null, reservation, ledger, new KeyCounter(), new KeyCounter(),
                    Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0);
            assertNotNull(pushed, "本级应使用整批申报");
            assertTrue(pushed.accepted() > 0);
            assertTrue(pool.amount(keys.get(index)).signum() == 0, "登记等待前不能提前交货");
            ParallelCraftingCpuLogic.settleImmediateBatch(host, job, pattern, pool, null, reservation,
                    ledger, null, (source, what, available) -> {
                        assertSame(grid, source);
                        return what.equals(keys.getFirst())
                                ? ParallelCraftingCpuLogic.claimDeclaredStandaloneOutput(job, pool, what, available, ignored -> {})
                                : ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, what, available, ignored -> {});
                    });
            assertFalse(job.batchObligations.hasPending(pattern));
            assertTrue(job.tasks.get(pattern).isFinished());
            assertTrue(host.buffer.isEmpty());
            assertEquals(BigInteger.ZERO, pool.amount(keys.get(index + 1)), "上级材料必须全额扣除");
            assertEquals(BigInteger.ZERO, job.materialAllocation.remainingFamilyQuota(pattern,
                    keys.get(index + 1)).exactValue(), "本级真实配额必须全部销账");
        }
        assertEquals(BigInteger.TWO, pool.amount(keys.getFirst()));
        assertTrue(job.waitingFor.list.isEmpty());
        System.out.printf("%d 级即时执行耗时 ms=%.3f%n", depth, (System.nanoTime() - start) / 1_000_000.0);
    }

    @Test
    void bufferedOutputKeepsRejectedAndPartiallyAcceptedAmounts() {
        var grid = FakeGrid.builder().build();
        var host = new ImmediateHost(grid);
        var key = AEItemKey.of(Items.DIAMOND);
        var amount = BigInteger.TEN.pow(500);
        host.buffer.add(key, amount);
        host.deliverBufferedBatchOutputs((source, what, available) -> BigInteger.ZERO);
        assertEquals(amount, host.buffer.getExact(key));
        host.deliverBufferedBatchOutputs((source, what, available) -> available.subtract(BigInteger.ONE));
        assertEquals(BigInteger.ONE, host.buffer.getExact(key));
    }

    @Test
    void missingPaymentKeepsDebtAndDoesNotDeliverTheBufferedBatchEarly() {
        var raw = AEItemKey.of(Items.IRON_INGOT);
        var output = AEItemKey.of(Items.IRON_BLOCK);
        var pattern = FakePattern.of(output, 1, new FakeInput(raw, 9));
        var base = new appeng.crafting.CraftingPlan(new appeng.api.stacks.GenericStack(output, 2), 1,
                false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 2L));
        var job = new ParallelExecutingCraftingJob(base, ignored -> {}, null, null);
        job.batchObligations.incur(pattern, BigInteger.ONE);
        var pool = new WideCraftingInventory(ignored -> {});
        var reservation = MaterialAllocationFixtures.build(
                Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(), Map.of(), Map.of(), Map.of());
        var host = new ImmediateHost(FakeGrid.builder().build());
        host.buffer.add(output, BigInteger.TWO);
        var deliveries = new java.util.concurrent.atomic.AtomicInteger();
        ParallelCraftingCpuLogic.settleImmediateBatch(host, job, pattern, pool, null, reservation,
                new ParallelPoolLedger(), null, (source, what, available) -> {
                    deliveries.incrementAndGet();
                    return available;
                });
        assertEquals(0, deliveries.get());
        assertEquals(BigInteger.ONE, job.batchObligations.pendingFirings(pattern));
        assertEquals(BigInteger.TWO, host.buffer.getExact(output));
    }

    private static final class ImmediateHost implements DeclaredBatchHost, ImmediateBatchOutputHost {
        private final IGrid grid;
        private final WideKeyCounter buffer = new WideKeyCounter();
        private ImmediateHost(IGrid grid) { this.grid = grid; }
        @Override public boolean supportsDeclaredBatch(IPatternDetails pattern) { return true; }
        @Override public boolean declaredBatchFiringsUnbounded() { return true; }
        @Override public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] prototype, BigInteger count) {
            for (var output : pattern.getOutputs()) buffer.add(output.what(), count.multiply(BigInteger.valueOf(output.amount())));
            return count;
        }
        @Override public void deliverBufferedBatchOutputs(DeclaredBatchOutputReceiver receiver) {
            CraftingOutputRouter.claimBufferedOutputs(buffer, grid, receiver);
        }
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] holder) { return false; }
        @Override public boolean isBusy() { return false; }
    }
}
