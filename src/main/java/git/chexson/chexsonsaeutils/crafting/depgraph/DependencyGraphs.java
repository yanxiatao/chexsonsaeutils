package git.chexson.chexsonsaeutils.crafting.depgraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

/**
 * Generic dependency-first ordering and strongly connected component analysis.
 * <p>
 * Shared by the host-batch chain segmenter and the recursive ring index so both
 * callers rely on one implementation of the iterative Tarjan walk.
 */
public final class DependencyGraphs {

    private DependencyGraphs() {
    }

    // Best-effort dependency-first order: acyclic graphs keep legacy FIFO Kahn output
    // bit-for-bit; cyclic groups are condensed via SCC and emitted adjacently instead
    // of failing. Null dependencies, null members, unknown members and self-loops are
    // ignored; input nodes are deduplicated keeping first occurrence.
    public static <N> List<N> orderDependencyFirst(
            Collection<? extends N> nodes,
            Function<? super N, ? extends Collection<? extends N>> dependenciesOf
    ) {
        if (nodes == null || nodes.isEmpty() || dependenciesOf == null) {
            return List.of();
        }

        List<N> uniqueNodes = new ArrayList<>();
        Map<N, Integer> indexOf = new LinkedHashMap<>();
        for (N node : nodes) {
            if (node != null && !indexOf.containsKey(node)) {
                indexOf.put(node, uniqueNodes.size());
                uniqueNodes.add(node);
            }
        }
        if (uniqueNodes.isEmpty()) {
            return List.of();
        }

        int nodeCount = uniqueNodes.size();
        List<LinkedHashSet<Integer>> consumersOf = new ArrayList<>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            consumersOf.add(new LinkedHashSet<>());
        }
        int[] indegree = new int[nodeCount];
        for (int consumerIndex = 0; consumerIndex < nodeCount; consumerIndex++) {
            Collection<? extends N> dependencies = dependenciesOf.apply(uniqueNodes.get(consumerIndex));
            if (dependencies == null) {
                continue;
            }
            for (N dependency : dependencies) {
                Integer dependencyIndex = dependency == null ? null : indexOf.get(dependency);
                if (dependencyIndex == null || dependencyIndex == consumerIndex) {
                    continue;
                }
                if (consumersOf.get(dependencyIndex).add(consumerIndex)) {
                    indegree[consumerIndex]++;
                }
            }
        }

        List<Integer> orderedIndexes = fifoKahn(consumersOf, indegree, nodeCount);
        if (orderedIndexes.size() == nodeCount) {
            return materializeOrdered(uniqueNodes, orderedIndexes);
        }

