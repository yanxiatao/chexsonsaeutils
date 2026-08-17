package git.chexson.chexsonsaeutils.client.gui.framepatternupgrade;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.StyleManager;
import git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeMenu;

/**
 * 扩容 GUI 屏幕（需求 5 阶段 5b）。
 * <p>
 * 布局由 {@code assets/ae2/screens/frame_pattern_upgrade.json} 定义：存储槽
 * （STORAGE_CELL，放框架供应器物品）、输入槽（MACHINE_INPUT，放扩展物品）、
 * 确认按钮（widget "confirm"，可扩容时才可用）与提示文本（drawFG 动态绘制：
 * 当前页数/最大页数、扩展物品数量）。
 */
public class FramePatternUpgradeScreen extends AEBaseScreen<FramePatternUpgradeMenu> {

    private final Button confirmButton;

    public FramePatternUpgradeScreen(FramePatternUpgradeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/frame_pattern_upgrade.json"));
        this.confirmButton = Button.builder(
                        Component.translatable("gui.chexsonsaeutils.frame_pattern_upgrade.confirm"),
                        btn -> this.menu.expandClient())
                .bounds(0, 0, 60, 20)
                .build();
        this.widgets.add("confirm", this.confirmButton);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        // 可扩容状态由服务端 @GuiSync 同步：未满足条件时禁用按钮
        this.confirmButton.active = this.menu.isCanExpand();
    }

    /**
     * 提示文本：当前页数/最大页数 + 输入槽扩展物品数量。
     * 位置依据：布局 json 的 text 区域（left 8, top 100 起），与存储/输入槽垂直对齐。
     */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);
        int maxPages = this.menu.getMaxPages();
        guiGraphics.drawString(
                this.font,
                Component.translatable("gui.chexsonsaeutils.frame_pattern_upgrade.pages",
                        this.menu.getPages(), maxPages),
                offsetX + 8,
                offsetY + 100,
                0x404040
        );
        int inputCount = this.menu.getInputCount();
        guiGraphics.drawString(
                this.font,
                Component.translatable("gui.chexsonsaeutils.frame_pattern_upgrade.input_count", inputCount),
                offsetX + 8,
                offsetY + 112,
                0x404040
        );
    }
}
