package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;
import net.minecraft.network.chat.Component;

/** 复用 AE2 原生工具栏按钮背景，避免 TabButton 边框与工具栏尺寸不匹配。 */
public final class PlanGraphOpenButton extends IconButton {
    public PlanGraphOpenButton(Runnable open) {
        super(ignored -> open.run()); setMessage(Component.translatable("gui.chexsonsaeutils.graph.open"));
    }
    @Override protected Icon getIcon() { return Icon.CRAFT_HAMMER; }
}
