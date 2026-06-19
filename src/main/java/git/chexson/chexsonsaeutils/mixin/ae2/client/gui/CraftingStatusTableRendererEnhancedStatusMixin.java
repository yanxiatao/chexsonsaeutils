package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.api.stacks.AmountFormat;
import appeng.api.util.AEColor;
import appeng.client.gui.me.crafting.CraftingStatusTableRenderer;
import appeng.core.AEConfig;
import appeng.menu.me.crafting.CraftingStatusEntry;
import git.chexson.chexsonsaeutils.config.EnhancedCraftingStatusFeatureGate;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingStatusEntry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = CraftingStatusTableRenderer.class, remap = false)
public abstract class CraftingStatusTableRendererEnhancedStatusMixin {

    private static final int BLOCKED_BACKGROUND = AEColor.ORANGE.blackVariant | 0x5A000000;

    @Inject(method = "getEntryDescription", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$appendBlockedDescription(
            CraftingStatusEntry entry,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        appendBlocked(entry, cir.getReturnValue(), AmountFormat.SLOT);
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$appendBlockedTooltip(
            CraftingStatusEntry entry,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        appendBlocked(entry, cir.getReturnValue(), AmountFormat.FULL);
    }

    @Inject(method = "getEntryBackgroundColor", at = @At("RETURN"), cancellable = true, remap = false)
    private void chexsonsaeutils$appendBlockedBackground(
            CraftingStatusEntry entry,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()
                || !AEConfig.instance().isUseColoredCraftingStatus()
                || blockedAmount(entry) <= 0L
                || cir.getReturnValue() != 0) {
            return;
        }
        cir.setReturnValue(BLOCKED_BACKGROUND);
    }

    private static void appendBlocked(CraftingStatusEntry entry, List<Component> lines, AmountFormat format) {
        long blockedAmount = blockedAmount(entry);
        if (!EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()
                || blockedAmount <= 0L
                || entry.getWhat() == null) {
            return;
        }
        lines.add(Component.translatable(
                "gui.chexsonsaeutils.crafting_status.blocked",
                entry.getWhat().formatAmount(blockedAmount, format)
        ));
    }

    private static long blockedAmount(CraftingStatusEntry entry) {
        if (entry instanceof EnhancedCraftingStatusEntry enhancedEntry) {
            return enhancedEntry.chexsonsaeutils$blockedAmount();
        }
        return 0L;
    }
}
