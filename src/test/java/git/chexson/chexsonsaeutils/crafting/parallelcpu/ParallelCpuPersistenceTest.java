package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.hostcpu.ParallelCpuHost;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实作业 NBT 往返：保留执行份数、材料硬配额和累计实物交付，覆盖尚未绑定世界的加载阶段。 */
final class ParallelCpuPersistenceTest {
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.CHEST);
    private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey ZERO_QUOTA = AEItemKey.of(Items.GOLD_INGOT);
    private static final BigInteger WIDE = BigInteger.TEN.pow(25).add(BigInteger.valueOf(123));
    private static final Pattern FIRST = new Pattern(AEItemKey.of(Items.STRUCTURE_VOID));
    private static final Pattern SECOND = new Pattern(AEItemKey.of(Items.JIGSAW));

    static {
        PatternDetailsHelper.registerDecoder(new IPatternDetailsDecoder() {
            @Override public boolean isEncodedPattern(ItemStack stack) {
                return stack.is(Items.STRUCTURE_VOID) || stack.is(Items.JIGSAW);
            }
            @Override public IPatternDetails decodePattern(AEItemKey key, Level level) {
                if (FIRST.definition().equals(key) || SECOND.definition().equals(key)) return new Pattern(key);
                return null;
            }
        });
    }

    @Test
    void activeJobRestoresWideProgressQuotasDeliveriesAndContinuesAccounting() {
        var host = new HostImpl();
        var cluster = cluster(host);
        var lane = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L);
        var job = job(lane);
        job.tasks.get(FIRST).addFirings(PlanAmount.of(WIDE));
        job.tasks.get(FIRST).consumeFirings(17L);
        var expectedFirings = job.tasks.get(FIRST).exactRemaining();
        var assigned = new WideKeyCounter();
        assigned.setExact(MATERIAL, WIDE);
        assigned.set(ZERO_QUOTA, 0L);
        var envelope = new WideKeyCounter();
        envelope.set(MATERIAL, 7L);
        job.materialAllocation.adopt(Map.of(FIRST, assigned), Map.of(FIRST, envelope));
        job.materialAllocation.recordConsumption(FIRST, MATERIAL, BigInteger.valueOf(29));
        job.batchObligations.incur(FIRST, BigInteger.valueOf(3));
        job.addFinalOutputDelivered(PRODUCT, 40L);
        job.remainingAmount = 60L;
        job.waitingFor.insert(PRODUCT, 60L, Actionable.MODULATE);
        job.initialItemsRemaining.insertExact(MATERIAL, WIDE, Actionable.MODULATE);
        job.initialItemsCursor = 1;
        job.noteUnboundedSupply(ZERO_QUOTA);
        job.suspended = true;

        var restored = new ParallelExecutingCraftingJob(job.writeToNBT(REGISTRIES), REGISTRIES, ignored -> {}, lane);
        assertEquals(List.of(FIRST.definition(), SECOND.definition()),
                restored.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList());
        var pattern = restored.tasks.keySet().iterator().next();
        assertNotSame(FIRST, pattern, "材料账必须绑定本次解码的身份");
        assertEquals(expectedFirings, restored.tasks.get(pattern).exactRemaining());
        assertEquals(WIDE.subtract(BigInteger.valueOf(29)),
                restored.materialAllocation.remainingFamilyQuota(pattern, MATERIAL).exactValue());
        assertTrue(restored.materialAllocation.hasQuotaBasis(pattern, ZERO_QUOTA));
        assertEquals(PlanAmount.ZERO, restored.materialAllocation.remainingFamilyQuota(pattern, ZERO_QUOTA));
        assertEquals(PlanAmount.of(7), restored.materialAllocation.envelopeAmount(pattern, MATERIAL));
        assertEquals(MaterialAllocation.Source.PLAN, restored.materialAllocation.source());
        assertEquals(BigInteger.valueOf(3), restored.batchObligations.pendingFirings(pattern));
        assertEquals(PlanAmount.of(40), restored.finalOutputDeliveredAmount());
        assertEquals(PlanAmount.of(100), restored.finalOutputOrderAmount());
        assertEquals(60L, restored.remainingAmount);
        assertEquals(PlanAmount.of(60), restored.waitingForExact(PRODUCT));
        assertEquals(WIDE, WideCraftingInventory.amount(restored.initialItemsRemaining, MATERIAL));
        assertEquals(1, restored.initialItemsCursor);
        assertTrue(restored.isUnboundedSupply(ZERO_QUOTA));
        assertTrue(restored.suspended);
        restored.tasks.get(pattern).consumeFirings(11L);
        restored.materialAllocation.recordConsumption(pattern, MATERIAL, 11L);
        restored.addFinalOutputDelivered(PRODUCT, 60L);
        assertEquals(expectedFirings.subtract(PlanAmount.of(11)), restored.tasks.get(pattern).exactRemaining());
        assertEquals(WIDE.subtract(BigInteger.valueOf(40)),
                restored.materialAllocation.remainingFamilyQuota(pattern, MATERIAL).exactValue());
        assertEquals(PlanAmount.of(100), restored.finalOutputDeliveredAmount());
    }

    @Test
    void originalBeyondLongProductionIsNotRecalculatedFromRemainingFirings() {
        var lane = new ParallelCraftingLaneState(cluster(new HostImpl()), UUID.randomUUID(), 0L);
        var plan = new CraftingPlan(new GenericStack(PRODUCT, Long.MAX_VALUE), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(FIRST, Long.MAX_VALUE, SECOND, 1L));
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(lane.laneId(), true, false), lane.linkCpu());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        job.tasks.get(FIRST).consumeFirings(42L);
        job.addFinalOutputDelivered(PRODUCT, 42L);
        var restored = new ParallelExecutingCraftingJob(job.writeToNBT(REGISTRIES), REGISTRIES, ignored -> {}, lane);
        assertEquals(PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)), restored.finalOutputOrderAmount());
        assertEquals(PlanAmount.of(42), restored.finalOutputDeliveredAmount());
    }

    @Test
    void loadBeforeWorldBindingPreservesRawTasksEvenWhenSavedAgain() {
        var cluster = cluster(new HostImpl());
        var lane = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L);
        var lanes = new ListTag();
        var laneTag = new CompoundTag();
        laneTag.putUUID("laneId", lane.laneId());
        var logic = new CompoundTag();
        logic.put("job", job(lane).writeToNBT(REGISTRIES));
        laneTag.put("logic", logic);
        lanes.add(laneTag);
        var saved = new CompoundTag();
        saved.put("parallelCpuLanes", lanes);
        cluster.readFromNBT(saved, REGISTRIES);
        saved.remove("parallelCpuLanes");
        cluster.restorePendingJobs();
        assertEquals(0, cluster.activeLaneCount(), "没有世界时不能提前解码任务");
        var rewritten = new CompoundTag();
        cluster.writeToNBT(rewritten, REGISTRIES);
        assertEquals(lanes, rewritten.getList("parallelCpuLanes", Tag.TAG_COMPOUND));
    }

    @Test
    void undecodableTaskIsPausedAndPreservedInsteadOfSilentlyDropped() {
        var lane = new ParallelCraftingLaneState(cluster(new HostImpl()), UUID.randomUUID(), 0L);
        var saved = job(lane).writeToNBT(REGISTRIES);
        var tasks = saved.getList("tasks", Tag.TAG_COMPOUND);
        var invalid = AEItemKey.of(Items.DIRT).toTag(REGISTRIES);
        ParallelExecutingCraftingJob.writeTaskProgress(invalid, new ParallelExecutingCraftingJob.TaskProgress());
        tasks.add(invalid);
        var restored = new ParallelExecutingCraftingJob(saved, REGISTRIES, ignored -> {}, lane);
        assertTrue(restored.suspended);
        assertEquals(2, restored.tasks.size());
        var rewritten = restored.writeToNBT(REGISTRIES).getList("tasks", Tag.TAG_COMPOUND);
        assertEquals(3, rewritten.size());
        assertEquals(invalid, rewritten.getCompound(2));
    }

    @Test
    void submissionPauseAndCancellationMarkHostForSaving() {
        var host = new HostImpl();
        var grid = new ParallelRecursiveFinalOutputDeliveryTest.TestGrid(() -> net.minecraft.network.chat.Component.empty());
        host.grid = grid;
        var cluster = cluster(host);
        var result = cluster.submitJob(grid, plan(), null, IActionSource.empty(), 0L);
        assertTrue(result.successful());
        assertTrue(host.saves > 0);
        var active = new java.util.ArrayList<ParallelCraftingLane>();
        cluster.appendActiveLanes(active);
        var lane = active.getFirst();
        int before = host.saves;
        ((ParallelCraftingLaneState) lane).logic().setJobSuspended(true);
        assertTrue(host.saves > before);
        before = host.saves;
        cluster.cancelLane((ParallelCraftingLaneState) lane);
        assertTrue(host.saves > before);
        assertEquals(0, cluster.activeLaneCount());
    }

    private static ParallelCraftingCpuCluster cluster(HostImpl host) {
        return new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
    }
    private static CraftingPlan plan() {
        var patterns = new LinkedHashMap<IPatternDetails, Long>();
        patterns.put(FIRST, 50L);
        patterns.put(SECOND, 50L);
        return new CraftingPlan(new GenericStack(PRODUCT, 100L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), patterns);
    }
    private static ParallelExecutingCraftingJob job(ParallelCraftingLaneState lane) {
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(lane.laneId(), true, false), lane.linkCpu());
        return new ParallelExecutingCraftingJob(plan(), ignored -> {}, link, null);
    }
    private record Pattern(AEItemKey definition) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return definition; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 1L)); }
    }
    private static final class HostImpl implements ParallelCpuHost {
        private int saves;
        private IGrid grid;
        @Override public void saveChanges() { saves++; }
        @Override public IGrid getGrid() { return grid; }
        @Override public Level getLevel() { return null; }
        @Override public IGridNode getActionableNode() { return null; }
        @Override public CpuSelectionMode getSelectionMode() { return CpuSelectionMode.ANY; }
        @Override public boolean isParallelCpuProviderActive() { return true; }
        @Override public boolean canProcessParallelCpuJobs() { return true; }
    }
}
