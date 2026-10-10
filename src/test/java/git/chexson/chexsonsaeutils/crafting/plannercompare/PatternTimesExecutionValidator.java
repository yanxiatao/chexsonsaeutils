package git.chexson.chexsonsaeutils.crafting.plannercompare;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.CapturedPlanFixture;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PatternTimesExecutionValidator {
    private static final BigInteger ZERO = BigInteger.ZERO;

    private PatternTimesExecutionValidator() {
    }

    static Validation validate(
            CapturedPlanFixture.Loaded fixture,
            ICraftingPlan plan,
            CraftGraph graph
    ) {
        if (plan == null) {
            return Validation.failed("null plan");
        }
        if (plan.simulation()) {
            return Validation.failed("simulation plan");
        }
        if (!plan.missingItems().isEmpty()) {
            return Validation.failed("non-empty missing items");
        }
        if (!fixture.target().equals(plan.finalOutput().what())
                || plan.finalOutput().amount() < fixture.amount()) {
            return Validation.failed("wrong final output");
        }

        Map<AEKey, BigInteger> available = new HashMap<>();
        for (var entry : plan.usedItems()) {
            if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                return Validation.failed("invalid used item");
            }
            long captured = fixture.stock().get(entry.getKey());
            if (entry.getLongValue() > captured) {
                return Validation.failed("network extraction exceeds captured stock");
            }
            add(available, entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
        }
        for (var entry : plan.emittedItems()) {
            if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                return Validation.failed("invalid emitted item");
            }
            if (!fixture.craftingService().canEmitFor(entry.getKey())) {
                return Validation.failed("emitted item is not emittable");
            }
            add(available, entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
        }

        boolean recursiveGraph = graph.cycleKind() != CraftGraph.CycleKind.NONE;
        if (recursiveGraph) {
            if (!(plan instanceof RecursivePlanContract recursive)
                    || !recursive.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return Validation.failed("recursive graph lacks recursive contract");
            }
            if (recursive.chexsonsaeutils$dyeableRecursiveFinalOutputAmount()
                    != plan.finalOutput().amount()) {
                return Validation.failed("recursive final output contract mismatch");
            }
            for (var entry : recursive.chexsonsaeutils$dyeableRecursiveInitialItems()) {
                if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                    return Validation.failed("invalid recursive initial item");
                }
                if (available.getOrDefault(entry.getKey(), ZERO)
                        .compareTo(BigInteger.valueOf(entry.getLongValue())) < 0) {
                    return Validation.failed("recursive initial item was not sourced");
                }
            }
        }

        Map<IPatternDetails, Long> remaining = new IdentityHashMap<>();
        for (var entry : plan.patternTimes().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                return Validation.failed("invalid patternTimes entry");
            }
            remaining.put(entry.getKey(), entry.getValue());
        }
        if (remaining.isEmpty()) {
            return Validation.failed("empty patternTimes");
        }

        Set<IPatternDetails> known = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        known.addAll(graph.patternRegistrationOrder());
        for (IPatternDetails pattern : remaining.keySet()) {
            if (!known.contains(pattern)) {
                return Validation.failed("patternTimes contains an unreachable pattern");
            }
        }

        List<PatternSpec> ordered = new ArrayList<>(remaining.size());
        for (IPatternDetails pattern : graph.patternRegistrationOrder()) {
            if (!remaining.containsKey(pattern)) {
                continue;
            }
            PatternSpec spec = compile(pattern, graph);
            if (spec == null) {
                return Validation.failed("cannot resolve pattern inputs for " + patternLabel(graph, pattern));
            }
            ordered.add(spec);
        }

        Map<AEKey, BigInteger> candidateCosts = new HashMap<>();
        for (PatternSpec pattern : ordered) {
            for (SlotSpec slot : pattern.slots()) {
                if (!slot.hasAlternatives()) {
                    continue;
                }
                for (Template template : slot.templates()) {
                    BigInteger previous = candidateCosts.putIfAbsent(
                            template.key(), BigInteger.valueOf(template.amount()));
                    if (previous != null && !previous.equals(BigInteger.valueOf(template.amount()))) {
                        return Validation.failed("incompatible candidate template amounts");
                    }
                }
            }
        }

        SchedulingResult scheduling = schedule(available, ordered, remaining, candidateCosts);
        if (!scheduling.complete()) {
            return Validation.failed("patternTimes cannot be scheduled");
        }

        BigInteger producedTarget = scheduling.available().getOrDefault(fixture.target(), ZERO);
        if (producedTarget.compareTo(BigInteger.valueOf(plan.finalOutput().amount())) < 0) {
            return Validation.failed("scheduled firings do not produce the promised final output");
        }
        return new Validation(true, null, producedTarget);
    }

    private static SchedulingResult schedule(
            Map<AEKey, BigInteger> initial,
            List<PatternSpec> patterns,
            Map<IPatternDetails, Long> planned,
            Map<AEKey, BigInteger> candidateCosts
    ) {
        Map<AEKey, BigInteger> available = new HashMap<>(initial);
        Map<IPatternDetails, Long> remaining = new IdentityHashMap<>(planned);
        Set<IPatternDetails> started = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        while (!remaining.isEmpty()) {
            PatternSpec selected = null;
            long selectedTimes = 0L;
            Allocation selectedAllocation = null;
            for (PatternSpec pattern : patterns) {
                long left = remaining.getOrDefault(pattern.pattern(), 0L);
                if (left <= 0L || !canPossiblyFire(available, pattern, 1L, started.contains(pattern.pattern()))) {
                    continue;
                }
                boolean wasStarted = started.contains(pattern.pattern());
                Allocation full = allocate(
                        available, patterns, remaining, started, candidateCosts, pattern, left);
                if (full.feasible()) {
                    selected = pattern;
                    selectedTimes = left;
                    selectedAllocation = full;
                    break;
                }
                Allocation one = allocate(
                        available, patterns, remaining, started, candidateCosts, pattern, 1L);
                if (!one.feasible()) {
                    continue;
                }
                long low = 1L;
                long high = left - 1L;
                Allocation best = one;
                while (low < high) {
                    long middle = low + (high - low + 1L) / 2L;
                    Allocation candidate = allocate(
                            available, patterns, remaining, started, candidateCosts, pattern, middle);
                    if (candidate.feasible()) {
                        low = middle;
                        best = candidate;
                    } else {
                        high = middle - 1L;
                    }
                }
                if (!wasStarted && low <= 0L) {
                    continue;
                }
                selected = pattern;
                selectedTimes = low;
                selectedAllocation = best;
                break;
            }
            if (selected == null || selectedAllocation == null) {
                return new SchedulingResult(false, available);
            }
            boolean wasStarted = started.contains(selected.pattern());
            if (!fire(available, selected, selectedTimes, wasStarted, selectedAllocation.consumed())) {
                return new SchedulingResult(false, available);
            }
            started.add(selected.pattern());
            long left = remaining.get(selected.pattern());
            if (selectedTimes == left) {
                remaining.remove(selected.pattern());
            } else {
                remaining.put(selected.pattern(), left - selectedTimes);
            }
        }
        return new SchedulingResult(true, available);
    }

    private static boolean canPossiblyFire(
            Map<AEKey, BigInteger> available,
            PatternSpec pattern,
            long times,
            boolean started
    ) {
        Map<AEKey, BigInteger> fixed = immediateFixedDemand(pattern, times, started);
        for (var entry : fixed.entrySet()) {
            if (available.getOrDefault(entry.getKey(), ZERO).compareTo(entry.getValue()) < 0) {
                return false;
            }
        }
        for (SlotSpec slot : pattern.slots()) {
            if (!slot.hasAlternatives()) {
                continue;
            }
            BigInteger needed = BigInteger.valueOf(slot.multiplier()).multiply(BigInteger.valueOf(times));
            BigInteger possible = ZERO;
            for (Template template : slot.templates()) {
                BigInteger present = available.getOrDefault(template.key(), ZERO)
                        .subtract(fixed.getOrDefault(template.key(), ZERO)).max(ZERO);
                possible = possible.add(present.divide(BigInteger.valueOf(template.amount())));
            }
            if (possible.compareTo(needed) < 0) {
                return false;
            }
        }
        return true;
    }

    private static Allocation allocate(
            Map<AEKey, BigInteger> available,
            List<PatternSpec> patterns,
            Map<IPatternDetails, Long> remaining,
            Set<IPatternDetails> started,
            Map<AEKey, BigInteger> candidateCosts,
            PatternSpec immediatePattern,
            long immediateTimes
    ) {
        Map<AEKey, BigInteger> totalSupply = new HashMap<>(available);
        Map<AEKey, BigInteger> fixedDemand = new HashMap<>();
        for (PatternSpec pattern : patterns) {
            long left = remaining.getOrDefault(pattern.pattern(), 0L);
            if (left <= 0L) {
                continue;
            }
            boolean wasStarted = started.contains(pattern.pattern());
            accumulateFutureOutputs(totalSupply, pattern, left, wasStarted);
            accumulateFixedDemand(fixedDemand, pattern, left, wasStarted);
        }
        for (var entry : fixedDemand.entrySet()) {
            BigInteger present = totalSupply.getOrDefault(entry.getKey(), ZERO);
            if (present.compareTo(entry.getValue()) < 0) {
                return Allocation.failed();
            }
            add(totalSupply, entry.getKey(), entry.getValue().negate());
        }

        boolean immediateStarted = started.contains(immediatePattern.pattern());
        Map<AEKey, BigInteger> immediateFixed = immediateFixedDemand(
                immediatePattern, immediateTimes, immediateStarted);
        Map<AEKey, BigInteger> currentAfterFixed = new HashMap<>(available);
        for (var entry : immediateFixed.entrySet()) {
            BigInteger present = currentAfterFixed.getOrDefault(entry.getKey(), ZERO);
            if (present.compareTo(entry.getValue()) < 0) {
                return Allocation.failed();
            }
            add(currentAfterFixed, entry.getKey(), entry.getValue().negate());
        }

        BigFlow flow = new BigFlow();
        int source = flow.addNode();
        int sink = flow.addNode();
        Map<AEKey, Integer> keyNodes = new HashMap<>();
        Map<AEKey, Integer> immediateNodes = new HashMap<>();
        for (var entry : candidateCosts.entrySet()) {
            AEKey key = entry.getKey();
            BigInteger totalCapacity = totalSupply.getOrDefault(key, ZERO).divide(entry.getValue());
            if (totalCapacity.signum() <= 0) {
                continue;
            }
            int keyNode = flow.addNode();
            keyNodes.put(key, keyNode);
            flow.addEdge(source, keyNode, totalCapacity);
            BigInteger currentCapacity = currentAfterFixed.getOrDefault(key, ZERO).divide(entry.getValue());
            if (currentCapacity.signum() > 0) {
                int immediateNode = flow.addNode();
                immediateNodes.put(key, immediateNode);
                flow.addEdge(keyNode, immediateNode, currentCapacity);
            }
        }

        BigInteger totalDemand = ZERO;
        List<FlowBinding> bindings = new ArrayList<>();
        for (PatternSpec pattern : patterns) {
            long left = remaining.getOrDefault(pattern.pattern(), 0L);
            if (left <= 0L) {
                continue;
            }
            for (SlotSpec slot : pattern.slots()) {
                if (!slot.hasAlternatives()) {
                    continue;
                }
                BigInteger immediateUnits = pattern == immediatePattern
                        ? BigInteger.valueOf(slot.multiplier()).multiply(BigInteger.valueOf(immediateTimes))
                        : ZERO;
                BigInteger allUnits = BigInteger.valueOf(slot.multiplier()).multiply(BigInteger.valueOf(left));
                BigInteger futureUnits = allUnits.subtract(immediateUnits);
                if (immediateUnits.signum() > 0) {
                    int slotNode = flow.addNode();
                    flow.addEdge(slotNode, sink, immediateUnits);
                    totalDemand = totalDemand.add(immediateUnits);
                    for (Template template : slot.templates()) {
                        Integer keyNode = immediateNodes.get(template.key());
                        if (keyNode != null) {
                            BigFlow.Edge edge = flow.addEdge(keyNode, slotNode, immediateUnits);
                            bindings.add(new FlowBinding(template.key(), edge));
                        }
                    }
                }
                if (futureUnits.signum() > 0) {
                    int slotNode = flow.addNode();
                    flow.addEdge(slotNode, sink, futureUnits);
                    totalDemand = totalDemand.add(futureUnits);
                    for (Template template : slot.templates()) {
                        Integer keyNode = keyNodes.get(template.key());
                        if (keyNode != null) {
                            flow.addEdge(keyNode, slotNode, futureUnits);
                        }
                    }
                }
            }
        }
        if (!flow.maxFlow(source, sink).equals(totalDemand)) {
            return Allocation.failed();
        }
        Map<AEKey, BigInteger> consumed = new HashMap<>();
        for (FlowBinding binding : bindings) {
            BigInteger units = flow.flow(binding.edge());
            if (units.signum() > 0) {
                add(consumed, binding.key(), units.multiply(candidateCosts.get(binding.key())));
            }
        }
        return new Allocation(true, consumed);
    }

    private static void accumulateFixedDemand(
            Map<AEKey, BigInteger> demand,
            PatternSpec pattern,
            long times,
            boolean started
    ) {
        BigInteger count = BigInteger.valueOf(times);
        for (SlotSpec slot : pattern.slots()) {
            Template template = slot.templates().getFirst();
            BigInteger once = BigInteger.valueOf(template.amount())
                    .multiply(BigInteger.valueOf(slot.multiplier()));
            switch (slot.mode()) {
                case NORMAL -> {
                    if (!slot.hasAlternatives()) {
                        add(demand, template.key(), once.multiply(count));
                    }
                }
                case FINITE_USE -> add(demand, template.key(), once.multiply(count));
                case RETURNED, CONSUMED_RETURNING -> {
                    if (!started) {
                        add(demand, template.key(), once);
                    }
                }
            }
        }
    }

    private static Map<AEKey, BigInteger> immediateFixedDemand(
            PatternSpec pattern,
            long times,
            boolean started
    ) {
        Map<AEKey, BigInteger> demand = new HashMap<>();
        BigInteger count = BigInteger.valueOf(times);
        for (SlotSpec slot : pattern.slots()) {
            Template template = slot.templates().getFirst();
            BigInteger once = BigInteger.valueOf(template.amount())
                    .multiply(BigInteger.valueOf(slot.multiplier()));
            switch (slot.mode()) {
                case NORMAL -> {
                    if (!slot.hasAlternatives()) {
                        add(demand, template.key(), once.multiply(count));
                    }
                }
                case FINITE_USE -> add(demand, template.key(), once.multiply(count));
                case RETURNED, CONSUMED_RETURNING -> {
                    if (!started) {
                        add(demand, template.key(), once);
                    }
                }
            }
        }
        return demand;
    }

    private static void accumulateFutureOutputs(
            Map<AEKey, BigInteger> supply,
            PatternSpec pattern,
            long times,
            boolean started
    ) {
        BigInteger count = BigInteger.valueOf(times);
        for (var entry : pattern.produced().entrySet()) {
            add(supply, entry.getKey(), entry.getValue().multiply(count));
        }
        for (SlotSpec slot : pattern.slots()) {
            switch (slot.mode()) {
                case FINITE_USE -> add(supply, slot.remainderKey(),
                        BigInteger.valueOf(slot.multiplier()).multiply(count));
                case RETURNED -> {
                    if (!started) {
                        add(supply, slot.remainderKey(), BigInteger.valueOf(slot.multiplier()));
                    }
                }
                case CONSUMED_RETURNING -> {
                    BigInteger recycledFirings = started ? count : count.subtract(BigInteger.ONE);
                    if (recycledFirings.signum() > 0) {
                        Template template = slot.templates().getFirst();
                        BigInteger consumed = BigInteger.valueOf(template.amount())
                                .multiply(BigInteger.valueOf(slot.multiplier()))
                                .multiply(recycledFirings);
                        add(supply, template.key(), consumed.negate());
                    }
                }
                case NORMAL -> {
                }
            }
        }
    }

    private static boolean fire(
            Map<AEKey, BigInteger> available,
            PatternSpec pattern,
            long times,
            boolean started,
            Map<AEKey, BigInteger> alternativeConsumption
    ) {
        Map<AEKey, BigInteger> fixed = immediateFixedDemand(pattern, times, started);
        for (var entry : alternativeConsumption.entrySet()) {
            add(fixed, entry.getKey(), entry.getValue());
        }
        for (var entry : fixed.entrySet()) {
            BigInteger present = available.getOrDefault(entry.getKey(), ZERO);
            if (present.compareTo(entry.getValue()) < 0) {
                return false;
            }
            add(available, entry.getKey(), entry.getValue().negate());
        }
        accumulateFutureOutputs(available, pattern, times, started);
        for (BigInteger amount : available.values()) {
            if (amount.signum() < 0) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static PatternSpec compile(IPatternDetails pattern, CraftGraph graph) {
        Map<AEKey, BigInteger> produced = new LinkedHashMap<>();
        for (GenericStack output : pattern.getOutputs()) {
            if (!valid(output)) {
                return null;
            }
            add(produced, output.what(), BigInteger.valueOf(output.amount()));
        }

        IPatternDetails.IInput[] rawInputs = pattern.getInputs();
        var compiled = graph.patternsBySource().get(pattern);
        if (compiled == null || compiled.inputs().size() != rawInputs.length) {
            return null;
        }
        List<SlotSpec> slots = new ArrayList<>(rawInputs.length);
        for (int slot = 0; slot < rawInputs.length; slot++) {
            IPatternDetails.IInput raw = rawInputs[slot];
            if (raw == null || raw.getMultiplier() <= 0L) {
                return null;
            }
            GraphInput input = compiled.inputs().get(slot);
            List<Template> templates = templates(input, graph);
            if (templates.isEmpty()) {
                return null;
            }
            if (input.mode() != GraphInput.Mode.NORMAL && templates.size() != 1) {
                return null;
            }
            slots.add(new SlotSpec(
                    input.mode(), templates, input.multiplier(), input.remainderKey()));
        }
        return new PatternSpec(pattern, List.copyOf(slots), Map.copyOf(produced));
    }

    private static List<Template> templates(GraphInput input, CraftGraph graph) {
        if (input.extractCandidates() == null) {
            return List.of(new Template(input.key(), input.amountPerCraft()));
        }
        Set<Template> templates = new LinkedHashSet<>();
        Set<AEKey> valid = input.validVariants();
        for (GraphInput.Candidate candidate : input.extractCandidates()) {
            List<AEKey> members = graph.classVariants().get(candidate.key());
            if (members == null) {
                if (valid.contains(candidate.key())) {
                    templates.add(new Template(candidate.key(), candidate.amount()));
                }
                continue;
            }
            for (AEKey member : members) {
                if (valid.contains(member)) {
                    templates.add(new Template(member, candidate.amount()));
                }
            }
        }
        return List.copyOf(templates);
    }

    private static boolean valid(@Nullable GenericStack stack) {
        return stack != null && stack.what() != null && stack.amount() > 0L;
    }

    private static String patternLabel(CraftGraph graph, IPatternDetails pattern) {
        int index = graph.patternRegistrationOrder().indexOf(pattern);
        return index >= 0 ? "p:" + index : "unknown";
    }

    private static void add(Map<AEKey, BigInteger> values, AEKey key, BigInteger amount) {
        BigInteger updated = values.getOrDefault(key, ZERO).add(amount);
        if (updated.signum() == 0) {
            values.remove(key);
        } else {
            values.put(key, updated);
        }
    }

    record Validation(boolean valid, @Nullable String reason, BigInteger producedTarget) {
        private static Validation failed(String reason) {
            return new Validation(false, reason, ZERO);
        }
    }

    private record SchedulingResult(boolean complete, Map<AEKey, BigInteger> available) {
    }

    private record PatternSpec(
            IPatternDetails pattern,
            List<SlotSpec> slots,
            Map<AEKey, BigInteger> produced
    ) {
    }

    private record SlotSpec(
            GraphInput.Mode mode,
            List<Template> templates,
            long multiplier,
            @Nullable AEKey remainderKey
    ) {
        private boolean hasAlternatives() {
            return mode == GraphInput.Mode.NORMAL && templates.size() > 1;
        }
    }

    private record Template(AEKey key, long amount) {
    }

    private record Allocation(boolean feasible, Map<AEKey, BigInteger> consumed) {
        private static Allocation failed() {
            return new Allocation(false, Map.of());
        }
    }

    private record FlowBinding(AEKey key, BigFlow.Edge edge) {
    }

    private static final class BigFlow {
        private final List<List<Edge>> graph = new ArrayList<>();
        private int[] level;
        private int[] next;

        private int addNode() {
            graph.add(new ArrayList<>());
            return graph.size() - 1;
        }

        private Edge addEdge(int from, int to, BigInteger capacity) {
            Edge forward = new Edge(to, graph.get(to).size(), capacity);
            Edge reverse = new Edge(from, graph.get(from).size(), ZERO);
            graph.get(from).add(forward);
            graph.get(to).add(reverse);
            return forward;
        }

        private BigInteger maxFlow(int source, int sink) {
            BigInteger total = ZERO;
            while (buildLevels(source, sink)) {
                next = new int[graph.size()];
                BigInteger sent;
                do {
                    sent = send(source, sink, null);
                    total = total.add(sent);
                } while (sent.signum() > 0);
            }
            return total;
        }

        private boolean buildLevels(int source, int sink) {
            level = new int[graph.size()];
            Arrays.fill(level, -1);
            level[source] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.addLast(source);
            while (!queue.isEmpty()) {
                int node = queue.removeFirst();
                for (Edge edge : graph.get(node)) {
                    if (edge.capacity.signum() > 0 && level[edge.to] < 0) {
                        level[edge.to] = level[node] + 1;
                        queue.addLast(edge.to);
                    }
                }
            }
            return level[sink] >= 0;
        }

        private BigInteger send(int node, int sink, @Nullable BigInteger limit) {
            if (node == sink) {
                return limit == null ? ZERO : limit;
            }
            List<Edge> edges = graph.get(node);
            while (next[node] < edges.size()) {
                Edge edge = edges.get(next[node]);
                if (edge.capacity.signum() > 0 && level[edge.to] == level[node] + 1) {
                    BigInteger allowed = limit == null ? edge.capacity : limit.min(edge.capacity);
                    BigInteger sent = send(edge.to, sink, allowed);
                    if (sent.signum() > 0) {
                        edge.capacity = edge.capacity.subtract(sent);
                        Edge reverse = graph.get(edge.to).get(edge.reverse);
                        reverse.capacity = reverse.capacity.add(sent);
                        return sent;
                    }
                }
                next[node]++;
            }
            return ZERO;
        }

        private BigInteger flow(Edge edge) {
            return graph.get(edge.to).get(edge.reverse).capacity;
        }

        private static final class Edge {
            private final int to;
            private final int reverse;
            private BigInteger capacity;

            private Edge(int to, int reverse, BigInteger capacity) {
                this.to = to;
                this.reverse = reverse;
                this.capacity = capacity;
            }
        }
    }
}
