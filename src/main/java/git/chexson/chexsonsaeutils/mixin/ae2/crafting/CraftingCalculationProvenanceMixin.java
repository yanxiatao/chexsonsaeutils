package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tags plans produced by AE2's native (level-3) calculation, including native
 * fallbacks reached through {@code super.run()} from the optimized shells.
 * Shells that compute a plan themselves tag their own level instead.
 */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class CraftingCalculationProvenanceMixin {

    @Unique
    private long chexsonsaeutils$runStartNanos;

    @Inject(method = "run", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$markRunStart(CallbackInfoReturnable<ICraftingPlan> cir) {
        this.chexsonsaeutils$runStartNanos = System.nanoTime();
    }

    @Inject(method = "run", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$tagNativeProvenance(CallbackInfoReturnable<ICraftingPlan> cir) {
        PlanningProvenance.tag(cir.getReturnValue(), PlanningProvenance.NATIVE,
                this.chexsonsaeutils$runStartNanos);
    }
}
