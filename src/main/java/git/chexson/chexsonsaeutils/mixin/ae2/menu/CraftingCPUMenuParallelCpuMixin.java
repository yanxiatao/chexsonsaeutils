package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.core.network.clientbound.CraftingStatusPacket;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
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

    @Shadow(remap = false)
    private boolean cachedSuspend;

    @Shadow(remap = false)
    public CpuSelectionMode schedulingMode;

    @Shadow(remap = false)
    public boolean cantStoreItems;

    @Unique
    private ParallelCraftingCPU chexsonsaeutils$parallelCpu;

    @Unique
    private long chexsonsaeutils$lastParallelStatusSignature = Long.MIN_VALUE;

    @Inject(method = "setCPU", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$setParallelCpu(ICraftingCPU selectedCpu, CallbackInfo ci) {
        if (!(selectedCpu instanceof ParallelCraftingCPU parallelCpu)) {
            if (this.chexsonsaeutils$parallelCpu != null) {
                this.chexsonsaeutils$clearParallelCpuState();
                chexsonsaeutils$sendStatus((CraftingCPUMenu) (Object) this, CraftingStatus.EMPTY);
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
        this.chexsonsaeutils$lastParallelStatusSignature = Long.MIN_VALUE;
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

    @Inject(method = "toggleScheduling", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$toggleParallelScheduling(CallbackInfo ci) {
        var menu = (CraftingCPUMenu) (Object) this;
        if (menu.isClientSide() || this.chexsonsaeutils$parallelCpu == null) {
            return;
        }
        this.chexsonsaeutils$parallelCpu.setSuspended(!this.chexsonsaeutils$parallelCpu.isSuspended());
        this.cachedSuspend = this.chexsonsaeutils$parallelCpu.isSuspended();
        ci.cancel();
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = false)
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
        this.chexsonsaeutils$lastParallelStatusSignature = Long.MIN_VALUE;
    }

    @Unique
    private void chexsonsaeutils$sendParallelStatus(
            CraftingCPUMenu menu,
            ParallelCraftingCPU parallelCpu,
            boolean force
    ) {
        CraftingStatus status = parallelCpu.createMenuStatus();
        long signature = chexsonsaeutils$statusSignature(status);
        if (!force && signature == this.chexsonsaeutils$lastParallelStatusSignature) {
            return;
        }
        this.chexsonsaeutils$lastParallelStatusSignature = signature;
        this.cachedSuspend = status.isSuspended();
        chexsonsaeutils$sendStatus(menu, status);
    }

    @Unique
    private static void chexsonsaeutils$sendStatus(CraftingCPUMenu menu, CraftingStatus status) {
        if (menu.getPlayer() instanceof ServerPlayer serverPlayer) {
            try {
                serverPlayer.connection.send(new CraftingStatusPacket(menu.containerId, status));
            } catch (UnsupportedOperationException ignored) {
                // GameTest mock connections can reject mod payloads that are valid for a real client.
            }
        }
    }

    @Unique
    private static long chexsonsaeutils$statusSignature(CraftingStatus status) {
        long signature = 0xcbf29ce484222325L;
        signature = chexsonsaeutils$mix(signature, status.isFullStatus() ? 1L : 0L);
        signature = chexsonsaeutils$mix(signature, status.getRemainingItemCount());
        signature = chexsonsaeutils$mix(signature, status.getStartItemCount());
        signature = chexsonsaeutils$mix(signature, status.isSuspended() ? 1L : 0L);
        signature = chexsonsaeutils$mix(signature, status.getEntries().size());
        for (CraftingStatusEntry entry : status.getEntries()) {
            signature = chexsonsaeutils$mix(signature, entry.getSerial());
            signature = chexsonsaeutils$mix(signature, entry.getStoredAmount());
            signature = chexsonsaeutils$mix(signature, entry.getActiveAmount());
            signature = chexsonsaeutils$mix(signature, entry.getPendingAmount());
            signature = chexsonsaeutils$mix(signature, entry.getWhat() == null ? 0L : entry.getWhat().hashCode());
        }
        return signature;
    }

    @Unique
    private static long chexsonsaeutils$mix(long signature, long value) {
        signature ^= value;
        return signature * 0x100000001b3L;
    }
}
