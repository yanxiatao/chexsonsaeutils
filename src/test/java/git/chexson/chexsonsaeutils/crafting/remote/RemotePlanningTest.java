package git.chexson.chexsonsaeutils.crafting.remote;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.*;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.*;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RemotePlanningTest {
    private static final AEItemKey RAW = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey INTERMEDIATE = AEItemKey.of(Items.IRON_BLOCK);
    private static final AEItemKey FINAL = AEItemKey.of(Items.CAULDRON);
    private static RemotePattern remote() { return new RemotePatternImpl(AEItemKey.of(Items.PAPER), INTERMEDIATE); }
    @Test void receiverIntermediateHasNoRemoteMaterialDemandButSourceDoes() {
        var remote = remote();
        var localFinal = FakePattern.crafting(FINAL, 1, Map.of(INTERMEDIATE, 2L));
        var receiver = FakeGrid.builder().pattern(INTERMEDIATE, remote).pattern(FINAL, localFinal).storage(Map.of(RAW, 999L)).build();
        var session = GraphPlanningSession.create(receiver.crafting, receiver.storage.getCachedInventory(), FINAL,
                CalculationStrategy.REPORT_MISSING_ITEMS, new GraphPlanningBudget(5000), null, false);
        var result = session.solve(3);
        assertTrue(result.handled());
        assertFalse(result.plan().simulation());
        assertEquals(0, result.plan().usedItems().get(RAW));
        assertEquals(BigInteger.valueOf(6), RemoteDemand.from(result.plan()).get(INTERMEDIATE));
        var source = FakeGrid.builder().pattern(INTERMEDIATE, FakePattern.crafting(INTERMEDIATE, 1, Map.of(RAW, 9L))).build();
        var sourcePlan = RemotePlanning.plan(null, source.crafting, INTERMEDIATE, BigInteger.valueOf(6), source, new KeyCounter());
        assertTrue(sourcePlan.simulation());
        assertEquals(54, sourcePlan.missingItems().get(RAW));
        assertFalse(sourcePlan.patternTimes().containsKey(remote));
    }
    @Test void sourceCannotReexportRemoteDependencyEvenWhenItCouldProduceMissingIntermediate() {
        var source = FakeGrid.builder().pattern(INTERMEDIATE, remote()).pattern(FINAL, FakePattern.crafting(FINAL, 1, Map.of(INTERMEDIATE, 2L))).build();
        var plan = RemotePlanning.plan(null, source.crafting, FINAL, BigInteger.ONE, source, new KeyCounter());
        assertTrue(plan.simulation());
        assertEquals(2, plan.missingItems().get(INTERMEDIATE));
        assertTrue(plan.patternTimes().keySet().stream().noneMatch(RemotePattern.class::isInstance));
    }
    @Test void finiteWideSourceIsDeductedExactlyAcrossMultipleTargets() {
        var wide = BigInteger.TEN.pow(30);
        var stock = new KeyCounter(); stock.add(RAW, Long.MAX_VALUE);
        var shared = new RemoteStock(stock, PlanningSupplyTruth.of(Map.of(RAW, PlanAmount.of(wide))));
        shared.reserve(Map.of(RAW, PlanAmount.of(wide.subtract(BigInteger.valueOf(10)))));
        assertEquals(10, shared.snapshot().get(RAW));
        assertEquals(BigInteger.TEN, shared.truth().supplyOf(RAW, Long.MAX_VALUE).exactValue());
        shared.reserve(Map.of(RAW, PlanAmount.of(7)));
        assertEquals(BigInteger.valueOf(3), shared.truth().supplyOf(RAW, shared.snapshot().get(RAW)).exactValue());
        assertThrows(IllegalStateException.class, () -> shared.reserve(Map.of(RAW, PlanAmount.of(4))));
        assertEquals(3, shared.snapshot().get(RAW));
    }
    @Test void executionCanProducePartialBatchWithoutWeakeningFullPreview() {
        var source = FakeGrid.builder().pattern(INTERMEDIATE,
                FakePattern.crafting(INTERMEDIATE, 1, Map.of(RAW, 9L))).storage(Map.of(RAW, 27L)).build();
        var stock = source.storage.getCachedInventory();
        var full = RemotePlanning.plan(null, source.crafting, INTERMEDIATE, BigInteger.TEN, source, stock);
        assertTrue(full.simulation());
        assertEquals(63, full.missingItems().get(RAW));
        var partial = RemotePlanning.plan(null, source.crafting, INTERMEDIATE, BigInteger.TEN, source, stock,
                null, CalculationStrategy.CRAFT_LESS);
        assertFalse(partial.simulation());
        assertEquals(3, partial.finalOutput().amount());
        assertEquals(27, partial.usedItems().get(RAW));
        assertEquals(10, full.finalOutput().amount());
    }
}
