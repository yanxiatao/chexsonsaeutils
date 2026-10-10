package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummary;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Syncs the planning provenance (millis + solving level) to the client as a
 * trailing payload on the plan-summary packet.
 */
@Mixin(value = CraftingPlanSummary.class, remap = false)
public abstract class CraftingPlanSummaryProvenanceMixin implements EnhancedCraftingPlanSummary {

    @Unique
    private long chexsonsaeutils$planningMillis = -1L;

    @Unique
    private String chexsonsaeutils$solver = "";

    @Override
    public long chexsonsaeutils$planningMillis() {
        return this.chexsonsaeutils$planningMillis;
    }

    @Override
    public String chexsonsaeutils$solver() {
        return this.chexsonsaeutils$solver;
    }

    @Override
    public void chexsonsaeutils$setProvenance(long millis, String solver) {
        this.chexsonsaeutils$planningMillis = millis;
        this.chexsonsaeutils$solver = solver == null ? "" : solver;
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$writeProvenance(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeVarLong(this.chexsonsaeutils$planningMillis);
        buffer.writeUtf(this.chexsonsaeutils$solver);
    }

    @Inject(method = "read", at = @At("RETURN"), remap = false)
    private static void chexsonsaeutils$readProvenance(
            RegistryFriendlyByteBuf buffer,
            CallbackInfoReturnable<CraftingPlanSummary> cir
    ) {
        long millis = buffer.readVarLong();
        String solver = buffer.readUtf();
        if (cir.getReturnValue() instanceof EnhancedCraftingPlanSummary enhanced) {
            enhanced.chexsonsaeutils$setProvenance(millis, solver);
        }
    }
}
