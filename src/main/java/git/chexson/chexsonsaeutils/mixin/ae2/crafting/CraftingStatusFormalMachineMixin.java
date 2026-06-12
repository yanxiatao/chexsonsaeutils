package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingStatus;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineCraftingTimingService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingStatus.class, remap = false)
public abstract class CraftingStatusFormalMachineMixin {

    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static void chexsonsaeutils$correctFormalMachineStatus(
            IncrementalUpdateHelper changes,
            CraftingCpuLogic logic,
            CallbackInfoReturnable<CraftingStatus> cir
    ) {
        cir.setReturnValue(FormalMachineCraftingTimingService.correctStatus(logic, cir.getReturnValue()));
    }
}
