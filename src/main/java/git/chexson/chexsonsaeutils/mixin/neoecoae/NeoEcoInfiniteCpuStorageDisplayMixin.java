package git.chexson.chexsonsaeutils.mixin.neoecoae;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeoEco 在 InfoBar 写入时重新格式化容量，必须在该出口保留无限 CPU 的容量语义。 */
@Pseudo
@Mixin(targets = "cn.dancingsnow.neoecoae.crafting.display.format.NEByteFormatter", remap = false)
public abstract class NeoEcoInfiniteCpuStorageDisplayMixin {
    @Inject(method = "formatCpuStorage", at = @At("HEAD"), cancellable = true, remap = false)
    private static void chexsonsaeutils$infiniteCapacity(long storage, CallbackInfoReturnable<String> cir) {
        if (storage == Long.MAX_VALUE) cir.setReturnValue("∞");
    }
}
