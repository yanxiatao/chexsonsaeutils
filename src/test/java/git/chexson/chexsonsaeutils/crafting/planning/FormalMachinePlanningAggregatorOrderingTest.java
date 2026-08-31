package git.chexson.chexsonsaeutils.crafting.planning;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FormalMachinePlanningAggregatorOrderingTest {

    private static List<String> order(List<String> nodes, Map<String, List<String>> dependencies) {
        return FormalMachinePlanningAggregator.orderDependencyFirst(nodes, dependencies::get);
    }

    private static Map<String, List<String>> deps(Object... pairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            String consumer = (String) pairs[index];
            @SuppressWarnings("unchecked")
            List<String> dependencies = (List<String>) pairs[index + 1];
            map.put(consumer, dependencies);
        }
        return map;
    }

    @Test
    void emptyAndNullInputsReturnEmpty() {
        assertEquals(List.of(), FormalMachinePlanningAggregator.orderDependencyFirst(List.of(), node -> List.of()));
        assertEquals(List.of(), FormalMachinePlanningAggregator.orderDependencyFirst(null, node -> List.of()));
        assertEquals(List.of(), FormalMachinePlanningAggregator.orderDependencyFirst(List.of("A"), null));
        assertEquals(List.of(), order(Arrays.asList((String) null), Map.of()));
    }

    @Test
    void chainOrdersDependenciesFirst() {
        List<String> ordered = order(
                List.of("C", "B", "A"),
                deps("C", List.of("B"), "B", List.of("A"))
        );
        assertEquals(List.of("A", "B", "C"), ordered);
    }

    @Test
    void fifoTieBreakMatchesLegacyKahn() {
        // Legacy FIFO Kahn emits A, B, D, C here; a min-index priority queue would emit A, B, C, D.
        List<String> nodes = List.of("A", "B", "C", "D");
        Map<String, List<String>> dependencies = deps("D", List.of("A"), "C", List.of("B"));

        List<String> ordered = order(nodes, dependencies);

        assertEquals(List.of("A", "B", "D", "C"), ordered);
        assertEquals(referenceFifoKahn(nodes, dependencies), ordered);
    }

    @Test
    void diamondKeepsDependencyOrder() {
        List<String> ordered = order(
                List.of("A", "B", "C", "D"),
                deps("B", List.of("A"), "C", List.of("A"), "D", List.of("B", "C"))
        );
        assertEquals(List.of("A", "B", "C", "D"), ordered);
    }

    @Test
    void isolatedNodesAndEmptyDependenciesAreIncluded() {
        List<String> ordered = order(
                List.of("A", "B", "C"),
                deps("B", List.of())
        );
        assertEquals(List.of("A", "B", "C"), ordered);
    }

    @Test
    void nullDependencyCollectionAndMembersAreIgnored() {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        dependencies.put("B", null);
        dependencies.put("A", Arrays.asList((String) null));

        assertEquals(List.of("A", "B"), order(List.of("A", "B"), dependencies));
    }

    @Test
    void selfLoopsAreIgnored() {
        assertEquals(
                List.of("A", "B"),
                order(List.of("A", "B"), deps("A", List.of("A"), "B", List.of("B", "A")))
        );
    }

    @Test
    void dependenciesOutsideNodeSetAreIgnored() {
        assertEquals(
                List.of("A", "B"),
                order(List.of("A", "B"), deps("A", List.of("Z"), "B", List.of("A", "Z")))
        );
    }

    @Test
    void duplicateNodesAndEdgesAreCollapsed() {
        List<String> ordered = order(
                Arrays.asList("A", "B", "A"),
                deps("B", List.of("A", "A"))
        );
        assertEquals(List.of("A", "B"), ordered);
    }

    @Test
    void twoCycleKeepsMembersAdjacentAndDownstreamLast() {
        List<String> ordered = order(
                List.of("A", "B", "C"),
                deps("A", List.of("B"), "B", List.of("A"), "C", List.of("A"))
        );
        assertEquals(List.of("A", "B", "C"), ordered);
    }

    @Test
    void threeCycleReturnsAllMembersInInsertionOrder() {
        List<String> ordered = order(
                List.of("A", "B", "C"),
                deps("A", List.of("B"), "B", List.of("C"), "C", List.of("A"))
        );
        assertEquals(List.of("A", "B", "C"), ordered);
    }

    @Test
    void cyclicOrderingIsDeterministicAcrossCalls() {
        List<String> nodes = List.of("A", "B", "C", "D", "E");
        Map<String, List<String>> dependencies = deps(
                "A", List.of("B"),
                "B", List.of("A"),
                "C", List.of("A"),
                "D", List.of("E"),
                "E", List.of("D")
        );

        List<String> first = order(nodes, dependencies);
        assertEquals(first, order(nodes, dependencies));
        assertEquals(new HashSet<>(nodes), new HashSet<>(first));
        assertEquals(nodes.size(), first.size());
    }

    @Test
    void crossComponentDependenciesAlwaysComeFirst() {
        List<String> nodes = List.of("A", "B", "C", "D", "E", "F");
        Map<String, List<String>> dependencies = deps(
                "A", List.of("B"),
                "B", List.of("A"),
                "C", List.of("B"),
                "E", List.of("D")
        );

        List<String> ordered = order(nodes, dependencies);

        assertEquals(List.of("A", "B", "C", "D", "E", "F"), ordered);
    }

    @Test
    void randomDagsMatchReferenceFifoKahn() {
        Random random = new Random(42L);
        int nodeCount = 150;
        List<String> nodes = new ArrayList<>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            nodes.add(String.format("n%03d", index));
        }

        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (int consumer = 0; consumer < nodeCount; consumer++) {
            List<String> producers = new ArrayList<>();
            for (int producer = 0; producer < consumer; producer++) {
                if (random.nextInt(100) < 3) {
                    producers.add(nodes.get(producer));
                }
            }
            dependencies.put(nodes.get(consumer), producers);
        }

        List<String> expected = referenceFifoKahn(nodes, dependencies);
        assertEquals(nodeCount, expected.size(), "generated graph must stay acyclic");
        assertEquals(expected, order(nodes, dependencies));
    }

    @Test
    void randomGraphSccPartitionMatchesRecursiveReference() {
        Random random = new Random(7L);
        int nodeCount = 80;
        List<String> nodes = new ArrayList<>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            nodes.add(String.format("g%03d", index));
        }

        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (String node : nodes) {
            List<String> producers = new ArrayList<>();
            for (String candidate : nodes) {
                if (candidate != node && random.nextInt(100) < 4) {
                    producers.add(candidate);
                }
            }
            dependencies.put(node, producers);
        }

        Set<Set<String>> expectedPartition = new HashSet<>(referenceStronglyConnectedComponents(
                nodes,
                consumerAdjacency(nodes, dependencies)
        ));
        Set<Set<String>> actualPartition = new HashSet<>();
        for (List<Integer> component : FormalMachinePlanningAggregator.stronglyConnectedComponents(
                indexConsumersOf(nodes, dependencies), nodeCount)) {
            Set<String> members = new HashSet<>();
            for (int memberIndex : component) {
                members.add(nodes.get(memberIndex));
            }
            actualPartition.add(members);
        }
        assertEquals(expectedPartition, actualPartition);

        List<String> ordered = order(nodes, dependencies);
        assertEquals(nodeCount, ordered.size());
        assertEquals(new HashSet<>(nodes), new HashSet<>(ordered));

        Map<String, Integer> positionOf = new HashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            positionOf.put(ordered.get(index), index);
        }
        Map<String, Set<String>> partitionOf = new HashMap<>();
        for (Set<String> component : expectedPartition) {
            for (String member : component) {
                partitionOf.put(member, component);
            }
        }
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                if (partitionOf.get(dependency) != partitionOf.get(entry.getKey())) {
                    assertTrue(
                            positionOf.get(dependency) < positionOf.get(entry.getKey()),
                            "cross-component dependency " + dependency + " must precede " + entry.getKey()
                    );
                }
            }
        }
    }

    private static List<String> referenceFifoKahn(
            List<String> nodeOrder,
            Map<String, List<String>> dependenciesByConsumer
    ) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String node : nodeOrder) {
            indegree.putIfAbsent(node, 0);
            adjacency.putIfAbsent(node, new ArrayList<>());
        }
        for (Map.Entry<String, List<String>> entry : dependenciesByConsumer.entrySet()) {
            String consumer = entry.getKey();
            if (!adjacency.containsKey(consumer)) {
                continue;
            }
            for (String dependency : entry.getValue()) {
                if (dependency == null || dependency.equals(consumer) || !adjacency.containsKey(dependency)) {
                    continue;
                }
                adjacency.get(dependency).add(consumer);
                indegree.merge(consumer, 1, Integer::sum);
            }
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.addLast(entry.getKey());
            }
        }

        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            ordered.add(current);
            for (String consumer : adjacency.get(current)) {
                int next = indegree.get(consumer) - 1;
                indegree.put(consumer, next);
                if (next == 0) {
                    ready.addLast(consumer);
                }
            }
        }
        return ordered;
    }

    private static Map<String, Set<String>> consumerAdjacency(
            List<String> nodes,
            Map<String, List<String>> dependencies
    ) {
        Map<String, Set<String>> consumersOf = new LinkedHashMap<>();
        for (String node : nodes) {
            consumersOf.put(node, new LinkedHashSet<>());
        }
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            String consumer = entry.getKey();
            for (String dependency : entry.getValue()) {
                if (dependency != consumer && consumersOf.containsKey(dependency)) {
                    consumersOf.get(dependency).add(consumer);
                }
            }
        }
        return consumersOf;
    }

    private static List<LinkedHashSet<Integer>> indexConsumersOf(
            List<String> nodes,
            Map<String, List<String>> dependencies
    ) {
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            indexOf.put(nodes.get(index), index);
        }
        List<LinkedHashSet<Integer>> consumersOf = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            consumersOf.add(new LinkedHashSet<>());
        }
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            Integer consumerIndex = indexOf.get(entry.getKey());
            for (String dependency : entry.getValue()) {
                Integer dependencyIndex = indexOf.get(dependency);
                if (consumerIndex != null
                        && dependencyIndex != null
                        && !dependencyIndex.equals(consumerIndex)) {
                    consumersOf.get(dependencyIndex).add(consumerIndex);
                }
            }
        }
        return consumersOf;
    }

    private static List<Set<String>> referenceStronglyConnectedComponents(
            List<String> nodes,
            Map<String, Set<String>> consumersOf
    ) {
        Map<String, Integer> discovery = new HashMap<>();
        Map<String, Integer> lowlink = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        int[] clock = {0};
        List<Set<String>> components = new ArrayList<>();
        for (String node : nodes) {
            if (!discovery.containsKey(node)) {
                referenceTarjanDfs(
                        node, consumersOf, discovery, lowlink, onStack, stack, clock, components
                );
            }
        }
        return components;
    }

    private static void referenceTarjanDfs(
            String node,
            Map<String, Set<String>> consumersOf,
            Map<String, Integer> discovery,
            Map<String, Integer> lowlink,
            Set<String> onStack,
            Deque<String> stack,
            int[] clock,
            List<Set<String>> components
    ) {
        discovery.put(node, clock[0]);
        lowlink.put(node, clock[0]);
        clock[0]++;
        stack.push(node);
        onStack.add(node);

        for (String successor : consumersOf.getOrDefault(node, Set.of())) {
            if (!discovery.containsKey(successor)) {
                referenceTarjanDfs(
                        successor, consumersOf, discovery, lowlink, onStack, stack, clock, components
                );
                lowlink.put(node, Math.min(lowlink.get(node), lowlink.get(successor)));
            } else if (onStack.contains(successor)) {
                lowlink.put(node, Math.min(lowlink.get(node), discovery.get(successor)));
            }
        }

        if (lowlink.get(node).equals(discovery.get(node))) {
            Set<String> component = new HashSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            components.add(component);
        }
    }
}
