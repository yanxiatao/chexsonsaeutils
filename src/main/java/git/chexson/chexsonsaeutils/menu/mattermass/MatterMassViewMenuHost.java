package git.chexson.chexsonsaeutils.menu.mattermass;

import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.api.storage.SupplierStorage;
import appeng.api.util.IConfigManager;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.locator.ItemMenuHostLocator;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassItem;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 物质团查看菜单宿主（照 AE2 便携元件 {@code PortableCellMenuHost} 范式）。
 * <p>
 * 实现 {@link ITerminalHost} 并绑定 {@link MEStorageMenu}：屏幕侧用 AE2 自带
 * {@code MEStorageScreen} + {@code /screens/terminals/portable_item_cell.json}，
 * 得到与便携元件一致的终端存储视图（排序/搜索/数量显示）。
 * 内容物经 {@link StorageCells#getCellInventory} 解析为物质团只读单元
 * （{@link git.chexson.chexsonsaeutils.cell.MatterMassCellInventory}）。
 * 配置项（排序等）不落盘，避免污染物质团物品组件。
 * <p>
 * 只读语义 = 可取出、不可放入：取出由本宿主 {@link IEnergySource} 无限供能支撑
 * （否则终端因无能量无法提取）；放入被单元 {@code insert} 恒 0 拒绝。
 */
public class MatterMassViewMenuHost extends ItemMenuHost<MatterMassItem>
        implements ITerminalHost, IEnergySource {

    public static final MenuType<MEStorageMenu> TYPE = MenuTypeBuilder
            .<MEStorageMenu, MatterMassViewMenuHost>create(MEStorageMenu::new, MatterMassViewMenuHost.class)
            .withMenuTitle(host -> host.getItemStack().getHoverName())
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "matter_mass_view"));

    private final MEStorage cellStorage;
    private final IConfigManager configManager;

    public MatterMassViewMenuHost(Player player, ItemMenuHostLocator locator) {
        super(ChexsonsaeutilsContent.MATTER_MASS_ITEM.get(), player, locator);
        this.cellStorage = new SupplierStorage(new CellStorageSupplier());
        // 内存配置（不落盘）：便携元件同款设置项，排序/视图/方向
        this.configManager = IConfigManager.builder(() -> {
        })
                .registerSetting(Settings.SORT_BY, SortOrder.NAME)
                .registerSetting(Settings.VIEW_MODE, ViewItems.ALL)
                .registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING)
                .build();
    }

    @Override
    public MEStorage getInventory() {
        return cellStorage;
    }

    @Override
    public IConfigManager getConfigManager() {
        return configManager;
    }

    /** 物质团是本地物品存储，无网络连接概念：恒已连接（不显示离线提示）。 */
    @Override
    public ILinkStatus getLinkStatus() {
        return ILinkStatus.ofConnected();
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(TYPE, player, subMenu.getLocator());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return getItemStack();
    }

    /**
     * 物质团不耗电：恒定按请求量"供能"。
     * 动机：MEStorageMenu 对非能量宿主回退 {@code IEnergySource.empty()}（供能恒 0），
     * 终端取出走 {@code StorageHelper.poweredExtraction} 按可用电力折算提取量，
     * 供能为 0 则任何物品都取不出。实现本接口解除该限制；物质团本身不消耗任何能量。
     */
    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
        return amt;
    }

    /**
     * 物品栈每次访问重新定位（玩家可能移动物品）：栈变化时重建单元库存。
     * 照 {@code PortableCellMenuHost.CellStorageSupplier}。
     */
    private class CellStorageSupplier implements Supplier<MEStorage> {
        private MEStorage currentStorage;
        private ItemStack currentStack;

        @Override
        public MEStorage get() {
            var stack = getItemStack();
            if (stack != currentStack) {
                currentStorage = StorageCells.getCellInventory(stack, null);
                currentStack = stack;
            }
            return currentStorage;
        }
    }
}
