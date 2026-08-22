package git.chexson.chexsonsaeutils.client.gui.framepatternprovider;

import appeng.client.gui.Icon;
import appeng.client.gui.implementations.PatternProviderScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.client.gui.MultiPagePatternScreen;
import git.chexson.chexsonsaeutils.crafting.framepattern.FramePatternItem;
import git.chexson.chexsonsaeutils.menu.framepatternprovider.FramePatternProviderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/**
 * 框架样板供应器屏幕（阶段 3：继承 AE2 原版 PatternProviderScreen）。
 * <p>
 * 布局由 {@code assets/ae2/screens/frame_pattern_provider.json} 定义（StyleManager 从
 * {@code ae2:screens/} 命名空间加载），包含 36 样板槽与 9 格返回库存。升级卡槽位
 * 显示由 appflux 自理（照 extendedae 模式，本项目不注册升级卡、不显示升级槽）。
 * 父类构造器已添加原版按钮
 * （blockingMode/lockCraftingMode/openPriority/showInPatternAccessTerminal）与
 * lockReason 指示器（json 必须定义 lockReason/openPriority widget，否则构造抛异常）。
 * 左工具栏额外提供：隔离模式切换按钮（锁定/解锁图标）、主动抽取按钮（需求 8，
 * 点击后服务端把私有维度机器输出抽取到返回库存）、样板配置按钮（需求 4b：
 * 配置模式下点击处理样板槽位打开配置 GUI）与翻页按钮（需求 5：上一页/下一页，
 * 每帧按当前页 setActive 隐藏其他页样板槽，页号绘制在标题右侧）。
 */
public class FramePatternProviderScreen extends PatternProviderScreen<FramePatternProviderMenu>
        implements MultiPagePatternScreen {

    private final ToggleButton extractButton;
    private final ToggleButton configButton;
    private final ToggleButton filterImportButton;
    private final IconButton prevPageButton;
    private final IconButton nextPageButton;

    /** 上一次渲染的页号：检测翻页以清除悬停槽位残留（照 ExtendedAE_Plus GuiExPatternProviderMixin）。 */
    private int lastRenderedPage = -1;

    public FramePatternProviderScreen(FramePatternProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/frame_pattern_provider.json"));
        // 主动抽取按钮（需求 8 toggle）：切换持续抽取机器输出到返回库存（服务端 Ticker
        // 每 10 tick 调用 pullFromMachine，状态经 @GuiSync 同步）
        this.extractButton = new ToggleButton(Icon.AUTO_EXPORT_ON, Icon.AUTO_EXPORT_OFF,
                btn -> this.menu.toggleActiveExtract());
        this.extractButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.pull_on"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.pull_on_hint")));
        this.extractButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.pull_off"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.pull_off_hint")));
        this.addToLeftToolbar(this.extractButton);
        // 样板配置按钮（需求 4b）：配置模式下点击处理样板槽位打开配置 GUI
        this.configButton = new ToggleButton(Icon.COG, Icon.COG_DISABLED, btn -> this.menu.toggleConfigMode());
        this.configButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_on"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_on_hint")));
        this.configButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_off"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.configure_off_hint")));
        this.addToLeftToolbar(this.configButton);
        // 输入过滤按钮（需求 6a）：过滤开启时 returnInv 注入网络只放行已配置样板的输出物品
        this.filterImportButton = new ToggleButton(Icon.FILTER_ON_EXTRACT_ENABLED,
                Icon.FILTER_ON_EXTRACT_DISABLED, btn -> this.menu.toggleFilteredImport());
        this.filterImportButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.filtered_import_on"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.filtered_import_on_hint")));
        this.filterImportButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.filtered_import_off"),
                Component.translatable("gui.chexsonsaeutils.frame_pattern_provider.filtered_import_off_hint")));
        this.addToLeftToolbar(this.filterImportButton);
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
        this.extractButton.setState(this.menu.isActiveExtract());
        this.configButton.setState(this.menu.isConfigMode());
        this.filterImportButton.setState(this.menu.isFilteredImport());
        // 翻页时重摆样板槽使布局函数按新页号重算，并清除悬停残留（照 ExtendedAE_Plus）
        if (this.lastRenderedPage != this.menu.getPage()) {
            this.lastRenderedPage = this.menu.getPage();
            this.repositionSlots(SlotSemantics.ENCODED_PATTERN);
            this.repositionSlots(SlotSemantics.STORAGE);
            this.hoveredSlot = null;
        }
        // 需求 5：每帧按当前页刷新样板槽渲染可见性（服务端另有 isSlotEnabled 交互防护）
        this.updatePageSlotActivity();
        this.prevPageButton.setVisibility(this.menu.getPage() > 0);
        this.nextPageButton.setVisibility(this.menu.getPage() < this.menu.getPages() - 1);
    }

    /**
     * 需求 5：按当前页设置样板槽渲染可见性（每页 36 槽）。
     * <p>
     * 动机（遍历计数器）：AppEngSlot 未覆写 getSlotIndex()（继承 Slot.getSlotIndex() →
     * this.index），AbstractContainerMenu.addSlot 会覆盖 slot.index = slots.size()
     * （菜单槽位序号）——父类构造器先建 36 个玩家槽（index 0-35），样板槽从菜单
     * index 36 起，getSlotIndex() = 36 + 库存索引，页号计算偏移错乱。
     * 故改为遍历 ENCODED_PATTERN 槽位列表用计数器 0..N-1 算页号（照 ExtendedAE_Plus
     * 客户端实现）。
     * <p>
     * 只 setActive 不 setSlotEnabled：客户端 setSlotEnabled(false) 会让
     * AppEngSlot.getItem() 返回 EMPTY 干扰渲染；交互防护由服务端 Menu 的
     * setSlotEnabled 承担。
     */
    private void updatePageSlotActivity() {
        int currentPage = this.menu.getPage();
        var slots = this.menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        int unlockedSlots = Math.min(slots.size(),
                this.menu.getPages() * FramePatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE);
        int slotId = 0;
        for (var slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot) {
                int slotPage = slotId / FramePatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE;
                boolean unlocked = slotId < unlockedSlots;
                appEngSlot.setActive(unlocked && slotPage == currentPage);
                ++slotId;
            }
        }
    }

    /**
     * 需求 5：页号绘制在右上角（"当前页/总页数"，1 起）。
     * <p>
     * 位置依据（S3 修复）：与布局 json 的 dialog_title（left 8, top 6）同一水平线，
     * 右对齐到背景右缘（imageWidth，extendedae 布局 176 宽）8px 边距。
     * 相对坐标：renderLabels 前已有 translate(leftPos, topPos)，drawFG 内不得再加窗口偏移。
     */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);
        String pageText = (this.menu.getPage() + 1) + "/" + this.menu.getPages();
        guiGraphics.drawString(
                this.font,
                pageText,
                this.imageWidth - 8 - this.font.width(pageText),
                // y=22 避让右上角 openPriority 按钮（json: top -5, 高 20，底缘 15）
                22,
                0x404040
        );
    }

    /**
     * 多页摆位接口实现（照 ExtendedAE_Plus IExPatternPage）：页号由菜单 @GuiSync
     * 服务端权威同步，网格布局 mixin 经本方法读取当前页。
     */
    @Override
    public int chexsonsaeutils$getCurrentPage() {
        return this.menu.getPage();
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