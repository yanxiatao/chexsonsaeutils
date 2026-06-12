package git.chexson.chexsonsaeutils.crafting.directprocessing;

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

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class GenericRecipeShapeReader {

    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final int MAX_CANDIDATE_INPUTS_PER_RECIPE = 256;
    private static final int MAX_REFLECTIVE_MEMBERS_PER_CLASS = 16;

    private final Map<Class<?>, ReflectionShapeReader> reflectionShapeReaders = new ConcurrentHashMap<>();
    private final ItemRecipeShapeReader itemShapeReader = new ItemRecipeShapeReader();
    private final FluidRecipeShapeReader fluidShapeReader = new FluidRecipeShapeReader();
    private final NestedValueUnwrapper nestedValueUnwrapper = new NestedValueUnwrapper();
    private final boolean reflectiveDiscoveryEnabled;

    public GenericRecipeShapeReader() {
        this(true);
    }

    public GenericRecipeShapeReader(boolean reflectiveDiscoveryEnabled) {
        this.reflectiveDiscoveryEnabled = reflectiveDiscoveryEnabled;
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
        ShapeReadOutcome mekanismOutcome = tryReadMekanismShape(candidate, recipe);
        if (mekanismOutcome != null) {
            return mekanismOutcome;
        }
        if (hasUnsafeDynamicMembers(recipe.getClass())) {
            return ShapeReadOutcome.unsafeDynamic();
        }
        RecipeShape shape = readPublicShape(level, recipe);
        if (reflectiveDiscoveryEnabled) {
            RecipeShape reflectiveShape = readReflectiveShape(recipe, identity);
            shape = mergeRecipeShapes(shape, reflectiveShape);
        }
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
    private ShapeReadOutcome tryReadMekanismShape(RecipeTypeCandidate candidate, Recipe<?> recipe) {
        if (candidate.recipeType() == null) {
            return null;
        }
        var recipeTypeId = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(candidate.recipeType());
        if (recipeTypeId == null || !MEKANISM_MOD_ID.equals(recipeTypeId.getNamespace())) {
            return null;
        }
        MekanismShapeReadResult shapeResult = MekanismShapeSupportHolder.readStaticItemRecipeShape(candidate, recipe);
        if (!shapeResult.handled()) {
            return null;
        }
        if (!shapeResult.supported()) {
            return ShapeReadOutcome.unreadable();
        }
        GenericStack output = itemShapeReader.toGenericStack(shapeResult.output().copy());
        if (output == null) {
            return ShapeReadOutcome.unreadable();
        }
        List<List<RecipeSignatureInput>> inputChoices = readStackChoices(shapeResult.inputChoices());
        if (inputChoices.isEmpty()) {
            return ShapeReadOutcome.unreadable();
        }
        Set<RecipeSignature> signatures = new LinkedHashSet<>();
        expandSignatures(candidate, List.of(output), inputChoices, 0, new ArrayList<>(), signatures);
        return signatures.isEmpty() ? ShapeReadOutcome.unreadable() : ShapeReadOutcome.supported(signatures);
    }

    private RecipeShape readPublicShape(Level level, Recipe<?> recipe) {
        try {
            List<List<RecipeSignatureInput>> inputs = readIngredientChoices(recipe.getIngredients());
            GenericStack output = itemShapeReader.toGenericStack(recipe.getResultItem(level.registryAccess()).copy());
            if (inputs.isEmpty() || output == null) {
                return RecipeShape.unreadable();
            }
            return new RecipeShape(inputs, List.of(output));
        } catch (RuntimeException ignored) {
            return RecipeShape.unreadable();
        }
    }

    private RecipeShape readReflectiveShape(Recipe<?> recipe) {
        return readReflectiveShape(recipe, null);
    }

    private RecipeShape readReflectiveShape(Recipe<?> recipe, @Nullable MachineIdentity identity) {
        try {
            return reflectionShapeReaders
                    .computeIfAbsent(recipe.getClass(), this::buildReflectionShapeReader)
                    .read(recipe, identity);
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
        return resolveStaticRecipeCodec(recipe.getClass());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    private Codec<Object> resolveStaticRecipeCodec(Class<?> recipeClass) {
        for (Class<?> currentClass = recipeClass;
             currentClass != null && currentClass != Object.class;
             currentClass = currentClass.getSuperclass()) {
            Codec<Object> preferredCodec = resolveNamedStaticRecipeCodec(currentClass, "CODEC");
            if (preferredCodec != null) {
                return preferredCodec;
            }
            preferredCodec = resolveNamedStaticRecipeCodec(currentClass, "MAP_CODEC");
            if (preferredCodec != null) {
                return preferredCodec;
            }
            for (Field field : currentClass.getDeclaredFields()) {
                if (field == null
                        || !Modifier.isStatic(field.getModifiers())
                        || !normalizeName(field.getName()).contains("codec")) {
                    continue;
                }
                Codec<Object> fieldCodec = resolveStaticCodecField(field);
                if (fieldCodec != null) {
                    return fieldCodec;
                }
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    private Codec<Object> resolveNamedStaticRecipeCodec(Class<?> recipeClass, String fieldName) {
        try {
            Field field = recipeClass.getDeclaredField(fieldName);
            return resolveStaticCodecField(field);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    private Codec<Object> resolveStaticCodecField(@Nullable Field field) {
        if (field == null || !Modifier.isStatic(field.getModifiers()) || !tryMakeAccessible(field)) {
            return null;
        }
        try {
            Object value = field.get(null);
            if (value instanceof MapCodec<?> mapCodec) {
                return (Codec<Object>) mapCodec.codec();
            }
            if (value instanceof Codec<?> codec) {
                return (Codec<Object>) codec;
            }
        } catch (IllegalAccessException | RuntimeException ignored) {
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
        if (value instanceof ItemStack stack) {
            return toSignatureInput(stack);
        }
        if (value instanceof FluidStack fluidStack) {
            return toSignatureInput(fluidStack);
        }
        if (value instanceof GenericStack genericStack) {
            return toSignatureInput(genericStack);
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

    private ReflectionShapeReader buildReflectionShapeReader(Class<?> recipeClass) {
        if (recipeClass == null || hasUnsafeDynamicMembers(recipeClass)) {
            return new ReflectionShapeReader(List.of(), List.of(), List.of());
        }
        List<ReflectionMember> inputMembers = new ArrayList<>();
        List<ReflectionMember> outputMembers = new ArrayList<>();
        List<ReflectionMember> contextMembers = new ArrayList<>();
        Set<String> seenMemberSignatures = new LinkedHashSet<>();
        collectReflectionFields(recipeClass, inputMembers, outputMembers, contextMembers, seenMemberSignatures);
        collectReflectionAccessors(recipeClass, inputMembers, outputMembers, contextMembers, seenMemberSignatures);
        return new ReflectionShapeReader(
                List.copyOf(inputMembers),
                List.copyOf(outputMembers),
                List.copyOf(contextMembers)
        );
    }

    private void collectReflectionFields(
            Class<?> recipeClass,
            List<ReflectionMember> inputMembers,
            List<ReflectionMember> outputMembers,
            List<ReflectionMember> contextMembers,
            Set<String> seenMemberSignatures
    ) {
        for (Class<?> currentClass = recipeClass;
             currentClass != null && currentClass != Object.class;
             currentClass = currentClass.getSuperclass()) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (inputMembers.size() + outputMembers.size() >= MAX_REFLECTIVE_MEMBERS_PER_CLASS) {
                    return;
                }
                if (field == null || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                FieldReflectionMember member = new FieldReflectionMember(field);
                if (!seenMemberSignatures.add(member.signature())) {
                    continue;
                }
                String name = field.getName();
                Class<?> type = field.getType();
                if (isOutputMember(name, type) && tryMakeAccessible(field)) {
                    outputMembers.add(member);
                } else if (isInputMember(name, type) && tryMakeAccessible(field)) {
                    inputMembers.add(member);
                } else if (isContextMember(name, type) && tryMakeAccessible(field)) {
                    contextMembers.add(member);
                }
            }
        }
    }

    private void collectReflectionAccessors(
            Class<?> recipeClass,
            List<ReflectionMember> inputMembers,
            List<ReflectionMember> outputMembers,
            List<ReflectionMember> contextMembers,
            Set<String> seenMemberSignatures
    ) {
        for (Method method : recipeClass.getMethods()) {
            if (inputMembers.size() + outputMembers.size() >= MAX_REFLECTIVE_MEMBERS_PER_CLASS) {
                return;
            }
            if (method == null
                    || method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers())
                    || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = accessorMemberName(method.getName());
            Class<?> type = method.getReturnType();
            if (name.isBlank() || !canReadReflectiveValue(type)) {
                continue;
            }
            AccessorReflectionMember member = new AccessorReflectionMember(method);
            if (!seenMemberSignatures.add(member.signature())) {
                continue;
            }
            if (isOutputMember(name, type)) {
                outputMembers.add(member);
            } else if (isInputMember(name, type)) {
                inputMembers.add(member);
            } else if (isContextMember(name, type)) {
                contextMembers.add(member);
            }
        }
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

    @Nullable
    private RecipeSignatureInput toSignatureInput(@Nullable ItemStack inputStack) {
        GenericStack stack = itemShapeReader.toGenericStack(inputStack);
        return stack == null ? null : new RecipeSignatureInput(stack.what(), stack.amount());
    }

    @Nullable
    private RecipeSignatureInput toSignatureInput(@Nullable FluidStack fluidStack) {
        GenericStack stack = fluidShapeReader.toGenericStack(fluidStack);
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
        if (value instanceof ItemStack itemStack) {
            return itemShapeReader.toGenericStack(itemStack);
        }
        if (value instanceof Block block) {
            return toOutputStack(block.asItem());
        }
        if (value instanceof Item item) {
            return item == Items.AIR ? null : itemShapeReader.toGenericStack(item.getDefaultInstance());
        }
        if (value instanceof ItemLike itemLike) {
            return toOutputStack(itemLike.asItem());
        }
        if (value instanceof ResourceLocation resourceLocation) {
            Item item = BuiltInRegistries.ITEM.get(resourceLocation);
            if (item != null && item != Items.AIR) {
                return itemShapeReader.toGenericStack(item.getDefaultInstance());
            }
            Block block = BuiltInRegistries.BLOCK.get(resourceLocation);
            if (block != null) {
                return toOutputStack(block);
            }
            return null;
        }
        if (value instanceof FluidStack fluidStack) {
            return fluidShapeReader.toGenericStack(fluidStack);
        }
        if (value instanceof GenericStack genericStack && genericStack.what() != null && genericStack.amount() > 0L) {
            return new GenericStack(genericStack.what(), genericStack.amount());
        }
        return null;
    }

    private static boolean hasUnsafeDynamicMembers(Class<?> recipeClass) {
        for (Class<?> currentClass = recipeClass;
             currentClass != null && currentClass != Object.class;
             currentClass = currentClass.getSuperclass()) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && isUnsafeDynamicName(field.getName())) {
                    return true;
                }
            }
        }
        for (Method method : recipeClass.getMethods()) {
            if (method != null
                    && method.getParameterCount() == 0
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getDeclaringClass() != Object.class
                    && isUnsafeDynamicName(accessorMemberName(method.getName()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsafeDynamicName(String name) {
        String normalized = normalizeName(name);
        return normalized.contains("chance")
                || normalized.contains("random")
                || normalized.contains("probability")
                || normalized.contains("weight")
                || normalized.contains("secondary")
                || normalized.contains("byproduct")
                || normalized.contains("bonus");
    }

    private static boolean isInputMember(String name, Class<?> type) {
        return isInputMemberName(name) && canReadReflectiveValue(type);
    }

    private static boolean isOutputMember(String name, Class<?> type) {
        return isOutputMemberName(name) && canReadReflectiveValue(type);
    }

    private static boolean isContextMember(String name, Class<?> type) {
        return isContextMemberName(name) && canReadContextValue(type);
    }

    private static boolean matchesAnyName(String normalizedName, String... acceptedNames) {
        for (String acceptedName : acceptedNames) {
            if (normalizedName.contains(acceptedName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canReadReflectiveValue(Class<?> type) {
        return type != null && !type.isPrimitive() && type != Void.TYPE;
    }

    private static boolean canReadContextValue(Class<?> type) {
        return canReadReflectiveValue(type);
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String accessorMemberName(String methodName) {
        String normalized = normalizeName(methodName);
        if (normalized.startsWith("get") && normalized.length() > 3) {
            return normalized.substring(3);
        }
        if (normalized.startsWith("is") && normalized.length() > 2) {
            return normalized.substring(2);
        }
        return normalized;
    }

    private static boolean tryMakeAccessible(Field field) {
        try {
            if (!field.canAccess(null)) {
                field.setAccessible(true);
            }
            return true;
        } catch (IllegalArgumentException ignored) {
            try {
                field.setAccessible(true);
                return true;
            } catch (SecurityException | InaccessibleObjectException ignoredAgain) {
                return false;
            }
        } catch (SecurityException | InaccessibleObjectException ignored) {
            return false;
        }
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

    private final class ReflectionShapeReader {
        private final List<ReflectionMember> inputMembers;
        private final List<ReflectionMember> outputMembers;
        private final List<ReflectionMember> contextMembers;

        private ReflectionShapeReader(
                List<ReflectionMember> inputMembers,
                List<ReflectionMember> outputMembers,
                List<ReflectionMember> contextMembers
        ) {
            this.inputMembers = inputMembers;
            this.outputMembers = outputMembers;
            this.contextMembers = contextMembers;
        }

        private RecipeShape read(Object recipe, @Nullable MachineIdentity identity) {
            if (!contextMembers.isEmpty() && !contextMatchesIdentity(recipe, identity)) {
                return RecipeShape.unreadable();
            }
            List<List<RecipeSignatureInput>> inputs = List.of();
            Set<List<List<RecipeSignatureInput>>> seenInputContributions = new LinkedHashSet<>();
            for (ReflectionMember member : inputMembers) {
                List<List<RecipeSignatureInput>> memberInputs = normalizeInputContribution(readInputChoices(member.get(recipe)));
                if (memberInputs.isEmpty() || !seenInputContributions.add(memberInputs)) {
                    continue;
                }
                inputs = mergeInputChoices(inputs, memberInputs);
                if (inputs.size() > MAX_CANDIDATE_INPUTS_PER_RECIPE) {
                    return RecipeShape.unreadable();
                }
            }
            List<GenericStack> outputs = new ArrayList<>();
            Set<List<GenericStack>> seenOutputContributions = new LinkedHashSet<>();
            for (ReflectionMember member : outputMembers) {
                List<GenericStack> memberOutputs =
                        DirectProcessingStackSupport.normalizeStacks(readOutputStacks(member.get(recipe)));
                if (memberOutputs.isEmpty() || !seenOutputContributions.add(memberOutputs)) {
                    continue;
                }
                outputs.addAll(memberOutputs);
            }
            return new RecipeShape(inputs, DirectProcessingStackSupport.normalizeStacks(outputs));
        }

        private List<List<RecipeSignatureInput>> readInputChoices(Object value) {
            return readInputChoices(value, false);
        }

        private List<List<RecipeSignatureInput>> readInputChoices(Object value, boolean nested) {
            if (value instanceof Ingredient ingredient) {
                return wrapIngredientChoice(ingredient);
            }
            if (value instanceof Optional<?> optional) {
                return optional.isPresent() ? readInputChoices(optional.get(), nested) : List.of();
            }
            RecipeSignatureInput directInput = toInputChoice(value);
            if (directInput != null) {
                return List.of(List.of(directInput));
            }
            if (value instanceof Iterable<?> iterable) {
                return readIterableInputChoices(iterable);
            }
            if (value != null && value.getClass().isArray()) {
                return readArrayInputChoices(value);
            }
            if (!nested) {
                return readNestedInputChoices(value);
            }
            return List.of();
        }

        @Nullable
        private RecipeSignatureInput toInputChoice(@Nullable Object value) {
            if (value instanceof ItemStack stack) {
                return toSignatureInput(stack);
            }
            if (value instanceof FluidStack fluidStack) {
                return toSignatureInput(fluidStack);
            }
            if (value instanceof GenericStack genericStack) {
                return toSignatureInput(genericStack);
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
            return null;
        }

        private List<List<RecipeSignatureInput>> readNestedInputChoices(Object value) {
            List<Object> nestedValues = nestedValueUnwrapper.unwrapInputValues(value);
            if (nestedValues.isEmpty()) {
                return List.of();
            }
            long multiplier = nestedValues.size() == 1 ? nestedValueUnwrapper.readInputMultiplier(value) : 1L;
            List<List<RecipeSignatureInput>> inputs = new ArrayList<>();
            for (Object nestedValue : nestedValues) {
                List<List<RecipeSignatureInput>> nestedInputs = readInputChoices(nestedValue, true);
                if (nestedInputs.isEmpty()) {
                    return List.of();
                }
                if (multiplier > 1L && !hasIntrinsicAmount(nestedValue)) {
                    nestedInputs = scaleInputChoices(nestedInputs, multiplier);
                    if (nestedInputs.isEmpty()) {
                        return List.of();
                    }
                }
                inputs.addAll(nestedInputs);
            }
            return List.copyOf(inputs);
        }

        private List<List<RecipeSignatureInput>> readIterableInputChoices(Iterable<?> iterable) {
            List<List<RecipeSignatureInput>> inputs = new ArrayList<>();
            for (Object element : iterable) {
                List<List<RecipeSignatureInput>> elementInputs = readInputChoices(element);
                if (elementInputs.isEmpty()) {
                    return List.of();
                }
                inputs.addAll(elementInputs);
            }
            return List.copyOf(inputs);
        }

        private List<List<RecipeSignatureInput>> readArrayInputChoices(Object array) {
            int length = java.lang.reflect.Array.getLength(array);
            List<List<RecipeSignatureInput>> inputs = new ArrayList<>();
            for (int index = 0; index < length; index++) {
                List<List<RecipeSignatureInput>> elementInputs =
                        readInputChoices(java.lang.reflect.Array.get(array, index), true);
                if (elementInputs.isEmpty()) {
                    return List.of();
                }
                inputs.addAll(elementInputs);
            }
            return List.copyOf(inputs);
        }

        private List<GenericStack> readOutputStacks(Object value) {
            return readOutputStacks(value, false);
        }

        private List<GenericStack> readOutputStacks(Object value, boolean nested) {
            if (value instanceof Optional<?> optional) {
                return optional.isPresent() ? readOutputStacks(optional.get(), nested) : List.of();
            }
            GenericStack direct = GenericRecipeShapeReader.this.toOutputStack(value);
            if (direct != null) {
                return List.of(direct);
            }
            if (value instanceof Iterable<?> iterable) {
                return readIterableOutputs(iterable);
            }
            if (value != null && value.getClass().isArray()) {
                return readArrayOutputs(value);
            }
            if (!nested) {
                return readNestedOutputs(value);
            }
            return List.of();
        }

        private List<GenericStack> readNestedOutputs(Object value) {
            List<Object> nestedValues = nestedValueUnwrapper.unwrapOutputValues(value);
            if (nestedValues.isEmpty()) {
                return List.of();
            }
            List<GenericStack> outputs = new ArrayList<>();
            for (Object nestedValue : nestedValues) {
                List<GenericStack> nestedOutputs = readOutputStacks(nestedValue, true);
                if (nestedOutputs.isEmpty()) {
                    continue;
                }
                outputs.addAll(nestedOutputs);
            }
            return outputs.isEmpty() ? List.of() : List.copyOf(outputs);
        }

        private List<GenericStack> readIterableOutputs(Iterable<?> iterable) {
            List<GenericStack> outputs = new ArrayList<>();
            for (Object element : iterable) {
                GenericStack stack = GenericRecipeShapeReader.this.toOutputStack(element);
                if (stack != null) {
                    outputs.add(stack);
                }
            }
            return List.copyOf(outputs);
        }

        private boolean contextMatchesIdentity(Object recipe, @Nullable MachineIdentity identity) {
            if (identity == null) {
                return false;
            }
            boolean matched = false;
            for (ReflectionMember member : contextMembers) {
                Object value = member.get(recipe);
                if (value == null) {
                    continue;
                }
                if (!readContextMatches(value, identity, false)) {
                    return false;
                }
                matched = true;
            }
            return matched;
        }

        private boolean readContextMatches(Object value, MachineIdentity identity, boolean nested) {
            if (matchesMachineIdentity(value, identity)) {
                return true;
            }
            if (value instanceof Optional<?> optional) {
                return optional.isPresent() && readContextMatches(optional.get(), identity, nested);
            }
            if (value instanceof Iterable<?> iterable) {
                boolean matched = false;
                for (Object element : iterable) {
                    if (!readContextMatches(element, identity, true)) {
                        return false;
                    }
                    matched = true;
                }
                return matched;
            }
            if (value != null && value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                boolean matched = false;
                for (int index = 0; index < length; index++) {
                    if (!readContextMatches(java.lang.reflect.Array.get(value, index), identity, true)) {
                        return false;
                    }
                    matched = true;
                }
                return matched;
            }
            if (!nested) {
                List<Object> nestedValues = nestedValueUnwrapper.unwrapInputValues(value);
                if (!nestedValues.isEmpty()) {
                    boolean matched = false;
                    for (Object nestedValue : nestedValues) {
                        if (!readContextMatches(nestedValue, identity, true)) {
                            return false;
                        }
                        matched = true;
                    }
                    return matched;
                }
            }
            return false;
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

        private boolean matchesIdentityResource(@Nullable ResourceLocation expected, @Nullable ResourceLocation actual) {
            return expected != null && actual != null && expected.equals(actual);
        }

        private List<GenericStack> readArrayOutputs(Object array) {
            List<GenericStack> outputs = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(array);
            for (int index = 0; index < length; index++) {
                List<GenericStack> nestedOutputs = readOutputStacks(java.lang.reflect.Array.get(array, index), true);
                if (!nestedOutputs.isEmpty()) {
                    outputs.addAll(nestedOutputs);
                }
            }
            return List.copyOf(outputs);
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

        private List<List<RecipeSignatureInput>> normalizeInputContribution(List<List<RecipeSignatureInput>> rawChoices) {
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

        private boolean hasIntrinsicAmount(Object value) {
            return value instanceof ItemStack
                    || value instanceof FluidStack
                    || value instanceof GenericStack;
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
    }

    private sealed interface ReflectionMember permits FieldReflectionMember, AccessorReflectionMember {
        Object get(Object owner);
    }

    private record FieldReflectionMember(Field field) implements ReflectionMember {
        @Override
        public Object get(Object owner) {
            try {
                return field.get(owner);
            } catch (IllegalAccessException | RuntimeException ignored) {
                return null;
            }
        }

        private String signature() {
            return "field:" + field.getDeclaringClass().getName() + ':' + field.getName();
        }
    }

    private record AccessorReflectionMember(Method method) implements ReflectionMember {
        @Override
        public Object get(Object owner) {
            try {
                return method.invoke(owner);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private String signature() {
            return "method:" + method.getDeclaringClass().getName() + ':' + method.getName();
        }
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

        public static ShapeReadOutcome unsafeDynamic() {
            return new ShapeReadOutcome(Set.of(), MachineSupportStatus.UNSAFE_DYNAMIC, MachineSupportReasonCode.DYNAMIC_RECIPE_SHAPE);
        }

        public boolean unsafe() {
            return status == MachineSupportStatus.UNSAFE_DYNAMIC;
        }
    }

    private record MekanismShapeReadResult(
            boolean handled,
            boolean supported,
            List<List<ItemStack>> inputChoices,
            ItemStack output
    ) {
    }

    private static final class MekanismShapeSupportHolder {
        private MekanismShapeSupportHolder() {
        }

        private static MekanismShapeReadResult readStaticItemRecipeShape(
                RecipeTypeCandidate candidate,
                Recipe<?> recipe
        ) {
            var result = git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism
                    .MekanismRecipeShapeSupport.readStaticItemRecipeShape(candidate, recipe);
            return new MekanismShapeReadResult(
                    result.handled(),
                    result.supported(),
                    result.inputChoices(),
                    result.output()
            );
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
