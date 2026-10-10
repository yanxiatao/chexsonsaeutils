package git.chexson.chexsonsaeutils.crafting.directprocessing;

import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 直采加工 mock 配方的注册宿主（D7-1 自持）：recipe type 与 recipe serializer 两枚把手
 * 连同条目都留在 crafting.directprocessing 包内，registration 侧只在 register 里 dispatch 一次。
 */
public final class DirectProcessingRecipeRegistration {

    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, ModIds.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, ModIds.MODID);

    public static final Supplier<RecipeType<DirectProcessingMockRecipe>> DIRECT_PROCESSING_MOCK_RECIPE_TYPE =
            RECIPE_TYPES.register("direct_processing_mock", DirectProcessingMockRecipe::createType);
    public static final Supplier<RecipeSerializer<DirectProcessingMockRecipe>>
            DIRECT_PROCESSING_MOCK_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register(
                    "direct_processing_mock",
                    DirectProcessingMockRecipe::createSerializer
            );

    public static final Supplier<RecipeType<DirectProcessingPackRecipe>> PACK_RECIPE_TYPE = RECIPE_TYPES.register(
            "direct_processing", () -> new RecipeType<>() {
                @Override public String toString() { return DirectProcessingPackRecipe.TYPE_ID.toString(); }
            });
    public static final Supplier<RecipeSerializer<DirectProcessingPackRecipe>> PACK_RECIPE_SERIALIZER =
            RECIPE_SERIALIZERS.register("direct_processing", DirectProcessingPackRecipe.Serializer::new);

    private DirectProcessingRecipeRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        RECIPE_TYPES.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
    }
}
