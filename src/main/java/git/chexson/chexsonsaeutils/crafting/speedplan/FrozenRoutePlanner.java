package git.chexson.chexsonsaeutils.crafting.speedplan;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import git.chexson.chexsonsaeutils.crafting.executionwitness.WitnessedCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingContraction;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 冻结路线直线规划器。
 *
 * <p>每次编译构成一个 provider epoch，并把每个键看到的首个 producer 固化。
 * 求解只执行编译后的 int/long 数组程序，不做图搜索、Map 查询或递归。
 */
public final class FrozenRoutePlanner {
    private static final AtomicLong EPOCHS = new AtomicLong();
    private static final ThreadLocal<Workspace> WORKSPACES = ThreadLocal.withInitial(Workspace::new);

    public record CompileStats(long providerEpoch, int keys, int edges, int candidates, int routes, int sccs) {
    }

    public record SolveStats(int keys, int edges, int candidates, int routes, int sccs,
            long operations, long tasks, int missingKinds) {
    }

    public record SolveResult(ICraftingPlan plan, SolveStats stats) {
    }

    public static final class CompiledProgram {
        private final AEKey[] keys;
        private final IPatternDetails[] producers;
        private final long[] outputAmounts;
        private final boolean[] emittable;
        private final int[] keyRouteOffsets;
        private final int[] routeCandidateOffsets;
        private final int[] candidateKeys;
        private final long[] candidateAmounts;
        private final long[] routeMultipliers;
        private final boolean[] routeTerminal;
        private final int[] programKeys;
        private final Object2IntOpenHashMap<AEKey> keyIds;
        private final boolean multiplePaths;
        private final CraftGraph.CycleKind cycleKind;
        private final CompileStats stats;

        private CompiledProgram(AEKey[] keys, IPatternDetails[] producers, long[] outputAmounts,
                boolean[] emittable, int[] keyRouteOffsets, int[] routeCandidateOffsets,
                int[] candidateKeys, long[] candidateAmounts, long[] routeMultipliers,
                boolean[] routeTerminal, int[] programKeys, Object2IntOpenHashMap<AEKey> keyIds,
                boolean multiplePaths, CraftGraph.CycleKind cycleKind, CompileStats stats) {
            this.keys = keys;
            this.producers = producers;
            this.outputAmounts = outputAmounts;
            this.emittable = emittable;
            this.keyRouteOffsets = keyRouteOffsets;
            this.routeCandidateOffsets = routeCandidateOffsets;
            this.candidateKeys = candidateKeys;
            this.candidateAmounts = candidateAmounts;
            this.routeMultipliers = routeMultipliers;
            this.routeTerminal = routeTerminal;
            this.programKeys = programKeys;
            this.keyIds = keyIds;
            this.multiplePaths = multiplePaths;
            this.cycleKind = cycleKind;
            this.stats = stats;
        }

        public CompileStats stats() {
            return stats;
        }

        public int keyCount() {
            return keys.length;
        }

        public CraftGraph.CycleKind cycleKind() {
            return cycleKind;
        }

        public boolean hasCycles() {
            return cycleKind != CraftGraph.CycleKind.NONE;
        }
    }

    /** 编译并求解，适合作为统一公开入口。 */
    public ICraftingPlan plan(ICraftingService service, KeyCounter stock, AEKey target,
            long amount, @Nullable Level level) {
        return solve(compile(service, target, level), stock, amount).plan();
    }

