package git.chexson.chexsonsaeutils.bootstrap.patternprovider;

import git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPartItem;
import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** 定制样板供应器面板的注册装配，持有物品注册把手与面板工厂。 */
public final class CustomPatternProviderPartRegistration {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModIds.MODID);

    /** 定制样板供应器面板（阶段 3）：PartItem 工厂创建面板实例，模型经 PartModels 注册。 */
    public static final Supplier<Item> CUSTOM_PATTERN_PROVIDER_PART_ITEM =
            ITEMS.register("custom_pattern_provider_part",
                    () -> new CustomPatternProviderPartItem(new Item.Properties()));

    private CustomPatternProviderPartRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
