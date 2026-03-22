package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.api.stacks.AmountFormat;
import appeng.api.util.AEColor;
import appeng.client.gui.me.crafting.CraftingStatusTableRenderer;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftingStatusEntry;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusService;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = CraftingStatusTableRenderer.class, remap = false)
public abstract class CraftingStatusTableRendererContinuationMixin {
    private static final int WAITING_BACKGROUND = AEColor.YELLOW.blackVariant | 0x5A000000;

    @Inject(method = "getEntryDescription", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$replaceWaitingDescription(
            CraftingStatusEntry entry,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        long waitingAmount = getWaitingAmount(entry);
        if (waitingAmount <= 0L || entry.getWhat() == null) {
            return;
        }

        cir.setReturnValue(buildWaitingLines(entry, waitingAmount, AmountFormat.SLOT));
    }

    @Inject(method = "getEntryTooltip", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$replaceWaitingTooltip(
            CraftingStatusEntry entry,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        long waitingAmount = getWaitingAmount(entry);
        if (waitingAmount <= 0L || entry.getWhat() == null) {
            return;
        }

        cir.setReturnValue(buildWaitingLines(entry, waitingAmount, AmountFormat.FULL));
    }

    @Inject(method = "getEntryBackgroundColor", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$replaceWaitingBackground(
            CraftingStatusEntry entry,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (getWaitingAmount(entry) > 0L) {
            cir.setReturnValue(WAITING_BACKGROUND);
        }
    }

    private List<Component> buildWaitingLines(
            CraftingStatusEntry entry,
            long waitingAmount,
            AmountFormat amountFormat
    ) {
        List<Component> lines = new ArrayList<>(2);
        if (entry.getStoredAmount() > 0) {
            lines.add(GuiText.FromStorage.text(entry.getWhat().formatAmount(entry.getStoredAmount(), amountFormat)));
        }
        lines.add(Component.translatable(
                "gui.chexsonsaeutils.crafting_status.waiting",
                entry.getWhat().formatAmount(waitingAmount, amountFormat)
        ));
        return lines;
    }

    private long getWaitingAmount(CraftingStatusEntry entry) {
        var screen = ((AbstractTableRendererAccessor) this).chexsonsaeutils$getScreen();
        if (entry.getWhat() == null
                || !(screen instanceof CraftingContinuationStatusService.WaitingStackProjectionHost host)
                || !host.chexsonsaeutils$partialWaiting()) {
            return 0L;
        }

        return host.chexsonsaeutils$waitingStackAmounts().getOrDefault(
                CraftingContinuationStatusService.encodeKeyForSync(entry.getWhat()),
                0L
        );
    }
}
