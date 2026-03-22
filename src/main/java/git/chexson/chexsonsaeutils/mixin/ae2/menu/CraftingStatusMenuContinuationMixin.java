package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.menu.me.crafting.CraftingStatusMenu;
import appeng.menu.guisync.GuiSync;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationStatusService;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationStatusSnapshot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.stream.Collectors;

@Mixin(value = CraftingStatusMenu.class, remap = false)
public abstract class CraftingStatusMenuContinuationMixin
        implements CraftingContinuationStatusService.SelectedCpuDetailHost {
    @Unique
    @GuiSync(10)
    public boolean partialWaiting;

    @Unique
    @GuiSync(11)
    public String finalOutput = "";

    @Unique
    @GuiSync(12)
    public long requestedAmount = 0L;

    @Unique
    @GuiSync(13)
    public String waitingBranchLines = "";

    @Inject(method = "broadcastChanges", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$syncSelectedCpuDetail(CallbackInfo ci) {
        CraftingContinuationStatusService.syncSelectedCpuDetailForMenu((CraftingStatusMenu) (Object) this);
    }

    @Inject(method = "selectCpu", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$syncSelectedCpuDetailOnSelection(int serial, CallbackInfo ci) {
        CraftingContinuationStatusService.syncSelectedCpuDetailForMenu((CraftingStatusMenu) (Object) this);
    }

    @Override
    public void chexsonsaeutils$setSelectedCpuDetail(@Nullable CraftingContinuationStatusSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasWaitingBranches()) {
            partialWaiting = false;
            finalOutput = "";
            requestedAmount = 0L;
            waitingBranchLines = "";
            return;
        }

        partialWaiting = true;
        finalOutput = snapshot.finalOutputKey();
        requestedAmount = snapshot.requestedAmount();
        waitingBranchLines = snapshot.waitingBranches().stream()
                .map(branch -> branch.branchLabel() + ": " + branch.missingStacks())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public boolean chexsonsaeutils$partialWaiting() {
        return partialWaiting;
    }

    @Override
    public String chexsonsaeutils$finalOutput() {
        return finalOutput;
    }

    @Override
    public long chexsonsaeutils$requestedAmount() {
        return requestedAmount;
    }

    @Override
    public String chexsonsaeutils$waitingBranchLines() {
        return waitingBranchLines;
    }
}
