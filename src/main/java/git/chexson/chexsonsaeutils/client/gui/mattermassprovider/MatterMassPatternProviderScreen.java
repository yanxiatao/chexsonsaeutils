package git.chexson.chexsonsaeutils.client.gui.mattermassprovider;

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
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import git.chexson.chexsonsaeutils.blockentity.mattermassprovider.MatterMassPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.client.gui.MultiPagePatternScreen;
import git.chexson.chexsonsaeutils.menu.mattermassprovider.MatterMassPatternProviderMenu;

/**
 * 物质团供应器屏幕（专用 {@link AEBaseScreen}，不继承 PatternProviderScreen）。
 * <p>
 * 动机：PatternProviderScreen 会强制添加阻塞模式/锁定模式/样板访问终端/优先级等
 * 本机不适用的按钮与 lockReason 指示器；物质团供应器只需返回目标切换、翻页、扩容。
 * <p>
 * 布局由 {@code assets/ae2/screens/matter_mass_pattern_provider.json} 定义
 * （生成式背景，36 样板槽 + 玩家背包；无返回栏、无升级面板、无优先级控件）。
 */
public class MatterMassPatternProviderScreen extends AEBaseScreen<MatterMassPatternProviderMenu>
        implements MultiPagePatternScreen {

    private final ToggleButton returnModeButton;
    private final IconButton prevPageButton;
    private final IconButton nextPageButton;

    /** 上一次渲染的页号：检测翻页以重摆槽位并清除悬停残留。 */
    private int lastRenderedPage = -1;

    public MatterMassPatternProviderScreen(MatterMassPatternProviderMenu menu, Inventory playerInventory,
            Component title) {
        super(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/matter_mass_pattern_provider.json"));

        // 返回目标切换：false = AE 网络（AUTO_EXPORT_OFF 图标），true = 玩家背包（AUTO_EXPORT_ON 图标）
        this.returnModeButton = new ToggleButton(Icon.AUTO_EXPORT_ON, Icon.AUTO_EXPORT_OFF,
                btn -> this.menu.toggleReturnModeClient());
        this.returnModeButton.setTooltipOn(List.of(
                Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.return_player"),
                Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.return_player_hint")));
        this.returnModeButton.setTooltipOff(List.of(
                Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.return_network"),
                Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.return_network_hint")));
        this.addToLeftToolbar(this.returnModeButton);

        // 翻页按钮（边界页隐藏）
        this.prevPageButton = new IconButton(btn -> this.menu.setPage(this.menu.getPage() - 1)) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_LEFT;
            }
        };
        this.prevPageButton.setMessage(
                Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.prev_page"));
        this.addToLeftToolbar(this.prevPageButton);
        this.nextPageButton = new IconButton(btn -> this.menu.setPage(this.menu.getPage() + 1)) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_RIGHT;
            }
        };
        this.nextPageButton.setMessage(
                Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.next_page"));
        this.addToLeftToolbar(this.nextPageButton);

        // 扩容按钮：打开扩容 GUI（复用定制样板供应器扩容体系）
        IconButton upgradeButton = new IconButton(btn -> this.menu.openUpgradeGuiClient()) {
            @Override
            protected Icon getIcon() {
                return Icon.ENTER;
            }
        };
        upgradeButton.setMessage(Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.upgrade")
                .append("\n")
                .append(Component.translatable("gui.chexsonsaeutils.matter_mass_pattern_provider.upgrade_hint")));
        this.addToLeftToolbar(upgradeButton);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.returnModeButton.setState(this.menu.isReturnToPlayer());
        // 翻页时重摆样板槽并清除悬停残留（照 ExtendedAE_Plus）
        if (this.lastRenderedPage != this.menu.getPage()) {
            this.lastRenderedPage = this.menu.getPage();
            this.repositionSlots(SlotSemantics.ENCODED_PATTERN);
            this.hoveredSlot = null;
        }
        // 每帧按当前页刷新样板槽渲染可见性（服务端另有 isSlotEnabled 交互防护）
        this.updatePageSlotActivity();
        this.prevPageButton.setVisibility(this.menu.getPage() > 0);
        this.nextPageButton.setVisibility(this.menu.getPage() < this.menu.getPages() - 1);
    }

    /**
     * 按当前页设置样板槽渲染可见性（每页 36 槽，照
     * CustomPatternProviderScreen.updatePageSlotActivity）。只 setActive 不
     * setSlotEnabled：客户端 setSlotEnabled(false) 会让 AppEngSlot.getItem()
     * 返回 EMPTY 干扰渲染；交互防护由服务端 Menu 承担。
     */
    private void updatePageSlotActivity() {
        int currentPage = this.menu.getPage();
        var slots = this.menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        int unlockedSlots = Math.min(slots.size(),
                this.menu.getPages() * MatterMassPatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE);
        int slotId = 0;
        for (var slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot) {
                int slotPage = slotId / MatterMassPatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE;
                boolean unlocked = slotId < unlockedSlots;
                appEngSlot.setActive(unlocked && slotPage == currentPage);
                ++slotId;
            }
        }
    }

    /** 页号绘制在右上角（"当前页/总页数"，1 起），照 CustomPatternProviderScreen.drawFG。 */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        if (this.menu.getPages() > 1) {
            String pageText = (this.menu.getPage() + 1) + "/" + this.menu.getPages();
            guiGraphics.drawString(
                    this.font,
                    pageText,
                    this.imageWidth - 8 - this.font.width(pageText),
                    22,
                    0x404040
            );
        }
    }

    /** 多页摆位接口实现（照 ExtendedAE_Plus IExPatternPage）：布局 mixin 经本方法读当前页。 */
    @Override
    public int chexsonsaeutils$getCurrentPage() {
        return this.menu.getPage();
    }

    /** 非当前页样板槽（渲染隐藏）的点击直接忽略，双保险防伪造。 */
    @Override
    protected void slotClicked(Slot slot, int slotIndex, int button, ClickType clickType) {
        if (slot instanceof AppEngSlot appEngSlot
                && !appEngSlot.isActive()
                && this.menu.getSlots(SlotSemantics.ENCODED_PATTERN).contains(slot)) {
            return;
        }
        super.slotClicked(slot, slotIndex, button, clickType);
    }
}
