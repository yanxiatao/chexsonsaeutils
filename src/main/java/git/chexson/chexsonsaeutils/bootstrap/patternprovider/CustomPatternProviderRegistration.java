package git.chexson.chexsonsaeutils.bootstrap.patternprovider;

import git.chexson.chexsonsaeutils.block.custompatternprovider.CustomPatternProviderBlock;
import git.chexson.chexsonsaeutils.item.custompatternprovider.CustomPatternProviderItem;
import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** 定制样板供应器方块与物品的注册装配，集中持有注册把手与构造点。 */
public final class CustomPatternProviderRegistration {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModIds.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModIds.MODID);

    /** 定制样板供应器方块（阶段 2）：机器即周围相邻方块，无方块级 ticker。 */
    public static final Supplier<CustomPatternProviderBlock> CUSTOM_PATTERN_PROVIDER_BLOCK =
            BLOCKS.register("custom_pattern_provider", CustomPatternProviderBlock::new);
    /** 定制样板供应器物品（阶段 2）：BlockItem 默认放置，页数经 CUSTOM_PATTERN_PAGES 组件保留闭环。 */
    public static final Supplier<Item> CUSTOM_PATTERN_PROVIDER_ITEM =
            ITEMS.register("custom_pattern_provider",
                    () -> new CustomPatternProviderItem(CUSTOM_PATTERN_PROVIDER_BLOCK.get(), new Item.Properties()));

    private CustomPatternProviderRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
