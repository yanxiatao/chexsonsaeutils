package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class DirectProcessingMockRecipe extends SingleItemRecipe {

    private static final RecipeType<DirectProcessingMockRecipe> MOCK_TYPE = new RecipeType<>() {};
    private static final RecipeSerializer<DirectProcessingMockRecipe> MOCK_SERIALIZER = new Serializer();

    private final InputBundle inputs;
    private final OutputBundle resultStack;

    public DirectProcessingMockRecipe(String group, Ingredient ingredient, ItemStack result) {
        super(
                MOCK_TYPE,
                MOCK_SERIALIZER,
                group,
                ingredient,
                result
        );
        this.inputs = new InputBundle(new IngredientBundle(ingredient));
        this.resultStack = new OutputBundle(result.copy());
    }

    @Override
    public boolean matches(Container input, Level level) {
        return input != null && input.getContainerSize() > 0 && ingredient.test(input.getItem(0));
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(Blocks.CRAFTING_TABLE);
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return NonNullList.create();
    }

    @Override
    public ItemStack getResultItem() {
        return ItemStack.EMPTY;
    }

    public InputBundle inputs() {
        return inputs;
    }

    public OutputBundle resultStack() {
        return resultStack;
    }

    public static RecipeType<DirectProcessingMockRecipe> createType() {
        return new RecipeType<>() {
            @Override
            public String toString() {
                return Chexsonsaeutils.MODID + ":direct_processing_mock";
            }
        };
    }

    public static RecipeSerializer<DirectProcessingMockRecipe> createSerializer() {
        return new Serializer();
    }

    public static final class Serializer extends SingleItemRecipe.Serializer<DirectProcessingMockRecipe> {
        public Serializer() {
            super(DirectProcessingMockRecipe::new);
        }
    }

    public record InputBundle(IngredientBundle ingredient) {
    }

    public record IngredientBundle(Ingredient ingredient) {
    }

    public record OutputBundle(ItemStack stack) {
    }
}
