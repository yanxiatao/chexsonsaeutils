package git.chexson.chexsonsaeutils.crafting.directprocessing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MachineRecipeUserConfigStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final MachineRecipeUserConfigStore INSTANCE = new MachineRecipeUserConfigStore();
    private static final String CRLF = "\r\n";

    private final Path configPath = FMLPaths.CONFIGDIR.get()
            .resolve("chexsonsaeutils")
            .resolve("direct_processing_machines.json");
    private final Path guidePath = FMLPaths.CONFIGDIR.get()
            .resolve("chexsonsaeutils")
            .resolve("direct_processing_machines.guide.md");

    private MachineRecipeUserConfigStore() {
    }

    public static MachineRecipeUserConfigStore instance() {
        return INSTANCE;
    }

    public synchronized List<MachineRecipeConfigMappingRegistry.ParsedConfigMapping> loadMappings() {
        ensureConfigFileExists();
        if (!Files.isRegularFile(configPath)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                return List.of();
            }
            List<MachineRecipeConfigMappingRegistry.ParsedConfigMapping> mappings = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (element != null && element.isJsonObject()) {
                    readEntry(element.getAsJsonObject(), mappings);
                }
            }
            return List.copyOf(mappings);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to read AE direct processing user config {}", configPath, exception);
            return List.of();
        }
    }

    public synchronized LoadedImportedSignatures loadImportedSignatures(
            MachineIdentity identity
    ) {
        return loadImportedSignatures(identity, List.of());
    }

    public synchronized LoadedImportedSignatures loadImportedSignatures(
            MachineIdentity identity,
            List<ResourceLocation> allowedRecipeTypeIds
    ) {
        ensureConfigFileExists();
        if (identity == null || !Files.isRegularFile(configPath)) {
            return LoadedImportedSignatures.empty();
        }
        Set<ResourceLocation> allowedIds = allowedRecipeTypeIds == null || allowedRecipeTypeIds.isEmpty()
                ? Set.of()
                : Set.copyOf(allowedRecipeTypeIds);
        Set<ResourceLocation> recipeTypeIds = new LinkedHashSet<>();
        Set<RecipeSignature> signatures = new LinkedHashSet<>();
        JsonArray root = readExistingArray();
        for (JsonElement element : root) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("enabled") && !object.get("enabled").getAsBoolean()) {
                continue;
            }
            if (!matchesMachine(identity, object)) {
                continue;
            }
            for (MachineRecipeImportedSignature imported : readStoredSignaturePayloads(object)) {
                if (imported == null || imported.recipeTypeId() == null) {
                    continue;
                }
                if (!allowedIds.isEmpty() && !allowedIds.contains(imported.recipeTypeId())) {
                    continue;
                }
                RecipeSignature signature = imported.toRecipeSignature();
                if (signature != null) {
                    recipeTypeIds.add(imported.recipeTypeId());
                    signatures.add(signature);
                }
            }
        }
        return new LoadedImportedSignatures(List.copyOf(recipeTypeIds), signatures);
    }

    public synchronized void upsertMapping(MachineRecipeConfigImportRequest request) {
        if (request == null || request.recipeTypeIds().isEmpty()) {
            return;
        }
        ensureConfigFileExists();
        JsonArray root = readExistingArray();
        boolean replaced = false;
        for (int index = 0; index < root.size(); index++) {
            JsonElement element = root.get(index);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            ResourceLocation machineItemId = readResourceLocation(object, "machine_item");
            ResourceLocation machineBlockId = readResourceLocation(object, "machine_block");
            if (matchesMachine(request, machineItemId, machineBlockId)) {
                root.set(index, toJson(request));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            root.add(toJson(request));
        }
        writeArray(root);
    }

    public synchronized void upsertMappingAndApply(MachineRecipeConfigImportRequest request) {
        upsertMapping(request);
        MachineRecipeConfigMappingRegistry.instance().replaceUserConfigMappings(loadMappings());
    }

    public String configPathForUi() {
        return "config/chexsonsaeutils/direct_processing_machines.json";
    }

    public String guidePathForUi() {
        return "config/chexsonsaeutils/direct_processing_machines.guide.md";
    }

    private void ensureConfigFileExists() {
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.exists(configPath)) {
                writeText(configPath, buildDefaultConfigTemplate());
            }
            if (!Files.exists(guidePath)) {
                writeText(guidePath, buildGuideMarkdown());
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to prepare AE direct processing user config {}", configPath, exception);
        }
    }

    private JsonArray readExistingArray() {
        if (!Files.isRegularFile(configPath)) {
            return new JsonArray();
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root != null && root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to reuse AE direct processing user config {}", configPath, exception);
            return new JsonArray();
        }
    }

    private void writeArray(JsonArray root) {
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            writer.write(toCrlfJson(root));
        } catch (IOException exception) {
            LOGGER.warn("Failed to write AE direct processing user config {}", configPath, exception);
        }
    }

    private void readEntry(
            JsonObject object,
            List<MachineRecipeConfigMappingRegistry.ParsedConfigMapping> mappings
    ) {
        if (object.has("enabled") && !object.get("enabled").getAsBoolean()) {
            return;
        }
        ResourceLocation machineId = readResourceLocation(object, "machine_item");
        if (machineId == null) {
            machineId = readResourceLocation(object, "machine_block");
        }
        if (machineId == null || !object.has("recipe_types") || !object.get("recipe_types").isJsonArray()) {
            return;
        }
        int defaultTicks = object.has("default_ticks") ? Math.max(1, object.get("default_ticks").getAsInt()) : 20;
        JsonArray recipeTypes = object.getAsJsonArray("recipe_types");
        for (JsonElement recipeTypeElement : recipeTypes) {
            if (recipeTypeElement == null || !recipeTypeElement.isJsonPrimitive()) {
                continue;
            }
            ResourceLocation recipeTypeId = ResourceLocation.tryParse(recipeTypeElement.getAsString());
            if (recipeTypeId == null) {
                continue;
            }
            RecipeType<?> recipeType = BuiltInRegistries.RECIPE_TYPE.get(recipeTypeId);
            if (recipeType == null) {
                continue;
            }
            mappings.add(new MachineRecipeConfigMappingRegistry.ParsedConfigMapping(machineId, recipeType, defaultTicks));
        }
    }

    private static boolean matchesMachine(
            MachineRecipeConfigImportRequest request,
            ResourceLocation machineItemId,
            ResourceLocation machineBlockId
    ) {
        return request.machineItemId() != null && request.machineItemId().equals(machineItemId)
                || request.machineBlockId() != null && request.machineBlockId().equals(machineBlockId);
    }

    private static boolean matchesMachine(MachineIdentity identity, JsonObject object) {
        ResourceLocation machineItemId = readResourceLocation(object, "machine_item");
        ResourceLocation machineBlockId = readResourceLocation(object, "machine_block");
        return identity.machineItemId() != null && identity.machineItemId().equals(machineItemId)
                || identity.blockId() != null && identity.blockId().equals(machineBlockId);
    }

    private static JsonObject toJson(MachineRecipeConfigImportRequest request) {
        JsonObject object = new JsonObject();
        if (request.machineItemId() != null) {
            object.addProperty("machine_item", request.machineItemId().toString());
        }
        if (request.machineBlockId() != null) {
            object.addProperty("machine_block", request.machineBlockId().toString());
        }
        JsonArray recipeTypes = new JsonArray();
        for (ResourceLocation recipeTypeId : request.recipeTypeIds()) {
            if (recipeTypeId != null) {
                recipeTypes.add(recipeTypeId.toString());
            }
        }
        object.add("recipe_types", recipeTypes);
        object.addProperty("default_ticks", request.defaultTicks());
        object.addProperty("enabled", request.enabled());
        object.addProperty("io_mode", request.ioMode());
        object.addProperty("key_types", request.keyTypes());
        JsonArray signatures = parseSignatureHintsArray(request.signatureHintsJson());
        if (!signatures.isEmpty()) {
            object.add("signatures", signatures);
        }
        return object;
    }

    private static List<MachineRecipeImportedSignature> readStoredSignaturePayloads(JsonObject object) {
        if (!object.has("signatures") || !object.get("signatures").isJsonArray()) {
            return List.of();
        }
        return MachineRecipeImportedSignature.parseJsonArray(object.getAsJsonArray("signatures"));
    }

    private static JsonArray parseSignatureHintsArray(String signatureHintsJson) {
        JsonArray array = new JsonArray();
        for (MachineRecipeImportedSignature signature : MachineRecipeImportedSignature.parseJson(signatureHintsJson)) {
            if (signature != null) {
                array.add(signature.toJsonObject());
            }
        }
        return array;
    }

    private static ResourceLocation readResourceLocation(JsonObject object, String memberName) {
        if (!object.has(memberName) || !object.get(memberName).isJsonPrimitive()) {
            return null;
        }
        return ResourceLocation.tryParse(object.get(memberName).getAsString());
    }

    private void writeText(Path path, String content) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(content == null ? "" : content);
        }
    }

    private static String toCrlfJson(JsonArray root) {
        return GSON.toJson(root).replace("\r\n", "\n").replace("\n", CRLF);
    }

    private static String buildDefaultConfigTemplate() {
        JsonArray root = new JsonArray();

        JsonObject vanillaExample = new JsonObject();
        vanillaExample.addProperty("_comment", "示例 1：把 enabled 改成 true 后，可为原版熔炉启用映射。");
        vanillaExample.addProperty("machine_item", "minecraft:furnace");
        vanillaExample.addProperty("machine_block", "minecraft:furnace");
        JsonArray vanillaRecipeTypes = new JsonArray();
        vanillaRecipeTypes.add("minecraft:smelting");
        vanillaExample.add("recipe_types", vanillaRecipeTypes);
        vanillaExample.addProperty("default_ticks", 20);
        vanillaExample.addProperty("enabled", false);
        vanillaExample.addProperty("io_mode", "generic");
        vanillaExample.addProperty("key_types", "item");
        root.add(vanillaExample);

        JsonObject modExample = new JsonObject();
        modExample.addProperty("_comment", "示例 2：把 machine 与 recipe_types 改成目标模组的机器与配方类型。");
        modExample.addProperty("machine_item", "examplemod:crusher");
        modExample.addProperty("machine_block", "examplemod:crusher");
        JsonArray modRecipeTypes = new JsonArray();
        modRecipeTypes.add("examplemod:crushing");
        modExample.add("recipe_types", modRecipeTypes);
        modExample.addProperty("default_ticks", 20);
        modExample.addProperty("enabled", false);
        modExample.addProperty("io_mode", "generic");
        modExample.addProperty("key_types", "any");
        root.add(modExample);

        return toCrlfJson(root);
    }

    private static String buildGuideMarkdown() {
        return String.join(CRLF,
                "# AE 直连处理机用户映射指引",
                "",
                "- 配置文件路径：`config/chexsonsaeutils/direct_processing_machines.json`。",
                "- 每个对象代表一台可绑定机器的显式映射。",
                "- 只有 `enabled=true` 的项会生效。",
                "",
                "## 字段说明",
                "",
                "- `machine_item`：机器方块物品 ID，例如 `minecraft:furnace`。",
                "- `machine_block`：机器方块 ID。",
                "- `recipe_types`：该机器允许的 `recipe type` 列表，例如 `minecraft:smelting`。",
                "- `default_ticks`：默认处理时长，最小值为 `1`。",
                "- `enabled`：是否启用该条映射。",
                "- `io_mode`：当前保持 `generic`。",
                "- `key_types`：`item`、`fluid` 或 `any`。",
                "- `signatures`：可选。JEI 导入后生成的静态输入输出签名。",
                "",
                "## 填写规则",
                "",
                "- `modid:machine` 填机器方块物品或方块的注册名。",
                "- `modid:recipe_type` 填服务端已注册的配方类型名。",
                "- 同一台机器可以填写多个 `recipe_types`。",
                "",
                "## 示例",
                "",
                "- 原版熔炉：`machine_item = minecraft:furnace`，`recipe_types = [minecraft:smelting]`。",
                "- 第三方机器：`machine_item = examplemod:crusher`，`recipe_types = [examplemod:crushing]`。",
                "",
                "## 排障",
                "",
                "- 如果 JEI 能看到候选，导入后仍显示不支持，优先检查该配方是否存在公开输入输出访问器。",
                "- 如果没有可用访问器，或配方依赖概率、副产物、世界状态、运行时动态数据，",
                "  可直接在此文件手工指定 `machine_item/machine_block + recipe_types`。",
                "- JEI 导入会同时尝试写入静态签名；若服务端能校验这些签名，后续索引重建可直接复用。",
                "- 若导入后仍不可用，优先检查本文件中的 `recipe_types` 与可选 `signatures` 项。"
        ) + CRLF;
    }

    public record LoadedImportedSignatures(
            List<ResourceLocation> recipeTypeIds,
            Set<RecipeSignature> signatures
    ) {
        private static LoadedImportedSignatures empty() {
            return new LoadedImportedSignatures(List.of(), Set.of());
        }

        public LoadedImportedSignatures {
            recipeTypeIds = recipeTypeIds == null ? List.of() : List.copyOf(recipeTypeIds);
            signatures = signatures == null ? Set.of() : Set.copyOf(signatures);
        }
    }
}
