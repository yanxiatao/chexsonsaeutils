package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPatternFlows;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;

/**
 * Phase 2d B4 —— 后置守恒闸门（照 neoecoae {@code ECOPlanMaterialValidator}
 * 的立场）：solve 接受结果前逐键校验 {@code supply >= demand}，不符即
 * DECLINE 回落树路径。闭式捷径里的符号错误（A0 那类倒扣、环宏口径漂移、
 * SCC 与净流量口径不一致）都会在出门前被拦住，绝不让不可能的计划进
 * {@code CraftingPlan}。
 *
 * <p>只在 SUCCESS 时跑，不参与搜索，成本 O(patternTimes × 槽数)。
 * 账目逐 mode 复刻 {@code LinearPlanner.fire} 的口径，宽域精确累计：
 * <ul>
 * <li>supply = used（requiredExtract 峰值 ≥ 真实提取，方向保守）+ emitted
 * + ringExtractions（环催化物种子）+ Σ crafts 的产出/余料归还贡献；</li>
 * <li>demand = Σ crafts 的输入消耗 + finalOutput + missing（模拟轮缺口）。</li>
 * </ul>
 *
 * <p>产出侧的 CONSUMED_RETURNING 闭式修正与 {@code fire} 逐位一致：
 * 自喂键计 {@code produced×times − consumed×(times−1)}，同键多输出栈只
 * 修正第一个；A0 净增长门保证该式非负。环记账项按成员样板全量计入——
 * 环内键被环内产出抵消，催化物种子由 ringExtractions 侧供给，外部催化
 * 物的多余消耗只会让 demand 虚低（漏报方向，安全），不产生误报。
 */
public final class MaterialClosureGate {

    private MaterialClosureGate() {
    }

    /**
     * @return null 表示守恒闭合；否则返回违规键描述，调用方 DECLINE。
     */
    public static @Nullable String validate(GraphPlanningState state, CraftGraph graph, long finalAmount) {
        var patternsBySource = graph.patternsBySource();
        // 环记账项的 crafts times 是 ceil(scale) 的聚合，与环宏的实际流量口径
        // （netInputs×ceil、netOutputs×floor、催化物种子×1）在分数 scale 下
        // 本就分歧——这是图/树对拍锁定的既有设计，不是符号错误。环成员条目
        // 及其触及的键整体退出校验域，环外键保持全量校验。
        // 无环宏的图（绝大多数）不必跑这两趟全图收集
        boolean hasRings = !graph.ringMacrosByEntry().isEmpty();
        var ringMembers = hasRings ? collectRingMembers(graph) : java.util.Set.<IPatternDetails>of();
        var ringKeys = hasRings
                ? collectRingKeys(graph, patternsBySource, ringMembers)
                : java.util.Set.<AEKey>of();

        var supply = new WideKeyCounter();
        var demand = new WideKeyCounter();
        for (var entry : state.crafts().entrySet()) {
            if (ringMembers.contains(entry.getKey())) {
                continue;
            }
            var pattern = patternsBySource.get(entry.getKey());
            if (pattern == null) {
                return "material closure violated: pattern not on graph "
                        + entry.getKey().getDefinition();
            }
            accumulate(pattern, entry.getValue(), supply, demand);
        }
        addToSupply(supply, state.usedItems());
        addToSupply(supply, state.emittedItems());
        for (var key : state.ringExtractions().keys()) {
            supply.add(key, state.ringExtractions().getAmount(key));
        }
        demand.add(graph.target(), finalAmount);
        // missing 是「需求未被供给覆盖」的缺口记录，属供给侧平衡项：
        // supply + missing >= demand 才是模拟轮的守恒式。
        addToSupply(supply, state.missingItems());
        for (var key : demand.keys()) {
            if (ringKeys.contains(key)) {
                continue;
            }
            if (supply.getAmount(key).compareTo(demand.getAmount(key)) < 0) {
                return "material closure violated: " + key
                        + " supply=" + supply.getAmount(key)
                        + " demand=" + demand.getAmount(key)
                        + " missing=" + state.missingItems().get(key)
                        + " used=" + state.usedAmount(key);
            }
        }
        return null;
    }

    private static void addToSupply(WideKeyCounter target, KeyCounter source) {
        for (var entry : source) {
            target.add(entry.getKey(), entry.getLongValue());
        }
    }

    /** 全部环宏的成员样板集合——crafts 里的环记账项按宏口径另行承载。 */
    private static Set<IPatternDetails> collectRingMembers(CraftGraph graph) {
        var members = new HashSet<IPatternDetails>();
        for (var macros : graph.ringMacrosByEntry().values()) {
            for (var macro : macros) {
                members.addAll(macro.executionRatio().keySet());
            }
        }
        return members;
    }

