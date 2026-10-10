package git.chexson.chexsonsaeutils.item.mattermass;

import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 物质团注册宿主（M2 自持）：ITEMS／MENU_TYPES／DATA_COMPONENT_TYPES 三份把手连同
 * matter_mass／matter_mass_view／matter_mass_uuid 三枚条目都留在 item.mattermass 包内，
 * registration 侧只在 {@link #register(IEventBus)} 里 dispatch 一次。
 * <p>
 * 三份 DeferredRegister 是各自 registry 上的独立实例（NeoForge 21.1.222
 * {@code DeferredRegister} 的 registeredEventBus 是实例级标志），与同包两个条目类互不引用以外
 * 只依赖 {@link ModIds#MODID}，不再回指 registration 包。
 */
public final class MatterMassRegistration {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModIds.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, ModIds.MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, ModIds.MODID);

    public static final Supplier<MatterMassItem> MATTER_MASS_ITEM =
            ITEMS.register("matter_mass", () -> new MatterMassItem(new Item.Properties().stacksTo(1)));
    /** 物质团内容物外部存储索引（内容物不进组件，保证样板上报 key 与产物 key 一致）。 */
    public static final Supplier<DataComponentType<java.util.UUID>> MATTER_MASS_UUID =
            DATA_COMPONENT_TYPES.register("matter_mass_uuid",
                    () -> DataComponentType.<java.util.UUID>builder()
                            .persistent(net.minecraft.core.UUIDUtil.CODEC)
                            .networkSynchronized(net.minecraft.core.UUIDUtil.STREAM_CODEC)
                            .build());
    public static final Supplier<MenuType<appeng.menu.me.common.MEStorageMenu>> MATTER_MASS_VIEW_MENU =
            MENU_TYPES.register("matter_mass_view", () -> MatterMassViewMenuHost.TYPE);

    private MatterMassRegistration() {
    }

    /** hookup 相对序照 Content 现有全局序：ITEMS → MENU_TYPES → DATA_COMPONENT_TYPES。 */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        DATA_COMPONENT_TYPES.register(modEventBus);
    }
}
