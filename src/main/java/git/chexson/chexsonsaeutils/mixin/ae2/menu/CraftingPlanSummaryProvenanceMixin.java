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
 * 在计划摘要包尾同步规划耗时、求解档位和精确字节，避免独立菜单字段未到时丢失真值。
 */
@Mixin(value = CraftingPlanSummary.class, remap = false)
public abstract class CraftingPlanSummaryProvenanceMixin implements EnhancedCraftingPlanSummary {

    @Unique
    private long chexsonsaeutils$planningMillis = -1L;

    @Unique
    private String chexsonsaeutils$solver = "";

    @Unique
    private java.math.BigInteger chexsonsaeutils$exactBytes;

    @Override
    public java.math.BigInteger chexsonsaeutils$exactBytes() { return chexsonsaeutils$exactBytes; }

    @Override
    public void chexsonsaeutils$setExactBytes(java.math.BigInteger bytes) { chexsonsaeutils$exactBytes = bytes; }

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
        git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.write(buffer, this.chexsonsaeutils$exactBytes);
    }

    @Inject(method = "read", at = @At("RETURN"), remap = false)
    private static void chexsonsaeutils$readProvenance(
            RegistryFriendlyByteBuf buffer,
            CallbackInfoReturnable<CraftingPlanSummary> cir
    ) {
        long millis = buffer.readVarLong();
        String solver = buffer.readUtf();
        java.math.BigInteger exactBytes = git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.read(buffer);
        if (cir.getReturnValue() instanceof EnhancedCraftingPlanSummary enhanced) {
            enhanced.chexsonsaeutils$setProvenance(millis, solver);
            enhanced.chexsonsaeutils$setExactBytes(exactBytes);
        }
    }
}
