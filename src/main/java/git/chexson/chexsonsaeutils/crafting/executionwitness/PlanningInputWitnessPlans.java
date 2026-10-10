package git.chexson.chexsonsaeutils.crafting.executionwitness;

import appeng.api.networking.crafting.ICraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import org.jetbrains.annotations.Nullable;

/** 在不改变包装层类型的情况下沿委托链提取见证。 */
public final class PlanningInputWitnessPlans {

    private PlanningInputWitnessPlans() {
    }

    @Nullable
    public static PlanningInputWitness find(ICraftingPlan plan) {
        ICraftingPlan current = plan;
        var seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<ICraftingPlan, Boolean>());
        while (current != null && seen.add(current)) {
            if (current instanceof PlanningInputWitnessPlan witnessed) {
                try {
                    PlanningInputWitness result = witnessed.chexsonsaeutils$planningInputWitness();
                    if (result != null) {
                        return result;
                    }
                } catch (IllegalStateException absent) {
                    // 兼容只在委托深处携带见证的通用包装。
                }
            }
            if (current instanceof WitnessedCraftingPlan wrapped) {
                current = wrapped.delegate();
            } else if (current instanceof RecursivePlanContract recursive) {
                current = recursive.chexsonsaeutils$getDelegate();
            } else {
                break;
            }
        }
        return null;
    }
}
