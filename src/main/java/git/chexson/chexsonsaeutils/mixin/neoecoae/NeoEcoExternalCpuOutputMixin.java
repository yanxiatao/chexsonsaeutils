package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.api.me.output.ECOExternalCpuOutputRouter;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelActiveCpuHandle;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeoEco 工作单元按任务 UUID 交回并行 CPU，禁止退化为同键任意任务接收。 */
@Pseudo
@Mixin(value = ECOExternalCpuOutputRouter.class, remap = false)
public abstract class NeoEcoExternalCpuOutputMixin {
    @Inject(method = "insert", at = @At("HEAD"), cancellable = true)
    private static void chexsonsaeutils$insert(ICraftingCPU cpu, UUID jobId, AEKey key, long amount,
            Actionable mode, CallbackInfoReturnable<Long> result) {
        if (cpu instanceof ParallelCraftingCPU parallel && parallel.isActiveVirtualCpu()) {
            var handle = new ParallelActiveCpuHandle(parallel.cluster(), jobId);
            result.setReturnValue(handle.insert(key, amount, mode, parallel.cluster().source()));
        }
    }
}
