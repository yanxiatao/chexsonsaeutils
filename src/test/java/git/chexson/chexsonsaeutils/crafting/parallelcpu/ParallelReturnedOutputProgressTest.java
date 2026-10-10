package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.*;
import appeng.crafting.execution.CraftingCpuHelper;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 从真实计划工作量、实际回交到 AE2 监控比例，覆盖模拟、分批和流体单位。 */
final class ParallelReturnedOutputProgressTest {
    private static final AEKey ROOT = AEItemKey.of(Items.CHEST);
    private static final AEKey ITEM = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey FLUID = AEFluidKey.of(Fluids.WATER);

    @Test void actualLongReturnUpdatesBothTrackerAndMenuStatus() {
        var emitted = new KeyCounter();
        emitted.add(ITEM, 4);
        var plan = new CraftingPlan(new GenericStack(ROOT, 100), 0, false, false,
                new KeyCounter(), emitted, new KeyCounter(), Map.of());
        var job = job(plan);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved = new CompoundTag();
        saved.put("job", job.writeToNBT(registries));
        var lane = new ParallelCraftingLaneState(ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster(), UUID.randomUUID(), 0);
        lane.logic().readFromNBT(saved, registries);
        assertEquals(1, lane.insertIntoWaiting(ITEM, 1, Actionable.SIMULATE));
        assertEquals(0.0f, lane.logic().getElapsedTimeTracker().getProgress());
        assertEquals(1, lane.insertIntoWaiting(ITEM, 1, Actionable.MODULATE));
        assertEquals(0.25f, lane.logic().getElapsedTimeTracker().getProgress());
        var status = lane.getJobStatus();
        assertEquals(0.25, status.progress() / (double) status.totalItems(), 0.000001);
        assertEquals(3, lane.insertIntoWaiting(ITEM, 10, Actionable.MODULATE));
        assertEquals(1.0f, lane.logic().getElapsedTimeTracker().getProgress());
        assertEquals(0, lane.insertIntoWaiting(ITEM, 10, Actionable.MODULATE));
        assertEquals(1.0f, lane.logic().getElapsedTimeTracker().getProgress());
    }

    @Test void patternFluidReturnUsesTheSameRawUnitsAsItsPlannedWork() {
        long unit = FLUID.getAmountPerUnit();
        var pattern = new Pattern(List.of(new GenericStack(ITEM, 4), new GenericStack(FLUID, 4 * unit)));
        var plan = new CraftingPlan(new GenericStack(ROOT, 100), 0, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 1L));
        var job = job(plan);
        var waiting = (WideCraftingInventory) job.waitingFor;
        waiting.insert(ITEM, 4, Actionable.MODULATE);
        waiting.insert(FLUID, 4 * unit, Actionable.MODULATE);
        var pool = new WideCraftingInventory(ignored -> {});
        assertEquals(BigInteger.ONE, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, ITEM, BigInteger.ONE, ignored -> {}));
        assertEquals(0.125f, job.timeTracker.getProgress(), 0.000001f);
        assertEquals(BigInteger.valueOf(unit), ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, BigInteger.valueOf(unit), ignored -> {}));
        assertEquals(0.25f, job.timeTracker.getProgress(), 0.000001f);
        ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, ITEM, BigInteger.valueOf(3), ignored -> {});
        ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, BigInteger.valueOf(3 * unit), ignored -> {});
        assertEquals(1.0f, job.timeTracker.getProgress());
    }

    @Test void wideFluidReturnAdvancesExactlyAndSurvivesSaveReload() {
        long unit = FLUID.getAmountPerUnit();
        var pattern = new Pattern(List.of(new GenericStack(FLUID, 4 * unit)));
        var firings = BigInteger.TEN.pow(40);
        var total = firings.multiply(BigInteger.valueOf(4 * unit));
        var crafts = new git.chexson.chexsonsaeutils.bigint.WideCraftCounter();
        crafts.add(pattern, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings));
        var base = new CraftingPlan(new GenericStack(ROOT, 100), 0, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, Long.MAX_VALUE));
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(base,
                git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot.empty(),
                git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot.capture(crafts), Set.of());
        var job = job(plan);
        ((WideCraftingInventory) job.waitingFor).insertExact(FLUID, total, Actionable.MODULATE);
        var pool = new WideCraftingInventory(ignored -> {});
        var first = total.divide(BigInteger.valueOf(4));
        assertEquals(first, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, first, ignored -> {}));
        assertEquals(0.25f, job.timeTracker.getProgress());
        var restored = new ParallelElapsedTimeTracker(job.timeTracker.writeToNBT());
        assertEquals(0.25f, restored.getProgress());
        assertEquals(total.subtract(first), ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, total, ignored -> {}));
        assertEquals(1.0f, job.timeTracker.getProgress());
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, total, ignored -> {}));
        assertEquals(1.0f, job.timeTracker.getProgress());
    }

    private static ParallelExecutingCraftingJob job(appeng.api.networking.crafting.ICraftingPlan plan) {
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                (appeng.api.networking.crafting.ICraftingCPU) null);
        return new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
    }

    private record Pattern(List<GenericStack> outputs) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.PAPER); }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() { return outputs; }
    }
}