    /** provider epoch 由本次编译冻结；同一 CompiledProgram 的 producer 选择不再变化。 */
    public CompiledProgram compile(ICraftingService service, AEKey target, @Nullable Level level) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(target, "target");
        var builder = new Compiler(service, level);
        builder.discover(target);
        return builder.finish();
    }

    /** 执行根到叶直线程序；数组经 generation stamp 复用，热核心无 Map 和递归。 */
    public SolveResult solve(CompiledProgram program, KeyCounter stock, long amount) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(stock, "stock");
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must be non-negative");
        }

        Workspace workspace = WORKSPACES.get();
        workspace.begin(program.keys.length, program.producers.length);
        for (var entry : stock) {
            int id = program.keyIds.getInt(entry.getKey());
            if (id >= 0 && entry.getLongValue() > 0L) {
                workspace.setAvailable(id, entry.getLongValue());
            }
        }
        workspace.setDemand(0, amount);

        long operations = 0L;
        long tasks = 0L;
        for (int instruction = 0; instruction < program.programKeys.length; instruction++) {
            int keyId = program.programKeys[instruction];
            long demand = workspace.demand(keyId);
            if (demand == 0L) {
                continue;
            }
            operations++;
            long available = keyId == 0 ? 0L : workspace.available(keyId);
            long used = Math.min(demand, available);
            if (used != 0L) {
                workspace.setAvailable(keyId, available - used);
                workspace.addUsed(keyId, used);
                demand -= used;
            }
            if (demand == 0L) {
                continue;
            }
            if (program.emittable[keyId]) {
                workspace.addEmitted(keyId, demand);
                continue;
            }
            IPatternDetails producer = program.producers[keyId];
            if (producer == null) {
                workspace.addMissing(keyId, demand);
                continue;
            }

            long runs = ceilDivPositive(demand, program.outputAmounts[keyId]);
            workspace.addCraft(keyId, runs);
            tasks = Math.addExact(tasks, runs);
            int route = program.keyRouteOffsets[keyId];
            int routeEnd = program.keyRouteOffsets[keyId + 1];
            for (; route < routeEnd; route++) {
                operations++;
                long routeDemand = Math.multiplyExact(runs, program.routeMultipliers[route]);
                int candidateStart = program.routeCandidateOffsets[route];
                int candidateEnd = program.routeCandidateOffsets[route + 1];
                for (int candidate = candidateStart; candidate < candidateEnd && routeDemand != 0L;
                        candidate++) {
                    int candidateKey = program.candidateKeys[candidate];
                    long unit = program.candidateAmounts[candidate];
                    long exactAvailable = workspace.available(candidateKey);
                    long exactUnits = exactAvailable / unit;
                    long consumedUnits = Math.min(routeDemand, exactUnits);
                    if (consumedUnits != 0L) {
                        long consumed = Math.multiplyExact(consumedUnits, unit);
                        workspace.setAvailable(candidateKey, exactAvailable - consumed);
                        workspace.addUsed(candidateKey, consumed);
                        routeDemand -= consumedUnits;
                    }
                    operations++;
                }
                if (routeDemand != 0L) {
                    int canonical = program.candidateKeys[candidateStart];
                    long canonicalAmount = program.candidateAmounts[candidateStart];
                    long propagated = Math.multiplyExact(routeDemand, canonicalAmount);
                    if (program.routeTerminal[route]) {
                        if (program.emittable[canonical]) {
                            workspace.addEmitted(canonical, propagated);
                        } else {
                            workspace.addMissing(canonical, propagated);
                        }
                    } else {
                        workspace.addDemand(canonical, propagated);
                    }
                }
            }
        }

        var used = new KeyCounter();
        var emitted = new KeyCounter();
        var missing = new KeyCounter();
        var patternTimes = new IdentityHashMap<IPatternDetails, Long>();
        int missingKinds = 0;
        for (int keyId = 0; keyId < program.keys.length; keyId++) {
            long value = workspace.used(keyId);
            if (value != 0L) {
                used.add(program.keys[keyId], value);
            }
            value = workspace.emitted(keyId);
            if (value != 0L) {
                emitted.add(program.keys[keyId], value);
            }
            value = workspace.missing(keyId);
            if (value != 0L) {
                missing.add(program.keys[keyId], value);
                missingKinds++;
            }
            value = workspace.crafts(keyId);
            if (value != 0L) {
                patternTimes.merge(program.producers[keyId], value, Math::addExact);
            }
        }
        CompileStats compile = program.stats;
        var plan = new CraftingPlan(new GenericStack(program.keys[0], amount), tasks,
                missingKinds != 0, program.multiplePaths, used, emitted, missing,
                Collections.unmodifiableMap(patternTimes));
        ICraftingPlan resultPlan = plan;
        if (missingKinds == 0 && !patternTimes.isEmpty()) {
            var witness = buildWitness(program, workspace, patternTimes);
            if (witness != null) {
                resultPlan = new WitnessedCraftingPlan(plan, witness);
            }
        }
        var stats = new SolveStats(compile.keys(), compile.edges(), compile.candidates(),
                compile.routes(), compile.sccs(), operations, tasks, missingKinds);
        return new SolveResult(resultPlan, stats);
    }

    @Nullable
    private static PlanningInputWitness buildWitness(
            CompiledProgram program,
            Workspace workspace,
            IdentityHashMap<IPatternDetails, Long> patternTimes
    ) {
        boolean hasFuzzyInput = false;
        for (int keyId = 0; keyId < program.keys.length && !hasFuzzyInput; keyId++) {
            if (workspace.crafts(keyId) <= 0L || program.producers[keyId] == null) {
                continue;
            }
            for (int route = program.keyRouteOffsets[keyId];
                    route < program.keyRouteOffsets[keyId + 1]; route++) {
                if (program.routeCandidateOffsets[route + 1]
                        - program.routeCandidateOffsets[route] > 1) {
                    hasFuzzyInput = true;
                    break;
                }
            }
        }
        if (!hasFuzzyInput) {
            return null;
        }

        var expectedCrafts = new java.util.LinkedHashMap<AEItemKey, Long>();
        var patternRuns = new ArrayList<PlanningInputWitness.PatternInputRuns>();
        var ownership = new java.util.LinkedHashMap<AEKey,
                List<PlanningInputWitness.OwnershipRun>>();
        long[] remainingNetwork = new long[program.keys.length];
        for (int keyId = 0; keyId < program.keys.length; keyId++) {
            remainingNetwork[keyId] = workspace.used(keyId);
        }
        for (int keyId = 0; keyId < program.keys.length; keyId++) {
            long executions = workspace.crafts(keyId);
            IPatternDetails producer = program.producers[keyId];
            if (executions <= 0L || producer == null) {
                continue;
            }
            if (!(producer.getDefinition() instanceof AEItemKey definition)) {
                return null;
            }
            Long expected = patternTimes.get(producer);
            if (expected == null || expected != executions || expectedCrafts.put(definition, executions) != null) {
                return null;
            }
            IPatternDetails.IInput[] inputs = producer.getInputs();
            if (inputs.length == 0) {
                return null;
            }
            var runs = new ArrayList<PlanningInputWitness.InputRun>();
            int route = program.keyRouteOffsets[keyId];
            if (program.keyRouteOffsets[keyId + 1] - route != inputs.length) {
                return null;
            }
            for (int slot = 0; slot < inputs.length; slot++, route++) {
                long remainingUnits = Math.multiplyExact(inputs[slot].getMultiplier(), executions);
                for (int candidate = program.routeCandidateOffsets[route];
                        candidate < program.routeCandidateOffsets[route + 1] && remainingUnits > 0L;
                        candidate++) {
                    int exactKeyId = program.candidateKeys[candidate];
                    AEKey exactKey = program.keys[exactKeyId];
                    long templateAmount = program.candidateAmounts[candidate];
                    long usedUnits = 0L;
                    long plannedItems = Math.multiplyExact(remainingUnits, templateAmount);
                    long networkItems = remainingNetwork[exactKeyId];
                    if (networkItems > 0L) {
                        long fromNetwork = Math.min(plannedItems, networkItems);
                        usedUnits = fromNetwork / templateAmount;
                        remainingNetwork[exactKeyId] = Math.subtractExact(networkItems,
                                Math.multiplyExact(usedUnits, templateAmount));
                    }
                    if (usedUnits <= 0L && exactKeyId == program.candidateKeys[
                            program.routeCandidateOffsets[route]]) {
                        usedUnits = remainingUnits;
                    }
                    usedUnits = Math.min(usedUnits, remainingUnits);
                    if (usedUnits <= 0L) {
                        continue;
                    }
                    appendInputRun(runs, slot, exactKey, templateAmount, usedUnits);
                    appendOwnership(ownership, exactKey, definition, slot, templateAmount, usedUnits);
                    remainingUnits -= usedUnits;
                }
                if (remainingUnits > 0L) {
                    int canonicalId = program.candidateKeys[program.routeCandidateOffsets[route]];
                    AEKey canonical = program.keys[canonicalId];
                    long templateAmount = program.candidateAmounts[program.routeCandidateOffsets[route]];
                    appendInputRun(runs, slot, canonical, templateAmount, remainingUnits);
                    appendOwnership(ownership, canonical, definition, slot, templateAmount, remainingUnits);
                }
            }
            patternRuns.add(new PlanningInputWitness.PatternInputRuns(definition, inputs.length, runs));
        }
        var ownershipRuns = new ArrayList<PlanningInputWitness.ExactKeyOwnership>();
        ownership.forEach((key, runs) -> ownershipRuns.add(
                new PlanningInputWitness.ExactKeyOwnership(key, runs)));
        return new PlanningInputWitness(true, expectedCrafts, patternRuns, ownershipRuns);
    }

    private static void appendInputRun(
            List<PlanningInputWitness.InputRun> runs,
            int slot,
            AEKey key,
            long templateAmount,
            long units
    ) {
        int lastIndex = runs.size() - 1;
        if (lastIndex >= 0) {
            var last = runs.get(lastIndex);
            if (last.inputSlot() == slot && last.exactKey().equals(key)
                    && last.templateAmount() == templateAmount) {
                runs.set(lastIndex, new PlanningInputWitness.InputRun(slot, key, templateAmount,
                        Math.addExact(last.templateUnitCount(), units)));
                return;
            }
        }
        runs.add(new PlanningInputWitness.InputRun(slot, key, templateAmount, units));
    }

    private static void appendOwnership(
            Map<AEKey, List<PlanningInputWitness.OwnershipRun>> ownership,
            AEKey key,
            AEItemKey definition,
            int slot,
            long templateAmount,
            long units
    ) {
        var runs = ownership.computeIfAbsent(key, ignored -> new ArrayList<>());
        int lastIndex = runs.size() - 1;
        if (lastIndex >= 0) {
            var last = runs.get(lastIndex);
            if (last.patternDefinition().equals(definition) && last.inputSlot() == slot
                    && last.templateAmount() == templateAmount) {
                runs.set(lastIndex, new PlanningInputWitness.OwnershipRun(definition, slot,
                        templateAmount, Math.addExact(last.templateUnitCount(), units)));
                return;
            }
        }
        runs.add(new PlanningInputWitness.OwnershipRun(definition, slot, templateAmount, units));
    }

    private static long ceilDivPositive(long value, long divisor) {
        if (divisor <= 0L) {
            throw new ArithmeticException("producer output must be positive");
        }
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    private static final class Compiler {
        private final ICraftingService service;
        @Nullable
        private final Level level;
        private final Object2IntOpenHashMap<AEKey> ids = new Object2IntOpenHashMap<>();
        private final List<Node> nodes = new ArrayList<>();
        private final List<IPatternDetails> reachablePatterns = new ArrayList<>();
        private final Set<IPatternDetails> reachablePatternSet =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final ArrayDeque<Integer> queue = new ArrayDeque<>();
        private boolean multiplePaths;

        private Compiler(ICraftingService service, @Nullable Level level) {
            this.service = service;
            this.level = level;
            this.ids.defaultReturnValue(-1);
        }

        private void discover(AEKey target) {
            intern(target);
            while (!queue.isEmpty()) {
                int id = queue.removeFirst();
                Node node = nodes.get(id);
                Collection<IPatternDetails> choices = service.getCraftingFor(node.key);
                multiplePaths |= choices.size() > 1;
                for (IPatternDetails choice : choices) {
                    if (reachablePatternSet.add(choice)) {
                        reachablePatterns.add(choice);
                    }
                    for (IPatternDetails.IInput input : choice.getInputs()) {
                        for (GenericStack candidate : input.getPossibleInputs()) {
                            if (candidate != null && candidate.what() != null && candidate.amount() > 0L) {
                                intern(candidate.what());
                            }
                        }
                    }
                }
                if (!choices.isEmpty()) {
                    node.producer = choices.iterator().next();
                    GenericStack primary = node.producer.getPrimaryOutput();
                    if (!primary.what().equals(node.key) || primary.amount() <= 0L) {
                        throw new IllegalArgumentException("first producer has invalid primary output");
                    }
                    node.outputAmount = primary.amount();
                    for (IPatternDetails.IInput input : node.producer.getInputs()) {
                        GenericStack[] possible = input.getPossibleInputs();
                        if (possible.length == 0 || input.getMultiplier() <= 0L) {
                            throw new IllegalArgumentException("input route must have candidates and multiplier");
                        }
                        var route = new Route(input.getMultiplier());
                        for (GenericStack candidate : possible) {
                            if (candidate.amount() <= 0L) {
                                throw new IllegalArgumentException("invalid exact candidate");
                            }
                            int candidateId = intern(candidate.what());
                            route.candidates.add(new Candidate(candidateId, candidate.amount()));
                        }
                        node.routes.add(route);
                    }
                }
                node.emittable = service.canEmitFor(node.key);
            }
        }

        private int intern(AEKey key) {
            int found = ids.getInt(key);
            if (found >= 0) {
                return found;
            }
            int id = nodes.size();
            ids.put(key, id);
            nodes.add(new Node(key));
            queue.addLast(id);
            return id;
        }

        private CompiledProgram finish() {
            int keyCount = nodes.size();
            int routeCount = 0;
            int candidateCount = 0;
            for (Node node : nodes) {
                routeCount = Math.addExact(routeCount, node.routes.size());
                for (Route route : node.routes) {
                    candidateCount = Math.addExact(candidateCount, route.candidates.size());
                }
            }
            SccData scc = computeScc(nodes, candidateCount);
            int[] order = rootToLeafOrder(nodes, scc);
            var keys = new AEKey[keyCount];
            var producers = new IPatternDetails[keyCount];
            var outputs = new long[keyCount];
            var emittable = new boolean[keyCount];
            var keyRoutes = new int[keyCount + 1];
            var routeCandidates = new int[routeCount + 1];
            var candidateKeys = new int[candidateCount];
            var candidateAmounts = new long[candidateCount];
            var multipliers = new long[routeCount];
            var terminal = new boolean[routeCount];
            int routeIndex = 0;
            int candidateIndex = 0;
            for (int keyId = 0; keyId < keyCount; keyId++) {
                Node node = nodes.get(keyId);
                keys[keyId] = node.key;
                producers[keyId] = node.producer;
                outputs[keyId] = node.outputAmount;
                emittable[keyId] = node.emittable;
                keyRoutes[keyId] = routeIndex;
                for (Route route : node.routes) {
                    routeCandidates[routeIndex] = candidateIndex;
                    multipliers[routeIndex] = route.multiplier;
                    int canonical = route.candidates.get(0).keyId;
                    terminal[routeIndex] = scc.component[keyId] == scc.component[canonical];
                    for (Candidate candidate : route.candidates) {
                        candidateKeys[candidateIndex] = candidate.keyId;
                        candidateAmounts[candidateIndex] = candidate.amount;
                        candidateIndex++;
                    }
                    routeIndex++;
                }
            }
            keyRoutes[keyCount] = routeIndex;
            routeCandidates[routeCount] = candidateIndex;
            var stats = new CompileStats(EPOCHS.incrementAndGet(), keyCount, routeCount,
                    candidateCount, routeCount, scc.count);
            var frozenIds = new Object2IntOpenHashMap<AEKey>(ids);
            frozenIds.defaultReturnValue(-1);
            CraftGraph.CycleKind cycleKind = RingContraction.analyzePatterns(reachablePatterns).cycleKind();
            return new CompiledProgram(keys, producers, outputs, emittable, keyRoutes,
                    routeCandidates, candidateKeys, candidateAmounts, multipliers, terminal,
                    order, frozenIds, multiplePaths, cycleKind, stats);
        }
    }

    private static final class Node {
        private final AEKey key;
        private final List<Route> routes = new ArrayList<>();
        @Nullable
        private IPatternDetails producer;
        private long outputAmount;
        private boolean emittable;

        private Node(AEKey key) {
            this.key = key;
        }
    }

    private static final class Route {
        private final long multiplier;
        private final List<Candidate> candidates = new ArrayList<>();

        private Route(long multiplier) {
            this.multiplier = multiplier;
        }
    }

    private record Candidate(int keyId, long amount) {
    }

    private record SccData(int[] component, int count) {
    }

    /** Kosaraju 的双栈迭代实现，仅编译期执行。 */
    private static SccData computeScc(List<Node> nodes, int edgeCapacity) {
        int count = nodes.size();
        var reverse = new IntVectors(count, edgeCapacity);
        for (int from = 0; from < count; from++) {
            for (Route route : nodes.get(from).routes) {
                int to = route.candidates.get(0).keyId;
                reverse.add(to, from);
            }
        }
        reverse.freeze();

        var seen = new boolean[count];
        var finish = new int[count];
        var nodeStack = new int[count];
        var edgeStack = new int[count];
        int finished = 0;
        for (int start = 0; start < count; start++) {
            if (seen[start]) {
                continue;
            }
            int depth = 0;
            nodeStack[0] = start;
            edgeStack[0] = 0;
            seen[start] = true;
            while (depth >= 0) {
                int node = nodeStack[depth];
                int edge = edgeStack[depth];
                List<Route> routes = nodes.get(node).routes;
                if (edge < routes.size()) {
                    edgeStack[depth] = edge + 1;
                    int next = routes.get(edge).candidates.get(0).keyId;
                    if (!seen[next]) {
                        depth++;
                        nodeStack[depth] = next;
                        edgeStack[depth] = 0;
                        seen[next] = true;
                    }
                } else {
                    finish[finished++] = node;
                    depth--;
                }
            }
        }

        var component = new int[count];
        Arrays.fill(component, -1);
        int components = 0;
        for (int cursor = count - 1; cursor >= 0; cursor--) {
            int start = finish[cursor];
            if (component[start] >= 0) {
                continue;
            }
            int depth = 0;
            nodeStack[0] = start;
            component[start] = components;
            while (depth >= 0) {
                int node = nodeStack[depth--];
                for (int edge = reverse.offsets[node]; edge < reverse.offsets[node + 1]; edge++) {
                    int next = reverse.values[edge];
                    if (component[next] < 0) {
                        component[next] = components;
                        nodeStack[++depth] = next;
                    }
                }
            }
            components++;
        }
        return new SccData(component, components);
    }

    private static int[] rootToLeafOrder(List<Node> nodes, SccData scc) {
        int componentCount = scc.count;
        var componentMinKey = new int[componentCount];
        var sizes = new int[componentCount];
        Arrays.fill(componentMinKey, Integer.MAX_VALUE);
        for (int key = 0; key < nodes.size(); key++) {
            int component = scc.component[key];
            sizes[component]++;
            componentMinKey[component] = Math.min(componentMinKey[component], key);
        }
        int dagEdges = 0;
        for (int from = 0; from < nodes.size(); from++) {
            for (Route route : nodes.get(from).routes) {
                int to = route.candidates.get(0).keyId;
                if (scc.component[from] != scc.component[to]) {
                    dagEdges++;
                }
            }
        }
        var dag = new IntVectors(componentCount, dagEdges);
        var indegree = new int[componentCount];
        for (int from = 0; from < nodes.size(); from++) {
            for (Route route : nodes.get(from).routes) {
                int to = route.candidates.get(0).keyId;
                int fromComponent = scc.component[from];
                int toComponent = scc.component[to];
                if (fromComponent != toComponent) {
                    dag.add(fromComponent, toComponent);
                    indegree[toComponent]++;
                }
            }
        }
        dag.freeze();
        var ready = new int[componentCount];
        int readySize = 0;
        for (int component = 0; component < componentCount; component++) {
            if (indegree[component] == 0) {
                ready[readySize++] = component;
            }
        }
        var componentOrder = new int[componentCount];
        int ordered = 0;
        while (readySize != 0) {
            int bestAt = 0;
            for (int i = 1; i < readySize; i++) {
                if (componentMinKey[ready[i]] < componentMinKey[ready[bestAt]]) {
                    bestAt = i;
                }
            }
            int component = ready[bestAt];
            ready[bestAt] = ready[--readySize];
            componentOrder[ordered++] = component;
            for (int edge = dag.offsets[component]; edge < dag.offsets[component + 1]; edge++) {
                int next = dag.values[edge];
                if (--indegree[next] == 0) {
                    ready[readySize++] = next;
                }
            }
        }
        var order = new int[nodes.size()];
        int cursor = 0;
        for (int component : componentOrder) {
            for (int key = 0; key < nodes.size(); key++) {
                if (scc.component[key] == component) {
                    order[cursor++] = key;
                }
            }
        }
        if (cursor != nodes.size()) {
            throw new IllegalStateException("component DAG ordering failed");
        }
        return order;
    }

    private static final class IntVectors {
        private final int[] counts;
        private final int[] write;
        private final int capacity;
        private int[] offsets;
        private int[] values;
        private int[] pendingFrom;
        private int[] pendingTo;
        private int pending;

        private IntVectors(int nodes, int capacity) {
            this.counts = new int[nodes];
            this.write = new int[nodes];
            this.capacity = capacity;
            this.pendingFrom = new int[capacity];
            this.pendingTo = new int[capacity];
        }

        private void add(int from, int to) {
            pendingFrom[pending] = from;
            pendingTo[pending] = to;
            counts[from]++;
            pending++;
        }

        private void freeze() {
            offsets = new int[counts.length + 1];
            for (int i = 0; i < counts.length; i++) {
                offsets[i + 1] = offsets[i] + counts[i];
                write[i] = offsets[i];
            }
            values = new int[capacity];
            for (int i = 0; i < pending; i++) {
                values[write[pendingFrom[i]]++] = pendingTo[i];
            }
            pendingFrom = null;
            pendingTo = null;
        }
    }

    private static final class Workspace {
        private int generation;
        private int[] stamps = new int[0];
        private long[] demand = new long[0];
        private long[] available = new long[0];
        private long[] used = new long[0];
        private long[] emitted = new long[0];
        private long[] missing = new long[0];
        private long[] crafts = new long[0];

        private void begin(int keys, int ignoredRoutes) {
            if (keys > stamps.length) {
                int size = Math.max(keys, stamps.length * 2 + 8);
                stamps = Arrays.copyOf(stamps, size);
                demand = Arrays.copyOf(demand, size);
                available = Arrays.copyOf(available, size);
                used = Arrays.copyOf(used, size);
                emitted = Arrays.copyOf(emitted, size);
                missing = Arrays.copyOf(missing, size);
                crafts = Arrays.copyOf(crafts, size);
            }
            generation++;
            if (generation == 0) {
                Arrays.fill(stamps, 0);
                generation = 1;
            }
        }

        private void touch(int id) {
            if (stamps[id] == generation) {
                return;
            }
            stamps[id] = generation;
            demand[id] = available[id] = used[id] = emitted[id] = missing[id] = crafts[id] = 0L;
        }

        private long demand(int id) { touch(id); return demand[id]; }
        private long available(int id) { touch(id); return available[id]; }
        private long used(int id) { touch(id); return used[id]; }
        private long emitted(int id) { touch(id); return emitted[id]; }
        private long missing(int id) { touch(id); return missing[id]; }
        private long crafts(int id) { touch(id); return crafts[id]; }
        private void setDemand(int id, long value) { touch(id); demand[id] = value; }
        private void setAvailable(int id, long value) { touch(id); available[id] = value; }
        private void addDemand(int id, long value) { touch(id); demand[id] = Math.addExact(demand[id], value); }
        private void addUsed(int id, long value) { touch(id); used[id] = Math.addExact(used[id], value); }
        private void addEmitted(int id, long value) { touch(id); emitted[id] = Math.addExact(emitted[id], value); }
        private void addMissing(int id, long value) { touch(id); missing[id] = Math.addExact(missing[id], value); }
        private void addCraft(int id, long value) { touch(id); crafts[id] = Math.addExact(crafts[id], value); }
    }
}
