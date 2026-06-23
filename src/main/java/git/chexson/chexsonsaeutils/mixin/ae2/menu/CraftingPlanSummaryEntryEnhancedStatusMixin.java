package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummaryEntry;
import net.minecraft.network.FriendlyByteBuf;
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
    private List<Long> chexsonsaeutils$patternTimes = List.of();

    @Override
    public List<Long> chexsonsaeutils$patternTimes() {
        return chexsonsaeutils$patternTimes;
    }

    @Override
    public void chexsonsaeutils$setPatternTimes(List<Long> patternTimes) {
        if (patternTimes == null || patternTimes.isEmpty()) {
            this.chexsonsaeutils$patternTimes = List.of();
            return;
        }
        this.chexsonsaeutils$patternTimes = List.copyOf(patternTimes);
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$writePatternTimes(FriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeVarInt(chexsonsaeutils$patternTimes.size());
        for (long patternTime : chexsonsaeutils$patternTimes) {
            buffer.writeVarLong(patternTime);
        }
    }

    @Inject(method = "read", at = @At("RETURN"), remap = false)
    private static void chexsonsaeutils$readPatternTimes(
            FriendlyByteBuf buffer,
            CallbackInfoReturnable<CraftingPlanSummaryEntry> cir
    ) {
        int size = buffer.readVarInt();
        List<Long> patternTimes = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            patternTimes.add(buffer.readVarLong());
        }
        if (cir.getReturnValue() instanceof EnhancedCraftingPlanSummaryEntry enhancedEntry) {
            enhancedEntry.chexsonsaeutils$setPatternTimes(patternTimes);
        }
    }
}
