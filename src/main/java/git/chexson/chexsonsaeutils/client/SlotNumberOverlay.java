package git.chexson.chexsonsaeutils.client;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import appeng.items.tools.NetworkToolItem;

/**
 * 槽位编号显示（需求 4）：背包有网络工具时，任意 GUI 中按住 Alt+I 显示当前 GUI
 * 物品槽位编号（不含背包槽）。
 * <p>
 * 动机：AE2 无现成槽位编号显示功能，自建客户端渲染；网络工具检测复用 AE2 现成
 * 静态 API {@link NetworkToolItem#findNetworkToolInv}（遍历背包找网络工具）。
 * <p>
 * 成员作用：
 * <ul>
 *   <li>{@link #SHOW_SLOT_NUMBERS}：键位（默认 Alt+I，可在游戏设置修改）。</li>
 *   <li>{@link #registerKeyMapping}：mod 总线事件，注册键位。</li>
 *   <li>{@link #onScreenRender}：游戏总线事件，每帧判断键位按下 + 网络工具存在，
 *       在槽位位置渲染编号。</li>
 * </ul>
 * <p>
 * 开关行为：按住显示（isDown 每帧判断），松开隐藏——临时查看辅助功能，不改变
 * GUI 状态，无需 toggle 状态管理。
 */
public final class SlotNumberOverlay {

    /**
     * 显示槽位编号键位：默认 I 键 + Alt 修饰（1.21.1 KeyMapping 无修饰键构造，
     * Alt 修饰在 {@link #onScreenRender} 中经 {@link Screen#hasAltDown()} 判定；
     * 键位可在游戏设置修改 I 键，Alt 修饰固定）。
     */
    public static final KeyMapping SHOW_SLOT_NUMBERS = new KeyMapping(
            "key.chexsonsaeutils.show_slot_numbers",
            GLFW.GLFW_KEY_I,
            "key.chexsonsaeutils.category");

    private SlotNumberOverlay() {
    }

    /**
     * 注册键位（mod 总线 RegisterKeyMappingsEvent）。
     *
     * @param event 键位注册事件
     */
    public static void registerKeyMapping(RegisterKeyMappingsEvent event) {
        event.register(SHOW_SLOT_NUMBERS);
    }

    /**
     * 渲染槽位编号（游戏总线 ScreenEvent.Render.Post）。
     * <p>
     * 条件：键位按住 + 当前屏幕是容器 GUI + 玩家背包有网络工具。
     * 跳过玩家背包槽（{@code slot.container instanceof Inventory}），其余槽位
     * 在槽位左上角渲染原始索引编号。
     *
     * @param event 屏幕渲染后事件
     */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!SHOW_SLOT_NUMBERS.isDown() || !Screen.hasAltDown()) {
            return;
        }
        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null || NetworkToolItem.findNetworkToolInv(player) == null) {
            return;
        }
        var guiGraphics = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(containerScreen.getGuiLeft(), containerScreen.getGuiTop(), 0);
        for (var slot : containerScreen.getMenu().slots) {
            // 跳过玩家背包槽（含快捷栏），只显示 GUI 自身槽位
            if (slot.container instanceof Inventory) {
                continue;
            }
            guiGraphics.drawString(font, String.valueOf(slot.index), slot.x, slot.y, 0xFFFFFFFF);
        }
        guiGraphics.pose().popPose();
    }
}