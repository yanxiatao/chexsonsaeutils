package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.*;
import appeng.crafting.CraftingPlan;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ParallelWideRootDeliveryTest {
    private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final BigInteger ORDER = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.TEN);
    private ParallelExecutingCraftingJob job() {
        var base = new CraftingPlan(new GenericStack(PRODUCT, Long.MAX_VALUE), 0, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var plan = new WideAmountCraftingPlan(base, WideLedgerSnapshot.empty(), WideCraftSnapshot.empty(), Set.of(),
                PlanAmount.of(ORDER));
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false), (ICraftingCPU) null);
        return new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
    }
    @Test void representativeLongDeliveryDoesNotCompleteExactOrder() {
        var job = job();
        job.recordFinalOutputAccepted(Long.MAX_VALUE);
        assertEquals(ORDER, job.finalOutputOrderAmount().exactValue());
        assertEquals(ORDER.subtract(BigInteger.valueOf(Long.MAX_VALUE)), job.exactRemainingAmount.exactValue());
        assertEquals(Long.MAX_VALUE, job.remainingAmount);
        job.recordFinalOutputAccepted(Long.MAX_VALUE);
        assertEquals(10, job.remainingAmount);
        job.recordFinalOutputAccepted(0);
        assertEquals(10, job.remainingAmount);
        job.recordFinalOutputAccepted(10);
        assertEquals(PlanAmount.ZERO, job.exactRemainingAmount);
        assertEquals(0, job.remainingAmount);
    }

    @Test void standaloneWideOrderReallyEntersNetworkAndFinishesWithoutRequester() {
        var stored = new java.util.concurrent.atomic.AtomicReference<>(BigInteger.ZERO);
        var limit = new java.util.concurrent.atomic.AtomicLong(0);
        var ref = new java.util.concurrent.atomic.AtomicReference<ParallelCraftingLaneState>();
        var physical = new appeng.api.storage.MEStorage() {
            @Override public net.minecraft.network.chat.Component getDescription() {
                return net.minecraft.network.chat.Component.literal("测试网络");
            }
            @Override public long insert(AEKey key, long amount, appeng.api.config.Actionable mode,
                    appeng.api.networking.security.IActionSource source) {
                // 模拟网络先调用合成拦截器；同一 CPU 必须避开自己的再次拦截。
                assertEquals(0, ref.get().insertIntoWaiting(key, amount, mode));
                long accepted = Math.min(amount, limit.get());
                if (mode == appeng.api.config.Actionable.MODULATE)
                    stored.updateAndGet(value -> value.add(BigInteger.valueOf(accepted)));
                return accepted;
            }
        };
        var network = new appeng.me.storage.NetworkStorage();
        network.mount(0, physical);
        network.mount(Integer.MAX_VALUE, new appeng.api.storage.MEStorage() {
            @Override public net.minecraft.network.chat.Component getDescription() { return physical.getDescription(); }
            @Override public long insert(AEKey key, long amount, appeng.api.config.Actionable mode,
                    appeng.api.networking.security.IActionSource source) {
                var result = ref.get().insertIntoWaitingAndGetResult(key, amount, mode);
                assertEquals(result.physicalInserted(), result.accounted());
                return result.physicalInserted();
            }
        });
        var lane = new ParallelCraftingLaneState(ParallelRecursiveFinalOutputDeliveryTest.offlineCluster(network), UUID.randomUUID(), 0);
        ref.set(lane);
        var job = job();
        ((WideCraftingInventory) job.waitingFor).insertExact(PRODUCT, ORDER, appeng.api.config.Actionable.MODULATE);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.put("job", job.writeToNBT(registries));
        lane.logic().readFromNBT(tag, registries);
        assertEquals(Long.MAX_VALUE, lane.insertIntoWaiting(PRODUCT, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE));
        assertEquals(BigInteger.ZERO, stored.get());
        for (int i = 0; i < 2; i++) {
            assertEquals(Long.MAX_VALUE, network.insert(PRODUCT, Long.MAX_VALUE, appeng.api.config.Actionable.MODULATE,
                    appeng.api.networking.security.IActionSource.empty()));
            assertTrue(lane.logic().hasJob());
        }
        assertEquals(10, network.insert(PRODUCT, 10, appeng.api.config.Actionable.MODULATE,
                appeng.api.networking.security.IActionSource.empty()));
        lane.logic().drainStandaloneFinalOutput();
        assertEquals(BigInteger.ZERO, stored.get());
        assertTrue(lane.logic().hasJob());
        limit.set(4);
        lane.logic().drainStandaloneFinalOutput();
        assertTrue(stored.get().signum() > 0);
        assertTrue(lane.logic().hasJob());
        limit.set(Long.MAX_VALUE);
        lane.logic().drainStandaloneFinalOutput();
        assertFalse(lane.logic().hasJob());
        assertEquals(ORDER, stored.get());
    }
    @Test void persistsWideRemainingAndActualDeliveredQuantityAcrossReload() {
        var job = job();
        job.recordFinalOutputAccepted(Long.MAX_VALUE);
        job.addFinalOutputDelivered(PRODUCT, Long.MAX_VALUE);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved = job.writeToNBT(registries);
        var lane = new ParallelCraftingLaneState(ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster(), UUID.randomUUID(), 0);
        var restored = new ParallelExecutingCraftingJob(saved, registries, ignored -> {}, lane);
        assertEquals(ORDER, restored.finalOutputOrderAmount().exactValue());
        assertEquals(job.exactRemainingAmount, restored.exactRemainingAmount);
        assertEquals(job.finalOutputDeliveredAmount(), restored.finalOutputDeliveredAmount());
        restored.recordFinalOutputAccepted(Long.MAX_VALUE);
        assertEquals(10, restored.remainingAmount);
        saved.putByteArray("exactRemainingAmount", BigInteger.valueOf(-1).toByteArray());
        assertThrows(IllegalArgumentException.class, () -> new ParallelExecutingCraftingJob(saved, registries, ignored -> {}, lane));
    }

    @Test void networkDeclaredOrderRequiresRealTerminalDeliveryBeforeCompletion() {
        var job = job();
        job.addExpectedToNetwork(PRODUCT, ORDER);
        assertFalse(ParallelCraftingCpuLogic.isDeclaredNetworkJobComplete(job));
        job.creditDeclaredBatchDelivered(Map.of(PRODUCT, ORDER.subtract(BigInteger.ONE)), false);
        assertEquals(1, job.remainingAmount);
        assertFalse(ParallelCraftingCpuLogic.isDeclaredNetworkJobComplete(job));
        job.creditDeclaredBatchDelivered(Map.of(PRODUCT, BigInteger.ONE), false);
        assertEquals(0, job.remainingAmount);
        assertTrue(ParallelCraftingCpuLogic.isDeclaredNetworkJobComplete(job));
    }

    @Test void cpuSnapshotRetainsWideStoredAndActiveWithoutUsingLongProjection() {
        var job = job();
        var pattern = new appeng.api.crafting.IPatternDetails() {
            @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.STICK); }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 2)); }
        };
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(ORDER)); job.tasks.put(pattern, progress);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(PRODUCT, ORDER, appeng.api.config.Actionable.MODULATE);
        ((WideCraftingInventory) job.waitingFor).insertExact(PRODUCT, ORDER.add(BigInteger.ONE),
                appeng.api.config.Actionable.MODULATE);
        var snapshot = ParallelCraftingCpuLogic.exactMenuAmounts(pool, job);
        pool.extractExact(PRODUCT, BigInteger.ONE, appeng.api.config.Actionable.MODULATE);
        assertEquals(ORDER, snapshot.stored().get(PRODUCT));
        assertEquals(ORDER.add(BigInteger.ONE), snapshot.active().get(PRODUCT));
        assertEquals(ORDER.multiply(BigInteger.TWO), snapshot.pending().get(PRODUCT));
        assertEquals(ORDER.subtract(BigInteger.ONE), ParallelCraftingCpuLogic.exactMenuAmounts(pool, job).stored().get(PRODUCT));
    }
}
