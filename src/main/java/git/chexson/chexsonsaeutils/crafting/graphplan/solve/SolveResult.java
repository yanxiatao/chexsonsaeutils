package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;

/**
 * Outcome of one level-1 solve: a populated ledger plus the amount and
 * simulation flag that go into {@code CraftingPlan.finalOutput}, or a decline
 * reason when the graph engine refuses the request.
 *
 * <p>{@code shortage} 只属于守恒闸门那条判决：逐键「还差多少」的宽域真值。
 * 拒绝照旧（{@code declineReason != null}），但末级算法也没辙时这份数就是
 * 玩家该看到的"材料不足"，而不是聊天栏里一个 {@code IllegalStateException}。
 * 其余判决（预算耗尽、图 overflow、编译失败）一律不带缺口账，行为一字不变。
 */
public record SolveResult(
        @Nullable GraphPlanningState state,
        long finalAmount,
        boolean simulation,
        @Nullable String declineReason,
        @Nullable Map<AEKey, PlanAmount> shortage,
        PlanAmount exactFinalAmount) {

    public SolveResult(GraphPlanningState state, long finalAmount, boolean simulation, String declineReason,
            Map<AEKey, PlanAmount> shortage) {
        this(state, finalAmount, simulation, declineReason, shortage, PlanAmount.of(finalAmount));
    }

    public static SolveResult success(GraphPlanningState state, long finalAmount, boolean simulation) {
        return new SolveResult(state, finalAmount, simulation, null, null);
    }
    /** 根请求使用宽域真值；long 分量仅供 AE2 的显示接口使用。 */
    public static SolveResult success(GraphPlanningState state, PlanAmount finalAmount, boolean simulation) {
        return new SolveResult(state, git.chexson.chexsonsaeutils.bigint.Ae2Amounts.toLong(finalAmount),
                simulation, null, null, finalAmount);
    }

    public static SolveResult declined(String reason) {
        return new SolveResult(null, 0, false, reason, null);
    }

    /**
     * 守恒闸门判"真缺料"：判决照旧交回（调用方照旧 DECLINE），另外把<b>那份账本本身</b>
     * 与逐键缺口带回去——闸门不放行，但"差多少"是有确定答案的事实，不该跟着判决一起丢。
     *
     * @param ledger 被判不闭合的那份账（只读地用于末端报缺料，绝不当作已完成的计划）
     */
    public static SolveResult declinedShortage(String reason, Map<AEKey, PlanAmount> shortage,
            GraphPlanningState ledger, long finalAmount) {
        return new SolveResult(ledger, finalAmount, true, reason, Map.copyOf(shortage));
    }

    /** 本判决是否带着一本能点名报给玩家的缺口账。 */
    public boolean hasShortage() {
        return shortage != null && !shortage.isEmpty() && state != null;
    }

    public SolveResult withSimulation(boolean value) {
        return new SolveResult(state, finalAmount, value, declineReason, shortage, exactFinalAmount);
    }

    public boolean hasDeclined() {
        return declineReason != null;
    }
}
