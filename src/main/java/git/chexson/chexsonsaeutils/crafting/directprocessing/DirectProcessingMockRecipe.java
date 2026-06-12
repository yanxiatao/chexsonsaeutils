package git.chexson.chexsonsaeutils.crafting.directprocessing;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class DirectProcessingMockRecipe extends SingleItemRecipe {

    private final InputBundle inputs;
    private final OutputBundle resultStack;

    public DirectProcessingMockRecipe(String group, Ingredient ingredient, ItemStack result) {
        super(
                ChexsonsaeutilsContent.DIRECT_PROCESSING_MOCK_RECIPE_TYPE.get(),
                ChexsonsaeutilsContent.DIRECT_PROCESSING_MOCK_RECIPE_SERIALIZER.get(),
                group,
                ingredient,
                result
        );
        this.inputs = new InputBundle(new IngredientBundle(ingredient));
        this.resultStack = new OutputBundle(result.copy());
    }

    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        return input != null && ingredient.test(input.item());
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
    public ItemStack getResultItem(HolderLookup.Provider registries) {
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
