package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.*;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.*;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.*;
import java.util.*;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MultiPatternCrystalGrowthTest {
    private static final AEItemKey SEED = AEItemKey.of(Items.WHEAT_SEEDS);
    private static final AEItemKey PURE = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey DUST = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey BLOCK = AEItemKey.of(Items.DIAMOND_BLOCK);
    private static final AEItemKey ORDINARY = AEItemKey.of(Items.EMERALD);

    @Test void ordinaryCrystalCanBootstrapFromOneStoredDustBatch() {
        var grow = FakePattern.of(PURE, 1, new FakeInput(SEED, 1));
        var grind = FakePattern.of(DUST, 1, new FakeInput(PURE, 1));
        var recycle = FakePattern.of(SEED, 8, new FakeInput(DUST, 4));
        var pack = FakePattern.of(BLOCK, 1, new FakeInput(PURE, 9));
        var unpack = FakePattern.of(ORDINARY, 9, new FakeInput(BLOCK, 1));
        var grid = FakeGrid.builder().storage(Map.of(DUST, 4L))
                .pattern(PURE, grow).pattern(DUST, grind).pattern(SEED, recycle)
                .pattern(BLOCK, pack).pattern(ORDINARY, unpack).build();
        var stock = new KeyCounter(); stock.add(DUST, 4L);
        var graph = GraphCompiler.compile(grid.crafting, stock, ORDINARY, null);
        assertFalse(graph.hasDeclined(), graph::declineReason);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        for (long amount : new long[]{9L, 18L, 27L, 45L, 900L}) {
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, amount);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation(), "有启动粉的环必须给出实际可提交计划");
            assertTrue(result.state().missing().isEmpty(), () -> "missing=" + result.state().missing());
            assertEquals(4L, result.state().usedAmount(DUST));
            assertTrue(result.state().craftsWide().getAmount(recycle).signum() > 0);
            assertEquals(amount, result.finalAmount());
            assertNull(MaterialClosureGate.validate(result.state(), graph, amount, null));
        }
        var amount = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.TWO).add(java.math.BigInteger.valueOf(9)));
        var wide = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, amount, false, null);
        assertFalse(wide.hasDeclined(), wide::declineReason);
        assertFalse(wide.simulation(), () -> "dust=" + wide.state().missing().getAmount(DUST)
                + " seed=" + wide.state().missing().getAmount(SEED) + " pure=" + wide.state().missing().getAmount(PURE)
                + " block=" + wide.state().missing().getAmount(BLOCK) + " final=" + wide.state().missing().getAmount(ORDINARY));
        assertTrue(wide.state().missing().isEmpty());
        assertEquals(amount, wide.exactFinalAmount());
        assertEquals(4L, wide.state().usedAmount(DUST));
        assertNull(MaterialClosureGate.validate(wide.state(), graph, amount, null));
    }

    @Test void startupIdentityIsSharedAcrossEntriesButUndoRestoresUnstartedState() {
        var first = new git.chexson.chexsonsaeutils.crafting.recursive.RingId(SEED, 11);
        var otherEntry = new git.chexson.chexsonsaeutils.crafting.recursive.RingId(PURE, 11);
        var distinct = new git.chexson.chexsonsaeutils.crafting.recursive.RingId(SEED, 12);
        var state = new GraphPlanningState(Map.of(), Map.of(), new PlanJournal.Arena(16));
        int checkpoint = state.journal();
        state.markRingStarted(first);
        assertTrue(state.hasRingStarted(otherEntry));
        assertFalse(state.hasRingStarted(distinct));
        int started = state.journal();
        state.markRingStarted(otherEntry);
        assertEquals(started, state.journal(), "相同起步池不重复登记");
        for (int i = 0; i < 50; i++) state.addBytes(1);
        state.undoTo(checkpoint);
        assertFalse(state.hasRingStarted(first));
        assertFalse(state.hasRingStarted(otherEntry));
        state.markRingStarted(otherEntry);
        assertTrue(state.hasRingStarted(first));
        state.releaseRollback();
    }

    @Test void wideCycleRoundingBalancesInternalPowderAndPreservesExternalMaterialFees() {
        var raw = AEItemKey.of(Items.IRON_INGOT);
        var grow = FakePattern.of(PURE, 1, new FakeInput(SEED, 1));
        var grind = FakePattern.of(DUST, 1, new FakeInput(PURE, 1));
        var recycle = FakePattern.of(SEED, 8, new FakeInput(DUST, 4), new FakeInput(raw, 2));
        var macro = RingContraction.contractPatterns(List.of(grow, grind, recycle)).getFirst();
        var planned = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.TEN.pow(100).add(java.math.BigInteger.ONE));
        var rounded = new LinkedHashMap<appeng.api.crafting.IPatternDetails, git.chexson.chexsonsaeutils.bigint.PlanAmount>();
        macro.executionRatio().forEach((pattern, ratio) -> rounded.put(pattern,
                git.chexson.chexsonsaeutils.bigint.WideMath.wideCeilMulDiv(ratio, planned, macro.entryOutputPerCycle())));
        assertTrue(macro.netFlowOf(rounded).get(DUST).signum() < 0);
        var balanced = macro.internallyBalancedFirings(rounded, planned);
        var net = macro.netFlowOf(balanced);
        assertFalse(net.containsKey(DUST));
        assertTrue(net.get(SEED).compareTo(planned.exactValue()) >= 0);
        assertEquals(balanced.get(recycle).exactValue().multiply(java.math.BigInteger.TWO).negate(), net.get(raw));
    }
}
