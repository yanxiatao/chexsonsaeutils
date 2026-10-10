package git.chexson.chexsonsaeutils.client.gui.tooltip;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.crafting.PatternDetailsTooltip;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.core.localization.GuiText;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementPersistence;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwarePattern;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import java.util.List;
import java.util.Map;

/** 给样板物品及矩阵中的输入信息补充替代标签，保留 AE2 原有数量与文字样式。 */
public final class ProcessingReplacementTooltips {
    private static final ProcessingPatternReplacementPersistence PERSISTENCE = new ProcessingPatternReplacementPersistence();

    private ProcessingReplacementTooltips() { }

    /** 仅客户端注册；启动期无玩家、未解码或未配置替代的物品不处理。 */
    public static void onTooltip(ItemTooltipEvent event) {
        var player = event.getEntity();
        if (player == null || !PERSISTENCE.hasReplacementMetadata(event.getItemStack())) return;
        var details = PatternDetailsHelper.decodePattern(event.getItemStack(), player.level());
        if (details instanceof ReplacementAwarePattern replacement) {
            appendInputHints(event.getToolTip(), replacement.getTooltip(player.level(), event.getFlags()),
                    replacement.inputReplacementHints());
        }
    }

    /** 按 AE2 的输入行顺序追加提示，避免同名输出、物品标题及其他说明被错误修改。 */
    public static void appendInputHints(List<Component> lines, PatternDetailsTooltip details,
                                        Map<AEKey, Component> hints) {
        int cursor = 0;
        for (int inputIndex = 0; inputIndex < details.getInputs().size(); inputIndex++) {
            var input = details.getInputs().get(inputIndex);
            Component hint = hints.get(input.what());
            String prefix = inputIndex == 0 ? GuiText.With.text().getString() + ": "
                    : " " + GuiText.And.text().getString() + " ";
            String entry = input.what().getDisplayName().getString();
            if (input.amount() > 0) {
                entry = input.what().getType().formatAmount(input.amount(), AmountFormat.FULL) + " x " + entry;
            }
            String expected = prefix + entry;
            for (int line = cursor; line < lines.size(); line++) {
                if (!lines.get(line).getString().equals(expected)) continue;
                if (hint != null) lines.set(line, lines.get(line).copy().append(hint));
                cursor = line + 1;
                break;
            }
        }
    }
}
