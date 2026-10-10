package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import cn.dancingsnow.neoecoae.api.me.network.ECOCraftingNetworkSettings;
import cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPU;
import git.chexson.chexsonsaeutils.crafting.contract.WideRootCraftingCpu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** 用官方父订单接入计算子系统，不把其他规划器的任务向量伪装成 ECO 执行计划。 */
@Pseudo
@Mixin(value = ECOCraftingCPU.class, remap = false)
public abstract class NeoEcoWideCraftingCpuMixin implements WideRootCraftingCpu, git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver {
    @Override public java.math.BigInteger receive(IGrid grid, appeng.api.stacks.AEKey key,
            java.math.BigInteger available) {
        var cpu = (ECOCraftingCPU) (Object) this;
        return ((git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver) cpu.getLogic())
                .receive(grid, key, available);
    }
    @Override public boolean canSubmitWideJob(ICraftingPlan plan) {
        return ((ICraftingCPU) (Object) this).getAvailableStorage() > 0;
    }
    @Override public ICraftingSubmitResult submitWideJob(IGrid grid, ICraftingPlan plan, IActionSource source) {
        return git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoWideCraftingSubmission.submit(
                plan, (ECOCraftingNetworkSettings) grid.getCraftingService(),
                carrier -> grid.getCraftingService().submitJob(
                        carrier, null, (ICraftingCPU) (Object) this, true, source));
    }
}
