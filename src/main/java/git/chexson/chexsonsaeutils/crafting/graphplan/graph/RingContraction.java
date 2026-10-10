package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveRingPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import git.chexson.chexsonsaeutils.crafting.depgraph.DependencyGraphs;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图内核 SCC 收缩（Phase 2b）：把 {@link GraphCompiler} 已收集的 producer 图按
 * 强连通分量收缩为环宏，取代按色分桶的 {@code compileRingMacros} 查询。
 *
 * <p>二部图建图：节点 = {@code AEKey} ∪ 样板，边 = NORMAL 输入键→样板→产出键；
 * RETURNED / FINITE_USE 与 {@code selfBlocked} 输入不建边（各有闭式记账），自喂
 * 催化输入照常建边——催化物键与样板的互达回路正是催化物环的图论形态。收缩后
 * 图必为 DAG，环成为图上普通宏节点，不再只能挂在根/入口。
 */
public final class RingContraction {

    private RingContraction() {
    }

    /**
     * 对 producer 图跑 SCC，返回全部可压缩环的宏列表。每个环按其净输出键
     * 各产出一个宏实例（{@code RingId.entryPoint} 即挂载入口，与求解器的
     * scale 口径一致）；成员样板与净流量由 {@link CompressedRing#calculate} 权威给出。
     */
    public static List<RingMacro> contract(Map<AEKey, List<GraphPattern>> producers) {
        if (producers == null || producers.isEmpty()) {
            return List.of();
        }
        var patterns = new ArrayList<IPatternDetails>();
        for (var routes : producers.values()) {
            for (GraphPattern pattern : routes) {
                patterns.add(pattern.source());
            }
        }
        return analyzePatterns(patterns).macros();
    }

    /**
     * 对未经树路径祖先过滤的可达样板跑 SCC。
     *
     * <p>多节点环的回边样板即使被普通 DAG 编译跳过，仍由原始样板直接参与
     * {@link CompressedRing} 计算。
     */
    public static List<RingMacro> contractPatterns(
            Collection<? extends IPatternDetails> patterns
    ) {
        return analyzePatterns(patterns).macros();
    }

    public static Analysis analyzePatterns(Collection<? extends IPatternDetails> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return new Analysis(CraftGraph.CycleKind.NONE, List.of());
        }

