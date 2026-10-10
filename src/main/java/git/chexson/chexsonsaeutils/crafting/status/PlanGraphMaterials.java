package git.chexson.chexsonsaeutils.crafting.status;

import java.math.BigInteger;
import java.util.*;

/** 主图的物料投影；隐藏样板节点，同时保留每条生产关系对应的样板及两端精确数量。 */
public record PlanGraphMaterials(PlanGraph source, PlanGraph graph, List<Relation> relations) {
    public PlanGraphMaterials { relations = List.copyOf(relations); }
    /** 与展示边逐一对应；input/output 分别为整单消耗与产出，不能相互累加。 */
    public record Relation(PlanGraph.Node recipe, BigInteger input, BigInteger output) {}

    /** 查询物料关联的样板；无输入配方也保留详情，多个输入槽不重复列出份数。 */
    public List<PlanGraph.Node> recipesFor(int material) {
        var key = graph.nodes().get(material).key(); var ids = new LinkedHashSet<Integer>();
        for (var edge : source.edges()) {
            var from = source.nodes().get(edge.from()); var to = source.nodes().get(edge.to());
            if (!from.pattern() && from.key().equals(key)) ids.add(edge.to());
            if (!to.pattern() && to.key().equals(key)) ids.add(edge.from());
        }
        return ids.stream().map(source.nodes()::get).toList();
    }

    public static PlanGraphMaterials create(PlanGraph source) {
        var nodes = new ArrayList<PlanGraph.Node>();
        var edges = new ArrayList<PlanGraph.Edge>();
        var relations = new ArrayList<Relation>();
        int[] ids = new int[source.nodes().size()]; Arrays.fill(ids, -1);
        var inputs = new ArrayList<List<PlanGraph.Edge>>();
        var outputs = new ArrayList<List<PlanGraph.Edge>>();
        for (int i = 0; i < ids.length; i++) {
            var node = source.nodes().get(i);
            if (!node.pattern()) { ids[i] = nodes.size(); nodes.add(node); }
            inputs.add(new ArrayList<>()); outputs.add(new ArrayList<>());
        }
        for (var edge : source.edges()) {
            if (!source.nodes().get(edge.from()).pattern() && source.nodes().get(edge.to()).pattern()) inputs.get(edge.to()).add(edge);
            else if (source.nodes().get(edge.from()).pattern() && !source.nodes().get(edge.to()).pattern()) outputs.get(edge.from()).add(edge);
            else throw new IllegalArgumentException("生产图必须使用物料与样板交替连接");
        }
        for (int i = 0; i < ids.length; i++) for (var input : inputs.get(i)) for (var output : outputs.get(i)) {
            if (edges.size() == 60000) {
                com.mojang.logging.LogUtils.getLogger().warn("物料生产图超过 60000 条展示关系");
                return new PlanGraphMaterials(source, new PlanGraph(List.of(), List.of(), "物料生产图超过 60000 条关系上限"), List.of());
            }
            edges.add(new PlanGraph.Edge(ids[input.from()], ids[output.to()], input.amount(), false,
                    input.alternative() || output.alternative(), output.remainder()));
            relations.add(new Relation(source.nodes().get(i), input.amount(), output.amount()));
        }
        return new PlanGraphMaterials(source, new PlanGraph(nodes, edges, source.problem()), relations);
    }
}
