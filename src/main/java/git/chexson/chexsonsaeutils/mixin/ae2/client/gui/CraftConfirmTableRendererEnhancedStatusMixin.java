package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.client.gui.crafting.WideAmountSource;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.status.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(value = CraftConfirmTableRenderer.class, remap = false)
public abstract class CraftConfirmTableRendererEnhancedStatusMixin {
    @Unique
    private final CraftingPreviewRows chexsonsaeutils$rows = new CraftingPreviewRowsImpl();

    @Inject(method = "getEntryDescription", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$description(CraftingPlanSummaryEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        if (chexsonsaeutils$source() != null) cir.setReturnValue(chexsonsaeutils$lines(entry, AmountFormat.SLOT));
    }

    @Inject(method = "getEntryDescription", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$otherDescription(CraftingPlanSummaryEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        if (chexsonsaeutils$source() == null) chexsonsaeutils$appendTimesOnly(entry, cir.getReturnValue(), AmountFormat.SLOT);
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$tooltip(CraftingPlanSummaryEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        // 保留 AEKeyRendering 刚刚生成的物品提示，只替换尾部数量块。
        var lines = cir.getReturnValue();
        if (chexsonsaeutils$source() == null) {
            chexsonsaeutils$appendTimesOnly(entry, lines, AmountFormat.FULL);
            return;
        }
        int count = WideAmountConfirmLines.shownLineCount(entry.getStoredAmount(), entry.getMissingAmount(), entry.getCraftAmount());
        if (count > lines.size()) return;
        lines.subList(lines.size() - count, lines.size()).clear();
        lines.addAll(chexsonsaeutils$lines(entry, AmountFormat.FULL));
    }

    @Unique
    private List<Component> chexsonsaeutils$lines(CraftingPlanSummaryEntry entry, AmountFormat format) {
        var source = chexsonsaeutils$source();
        return chexsonsaeutils$rows.lines(entry, source == null ? null : source.chexsonsaeutils$wideSnapshot(),
                source != null, format, EnhancedCraftingStatusFormatting.currentStyle(),
                ChexsonsaeutilsCompatibilityConfig.featureEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED,
                        "enhancedCraftingStatusEnabled"));
    }

    @Unique
    private void chexsonsaeutils$appendTimesOnly(CraftingPlanSummaryEntry entry, List<Component> lines, AmountFormat format) {
        var cached = chexsonsaeutils$lines(entry, format);
        int count = WideAmountConfirmLines.shownLineCount(entry.getStoredAmount(), entry.getMissingAmount(), entry.getCraftAmount());
        if (cached.size() > count) lines.add(cached.getLast());
    }

    @Unique
    private WideAmountSource chexsonsaeutils$source() {
        var screen = ((AbstractTableRendererAccessor) (Object) this).chexsonsaeutils$getScreen();
        return screen instanceof WideAmountSource value ? value : null;
    }
}
