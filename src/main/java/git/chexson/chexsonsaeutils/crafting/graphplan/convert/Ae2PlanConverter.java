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
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
// 超 long 显示旁路：宽域真值随计划对象一起出门（载体），不再走按对象身份的旁路注册表。
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import org.jetbrains.annotations.Nullable;

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

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private Ae2PlanConverter() {
    }

    public static ICraftingPlan toPlan(SolveResult result, CraftGraph graph) {
        var state = result.state();
        // 超 long 显示旁路的唯一取数点：此刻三张宽域账与份数宽账还是精确真值，下面就要被
        // Ae2Amounts.toLong 饱和进 KeyCounter(long) / Map<IPatternDetails,Long> 交给 AE2。
        // 快照挂在计划对象上出门，读侧按 WideAmountPlanCarrier 取回——纯只读捕获，
        // 不参与任何规划/提交判定。
        WideLedgerSnapshot wideLedgers = WideLedgerSnapshot.capture(
                state.missing(), state.used(), state.emitted());
        WideCraftSnapshot wideCrafts = WideCraftSnapshot.capture(state.craftsWide());
        // 无限源键集同一条挂法出门：求解途中按键长进这张表，出计划这一刻才是全的。
        // 执行侧拿它豁免配额/占料封顶，缺口在取料点就地认量（不经池子），读不到就是空集（老口径）。
        var unboundedKeys = graph.supplyTruth().unboundedSupplies();
        logWideCrafts(graph, wideCrafts);
        // 不可压缩环也可能由有限的普通样板序列完成；执行侧仍需递归契约，
        // 不能仅用 ringExtractions 判断是否包装。
        if (graph.cycleKind() != CraftGraph.CycleKind.NONE) {
            return toRecursivePlan(result, graph, state, wideLedgers, wideCrafts, unboundedKeys);
        }

        // 交出去的这份份数字典按<b>注册序</b>物化（不是主表的哈希序）：它是执行侧任务表的底序，
        // 而顺位链上只有一把尺（RecursiveTaskOrdering）——底序若随样板哈希变，账面数字就无从复现。
        // 见 WideCraftCounter#orderedLongView 那条：样板哈希取到 Item 的身份哈希，跨 JVM 会话即翻。
        var crafts = state.craftsWide().orderedLongView();
        CraftingPlan base = new CraftingPlan(
                new GenericStack(graph.target(), result.finalAmount()),
                Ae2Amounts.toLong(state.byteAccount().value()),
                result.simulation(),
                graph.multiplePaths(),
                state.usedItems(),
                state.emittedItems(),
                state.missingItems(),
                crafts);
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log(
                    "convert base target=%s finalAmount=%d bytes=%d simulation=%s ringRewrite=false missing=%s"
                            + " recursive=false wideCraftKeys=%d",
                    graph.target(), result.finalAmount(), base.bytes(), base.simulation(),
                    base.missingItems(), wideCrafts.size());
        }
        // 无环分支同样是我们的图规划出口（实机 ae2:creative_storage_cell x 1000000 的
        // lava 1.6e19 缺料就出自这条），有溢出键、超界份数或无限源键才套一层不含执行契约的透明载体：
        // 它不命中 RecursivePlanContract 的任何判据，规划/提交语义与直接返回 base 一致。
        return result.exactFinalAmount().isFast() && wideLedgers.isEmpty() && wideCrafts.isEmpty() && unboundedKeys.isEmpty()
                ? base
                : new WideAmountCraftingPlan(base, wideLedgers, wideCrafts, unboundedKeys,
                        result.exactFinalAmount().isFast() ? null : result.exactFinalAmount());
    }

    /**
     * 份数超界的出口证据行：AE2 的 {@code patternTimes} 是 {@code Map<IPatternDetails, Long>}，
     * 交出去的是向上饱和的代表值，真值只能靠这份旁路账读回。常态（无超界份数）零输出。
     */
    private static void logWideCrafts(CraftGraph graph, WideCraftSnapshot wideCrafts) {
        if (wideCrafts.isEmpty()) {
            return;
        }
        LOGGER.info("Graph plan wide craft counts: target={} wideCraftKeys={} wideCrafts={}"
                + " （patternTimes 交饱和代表值，真值随计划对象出门）",
                graph.target(), wideCrafts.size(), wideCrafts);
    }

    /** 递归计划：每个账本只物化一次，物化结果直接持有（AE2 CraftingPlan 是纯字段 record）。 */
    private static ICraftingPlan toRecursivePlan(SolveResult result, CraftGraph graph,
            GraphPlanningState state, WideLedgerSnapshot wideLedgers, WideCraftSnapshot wideCrafts,
            java.util.Set<AEKey> unboundedKeys) {
        var crafts = state.craftsWide().orderedLongView();
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
                            + " usedKeys=%d emittedKeys=%d crafts=%d wideCraftKeys=%d",
                    graph.target(), result.finalAmount(), bytes, missingItems.size(), detail,
                    combinedUsedItems.size(), emittedItems.size(), crafts.size(), wideCrafts.size());
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
        // 配给表在规划出口定版：与守恒闸门读同一份供给、同一个候选宇宙、同一条顺位，
        // 执行侧照这份数取料，不再由提交期按现场量重算第二份归属。
        // 这张表是宽域账本（不是 long 通道）：发数已经走宽域，份数合计没有上界，
        // 在这里窄化就把执行侧配额的上界截断了——所以它不需要像 usedItems 那样交饱和代表值。
        // 同一趟账还分出封套那一本（模糊槽首位候选的许可上界）：它照样在下发的表里，
        // 但执行侧的 hole= 读数要拿它当减数，配额夹照旧读整本，所以必须随契约一起出门。
        var materialBill = MaterialClosureGate.buildMaterialPlan(
                state, graph, result.exactFinalAmount());
        return new RecursiveCraftingPlan(merged, recursiveInitialItems, internalItems,
                result.finalAmount(), selected, materialBill.assigned(), wideLedgers, wideCrafts,
                unboundedKeys, materialBill.envelopes(),
                result.exactFinalAmount().isFast() ? null : result.exactFinalAmount());
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
                // #219 K1 条目②判定为不可达，故这一眼留在 long 域、不造宽域替身：
                // 它是"单发单槽"每发用量（不乘发数），越界要求一次点火在一格里吃掉
                // > 9.22e18 枚——同形乘算在 GraphCompiler:436 的催化臂是直接 DECLINE 的。
                // 且这本账的唯一读点是 host-batch 聚合样板重建（HostChainSegmenter
                // #describeAggregationInputs → toScaledStacks/advanceExecutionBalance），
                // 整条是 long + GenericStack 的 AE2 边界账；执行侧"一发吃几枚"的判据
                // 早就改读 SlotAccount.unitsPerFiringExact（BigInteger），不经这本。
                // 把这里改宽只会在同一句里再窄化回去 —— 那是第二本账，不是同一把尺。
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
