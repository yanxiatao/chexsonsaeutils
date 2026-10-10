package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class PlanGraphTest {
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT), GOLD = AEItemKey.of(Items.GOLD_INGOT), STICK = AEItemKey.of(Items.STICK);
    private static final BigInteger WIDE = BigInteger.TEN.pow(24);
    private record Input(GenericStack[] getPossibleInputs, long getMultiplier, AEKey remainder) implements IPatternDetails.IInput {
        Input(GenericStack[] possible, long multiplier) { this(possible, multiplier, null); }
        @Override public boolean isValid(AEKey key, Level level) { return true; }
        @Override public AEKey getRemainingKey(AEKey key) { return remainder; }
    }
    private record Pattern(AEItemKey getDefinition, IPatternDetails.IInput[] getInputs, List<GenericStack> getOutputs) implements IPatternDetails {}
    @Test void capturesExactExecutionsByproductsAndAlternativeUpperBounds() {
        var pattern = new Pattern(STICK, new IPatternDetails.IInput[]{new Input(new GenericStack[]{new GenericStack(IRON, 2), new GenericStack(GOLD, 3)}, 4)},
                List.of(new GenericStack(STICK, 5), new GenericStack(GOLD, 1)));
        var missing = new KeyCounter(); missing.add(IRON, 9);
        var crafts = new WideCraftCounter(); crafts.add(pattern, PlanAmount.of(WIDE));
        var plan = new WideAmountCraftingPlan(new CraftingPlan(new GenericStack(STICK, Long.MAX_VALUE), 1, true, false,
                new KeyCounter(), new KeyCounter(), missing, Map.of(pattern, Long.MAX_VALUE)),
                WideLedgerSnapshot.empty(), WideCraftSnapshot.capture(crafts), Set.of(), PlanAmount.of(WIDE.multiply(BigInteger.valueOf(5))));
        var graph = PlanGraph.fromPlan(plan);
        assertEquals(WIDE.multiply(BigInteger.valueOf(5)), graph.nodes().getFirst().amount());
        var task = graph.nodes().stream().filter(PlanGraph.Node::pattern).findFirst().orElseThrow(); assertEquals(WIDE, task.amount());
        assertEquals(4, graph.edges().size());
        assertEquals(List.of(WIDE.multiply(BigInteger.valueOf(8)), WIDE.multiply(BigInteger.valueOf(12))),
                graph.edges().stream().filter(PlanGraph.Edge::input).map(PlanGraph.Edge::amount).toList());
        assertTrue(graph.edges().stream().filter(PlanGraph.Edge::input).allMatch(PlanGraph.Edge::alternative));
        var complete = new WideAmountCraftingPlan(new CraftingPlan(plan.finalOutput(), 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), plan.patternTimes()),
                WideLedgerSnapshot.empty(), WideCraftSnapshot.capture(crafts), Set.of(), PlanAmount.of(WIDE.multiply(BigInteger.valueOf(5))));
        var expectedSummaryGraph = PlanGraph.fromPlan(complete);
        var summary = appeng.menu.me.crafting.CraftingPlanSummary.fromJob(
                git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder().build(),
                appeng.api.networking.security.IActionSource.empty(), complete);
        assertEquals(expectedSummaryGraph, ((EnhancedCraftingPlanSummary) (Object) summary).chexsonsaeutils$graph());
        var packet = buffer();
        try { summary.write(packet);
            var decoded = (EnhancedCraftingPlanSummary) (Object) appeng.menu.me.crafting.CraftingPlanSummary.read(packet);
            assertEquals(expectedSummaryGraph, decoded.chexsonsaeutils$graph()); assertEquals(0, packet.readableBytes());
        } finally { packet.release(); }
        assertEquals(BigInteger.valueOf(9), graph.nodes().stream().filter(n -> !n.pattern() && n.key().equals(IRON)).findFirst().orElseThrow().missing());
        var buf = buffer();
        try { graph.write(buf); assertEquals(graph, PlanGraph.read(buf)); assertEquals(0, buf.readableBytes()); }
        finally { buf.release(); }
    }
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)); }
    @Test void selectedInputsUsePlannerAmountsAndContainerReturnsUseSlotMultiplier() {
        var pattern = new Pattern(STICK, new IPatternDetails.IInput[]{new Input(new GenericStack[]{new GenericStack(IRON, 2), new GenericStack(GOLD, 3)}, 4, IRON)}, List.of(new GenericStack(STICK, 1)));
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan(
                new CraftingPlan(new GenericStack(STICK, 10), 1, false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 10L)),
                new KeyCounter(), new KeyCounter(), 10L, Map.of(STICK, Map.of(IRON, 8L)), Map.of(), WideLedgerSnapshot.empty(), WideCraftSnapshot.empty(), Set.of(), Map.of());
        var graph = PlanGraph.fromPlan(plan);
        var inputs = graph.edges().stream().filter(PlanGraph.Edge::input).toList(); assertEquals(1, inputs.size());
        assertEquals(BigInteger.valueOf(80), inputs.getFirst().amount()); assertFalse(inputs.getFirst().alternative());
        var returned = graph.edges().stream().filter(PlanGraph.Edge::remainder).toList(); assertEquals(1, returned.size());
        assertEquals(BigInteger.valueOf(40), returned.getFirst().amount());
        assertEquals(2, PlanGraphLayout.create(graph).positions().stream().filter(PlanGraphLayout.Position::cycle).count());
    }
    private static PlanGraph.Node node() { return new PlanGraph.Node(IRON, false, WIDE, BigInteger.ZERO, BigInteger.ZERO); }
    private static PlanGraph.Edge edge(int a, int b) { return new PlanGraph.Edge(a, b, BigInteger.ONE, false, false); }
    @Test void laysOutSharedDependenciesAndCyclesWithoutInventingSequentialExecution() {
        var graph = new PlanGraph(Collections.nCopies(6, node()), List.of(edge(0,1), edge(0,2), edge(1,3), edge(2,3), edge(3,4), edge(4,3), edge(4,5)));
        var layout = PlanGraphLayout.create(graph); var p = layout.positions();
        assertEquals(p.get(1).layer(), p.get(2).layer()); assertEquals(p.get(3).layer(), p.get(4).layer());
        assertTrue(p.get(3).cycle()); assertTrue(p.get(4).cycle()); assertFalse(p.get(5).cycle());
        assertTrue(p.get(5).layer() > p.get(4).layer()); assertTrue(p.get(1).layer() > p.get(0).layer());
        assertTrue(p.get(5).y() < p.get(4).y()); assertEquals(p.get(1).y(), p.get(2).y());
        assertEquals(layout, PlanGraphLayout.create(graph));
    }
    @Test void handlesDeepChainsWithoutJavaStackRecursion() {
        var edges = new ArrayList<PlanGraph.Edge>(); for (int i = 0; i < 9999; i++) edges.add(edge(i, i + 1));
        var layout = PlanGraphLayout.create(new PlanGraph(Collections.nCopies(10000, node()), edges));
        assertEquals(9999, layout.positions().getLast().layer());
    }
    @Test void rejectsInvalidReferencesAndNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new PlanGraph(List.of(node()), List.of(edge(0, 1))));
        assertThrows(IllegalArgumentException.class, () -> new PlanGraph.Node(IRON, false, BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ZERO));
        var b = buffer(); try { b.writeUtf(""); b.writeVarInt(-1); assertThrows(IllegalArgumentException.class, () -> PlanGraph.read(b)); } finally { b.release(); }
    }
    @Test void arrowsAttachToSeparateNodePortsAndCycleRoutesStayOutsideIcons() {
        var graph = new PlanGraph(Collections.nCopies(4, node()), List.of(edge(0,1), edge(0,2), edge(2,3), edge(3,2)));
        var layout = PlanGraphLayout.create(graph); var routes = PlanGraphRoutes.create(graph, layout);
        assertNotEquals(routes.get(0).points().getFirst(), routes.get(1).points().getFirst());
        for (int i = 0; i < routes.size(); i++) {
            var route = routes.get(i).points(); var edge = graph.edges().get(i);
            var source = layout.positions().get(edge.from()); var target = layout.positions().get(edge.to());
            assertEquals(target.y() < source.y() ? source.y() : source.y() + PlanGraphLayout.NODE_SIZE, route.getFirst().y());
            assertEquals(target.y() <= source.y() ? target.y() + PlanGraphLayout.NODE_SIZE : target.y(), route.getLast().y());
            assertTrue(route.getFirst().x() > source.x() && route.getFirst().x() < source.x() + PlanGraphLayout.NODE_SIZE);
            assertTrue(route.getLast().x() > target.x() && route.getLast().x() < target.x() + PlanGraphLayout.NODE_SIZE);
            assertTrue(routes.get(i).visible(route.getLast().x(), route.getLast().y(), route.getLast().x(), route.getLast().y()));
            assertFalse(routes.get(i).visible(-1000, -1000, -900, -900));
            assertTrue(routes.get(i).maxX() < layout.width()); assertTrue(routes.get(i).maxY() < layout.height());
            for (int j = 1; j < route.size(); j++) assertTrue(route.get(j - 1).x() == route.get(j).x() || route.get(j - 1).y() == route.get(j).y());
            if (source.y() == target.y()) assertTrue(route.get(1).y() > source.y() + PlanGraphLayout.NODE_SIZE);
        }
    }
    @Test void oversizedGraphPublishesExplicitProblemInsteadOfOversizedMenuPacket() {
        var graph = new PlanGraph(Collections.nCopies(20000, node()), List.of()); var b = buffer();
        try { graph.write(b); assertTrue(b.readableBytes() < 256); var result = PlanGraph.read(b);
            assertFalse(result.problem().isEmpty()); assertTrue(result.nodes().isEmpty()); assertEquals(0, b.readableBytes());
        } finally { b.release(); }
    }    @Test void finalTargetIsTopmostAndInterleavedCycleMembersStayInContiguousBlocks() {
        // 两个独立环的编号交错，中间还夹着非环材料；聚集依据真实 SCC 而非编号。
        var graph = new PlanGraph(Collections.nCopies(7, node()), List.of(edge(1, 3), edge(3, 1),
                edge(2, 5), edge(5, 2), edge(3, 0), edge(5, 0), edge(4, 0), edge(6, 4), edge(6, 1), edge(6, 2)));
        var layout = PlanGraphLayout.create(graph); var p = layout.positions();
        assertEquals(24, p.get(0).y());
        assertTrue(p.stream().allMatch(position -> position.y() >= p.get(0).y()));
        assertEquals(p.get(1).y(), p.get(3).y()); assertEquals(80, p.get(3).x() - p.get(1).x());
        assertEquals(p.get(2).y(), p.get(5).y()); assertEquals(80, p.get(5).x() - p.get(2).x());
        assertTrue(p.get(2).x() > p.get(3).x() + PlanGraphLayout.NODE_SIZE);
        assertTrue(p.get(4).x() > p.get(5).x() + PlanGraphLayout.NODE_SIZE);
        assertTrue(p.get(6).y() > p.get(4).y());
        assertEquals(layout, PlanGraphLayout.create(graph));
        for (var route : PlanGraphRoutes.create(graph, layout)) {
            assertTrue(route.minX() >= 0 && route.minY() >= 0);
            assertTrue(route.maxX() < layout.width() && route.maxY() < layout.height());
        }
    }
    @Test void targetRemainsTopmostWhenItsCycleProducesAnotherTerminalByproduct() {
        var graph = new PlanGraph(Collections.nCopies(3, node()), List.of(edge(0, 1), edge(1, 0), edge(1, 2)));
        var p = PlanGraphLayout.create(graph).positions();
        assertEquals(24, p.get(0).y()); assertEquals(p.get(0).y(), p.get(1).y());
        assertTrue(p.get(2).y() > p.get(0).y());
    }

}
