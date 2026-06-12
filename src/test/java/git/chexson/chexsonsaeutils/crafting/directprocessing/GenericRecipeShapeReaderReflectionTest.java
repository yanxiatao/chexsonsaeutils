package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.GenericStack;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericRecipeShapeReaderReflectionTest {
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);


    @Test
    void inheritedFieldsStayReadableForGenericReflection() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(true);
        InheritedFieldRecipe recipe = new InheritedFieldRecipe(
                List.of(Ingredient.of(Items.COBBLESTONE)),
                new FluidStack(Fluids.WATER, 1000),
                new ItemStack(Items.STONE)
        );

        Object shape = readReflectiveShape(reader, recipe);

        assertTrue(isReadable(shape));
        assertEquals(2, inputChoices(shape).size());
        assertEquals(1, outputs(shape).size());
    }

    @Test
    void nestedWrapperMembersStayReadableForGenericReflection() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(true);
        WrappedRecipe recipe = new WrappedRecipe(
                new WrappedInputs(
                        List.of(Ingredient.of(Items.COAL)),
                        new FluidStack(Fluids.LAVA, 250)
                ),
                new WrappedOutput(new ItemStack(Items.DIAMOND))
        );

        Object shape = readReflectiveShape(reader, recipe);

        assertTrue(isReadable(shape));
        assertEquals(2, inputChoices(shape).size());
        assertEquals(1, outputs(shape).size());
    }

    @Test
    void countedNestedWrapperMembersPreserveDeclaredInputAmounts() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(true);
        CountedWrappedRecipe recipe = new CountedWrappedRecipe(
                new CountedWrappedInput(Ingredient.of(Items.IRON_INGOT), 3),
                new ItemStack(Items.IRON_BLOCK)
        );

        Object shape = readReflectiveShape(reader, recipe);

        assertTrue(isReadable(shape));
        assertEquals(1, inputChoices(shape).size());
        assertEquals(1, inputChoices(shape).getFirst().size());
        assertEquals(3L, inputChoices(shape).getFirst().getFirst().amount());
        assertEquals(1, outputs(shape).size());
        assertEquals(Items.IRON_BLOCK.toString(), outputs(shape).getFirst().what().toString());
    }

    @Test
    void ifeuStylePublicFieldRecipesStayReadableForGenericReflection() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(true);
        IfeuStyleRecipe recipe = new IfeuStyleRecipe(
                List.of(Ingredient.of(Items.COAL)),
                new FluidStack(Fluids.WATER, 250),
                new ItemStack(Items.DIAMOND)
        );

        Object shape = readReflectiveShape(reader, recipe);

        assertTrue(isReadable(shape));
        assertEquals(2, inputChoices(shape).size());
        assertEquals(1, outputs(shape).size());
    }

    @Test
    void optionalOutputsStayReadableForGenericReflection() {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(true);
        OptionalOutputRecipe recipe = new OptionalOutputRecipe(
                new ItemStack(Items.COAL),
                new FluidStack(Fluids.WATER, 250),
                Optional.of(new ItemStack(Items.DIAMOND))
        );

        try {
            Object shape = readReflectiveShape(reader, recipe, null);
            assertTrue(isReadable(shape));
            assertEquals(2, inputChoices(shape).size());
            assertEquals(1, outputs(shape).size());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void contextBlockRecipesRequireMatchingMachineIdentity() {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(true);
        BlockRightClickLikeRecipe recipe = new BlockRightClickLikeRecipe(
                new ItemStack(Items.FLINT_AND_STEEL),
                Blocks.STONECUTTER,
                Blocks.DIAMOND_BLOCK
        );

        try {
            Object matched = readReflectiveShape(
                    reader,
                    recipe,
                    MachineIdentity.fromBindingStack(new ItemStack(Blocks.STONECUTTER))
            );
            Object mismatched = readReflectiveShape(
                    reader,
                    recipe,
                    MachineIdentity.fromBindingStack(new ItemStack(Blocks.FURNACE))
            );

            assertTrue(isReadable(matched));
            assertEquals(1, inputChoices(matched).size());
            assertEquals(1, outputs(matched).size());
            assertFalse(isReadable(mismatched));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void directProcessingMockRecipeDoesNotDoubleCountInheritedSingleItemFields() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(true);
        DirectProcessingMockRecipe recipe = new DirectProcessingMockRecipe(
                "mock",
                Ingredient.of(Items.COAL),
                new ItemStack(Items.DIAMOND)
        );
        Object shape = readReflectiveShape(reader, recipe);

        assertTrue(isReadable(shape));
        assertEquals(1, inputChoices(shape).size());
        assertEquals(1, inputChoices(shape).getFirst().size());
        assertEquals(Items.COAL.toString(), inputChoices(shape).getFirst().getFirst().input().toString());
        assertEquals(1L, inputChoices(shape).getFirst().getFirst().amount());
        assertEquals(1, outputs(shape).size());
        assertEquals(Items.DIAMOND.toString(), outputs(shape).getFirst().what().toString());
        assertEquals(1L, outputs(shape).getFirst().amount());
    }

    @Test
    void codecFallbackReadsShapeWhenFieldNamesAreOpaque() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(false);
        CodecOnlyRecipe recipe = new CodecOnlyRecipe(
                new CodecOnlyPayload(
                        List.of(Ingredient.of(Items.COAL)),
                        new FluidStack(Fluids.WATER, 250)
                ),
                new ItemStack(Items.DIAMOND)
        );

        Object shape = readCodecShape(reader, recipe, null);

        assertTrue(isReadable(shape));
        assertEquals(2, inputChoices(shape).size());
        assertEquals(1, outputs(shape).size());
        assertEquals(Items.DIAMOND.toString(), outputs(shape).getFirst().what().toString());
    }

    @Test
    void codecFallbackHonorsMachineContextFields() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(false);
        CodecOnlyContextRecipe recipe = new CodecOnlyContextRecipe(
                new ItemStack(Items.FLINT_AND_STEEL),
                Blocks.STONECUTTER,
                Blocks.DIAMOND_BLOCK
        );

        Object matched = readCodecShape(
                reader,
                recipe,
                MachineIdentity.fromBindingStack(new ItemStack(Blocks.STONECUTTER))
        );
        Object mismatched = readCodecShape(
                reader,
                recipe,
                MachineIdentity.fromBindingStack(new ItemStack(Blocks.FURNACE))
        );

        assertTrue(isReadable(matched));
        assertEquals(1, inputChoices(matched).size());
        assertEquals(1, outputs(matched).size());
        assertFalse(isReadable(mismatched));
    }

    @Test
    void codecFallbackReadsStaticCodecFieldWithoutSerializerCodec() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(false);
        StaticCodecOnlyRecipe recipe = new StaticCodecOnlyRecipe(
                new StaticCodecOnlyPayload(
                        new ItemStack(Items.COAL),
                        new FluidStack(Fluids.WATER, 500)
                ),
                new ItemStack(Items.DIAMOND)
        );

        Object shape = readCodecShape(reader, recipe, null);

        assertTrue(isReadable(shape));
        assertEquals(2, inputChoices(shape).size());
        assertEquals(1, outputs(shape).size());
        assertEquals(Items.DIAMOND.toString(), outputs(shape).getFirst().what().toString());
    }

    @Test
    void codecFallbackReadsIfeuStyleMultiFluidAndOptionalOutputs() throws ReflectiveOperationException {
        GenericRecipeShapeReader reader = new GenericRecipeShapeReader(false);
        IfeuCodecOptionalRecipe recipe = new IfeuCodecOptionalRecipe(
                new ItemStack(Items.COAL),
                new FluidStack(Fluids.WATER, 250),
                new FluidStack(Fluids.LAVA, 125),
                Optional.of(new ItemStack(Items.DIAMOND)),
                Optional.of(new FluidStack(Fluids.WATER, 1000))
        );

        Object shape = readCodecShape(reader, recipe, null);

        assertTrue(isReadable(shape));
        assertEquals(3, inputChoices(shape).size());
        assertEquals(2, outputs(shape).size());
    }

    private static Object readReflectiveShape(
            GenericRecipeShapeReader reader,
            Recipe<?> recipe
    ) throws ReflectiveOperationException {
        Method method = GenericRecipeShapeReader.class.getDeclaredMethod("readReflectiveShape", Recipe.class);
        method.setAccessible(true);
        return method.invoke(reader, recipe);
    }

    private static Object readReflectiveShape(
            GenericRecipeShapeReader reader,
            Recipe<?> recipe,
            MachineIdentity identity
    ) throws ReflectiveOperationException {
        Method method = GenericRecipeShapeReader.class.getDeclaredMethod(
                "readReflectiveShape",
                Recipe.class,
                MachineIdentity.class
        );
        method.setAccessible(true);
        return method.invoke(reader, recipe, identity);
    }

    private static Object readCodecShape(
            GenericRecipeShapeReader reader,
            Recipe<?> recipe,
            MachineIdentity identity
    ) throws ReflectiveOperationException {
        Method method = GenericRecipeShapeReader.class.getDeclaredMethod(
                "readCodecShape",
                HolderLookup.Provider.class,
                Recipe.class,
                MachineIdentity.class
        );
        method.setAccessible(true);
        return method.invoke(reader, REGISTRIES, recipe, identity);
    }

    private static boolean isReadable(Object shape) throws ReflectiveOperationException {
        Method method = shape.getClass().getDeclaredMethod("isReadable");
        method.setAccessible(true);
        return (boolean) method.invoke(shape);
    }

    @SuppressWarnings("unchecked")
    private static List<List<RecipeSignatureInput>> inputChoices(Object shape) throws ReflectiveOperationException {
        Method method = shape.getClass().getDeclaredMethod("inputChoices");
        method.setAccessible(true);
        return (List<List<RecipeSignatureInput>>) method.invoke(shape);
    }

    @SuppressWarnings("unchecked")
    private static List<GenericStack> outputs(Object shape) throws ReflectiveOperationException {
        Method method = shape.getClass().getDeclaredMethod("outputs");
        method.setAccessible(true);
        return (List<GenericStack>) method.invoke(shape);
    }

    private abstract static class StubRecipe implements Recipe<CraftingInput> {
        @Override
        public boolean matches(CraftingInput input, Level level) {
            return false;
        }

        @Override
        public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return false;
        }

        @Override
        public ItemStack getResultItem(HolderLookup.Provider registries) {
            return ItemStack.EMPTY;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return null;
        }

        @Override
        public RecipeType<?> getType() {
            return null;
        }
    }

    private abstract static class FluidBaseRecipe extends StubRecipe {
        public FluidStack inputFluid;

        private FluidBaseRecipe(FluidStack inputFluid) {
            this.inputFluid = inputFluid;
        }
    }

    private static final class InheritedFieldRecipe extends FluidBaseRecipe {
        public List<Ingredient> inputs;
        public ItemStack output;

        private InheritedFieldRecipe(List<Ingredient> inputs, FluidStack inputFluid, ItemStack output) {
            super(inputFluid);
            this.inputs = inputs;
            this.output = output;
        }
    }

    private static class WrappedInputsBase {
        public FluidStack inputFluid;

        private WrappedInputsBase(FluidStack inputFluid) {
            this.inputFluid = inputFluid;
        }
    }

    private static final class WrappedInputs extends WrappedInputsBase {
        public List<Ingredient> inputs;

        private WrappedInputs(List<Ingredient> inputs, FluidStack inputFluid) {
            super(inputFluid);
            this.inputs = inputs;
        }
    }

    private record WrappedOutput(ItemStack output) {
    }

    private static final class WrappedRecipe extends StubRecipe {
        public WrappedInputs inputPayload;
        public WrappedOutput outputPayload;

        private WrappedRecipe(WrappedInputs inputPayload, WrappedOutput outputPayload) {
            this.inputPayload = inputPayload;
            this.outputPayload = outputPayload;
        }
    }

    private static final class CountedWrappedInput {
        private final Ingredient ingredient;
        private final int count;

        private CountedWrappedInput(Ingredient ingredient, int count) {
            this.ingredient = ingredient;
            this.count = count;
        }

        public Ingredient ingredient() {
            return ingredient;
        }

        public int count() {
            return count;
        }
    }

    private static final class CountedWrappedRecipe extends StubRecipe {
        private final CountedWrappedInput input;
        private final ItemStack output;

        private CountedWrappedRecipe(CountedWrappedInput input, ItemStack output) {
            this.input = input;
            this.output = output;
        }

        public CountedWrappedInput input() {
            return input;
        }

        public ItemStack output() {
            return output;
        }
    }

    private static final class IfeuStyleRecipe extends StubRecipe {
        public List<Ingredient> inputs;
        public FluidStack inputFluid;
        public ItemStack output;

        private IfeuStyleRecipe(List<Ingredient> inputs, FluidStack inputFluid, ItemStack output) {
            this.inputs = inputs;
            this.inputFluid = inputFluid;
            this.output = output;
        }
    }

    private static final class OptionalOutputRecipe extends StubRecipe {
        public ItemStack input;
        public FluidStack inputFluid;
        public Optional<ItemStack> output;

        private OptionalOutputRecipe(ItemStack input, FluidStack inputFluid, Optional<ItemStack> output) {
            this.input = input;
            this.inputFluid = inputFluid;
            this.output = output;
        }
    }

    private record CodecOnlyPayload(
            List<Ingredient> ingredients,
            FluidStack fluidPayload
    ) {
        private static final MapCodec<CodecOnlyPayload> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Ingredient.CODEC.listOf().fieldOf("inputs").forGetter(CodecOnlyPayload::ingredients),
                FluidStack.CODEC.fieldOf("inputFluid").forGetter(CodecOnlyPayload::fluidPayload)
        ).apply(instance, CodecOnlyPayload::new));
    }

    private static final class CodecOnlyRecipe extends StubRecipe {
        private static final MapCodec<CodecOnlyRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                CodecOnlyPayload.CODEC.forGetter(recipe -> recipe.payloadA),
                ItemStack.CODEC.fieldOf("output").forGetter(recipe -> recipe.payloadB)
        ).apply(instance, CodecOnlyRecipe::new));
        private static final RecipeSerializer<CodecOnlyRecipe> SERIALIZER = new CodecOnlyRecipeSerializer();

        private final CodecOnlyPayload payloadA;
        private final ItemStack payloadB;

        private CodecOnlyRecipe(CodecOnlyPayload payloadA, ItemStack payloadB) {
            this.payloadA = payloadA;
            this.payloadB = payloadB;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return SERIALIZER;
        }
    }

    private static final class CodecOnlyContextRecipe extends StubRecipe {
        private static final MapCodec<CodecOnlyContextRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemStack.CODEC.fieldOf("handItem").forGetter(recipe -> recipe.payloadA),
                net.minecraft.world.level.block.Block.CODEC.fieldOf("block").forGetter(recipe -> recipe.payloadB),
                net.minecraft.world.level.block.Block.CODEC.fieldOf("result").forGetter(recipe -> recipe.payloadC)
        ).apply(instance, CodecOnlyContextRecipe::new));
        private static final RecipeSerializer<CodecOnlyContextRecipe> SERIALIZER =
                new CodecOnlyContextRecipeSerializer();

        private final ItemStack payloadA;
        private final net.minecraft.world.level.block.Block payloadB;
        private final net.minecraft.world.level.block.Block payloadC;

        private CodecOnlyContextRecipe(
                ItemStack payloadA,
                net.minecraft.world.level.block.Block payloadB,
                net.minecraft.world.level.block.Block payloadC
        ) {
            this.payloadA = payloadA;
            this.payloadB = payloadB;
            this.payloadC = payloadC;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return SERIALIZER;
        }
    }

    private record StaticCodecOnlyPayload(
            ItemStack stackPayload,
            FluidStack fluidPayload
    ) {
        private static final MapCodec<StaticCodecOnlyPayload> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemStack.CODEC.fieldOf("input").forGetter(StaticCodecOnlyPayload::stackPayload),
                FluidStack.CODEC.fieldOf("inputFluid").forGetter(StaticCodecOnlyPayload::fluidPayload)
        ).apply(instance, StaticCodecOnlyPayload::new));
    }

    private static final class StaticCodecOnlyRecipe extends StubRecipe {
        private static final MapCodec<StaticCodecOnlyRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                StaticCodecOnlyPayload.CODEC.forGetter(recipe -> recipe.payloadA),
                ItemStack.CODEC.fieldOf("output").forGetter(recipe -> recipe.payloadB)
        ).apply(instance, StaticCodecOnlyRecipe::new));
        private static final RecipeSerializer<StaticCodecOnlyRecipe> SERIALIZER =
                new StaticCodecOnlyRecipeSerializer();

        private final StaticCodecOnlyPayload payloadA;
        private final ItemStack payloadB;

        private StaticCodecOnlyRecipe(StaticCodecOnlyPayload payloadA, ItemStack payloadB) {
            this.payloadA = payloadA;
            this.payloadB = payloadB;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return SERIALIZER;
        }
    }

    private static final class IfeuCodecOptionalRecipe extends StubRecipe {
        private static final MapCodec<IfeuCodecOptionalRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemStack.CODEC.fieldOf("input").forGetter(recipe -> recipe.payloadA),
                FluidStack.CODEC.fieldOf("inputFluid1").forGetter(recipe -> recipe.payloadB),
                FluidStack.CODEC.fieldOf("inputFluid2").forGetter(recipe -> recipe.payloadC),
                ItemStack.CODEC.optionalFieldOf("output").forGetter(recipe -> recipe.payloadD),
                FluidStack.CODEC.optionalFieldOf("outputFluid").forGetter(recipe -> recipe.payloadE)
        ).apply(instance, IfeuCodecOptionalRecipe::new));
        private static final RecipeSerializer<IfeuCodecOptionalRecipe> SERIALIZER =
                new IfeuCodecOptionalRecipeSerializer();

        private final ItemStack payloadA;
        private final FluidStack payloadB;
        private final FluidStack payloadC;
        private final Optional<ItemStack> payloadD;
        private final Optional<FluidStack> payloadE;

        private IfeuCodecOptionalRecipe(
                ItemStack payloadA,
                FluidStack payloadB,
                FluidStack payloadC,
                Optional<ItemStack> payloadD,
                Optional<FluidStack> payloadE
        ) {
            this.payloadA = payloadA;
            this.payloadB = payloadB;
            this.payloadC = payloadC;
            this.payloadD = payloadD;
            this.payloadE = payloadE;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return SERIALIZER;
        }
    }

    private static final class CodecOnlyRecipeSerializer implements RecipeSerializer<CodecOnlyRecipe> {
        @Override
        public MapCodec<CodecOnlyRecipe> codec() {
            return CodecOnlyRecipe.CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CodecOnlyRecipe> streamCodec() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StaticCodecOnlyRecipeSerializer implements RecipeSerializer<StaticCodecOnlyRecipe> {
        @Override
        public MapCodec<StaticCodecOnlyRecipe> codec() {
            return null;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, StaticCodecOnlyRecipe> streamCodec() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CodecOnlyContextRecipeSerializer implements RecipeSerializer<CodecOnlyContextRecipe> {
        @Override
        public MapCodec<CodecOnlyContextRecipe> codec() {
            return CodecOnlyContextRecipe.CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CodecOnlyContextRecipe> streamCodec() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class IfeuCodecOptionalRecipeSerializer implements RecipeSerializer<IfeuCodecOptionalRecipe> {
        @Override
        public MapCodec<IfeuCodecOptionalRecipe> codec() {
            return IfeuCodecOptionalRecipe.CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, IfeuCodecOptionalRecipe> streamCodec() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class BlockRightClickLikeRecipe extends StubRecipe {
        public ItemStack handItem;
        public net.minecraft.world.level.block.Block block;
        public net.minecraft.world.level.block.Block result;

        private BlockRightClickLikeRecipe(
                ItemStack handItem,
                net.minecraft.world.level.block.Block block,
                net.minecraft.world.level.block.Block result
        ) {
            this.handItem = handItem;
            this.block = block;
            this.result = result;
        }

        @Override
        public ItemStack getResultItem(HolderLookup.Provider registries) {
            Item resultItem = result.asItem();
            return resultItem == Items.AIR ? ItemStack.EMPTY : new ItemStack(resultItem);
        }
    }

}
