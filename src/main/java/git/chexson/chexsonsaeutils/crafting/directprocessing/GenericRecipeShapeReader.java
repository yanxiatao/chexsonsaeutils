package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GenericRecipeShapeReader {

    private static final int MAX_CANDIDATE_INPUTS_PER_RECIPE = 256;

    private final DirectProcessingStackConverterRegistry stackConverters;
    private final DirectProcessingExternalRecipeShapeRegistry externalShapeRegistry;

    public GenericRecipeShapeReader() {
        this(
                DirectProcessingStackConverterRegistry.directProcessingDefaults(),
                DirectProcessingExternalRecipeShapeRegistry.directProcessingDefaults()
        );
    }

    GenericRecipeShapeReader(
            DirectProcessingStackConverterRegistry stackConverters,
            DirectProcessingExternalRecipeShapeRegistry externalShapeRegistry
    ) {
        this.stackConverters = stackConverters == null
                ? DirectProcessingStackConverterRegistry.directProcessingDefaults()
                : stackConverters;
        this.externalShapeRegistry = externalShapeRegistry == null
                ? DirectProcessingExternalRecipeShapeRegistry.directProcessingDefaults()
                : externalShapeRegistry;
    }

    public Set<RecipeSignature> readStaticItemRecipe(
            Level level,
            RecipeTypeCandidate candidate,
            Recipe<?> recipe
    ) {
        return readStaticItemRecipe(level, candidate, recipe, null);
    }

    public Set<RecipeSignature> readStaticItemRecipe(
            Level level,
            RecipeTypeCandidate candidate,
            Recipe<?> recipe,
            @Nullable MachineIdentity identity
    ) {
        return readStaticItemRecipeOutcome(level, candidate, recipe, identity).signatures();
    }

    public ShapeReadOutcome readStaticItemRecipeOutcome(
            Level level,
            RecipeTypeCandidate candidate,
            Recipe<?> recipe
    ) {
        return readStaticItemRecipeOutcome(level, candidate, recipe, null);
    }

    public ShapeReadOutcome readStaticItemRecipeOutcome(
            Level level,
            RecipeTypeCandidate candidate,
            Recipe<?> recipe,
            @Nullable MachineIdentity identity
    ) {
        if (level == null || candidate == null || recipe == null) {
            return ShapeReadOutcome.unreadable();
        }
        ShapeReadOutcome externalOutcome = tryReadExternalShape(candidate, recipe);
        if (externalOutcome != null) {
            return externalOutcome;
        }
        RecipeShape shape = readPublicShape(level, recipe);
        RecipeShape codecShape = readCodecShape(level.registryAccess(), recipe, identity);
        shape = mergeRecipeShapes(shape, codecShape);
        if (!shape.isReadable()) {
            return ShapeReadOutcome.unreadable();
        }
        Set<RecipeSignature> signatures = new LinkedHashSet<>();
        expandSignatures(candidate, shape.outputs(), shape.inputChoices(), 0, new ArrayList<>(), signatures);
        return signatures.isEmpty() ? ShapeReadOutcome.unreadable() : ShapeReadOutcome.supported(signatures);
    }

    @Nullable
    private ShapeReadOutcome tryReadExternalShape(RecipeTypeCandidate candidate, Recipe<?> recipe) {
        if (candidate == null || recipe == null) {
            return null;
        }
        DirectProcessingExternalRecipeShapeRegistry.ShapeResult shapeResult =
                externalShapeRegistry.readShape(candidate, recipe);
        if (shapeResult == null) {
            return null;
        }
        if (!shapeResult.handled()) {
            return null;
        }
        if (!shapeResult.supported()) {
            return ShapeReadOutcome.unreadable();
        }
        if (shapeResult.outputs().isEmpty()) {
            return ShapeReadOutcome.unreadable();
        }
        List<List<RecipeSignatureInput>> inputChoices = readGenericStackChoices(shapeResult.inputChoices());
        if (inputChoices.isEmpty()) {
            return ShapeReadOutcome.unreadable();
        }
        Set<RecipeSignature> signatures = new LinkedHashSet<>();
        expandSignatures(candidate, shapeResult.outputs(), inputChoices, 0, new ArrayList<>(), signatures);
        return signatures.isEmpty() ? ShapeReadOutcome.unreadable() : ShapeReadOutcome.supported(signatures);
    }

    private RecipeShape readPublicShape(Level level, Recipe<?> recipe) {
        try {
            List<List<RecipeSignatureInput>> inputs = readIngredientChoices(recipe.getIngredients());
            GenericStack output = itemToGenericStack(recipe.getResultItem(level.registryAccess()).copy());
            if (inputs.isEmpty() || output == null) {
                return RecipeShape.unreadable();
            }
            return new RecipeShape(inputs, List.of(output));
        } catch (RuntimeException ignored) {
            return RecipeShape.unreadable();
        }
    }

    private RecipeShape readCodecShape(
            HolderLookup.Provider registries,
            Recipe<?> recipe,
            @Nullable MachineIdentity identity
    ) {
        if (registries == null || recipe == null) {
            return RecipeShape.unreadable();
        }
        try {
            Codec<Object> codec = resolveRecipeCodec(recipe);
            if (codec == null) {
                return RecipeShape.unreadable();
            }
            DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
            JsonElement encoded = codec.encodeStart(ops, recipe).result().orElse(null);
            if (!(encoded instanceof JsonObject jsonObject)) {
                return RecipeShape.unreadable();
            }
            return readJsonShape(ops, jsonObject, identity);
        } catch (RuntimeException ignored) {
            return RecipeShape.unreadable();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    private Codec<Object> resolveRecipeCodec(Recipe<?> recipe) {
        RecipeSerializer<?> serializer;
        try {
            serializer = recipe.getSerializer();
        } catch (RuntimeException ignored) {
            serializer = null;
        }
        if (serializer != null) {
            try {
                MapCodec<?> serializerCodec = serializer.codec();
                if (serializerCodec != null) {
                    return (Codec<Object>) serializerCodec.codec();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private RecipeShape readJsonShape(
            DynamicOps<JsonElement> ops,
            JsonObject root,
            @Nullable MachineIdentity identity
    ) {
        boolean hasContextMembers = false;
        boolean matchedContext = false;
        List<List<RecipeSignatureInput>> inputs = List.of();
        Set<List<List<RecipeSignatureInput>>> seenInputContributions = new LinkedHashSet<>();
        List<GenericStack> outputs = new ArrayList<>();
        Set<List<GenericStack>> seenOutputContributions = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (isContextMemberName(entry.getKey())) {
                hasContextMembers = true;
                if (identity == null || !readJsonContextMatches(ops, value, identity, false)) {
                    return RecipeShape.unreadable();
                }
                matchedContext = true;
            }
        }
        if (hasContextMembers && !matchedContext) {
            return RecipeShape.unreadable();
        }
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (isInputMemberName(entry.getKey())) {
                List<List<RecipeSignatureInput>> memberInputs =
                        normalizeInputContribution(readJsonInputChoices(ops, entry.getKey(), value, false));
                if (memberInputs.isEmpty() || !seenInputContributions.add(memberInputs)) {
                    continue;
                }
                inputs = mergeInputChoices(inputs, memberInputs);
                if (inputs.size() > MAX_CANDIDATE_INPUTS_PER_RECIPE) {
                    return RecipeShape.unreadable();
                }
            } else if (isOutputMemberName(entry.getKey())) {
                List<GenericStack> memberOutputs =
                        DirectProcessingStackSupport.normalizeStacks(readJsonOutputStacks(ops, entry.getKey(), value, false));
                if (memberOutputs.isEmpty() || !seenOutputContributions.add(memberOutputs)) {
                    continue;
                }
                outputs.addAll(memberOutputs);
            }
        }
        return new RecipeShape(inputs, DirectProcessingStackSupport.normalizeStacks(outputs));
    }

    private List<List<RecipeSignatureInput>> readJsonInputChoices(
            DynamicOps<JsonElement> ops,
            String fieldName,
            JsonElement value,
            boolean nested
    ) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (value.isJsonArray() && isPluralJsonField(fieldName)) {
            return readJsonArrayInputChoices(ops, value.getAsJsonArray());
        }
        Ingredient ingredient = parseWithCodec(Ingredient.CODEC, ops, value);
        if (ingredient != null) {
            return wrapIngredientChoice(ingredient);
        }
        RecipeSignatureInput directInput = decodeJsonInputChoice(ops, value);
        if (directInput != null) {
            return List.of(List.of(directInput));
        }
        if (value.isJsonArray()) {
            return readJsonArrayInputChoices(ops, value.getAsJsonArray());
        }
        if (!nested && value.isJsonObject()) {
            return readNestedJsonInputChoices(ops, value.getAsJsonObject());
        }
        return List.of();
    }

    private List<List<RecipeSignatureInput>> readJsonArrayInputChoices(
            DynamicOps<JsonElement> ops,
            JsonArray array
    ) {
        List<List<RecipeSignatureInput>> inputs = new ArrayList<>();
        for (JsonElement element : array) {
            List<List<RecipeSignatureInput>> elementInputs = readJsonInputChoices(ops, "", element, true);
            if (elementInputs.isEmpty()) {
                return List.of();
            }
            inputs.addAll(elementInputs);
        }
        return List.copyOf(inputs);
    }

    private List<List<RecipeSignatureInput>> readNestedJsonInputChoices(
            DynamicOps<JsonElement> ops,
            JsonObject object
    ) {
        List<JsonElement> nestedValues = collectJsonValues(
                object,
                "input",
                "inputs",
                "ingredient",
                "ingredients",
                "stack",
                "stacks",
                "item",
                "items",
                "fluid",
                "fluids",
                "value",
                "values"
        );
        if (nestedValues.isEmpty()) {
            return List.of();
        }
        long multiplier = nestedValues.size() == 1 ? readJsonMultiplier(object) : 1L;
        List<List<RecipeSignatureInput>> inputs = new ArrayList<>();
        for (JsonElement nestedValue : nestedValues) {
            List<List<RecipeSignatureInput>> nestedInputs = readJsonInputChoices(ops, "", nestedValue, true);
            if (nestedInputs.isEmpty()) {
                return List.of();
            }
            if (multiplier > 1L && !jsonHasIntrinsicAmount(ops, nestedValue)) {
                nestedInputs = scaleInputChoices(nestedInputs, multiplier);
                if (nestedInputs.isEmpty()) {
                    return List.of();
                }
            }
            inputs.addAll(nestedInputs);
        }
        return List.copyOf(inputs);
    }

    private List<GenericStack> readJsonOutputStacks(
            DynamicOps<JsonElement> ops,
            String fieldName,
            JsonElement value,
            boolean nested
    ) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        GenericStack direct = decodeJsonOutputStack(ops, value);
        if (direct != null) {
            return List.of(direct);
        }
        if (value.isJsonArray()) {
            return readJsonArrayOutputs(ops, value.getAsJsonArray());
        }
        if (!nested && value.isJsonObject()) {
            return readNestedJsonOutputs(ops, value.getAsJsonObject());
        }
        return List.of();
    }

    private List<GenericStack> readJsonArrayOutputs(DynamicOps<JsonElement> ops, JsonArray array) {
        List<GenericStack> outputs = new ArrayList<>();
        for (JsonElement element : array) {
            List<GenericStack> nestedOutputs = readJsonOutputStacks(ops, "", element, true);
            if (!nestedOutputs.isEmpty()) {
                outputs.addAll(nestedOutputs);
            }
        }
        return outputs.isEmpty() ? List.of() : List.copyOf(outputs);
    }

    private List<GenericStack> readNestedJsonOutputs(
            DynamicOps<JsonElement> ops,
            JsonObject object
    ) {
        List<JsonElement> nestedValues = collectJsonValues(
                object,
                "output",
                "outputs",
                "result",
                "results",
                "stack",
                "stacks",
                "item",
                "items",
                "fluid",
                "fluids",
                "value",
                "values"
        );
        if (nestedValues.isEmpty()) {
            return List.of();
        }
        List<GenericStack> outputs = new ArrayList<>();
        for (JsonElement nestedValue : nestedValues) {
            List<GenericStack> nestedOutputs = readJsonOutputStacks(ops, "", nestedValue, true);
            if (!nestedOutputs.isEmpty()) {
                outputs.addAll(nestedOutputs);
            }
        }
        return outputs.isEmpty() ? List.of() : List.copyOf(outputs);
    }

    private boolean readJsonContextMatches(
            DynamicOps<JsonElement> ops,
            JsonElement value,
            MachineIdentity identity,
            boolean nested
    ) {
        Object directValue = decodeJsonContextValue(ops, value);
        if (matchesMachineIdentity(directValue, identity)) {
            return true;
        }
        if (value instanceof JsonArray array) {
            boolean matched = false;
            for (JsonElement element : array) {
                if (!readJsonContextMatches(ops, element, identity, true)) {
                    return false;
                }
                matched = true;
            }
            return matched;
        }
        if (!nested && value instanceof JsonObject object) {
            List<JsonElement> nestedValues = collectJsonValues(
                    object,
                    "input",
                    "inputs",
                    "item",
                    "items",
                    "value",
                    "values",
                    "stack",
                    "stacks",
                    "block",
                    "blocks",
                    "machine",
                    "machines",
                    "catalyst",
                    "workstation",
                    "station",
                    "controller"
            );
            if (!nestedValues.isEmpty()) {
                boolean matched = false;
                for (JsonElement nestedValue : nestedValues) {
                    if (!readJsonContextMatches(ops, nestedValue, identity, true)) {
                        return false;
                    }
                    matched = true;
                }
                return matched;
            }
        }
        return false;
    }

    @Nullable
    private RecipeSignatureInput decodeJsonInputChoice(
            DynamicOps<JsonElement> ops,
            JsonElement element
    ) {
        ItemStack itemStack = parseWithCodec(ItemStack.CODEC, ops, element);
        if (itemStack != null) {
            return toInputChoice(itemStack);
        }
        FluidStack fluidStack = parseWithCodec(FluidStack.CODEC, ops, element);
        if (fluidStack != null) {
            return toInputChoice(fluidStack);
        }
        Block block = parseWithCodec(Block.CODEC.codec(), ops, element);
        if (block != null) {
            return toInputChoice(block);
        }
        ResourceLocation resourceLocation = parseWithCodec(ResourceLocation.CODEC, ops, element);
        if (resourceLocation != null) {
            return toInputChoice(resourceLocationAsValue(resourceLocation));
        }
        return null;
    }

    @Nullable
    private GenericStack decodeJsonOutputStack(
            DynamicOps<JsonElement> ops,
            JsonElement element
    ) {
        ItemStack itemStack = parseWithCodec(ItemStack.CODEC, ops, element);
        if (itemStack != null) {
            return toOutputStack(itemStack);
        }
        FluidStack fluidStack = parseWithCodec(FluidStack.CODEC, ops, element);
        if (fluidStack != null) {
            return toOutputStack(fluidStack);
        }
        Block block = parseWithCodec(Block.CODEC.codec(), ops, element);
        if (block != null) {
            return toOutputStack(block);
        }
        ResourceLocation resourceLocation = parseWithCodec(ResourceLocation.CODEC, ops, element);
        if (resourceLocation != null) {
            return toOutputStack(resourceLocation);
        }
        return null;
    }

    @Nullable
    private Object decodeJsonContextValue(
            DynamicOps<JsonElement> ops,
            JsonElement element
    ) {
        Block block = parseWithCodec(Block.CODEC.codec(), ops, element);
        if (block != null) {
            return block;
        }
        ItemStack itemStack = parseWithCodec(ItemStack.CODEC, ops, element);
        if (itemStack != null) {
            return itemStack;
        }
        ResourceLocation resourceLocation = parseWithCodec(ResourceLocation.CODEC, ops, element);
        if (resourceLocation != null) {
            return resourceLocation;
        }
        return null;
    }

    @Nullable
    private RecipeSignatureInput toInputChoice(@Nullable Object value) {
        RecipeSignatureInput converted = toSignatureInput(stackConverters.convert(value));
        if (converted != null) {
            return converted;
        }
        if (value instanceof Block block) {
            return toInputChoice(block.asItem());
        }
        if (value instanceof Item item) {
            return item == Items.AIR ? null : toSignatureInput(item.getDefaultInstance());
        }
        if (value instanceof ItemLike itemLike) {
            return toInputChoice(itemLike.asItem());
        }
        if (value instanceof ResourceLocation resourceLocation) {
            return toInputChoice(resourceLocationAsValue(resourceLocation));
        }
        return null;
    }

    @Nullable
    private Object resourceLocationAsValue(ResourceLocation resourceLocation) {
        Item item = BuiltInRegistries.ITEM.get(resourceLocation);
        if (item != null && item != Items.AIR) {
            return item;
        }
        Block block = BuiltInRegistries.BLOCK.get(resourceLocation);
        if (block != null) {
            return block;
        }
        return resourceLocation;
    }

    private boolean matchesMachineIdentity(@Nullable Object value, MachineIdentity identity) {
        if (value == null) {
            return false;
        }
        if (value instanceof Block block) {
            return matchesIdentityResource(identity.blockId(), BuiltInRegistries.BLOCK.getKey(block));
        }
        if (value instanceof ItemStack stack) {
            return matchesMachineIdentity(stack.getItem(), identity);
        }
        if (value instanceof Item item) {
            return matchesIdentityResource(identity.machineItemId(), BuiltInRegistries.ITEM.getKey(item));
        }
        if (value instanceof ItemLike itemLike) {
            return matchesMachineIdentity(itemLike.asItem(), identity);
        }
        if (value instanceof ResourceLocation resourceLocation) {
            return matchesIdentityResource(identity.machineItemId(), resourceLocation)
                    || matchesIdentityResource(identity.blockId(), resourceLocation);
        }
        if (value instanceof Holder<?> holder) {
            return matchesMachineIdentity(holder.value(), identity);
        }
        return false;
    }

    private boolean matchesIdentityResource(
            @Nullable ResourceLocation expected,
            @Nullable ResourceLocation actual
    ) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private List<List<RecipeSignatureInput>> wrapIngredientChoice(Ingredient ingredient) {
        List<RecipeSignatureInput> choices = new ArrayList<>();
        for (ItemStack inputStack : ingredient.getItems()) {
            RecipeSignatureInput input = toSignatureInput(inputStack);
            if (input != null) {
                choices.add(input);
            }
        }
        return choices.isEmpty() ? List.of() : List.of(List.copyOf(choices));
    }

    private List<List<RecipeSignatureInput>> normalizeInputContribution(
            List<List<RecipeSignatureInput>> rawChoices
    ) {
        if (rawChoices == null || rawChoices.isEmpty()) {
            return List.of();
        }
        List<List<RecipeSignatureInput>> normalizedChoices = new ArrayList<>(rawChoices.size());
        for (List<RecipeSignatureInput> rawChoice : rawChoices) {
            List<RecipeSignatureInput> normalizedChoice =
                    DirectProcessingStackSupport.normalizeSignatureInputs(rawChoice);
            if (normalizedChoice.isEmpty()) {
                return List.of();
            }
            normalizedChoices.add(normalizedChoice);
        }
        return List.copyOf(normalizedChoices);
    }

    private boolean jsonHasIntrinsicAmount(
            DynamicOps<JsonElement> ops,
            JsonElement element
    ) {
        return parseWithCodec(ItemStack.CODEC, ops, element) != null
                || parseWithCodec(FluidStack.CODEC, ops, element) != null;
    }

    private List<List<RecipeSignatureInput>> scaleInputChoices(
            List<List<RecipeSignatureInput>> rawChoices,
            long multiplier
    ) {
        if (rawChoices == null || rawChoices.isEmpty() || multiplier <= 0L) {
            return List.of();
        }
        if (multiplier == 1L) {
            return rawChoices;
        }
        List<List<RecipeSignatureInput>> scaledChoices = new ArrayList<>(rawChoices.size());
        for (List<RecipeSignatureInput> rawChoice : rawChoices) {
            List<RecipeSignatureInput> scaledChoice = new ArrayList<>(rawChoice.size());
            for (RecipeSignatureInput input : rawChoice) {
                if (input == null || input.input() == null || input.amount() <= 0L) {
                    return List.of();
                }
                long scaledAmount = multiplyOrZero(input.amount(), multiplier);
                if (scaledAmount <= 0L) {
                    return List.of();
                }
                scaledChoice.add(new RecipeSignatureInput(input.input(), scaledAmount));
            }
            scaledChoices.add(List.copyOf(scaledChoice));
        }
        return List.copyOf(scaledChoices);
    }

    private static long readJsonMultiplier(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null
                    || value.isJsonNull()
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()
                    || !matchesAnyName(
                    normalizeName(entry.getKey()),
                    "count",
                    "counts",
                    "amount",
                    "amounts",
                    "quantity",
                    "quantities",
                    "size",
                    "sizes",
                    "requiredcount",
                    "requiredamount",
                    "ingredientcount",
                    "ingredientamount",
                    "inputcount",
                    "inputamount"
            )) {
                continue;
            }
            long multiplier = value.getAsLong();
            if (multiplier > 0L) {
                return multiplier;
            }
        }
        return 1L;
    }

    private static List<JsonElement> collectJsonValues(
            JsonObject object,
            String... acceptedNames
    ) {
        List<JsonElement> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (matchesAnyName(normalizeName(entry.getKey()), acceptedNames)) {
                values.add(value);
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private static boolean isInputMemberName(String name) {
        return matchesAnyName(
                normalizeName(name),
                "input",
                "inputs",
                "ingredient",
                "ingredients",
                "handitem",
                "helditem",
                "tool",
                "inputitem",
                "inputfluid",
                "inputfluids"
        );
    }

    private static boolean isOutputMemberName(String name) {
        return matchesAnyName(
                normalizeName(name),
                "result",
                "results",
                "output",
                "outputs",
                "resultstack",
                "outputstack",
                "fluidoutput",
                "fluidoutputs"
        );
    }

    private static boolean isContextMemberName(String name) {
        return matchesAnyName(
                normalizeName(name),
                "block",
                "machine",
                "catalyst",
                "workstation",
                "station",
                "controller"
        );
    }

    private static boolean isPluralJsonField(String name) {
        String normalized = normalizeName(name);
        return normalized.contains("inputs")
                || normalized.contains("ingredients")
                || normalized.contains("outputs")
                || normalized.contains("results")
                || normalized.contains("items")
                || normalized.contains("fluids")
                || normalized.contains("stacks")
                || normalized.contains("values");
    }

    @Nullable
    private static <T> T parseWithCodec(
            Codec<T> codec,
            DynamicOps<JsonElement> ops,
            JsonElement element
    ) {
        if (codec == null || ops == null || element == null || element.isJsonNull()) {
            return null;
        }
        return codec.parse(ops, element).result().orElse(null);
    }

    private static RecipeShape mergeRecipeShapes(RecipeShape primary, RecipeShape secondary) {
        if (primary == null || !primary.isReadable()) {
            return secondary == null ? RecipeShape.unreadable() : secondary;
        }
        if (secondary == null || !secondary.isReadable()) {
            return primary;
        }
        List<List<RecipeSignatureInput>> mergedInputs = new ArrayList<>(primary.inputChoices());
        for (List<RecipeSignatureInput> inputChoice : secondary.inputChoices()) {
            if (!mergedInputs.contains(inputChoice)) {
                mergedInputs.add(inputChoice);
            }
        }
        List<GenericStack> mergedOutputs = new ArrayList<>(primary.outputs());
        for (GenericStack output : secondary.outputs()) {
            if (!mergedOutputs.contains(output)) {
                mergedOutputs.add(output);
            }
        }
        return new RecipeShape(mergedInputs, mergedOutputs);
    }

    private void expandSignatures(
            RecipeTypeCandidate candidate,
            List<GenericStack> outputs,
            List<List<RecipeSignatureInput>> inputChoices,
            int ingredientIndex,
            List<RecipeSignatureInput> selectedInputs,
            Set<RecipeSignature> signatures
    ) {
        if (signatures.size() >= MAX_CANDIDATE_INPUTS_PER_RECIPE) {
            return;
        }
        if (ingredientIndex >= inputChoices.size()) {
            signatures.add(new RecipeSignature(candidate.kind(), selectedInputs, outputs));
            return;
        }
        for (RecipeSignatureInput inputChoice : inputChoices.get(ingredientIndex)) {
            selectedInputs.add(inputChoice);
            expandSignatures(candidate, outputs, inputChoices, ingredientIndex + 1, selectedInputs, signatures);
            selectedInputs.removeLast();
            if (signatures.size() >= MAX_CANDIDATE_INPUTS_PER_RECIPE) {
                return;
            }
        }
    }

    private List<List<RecipeSignatureInput>> readIngredientChoices(List<Ingredient> ingredients) {
        List<List<RecipeSignatureInput>> choices = new ArrayList<>();
        int candidateCount = 1;
        for (Ingredient ingredient : ingredients) {
            List<RecipeSignatureInput> ingredientChoices = readIngredientChoice(ingredient);
            if (ingredientChoices.isEmpty()) {
                return List.of();
            }
            if (candidateCount > MAX_CANDIDATE_INPUTS_PER_RECIPE / ingredientChoices.size()) {
                return List.of();
            }
            candidateCount *= ingredientChoices.size();
            choices.add(ingredientChoices);
        }
        return List.copyOf(choices);
    }

    private List<RecipeSignatureInput> readIngredientChoice(@Nullable Ingredient ingredient) {
        if (ingredient == null) {
            return List.of();
        }
        List<RecipeSignatureInput> choices = new ArrayList<>();
        for (ItemStack inputStack : ingredient.getItems()) {
            RecipeSignatureInput input = toSignatureInput(inputStack);
            if (input != null) {
                choices.add(input);
            }
        }
        return List.copyOf(choices);
    }

    private List<List<RecipeSignatureInput>> readStackChoices(List<List<ItemStack>> stackChoices) {
        if (stackChoices == null || stackChoices.isEmpty()) {
            return List.of();
        }
        List<List<RecipeSignatureInput>> choices = new ArrayList<>();
        int candidateCount = 1;
        for (List<ItemStack> rawChoice : stackChoices) {
            List<RecipeSignatureInput> signatureChoices = new ArrayList<>();
            if (rawChoice == null) {
                return List.of();
            }
            for (ItemStack stack : rawChoice) {
                RecipeSignatureInput input = toSignatureInput(stack);
                if (input != null) {
                    signatureChoices.add(input);
                }
            }
            if (signatureChoices.isEmpty()) {
                return List.of();
            }
            if (candidateCount > MAX_CANDIDATE_INPUTS_PER_RECIPE / signatureChoices.size()) {
                return List.of();
            }
            candidateCount *= signatureChoices.size();
            choices.add(List.copyOf(signatureChoices));
        }
        return List.copyOf(choices);
    }

    private List<List<RecipeSignatureInput>> readGenericStackChoices(List<List<GenericStack>> stackChoices) {
        if (stackChoices == null || stackChoices.isEmpty()) {
            return List.of();
        }
        List<List<RecipeSignatureInput>> choices = new ArrayList<>(stackChoices.size());
        int candidateCount = 1;
        for (List<GenericStack> rawChoice : stackChoices) {
            if (rawChoice == null || rawChoice.isEmpty()) {
                return List.of();
            }
            List<RecipeSignatureInput> signatureChoices = new ArrayList<>();
            for (GenericStack stack : rawChoice) {
                RecipeSignatureInput input = toSignatureInput(stack);
                if (input != null) {
                    signatureChoices.add(input);
                }
            }
            if (signatureChoices.isEmpty()) {
                return List.of();
            }
            if (candidateCount > MAX_CANDIDATE_INPUTS_PER_RECIPE / signatureChoices.size()) {
                return List.of();
            }
            candidateCount *= signatureChoices.size();
            choices.add(List.copyOf(signatureChoices));
        }
        return List.copyOf(choices);
    }

    @Nullable
    private RecipeSignatureInput toSignatureInput(@Nullable ItemStack inputStack) {
        GenericStack stack = itemToGenericStack(inputStack);
        return stack == null ? null : new RecipeSignatureInput(stack.what(), stack.amount());
    }

    @Nullable
    private RecipeSignatureInput toSignatureInput(@Nullable FluidStack fluidStack) {
        GenericStack stack = fluidToGenericStack(fluidStack);
        return stack == null ? null : new RecipeSignatureInput(stack.what(), stack.amount());
    }

    @Nullable
    private RecipeSignatureInput toSignatureInput(@Nullable GenericStack stack) {
        if (stack == null || stack.what() == null || stack.amount() <= 0L) {
            return null;
        }
        return new RecipeSignatureInput(stack.what(), stack.amount());
    }

    @Nullable
    private GenericStack toOutputStack(@Nullable Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? toOutputStack(optional.get()) : null;
        }
        GenericStack converted = stackConverters.convert(value);
        if (converted != null) {
            return converted;
        }
        if (value instanceof Block block) {
            return toOutputStack(block.asItem());
        }
        if (value instanceof Item item) {
            return item == Items.AIR ? null : itemToGenericStack(item.getDefaultInstance());
        }
        if (value instanceof ItemLike itemLike) {
            return toOutputStack(itemLike.asItem());
        }
        if (value instanceof ResourceLocation resourceLocation) {
            Item item = BuiltInRegistries.ITEM.get(resourceLocation);
            if (item != null && item != Items.AIR) {
                return itemToGenericStack(item.getDefaultInstance());
            }
            Block block = BuiltInRegistries.BLOCK.get(resourceLocation);
            if (block != null) {
                return toOutputStack(block);
            }
            return null;
        }
        return null;
    }

    @Nullable
    private static GenericStack itemToGenericStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return null;
        }
        return new GenericStack(key, Math.max(1, stack.getCount()));
    }

    @Nullable
    private static GenericStack fluidToGenericStack(@Nullable FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        AEFluidKey key = AEFluidKey.of(stack);
        if (key == null) {
            return null;
        }
        return new GenericStack(key, Math.max(1, stack.getAmount()));
    }

    private static boolean matchesAnyName(String normalizedName, String... acceptedNames) {
        for (String acceptedName : acceptedNames) {
            if (normalizedName.contains(acceptedName)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static List<List<RecipeSignatureInput>> mergeInputChoices(
            List<List<RecipeSignatureInput>> left,
            List<List<RecipeSignatureInput>> right
    ) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        List<List<RecipeSignatureInput>> merged = new ArrayList<>(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private record RecipeShape(List<List<RecipeSignatureInput>> inputChoices, List<GenericStack> outputs) {
        RecipeShape {
            inputChoices = inputChoices == null ? List.of() : List.copyOf(inputChoices);
            outputs = DirectProcessingStackSupport.normalizeStacks(outputs);
        }

        private static RecipeShape unreadable() {
            return new RecipeShape(List.of(), List.of());
        }

        private boolean isReadable() {
            return !inputChoices.isEmpty() && !outputs.isEmpty();
        }
    }

    public record ShapeReadOutcome(
            Set<RecipeSignature> signatures,
            MachineSupportStatus status,
            MachineSupportReasonCode reasonCode
    ) {
        public ShapeReadOutcome {
            signatures = signatures == null ? Set.of() : Set.copyOf(signatures);
            status = status == null ? MachineSupportStatus.UNSUPPORTED_UNREADABLE : status;
            reasonCode = reasonCode == null ? MachineSupportReasonCode.MALFORMED_DATA : reasonCode;
        }

        public static ShapeReadOutcome supported(Set<RecipeSignature> signatures) {
            return new ShapeReadOutcome(signatures, MachineSupportStatus.SUPPORTED_GENERIC, MachineSupportReasonCode.NONE);
        }

        public static ShapeReadOutcome unreadable() {
            return new ShapeReadOutcome(Set.of(), MachineSupportStatus.UNSUPPORTED_UNREADABLE, MachineSupportReasonCode.MALFORMED_DATA);
        }

    }

    private static long multiplyOrZero(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return 0L;
        }
        return left * right;
    }
}
