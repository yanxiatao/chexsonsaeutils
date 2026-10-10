package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.*;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.*;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import net.minecraft.world.item.Items;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WideRootRequestTest {
    private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey RAW = AEItemKey.of(Items.GOLD_INGOT);
    private static final BigInteger REQUEST = BigInteger.TEN.pow(30).add(BigInteger.ONE);
    private record Pattern(AEItemKey definition) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return definition; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 2)); }
    }
    private CraftGraph graph(BigInteger stock) {
        return graph(stock, List.of(new GraphInput(RAW, 3, 1, GraphInput.Mode.NORMAL, null, null, null, false)));
    }
    private CraftGraph graph(BigInteger stock, List<GraphInput> inputs) {
        var pattern = new GraphPattern(new Pattern(AEItemKey.of(Items.CLOCK)), PRODUCT, 2,
                List.of(new GenericStack(PRODUCT, 2)), inputs);
        var graph = new CraftGraph(PRODUCT, Map.of(PRODUCT, List.of(pattern)), Map.of(pattern.source(), pattern),
                List.of(pattern.source()), Map.of(), Map.of(), Map.of(), Map.of(RAW, Long.MAX_VALUE),
                Set.of(), Map.of(), false, null, 1, 1);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW, PlanAmount.of(stock))));
        return graph;
    }
    @Test void wideRootPreservesRequestCeilingRemainderAndPlanCarrier() {
        var times = REQUEST.add(BigInteger.ONE).divide(BigInteger.TWO);
        var graph = graph(times.multiply(BigInteger.valueOf(3)));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(REQUEST), false, null);
        assertFalse(result.hasDeclined(), result.declineReason());
        assertFalse(result.simulation());
        assertEquals(REQUEST, result.exactFinalAmount().exactValue());
        assertEquals(times.multiply(BigInteger.valueOf(3)), result.state().used().getExact(RAW));
        assertEquals(times, result.state().craftsWide().getAmount(graph.producersByOutput().get(PRODUCT).getFirst().source()).exactValue());
        assertNull(MaterialClosureGate.validate(result.state(), graph, result.exactFinalAmount(), null));
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertEquals(REQUEST, WideAmountPlanCarrier.resolveFinalOutput(plan).exactValue());
        assertEquals(Long.MAX_VALUE, plan.finalOutput().amount());
    }
    @Test void wideBatchDebitsEveryInputEvenWhenTwoSlotsShareOneRawKey() {
        var times = REQUEST.add(BigInteger.ONE).divide(BigInteger.TWO);
        var total = times.multiply(BigInteger.valueOf(8));
        var graph = graph(total, List.of(
                new GraphInput(RAW, 3, 1, GraphInput.Mode.NORMAL, null, null, null, false),
                new GraphInput(RAW, 5, 1, GraphInput.Mode.NORMAL, null, null, null, false)));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(REQUEST), false, null);
        assertFalse(result.hasDeclined(), result.declineReason());
        assertFalse(result.simulation());
        assertEquals(total, result.state().used().getExact(RAW));
        assertNull(MaterialClosureGate.validate(result.state(), graph, result.exactFinalAmount(), null));
    }
    @Test void wideRootReportsExactShortageAndPartialCraftingUsesActualAvailableStock() {
        var graph = graph(BigInteger.valueOf(30));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(REQUEST), false, null);
        assertFalse(result.hasDeclined(), result.declineReason());
        assertTrue(result.simulation());
        var total = REQUEST.add(BigInteger.ONE).divide(BigInteger.TWO).multiply(BigInteger.valueOf(3));
        assertEquals(total.subtract(BigInteger.valueOf(30)), result.state().missing().getExact(RAW));
        var partial = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, PlanAmount.of(REQUEST), false, null);
        assertFalse(partial.hasDeclined(), partial.declineReason());
        assertFalse(partial.simulation());
        assertEquals(BigInteger.valueOf(20), partial.exactFinalAmount().exactValue());
    }
}
