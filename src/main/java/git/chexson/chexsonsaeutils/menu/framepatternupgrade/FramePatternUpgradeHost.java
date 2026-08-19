package git.chexson.chexsonsaeutils.menu.framepatternupgrade;

import net.minecraft.world.item.ItemStack;

import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogicHost;
import git.chexson.chexsonsaeutils.integration.extendedae.ExtendedAeCompat;
import git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeMenu.InventoryChangedHandler;

/**
 * 扩容 GUI 的菜单宿主（MenuHost）。
 * <p>
 * 动机：扩容 GUI 直接作用于打开它的供应器（方块实体或面板），宿主持有
 * {@link FramePatternProviderLogicHost} 引用（由 {@link FramePatternUpgradeLocator}
 * 在打开菜单时解析），页数读写直接委托宿主（setPages 持久化）。
 * 会话库存仅 1 槽：输入槽（仅 ExtendedAE 扩展样板供应器物品，过滤器在库存层实现——
 * AppEngSlot.mayPlace 委托 inventory.isItemValid）。宿主由 locator 构造，
 * 菜单关闭即丢弃，无需持久化（saveChangedInventory 为空实现）。
 * <p>
 * 变更转发：库存变更经 onChangeInventory 转发到服务端 Menu 逻辑（重算可扩容状态）。
 */
public class FramePatternUpgradeHost implements InternalInventoryHost {

    /** 槽 0 = ExtendedAE 扩展样板供应器（输入槽）。 */
    private final AppEngInternalInventory inventory = new AppEngInternalInventory(this, 1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // S3 修复：与默认 isItemValid 语义一致（maxStack 为 0 的槽位拒绝一切）
            if (getSlotLimit(slot) == 0) {
                return false;
            }
            // 输入槽仅接受 ExtendedAE 扩展样板供应器
            return ExtendedAeCompat.isExPatternProvider(stack);
        }
    };
    private final FramePatternProviderLogicHost pageHolder;
    private InventoryChangedHandler invChangeHandler;

    public FramePatternUpgradeHost(FramePatternProviderLogicHost pageHolder) {
        this.pageHolder = pageHolder;
    }

    public AppEngInternalInventory getInventory() {
        return this.inventory;
    }

    /**
     * @return 扩容目标宿主（方块实体或面板，页数读写委托对象）
     */
    public FramePatternProviderLogicHost getPageHolder() {
        return this.pageHolder;
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