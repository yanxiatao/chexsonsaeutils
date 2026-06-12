package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.core.network.clientbound.CraftingStatusPacket;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatus;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineCraftingTimingService;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCPUMenu.class, remap = false)
public abstract class CraftingCPUMenuFormalMachineHeartbeatMixin extends AEBaseMenu {
    @Unique
    private static final int CHEXSONSAEUTILS_FORMAL_STATUS_HEARTBEAT_INTERVAL_TICKS = 20;

    @Shadow
    @Final
    private IncrementalUpdateHelper incrementalUpdateHelper;

    @Shadow
    private CraftingCPUCluster cpu;

    @Shadow
    private boolean cachedSuspend;

    @Unique
    private boolean chexsonsaeutils$formalStatusOriginalPacketExpected;

    @Unique
    private int chexsonsaeutils$formalStatusHeartbeatCountdown;

    protected CraftingCPUMenuFormalMachineHeartbeatMixin(
            MenuType<?> menuType,
            int id,
            Inventory playerInventory,
            Object host
    ) {
        super(menuType, id, playerInventory, host);
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$captureFormalMachineOriginalStatusPacket(CallbackInfo ci) {
        CraftingCPUCluster selectedCpu = this.cpu;
        chexsonsaeutils$formalStatusOriginalPacketExpected = isServerSide()
                && selectedCpu != null
                && chexsonsaeutils$hasAe2StatusChange(selectedCpu);
    }

    @Inject(method = "broadcastChanges", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$sendFormalMachineStatusHeartbeat(CallbackInfo ci) {
        CraftingCPUCluster selectedCpu = this.cpu;
        if (!isServerSide() || selectedCpu == null) {
            chexsonsaeutils$resetFormalStatusHeartbeat();
            return;
        }
        if (chexsonsaeutils$formalStatusOriginalPacketExpected) {
            chexsonsaeutils$resetFormalStatusHeartbeat();
            return;
        }
        if (!FormalMachineCraftingTimingService.hasActiveState(selectedCpu.craftingLogic)
                || !FormalMachineCraftingTimingService.shouldSendHeartbeat(selectedCpu.craftingLogic)) {
            chexsonsaeutils$resetFormalStatusHeartbeat();
            return;
        }
        if (chexsonsaeutils$formalStatusHeartbeatCountdown > 0) {
            chexsonsaeutils$formalStatusHeartbeatCountdown--;
            chexsonsaeutils$formalStatusOriginalPacketExpected = false;
            return;
        }

        CraftingStatus status = FormalMachineCraftingTimingService.createHeartbeatStatus(selectedCpu.craftingLogic);
        sendPacketToClient(new CraftingStatusPacket(containerId, status));
        FormalMachineCraftingTimingService.recordFormalStatusHeartbeat(selectedCpu.craftingLogic);
        chexsonsaeutils$formalStatusHeartbeatCountdown = Math.max(
                0,
                CHEXSONSAEUTILS_FORMAL_STATUS_HEARTBEAT_INTERVAL_TICKS - 1
        );
        chexsonsaeutils$formalStatusOriginalPacketExpected = false;
    }

    @Unique
    private boolean chexsonsaeutils$hasAe2StatusChange(CraftingCPUCluster selectedCpu) {
        return this.incrementalUpdateHelper.hasChanges()
                || this.cachedSuspend != selectedCpu.craftingLogic.isJobSuspended();
    }

    @Unique
    private void chexsonsaeutils$resetFormalStatusHeartbeat() {
        chexsonsaeutils$formalStatusOriginalPacketExpected = false;
        chexsonsaeutils$formalStatusHeartbeatCountdown = 0;
    }
}
