package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class PlanGraphHighlightTest {
    private static final PlanGraph.Node NODE = new PlanGraph.Node(AEItemKey.of(Items.IRON_INGOT), false,
            BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO);
    private static PlanGraph.Edge edge(int from, int to) { return new PlanGraph.Edge(from, to, BigInteger.ONE, false, false); }
    @Test void followsAllAncestorsAndDescendantsWithoutFollowingUnrelatedSideBranches() {
        // 0→1→2→3→4 为主链；5→1 是另一种基础输入。0→6→4 绕过选中物料 2，不能误高亮。
        var graph = new PlanGraph(Collections.nCopies(8, NODE), List.of(edge(0, 1), edge(1, 2), edge(2, 3),
                edge(3, 4), edge(5, 1), edge(0, 6), edge(6, 4), edge(7, 6)));
        var direct = PlanGraphHighlight.create(graph, 2, false);
        assertEquals(Set.of(1, 2), direct.edges()); assertEquals(Set.of(1, 2, 3), direct.nodes());
        var chain = PlanGraphHighlight.create(graph, 2, true);
        assertEquals(Set.of(0, 1, 2, 3, 4), chain.edges()); assertEquals(Set.of(0, 1, 2, 3, 4, 5), chain.nodes());
        assertTrue(PlanGraphHighlight.create(graph, -1, true).edges().isEmpty());
    }
    @Test void longChainWithCycleAndParallelAlternativeEdgesTerminatesWithoutLosingRelations() {
        int size = 10000; var edges = new ArrayList<PlanGraph.Edge>();
        for (int i = 0; i < size - 1; i++) edges.add(edge(i, i + 1));
        edges.add(new PlanGraph.Edge(6000, 5000, BigInteger.TEN, false, false, true));
        edges.add(new PlanGraph.Edge(4999, 5000, BigInteger.TWO, false, true));
        var graph = new PlanGraph(Collections.nCopies(size, NODE), edges);
        var chain = PlanGraphHighlight.create(graph, 5000, true);
        assertEquals(size, chain.nodes().size()); assertEquals(edges.size(), chain.edges().size());
    }
    @Test void overviewConnectsCentersAndSharesTracksForSameColumnPair() {
        var graph = new PlanGraph(Collections.nCopies(3, NODE), List.of(edge(0, 1), edge(0, 2), edge(2, 2)));
        var layout = PlanGraphLayout.create(graph); var routes = PlanGraphRoutes.createOverview(graph, layout);
        for (int i = 0; i < routes.size(); i++) {
            var e = graph.edges().get(i); var points = routes.get(i).points();
            var from = layout.positions().get(e.from()); var to = layout.positions().get(e.to());
            assertEquals(new PlanGraphRoutes.Point(from.x() + 15, from.y() + 15), points.getFirst());
            assertEquals(new PlanGraphRoutes.Point(to.x() + 15, to.y() + 15), points.getLast());
            for (int j = 1; j < points.size(); j++) assertTrue(points.get(j - 1).x() == points.get(j).x()
                    || points.get(j - 1).y() == points.get(j).y());
        }
        assertEquals(routes.get(0).points().get(1).y(), routes.get(1).points().get(1).y());
        assertTrue(routes.get(2).maxY() > layout.positions().get(2).y() + PlanGraphLayout.NODE_SIZE);
    }

}
