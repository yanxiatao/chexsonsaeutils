package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 同一物品的组件区分 1450 级压缩，贯通编译、精确求解、计划转换与执行任务建单。 */
class DeepCompressionOrderTest {
    @Test
    void componentCompressionChainCanBePlannedAndOrderedWithoutJvmRecursion() {
        verify(1450, false);
    }

    @Test
    void manyInvalidClassMembersDoNotForceExactInputsIntoNativePlanning() {
        verify(96, false);
    }

    @Test
    void deepShortageRemainsExactAndCannotBecomeAnExecutableOrder() {
        verify(1450, true);
    }

    @Test
    void deepOrdersAndShortageDoNotDependOnLargeThreadStacks() throws Exception {
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                runnable -> Thread.ofPlatform().stackSize(256 * 1024).unstarted(runnable))) {
            executor.submit(() -> {
                verify(1450, false);
                verify(1450, true);
            }).get();
        }
    }

    @Test
    void level4000PlanningPreviewAndOrderDoNotUseTheRecursiveDepthCeiling() throws Exception {
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                runnable -> Thread.ofPlatform().stackSize(256 * 1024).unstarted(runnable))) {
            executor.submit(() -> {
                verify(4000, false);
                verify(4000, true);
            }).get();
        }
    }

    private void verify(int depth, boolean shortage) {
        var keys = new ArrayList<AEItemKey>();
        for (int index = 0; index <= depth; index++) {
            keys.add(FakePlanGrid.dyedKey(Items.PAPER, index));
        }
        var base = keys.getLast();
        var demand = BigInteger.valueOf(9).pow(depth);
        var stock = shortage ? demand.subtract(BigInteger.ONE) : demand;
        var builder = FakeGrid.builder().storage(Map.of(base, Long.MAX_VALUE));
        var patterns = new ArrayList<FakePattern>();
        for (int index = 0; index < depth; index++) {
            var pattern = FakePattern.of(keys.get(index), 1, new FakeInput(keys.get(index + 1), 9));
            patterns.add(pattern);
            builder.pattern(keys.get(index), pattern);
        }
        var grid = builder.build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), keys.getFirst(), null);
        assertFalse(graph.hasDeclined(), graph::declineReason);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(base, PlanAmount.of(stock))));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertEquals(shortage, result.simulation());
        assertEquals(stock, result.state().used().getExact(base));
        assertEquals(shortage ? BigInteger.ONE : BigInteger.ZERO, result.state().missing().getExact(base));
        assertNull(MaterialClosureGate.validate(result.state(), graph, 1, null));
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertNotNull(plan);
        assertTrue(plan.bytes() > 0);
        assertTrue(WideAmountPlanCarrier.resolveBytes(plan).exactValue().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        var counts = git.chexson.chexsonsaeutils.crafting.status.PlanSummaryAmounts.fromPlan(plan);
        var summary = new appeng.menu.me.crafting.CraftingPlanSummary(plan.bytes(), plan.simulation(), counts.entries());
        git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer.attachPatternTimes(summary, plan);
        git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.attach(summary, plan);
        git.chexson.chexsonsaeutils.crafting.status.PlanGraph.attach(summary, plan);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        try {
            summary.write(buffer);
            System.out.printf("压缩链预览 depth=%d shortage=%s bytes=%d%n", depth, shortage, buffer.readableBytes());
            var decoded = appeng.menu.me.crafting.CraftingPlanSummary.read(buffer);
            assertEquals(WideAmountPlanCarrier.resolveBytes(plan).exactValue(),
                    git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.resolve(decoded, ""));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
        if (shortage) {
            assertTrue(plan.simulation());
            return;
        }
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false), (ICraftingCPU) null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        assertEquals(depth, job.tasks.size());
        var firings = BigInteger.ONE;
        for (var pattern : patterns) {
            assertEquals(firings, job.tasks.get(pattern).exactRemaining().exactValue());
            firings = firings.multiply(BigInteger.valueOf(9));
        }
        assertTrue(result.state().journal() < depth * 30, "日志大小应随级数增长，不能随压缩数量分轮");
    }
}
