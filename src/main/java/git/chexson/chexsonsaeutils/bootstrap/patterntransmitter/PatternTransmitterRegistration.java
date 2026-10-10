package git.chexson.chexsonsaeutils.bootstrap.patterntransmitter;
import appeng.api.AECapabilities;
import appeng.api.crafting.PatternDetailsHelper;
import git.chexson.chexsonsaeutils.block.patterntransmitter.PatternTransmitterBlock;
import git.chexson.chexsonsaeutils.blockentity.patterntransmitter.PatternTransmitterBlockEntity;
import git.chexson.chexsonsaeutils.crafting.remote.RemotePatternItem;
import git.chexson.chexsonsaeutils.helpers.remote.RemoteTransmitterImpl;
import git.chexson.chexsonsaeutils.menu.patterntransmitter.PatternTransmitterMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** 方块、内部样板、菜单、真实编解码及双节点能力的集中装配。 */
public final class PatternTransmitterRegistration {
    private static final String MOD = "chexsonsaeutils";
    /** 独立登记表及绑定把手，供装配与真实注册契约检查使用。 */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD);
    public static final Supplier<PatternTransmitterBlock> BLOCK = BLOCKS.register("pattern_transmitter", PatternTransmitterBlock::new);
    public static final Supplier<Item> ITEM = ITEMS.register("pattern_transmitter", () -> new BlockItem(BLOCK.get(), new Item.Properties().stacksTo(1)));
    public static final Supplier<RemotePatternItem> PATTERN = ITEMS.register("remote_pattern", () -> new RemotePatternItem(new Item.Properties()));
    public static final Supplier<BlockEntityType<PatternTransmitterBlockEntity>> ENTITY = ENTITIES.register("pattern_transmitter",
            () -> BlockEntityType.Builder.of(PatternTransmitterBlockEntity::new, BLOCK.get()).build(null));
    public static final Supplier<MenuType<PatternTransmitterMenu>> MENU = MENUS.register("pattern_transmitter", () -> PatternTransmitterMenu.TYPE);
    private PatternTransmitterRegistration() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); }
    public static void bootstrap() {
        BLOCK.get().setBlockEntity(PatternTransmitterBlockEntity.class, ENTITY.get(), null,
                (level, pos, state, entity) -> entity.transmitter().tick());
        appeng.blockentity.AEBaseBlockEntity.registerBlockEntityItem(ENTITY.get(), ITEM.get());
        RemoteTransmitterImpl.bindEncoding(PATTERN.get()::encode);
        PatternDetailsHelper.registerDecoder(RemotePatternItem.DECODER);
        git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.bindServer(RemotePreviewAssembly::request);
    }
    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, ENTITY.get(), (entity, context) -> entity);
    }
}
