package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.google.gson.JsonObject;
import git.chexson.chexsonsaeutils.integration.kubejs.ChexsonsaeutilsKubeJSPlugin;
import dev.latvian.mods.kubejs.recipe.RecipeKey;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaRegistry;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaStorage;
import static org.junit.jupiter.api.Assertions.*;

class DirectProcessingPackRecipeTest {
    private static final ResourceLocation MARKER = ResourceLocation.parse("minecraft:paper");
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static DirectProcessingPackRecipe mixedRecipe() {
        return new DirectProcessingPackRecipe(List.of(MARKER),
                List.of(new SizedIngredient(Ingredient.of(Items.RAW_IRON, Items.RAW_GOLD), 3)),
                List.of(SizedFluidIngredient.of(Fluids.WATER, 500)),
                List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.STONE, 1)),
                List.of(new FluidStack(Fluids.LAVA, 250)));
    }

    @Test void alternativesPreserveExactAmountsAndAllByproducts() {
        var recipe = mixedRecipe();
        var signatures = recipe.signatures();
        assertEquals(2, signatures.size());
        assertEquals(Set.of(AEItemKey.of(Items.RAW_IRON), AEItemKey.of(Items.RAW_GOLD)),
                signatures.stream().flatMap(signature -> signature.inputs().stream())
                        .filter(input -> input.input() instanceof AEItemKey).map(RecipeSignatureInput::input).collect(Collectors.toSet()));
        for (var signature : signatures) {
            var inputs = DirectProcessingStackSupport.signatureInputAmounts(signature.inputs());
            assertEquals(500L, inputs.get(AEFluidKey.of(Fluids.WATER)));
            assertTrue(signature.inputs().stream().anyMatch(input -> input.input() instanceof AEItemKey && input.amount() == 3));
            var outputs = DirectProcessingStackSupport.stackAmounts(signature.outputs());
            assertEquals(2L, outputs.get(AEItemKey.of(Items.IRON_INGOT)));
            assertEquals(1L, outputs.get(AEItemKey.of(Items.STONE)));
            assertEquals(250L, outputs.get(AEFluidKey.of(Fluids.LAVA)));
        }
        assertTrue(recipe.supports(MachineIdentity.fromBindingStack(Items.PAPER.getDefaultInstance())));
        assertFalse(recipe.supports(MachineIdentity.fromBindingStack(Items.FURNACE.getDefaultInstance())));
        recipe.itemOutputs().getFirst().setCount(99);
        assertEquals(2, recipe.itemOutputs().getFirst().getCount());
    }

    @Test void datapackAndNetworkRoundtripsKeepMachineAndIo() {
        var recipe = mixedRecipe();
        var ops = REGISTRIES.createSerializationContext(JsonOps.INSTANCE);
        var json = DirectProcessingPackRecipe.CODEC.codec().encodeStart(ops, recipe).getOrThrow();
        var fromJson = DirectProcessingPackRecipe.CODEC.codec().parse(ops, json).getOrThrow();
        assertEquals(recipe.signatures(), fromJson.signatures());
        assertEquals(recipe.machines(), fromJson.machines());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            DirectProcessingPackRecipe.STREAM_CODEC.encode(buffer, recipe);
            var decoded = DirectProcessingPackRecipe.STREAM_CODEC.decode(buffer);
            assertEquals(recipe.signatures(), decoded.signatures());
            assertEquals(recipe.machines(), decoded.machines());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void itemOnlyMatchingRequiresFullInputAmount() {
        var recipe = new DirectProcessingPackRecipe(List.of(MARKER),
                List.of(SizedIngredient.of(Items.RAW_IRON, 3)), List.of(),
                List.of(new ItemStack(Items.IRON_INGOT, 2)), List.of());
        assertTrue(recipe.matches(new SingleRecipeInput(new ItemStack(Items.RAW_IRON, 3)), null));
        assertFalse(recipe.matches(new SingleRecipeInput(new ItemStack(Items.RAW_IRON, 2)), null));
        assertFalse(recipe.matches(new SingleRecipeInput(new ItemStack(Items.RAW_GOLD, 3)), null));
        assertFalse(mixedRecipe().matches(new SingleRecipeInput(new ItemStack(Items.RAW_IRON, 3)), null));
    }

    @Test void fluidOnlyRecipeNeedsNoDummyItem() {
        var recipe = new DirectProcessingPackRecipe(List.of(MARKER), List.of(),
                List.of(SizedFluidIngredient.of(Fluids.WATER, 1000)), List.of(),
                List.of(new FluidStack(Fluids.LAVA, 500)));
        assertEquals(1, recipe.signatures().size());
        assertTrue(recipe.getResultItem(REGISTRIES).isEmpty());
        assertEquals(500L, recipe.signatures().iterator().next().outputs().getFirst().amount());
    }

    @Test void emptyInputsAndOversizedAlternativesFailAsWholeRecipe() {
        assertThrows(IllegalArgumentException.class, () -> new DirectProcessingPackRecipe(List.of(MARKER),
                List.of(), List.of(), List.of(Items.STONE.getDefaultInstance()), List.of()));
        var empty = new DirectProcessingPackRecipe(List.of(MARKER),
                List.of(new SizedIngredient(Ingredient.EMPTY, 1)), List.of(),
                List.of(Items.STONE.getDefaultInstance()), List.of());
        assertThrows(IllegalArgumentException.class, empty::signatures);
        var many = new DirectProcessingPackRecipe(List.of(MARKER),
                Collections.nCopies(9, new SizedIngredient(Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT), 1)),
                List.of(), List.of(Items.STONE.getDefaultInstance()), List.of());
        assertThrows(IllegalArgumentException.class, many::signatures);
    }

    @Test void reloadedIndexHonorsMarkersRemovalAndExecutionRules() {
        var builder = new MachineRecipeIndexBuilder(MachineAdapterRegistry.empty(), new GenericRecipeShapeReader(DirectProcessingStackConverterRegistry.builtins(),
                new DirectProcessingExternalRecipeShapeRegistry(List.of())));
        var marker = MachineIdentity.fromBindingStack(Items.PAPER.getDefaultInstance());
        var other = MachineIdentity.fromBindingStack(Items.FURNACE.getDefaultInstance());
        var valid = new RecipeHolder<>(ResourceLocation.parse("test:valid"), mixedRecipe());
        var invalid = new RecipeHolder<>(ResourceLocation.parse("test:empty_tag"),
                new DirectProcessingPackRecipe(List.of(MARKER), List.of(new SizedIngredient(Ingredient.EMPTY, 1)),
                        List.of(), List.of(Items.STONE.getDefaultInstance()), List.of()));
        assertEquals(2, builder.buildPackRecipeIndex(marker, List.of(invalid, valid)).recipeSignatureCount());
        assertTrue(builder.buildPackRecipeIndex(other, List.of(valid)).isEmpty());
        assertTrue(builder.buildPackRecipeIndex(marker, List.of()).isEmpty());
        try {
            DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder()
                    .disableType(DirectProcessingPackRecipe.TYPE_ID).build());
            assertTrue(builder.buildPackRecipeIndex(marker, List.of(valid)).isEmpty());
            DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder().disableMachine(MARKER).build());
            assertTrue(builder.buildPackRecipeIndex(marker, List.of(valid)).isEmpty());
        } finally { DirectProcessingPackRulesRegistry.reset(); }
    }

    @Test void registeredKubeSchemaProducesNativeRecipeJsonWithFluentIoNames() {
        // 此处只注册 schema，不启动脚本管理器或加载资源，因此 storage 不需要服务器上下文。
        var storage = new RecipeSchemaStorage(null);
        new ChexsonsaeutilsKubeJSPlugin().registerRecipeSchemas(new RecipeSchemaRegistry(storage));
        var schema = storage.namespace("chexsonsaeutils").getRegisteredOrThrow("direct_processing").schema;
        assertEquals(List.of("item_outputs", "item_inputs", "machines"),
                schema.constructors().get(3).keys.stream().map(key -> key.name).toList());
        assertEquals(List.of("machines"), schema.constructors().get(1).keys.stream().map(key -> key.name).toList());
        var recipe = mixedRecipe();
        var json = new JsonObject();
        for (var key : schema.keys) {
            Object value = switch (key.name) {
                case "machines" -> recipe.machines().stream().map(ResourceLocation::toString).toList();
                case "item_inputs" -> recipe.itemInputs();
                case "item_outputs" -> recipe.itemOutputs();
                case "fluid_inputs" -> recipe.fluidInputs();
                case "fluid_outputs" -> recipe.fluidOutputs();
                default -> throw new AssertionError(key.name);
            };
            encodeSchemaValue(json, key, value);
        }
        assertEquals(recipe.signatures(), DirectProcessingPackRecipe.CODEC.codec()
                .parse(REGISTRIES.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow().signatures());
        assertTrue(schema.keys.stream().filter(key -> key.name.equals("fluid_inputs"))
                .findFirst().orElseThrow().getValidFunctionNames().contains("fluidInputs"));
        assertTrue(schema.keys.stream().filter(key -> key.name.equals("fluid_outputs"))
                .findFirst().orElseThrow().getValidFunctionNames().contains("fluidOutputs"));
    }

    @SuppressWarnings("unchecked")
    private static <T> void encodeSchemaValue(JsonObject json, RecipeKey<T> key, Object value) {
        json.add(key.name, key.codec.encodeStart(REGISTRIES.createSerializationContext(JsonOps.INSTANCE),
                (T) value).getOrThrow());
    }
}
