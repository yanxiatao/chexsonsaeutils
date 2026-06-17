package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.config.EnhancedCraftingStatusFeatureGate;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingStatusFormatting;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingStatusService;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = CraftConfirmTableRenderer.class, remap = false)
public abstract class CraftConfirmTableRendererEnhancedStatusMixin {

    @Inject(method = "getEntryDescription", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$appendPatternTimesDescription(
            CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        appendPatternTimes(entry, cir.getReturnValue(), AmountFormat.SLOT, 2);
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$appendPatternTimesTooltip(
            CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        appendPatternTimes(entry, cir.getReturnValue(), AmountFormat.FULL, 5);
    }

    private static void appendPatternTimes(
            CraftingPlanSummaryEntry entry,
            List<Component> lines,
            AmountFormat format,
            int maxEntries
    ) {
        if (!EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()
                || !(entry instanceof EnhancedCraftingPlanSummaryEntry enhancedEntry)) {
            return;
        }

        List<Long> patternTimes = enhancedEntry.chexsonsaeutils$patternTimes();
        if (patternTimes.isEmpty()) {
            return;
        }

        MutableComponent text = Component.empty();
        List<Long> displayedTimes = EnhancedCraftingStatusService.sortedPatternTimes(patternTimes, maxEntries);
        for (int index = 0; index < displayedTimes.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(Component.literal(EnhancedCraftingStatusFormatting.formatAmount(displayedTimes.get(index),
                    format)));
        }
        if (patternTimes.size() > displayedTimes.size()) {
            text.append("...");
        }
        lines.add(Component.translatable("gui.chexsonsaeutils.crafting_status.pattern_times", text));
    }
}
