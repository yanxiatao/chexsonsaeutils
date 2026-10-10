package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 原生直连配方，参与 RecipeManager 与 KubeJS 的 ID 删除、替换及数据包重载。 */
public final class DirectProcessingPackRecipe implements Recipe<RecipeInput> {
    public static final ResourceLocation TYPE_ID = ResourceLocation.parse("chexsonsaeutils:direct_processing");
    private static final int MAX_INPUT_COMBINATIONS = 256;
    public static final MapCodec<DirectProcessingPackRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.listOf().fieldOf("machines").forGetter(DirectProcessingPackRecipe::machines),
            SizedIngredient.NESTED_CODEC.listOf().optionalFieldOf("item_inputs", List.of()).forGetter(DirectProcessingPackRecipe::itemInputs),
            SizedFluidIngredient.NESTED_CODEC.listOf().optionalFieldOf("fluid_inputs", List.of()).forGetter(DirectProcessingPackRecipe::fluidInputs),
            ItemStack.CODEC.listOf().optionalFieldOf("item_outputs", List.of()).forGetter(DirectProcessingPackRecipe::itemOutputs),
            FluidStack.CODEC.listOf().optionalFieldOf("fluid_outputs", List.of()).forGetter(DirectProcessingPackRecipe::fluidOutputs)
    ).apply(instance, DirectProcessingPackRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, DirectProcessingPackRecipe> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), DirectProcessingPackRecipe::machines,
            SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), DirectProcessingPackRecipe::itemInputs,
            SizedFluidIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), DirectProcessingPackRecipe::fluidInputs,
            ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()), DirectProcessingPackRecipe::itemOutputs,
            FluidStack.STREAM_CODEC.apply(ByteBufCodecs.list()), DirectProcessingPackRecipe::fluidOutputs,
            DirectProcessingPackRecipe::new);

    private final List<ResourceLocation> machines;
    private final List<SizedIngredient> itemInputs;
    private final List<SizedFluidIngredient> fluidInputs;
    private final List<ItemStack> itemOutputs;
    private final List<FluidStack> fluidOutputs;

    public DirectProcessingPackRecipe(List<ResourceLocation> machines, List<SizedIngredient> itemInputs,
                                     List<SizedFluidIngredient> fluidInputs, List<ItemStack> itemOutputs,
                                     List<FluidStack> fluidOutputs) {
        if (machines.isEmpty()) throw new IllegalArgumentException("直连配方必须指定机器或标记物品");
        if (itemInputs.isEmpty() && fluidInputs.isEmpty()) throw new IllegalArgumentException("直连配方必须有输入");
        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty()) throw new IllegalArgumentException("直连配方必须有输出");
        if (itemOutputs.stream().anyMatch(ItemStack::isEmpty) || fluidOutputs.stream().anyMatch(FluidStack::isEmpty)) {
            throw new IllegalArgumentException("直连配方输出不能为空堆栈");
        }
        this.machines = List.copyOf(new LinkedHashSet<>(machines));
        this.itemInputs = List.copyOf(itemInputs);
        this.fluidInputs = List.copyOf(fluidInputs);
        this.itemOutputs = itemOutputs.stream().map(ItemStack::copy).toList();
        this.fluidOutputs = fluidOutputs.stream().map(FluidStack::copy).toList();
    }
    public List<ResourceLocation> machines() { return machines; }
    public List<SizedIngredient> itemInputs() { return itemInputs; }
    public List<SizedFluidIngredient> fluidInputs() { return fluidInputs; }
    public List<ItemStack> itemOutputs() { return itemOutputs.stream().map(ItemStack::copy).toList(); }
    public List<FluidStack> fluidOutputs() { return fluidOutputs.stream().map(FluidStack::copy).toList(); }

    /** 机器 ID 或标记物品 ID 精确匹配，不向其他机器开放自定义配方。 */
    public boolean supports(MachineIdentity identity) {
        return identity != null && (machines.contains(identity.machineItemId())
                || identity.blockId() != null && machines.contains(identity.blockId()));
    }

    /** 将物品/流体标签展开成确定签名，完整保留数量和所有副产物。 */
    public Set<RecipeSignature> signatures() {
        List<List<RecipeSignatureInput>> combinations = new ArrayList<>();
        combinations.add(List.of());
        for (SizedIngredient input : itemInputs) {
            Set<RecipeSignatureInput> options = new LinkedHashSet<>();
            for (ItemStack stack : input.ingredient().getItems()) {
                if (!stack.isEmpty()) options.add(new RecipeSignatureInput(AEItemKey.of(stack), input.count()));
            }
            combinations = expand(combinations, options);
        }
        for (SizedFluidIngredient input : fluidInputs) {
            Set<RecipeSignatureInput> options = new LinkedHashSet<>();
            for (FluidStack stack : input.ingredient().getStacks()) {
                if (!stack.isEmpty()) options.add(new RecipeSignatureInput(AEFluidKey.of(stack), input.amount()));
            }
            combinations = expand(combinations, options);
        }
        List<GenericStack> outputs = new ArrayList<>();
        for (ItemStack stack : itemOutputs) outputs.add(new GenericStack(AEItemKey.of(stack), stack.getCount()));
        for (FluidStack stack : fluidOutputs) outputs.add(new GenericStack(AEFluidKey.of(stack), stack.getAmount()));
        Set<RecipeSignature> signatures = new LinkedHashSet<>();
        for (List<RecipeSignatureInput> inputs : combinations) signatures.add(new RecipeSignature(null, inputs, outputs));
        return signatures;
    }
    private static List<List<RecipeSignatureInput>> expand(List<List<RecipeSignatureInput>> prior,
                                                          Set<RecipeSignatureInput> options) {
        if (options.isEmpty()) throw new IllegalArgumentException("直连配方输入标签为空或没有可用堆栈");
        if ((long) prior.size() * options.size() > MAX_INPUT_COMBINATIONS) {
            throw new IllegalArgumentException("直连配方输入组合超过 " + MAX_INPUT_COMBINATIONS);
        }
        List<List<RecipeSignatureInput>> expanded = new ArrayList<>();
        for (var inputs : prior) for (var option : options) {
            var next = new ArrayList<>(inputs); next.add(option); expanded.add(List.copyOf(next));
        }
        return expanded;
    }
    @Override public boolean matches(RecipeInput input, Level level) {
        if (!fluidInputs.isEmpty()) return false;
        List<GenericStack> provided = new ArrayList<>();
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (!stack.isEmpty()) provided.add(new GenericStack(AEItemKey.of(stack), stack.getCount()));
        }
        var amounts = DirectProcessingStackSupport.stackAmounts(provided);
        return signatures().stream().anyMatch(signature -> DirectProcessingStackSupport.signatureInputAmounts(signature.inputs()).equals(amounts));
    }
    @Override public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) { return getResultItem(registries); }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= itemInputs.size(); }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) {
        return itemOutputs.isEmpty() ? ItemStack.EMPTY : itemOutputs.getFirst().copy();
    }
    @Override public NonNullList<Ingredient> getIngredients() {
        var ingredients = NonNullList.<Ingredient>create();
        for (var input : itemInputs) ingredients.add(input.ingredient());
        return ingredients;
    }
    @Override public boolean isSpecial() { return true; }
    @Override public RecipeSerializer<?> getSerializer() { return DirectProcessingRecipeRegistration.PACK_RECIPE_SERIALIZER.get(); }
    @Override public RecipeType<?> getType() { return DirectProcessingRecipeRegistration.PACK_RECIPE_TYPE.get(); }

    public static final class Serializer implements RecipeSerializer<DirectProcessingPackRecipe> {
        @Override public MapCodec<DirectProcessingPackRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, DirectProcessingPackRecipe> streamCodec() { return STREAM_CODEC; }
    }
}
