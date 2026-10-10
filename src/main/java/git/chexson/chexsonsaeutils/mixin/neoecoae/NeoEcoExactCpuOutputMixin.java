package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOExactInventory;
import cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPU;
import cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPULogic;
import cn.dancingsnow.neoecoae.crafting.execution.ExecutingCraftingJob;
import cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingJobLifecycle;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoExactCompletedWork;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoExactOutputTransfer;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** 装配机真实产物进入 ECO 精确等待账；普通任务继续经官方 long 接收口。 */
@Pseudo
@Mixin(value = ECOCraftingCPULogic.class, remap = false)
public abstract class NeoEcoExactCpuOutputMixin implements DeclaredBatchOutputReceiver {
    @Shadow @Final private ECOCraftingCPU cpu;
    @Shadow public abstract ExecutingCraftingJob getJob();
    @Shadow public abstract appeng.crafting.inv.ListCraftingInventory getInventory();
    @Shadow public abstract long insert(AEKey key, long amount, Actionable mode);
    @org.spongepowered.asm.mixin.injection.Inject(method = "storeItems",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void chexsonsaeutils$returnExactInventory(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        var grid = cpu.getGrid();
        if (getJob() != null || grid == null || !(getInventory() instanceof ECOExactInventory inventory)
                || !inventory.isEnabled()
                || !(grid.getStorageService().getInventory() instanceof
                        git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion)) return;
        NeoEcoExactOutputTransfer.returnInventory(inventory, grid.getStorageService().getInventory(), cpu.getActionSource());
        cpu.markDirty();
        callback.cancel();
    }

    @Override public BigInteger receive(IGrid sourceGrid, AEKey key, BigInteger available) {
        var current = getJob();
        if (sourceGrid == null || sourceGrid != cpu.getGrid() || current == null) return BigInteger.ZERO;
        var access = (NeoEcoJobOutputAccessor) current;
        if (!access.chexsonsaeutils$exactOrder()
                || ECOCraftingJobLifecycle.isTerminated(cpu.getLevel(), access.chexsonsaeutils$link().getCraftingID()))
            return BigInteger.ZERO;
        return NeoEcoExactOutputTransfer.receive((ECOExactInventory) access.chexsonsaeutils$waitingFor(),
                (ECOExactInventory) getInventory(), key, available,
                window -> insert(key, window, Actionable.MODULATE), amount -> {
                    ((NeoEcoExactCompletedWork) access.chexsonsaeutils$timeTracker())
                            .chexsonsaeutils$recordCompleted(amount, key.getType());
                    cpu.markDirty();
                });
    }
}
