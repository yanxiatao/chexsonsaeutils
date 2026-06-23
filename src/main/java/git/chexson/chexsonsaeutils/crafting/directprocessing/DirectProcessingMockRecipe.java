package git.chexson.chexsonsaeutils.crafting.directprocessing;

import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class DirectProcessingMockRecipe extends SingleItemRecipe {

    private static final RecipeType<DirectProcessingMockRecipe> MOCK_TYPE = new RecipeType<>() {};
    private static final RecipeSerializer<DirectProcessingMockRecipe> MOCK_SERIALIZER = new Serializer();

    private final InputBundle inputs;
    private final OutputBundle resultStack;

    public DirectProcessingMockRecipe(ResourceLocation id, String group, Ingredient ingredient, ItemStack result) {
        super(
                MOCK_TYPE,
                MOCK_SERIALIZER,
                id,
                group,
                ingredient,
                result
        );
        this.inputs = new InputBundle(new IngredientBundle(ingredient));
        this.resultStack = new OutputBundle(result.copy());
    }

    public DirectProcessingMockRecipe(String group, Ingredient ingredient, ItemStack result) {
        this(
                ResourceLocation.tryParse("chexsonsaeutils:direct_processing_mock"),
                group,
                ingredient,
                result
        );
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

    public ItemStack getResultItem() {
        return ItemStack.EMPTY;
    }

    public ItemStack assemble(Container container) {
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
                return "chexsonsaeutils:direct_processing_mock";
            }
        };
    }

    public static RecipeSerializer<DirectProcessingMockRecipe> createSerializer() {
        return MOCK_SERIALIZER;
    }

    public static final class Serializer implements RecipeSerializer<DirectProcessingMockRecipe> {
        @Override
        public DirectProcessingMockRecipe fromJson(ResourceLocation id, JsonObject json) {
            String group = "";
            Ingredient ingredient = Ingredient.EMPTY;
            ItemStack result = ItemStack.EMPTY;
            try {
                if (json.has("group")) {
                    group = json.get("group").getAsString();
                }
                if (json.has("ingredient")) {
                    ingredient = Ingredient.fromJson(json.get("ingredient"));
                }
                if (json.has("result")) {
                    result = ShapedRecipe.itemStackFromJson(json.get("result").getAsJsonObject());
                }
            } catch (Exception ignored) {
            }
            return new DirectProcessingMockRecipe(id, group, ingredient, result);
        }

        @Override
        public DirectProcessingMockRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            String group = buf.readUtf();
            Ingredient ingredient = Ingredient.fromNetwork(buf);
            ItemStack result = buf.readItem();
            return new DirectProcessingMockRecipe(id, group, ingredient, result);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, DirectProcessingMockRecipe recipe) {
            buf.writeUtf(recipe.group);
            recipe.ingredient.toNetwork(buf);
            buf.writeItem(recipe.result);
        }
    }

    public record InputBundle(IngredientBundle ingredient) {
    }

    public record IngredientBundle(Ingredient ingredient) {
    }

    public record OutputBundle(ItemStack stack) {
    }
}
