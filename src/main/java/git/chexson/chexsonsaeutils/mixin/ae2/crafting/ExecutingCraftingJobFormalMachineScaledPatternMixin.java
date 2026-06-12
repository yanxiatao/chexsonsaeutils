package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.execution.ExecutingCraftingJob;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineScaledPatternNbtBridge;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public abstract class ExecutingCraftingJobFormalMachineScaledPatternMixin {

    @Inject(method = "writeToNBT", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$writeFormalScaledTasks(
            HolderLookup.Provider registries,
            CallbackInfoReturnable<CompoundTag> cir
    ) {
        if (cir.getReturnValue() == null) {
            return;
        }
        FormalMachineScaledPatternNbtBridge.rewriteTaskListForWrite(
                (ExecutingCraftingJob) (Object) this,
                registries,
                cir.getReturnValue()
        );
    }

}
