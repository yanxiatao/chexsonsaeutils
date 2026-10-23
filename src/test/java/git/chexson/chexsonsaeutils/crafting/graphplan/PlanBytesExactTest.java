package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 实际规划出口必须携带精确字节，即使物品、发数和根订单都没有溢出。 */
final class PlanBytesExactTest {
    @Test void returnedSlotByteCompensationPreservesWideProduct() {
        assertContainerBytes(true);
    }

    @Test void finiteUseRemainderFeesPreserveWideProduct() {
        assertContainerBytes(false);
    }

    private static void assertContainerBytes(boolean returned) {
        var target = AEItemKey.of(Items.IRON_INGOT);
        var input = AEItemKey.of(Items.BUCKET);
        var remainder = returned ? input : AEItemKey.of(Items.PAPER);
        long times = Long.MAX_VALUE;
        var pattern = git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern.of(
                target, 1, new git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput(input, 2, remainder));
        var grid = git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder()
                .storage(Map.of(input, returned ? 2L : Long.MAX_VALUE)).pattern(target, pattern).build();
        var graph = git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler.compile(
                grid.crafting, grid.storage.getCachedInventory(), target, null);
        assertFalse(graph.hasDeclined(), graph::declineReason);
        graph.attachSupplyTruth(git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.of(
                Map.of(input, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(
                        BigInteger.valueOf(times).multiply(BigInteger.TWO)))));
        var result = git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner.solve(
                graph, appeng.api.networking.crafting.CalculationStrategy.REPORT_MISSING_ITEMS, times);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        // 根物料费 times、输入和返还各 2*times、节点费 times、两个站点各 8 字节。
        var expected = BigInteger.valueOf(times).multiply(BigInteger.valueOf(6)).add(BigInteger.valueOf(16));
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertEquals(expected, WideAmountPlanCarrier.resolveBytes(plan).exactValue());
        assertEquals(BigInteger.valueOf(times), result.state().craftsWide().getAmount(pattern).exactValue());
    }

    @Test void bytesAloneCanRequireAWidePlan() {
        var key = AEItemKey.of(Items.PAPER);
        var state = new GraphPlanningState(Map.of(), Map.of());
        state.byteAccount().add(key, Long.MAX_VALUE, 100);
        var graph = new CraftGraph(key, Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Set.of(), Map.of(), false, null, 0, 0);
        var plan = Ae2PlanConverter.toPlan(SolveResult.success(state, 1, false), graph);
        var expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(800))
                .divide(BigInteger.valueOf(key.getAmountPerByte()));
        assertTrue(expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        assertEquals(Long.MAX_VALUE, plan.bytes());
        assertEquals(expected, WideAmountPlanCarrier.resolveBytes(plan).exactValue());
        assertEquals(1, plan.finalOutput().amount());
    }
}
