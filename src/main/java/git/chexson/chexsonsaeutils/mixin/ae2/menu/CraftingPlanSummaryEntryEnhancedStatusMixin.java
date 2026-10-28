package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummaryEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = CraftingPlanSummaryEntry.class, remap = false)
public abstract class CraftingPlanSummaryEntryEnhancedStatusMixin implements EnhancedCraftingPlanSummaryEntry {

    @Unique
    private List<java.math.BigInteger> chexsonsaeutils$patternTimes = List.of();

    @Override
    public List<java.math.BigInteger> chexsonsaeutils$patternTimes() {
        return chexsonsaeutils$patternTimes;
    }

    @Override
    public void chexsonsaeutils$setPatternTimes(List<java.math.BigInteger> patternTimes) {
        if (patternTimes == null || patternTimes.isEmpty()) {
            this.chexsonsaeutils$patternTimes = List.of();
            return;
        }
        this.chexsonsaeutils$patternTimes = git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer
                .normalizePatternTimes(patternTimes);
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$writePatternTimes(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeVarInt(chexsonsaeutils$patternTimes.size());
        for (java.math.BigInteger patternTime : chexsonsaeutils$patternTimes) {
            git.chexson.chexsonsaeutils.network.codec.ExactAmountWire.write(buffer, patternTime);
        }
    }

    @Inject(method = "read", at = @At("RETURN"), remap = false)
    private static void chexsonsaeutils$readPatternTimes(
            RegistryFriendlyByteBuf buffer,
            CallbackInfoReturnable<CraftingPlanSummaryEntry> cir
    ) {
        int size = git.chexson.chexsonsaeutils.network.codec.ExactAmountWire.readCount(buffer);
        List<java.math.BigInteger> patternTimes = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            var amount = git.chexson.chexsonsaeutils.network.codec.ExactAmountWire.read(buffer);
            if (amount.signum() < 0) throw new IllegalArgumentException("样板次数不得为负");
            patternTimes.add(amount);
        }
        if (cir.getReturnValue() instanceof EnhancedCraftingPlanSummaryEntry enhancedEntry) {
            enhancedEntry.chexsonsaeutils$setPatternTimes(patternTimes);
        }
    }
}
