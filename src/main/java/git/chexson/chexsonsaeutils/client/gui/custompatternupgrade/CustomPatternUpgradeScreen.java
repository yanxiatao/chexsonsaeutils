package git.chexson.chexsonsaeutils.client.gui.custompatternupgrade;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.StyleManager;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeMenu;

/**
 * 扩容 GUI 屏幕（需求 5 阶段 5b）。
 * <p>
 * 布局由 {@code assets/ae2/screens/custom_pattern_upgrade.json} 定义（condenser
 * 风格 176x199 背景，槽位坐标对齐 {@code screens/condenser.json}）：存储槽
 * （STORAGE_CELL，放框架供应器物品）、输入槽（MACHINE_INPUT，放扩展物品）、
 * 无确认按钮——放入扩展物品后服务端自动消耗扩容，提示文本（drawFG 动态绘制：
 * 当前页数/最大页数、扩展物品数量）。
 */
public class CustomPatternUpgradeScreen extends AEBaseScreen<CustomPatternUpgradeMenu> {

    public CustomPatternUpgradeScreen(CustomPatternUpgradeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/custom_pattern_upgrade.json"));
    }

    /**
     * 提示文本：当前页数/最大页数 + 输入槽扩展物品数量。
     * 位置依据：condenser 风格布局左侧空白区（left 8，标题下方，输入槽 (51,52) 上方）。
     * 相对坐标：renderLabels 前已有 translate(leftPos, topPos)，drawFG 内不得再加窗口偏移。
     */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);
        int maxPages = this.menu.getMaxPages();
        guiGraphics.drawString(
                this.font,
                Component.translatable("gui.chexsonsaeutils.custom_pattern_upgrade.pages",
                        this.menu.getPages(), maxPages),
                8,
                16,
                0x404040
        );
        int inputCount = this.menu.getInputCount();
        guiGraphics.drawString(
                this.font,
                Component.translatable("gui.chexsonsaeutils.custom_pattern_upgrade.input_count", inputCount),
                8,
                28,
                0x404040
        );
    }
}
