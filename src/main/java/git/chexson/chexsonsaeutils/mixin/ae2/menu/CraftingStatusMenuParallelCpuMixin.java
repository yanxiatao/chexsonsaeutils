package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.me.crafting.CraftingStatusMenu;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingStatusMenu.class, remap = false)
public abstract class CraftingStatusMenuParallelCpuMixin {

    @Shadow(remap = false)
    @Nullable
    private ICraftingCPU selectedCpu;

    @Inject(method = "setCPU", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$rememberSelectedCpu(ICraftingCPU cpu, CallbackInfo ci) {
        this.selectedCpu = cpu;
    }
}
