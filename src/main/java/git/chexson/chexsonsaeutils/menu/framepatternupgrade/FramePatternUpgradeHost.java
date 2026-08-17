package git.chexson.chexsonsaeutils.menu.framepatternupgrade;

import net.minecraft.world.item.ItemStack;

import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import git.chexson.chexsonsaeutils.integration.extendedae.ExtendedAeCompat;
import git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem;
import git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeMenu.InventoryChangedHandler;

/**
 * 扩容 GUI 的菜单宿主（MenuHost）。
 * <p>
 * 动机：扩容 GUI 操作的是「物品」（框架供应器物品 + ExtendedAE 扩展样板供应器），
 * 不依附任何方块，需要瞬态宿主承载会话库存：槽 0 = 存储槽（框架供应器物品，仅 1 个）、
 * 槽 1 = 输入槽（仅 ExtendedAE 扩展样板供应器物品，过滤器在库存层实现——
 * AppEngSlot.mayPlace 委托 inventory.isItemValid）。宿主由
 * {@link FramePatternUpgradeLocator} 在打开菜单时构造，菜单关闭即丢弃，
 * 无需持久化（saveChangedInventory 为空实现）。
 * <p>
 * 变更转发：库存变更经 onChangeInventory 转发到服务端 Menu 逻辑（重算可扩容状态）。
 */
public class FramePatternUpgradeHost implements InternalInventoryHost {

    /** 槽 0 = 框架供应器物品（存储槽），槽 1 = ExtendedAE 扩展样板供应器（输入槽）。 */
    private final AppEngInternalInventory inventory = new AppEngInternalInventory(this, 2) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // S3 修复：与默认 isItemValid 语义一致（maxStack 为 0 的槽位拒绝一切）
            if (getSlotLimit(slot) == 0) {
                return false;
            }
            // S2 修复：存储槽仅接受框架供应器物品（其他 IStorageComponent 不可放入）；
            // 输入槽仅接受 ExtendedAE 扩展样板供应器
            return switch (slot) {
                case 0 -> stack.getItem() instanceof FramePatternProviderItem;
                case 1 -> ExtendedAeCompat.isExPatternProvider(stack);
                default -> super.isItemValid(slot, stack);
            };
        }
    };
    private InventoryChangedHandler invChangeHandler;

    public AppEngInternalInventory getInventory() {
        return this.inventory;
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
        // 与 FramePatternConfigHost 同设计：客户端副本的库存变更不允许产生服务端行为，
        // AppEngInternalInventory 仅在 false 时走保存/通知路径，保持 false 最符合设计意图。
        return false;
    }
}
