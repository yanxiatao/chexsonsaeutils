package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.core.sync.packets.CraftingStatusPacket;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatus;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = CraftingCPUMenu.class, remap = false)
public abstract class CraftingCPUMenuParallelCpuMixin {

    @Shadow(remap = false)
    private CraftingCPUCluster cpu;

    @Shadow(remap = false)
    @Final
    private IncrementalUpdateHelper incrementalUpdateHelper;

    @Shadow(remap = false)
    @Final
    private Consumer<AEKey> cpuChangeListener;

    @Unique
    private boolean cachedSuspend;

    @Shadow(remap = false)
    public CpuSelectionMode schedulingMode;

    @Shadow(remap = false)
    public boolean cantStoreItems;

    @Unique
    private ParallelCraftingCPU chexsonsaeutils$parallelCpu;

    @Inject(method = "setCPU", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$setParallelCpu(ICraftingCPU selectedCpu, CallbackInfo ci) {
        if (!(selectedCpu instanceof ParallelCraftingCPU parallelCpu)) {
            if (this.chexsonsaeutils$parallelCpu != null) {
                this.chexsonsaeutils$clearParallelCpuState();
                sendStatus((CraftingCPUMenu) (Object) this, CraftingStatus.EMPTY);
            }
            this.chexsonsaeutils$parallelCpu = null;
            return;
        }

        if (this.cpu != null) {
            this.cpu.craftingLogic.removeListener(this.cpuChangeListener);
        }
        if (this.chexsonsaeutils$parallelCpu == parallelCpu) {
            this.schedulingMode = parallelCpu.getSelectionMode();
            this.cantStoreItems = parallelCpu.isCantStoreItems();
            ci.cancel();
            return;
        }

        this.incrementalUpdateHelper.reset();
        this.cachedSuspend = parallelCpu.isSuspended();
        this.cpu = null;
        this.chexsonsaeutils$parallelCpu = parallelCpu;
        this.schedulingMode = parallelCpu.getSelectionMode();
        this.cantStoreItems = parallelCpu.isCantStoreItems();

        var menu = (CraftingCPUMenu) (Object) this;
        chexsonsaeutils$sendParallelStatus(menu, parallelCpu, true);
        ci.cancel();
    }

    @Inject(method = "cancelCrafting", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$cancelParallelCrafting(CallbackInfo ci) {
        var menu = (CraftingCPUMenu) (Object) this;
        if (menu.isClientSide() || this.chexsonsaeutils$parallelCpu == null) {
            return;
        }
        this.chexsonsaeutils$parallelCpu.cancelJob();
        ci.cancel();
    }

    @Inject(method = "m_38946_", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$broadcastParallelCpu(CallbackInfo ci) {
        if (this.chexsonsaeutils$parallelCpu == null) {
            return;
        }
        this.schedulingMode = this.chexsonsaeutils$parallelCpu.getSelectionMode();
        this.cantStoreItems = this.chexsonsaeutils$parallelCpu.isCantStoreItems();
        chexsonsaeutils$sendParallelStatus(
                (CraftingCPUMenu) (Object) this,
                this.chexsonsaeutils$parallelCpu,
                false
        );
    }

    @Unique
    private void chexsonsaeutils$clearParallelCpuState() {
        this.incrementalUpdateHelper.reset();
        this.cachedSuspend = false;
    }

    @Unique
    private void chexsonsaeutils$sendParallelStatus(
            CraftingCPUMenu menu,
            ParallelCraftingCPU parallelCpu,
            boolean force
    ) {
        CraftingStatus status = parallelCpu.createMenuStatus();
        this.cachedSuspend = false;
        sendStatus(menu, status);
    }

    @Unique
    private static void sendStatus(CraftingCPUMenu menu, CraftingStatus status) {
        if (menu.getPlayer() instanceof ServerPlayer serverPlayer) {
            appeng.core.sync.network.NetworkHandler.instance()
                    .sendTo(new CraftingStatusPacket(menu.containerId, status), serverPlayer);
        }
    }

}
