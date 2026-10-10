package git.chexson.chexsonsaeutils.parts.automation.model;

/** 发信器共享的注册标识与槽位容量，供运行部件、菜单和装配独立引用。 */
public final class MultiLevelEmitterDefinition {
    public static final String REGISTRY_PATH = "multi_level_emitter";
    public static final String MENU_BINDING_KEY = REGISTRY_PATH + "_menu";
    public static final String SCREEN_BINDING_KEY = REGISTRY_PATH + "_screen";
    public static final int SLOT_CAPACITY = 64;

    private MultiLevelEmitterDefinition() {
    }
}
