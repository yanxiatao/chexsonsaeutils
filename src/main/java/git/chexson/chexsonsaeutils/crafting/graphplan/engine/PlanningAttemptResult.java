package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;

/**
 * Outcome of one graph-planning attempt: either a finished plan, or a decline
 * reason telling the caller to fall through to AE2's native tree.
 * A budget exhaustion additionally records which phase ran out — that is a
 * reading for the fallback log line and nothing more: the fallback has exactly
 * one destination, so no attempt result picks a route by phase.
 *
 * <p>{@code declinedLedger} 只挂在<b>守恒闸门判真缺料</b>那一类判决上：被拒的那份账本
 * 与逐键缺口。末级算法（NATIVE）同样规划不出时，它是"材料不足"这份回执唯一的数字来源
 * ——没有它，玩家能看到的就只剩一个异常。其余判决（预算耗尽、图 overflow、编译失败、
 * 模糊槽排水）一律不带账本，回落链一字不变。
 */
public record PlanningAttemptResult(
        boolean handled,
        @Nullable ICraftingPlan plan,
        @Nullable String declineReason,
        @Nullable GraphPlanningBudget.Phase budgetExhaustedPhase,
        @Nullable SolveResult declinedLedger
) {

    public static PlanningAttemptResult handled(ICraftingPlan plan) {
        return new PlanningAttemptResult(true, plan, null, null, null);
    }

    public static PlanningAttemptResult declined(String reason) {
        return new PlanningAttemptResult(false, null, reason, null, null);
    }

    /** 带真缺料账本的判决：DECLINE 不变，只是"还差多少"不再跟着一起丢。 */
    public static PlanningAttemptResult declinedWithLedger(SolveResult declined) {
        return new PlanningAttemptResult(false, null, declined.declineReason(), null, declined);
    }

    public static PlanningAttemptResult budgetExhausted(String reason,
            GraphPlanningBudget.Phase phase) {
        return new PlanningAttemptResult(false, null, reason, phase, null);
    }
}
