package git.chexson.chexsonsaeutils.integration.craftingdisplay.neoecoae;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import cn.dancingsnow.neoecoae.mixins.ae2.accessor.CraftingPlanSummaryAccessor;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.status.PlanSummaryAmounts;

/** 复用 NeoEco 公开条目 setter，保留原概要对象及其他模组附带数据。 */
public final class NeoEcoCraftingPreview {
    private NeoEcoCraftingPreview() {}
    public static void apply(CraftingPlanSummary summary, ICraftingPlan plan) {
        if (plan instanceof WideAmountPlanCarrier)
            ((CraftingPlanSummaryAccessor) summary).neoecoae$setEntries(PlanSummaryAmounts.fromPlan(plan).entries());
    }
}
