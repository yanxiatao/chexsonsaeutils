package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastDyeablePatternCraftingCalculation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroutes the dyeable recursive calculation's pausing hook for the parallel CPU
 * fast path. {@code DyeablePatternCraftingCalculation#handlePausing} is
 * package-private and overrides AE2's, so when the calculation is a
 * {@link FastDyeablePatternCraftingCalculation} the monitor-based pausing is
 * replaced by a cheap budget check. Native recursive calculations are untouched.
 */
@Mixin(value = DyeablePatternCraftingCalculation.class, remap = false)
public abstract class DyeablePatternCraftingCalculationFastPausingMixin {

    @Inject(method = "handlePausing", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$fastPathPausing(CallbackInfo ci) throws InterruptedException {
        if ((Object) this instanceof FastDyeablePatternCraftingCalculation fastCalculation) {
            fastCalculation.fastHandlePausing();
            ci.cancel();
        }
    }
}
