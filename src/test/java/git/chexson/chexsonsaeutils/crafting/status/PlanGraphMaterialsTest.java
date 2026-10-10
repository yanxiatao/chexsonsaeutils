package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class PlanGraphMaterialsTest {
    private static final BigInteger WIDE = BigInteger.TEN.pow(24);
    private static PlanGraph.Node material(net.minecraft.world.item.Item item) {
        return new PlanGraph.Node(AEItemKey.of(item), false, WIDE, BigInteger.ZERO, BigInteger.ZERO);
    }
    private static PlanGraph.Node recipe() {
        return new PlanGraph.Node(AEItemKey.of(Items.PAPER), true, WIDE, BigInteger.ZERO, BigInteger.ZERO);
    }
    @Test void hidesRecipesButPreservesExactAlternativesByproductsAndReturns() {
        var source = new PlanGraph(List.of(material(Items.GOLD_INGOT), recipe(), material(Items.IRON_INGOT), material(Items.BUCKET)),
                List.of(new PlanGraph.Edge(2, 1, WIDE.multiply(BigInteger.TWO), true, true),
                        new PlanGraph.Edge(1, 0, WIDE, false, false),
                        new PlanGraph.Edge(1, 3, WIDE.multiply(BigInteger.valueOf(3)), false, true, true)));
        var projected = PlanGraphMaterials.create(source); var graph = projected.graph();
        assertEquals(3, graph.nodes().size()); assertTrue(graph.nodes().stream().noneMatch(PlanGraph.Node::pattern));
        assertSame(source.nodes().getFirst(), graph.nodes().getFirst());
        assertEquals(List.of(0, 2), graph.edges().stream().map(PlanGraph.Edge::to).toList());
        assertTrue(graph.edges().stream().allMatch(e -> e.from() == 1 && e.alternative()));
        assertTrue(graph.edges().getLast().remainder());
        assertEquals(WIDE.multiply(BigInteger.TWO), projected.relations().getFirst().input());
        assertEquals(WIDE.multiply(BigInteger.valueOf(3)), projected.relations().getLast().output());
        assertEquals(WIDE, projected.relations().getFirst().recipe().amount());
        assertEquals(List.of(source.nodes().get(1)), projected.recipesFor(0));
        assertEquals(4, source.nodes().size()); assertEquals(3, source.edges().size());
    }
    @Test void preservesSharedProducersCyclesAndRecipesWithoutInputs() {
        var source = new PlanGraph(List.of(material(Items.GOLD_INGOT), material(Items.IRON_INGOT), recipe(), recipe(), recipe()),
                List.of(new PlanGraph.Edge(1, 2, WIDE, true, false), new PlanGraph.Edge(2, 0, WIDE, false, false),
                        new PlanGraph.Edge(0, 3, WIDE, true, false), new PlanGraph.Edge(3, 1, WIDE, false, false),
                        new PlanGraph.Edge(3, 0, WIDE, false, false, true), new PlanGraph.Edge(4, 0, WIDE, false, false)));
        var projected = PlanGraphMaterials.create(source);
        assertEquals(3, projected.graph().edges().size());
        assertEquals(2, PlanGraphLayout.create(projected.graph()).positions().stream().filter(PlanGraphLayout.Position::cycle).count());
        assertTrue(projected.graph().edges().stream().anyMatch(e -> e.from() == e.to() && e.remainder()));
        assertEquals(3, projected.recipesFor(0).size());
    }
}
