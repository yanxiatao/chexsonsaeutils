package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.CraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastCraftingCalculation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroutes AE2's pausing hook for the parallel CPU fast planning path.
 *
 * <p>{@code CraftingCalculation#handlePausing} is package-private, so it cannot
 * be overridden from this mod's package. Instead, when the calculation is a
 * {@link FastCraftingCalculation}, the native monitor-based pausing is skipped
 * and replaced by a cheap budget check, letting the plan run full-speed on the
 * crafting thread. Native calculations are untouched.
 */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class CraftingCalculationFastPausingMixin {

    @Inject(method = "handlePausing", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$fastPathPausing(CallbackInfo ci) throws InterruptedException {
        if ((Object) this instanceof FastCraftingCalculation fastCalculation) {
            fastCalculation.fastHandlePausing();
            ci.cancel();
        }
    }
}
