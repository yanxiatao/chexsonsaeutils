package git.chexson.chexsonsaeutils.client.gui.framepatternprovider;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.menu.SlotSemantics;
import git.chexson.chexsonsaeutils.menu.framepatternprovider.FramePatternProviderMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 框架样板供应器屏幕。
 * <p>
 * 布局由 {@code assets/ae2/screens/frame_pattern_provider.json} 定义（StyleManager 从
 * {@code ae2:screens/} 命名空间加载），包含 36 样板槽、9 格返回库存与升级卡面板。
 * 左工具栏提供隔离模式切换按钮：隔离（锁定图标）只共享能量，非隔离（解锁图标）并入主网格。
 */
public class FramePatternProviderScreen extends AEBaseScreen<FramePatternProviderMenu> {

    private final ToggleButton isolatedButton;

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
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.isolatedButton.setState(this.menu.isIsolated());
    }
}