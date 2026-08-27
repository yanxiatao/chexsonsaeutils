package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastCraftingCalculation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroutes AE2's package-private hooks for the parallel CPU fast planning path.
 *
 * <p>{@code CraftingCalculation#handlePausing} and {@code addMissing} are
 * package-private, so they cannot be overridden from this mod's package. When the
 * calculation is a {@link FastCraftingCalculation}, the native monitor-based
 * pausing is replaced by a cheap budget check and missing-item accounting is
 * rerouted to the fast calculation's own counter. Native calculations are
 * untouched.
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

    @Inject(method = "addMissing", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$fastPathAddMissing(AEKey what, long amount, CallbackInfo ci) {
        if ((Object) this instanceof FastCraftingCalculation fastCalculation) {
            fastCalculation.fastAddMissing(what, amount);
            ci.cancel();
        }
    }
}
