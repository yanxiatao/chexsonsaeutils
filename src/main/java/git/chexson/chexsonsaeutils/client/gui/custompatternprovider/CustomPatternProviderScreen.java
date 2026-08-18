package git.chexson.chexsonsaeutils.client.gui.custompatternprovider;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import git.chexson.chexsonsaeutils.crafting.framepattern.FramePatternItem;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPart;

/**
 * 定制样板供应器屏幕（阶段 2）。
 * <p>
 * 布局由 {@code assets/ae2/screens/custom_pattern_provider.json} 定义（照框架版
 * 200x245，StyleManager 从 {@code ae2:screens/} 命名空间加载），包含 36 样板槽、
 * 9 格返回库存与升级卡面板。
 * 左工具栏（照框架版去隔离模式按钮）：主动抽取、样板配置、输入过滤、翻页、扩容。
 */
public class CustomPatternProviderScreen extends AEBaseScreen<CustomPatternProviderMenu> {

    private final ToggleButton configButton;
    private final ToggleButton filterImportButton;
    private final IconButton prevPageButton;
    private final IconButton nextPageButton;

    public CustomPatternProviderScreen(CustomPatternProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/custom_pattern_provider.json"));
        this.widgets.add("upgrades", new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE), menu.getHost()));
        // 主动抽取按钮：一次性动作（非 toggle），点击发送 pull_from_machine 到服务端
        IconButton pullButton = new IconButton(btn -> this.menu.pullFromMachine()) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_UP;
            }
        };
        pullButton.setMessage(Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.pull")
                .append("\n")
                .append(Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.pull_hint")));
        this.addToLeftToolbar(pullButton);
        // 样板配置按钮（需求 4b）：配置模式下点击处理样板槽位打开配置 GUI
        this.configButton = new ToggleButton(Icon.COG, Icon.COG_DISABLED, btn -> this.menu.toggleConfigMode());
        this.configButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.configure_on"),
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.configure_on_hint")));
        this.configButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.configure_off"),
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.configure_off_hint")));
        this.addToLeftToolbar(this.configButton);
        // 输入过滤按钮（需求 6a）：过滤开启时 returnInv 注入网络只放行已配置样板的输出物品
        this.filterImportButton = new ToggleButton(Icon.FILTER_ON_EXTRACT_ENABLED,
                Icon.FILTER_ON_EXTRACT_DISABLED, btn -> this.menu.toggleFilteredImport());
        this.filterImportButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.filtered_import_on"),
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.filtered_import_on_hint")));
        this.filterImportButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.filtered_import_off"),
                Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.filtered_import_off_hint")));
        this.addToLeftToolbar(this.filterImportButton);
        // 翻页按钮（需求 5）：上一页/下一页，边界页隐藏
        this.prevPageButton = new IconButton(btn -> this.menu.setPage(this.menu.getPage() - 1)) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_LEFT;
            }
        };
        this.prevPageButton.setMessage(Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.prev_page"));
        this.addToLeftToolbar(this.prevPageButton);
        this.nextPageButton = new IconButton(btn -> this.menu.setPage(this.menu.getPage() + 1)) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_RIGHT;
            }
        };
        this.nextPageButton.setMessage(Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.next_page"));
        this.addToLeftToolbar(this.nextPageButton);
        // 扩容按钮（需求 5 阶段 5b）：打开扩容 GUI（消耗 ExtendedAE 扩展样板供应器增加页数）
        IconButton upgradeButton = new IconButton(btn -> this.menu.openUpgradeGuiClient()) {
            @Override
            protected Icon getIcon() {
                return Icon.ENTER;
            }
        };
        upgradeButton.setMessage(Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.upgrade")
                .append("\n")
                .append(Component.translatable("gui.chexsonsaeutils.custom_pattern_provider.upgrade_hint")));
        this.addToLeftToolbar(upgradeButton);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.configButton.setState(this.menu.isConfigMode());
        this.filterImportButton.setState(this.menu.isFilteredImport());
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
                int slotPage = appEngSlot.getSlotIndex() / CustomPatternProviderPart.PATTERN_SLOTS_PER_PAGE;
                appEngSlot.setActive(slotPage == currentPage);
            }
        }
    }

    /**
     * 需求 5：页号绘制在右上角（"当前页/总页数"，1 起）。
     * <p>
     * 位置依据（S2 修复）：与布局 json 的 dialog_title（left 8, top 6）同一水平线，
     * 右对齐到背景右缘（imageWidth，布局 generatedBackground.width=200）8px 边距。
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
     * 配置模式下拦截供应器样板槽位的样板点击：不执行普通槽位操作，
     * 改为请求服务端打开配置 GUI（需求 4b，接受处理样板与框架样板两类）。
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
                && (slot.getItem().getItem() == AEItems.PROCESSING_PATTERN.asItem()
                        || slot.getItem().getItem() instanceof FramePatternItem)) {
            this.menu.openConfigForSlotClient(slotIndex);
            return;
        }
        super.slotClicked(slot, slotIndex, button, clickType);
    }
}