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

/** BigInteger 宿主的完整接单、产出预留、宽域扣发与欠账交割。 */
final class ParallelDeclaredWideBatchTest {
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey MATERIAL = AEItemKey.of(Items.IRON_ORE);

    @Test
    void loggedHundredUnitFiniteDebtClearsInOneRoundAndKeepsExactAccounts() {
        BigInteger firings = new BigInteger("101457092405402533877");
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 100L), new GenericStack(PRODUCT, 1L));
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings));
        job.tasks.put(details, task);
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        var ledger = new ParallelPoolLedger();
        var pool = ParallelCraftingCpuLogic.newLedgerBackedPool(ledger, ignored -> changes.incrementAndGet());
        BigInteger stock = firings.multiply(BigInteger.valueOf(100L));
        pool.insertExact(MATERIAL, stock, Actionable.MODULATE);
        var quota = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        quota.add(MATERIAL, stock);
        MaterialAllocationFixtures.assign(job, details, quota);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        var host = new WideHost();
        var pushed = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, ledger, new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);
        org.junit.jupiter.api.Assertions.assertNotNull(pushed);
        assertEquals(firings, host.accepted);
        var probe = new ParallelBatchExtraction.Probe();
        for (int tick = 0; tick < 1; tick++) {
            ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                    ledger, null, probe, () -> true);
        }
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.ZERO, pool.amount(MATERIAL));
        assertEquals(stock, job.accountTaken.getExact(MATERIAL));
        assertEquals(BigInteger.ZERO, reservation.quotaExact(MATERIAL, details, details.getInputs()[0]).exactValue());
        assertEquals(1L, probe.obligationBatches);
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(details, task, job.batchObligations));
        assertEquals(3, changes.get(), "一次入库、一发原型、一笔宽域扣料，不按材料量或发数拆循环");
    }

    @Test
    void pendingFiniteBatchWaitsWithoutFallingBackAndResumesAfterSettlement() {
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 100L), new GenericStack(PRODUCT, 1L));
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(MAX.multiply(BigInteger.TWO)));
        job.tasks.put(details, task);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(MATERIAL, MAX.multiply(BigInteger.valueOf(200L)), Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        var host = new WideHost();
        host.maxAccepted = BigInteger.TEN;
        var first = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);
        org.junit.jupiter.api.Assertions.assertNotNull(first);
        var before = pool.amount(MATERIAL);
        var remaining = task.exactRemaining();
        host.accepted = null;
        var waiting = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 1L);
        org.junit.jupiter.api.Assertions.assertNotNull(waiting);
        assertTrue(waiting.endsTaskRound());
        org.junit.jupiter.api.Assertions.assertFalse(waiting.rejected());
        org.junit.jupiter.api.Assertions.assertNull(host.accepted);
        assertEquals(before, pool.amount(MATERIAL));
        assertEquals(remaining, task.exactRemaining());
        ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(), () -> true);
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.valueOf(1000L), job.accountTaken.getExact(MATERIAL));
        host.maxAccepted = null;
        var resumed = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 2L);
        org.junit.jupiter.api.Assertions.assertNotNull(resumed);
        assertEquals(remaining.exactValue(), host.accepted);
    }

    @Test
    void unboundedHundredUnitInputDeclaresAndSettlesBeyondLongInOnePass() {
        BigInteger firings = MAX.multiply(BigInteger.valueOf(1_000_000L));
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 100L), new GenericStack(PRODUCT, 1L));
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings));
        job.tasks.put(details, task);
        var pool = new WideCraftingInventory(ignored -> {});
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                java.util.Set.of(MATERIAL));
        var host = new WideHost();
        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);
        org.junit.jupiter.api.Assertions.assertNotNull(push);
        assertEquals(firings, host.accepted);
        var probe = new ParallelBatchExtraction.Probe();
        ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                new ParallelPoolLedger(), null, probe, () -> true);
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(details));
        assertEquals(1L, probe.obligationBatches);
        assertEquals(BigInteger.ZERO, pool.amount(MATERIAL));
        assertEquals(BigInteger.ZERO, job.accountTaken.getExact(MATERIAL));
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(details, task, job.batchObligations));
    }

    @Test
    void mixedFiniteAndUnboundedInputsCannotClearUnpaidFiniteDebt() {
        var details = new FakePattern(AEItemKey.of(Items.FURNACE), new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(MATERIAL, 100L)),
                        new FakeInput(new GenericStack(AEItemKey.of(Items.COAL), 1L))});
        var job = newJob();
        job.tasks.put(details, progress(1L));
        BigInteger owed = MAX.multiply(BigInteger.TWO);
        job.batchObligations.incur(details, owed);
        var pool = new WideCraftingInventory(ignored -> {});
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                java.util.Set.of(MATERIAL));
        ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(), () -> true);
        assertEquals(owed, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.ZERO, job.accountTaken.getExact(AEItemKey.of(Items.COAL)));
    }

    @Test
    void mixedBatchPaysFiniteStockWithoutBeingCappedByHugeUnboundedInput() {
        var infinite = AEItemKey.of(Items.COAL);
        var details = new FakePattern(AEItemKey.of(Items.FURNACE),
                new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(MATERIAL, 8L)),
                        new FakeInput(new GenericStack(infinite, Long.MAX_VALUE / 3L))});
        BigInteger firings = new BigInteger("818969450506879");
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings));
        job.tasks.put(details, task);
        var pool = new WideCraftingInventory(ignored -> {});
        BigInteger finiteUnits = firings.multiply(BigInteger.valueOf(8L));
        pool.insertExact(MATERIAL, finiteUnits, Actionable.MODULATE);
        var quota = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        quota.add(MATERIAL, finiteUnits);
        MaterialAllocationFixtures.assign(job, details, quota);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                java.util.Set.of(infinite));
        var host = new WideHost();
        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);
        org.junit.jupiter.api.Assertions.assertNotNull(push);
        assertEquals(firings, host.accepted);
        assertEquals(firings.longValueExact(),
                ParallelBatchExtraction.obligationBatchFirings(details, firings));
        var probe = new ParallelBatchExtraction.Probe();
        ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                new ParallelPoolLedger(), null, probe, () -> true);
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.ZERO, pool.amount(MATERIAL));
        assertEquals(finiteUnits, job.accountTaken.getExact(MATERIAL));
        assertEquals(BigInteger.ZERO, job.accountTaken.getExact(infinite));
        assertEquals(BigInteger.ZERO, reservation.quotaExact(MATERIAL, details, details.getInputs()[0]).exactValue());
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(details, task, job.batchObligations));
        assertEquals(1L, probe.obligationBatches);
    }

    @Test
    void oneBatchExceedsLongAndPreservesEveryOutputAndOwedInput() {
        BigInteger firings = MAX.multiply(BigInteger.valueOf(3L));
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(PRODUCT, 80L));
        var job = newJob();
        var progress = progress(0L);
        progress.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings));
        job.tasks.put(details, progress);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(MATERIAL, firings, Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        var host = new WideHost();
        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);
        org.junit.jupiter.api.Assertions.assertNotNull(push);
        assertEquals(firings, host.accepted);
        assertTrue(progress.isFinished());
        assertEquals(firings.multiply(BigInteger.valueOf(80L)), job.waitingForExact(PRODUCT).exactValue());
        assertEquals(firings.multiply(BigInteger.valueOf(80L)), job.accountAnnounced.getExact(PRODUCT));
        assertEquals(firings.subtract(BigInteger.ONE), job.batchObligations.pendingFirings(details));
        assertEquals(1L, job.accountTaken.getLong(MATERIAL));
        for (int tick = 0; tick < 3; tick++) {
            ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                    new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(), () -> true);
        }
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.ZERO, pool.amount(MATERIAL));
        assertEquals(firings, job.accountTaken.getExact(MATERIAL));
    }

    @Test
    void wideNetworkPlanAndTerminalSettlementKeepTheWholeBatch() {
        BigInteger firings = MAX.multiply(BigInteger.TWO);
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(PRODUCT, 80L));
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings));
        job.tasks.put(details, task);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(MATERIAL, firings, Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        var host = new WideHost();
        host.reportToNetwork = true;
        var probe = new ParallelBatchExtraction.Probe();
        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, probe, null, null, null, 0L);
        org.junit.jupiter.api.Assertions.assertNotNull(push);
        BigInteger total = firings.multiply(BigInteger.valueOf(80L));
        assertEquals(total, job.expectedToNetwork(PRODUCT).exactValue());
        assertEquals(BigInteger.ZERO, job.waitingForExact(PRODUCT).exactValue());
        assertEquals(total, job.declaredNetworkUndelivered(PRODUCT).exactValue());
        assertEquals(firings, probe.declaredFiringsSum);
        host.receipt.deliverTerminal(host.receipt.batchId(),
                git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(BigInteger.ZERO, job.declaredNetworkUndelivered(PRODUCT).exactValue());
        assertEquals(total, job.expectedToNetwork(PRODUCT).exactValue());
    }

    @Test
    void partialWideAdmissionAndCancellationRestoreExactRemainingFirings() {
        BigInteger requested = MAX.multiply(BigInteger.valueOf(3L));
        BigInteger accepted = MAX.multiply(BigInteger.TWO);
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(PRODUCT, 80L));
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(requested));
        job.tasks.put(details, task);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(MATERIAL, requested, Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        var host = new WideHost();
        host.reportToNetwork = true;
        host.maxAccepted = accepted;
        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, details, job, job.tasks.entrySet(),
                pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);
        org.junit.jupiter.api.Assertions.assertNotNull(push);
        assertEquals(MAX, task.exactRemaining().exactValue());
        assertEquals(accepted.subtract(BigInteger.ONE), job.batchObligations.pendingFirings(details));
        assertEquals(accepted.multiply(BigInteger.valueOf(80L)), job.expectedToNetwork(PRODUCT).exactValue());
        host.receipt.deliverTerminal(host.receipt.batchId(),
                git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.CANCELLED);
        assertEquals(requested, task.exactRemaining().exactValue());
        assertEquals(BigInteger.ZERO, job.expectedToNetwork(PRODUCT).exactValue());
    }

    @Test
    void exactLongMaxQuotaStillLimitsAWiderPool() {
        BigInteger requested = MAX.multiply(BigInteger.valueOf(3L));
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(PRODUCT, 80L));
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(requested));
        job.tasks.put(details, task);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(MATERIAL, requested, Actionable.MODULATE);
        var quota = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        quota.add(MATERIAL, MAX);
        MaterialAllocationFixtures.assign(job, details, quota);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(MATERIAL, Long.MAX_VALUE),
                job.materialAllocation);
        assertEquals(MAX, ParallelCraftingCpuLogic.declaredBatchFirings(new WideHost(), details, task,
                pool, null, reservation, Long.MAX_VALUE, null));
    }

    @Test
    void duplicateSlotsShareOneWideMaterialQuota() {
        var details = new FakePattern(AEItemKey.of(Items.FURNACE), new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(MATERIAL, 1L)),
                        new FakeInput(new GenericStack(MATERIAL, 1L))});
        var job = newJob();
        var task = progress(0L);
        task.addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(MAX.multiply(BigInteger.valueOf(3L))));
        job.tasks.put(details, task);
        var quota = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        quota.add(MATERIAL, MAX.multiply(BigInteger.TWO));
        MaterialAllocationFixtures.assign(job, details, quota);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(MATERIAL, Long.MAX_VALUE),
                job.materialAllocation);
        var prototype = new KeyCounter();
        prototype.add(MATERIAL, 1L);
        assertEquals(MAX, ParallelCraftingCpuLogic.declaredPrototypeQuota(details,
                new ParallelBatchExtraction.Batch(new KeyCounter[]{prototype, prototype}, 1L),
                reservation, MAX.multiply(BigInteger.valueOf(3L))));
    }

    @Test
    void sharedSlotsPayOnlyWholeWideFiringsAvailableInPool() {
        var details = new FakePattern(AEItemKey.of(Items.FURNACE),
                new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(MATERIAL, 2L)),
                        new FakeInput(new GenericStack(MATERIAL, 3L))});
        BigInteger payable = MAX.multiply(BigInteger.TWO).add(BigInteger.valueOf(13L));
        BigInteger owed = payable.add(BigInteger.TEN);
        BigInteger stock = payable.multiply(BigInteger.valueOf(5L)).add(BigInteger.valueOf(4L));
        var job = newJob();
        job.tasks.put(details, progress(0L));
        job.batchObligations.incur(details, owed);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(MATERIAL, stock, Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        var probe = new ParallelBatchExtraction.Probe();
        ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                new ParallelPoolLedger(), null, probe, () -> true);
        assertEquals(BigInteger.TEN, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.valueOf(4L), pool.amount(MATERIAL), "不足一发的零头留在库存");
        assertEquals(payable.multiply(BigInteger.valueOf(5L)), job.accountTaken.getExact(MATERIAL));
        assertEquals(2L, probe.paidChunks, "两槽各按大数扣一次，无按 long 分段循环");
    }

    @Test
    void wideQuotaLimitsSettlementEvenWhenPoolHasMoreMaterial() {
        var details = pattern(Items.FURNACE, new GenericStack(MATERIAL, 7L), new GenericStack(PRODUCT, 1L));
        BigInteger payable = MAX.multiply(BigInteger.TWO).add(BigInteger.valueOf(13L));
        BigInteger owed = payable.add(BigInteger.TEN);
        BigInteger units = payable.multiply(BigInteger.valueOf(7L));
        var job = newJob();
        job.tasks.put(details, progress(0L));
        job.batchObligations.incur(details, owed);
        var quota = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        quota.add(MATERIAL, units.add(BigInteger.valueOf(6L)));
        MaterialAllocationFixtures.assign(job, details, quota);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(MATERIAL, owed.multiply(BigInteger.valueOf(7L)), Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
        for (int tick = 0; tick < 2; tick++) {
            ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                    new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(), () -> true);
        }
        assertEquals(BigInteger.TEN, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.valueOf(70L), pool.amount(MATERIAL));
        assertEquals(units, job.accountTaken.getExact(MATERIAL));
        assertEquals(BigInteger.valueOf(6L), reservation.quotaExact(MATERIAL, details, details.getInputs()[0]).exactValue());
    }

    @Test
    void mixedInfiniteAndFiniteDebtAlsoSettlesBeyondLongInOnePass() {
        var infinite = AEItemKey.of(Items.COAL);
        var details = new FakePattern(AEItemKey.of(Items.FURNACE),
                new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(infinite, Long.MAX_VALUE)),
                        new FakeInput(new GenericStack(MATERIAL, 8L))});
        BigInteger owed = MAX.multiply(BigInteger.valueOf(1000L));
        var job = newJob();
        job.tasks.put(details, progress(0L));
        job.batchObligations.incur(details, owed);
        var pool = new WideCraftingInventory(ignored -> {});
        BigInteger stock = owed.multiply(BigInteger.valueOf(8L));
        pool.insertExact(MATERIAL, stock.add(BigInteger.valueOf(17L)), Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                java.util.Set.of(infinite));
        ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(), () -> true);
        assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(details));
        assertEquals(BigInteger.valueOf(17L), pool.amount(MATERIAL));
        assertEquals(stock, job.accountTaken.getExact(MATERIAL));
        assertEquals(BigInteger.ZERO, pool.amount(infinite));
        assertEquals(BigInteger.ZERO, job.accountTaken.getExact(infinite));
    }

    @Test
    void shortWideExtractionRollsBackAllFiniteSlotsWithoutMintingInfiniteStock() {
        var second = AEItemKey.of(Items.GOLD_ORE);
        var infinite = AEItemKey.of(Items.COAL);
        var details = new FakePattern(AEItemKey.of(Items.FURNACE),
                new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(infinite, Long.MAX_VALUE)),
                        new FakeInput(new GenericStack(MATERIAL, 2L)),
                        new FakeInput(new GenericStack(second, 3L))});
        BigInteger owed = MAX.multiply(BigInteger.TWO);
        var job = newJob();
        job.tasks.put(details, progress(0L));
        job.batchObligations.incur(details, owed);
        var pool = new WideCraftingInventory(ignored -> {}) {
            @Override
            public BigInteger extractExact(AEKey key, BigInteger amount, Actionable mode) {
                return super.extractExact(key, key.equals(second) && mode == Actionable.MODULATE
                        ? amount.subtract(BigInteger.ONE) : amount, mode);
            }
        };
        BigInteger firstStock = owed.multiply(BigInteger.TWO);
        BigInteger secondStock = owed.multiply(BigInteger.valueOf(3L));
        pool.insertExact(MATERIAL, firstStock, Actionable.MODULATE);
        pool.insertExact(second, secondStock, Actionable.MODULATE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                java.util.Set.of(infinite));
        var probe = new ParallelBatchExtraction.Probe();
        ParallelCraftingCpuLogic.payObligationBatches(job, pool, null, reservation,
                new ParallelPoolLedger(), null, probe, () -> true);
        assertEquals(owed, job.batchObligations.pendingFirings(details));
        assertEquals(firstStock, pool.amount(MATERIAL));
        assertEquals(secondStock, pool.amount(second));
        assertEquals(BigInteger.ZERO, pool.amount(infinite));
        assertEquals(BigInteger.ZERO, job.accountTaken.getExact(MATERIAL));
        assertEquals(BigInteger.ZERO, job.accountTaken.getExact(second));
        assertEquals(1L, probe.takenMismatch);
    }

    private static final class WideHost implements git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost,
            git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchPlannedOutputs,
            git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCarrier {
        BigInteger accepted;
        boolean reportToNetwork;
        BigInteger maxAccepted;
        git.chexson.chexsonsaeutils.bigint.WideKeyCounter planned;
        git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt receipt;
        @Override public git.chexson.chexsonsaeutils.bigint.WideKeyCounter takePlannedNetworkOutputs() {
            var result = planned; planned = null; return result;
        }
        @Override public git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt lastAcceptedBatchReceipt() {
            return receipt;
        }
        @Override public boolean declaredBatchFiringsUnbounded() { return true; }
        @Override public boolean supportsDeclaredBatch(IPatternDetails pattern) { return true; }
        @Override public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] prototype,
                BigInteger requested) {
            accepted = maxAccepted == null ? requested : requested.min(maxAccepted);
            receipt = new git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt("test");
            receipt.noteBatchId(java.util.UUID.randomUUID());
            if (reportToNetwork) {
                planned = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
                for (var output : pattern.getOutputs()) {
                    planned.add(output.what(), accepted.multiply(BigInteger.valueOf(output.amount())));
                }
            }
            return accepted;
        }
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] holder) { return false; }
        @Override public boolean isBusy() { return false; }
    }

    private static ParallelExecutingCraftingJob newJob() {
        ICraftingPlan plan = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, true, false,
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
