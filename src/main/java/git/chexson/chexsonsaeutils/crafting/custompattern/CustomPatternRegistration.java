package git.chexson.chexsonsaeutils.crafting.custompattern;

import com.mojang.serialization.Codec;
import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 定制样板注册宿主（D7-2 自持）：ITEMS 与 DATA_COMPONENT_TYPES 两份把手连同
 * custom_pattern／encoded_custom_pattern／custom_pattern_pages 三枚条目都留在
 * crafting.custompattern 包内，registration 侧只在 register 里 dispatch 一次。
 */
public final class CustomPatternRegistration {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModIds.MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, ModIds.MODID);

    public static final Supplier<CustomPatternItem> CUSTOM_PATTERN_ITEM =
            ITEMS.register("custom_pattern", CustomPatternItem::createItem);
    public static final Supplier<DataComponentType<EncodedCustomPattern>> ENCODED_CUSTOM_PATTERN =
            DATA_COMPONENT_TYPES.register("encoded_custom_pattern",
                    () -> DataComponentType.<EncodedCustomPattern>builder()
                            .persistent(EncodedCustomPattern.CODEC)
                            .networkSynchronized(EncodedCustomPattern.STREAM_CODEC)
                            .build());
    /** 定制样板供应器的已扩展样板页数（拆除保留闭环：BE NBT → 掉落物品组件 → 放置/捕获读回）。 */
    public static final Supplier<DataComponentType<Integer>> CUSTOM_PATTERN_PAGES =
            DATA_COMPONENT_TYPES.register("custom_pattern_pages",
                    () -> DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.VAR_INT)
                            .build());

    private CustomPatternRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        DATA_COMPONENT_TYPES.register(modEventBus);
    }
}
