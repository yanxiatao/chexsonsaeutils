package git.chexson.chexsonsaeutils.client.gui.framepatternprovider;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.menu.framepatternprovider.FramePatternProviderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/**
 * 框架样板供应器屏幕。
 * <p>
 * 布局由 {@code assets/ae2/screens/frame_pattern_provider.json} 定义（StyleManager 从
 * {@code ae2:screens/} 命名空间加载），包含 36 样板槽、9 格返回库存与升级卡面板。
 * 左工具栏提供：隔离模式切换按钮（锁定/解锁图标）、主动抽取按钮（需求 8，
 * 点击后服务端把私有维度机器输出抽取到返回库存）、样板配置按钮（需求 4b：
 * 配置模式下点击处理样板槽位打开配置 GUI）与翻页按钮（需求 5：上一页/下一页，
 * 每帧按当前页 setActive 隐藏其他页样板槽，页号绘制在标题右侧）。
 */
public class FramePatternProviderScreen extends AEBaseScreen<FramePatternProviderMenu> {

    private final ToggleButton isolatedButton;
    private final ToggleButton configButton;
    private final IconButton prevPageButton;
    private final IconButton nextPageButton;

    public FramePatternProviderScreen(FramePatternProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/frame_pattern_provider.json"));
        this.widgets.add("upgrades", new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE), menu.getHost()));
        this.isolatedButton = new ToggleButton(Icon.LOCKED, Icon.UNLOCKED, btn -> this.menu.toggleIsolated());
        this.isolatedButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.isolated"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.isolated_hint")));
        this.isolatedButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.merged"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.merged_hint")));
        this.addToLeftToolbar(this.isolatedButton);
        // 主动抽取按钮：一次性动作（非 toggle），点击发送 pull_from_machine 到服务端
        IconButton pullButton = new IconButton(btn -> this.menu.pullFromMachine()) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_UP;
            }
        };
        pullButton.setMessage(Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.pull")
                .append("\n")
                .append(Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.pull_hint")));
        this.addToLeftToolbar(pullButton);
        // 样板配置按钮（需求 4b）：配置模式下点击处理样板槽位打开配置 GUI
        this.configButton = new ToggleButton(Icon.COG, Icon.COG_DISABLED, btn -> this.menu.toggleConfigMode());
        this.configButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_on"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_on_hint")));
        this.configButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_off"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_off_hint")));
        this.addToLeftToolbar(this.configButton);
        // 翻页按钮（需求 5）：上一页/下一页，边界页隐藏
        this.prevPageButton = new IconButton(btn -> this.menu.setPage(this.menu.getPage() - 1)) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_LEFT;
            }
        };
        this.prevPageButton.setMessage(Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.prev_page"));
        this.addToLeftToolbar(this.prevPageButton);
        this.nextPageButton = new IconButton(btn -> this.menu.setPage(this.menu.getPage() + 1)) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_RIGHT;
            }
        };
        this.nextPageButton.setMessage(Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.next_page"));
        this.addToLeftToolbar(this.nextPageButton);
        // 扩容按钮（需求 5 阶段 5b）：打开扩容 GUI（消耗 ExtendedAE 扩展样板供应器增加页数）
        IconButton upgradeButton = new IconButton(btn -> this.menu.openUpgradeGuiClient()) {
            @Override
            protected Icon getIcon() {
                return Icon.ENTER;
            }
        };
        upgradeButton.setMessage(Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.upgrade")
                .append("\n")
                .append(Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.upgrade_hint")));
        this.addToLeftToolbar(upgradeButton);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.isolatedButton.setState(this.menu.isIsolated());
        this.configButton.setState(this.menu.isConfigMode());
        // 需求 5：每帧按当前页刷新样板槽渲染可见性（服务端另有 isSlotEnabled 交互防护）
        this.updatePageSlotActivity();
        this.prevPageButton.setVisibility(this.menu.getPage() > 0);
        this.nextPageButton.setVisibility(this.menu.getPage() < this.menu.getPages() - 1);
    }

    /**
     * 需求 5：按当前页设置样板槽渲染可见性（每页 36 槽）。
     * <p>
     * AppEngSlot.active 只影响渲染（ContainerScreen.renderSlot 跳过 inactive 槽），
     * 交互防护由服务端 Menu 的 setSlotEnabled 承担。
     */
    private void updatePageSlotActivity() {
        int currentPage = this.menu.getPage();
        for (var slot : this.menu.getSlots(SlotSemantics.ENCODED_PATTERN)) {
            if (slot instanceof AppEngSlot appEngSlot) {
                int slotPage = appEngSlot.getSlotIndex() / FramePatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE;
                appEngSlot.setActive(slotPage == currentPage);
            }
        }
    }

    /**
     * 需求 5：页号绘制在右上角（"当前页/总页数"，1 起）。
     * <p>
     * 位置依据（S2 修复）：与布局 json 的 dialog_title（left 8, top 6）同一水平线，
     * 右对齐到背景右缘（imageWidth，布局 generatedBackground.width=200）8px 边距；
     * 标题最宽 ~113px，页号右对齐互不重叠。
     */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);
        String pageText = (this.menu.getPage() + 1) + "/" + this.menu.getPages();
        guiGraphics.drawString(
                this.font,
                pageText,
                offsetX + this.imageWidth - 8 - this.font.width(pageText),
                offsetY + 6,
                0x404040
        );
    }

    /**
     * 配置模式下拦截供应器样板槽位的处理样板点击：不执行普通槽位操作，
     * 改为请求服务端打开配置 GUI（需求 4b）。
     * I1 修复：只拦截 ENCODED_PATTERN 语义槽（供应器样板槽），背包槽位
     * 的正常物品移动/样板拿取不被劫持。
     * 需求 5：非当前页样板槽（渲染隐藏）的点击直接忽略，双保险防伪造。
     */
    @Override
    protected void slotClicked(Slot slot, int slotIndex, int button, ClickType clickType) {
        if (slot instanceof AppEngSlot appEngSlot
                && !appEngSlot.isActive()
                && this.menu.getSlots(SlotSemantics.ENCODED_PATTERN).contains(slot)) {
            return;
        }
        if (this.menu.isConfigMode()
                && this.menu.getSlots(SlotSemantics.ENCODED_PATTERN).contains(slot)
                && !slot.getItem().isEmpty()
                && slot.getItem().getItem() == AEItems.PROCESSING_PATTERN.asItem()) {
            this.menu.openConfigForSlotClient(slotIndex);
            return;
        }
        super.slotClicked(slot, slotIndex, button, clickType);
    }
}