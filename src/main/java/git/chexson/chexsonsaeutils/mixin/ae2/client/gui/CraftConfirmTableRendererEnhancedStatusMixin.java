package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.client.gui.crafting.WideAmountSource;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.status.AmountNumberStyle;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingStatusFormatting;
import git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer;
import git.chexson.chexsonsaeutils.crafting.status.WideAmountConfirmLines;
import git.chexson.chexsonsaeutils.mixin.ae2.client.gui.AbstractTableRendererAccessor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
        AmountNumberStyle style = EnhancedCraftingStatusFormatting.currentStyle();
        chexsonsaeutils$rewriteAmounts(entry, cir.getReturnValue(), AmountFormat.SLOT, style);
        appendPatternTimes(entry, cir.getReturnValue(), AmountFormat.SLOT, 2, style);
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$appendPatternTimesTooltip(
            CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        AmountNumberStyle style = EnhancedCraftingStatusFormatting.currentStyle();
        chexsonsaeutils$rewriteAmounts(entry, cir.getReturnValue(), AmountFormat.FULL, style);
        appendPatternTimes(entry, cir.getReturnValue(), AmountFormat.FULL, 5, style);
    }

    private static void appendPatternTimes(
            CraftingPlanSummaryEntry entry,
            List<Component> lines,
            AmountFormat format,
            int maxEntries,
            AmountNumberStyle style
    ) {
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED, "enhancedCraftingStatusEnabled")
                || !(entry instanceof EnhancedCraftingPlanSummaryEntry enhancedEntry)) {
            return;
        }

        List<java.math.BigInteger> patternTimes = enhancedEntry.chexsonsaeutils$patternTimes();
        if (patternTimes.isEmpty()) {
            return;
        }

        StringBuilder text = new StringBuilder();
        List<java.math.BigInteger> displayedTimes = CraftingStatusEnhancer.displayedPatternTimes(patternTimes, maxEntries);
        for (int index = 0; index < displayedTimes.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(EnhancedCraftingStatusFormatting.formatAmount(displayedTimes.get(index), format, style));
        }
        if (patternTimes.size() > displayedTimes.size()) {
            text.append(" … (+").append(patternTimes.size() - displayedTimes.size()).append(')');
        }
        lines.add(Component.translatable("gui.chexsonsaeutils.crafting_status.pattern_times", text.toString()));
    }

    /**
     * S1：把确认表条目里 AE2 自己写出的那三行数量<b>原地换成</b>超 long 的宽域真值
     * （格子内按计数方式显示、tooltip 全量千分位），不再在其后追加一行。
     *
     * <p>数据来自 M1 同步、经我们自己的确认屏解析好的宽域快照（经现成的
     * {@link AbstractTableRendererAccessor} 取到 screen 再向下转型）。快照只含溢出键，
     * 命中即真值超过 long；非我们的屏时一行都不动。快照为空但选了指数计数法仍要跑一趟——
     * 那一趟只换紧凑列的写法，{@link WideAmountConfirmLines} 自己判。
     *
     * <p>调用顺序有讲究：必须在 appendPatternTimes 之前改写——此刻 AE2 的数量块正好是列表尾部，
     * 块起点可以用 size 减块长定住，不必解析 chat contents（那套类名在小版本间改过名）。
     */
    @Unique
    private void chexsonsaeutils$rewriteAmounts(
            CraftingPlanSummaryEntry entry,
            List<Component> lines,
            AmountFormat format,
            AmountNumberStyle style
    ) {
        if (!(chexsonsaeutils$screen() instanceof WideAmountSource source)) {
            return;
        }
        WideLedgerSnapshot snapshot = source.chexsonsaeutils$wideSnapshot();
        int shown = WideAmountConfirmLines.shownLineCount(entry.getStoredAmount(),
                entry.getMissingAmount(), entry.getCraftAmount());
        WideAmountConfirmLines.applyWideAmounts(lines, lines.size() - shown,
                entry.getStoredAmount(), entry.getMissingAmount(), entry.getCraftAmount(),
                snapshot, entry.getWhat(), format, style);
    }

    /** 借 {@link AbstractTableRendererAccessor}（同 config 已注册）从 renderer 拿到底层 screen。 */
    @Unique
    private Object chexsonsaeutils$screen() {
        return ((AbstractTableRendererAccessor) (Object) this).chexsonsaeutils$getScreen();
    }
}
