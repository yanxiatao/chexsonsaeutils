package git.chexson.chexsonsaeutils.menu.framepatternconfig;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

/**
 * 框架样板配置 GUI 的菜单宿主（MenuHost）。
 * <p>
 * 动机：配置 GUI 不依附任何方块或手持物品（从供应器 GUI 的样板槽位打开），
 * 需要一个瞬态宿主承载配置会话状态：2 格库存（槽 0 = 输入处理样板，
 * 槽 1 = 输出框架样板）与槽位映射（slotMapping/extractSlots）。
 * 宿主由 {@link FramePatternConfigLocator} 在打开菜单时构造，菜单关闭即丢弃，
 * 无需持久化（saveChangedInventory 为空实现）。
 */
public class FramePatternConfigHost implements InternalInventoryHost {

    /** 宿主库存变更回调（由菜单注册，见 {@link #setInventoryChangedHandler}）。 */
    public interface InventoryChangedHandler {
        void handleChange(InternalInventory inv, int slot);
    }

    private final AppEngInternalInventory inOutInventory = new AppEngInternalInventory(this, 2);
    private int[] slotMapping = new int[0];
    private int[] extractSlots = new int[0];
    private InventoryChangedHandler invChangeHandler;

    /**
     * @param inputPattern 打开配置 GUI 时选中的处理样板（副本放入输入槽）
     */
    public FramePatternConfigHost(ItemStack inputPattern) {
        if (!inputPattern.isEmpty()) {
            this.inOutInventory.setItemDirect(0, inputPattern.copy());
        }
    }

    public AppEngInternalInventory getInventory() {
        return this.inOutInventory;
    }

    public int[] getSlotMapping() {
        return slotMapping;
    }

    public void setSlotMapping(int[] slotMapping) {
        // clone：防御组件数组引用污染（调用方可能传入样板组件内的数组引用，
        // 直接存引用会让后续修改意外改动组件数据）
        this.slotMapping = slotMapping.clone();
    }

    public int[] getExtractSlots() {
        return extractSlots;
    }

    public void setExtractSlots(int[] extractSlots) {
        this.extractSlots = extractSlots;
    }

    public void setInventoryChangedHandler(InventoryChangedHandler handler) {
        this.invChangeHandler = handler;
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        // 瞬态宿主：菜单关闭即丢弃，无需持久化
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (this.invChangeHandler != null) {
            this.invChangeHandler.handleChange(inv, slot);
        }
    }

    @Override
    public boolean isClientSide() {
        // S2 说明：宿主在客户端也会被构造（菜单渲染副本），但固定返回 false 是刻意为之——
        // AppEngInternalInventory 仅在 isClientSide() 为 false 时走
        // enableClientEvents/保存通知路径（见 AppEngInternalInventory.isClientSide 用法），
        // 而本宿主的库存变更只允许由服务端驱动（onChangeInventory 转发到服务端 Menu 逻辑），
        // 客户端副本的变更不应产生任何服务端行为，因此保持 false 最符合设计意图。
        return false;
    }
}