        return materializeOrdered(uniqueNodes, orderByStrongComponents(consumersOf, nodeCount));
    }

    private static List<Integer> fifoKahn(
            List<LinkedHashSet<Integer>> consumersOf,
            int[] indegree,
            int nodeCount
    ) {
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        for (int index = 0; index < nodeCount; index++) {
            if (indegree[index] == 0) {
                ready.addLast(index);
            }
        }

        List<Integer> ordered = new ArrayList<>(nodeCount);
        while (!ready.isEmpty()) {
            int current = ready.removeFirst();
            ordered.add(current);
            for (int consumer : consumersOf.get(current)) {
                indegree[consumer]--;
                if (indegree[consumer] == 0) {
                    ready.addLast(consumer);
                }
            }
        }
        return ordered;
    }

    private static List<Integer> orderByStrongComponents(
            List<LinkedHashSet<Integer>> consumersOf,
            int nodeCount
    ) {
        List<List<Integer>> components = stronglyConnectedComponents(consumersOf, nodeCount);
        int componentCount = components.size();

        int[] componentOf = new int[nodeCount];
        int[] minMemberIndex = new int[componentCount];
        for (int component = 0; component < componentCount; component++) {
            int minMember = Integer.MAX_VALUE;
            for (int member : components.get(component)) {
                componentOf[member] = component;
                minMember = Math.min(minMember, member);
            }
            minMemberIndex[component] = minMember;
        }

        List<LinkedHashSet<Integer>> componentConsumers = new ArrayList<>(componentCount);
        for (int component = 0; component < componentCount; component++) {
            componentConsumers.add(new LinkedHashSet<>());
        }
        int[] componentIndegree = new int[componentCount];
        for (int dependency = 0; dependency < nodeCount; dependency++) {
            int dependencyComponent = componentOf[dependency];
            for (int consumer : consumersOf.get(dependency)) {
                int consumerComponent = componentOf[consumer];
                if (consumerComponent != dependencyComponent
                        && componentConsumers.get(dependencyComponent).add(consumerComponent)) {
                    componentIndegree[consumerComponent]++;
                }
            }
        }

        PriorityQueue<Integer> ready = new PriorityQueue<>(
                Math.max(1, componentCount),
                Comparator.comparingInt(component -> minMemberIndex[component])
        );
        for (int component = 0; component < componentCount; component++) {
            if (componentIndegree[component] == 0) {
                ready.add(component);
            }
        }

        List<Integer> ordered = new ArrayList<>(nodeCount);
        while (!ready.isEmpty()) {
            int component = ready.poll();
            List<Integer> members = new ArrayList<>(components.get(component));
            Collections.sort(members);
            ordered.addAll(members);
            for (int consumer : componentConsumers.get(component)) {
                componentIndegree[consumer]--;
                if (componentIndegree[consumer] == 0) {
                    ready.add(consumer);
                }
            }
        }
        return ordered;
    }

    // Iterative Tarjan SCC; explicit call stack avoids recursion limits on large plan graphs.
    public static List<List<Integer>> stronglyConnectedComponents(
            List<LinkedHashSet<Integer>> consumersOf,
            int nodeCount
    ) {
        int[][] successors = new int[nodeCount][];
        for (int node = 0; node < nodeCount; node++) {
            LinkedHashSet<Integer> edges = consumersOf.get(node);
            successors[node] = new int[edges.size()];
            int position = 0;
            for (int consumer : edges) {
                successors[node][position++] = consumer;
            }
        }
        return stronglyConnectedComponents(successors);
    }

    /**
     * 稠密邻接表的迭代 Tarjan 入口：建图方已有整数边时无需先创建集合再拆箱。
     * 每行按调用方的边顺序遍历，返回顺序与集合入口一致；重复边和自环均合法。
     * 节点编号必须在邻接表范围内，每行必须非 null。
     */
    public static List<List<Integer>> stronglyConnectedComponents(int[][] successors) {
        int nodeCount = successors.length;

        int[] discovery = new int[nodeCount];
        int[] lowlink = new int[nodeCount];
        boolean[] onStack = new boolean[nodeCount];
        int[] componentStack = new int[nodeCount];
        int stackSize = 0;
        int clock = 0;
        List<List<Integer>> components = new ArrayList<>();
        int[] callNodes = new int[nodeCount];
        int[] callPositions = new int[nodeCount];
        int callDepth = 0;

        for (int root = 0; root < nodeCount; root++) {
            if (discovery[root] != 0) {
                continue;
            }
            discovery[root] = lowlink[root] = ++clock;
            componentStack[stackSize++] = root;
            onStack[root] = true;
            callNodes[callDepth] = root;
            callPositions[callDepth++] = 0;

            while (callDepth != 0) {
                int frame = callDepth - 1;
                int node = callNodes[frame];
                if (callPositions[frame] < successors[node].length) {
                    int successor = successors[node][callPositions[frame]++];
                    if (discovery[successor] == 0) {
                        discovery[successor] = lowlink[successor] = ++clock;
                        componentStack[stackSize++] = successor;
                        onStack[successor] = true;
                        callNodes[callDepth] = successor;
                        callPositions[callDepth++] = 0;
                    } else if (onStack[successor]) {
                        lowlink[node] = Math.min(lowlink[node], discovery[successor]);
                    }
                } else {
                    callDepth--;
                    if (callDepth != 0) {
                        int parent = callNodes[callDepth - 1];
                        lowlink[parent] = Math.min(lowlink[parent], lowlink[node]);
                    }
                    if (lowlink[node] == discovery[node]) {
                        List<Integer> component = new ArrayList<>();
                        int member;
                        do {
                            member = componentStack[--stackSize];
                            onStack[member] = false;
                            component.add(member);
                        } while (member != node);
                        components.add(component);
                    }
                }
            }
        }
        return components;
    }

    /**
     * SCC 缩点断言（借 neoecoae CondensationGraph）：分量图必须无环，含环即
     * 实现 bug。返回 node → 分量号的映射供调用方复用，避免二次扫边。
     */
    public static int[] assertCondensationAcyclic(
            List<List<Integer>> components,
            List<? extends Set<Integer>> successors
    ) {
        int[][] denseSuccessors = new int[successors.size()][];
        for (int node = 0; node < successors.size(); node++) {
            var edges = successors.get(node);
            denseSuccessors[node] = new int[edges.size()];
            int position = 0;
            for (int successor : edges) {
                denseSuccessors[node][position++] = successor;
            }
        }
        return assertCondensationAcyclic(components, denseSuccessors);
    }

    /**
     * 对整数邻接表检查 SCC 缩点是否无环，并返回节点到分量的映射。
     * 平行边按出现次数记入度并逐次扣减，无需为每个分量创建去重集合。
     */
    public static int[] assertCondensationAcyclic(List<List<Integer>> components, int[][] successors) {
        int componentCount = components.size();
        int[] componentOf = new int[successors.length];
        for (int component = 0; component < componentCount; component++) {
            for (int member : components.get(component)) {
                componentOf[member] = component;
            }
        }
        int[] indegree = new int[componentCount];
        int[] edgeCounts = new int[componentCount];
        for (int node = 0; node < successors.length; node++) {
            for (int successor : successors[node]) {
                int from = componentOf[node];
                int to = componentOf[successor];
                if (from != to) {
                    edgeCounts[from]++;
                    indegree[to]++;
                }
            }
        }
        int[][] componentSuccessors = new int[componentCount][];
        for (int component = 0; component < componentCount; component++) {
            componentSuccessors[component] = new int[edgeCounts[component]];
            edgeCounts[component] = 0;
        }
        for (int node = 0; node < successors.length; node++) {
            int from = componentOf[node];
            for (int successor : successors[node]) {
                int to = componentOf[successor];
                if (from != to) {
                    componentSuccessors[from][edgeCounts[from]++] = to;
                }
            }
        }
        int[] ready = new int[componentCount];
        int readyStart = 0;
        int readyEnd = 0;
        for (int component = 0; component < componentCount; component++) {
            if (indegree[component] == 0) {
                ready[readyEnd++] = component;
            }
        }
        int visited = 0;
        while (readyStart < readyEnd) {
            int component = ready[readyStart++];
            visited++;
            for (int successor : componentSuccessors[component]) {
                if (--indegree[successor] == 0) {
                    ready[readyEnd++] = successor;
                }
            }
        }
        if (visited != componentCount) {
            throw new IllegalStateException("SCC condensation graph must be acyclic");
        }
        return componentOf;
    }

    private static <N> List<N> materializeOrdered(List<N> uniqueNodes, List<Integer> orderedIndexes) {
        List<N> ordered = new ArrayList<>(orderedIndexes.size());
        for (int index : orderedIndexes) {
            ordered.add(uniqueNodes.get(index));
        }
        return List.copyOf(ordered);
    }
}