    /**
     * 环宏触及的键全集（成员样板的输入/输出 + 宏净输入/净输出/催化物）——
     * 这些键的供需由环宏的净流量口径承载，退出逐键守恒校验域。
     */
    private static Set<AEKey> collectRingKeys(CraftGraph graph,
            Map<IPatternDetails, GraphPattern> patternsBySource,
            Set<IPatternDetails> ringMembers) {
        var keys = new HashSet<AEKey>();
        for (var details : ringMembers) {
            var pattern = patternsBySource.get(details);
            if (pattern == null) {
                continue;
            }
            for (var input : pattern.inputs()) {
                keys.add(input.key());
            }
            for (var out : pattern.allOutputs()) {
                keys.add(out.what());
            }
        }
        for (var macros : graph.ringMacrosByEntry().values()) {
            for (var macro : macros) {
                for (var stack : macro.netInputs()) {
                    keys.add(stack.getKey());
                }
                for (var stack : macro.netOutputs()) {
                    keys.add(stack.getKey());
                }
                for (var stack : macro.catalysts()) {
                    keys.add(stack.getKey());
                }
                keys.addAll(macro.startupSeeds().keySet());
            }
        }
        return keys;
    }

    /** 一个样板 {@code times} 次执行的供给/需求贡献，口径与 {@code fire} 一致。 */
    private static void accumulate(GraphPattern pattern, long times,
            WideKeyCounter supply, WideKeyCounter demand) {
        // long 快路径：三数乘积在 long 域内精确时零分配；溢出才回落宽路径。
        // 饱和方向与出口窄化一致（向上饱和），校验只朝更松的方向偏离，不误报。
        for (var input : pattern.inputs()) {
            switch (input.mode()) {
                case FINITE_USE -> demand.add(input.key(),
                        mul3(input.amountPerCraft(), input.multiplier(), times));
                case NORMAL -> {
                    if (input.extractCandidates() == null) {
                        demand.add(input.key(),
                                mul3(input.amountPerCraft(), input.multiplier(), times));
                    }
                    // 多候选输入：实际提取键由贪婪迭代在候选间分配，逐键需求
                    // 无法重建——退出校验域（demand 不计、supply 侧 used 保留，
                    // 只朝「supply >= demand」更松的方向偏离，不产生误报）。
                }
                case RETURNED, CONSUMED_RETURNING -> {
                    // 批量发射只有首次真实提取，其余由闭式补偿覆盖
                    long once = WideMath.wideMul(input.amountPerCraft(), input.multiplier());
                    demand.add(input.key(), once);
                    if (input.mode() == GraphInput.Mode.RETURNED) {
                        supply.add(input.remainderKey(), input.multiplier());
                    }
                }
            }
        }
        for (var input : pattern.inputs()) {
            if (input.mode() == GraphInput.Mode.FINITE_USE) {
                supply.add(input.remainderKey(), WideMath.wideMul(input.multiplier(), times));
            }
        }
        boolean hasSelfFeeding = pattern.hasSelfFeedingInput();
        Set<AEKey> corrected = null;
        for (var out : pattern.allOutputs()) {
            if (!hasSelfFeeding) {
                supply.add(out.what(), WideMath.wideMul(out.amount(), times));
                continue;
            }
            PlanAmount amount = PlanAmount.of(out.amount()).multiply(PlanAmount.of(times));
            PlanAmount consumedPerCraft = GraphPatternFlows.selfConsumedPerCraft(pattern, out.what());
            if (consumedPerCraft.signum() > 0) {
                if (corrected == null) {
                    corrected = new HashSet<>();
                }
                if (corrected.add(out.what())) {
                    PlanAmount produced = GraphPatternFlows.outputPerCraftOf(pattern, out.what()).multiply(PlanAmount.of(times));
                    PlanAmount consumed = consumedPerCraft.multiply(PlanAmount.of(times - 1));
                    amount = produced.subtract(consumed);
                } else {
                    amount = PlanAmount.ZERO;
                }
            }
            supply.add(out.what(), amount);
        }
    }

    /** 三数乘积的 long 快路径；溢出时经宽路径饱和窄化（伴随诊断）。 */
    private static long mul3(long a, long b, long c) {
        long ab = WideMath.wideMul(a, b);
        // wideMul 饱和只会 saturate 到 ±MAX：任一操作数已饱和则乘积必溢出，
        // 直接回落宽路径得精确饱和值（与原逐级宽乘逐位一致）。
        if (ab == Long.MAX_VALUE || ab == Long.MIN_VALUE) {
            return git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts.toLong(
                    WideMath.mul(a, b).multiply(git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount.of(c)));
        }
        return WideMath.wideMul(ab, c);
    }

}
