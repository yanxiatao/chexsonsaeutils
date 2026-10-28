package git.chexson.chexsonsaeutils.bootstrap.playersimulation;

import appeng.api.parts.PartModels;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import appeng.items.parts.PartItem;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.menu.playersimulation.PlayerSimulationMenu;
import git.chexson.chexsonsaeutils.parts.playersimulation.PlayerSimulationPart;
import net.minecraft.world.item.Item;

/** 玩家模拟面板的物品、菜单打开行为、升级卡及模型装配。 */
public final class PlayerSimulationRegistration {
    private PlayerSimulationRegistration() {}
    /** 创建部件物品并注入菜单打开器。 */
    public static Item createItem() {
        return new PartItem<>(new Item.Properties(),PlayerSimulationPart.class,
                item -> new PlayerSimulationPart(item,(player,part) -> MenuOpener.open(PlayerSimulationMenu.TYPE,player,MenuLocators.forPart(part))));
    }
    /** 预初始化阶段注册动态面板的全部模型，避免 AE2 冻结模型表后才注册。 */
    public static void registerModels() {
        PartModels.registerModels(PlayerSimulationPart.getModels().stream()
                .flatMap(model -> model.getModels().stream()).toList());
    }
    /** 公共启动阶段注册原生升级卡。 */
    public static void bootstrap(Item item) {
        Upgrades.add(AEItems.CRAFTING_CARD,item,1);
        Upgrades.add(AEItems.FUZZY_CARD,item,1);
        Upgrades.add(AEItems.REDSTONE_CARD,item,1);
        Upgrades.add(AEItems.SPEED_CARD,item,4);
        Upgrades.add(AEItems.CAPACITY_CARD,item,5);
    }
}
