package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingSubmitResult;
import cn.dancingsnow.neoecoae.crafting.execution.ECOExternalCpuSupport;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeoEco 分阶段/精确任务契约必须留在 ECO 执行器，不能按其饱和 long 投影执行。 */
@Mixin(value = ParallelCraftingCpuCluster.class, remap = false)
public abstract class NeoEcoParallelPlanAdmissionMixin {
    @Inject(method = {"submitJob", "submitPartialJob"}, at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$validatePlan(IGrid grid, ICraftingPlan plan, ICraftingRequester requester,
            IActionSource source, long tick, CallbackInfoReturnable<ICraftingSubmitResult> result) {
        if (!ECOExternalCpuSupport.supportsPlan(plan)) {
            com.mojang.logging.LogUtils.getLogger().warn(
                    "NeoEco 计划 {} 需要 ECO 执行器，拒绝并行 CPU 对 long 投影的截断执行", plan.getClass().getName());
            result.setReturnValue(CraftingSubmitResult.INCOMPLETE_PLAN);
        }
    }
}
