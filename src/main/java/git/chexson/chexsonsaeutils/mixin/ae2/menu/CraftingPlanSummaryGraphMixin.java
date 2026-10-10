package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import git.chexson.chexsonsaeutils.crafting.status.PlanGraph;
import org.spongepowered.asm.mixin.Mixin;

/** 在摘要转换边界保留剥壳前的原计划；合成图不受状态增强或续投开关影响。 */
@Mixin(value = CraftingPlanSummary.class, remap = false)
public abstract class CraftingPlanSummaryGraphMixin {
    @WrapMethod(method = "fromJob")
    private static CraftingPlanSummary graph(IGrid grid, IActionSource source, ICraftingPlan plan,
            Operation<CraftingPlanSummary> original) {
        var summary = original.call(grid, source, plan);
        PlanGraph.attach(summary, plan);
        return summary;
    }
}
