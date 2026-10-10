package git.chexson.chexsonsaeutils.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

import appeng.items.tools.NetworkToolItem;

/**
 * 槽位编号显示（需求 4）：背包有网络工具时，任意 GUI 中按 Alt+I 切换显示当前 GUI
 * 物品槽位编号（不含背包槽）。
 * <p>
 * 动机：AE2 无现成槽位编号显示功能，自建客户端渲染；网络工具检测复用 AE2 现成
 * 静态 API {@link NetworkToolItem#findNetworkToolInv}（遍历背包找网络工具）。
 * <p>
 * 成员作用：
 * <ul>
 *   <li>{@link #SHOW_SLOT_NUMBERS}：组合键（默认 Alt+I，可在游戏设置修改）。</li>
 *   <li>{@link #registerKeyMapping}：mod 总线事件，注册键位。</li>
 *   <li>{@link #onScreenKeyPressed}：游戏总线事件，组合键按下时切换显示标志。</li>
 *   <li>{@link #onScreenClosing}：游戏总线事件，GUI 关闭时复位显示标志。</li>
 *   <li>{@link #onScreenRender}：游戏总线事件，标志开启 + 网络工具存在时渲染编号。</li>
 * </ul>
 * <p>
 * 开关行为：toggle 切换（按一次开、再按关），不要求按住；GUI 关闭自动复位。
 * <p>
 * 触发机制说明：1.21.1 KeyboardHandler.keyPress 在 screen != null 时走 GUI 分支，
 * KeyMapping.set 只在无 GUI 分支调用 → GUI 打开期间 KeyMapping.isDown() 恒 false，
 * 因此改用 ScreenEvent.KeyPressed.Pre 直接匹配组合键（matches 只查键码，修饰键
 * 改用事件 getModifiers() 位检测）。动机：GLFW 的 glfwGetKey 在 Windows 上读取
 * Alt 键状态不可靠（实测键码 342 状态 false、修饰掩码 4 与 8 混乱），改用
 * ScreenEvent.KeyPressed 事件的修饰键掩码检测（SHIFT=1、CTRL=2、ALT=4），
 * 掩码按 getKeyModifier() 动态映射以保持组合键整体可配置。
 */
public final class SlotNumberOverlay {

    /**
     * 显示槽位编号组合键：默认 Alt+I（KeyConflictContext.GUI + KeyModifier.ALT，
     * 整个组合键可在游戏设置修改，不再固定 Alt 修饰）。
     */
    public static final KeyMapping SHOW_SLOT_NUMBERS = new KeyMapping(
            "key.chexsonsaeutils.show_slot_numbers",
            KeyConflictContext.GUI,
            KeyModifier.ALT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            "key.chexsonsaeutils.category");

    /** 显示标志（toggle 状态）：组合键按下切换，GUI 关闭复位。 */
    private static boolean showSlotNumbers = false;

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
     * 组合键按下切换显示（游戏总线 ScreenEvent.KeyPressed.Pre）。
     * <p>
     * matches(int, int) 只匹配键码不含修饰键，必须配合事件修饰键掩码检测
     * （SHIFT=1、CTRL=2、ALT=4），否则任意修饰键组合都会触发。
     * <p>
     * 修饰键掩码按 {@link #SHOW_SLOT_NUMBERS#getKeyModifier()} 动态映射
     * （NONE→0、SHIFT→1、CONTROL→2、ALT→4），保持组合键整体可配置：
     * 用户在游戏设置改修饰键后无需改代码。NONE 时要求 SHIFT/CTRL/ALT
     * 三个位都未按下（掩码 0 与任何值按位与恒为 0，不能直接判等）。
     *
     * @param event 按键按下事件（Pre，可取消）
     */
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        boolean matches = SHOW_SLOT_NUMBERS.matches(event.getKeyCode(), event.getScanCode());
        int modifierMask = switch (SHOW_SLOT_NUMBERS.getKeyModifier()) {
            case NONE -> 0;
            case SHIFT -> 1;
            case CONTROL -> 2;
            case ALT -> 4;
        };
        boolean modifierPressed = modifierMask == 0
                ? (event.getModifiers() & (1 | 2 | 4)) == 0
                : (event.getModifiers() & modifierMask) == modifierMask;
        if (matches && modifierPressed) {
            showSlotNumbers = !showSlotNumbers;
            // 取消事件：防止输入框聚焦时 Alt+I 组合键向文本框输入字符
            event.setCanceled(true);
        }
    }

    /**
     * GUI 关闭复位显示标志（游戏总线 ScreenEvent.Closing），防止残留显示。
     *
     * @param event 屏幕关闭事件
     */
    public static void onScreenClosing(ScreenEvent.Closing event) {
        showSlotNumbers = false;
    }

    /**
     * 渲染槽位编号（游戏总线 ScreenEvent.Render.Post）。
     * <p>
     * 条件：显示标志开启 + 当前屏幕是容器 GUI + 玩家背包有网络工具。
     * 跳过玩家背包槽（{@code slot.container instanceof Inventory}），其余槽位
     * 在槽位左上角渲染原始索引编号。
     *
     * @param event 屏幕渲染后事件
     */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!showSlotNumbers) {
            return;
        }
        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        var player = Minecraft.getInstance().player;
        var toolInv = player == null ? null : NetworkToolItem.findNetworkToolInv(player);
        if (player == null || toolInv == null) {
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