        Map<AEKey, Integer> keyIndex = new LinkedHashMap<>();
        var patternList = new ArrayList<IPatternDetails>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<IPatternDetails, Boolean>());
        for (IPatternDetails pattern : patterns) {
            if (pattern == null || !seen.add(pattern)) {
                continue;
            }
            patternList.add(pattern);
            for (GenericStack output : pattern.getOutputs()) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    keyIndex.computeIfAbsent(output.what(), ignored -> keyIndex.size());
                }
            }
        }
        int keyCount = keyIndex.size();
        int nodeCount = keyCount + patternList.size();
        if (keyCount == 0 || patternList.isEmpty()) {
            return new Analysis(CraftGraph.CycleKind.NONE, List.of());
        }
        // 原始可达图直接写整数边，避免每节点一个集合和每边装箱。
        var edgeLists = new IntArrayList[nodeCount];
        for (int ordinal = 0; ordinal < patternList.size(); ordinal++) {
            IPatternDetails pattern = patternList.get(ordinal);
            int patternNode = keyCount + ordinal;
            for (GenericStack output : pattern.getOutputs()) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    addEdge(edgeLists, patternNode, keyIndex.get(output.what()));
                }
            }
            for (IPatternDetails.IInput input : pattern.getInputs()) {
                int inputKeyNode = normalInputKeyNode(input, keyIndex);
                if (inputKeyNode >= 0) {
                    addEdge(edgeLists, inputKeyNode, patternNode);
                }
            }
        }

        int[][] successors = new int[nodeCount][];
        int[] emptyEdges = new int[0];
        for (int node = 0; node < nodeCount; node++) {
            successors[node] = edgeLists[node] == null ? emptyEdges : edgeLists[node].toIntArray();
        }
        List<List<Integer>> components = DependencyGraphs.stronglyConnectedComponents(successors);
        int[] componentOf = DependencyGraphs.assertCondensationAcyclic(components, successors);

        int cyclicComponents = 0;
        int compressedComponents = 0;
        List<RingMacro> macros = new ArrayList<>();
        for (int componentId = 0; componentId < components.size(); componentId++) {
            if (components.get(componentId).size() <= 1) {
                continue;
            }
            cyclicComponents++;
            Set<IPatternDetails> memberSources =
                    componentSourcesOf(componentId, componentOf, keyCount, patternList);
            if (memberSources.isEmpty()) {
                continue;
            }
            CompressedRing ring = CompressedRing.calculate(memberSources);
            if (ring == null || !ring.calculable() || ring.entryPoints().isEmpty()) {
                continue;
            }
            int macroStart = macros.size();
            for (AEKey entry : ring.entryPoints()) {
                if (ring.netOutputs().get(entry) <= 0L) {
                    continue;
                }
                if (!RecursiveRingPlanner.allowsRingReplacementCandidate(ring, entry)) {
                    continue;
                }
                RingId ringId = RingId.of(entry, memberSources);
                Map<AEKey, git.chexson.chexsonsaeutils.bigint.PlanAmount> startupSeeds =
                        RingStartupPlan.minimumSeeds(ring.executionRatio());
                if (startupSeeds == null || startupSeeds.isEmpty()) {
                    continue;
                }
                macros.add(new RingMacro(ringId, ring.netInputs(), ring.netOutputs(),
                        ring.catalysts(), startupSeeds, ring.executionRatio(),
                        ring.memberFlows(), false));
            }
            if (macros.size() > macroStart) {
                compressedComponents++;
            }
        }
        CraftGraph.CycleKind cycleKind = cyclicComponents == 0
                ? CraftGraph.CycleKind.NONE
                : compressedComponents == cyclicComponents
                        ? CraftGraph.CycleKind.COMPRESSIBLE
                        : CraftGraph.CycleKind.UNSUPPORTED_CYCLE;
        return new Analysis(cycleKind, List.copyOf(macros));
    }

    public record Analysis(CraftGraph.CycleKind cycleKind, List<RingMacro> macros) {
    }

    /** 保留原始输入和输出的边顺序；平行边不影响强连通关系。 */
    private static void addEdge(IntArrayList[] edges, int from, int to) {
        if (edges[from] == null) {
            edges[from] = new IntArrayList(2);
        }
        edges[from].add(to);
    }

    private static int normalInputKeyNode(
            IPatternDetails.IInput input,
            Map<AEKey, Integer> keyIndex
    ) {
        if (input == null) {
            return -1;
        }
        GenericStack[] possibleInputs = input.getPossibleInputs();
        if (possibleInputs == null || possibleInputs.length == 0 || possibleInputs[0] == null) {
            return -1;
        }
        GenericStack selected = possibleInputs[0];
        for (GenericStack candidate : possibleInputs) {
            if (candidate != null && candidate.what() != null && candidate.amount() > 0L
                    && keyIndex.containsKey(candidate.what())) {
                selected = candidate;
                break;
            }
        }
        AEKey inputKey = selected.what();
        if (inputKey == null || selected.amount() <= 0L || input.getRemainingKey(inputKey) != null) {
            return -1;
        }
        return keyIndex.getOrDefault(inputKey, -1);
    }

    private static Set<IPatternDetails> componentSourcesOf(
            int componentId,
            int[] componentOf,
            int keyCount,
            List<IPatternDetails> patterns
    ) {
        Set<IPatternDetails> sources = new LinkedHashSet<>();
        for (int ordinal = 0; ordinal < patterns.size(); ordinal++) {
            if (componentOf[keyCount + ordinal] == componentId) {
                sources.add(patterns.get(ordinal));
            }
        }
        return sources;
    }
}
