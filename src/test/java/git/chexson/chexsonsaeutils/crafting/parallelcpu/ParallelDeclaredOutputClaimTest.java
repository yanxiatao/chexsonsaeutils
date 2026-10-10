package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputClaim;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCallback;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实际宽域交付的数量守恒、网络边界与多个任务分配；不使用计划回报代替实物到账。 */
final class ParallelDeclaredOutputClaimTest {
    private static final AEKey MATERIAL = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey SIDE = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEKey FINAL = AEItemKey.of(Items.CHEST);
    private static final BigInteger HUGE = BigInteger.TEN.pow(40).add(BigInteger.valueOf(17));
    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test
    void hugeActualOutputMovesIntoPoolWithoutLongSegments() {
        var job = job();
        var pool = new WideCraftingInventory(ignored -> {});
        var waiting = (WideCraftingInventory) job.waitingFor;
        waiting.insertExact(MATERIAL, HUGE, Actionable.MODULATE);
        var notifications = new java.util.concurrent.atomic.AtomicInteger();

        var accepted = ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, MATERIAL, HUGE, ignored -> notifications.incrementAndGet());

        assertEquals(HUGE, accepted);
        assertEquals(HUGE, pool.amount(MATERIAL));
        assertEquals(BigInteger.ZERO, waiting.amount(MATERIAL));
        assertEquals(HUGE, job.accountReturned.getExact(MATERIAL));
        assertEquals(1, notifications.get(), "数量再大也只做一次到账操作");
    }

    @Test
    void partialDeliveryAndRetryNeverExceedTheExactDebt() {
        var job = job();
        var pool = new WideCraftingInventory(ignored -> {});
        var waiting = (WideCraftingInventory) job.waitingFor;
        waiting.insertExact(MATERIAL, HUGE, Actionable.MODULATE);
        BigInteger first = HUGE.divide(BigInteger.valueOf(3));
        assertEquals(first, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, MATERIAL, first, ignored -> {}));
        assertEquals(HUGE.subtract(first), ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, MATERIAL, HUGE, ignored -> {}));
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, MATERIAL, HUGE, ignored -> {}));
        assertEquals(HUGE, pool.amount(MATERIAL));
        assertEquals(HUGE, job.accountReturned.getExact(MATERIAL));
    }

    @Test
    void onlyOwedByproductsEnterPoolAndFinalRequesterDeliveryStaysSeparate() {
        var job = job();
        var pool = new WideCraftingInventory(ignored -> {});
        var waiting = (WideCraftingInventory) job.waitingFor;
        waiting.insertExact(SIDE, HUGE, Actionable.MODULATE);
        waiting.insert(FINAL, 100L, Actionable.MODULATE);
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, MATERIAL, HUGE, ignored -> {}));
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, FINAL, HUGE, ignored -> {}));
        assertEquals(HUGE, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, SIDE, HUGE, ignored -> {}));
        assertEquals(BigInteger.valueOf(100L), waiting.amount(FINAL));
        assertEquals(BigInteger.ZERO, pool.amount(FINAL));
    }

    @Test
    void ringInternalOutputCanReplenishPoolEvenWhenItMatchesFinalKey() {
        var base = new CraftingPlan(new GenericStack(MATERIAL, 100L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var internal = new KeyCounter();
        internal.add(MATERIAL, 1L);
        var recursive = new git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan(
                base, new KeyCounter(), internal, 100L, Map.of(), Map.of(), null, null);
        var job = new ParallelExecutingCraftingJob(recursive, ignored -> {}, null, null);
        ((WideCraftingInventory) job.waitingFor).insertExact(MATERIAL, HUGE, Actionable.MODULATE);
        var pool = new WideCraftingInventory(ignored -> {});
        assertEquals(HUGE, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(
                job, pool, MATERIAL, HUGE, ignored -> {}));
        assertEquals(HUGE, pool.amount(MATERIAL));
        assertEquals(BigInteger.ZERO, job.finalOutputDelivered.getExact(MATERIAL),
                "环内补料仍是库存，不能冒充交给请求者的终点产物");
    }

    @Test
    void mergedMachineOutputFeedsMultipleLanesAndLeavesOnlyRealExcess() {
        var cluster = ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster();
        var grid = new ParallelCraftingCpuGrid(cluster.grid());
        grid.setClusters(java.util.List.of(cluster));
        var first = lane(cluster, HUGE);
        var second = lane(cluster, HUGE.multiply(BigInteger.TWO));
        grid.refreshLane(first);
        grid.refreshLane(second);
        var receipt = new ClaimReceipt();
        first.logic().bindDeclaredOutputReceiver(receipt);

        BigInteger available = HUGE.multiply(BigInteger.valueOf(4));
        assertEquals(HUGE.multiply(BigInteger.valueOf(3)),
                receipt.receiver.receive(cluster.grid(), MATERIAL, available));
        assertEquals(HUGE, pool(first).amount(MATERIAL));
        assertEquals(HUGE.multiply(BigInteger.TWO), pool(second).amount(MATERIAL));
        assertEquals(BigInteger.ZERO, receipt.receiver.receive(cluster.grid(), MATERIAL, available));
        assertEquals(0L, grid.getRequestedAmount(MATERIAL));
        assertTrue(first.isLaneActive(), "实物到账不能把未完成的订单提前结束");
    }

    @Test
    void detachedNetworkAndDifferentMachineNetworkCannotClaim() {
        var cluster = ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster();
        var grid = new ParallelCraftingCpuGrid(cluster.grid());
        grid.setClusters(java.util.List.of(cluster));
        var lane = lane(cluster, HUGE);
        grid.refreshLane(lane);
        var receipt = new ClaimReceipt();
        lane.logic().bindDeclaredOutputReceiver(receipt);
        IGrid foreign = ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster().grid();
        assertEquals(BigInteger.ZERO, receipt.receiver.receive(foreign, MATERIAL, HUGE));
        cluster.detachGridContext();
        assertEquals(BigInteger.ZERO, receipt.receiver.receive(cluster.grid(), MATERIAL, HUGE));
        assertEquals(BigInteger.ZERO, pool(lane).amount(MATERIAL));
    }

    @Test
    void oldLongDeliveryAndNewWideDeliveryShareOneWaitingLedger() {
        var cluster = ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster();
        var grid = new ParallelCraftingCpuGrid(cluster.grid());
        grid.setClusters(java.util.List.of(cluster));
        var lane = lane(cluster, HUGE);
        grid.refreshLane(lane);
        long narrow = lane.logic().insert(MATERIAL, Long.MAX_VALUE, Actionable.MODULATE);
        grid.refreshLane(lane);
        assertEquals(Long.MAX_VALUE, narrow);
        assertEquals(HUGE.subtract(BigInteger.valueOf(narrow)), grid.claimDeclaredOutputs(MATERIAL, HUGE));
        assertEquals(HUGE, pool(lane).amount(MATERIAL));
        assertEquals(BigInteger.ZERO, grid.claimDeclaredOutputs(MATERIAL, HUGE));
    }

    private static ParallelExecutingCraftingJob job() {
        var plan = new CraftingPlan(new GenericStack(FINAL, 100L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                (appeng.api.networking.crafting.ICraftingCPU) null);
        return new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
    }

    private static ParallelCraftingLaneState lane(ParallelCraftingCpuCluster cluster, BigInteger debt) {
        var job = job();
        ((WideCraftingInventory) job.waitingFor).insertExact(MATERIAL, debt, Actionable.MODULATE);
        var data = new CompoundTag();
        data.put("job", job.writeToNBT(REGISTRIES));
        var lane = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L);
        lane.logic().readFromNBT(data, REGISTRIES);
        return lane;
    }

    private static WideCraftingInventory pool(ParallelCraftingLaneState lane) {
        var data = new CompoundTag();
        lane.logic().writeToNBT(data, REGISTRIES);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.readFromNBT(data.getList("inventory", Tag.TAG_COMPOUND), REGISTRIES);
        return pool;
    }

    private static final class ClaimReceipt implements DeclaredBatchOutputClaim {
        private final UUID id = UUID.randomUUID();
        private DeclaredBatchOutputReceiver receiver;
        @Override public UUID batchId() { return id; }
        @Override public boolean plannedOutputsClamped() { return false; }
        @Override public void bindTerminal(DeclaredBatchTerminalCallback callback, UUID attachedBatchId) {}
        @Override public void bindOutputReceiver(DeclaredBatchOutputReceiver receiver) { this.receiver = receiver; }
    }
}
