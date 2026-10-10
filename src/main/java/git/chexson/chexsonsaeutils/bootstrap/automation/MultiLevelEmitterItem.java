package git.chexson.chexsonsaeutils.bootstrap.automation;

import appeng.items.parts.PartItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.model.MultiLevelEmitterDefinition;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import net.minecraft.world.item.Item;

import java.util.Locale;

/** 发信器物品装配；创建运行部件时注入具体菜单的打开行为。 */
public final class MultiLevelEmitterItem {

    public static final String REGISTRY_PATH = MultiLevelEmitterDefinition.REGISTRY_PATH;
    public static final String MENU_BINDING_KEY = MultiLevelEmitterDefinition.MENU_BINDING_KEY;
    public static final String SCREEN_BINDING_KEY = MultiLevelEmitterDefinition.SCREEN_BINDING_KEY;

    private MultiLevelEmitterItem() {
    }

    public static String id() {
        return REGISTRY_PATH;
    }

    public static Item createItem() {
        return new PartItem<>(new Item.Properties(), MultiLevelEmitterRuntimePart.class,
                partItem -> new MultiLevelEmitterRuntimePart(partItem, MultiLevelEmitterMenu::openMenu));
    }

    public static boolean isRegistryPath(String path) {
        if (path == null) {
            return false;
        }
        return REGISTRY_PATH.equals(path.toLowerCase(Locale.ROOT));
    }
}
