package git.chexson.chexsonsaeutils.crafting.status;

import java.util.*;

/** 高亮物料的直接关系或完整上下游有向链条；分别遍历两方向，避免把旁支误当成该物料的链条。 */
public record PlanGraphHighlight(Set<Integer> nodes, Set<Integer> edges) {
    public PlanGraphHighlight { nodes = Set.copyOf(nodes); edges = Set.copyOf(edges); }
    public static PlanGraphHighlight create(PlanGraph graph, int selected, boolean chain) {
        var nodes = new HashSet<Integer>(); var edges = new HashSet<Integer>();
        if (selected < 0) return new PlanGraphHighlight(nodes, edges);
        if (selected >= graph.nodes().size()) throw new IllegalArgumentException("高亮节点越界");
        nodes.add(selected);
        if (!chain) {
            for (int i = 0; i < graph.edges().size(); i++) {
                var e = graph.edges().get(i);
                if (e.from() == selected || e.to() == selected) { edges.add(i); nodes.add(e.from()); nodes.add(e.to()); }
            }
        } else {
            walk(graph, selected, false, nodes, edges);
            walk(graph, selected, true, nodes, edges);
        }
        return new PlanGraphHighlight(nodes, edges);
    }
    /** 迭代遍历，循环与长链均不会递归溢出；边编号保留多样板、候选和退料关系。 */
    private static void walk(PlanGraph graph, int start, boolean upstream, Set<Integer> nodes, Set<Integer> edges) {
        var adjacency = new ArrayList<List<Integer>>();
        for (int i = 0; i < graph.nodes().size(); i++) adjacency.add(new ArrayList<>());
        for (int i = 0; i < graph.edges().size(); i++) {
            var edge = graph.edges().get(i); adjacency.get(upstream ? edge.to() : edge.from()).add(i);
        }
        var seen = new boolean[graph.nodes().size()]; var queue = new ArrayDeque<Integer>();
        seen[start] = true; queue.add(start);
        while (!queue.isEmpty()) for (int i : adjacency.get(queue.remove())) {
            var edge = graph.edges().get(i); int next = upstream ? edge.from() : edge.to();
            edges.add(i); nodes.add(next);
            if (!seen[next]) { seen[next] = true; queue.add(next); }
        }
    }
}
