package git.chexson.chexsonsaeutils.crafting.mattermass;

import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 物质团样板注册宿主（M1 自持）：ITEMS 与 DATA_COMPONENT_TYPES 两份把手连同
 * matter_mass_pattern／encoded_matter_mass_pattern 两枚条目都留在 crafting.mattermass 包内，
 * registration 侧只在 register 里 dispatch 一次。
 */
public final class MatterMassPatternRegistration {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModIds.MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, ModIds.MODID);

    public static final Supplier<MatterMassPatternItem> MATTER_MASS_PATTERN_ITEM =
            ITEMS.register("matter_mass_pattern", MatterMassPatternItem::createItem);
    public static final Supplier<DataComponentType<EncodedMatterMassPattern>> ENCODED_MATTER_MASS_PATTERN =
            DATA_COMPONENT_TYPES.register("encoded_matter_mass_pattern",
                    () -> DataComponentType.<EncodedMatterMassPattern>builder()
                            .persistent(EncodedMatterMassPattern.CODEC)
                            .networkSynchronized(EncodedMatterMassPattern.STREAM_CODEC)
                            .build());

    private MatterMassPatternRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        DATA_COMPONENT_TYPES.register(modEventBus);
    }
}
