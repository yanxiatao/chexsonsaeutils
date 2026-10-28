package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AmountFormat;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import net.minecraft.network.chat.Component;
import java.math.BigInteger;
import java.util.*;

/** 一个预览 renderer 独占最多 64 行；滚动和重新规划均不会无限保留历史文本。 */
public final class CraftingPreviewRowsImpl implements CraftingPreviewRows {
    private final Map<CraftingPlanSummaryEntry, Row> rows = new LinkedHashMap<>(16, 0.75f, true);

    @Override
    public List<Component> lines(CraftingPlanSummaryEntry entry, WideLedgerSnapshot snapshot,
            boolean rewriteAmounts, AmountFormat format, AmountNumberStyle style, boolean showPatternTimes) {
        List<BigInteger> times = showPatternTimes && entry instanceof EnhancedCraftingPlanSummaryEntry enhanced
                ? enhanced.chexsonsaeutils$patternTimes() : List.of();
        Locale locale = Locale.getDefault();
        Row row = rows.get(entry);
        // 不遍历快照或整张发数表做 hash：两者都不可变，替换引用即表示数据更新。
        if (row == null || row.snapshot != snapshot || row.times != times || row.style != style
                || row.rewrite != rewriteAmounts || !row.locale.equals(locale)) {
            row = new Row(snapshot, times, style, rewriteAmounts, locale);
            rows.put(entry, row);
            if (rows.size() > 64) rows.remove(rows.keySet().iterator().next());
        }
        List<Component> cached = row.formats.get(format);
        if (cached == null) {
            var lines = new ArrayList<Component>(4);
            if (entry.getStoredAmount() > 0) lines.add(GuiText.FromStorage.text(entry.getWhat().formatAmount(entry.getStoredAmount(), format)));
            if (entry.getMissingAmount() > 0) lines.add(GuiText.Missing.text(entry.getWhat().formatAmount(entry.getMissingAmount(), format)));
            if (entry.getCraftAmount() > 0) lines.add(GuiText.ToCraft.text(entry.getWhat().formatAmount(entry.getCraftAmount(), format)));
            if (rewriteAmounts) WideAmountConfirmLines.applyWideAmounts(lines, 0, entry.getStoredAmount(),
                    entry.getMissingAmount(), entry.getCraftAmount(), snapshot, entry.getWhat(), format, style);
            if (!times.isEmpty()) {
                int limit = format == AmountFormat.SLOT ? 2 : 5;
                var displayed = CraftingStatusEnhancer.displayedPatternTimes(times, limit);
                var text = new StringBuilder();
                for (var count : displayed) {
                    if (!text.isEmpty()) text.append(", ");
                    text.append(EnhancedCraftingStatusFormatting.formatAmount(count, format, style));
                }
                if (times.size() > displayed.size()) text.append(" … (+").append(times.size() - displayed.size()).append(')');
                lines.add(Component.translatable("gui.chexsonsaeutils.crafting_status.pattern_times", text.toString()));
            }
            cached = List.copyOf(lines);
            row.formats.put(format, cached);
        }
        return new ArrayList<>(cached);
    }

    private static final class Row {
        private final WideLedgerSnapshot snapshot;
        private final List<BigInteger> times;
        private final AmountNumberStyle style;
        private final boolean rewrite;
        private final Locale locale;
        private final Map<AmountFormat, List<Component>> formats = new EnumMap<>(AmountFormat.class);
        private Row(WideLedgerSnapshot snapshot, List<BigInteger> times, AmountNumberStyle style, boolean rewrite, Locale locale) {
            this.snapshot = snapshot; this.times = times; this.style = style; this.rewrite = rewrite; this.locale = locale;
        }
    }
}
