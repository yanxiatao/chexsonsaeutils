package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AmountFormat;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/** 预览表数量行的显示入口；只缓存数量组件，物品自身的提示仍由 AE2 实时生成。 */
public interface CraftingPreviewRows {
    /** 按当前快照和显示选项复用数量行；返回独立列表，允许调用方追加提示而不污染缓存。 */
    List<Component> lines(CraftingPlanSummaryEntry entry, @Nullable WideLedgerSnapshot snapshot,
            boolean rewriteAmounts, AmountFormat format, AmountNumberStyle style, boolean showPatternTimes);
}
