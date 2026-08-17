package git.chexson.chexsonsaeutils.client.gui.framepatternprovider;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.menu.SlotSemantics;
import git.chexson.chexsonsaeutils.menu.framepatternprovider.FramePatternProviderMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 框架样板供应器屏幕。
 * <p>
 * 布局由 {@code assets/ae2/screens/frame_pattern_provider.json} 定义（StyleManager 从
 * {@code ae2:screens/} 命名空间加载），包含 36 样板槽、9 格返回库存与升级卡面板。
 * 阶段 3 接入样板推送逻辑后，在此屏幕补充状态展示与操作按钮。
 */
public class FramePatternProviderScreen extends AEBaseScreen<FramePatternProviderMenu> {

    public FramePatternProviderScreen(FramePatternProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/frame_pattern_provider.json"));
        this.widgets.add("upgrades", new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE), menu.getHost()));
    }
}