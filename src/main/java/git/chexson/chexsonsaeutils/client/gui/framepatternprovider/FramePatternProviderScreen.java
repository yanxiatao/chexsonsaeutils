package git.chexson.chexsonsaeutils.client.gui.framepatternprovider;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import git.chexson.chexsonsaeutils.menu.framepatternprovider.FramePatternProviderMenu;
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
 * 点击后服务端把私有维度机器输出抽取到返回库存）与样板配置按钮（需求 4b：
 * 配置模式下点击处理样板槽位打开配置 GUI）。
 */
public class FramePatternProviderScreen extends AEBaseScreen<FramePatternProviderMenu> {

    private final ToggleButton isolatedButton;
    private final ToggleButton configButton;

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
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.isolatedButton.setState(this.menu.isIsolated());
        this.configButton.setState(this.menu.isConfigMode());
    }

    /**
     * 配置模式下拦截供应器样板槽位的处理样板点击：不执行普通槽位操作，
     * 改为请求服务端打开配置 GUI（需求 4b）。
     * I1 修复：只拦截 ENCODED_PATTERN 语义槽（供应器样板槽），背包槽位
     * 的正常物品移动/样板拿取不被劫持。
     */
    @Override
    protected void slotClicked(Slot slot, int slotIndex, int button, ClickType clickType) {
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