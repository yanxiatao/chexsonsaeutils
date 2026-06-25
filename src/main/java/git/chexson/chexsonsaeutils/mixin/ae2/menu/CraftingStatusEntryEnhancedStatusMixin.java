package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.menu.me.crafting.CraftingStatusEntry;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingStatusEntry;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingStatusEntry.class, remap = false)
public abstract class CraftingStatusEntryEnhancedStatusMixin implements EnhancedCraftingStatusEntry {

    @Unique
    private long chexsonsaeutils$blockedAmount;

    @Override
    public long chexsonsaeutils$blockedAmount() {
        return chexsonsaeutils$blockedAmount;
    }

    @Override
    public void chexsonsaeutils$setBlockedAmount(long blockedAmount) {
        this.chexsonsaeutils$blockedAmount = Math.max(0L, blockedAmount);
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$writeBlockedAmount(
            FriendlyByteBuf buffer,
            CallbackInfo ci
    ) {
        buffer.writeVarLong(this.chexsonsaeutils$blockedAmount());
    }

    @Inject(method = "read", at = @At("RETURN"), remap = false)
    private static void chexsonsaeutils$readBlockedAmount(
            FriendlyByteBuf buffer,
            CallbackInfoReturnable<CraftingStatusEntry> cir
    ) {
        CraftingStatusEntry entry = cir.getReturnValue();
        if (entry instanceof EnhancedCraftingStatusEntry enhancedEntry) {
            enhancedEntry.chexsonsaeutils$setBlockedAmount(buffer.readVarLong());
        } else {
            buffer.readVarLong();
        }
    }
}
