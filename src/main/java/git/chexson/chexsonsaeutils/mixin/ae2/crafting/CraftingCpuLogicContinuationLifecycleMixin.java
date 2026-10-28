package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 拆除 CPU 后不再有轮询机会，必须在作业仍持有 link 时按结束 ID 清理续接明细。 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicContinuationLifecycleMixin {
    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    private CraftingCPUCluster cluster;

    @WrapMethod(method = "finishJob")
    private void chexsonsaeutils$clearFinishedContinuation(boolean success, Operation<Void> original) {
        var craftId = job == null ? null : ((ExecutingCraftingJobAccessor) job).getLink().getCraftingID();
        try {
            original.call(success);
        } finally {
            // 异常发生在 job 清空之前时仍须保留记录；清空后的库存返还异常不改变结束事实。
            if (craftId != null && job == null) {
                CraftingContinuationTracker.onJobFinished(cluster, craftId);
            }
        }
    }
}
