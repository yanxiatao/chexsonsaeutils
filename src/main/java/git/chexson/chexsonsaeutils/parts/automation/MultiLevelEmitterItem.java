package git.chexson.chexsonsaeutils.parts.automation;

import appeng.items.parts.PartItem;
import net.minecraft.world.item.Item;

import java.util.Locale;

public final class MultiLevelEmitterItem {

    public static final String REGISTRY_PATH = "multi_level_emitter";
    public static final String MENU_BINDING_KEY = REGISTRY_PATH + "_menu";
    public static final String SCREEN_BINDING_KEY = REGISTRY_PATH + "_screen";

    private MultiLevelEmitterItem() {
    }

    public static String id() {
        return REGISTRY_PATH;
    }

    public static Item createItem() {
        return new PartItem<>(new Item.Properties(), MultiLevelEmitterRuntimePart.class, MultiLevelEmitterRuntimePart::new);
    }

    public static boolean isRegistryPath(String path) {
        if (path == null) {
            return false;
        }
        return REGISTRY_PATH.equals(path.toLowerCase(Locale.ROOT));
    }
}
