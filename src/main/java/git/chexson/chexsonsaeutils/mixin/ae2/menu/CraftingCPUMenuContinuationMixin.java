package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.guisync.GuiSync;
import appeng.menu.me.crafting.CraftingCPUMenu;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusService;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusSnapshot;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingBranch;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Mixin(value = CraftingCPUMenu.class, remap = false)
public abstract class CraftingCPUMenuContinuationMixin
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

    @Unique
    @GuiSync(14)
    public String waitingStackLines = "";

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$syncSelectedCpuDetail(CallbackInfo ci) {
        CraftingContinuationStatusService.syncSelectedCpuDetailForMenu((CraftingCPUMenu) (Object) this);
    }

    @Inject(method = "setCPU", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$syncSelectedCpuDetailOnCpuChange(ICraftingCPU cpu, CallbackInfo ci) {
        CraftingContinuationStatusService.syncSelectedCpuDetailForMenu((CraftingCPUMenu) (Object) this);
    }

    @Override
    public void chexsonsaeutils$setSelectedCpuDetail(@Nullable CraftingContinuationStatusSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasWaitingBranches()) {
            partialWaiting = false;
            finalOutput = "";
            requestedAmount = 0L;
            waitingBranchLines = "";
            waitingStackLines = "";
            return;
        }

        partialWaiting = true;
        finalOutput = snapshot.finalOutputKey();
        requestedAmount = snapshot.requestedAmount();
        waitingBranchLines = snapshot.waitingBranches().stream()
                .map(branch -> branch.branchLabel() + ": " + branch.missingStacks())
                .collect(Collectors.joining("\n"));
        Map<String, Long> waitingStacks = new LinkedHashMap<>();
        for (CraftingContinuationWaitingBranch branch : snapshot.waitingBranches()) {
            for (var entry : branch.missingStacks().entrySet()) {
                waitingStacks.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        waitingStackLines = waitingStacks.entrySet().stream()
                .map(entry -> entry.getKey() + "\t" + entry.getValue())
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

    @Override
    public String chexsonsaeutils$waitingStackLines() {
        return waitingStackLines;
    }
}
