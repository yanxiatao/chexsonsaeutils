package git.chexson.chexsonsaeutils.crafting.graphplan.convert;

import java.util.HashMap;
import java.util.Map;


import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import git.chexson.chexsonsaeutils.crafting.executionwitness.WitnessedCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;

/**
 * Converts a level-1 {@link SolveResult} into an AE2 {@link CraftingPlan}.
 *
 * <p>The ledger already reproduces AE2's simulation accounting (requiredExtract
 * peak, emitted items, missing items, craft counts, bytes), so this is a direct
 * record assembly. When the reachable graph contains a cycle, the plan becomes
 * a {@link RecursiveCraftingPlan} carrying the exact contract fields the
 * executor consumes — combined used items with the final-output key rewritten
 * out and the ring reservations merged in, plus the per-pattern
 * single-execution input selection the formal aggregator folds
 * replacement-aware patterns with.
 */
public final class Ae2PlanConverter {

    private Ae2PlanConverter() {
    }

    public static ICraftingPlan toPlan(SolveResult result, CraftGraph graph) {
        var state = result.state();
        // 不可压缩环也可能由有限的普通样板序列完成；执行侧仍需递归契约，
        // 不能仅用 ringExtractions 判断是否包装。
        if (graph.cycleKind() != CraftGraph.CycleKind.NONE) {
            return toRecursivePlan(result, graph, state);
        }

        var crafts = new HashMap<>(state.crafts());
        CraftingPlan base = new CraftingPlan(
                new GenericStack(graph.target(), result.finalAmount()),
                Ae2Amounts.toLong(state.byteAccount().value()),
                result.simulation(),
                graph.multiplePaths(),
                state.usedItems(),
                state.emittedItems(),
                state.missingItems(),
                crafts);
        ICraftingPlan converted = base;
        PlanningInputWitness witness = result.witness();
        if (witness != null && witness.complete() && witnessMatchesCrafts(witness, crafts)) {
            converted = new WitnessedCraftingPlan(base, witness);
        }
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log(
                    "convert base target=%s finalAmount=%d bytes=%d simulation=%s ringRewrite=false missing=%s"
                            + " witness=%s recursive=false",
                    graph.target(), result.finalAmount(), base.bytes(), base.simulation(),
                    base.missingItems(), converted instanceof WitnessedCraftingPlan);
        }
        return converted;
    }

    private static boolean witnessMatchesCrafts(PlanningInputWitness witness,
            Map<IPatternDetails, Long> crafts) {
        var actual = new HashMap<AEItemKey, Long>();
        for (var entry : crafts.entrySet()) {
            if (!(entry.getKey().getDefinition() instanceof AEItemKey definition)
                    || entry.getValue() == null || entry.getValue() <= 0L) {
                return false;
            }
            try {
                actual.merge(definition, entry.getValue(), Math::addExact);
            } catch (ArithmeticException overflowed) {
                return false;
            }
        }
        return actual.equals(witness.expectedCrafts());
    }

    /** 递归计划：每个账本只物化一次，物化结果直接持有（AE2 CraftingPlan 是纯字段 record）。 */
    private static ICraftingPlan toRecursivePlan(SolveResult result, CraftGraph graph,
            GraphPlanningState state) {
        var crafts = new HashMap<>(state.crafts());
        GenericStack finalOutput = new GenericStack(graph.target(), result.finalAmount());
        long bytes = Ae2Amounts.toLong(state.byteAccount().value());

        // used − finalOutput，max 合并环初始物品（宽→窄一次完成）
        var combinedUsedItems = new KeyCounter();
        int finalId = state.idOf(graph.target());
        state.used().forEachPresentExcept(finalId,
                (key, narrowed) -> combinedUsedItems.add(key, Math.max(0L, narrowed)));
        mergeRecursiveInitialWide(combinedUsedItems, state.ringExtractions());

        // 环初始物品与内部预留来自同一张宽账本：一次遍历同时填两只不同的实例
        var recursiveInitialItems = new KeyCounter();
        var internalItems = new KeyCounter();
        state.ringExtractions().forEachPresent((key, narrowed) -> {
            if (narrowed < 0L) {
                return;
            }
            recursiveInitialItems.add(key, narrowed);
            internalItems.add(key, narrowed);
        });
        mergeInternalReserve(internalItems, state.recursiveInternalItems());

        var emittedItems = new KeyCounter();
        state.emitted().forEachPresent(
                (key, narrowed) -> emittedItems.add(key, Math.max(0L, narrowed)));
        var missingItems = new KeyCounter();
        state.missing().forEachPresent(
                (key, narrowed) -> missingItems.add(key, Math.max(0L, narrowed)));

        if (GraphDiagnostics.active()) {
            var detail = new StringBuilder();
            int shown = 0;
            for (var entry : missingItems) {
                if (shown++ >= 12) {
                    detail.append(" ...(+").append(missingItems.size() - shown + 1).append(')');
                    break;
                }
                if (shown > 1) {
                    detail.append(", ");
                }
                detail.append(entry.getKey()).append(" x ").append(entry.getLongValue());
            }
            GraphDiagnostics.log(
                    "convert recursive target=%s finalAmount=%d bytes=%d missingCount=%d missing=[%s]"
                            + " usedKeys=%d emittedKeys=%d crafts=%d",
                    graph.target(), result.finalAmount(), bytes, missingItems.size(), detail,
                    combinedUsedItems.size(), emittedItems.size(), crafts.size());
        }

        CraftingPlan merged = new CraftingPlan(
                finalOutput,
                bytes,
                result.simulation(),
                graph.multiplePaths(),
                combinedUsedItems,
                emittedItems,
                missingItems,
                crafts);
        var selected = buildSelectedPatternInputs(crafts, graph);
        return new RecursiveCraftingPlan(merged, recursiveInitialItems, internalItems,
                result.finalAmount(), selected);
    }

    /**
     * Per-pattern single-execution input selection: the compiled input choice
     * (after {@code findCraftedStack} replacement) and its per-craft amount —
     * intentionally not scaled by the firing count, matching the tree
     * planner's construction-time recording.
     */
    private static Map<AEItemKey, Map<AEKey, Long>> buildSelectedPatternInputs(
            Map<IPatternDetails, Long> crafts, CraftGraph graph) {
        Map<AEItemKey, Map<AEKey, Long>> selected = new HashMap<>();
        for (var entry : crafts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            var pattern = entry.getKey();
            if (!(pattern.getDefinition() instanceof AEItemKey definition)) {
                continue;
            }
            var compiled = graph.patternsBySource().get(pattern);
            if (compiled == null) {
                // Ring-macro executionRatio patterns are not planned through the
                // graph's per-input sites; the tree planner doesn't record them
                // either.
                continue;
            }
            // 内层查表外提：computeIfAbsent 从每槽一次降到每样板至多一次
            Map<AEKey, Long> inner = null;
            for (var input : compiled.inputs()) {
                long amount = WideMath.wideMul(input.amountPerCraft(), input.multiplier());
                if (input.key() == null || amount <= 0) {
                    continue;
                }
                if (inner == null) {
                    inner = selected.get(definition);
                    if (inner == null) {
                        inner = new HashMap<>();
                        selected.put(definition, inner);
                    }
                }
                inner.merge(input.key(), amount, Long::sum);
            }
        }
        return selected.isEmpty() ? Map.of() : Map.copyOf(selected);
    }

    /** Retention reserve, max-merged like the tree's {@code recursiveInternalItems}. */
    private static void mergeInternalReserve(KeyCounter target, WideKeyCounter reserve) {
        for (AEKey key : reserve.keys()) {
            PlanAmount amount = reserve.getAmount(key);
            if (amount.signum() > 0) {
                long narrowed = Ae2Amounts.toLong(amount);
                target.set(key, Math.max(target.get(key), narrowed));
            }
        }
    }

    private static void mergeRecursiveInitialWide(KeyCounter target, WideKeyCounter ringExtractions) {
        ringExtractions.forEachPresent((key, narrowed) -> {
            if (narrowed > 0L) {
                target.set(key, Math.max(target.get(key), narrowed));
            }
        });
    }

}
