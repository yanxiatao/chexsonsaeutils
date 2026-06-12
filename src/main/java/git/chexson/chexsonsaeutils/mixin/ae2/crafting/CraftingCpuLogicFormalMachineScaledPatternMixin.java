package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.execution.CraftingCpuLogic;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineScaledPatternNbtBridge;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicFormalMachineScaledPatternMixin {

    @Inject(method = "readFromNBT", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$restoreFormalScaledTasksAfterRead(
            CompoundTag data,
            HolderLookup.Provider registries,
            CallbackInfo ci
    ) {
        if (data == null || registries == null || !data.contains("job")) {
            return;
        }
        FormalMachineScaledPatternNbtBridge.rebuildTasksAfterRead(
                ((CraftingCpuLogicAccessor) this).getJob(),
                data.getCompound("job"),
                registries
        );
    }
}
