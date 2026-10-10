package git.chexson.chexsonsaeutils.bootstrap.patternprovider;

import git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderRegistration;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** 定制样板供应器方块实体的注册装配，将实体工厂与对应方块类型绑定。 */
public final class CustomPatternProviderBlockEntityRegistration {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModIds.MODID);

    /** 定制样板供应器方块实体类型：只绑本方块。 */
    public static final Supplier<BlockEntityType<CustomPatternProviderBlockEntity>>
            CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "custom_pattern_provider",
                    () -> BlockEntityType.Builder.of(
                            CustomPatternProviderBlockEntity::new,
                            CustomPatternProviderRegistration.CUSTOM_PATTERN_PROVIDER_BLOCK.get()
                    ).build(null)
            );

    private CustomPatternProviderBlockEntityRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
