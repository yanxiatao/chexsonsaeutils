package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.depgraph.DependencyGraphs;

/**
 * Capacity-walk cycle analysis over the compiled graph (Phase 2d B1).
 *
 * <p>Edges mirror the {@code LinearPlanner.CapacityUpperBound} recursion
 * exactly: producer edges come from every input the firing bound actually
 * requests (RETURNED and CONSUMED_RETURNING slots are skipped there), fuzzy
 * slots fan out to all candidate keys, and each ring macro contributes an
 * entry → net-input edge — the only cycle source left after the compiler's
 * ancestor-chain filtering, since macro dependency keys expand with fresh
 * paths.
 *
 * <p>The analysis is diagnostics and foundation only: route sets are never
 * restricted, so solved plans stay bit-identical (R1/R9 contract). Later
 * layers (X2 universe preclassification) reuse the SCC walk.
 */
public final class ActiveRouteSelection {

    private ActiveRouteSelection() {
    }

    public record Selection(boolean acyclic, Set<AEKey> cyclicKeys, Set<GraphPattern> cycleParticipants) {
    }

    public static Selection analyze(CraftGraph graph) {
        Map<AEKey, Integer> ordinals = new LinkedHashMap<>();
        List<AEKey> keysByOrdinal = new ArrayList<>();
        List<LinkedHashSet<Integer>> successors = new ArrayList<>();

        for (var entry : graph.producersByOutput().entrySet()) {
            int source = ordinalOf(ordinals, keysByOrdinal, successors, entry.getKey());
            for (var pattern : entry.getValue()) {
                for (var target : walkTargets(pattern)) {
                    successors.get(source).add(ordinalOf(ordinals, keysByOrdinal, successors, target));
                }
            }
        }
        for (var entry : graph.ringMacrosByEntry().entrySet()) {
            int source = ordinalOf(ordinals, keysByOrdinal, successors, entry.getKey());
            for (var macro : entry.getValue()) {
                for (var stack : macro.netInputs()) {
                    successors.get(source).add(ordinalOf(ordinals, keysByOrdinal, successors, stack.getKey()));
                }
            }
        }
        ordinalOf(ordinals, keysByOrdinal, successors, graph.target());

        int nodeCount = successors.size();
        var components = DependencyGraphs.stronglyConnectedComponents(successors, nodeCount);

        Set<AEKey> cyclicKeys = new LinkedHashSet<>();
        boolean[] cyclicComponent = new boolean[components.size()];
        for (int component = 0; component < components.size(); component++) {
            var members = components.get(component);
            boolean cyclic = members.size() > 1;
            if (!cyclic) {
                int member = members.getFirst();
                cyclic = successors.get(member).contains(member);
            }
            if (cyclic) {
                cyclicComponent[component] = true;
                for (int member : members) {
                    cyclicKeys.add(keysByOrdinal.get(member));
                }
            }
        }
        if (cyclicKeys.isEmpty()) {
            return new Selection(true, Set.of(), Set.of());
        }

        int[] componentOf = new int[nodeCount];
        for (int component = 0; component < components.size(); component++) {
            for (int member : components.get(component)) {
                componentOf[member] = component;
            }
        }
        Set<GraphPattern> participants = new LinkedHashSet<>();
        for (var entry : graph.producersByOutput().entrySet()) {
            Integer source = ordinals.get(entry.getKey());
            if (source == null || !cyclicComponent[componentOf[source]]) {
                continue;
            }
            for (var pattern : entry.getValue()) {
                for (var target : walkTargets(pattern)) {
                    Integer targetOrdinal = ordinals.get(target);
                    if (targetOrdinal != null && componentOf[targetOrdinal] == componentOf[source]) {
                        participants.add(pattern);
                        break;
                    }
                }
            }
        }
        return new Selection(false, Set.copyOf(cyclicKeys), Set.copyOf(participants));
    }

    private static List<AEKey> walkTargets(GraphPattern pattern) {
        List<AEKey> targets = new ArrayList<>();
        for (var input : pattern.inputs()) {
            if (input.mode() == GraphInput.Mode.RETURNED
                    || input.mode() == GraphInput.Mode.CONSUMED_RETURNING
                    || input.selfBlocked()) {
                // RETURNED / CONSUMED_RETURNING never bound the firing count;
                // a selfBlocked slot is a pattern-less leaf drawing class stock
                // only — neither contributes a capacity-walk edge.
                continue;
            }
            if (input.extractCandidates() != null) {
                for (var candidate : input.extractCandidates()) {
                    targets.add(candidate.key());
                }
            } else {
                targets.add(input.key());
            }
        }
        return targets;
    }

    private static int ordinalOf(
            Map<AEKey, Integer> ordinals,
            List<AEKey> keysByOrdinal,
            List<LinkedHashSet<Integer>> successors,
            AEKey key
    ) {
        Integer existing = ordinals.get(key);
        if (existing != null) {
            return existing;
        }
        int ordinal = keysByOrdinal.size();
        ordinals.put(key, ordinal);
        keysByOrdinal.add(key);
        successors.add(new LinkedHashSet<>());
        return ordinal;
    }
}
