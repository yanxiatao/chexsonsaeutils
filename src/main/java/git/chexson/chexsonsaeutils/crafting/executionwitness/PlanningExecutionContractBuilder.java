package git.chexson.chexsonsaeutils.crafting.executionwitness;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

public final class PlanningExecutionContractBuilder {

    private PlanningExecutionContractBuilder() {
    }

    @Nullable
    public static PlanningInputWitness build(GraphPlanningState state, CraftGraph graph) {
        var planned = new IdentityHashMap<IPatternDetails, PatternAllocation>();
        for (var entry : state.crafts().entrySet()) {
            long executions = entry.getValue() == null ? 0L : entry.getValue();
            GraphPattern pattern = graph.patternsBySource().get(entry.getKey());
            if (executions <= 0L || pattern == null
                    || !(entry.getKey().getDefinition() instanceof AEItemKey definition)
                    || pattern.inputs().size() != entry.getKey().getInputs().length) {
                return null;
            }
            for (GraphInput input : pattern.inputs()) {
                if (input.mode() != GraphInput.Mode.NORMAL) {
                    return null;
                }
            }
            planned.put(entry.getKey(), new PatternAllocation(pattern, definition, executions));
        }
        if (planned.isEmpty()) {
            return null;
        }

        // Ownership 必须按 allocation 事件的真实顺序 append：执行侧的 FIFO
        // 所有权门（ownsRequiredFront 的 reservedBeforeCurrent）依赖先到先得，
        // 按样板注册序重排会把后到 consumer 的配额排到先到 producer 前面。
        var ownership = new LinkedHashMap<AEKey, List<PlanningInputWitness.OwnershipRun>>();
        var expectedCrafts = new LinkedHashMap<AEItemKey, Long>();
        var patternRuns = new ArrayList<PlanningInputWitness.PatternInputRuns>();
        try {
            for (var allocation : state.inputAllocations()) {
                PatternAllocation owner = planned.get(allocation.consumer());
                if (owner == null || allocation.inputSlot() < 0
                        || allocation.inputSlot() >= owner.pattern.inputs().size()) {
                    return null;
                }
                GraphInput input = owner.pattern.inputs().get(allocation.inputSlot());
                if (!accepts(input, allocation.exactKey(), allocation.templateAmount(), graph)) {
                    return null;
                }
                owner.add(allocation.inputSlot(), allocation.exactKey(),
                        allocation.templateAmount(), allocation.units());
                appendOwnership(ownership.computeIfAbsent(allocation.exactKey(), ignored -> new ArrayList<>()),
                        owner.definition, allocation.inputSlot(),
                        allocation.templateAmount(), allocation.units());
            }

            for (IPatternDetails source : graph.patternRegistrationOrder()) {
                PatternAllocation allocation = planned.remove(source);
                if (allocation == null) {
                    continue;
                }
                if (expectedCrafts.putIfAbsent(allocation.definition, allocation.executions) != null) {
                    return null;
                }
                for (int slot = 0; slot < allocation.pattern.inputs().size(); slot++) {
                    long expected = Math.multiplyExact(
                            allocation.pattern.inputs().get(slot).multiplier(), allocation.executions);
                    if (allocation.slotUnits[slot] != expected) {
                        return null;
                    }
                }
                patternRuns.add(new PlanningInputWitness.PatternInputRuns(
                        allocation.definition, allocation.pattern.inputs().size(), allocation.runs));
            }
            if (!planned.isEmpty()) {
                return null;
            }
        } catch (ArithmeticException overflow) {
            return null;
        }
        var ownershipRuns = new ArrayList<PlanningInputWitness.ExactKeyOwnership>();
        ownership.forEach((key, runs) -> ownershipRuns.add(
                new PlanningInputWitness.ExactKeyOwnership(key, runs)));
        return new PlanningInputWitness(true, expectedCrafts, patternRuns, ownershipRuns);
    }

    private static boolean accepts(GraphInput input, AEKey key, long templateAmount, CraftGraph graph) {
        if (input.extractCandidates() == null) {
            return input.key().equals(key) && input.amountPerCraft() == templateAmount;
        }
        for (GraphInput.Candidate candidate : input.extractCandidates()) {
            if (candidate.amount() != templateAmount) {
                continue;
            }
            for (AEKey variant : graph.classVariants().getOrDefault(
                    candidate.key(), List.of(candidate.key()))) {
                if (variant.equals(key)
                        && (input.validVariants() == null || input.validVariants().contains(variant))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void appendOwnership(
            List<PlanningInputWitness.OwnershipRun> runs,
            AEItemKey definition,
            int slot,
            long templateAmount,
            long units
    ) {
        int lastIndex = runs.size() - 1;
        if (lastIndex >= 0) {
            var last = runs.get(lastIndex);
            if (last.patternDefinition().equals(definition) && last.inputSlot() == slot
                    && last.templateAmount() == templateAmount) {
                runs.set(lastIndex, new PlanningInputWitness.OwnershipRun(
                        definition, slot, templateAmount,
                        Math.addExact(last.templateUnitCount(), units)));
                return;
            }
        }
        runs.add(new PlanningInputWitness.OwnershipRun(definition, slot, templateAmount, units));
    }

    private static final class PatternAllocation {
        final GraphPattern pattern;
        final AEItemKey definition;
        final long executions;
        final long[] slotUnits;
        final List<PlanningInputWitness.InputRun> runs = new ArrayList<>();

        PatternAllocation(GraphPattern pattern, AEItemKey definition, long executions) {
            this.pattern = pattern;
            this.definition = definition;
            this.executions = executions;
            this.slotUnits = new long[pattern.inputs().size()];
        }

        void add(int slot, AEKey key, long templateAmount, long units) {
            slotUnits[slot] = Math.addExact(slotUnits[slot], units);
            int lastIndex = runs.size() - 1;
            if (lastIndex >= 0) {
                var last = runs.get(lastIndex);
                if (last.inputSlot() == slot && last.exactKey().equals(key)
                        && last.templateAmount() == templateAmount) {
                    runs.set(lastIndex, new PlanningInputWitness.InputRun(
                            slot, key, templateAmount,
                            Math.addExact(last.templateUnitCount(), units)));
                    return;
                }
            }
            runs.add(new PlanningInputWitness.InputRun(slot, key, templateAmount, units));
        }
    }
}
