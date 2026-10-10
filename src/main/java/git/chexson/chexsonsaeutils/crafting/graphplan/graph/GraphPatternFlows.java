package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideMath;

/**
 * 样板单次发射（per-craft）在指定键上的宽精确流量口径。
 * 线性规划求解器与守恒闸门共用同一份定义，避免两条路径各自漂移。
 */
public final class GraphPatternFlows {

    private GraphPatternFlows() {
    }

    /** 一个自喂键每次发射的催化消耗（宽精确累加）。 */
    public static PlanAmount selfConsumedPerCraft(GraphPattern pattern, AEKey key) {
        PlanAmount total = PlanAmount.ZERO;
        for (GraphInput input : pattern.inputs()) {
            if (input.mode() == GraphInput.Mode.CONSUMED_RETURNING && input.key().equals(key)) {
                total = total.add(WideMath.mul(input.amountPerCraft(), input.multiplier()));
            }
        }
        return total;
    }

    /** 一个键在全部产出栈上的每次发射产量。 */
    public static PlanAmount outputPerCraftOf(GraphPattern pattern, AEKey key) {
        PlanAmount total = PlanAmount.ZERO;
        for (var output : pattern.allOutputs()) {
            if (output.what().equals(key)) {
                total = total.add(PlanAmount.of(output.amount()));
            }
        }
        return total;
    }
}
