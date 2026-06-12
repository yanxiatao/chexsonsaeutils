package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.GenericStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record MachineRecipeImportedSignature(
        ResourceLocation recipeTypeId,
        List<MachineRecipeImportedStack> inputs,
        List<MachineRecipeImportedStack> outputs
) {

    public MachineRecipeImportedSignature {
        inputs = copyValid(inputs);
        outputs = copyValid(outputs);
    }

    @Nullable
    public RecipeSignature toRecipeSignature() {
        if (inputs.isEmpty() || outputs.isEmpty()) {
            return null;
        }
        List<RecipeSignatureInput> signatureInputs = new ArrayList<>(inputs.size());
        for (MachineRecipeImportedStack input : inputs) {
            GenericStack stack = input == null ? null : input.toGenericStack();
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                return null;
            }
            signatureInputs.add(new RecipeSignatureInput(stack.what(), stack.amount()));
        }
        List<GenericStack> signatureOutputs = new ArrayList<>(outputs.size());
        for (MachineRecipeImportedStack output : outputs) {
            GenericStack stack = output == null ? null : output.toGenericStack();
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                return null;
            }
            signatureOutputs.add(stack);
        }
        RecipeSignature signature = new RecipeSignature(null, signatureInputs, signatureOutputs);
        return signature.inputs().isEmpty() || signature.outputs().isEmpty() ? null : signature;
    }

    @Nullable
    public static MachineRecipeImportedSignature fromRecipeSignature(
            @Nullable ResourceLocation recipeTypeId,
            @Nullable RecipeSignature signature
    ) {
        if (recipeTypeId == null || signature == null) {
            return null;
        }
        List<MachineRecipeImportedStack> inputPayloads = new ArrayList<>(signature.inputs().size());
        for (RecipeSignatureInput input : signature.inputs()) {
            if (input == null || input.input() == null || input.amount() <= 0L) {
                return null;
            }
            MachineRecipeImportedStack imported = MachineRecipeImportedStack.fromGenericStack(
                    new GenericStack(input.input(), input.amount())
            );
            if (imported == null) {
                return null;
            }
            inputPayloads.add(imported);
        }
        List<MachineRecipeImportedStack> outputPayloads = new ArrayList<>(signature.outputs().size());
        for (GenericStack output : signature.outputs()) {
            MachineRecipeImportedStack imported = MachineRecipeImportedStack.fromGenericStack(output);
            if (imported == null) {
                return null;
            }
            outputPayloads.add(imported);
        }
        return inputPayloads.isEmpty() || outputPayloads.isEmpty()
                ? null
                : new MachineRecipeImportedSignature(recipeTypeId, inputPayloads, outputPayloads);
    }

    JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        if (recipeTypeId != null) {
            object.addProperty("recipe_type", recipeTypeId.toString());
        }
        JsonArray inputArray = new JsonArray();
        for (MachineRecipeImportedStack input : inputs) {
            if (input != null) {
                inputArray.add(input.toJsonObject());
            }
        }
        JsonArray outputArray = new JsonArray();
        for (MachineRecipeImportedStack output : outputs) {
            if (output != null) {
                outputArray.add(output.toJsonObject());
            }
        }
        object.add("inputs", inputArray);
        object.add("outputs", outputArray);
        return object;
    }

    public static String toJson(List<MachineRecipeImportedSignature> signatures) {
        JsonArray root = new JsonArray();
        if (signatures != null) {
            for (MachineRecipeImportedSignature signature : signatures) {
                if (signature != null && signature.recipeTypeId() != null
                        && !signature.inputs().isEmpty() && !signature.outputs().isEmpty()) {
                    root.add(signature.toJsonObject());
                }
            }
        }
        return root.toString();
    }

    public static List<MachineRecipeImportedSignature> parseJson(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            return root != null && root.isJsonArray() ? parseJsonArray(root.getAsJsonArray()) : List.of();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    public static List<MachineRecipeImportedSignature> parseJsonArray(@Nullable JsonArray root) {
        if (root == null || root.isEmpty()) {
            return List.of();
        }
        List<MachineRecipeImportedSignature> parsed = new ArrayList<>(root.size());
        for (JsonElement element : root) {
            if (element != null && element.isJsonObject()) {
                MachineRecipeImportedSignature signature = fromJsonObject(element.getAsJsonObject());
                if (signature != null) {
                    parsed.add(signature);
                }
            }
        }
        return List.copyOf(parsed);
    }

    public static List<ResourceLocation> collectRecipeTypeIds(List<MachineRecipeImportedSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (MachineRecipeImportedSignature signature : signatures) {
            if (signature != null && signature.recipeTypeId() != null) {
                ids.add(signature.recipeTypeId());
            }
        }
        return ids.isEmpty() ? List.of() : List.copyOf(ids);
    }

    @Nullable
    private static MachineRecipeImportedSignature fromJsonObject(@Nullable JsonObject object) {
        if (object == null) {
            return null;
        }
        ResourceLocation recipeTypeId = readResourceLocation(object, "recipe_type");
        List<MachineRecipeImportedStack> inputs = readStacks(object, "inputs");
        List<MachineRecipeImportedStack> outputs = readStacks(object, "outputs");
        MachineRecipeImportedSignature signature = new MachineRecipeImportedSignature(recipeTypeId, inputs, outputs);
        return signature.toRecipeSignature() == null ? null : signature;
    }

    private static List<MachineRecipeImportedStack> copyValid(List<MachineRecipeImportedStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        List<MachineRecipeImportedStack> copied = new ArrayList<>(stacks.size());
        for (MachineRecipeImportedStack stack : stacks) {
            if (stack != null && stack.toGenericStack() != null) {
                copied.add(stack);
            }
        }
        return copied.isEmpty() ? List.of() : List.copyOf(copied);
    }

    private static List<MachineRecipeImportedStack> readStacks(JsonObject object, String memberName) {
        if (!object.has(memberName) || !object.get(memberName).isJsonArray()) {
            return List.of();
        }
        List<MachineRecipeImportedStack> stacks = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(memberName)) {
            if (element != null && element.isJsonObject()) {
                MachineRecipeImportedStack stack = MachineRecipeImportedStack.fromJsonObject(element.getAsJsonObject());
                if (stack != null) {
                    stacks.add(stack);
                }
            }
        }
        return stacks.isEmpty() ? List.of() : List.copyOf(stacks);
    }

    @Nullable
    private static ResourceLocation readResourceLocation(JsonObject object, String memberName) {
        if (!object.has(memberName) || !object.get(memberName).isJsonPrimitive()) {
            return null;
        }
        return ResourceLocation.tryParse(object.get(memberName).getAsString());
    }
}
