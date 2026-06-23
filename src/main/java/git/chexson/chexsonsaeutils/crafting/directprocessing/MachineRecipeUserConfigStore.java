package git.chexson.chexsonsaeutils.crafting.directprocessing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class MachineRecipeUserConfigStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CRLF = "\r\n";
    private static final String CONFIG_FILE_NAME = "direct_processing_machines.json";
    private static final String GUIDE_FILE_NAME = "direct_processing_machines.guide.md";
    private static final String SIGNATURE_SHARD_ROOT_NAME = "direct_processing_machines.signatures";
    private static final String SIGNATURES_MEMBER_NAME = "signatures";
    private static final String SIGNATURE_SHARDS_MEMBER_NAME = "signature_shards";
    private static final int SIGNATURES_PER_SHARD = 256;

    private final Path configDirectory;
    private final Path configPath;
    private final Path guidePath;
    private final Path signatureShardRootPath;

    private MachineRecipeUserConfigStore() {
        this(defaultConfigDirectory());
    }

    MachineRecipeUserConfigStore(Path configDirectory) {
        this.configDirectory = configDirectory == null ? defaultConfigDirectory() : configDirectory;
        this.configPath = this.configDirectory.resolve(CONFIG_FILE_NAME);
        this.guidePath = this.configDirectory.resolve(GUIDE_FILE_NAME);
        this.signatureShardRootPath = this.configDirectory.resolve(SIGNATURE_SHARD_ROOT_NAME);
    }

    public static MachineRecipeUserConfigStore instance() {
        return Holder.INSTANCE;
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
            HolderLookup.Provider registries,
            MachineIdentity identity,
            List<ResourceLocation> allowedRecipeTypeIds
    ) {
        ensureConfigFileExists();
        if (registries == null || identity == null || !Files.isRegularFile(configPath)) {
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
            for (MachineRecipeImportedSignature imported : readStoredSignaturePayloads(registries, object)) {
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

    public synchronized void upsertMapping(
            MachineRecipeConfigImportRequest request,
            HolderLookup.Provider registries
    ) {
        if (request == null || request.recipeTypeIds().isEmpty()) {
            return;
        }
        ensureConfigFileExists();
        List<MachineRecipeImportedSignature> importedSignatures =
                MachineRecipeImportedSignature.parseJson(registries, request.signatureHintsJson());
        JsonObject replacement = buildReplacementEntry(request, importedSignatures, registries);
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
                root.set(index, replacement);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            root.add(replacement);
        }
        writeJson(configPath, root);
    }

    public synchronized void upsertMappingAndApply(
            MachineRecipeConfigImportRequest request,
            HolderLookup.Provider registries
    ) {
        upsertMapping(request, registries);
        MachineRecipeConfigMappingRegistry.instance().replaceUserConfigMappings(loadMappings());
    }

    public String configPathForUi() {
        return "config/chexsonsaeutils/direct_processing_machines.json";
    }

    public String guidePathForUi() {
        return "config/chexsonsaeutils/direct_processing_machines.guide.md";
    }

    private JsonObject buildReplacementEntry(
            MachineRecipeConfigImportRequest request,
            List<MachineRecipeImportedSignature> importedSignatures,
            HolderLookup.Provider registries
    ) {
        try {
            List<String> shardPaths = writeSignatureShards(request, importedSignatures, registries);
            return toJson(request, registries, List.of(), shardPaths);
        } catch (IOException exception) {
            LOGGER.warn("Failed to write AE direct processing signature shards for {}", request.machineItemId(), exception);
            return toJson(request, registries, importedSignatures, List.of());
        }
    }

    private void ensureConfigFileExists() {
        try {
            Files.createDirectories(configDirectory);
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

    private List<MachineRecipeImportedSignature> readStoredSignaturePayloads(
            HolderLookup.Provider registries,
            JsonObject object
    ) {
        if (object.has(SIGNATURE_SHARDS_MEMBER_NAME)) {
            List<MachineRecipeImportedSignature> shardSignatures = readShardSignatures(registries, object);
            if (!shardSignatures.isEmpty() || !object.has(SIGNATURES_MEMBER_NAME)) {
                return shardSignatures;
            }
        }
        if (!object.has(SIGNATURES_MEMBER_NAME) || !object.get(SIGNATURES_MEMBER_NAME).isJsonArray()) {
            return List.of();
        }
        return MachineRecipeImportedSignature.parseJsonArray(registries, object.getAsJsonArray(SIGNATURES_MEMBER_NAME));
    }

    private List<MachineRecipeImportedSignature> readShardSignatures(
            HolderLookup.Provider registries,
            JsonObject object
    ) {
        JsonArray shardArray = object.getAsJsonArray(SIGNATURE_SHARDS_MEMBER_NAME);
        if (shardArray == null || shardArray.isEmpty()) {
            return List.of();
        }
        List<MachineRecipeImportedSignature> signatures = new ArrayList<>();
        for (JsonElement element : shardArray) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            Path shardPath = resolveConfigRelativePath(element.getAsString());
            if (shardPath == null || !Files.isRegularFile(shardPath)) {
                continue;
            }
            signatures.addAll(readSignatureShard(registries, shardPath));
        }
        return signatures.isEmpty() ? List.of() : List.copyOf(signatures);
    }

    private List<MachineRecipeImportedSignature> readSignatureShard(
            HolderLookup.Provider registries,
            Path shardPath
    ) {
        try (Reader reader = Files.newBufferedReader(shardPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root != null && root.isJsonArray()
                    ? MachineRecipeImportedSignature.parseJsonArray(registries, root.getAsJsonArray())
                    : List.of();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to read AE direct processing signature shard {}", shardPath, exception);
            return List.of();
        }
    }

    private List<String> writeSignatureShards(
            MachineRecipeConfigImportRequest request,
            List<MachineRecipeImportedSignature> signatures,
            HolderLookup.Provider registries
    ) throws IOException {
        Path machineShardDirectory = machineShardDirectory(request.machineItemId(), request.machineBlockId());
        deleteRecursively(machineShardDirectory);
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        Files.createDirectories(machineShardDirectory);
        List<String> shardPaths = new ArrayList<>();
        int shardIndex = 0;
        for (int start = 0; start < signatures.size(); start += SIGNATURES_PER_SHARD) {
            int end = Math.min(signatures.size(), start + SIGNATURES_PER_SHARD);
            JsonArray shardRoot = new JsonArray();
            for (MachineRecipeImportedSignature signature : signatures.subList(start, end)) {
                JsonObject encoded = signature == null ? null : signature.toJsonObject(registries);
                if (encoded != null) {
                    shardRoot.add(encoded);
                }
            }
            Path shardPath = machineShardDirectory.resolve(String.format("%04d.json", shardIndex++));
            writeText(shardPath, toCrlfJson(shardRoot));
            shardPaths.add(toConfigRelativePath(shardPath));
        }
        return List.copyOf(shardPaths);
    }

    private Path machineShardDirectory(
            ResourceLocation machineItemId,
            ResourceLocation machineBlockId
    ) {
        String canonicalKey = canonicalMachineKey(machineItemId, machineBlockId);
        String safeLabel = sanitizeMachineLabel(canonicalKey);
        String hash = String.format("%08x", canonicalKey.hashCode());
        return signatureShardRootPath.resolve(safeLabel + "--" + hash);
    }

    private static String canonicalMachineKey(
            ResourceLocation machineItemId,
            ResourceLocation machineBlockId
    ) {
        String itemPart = machineItemId == null ? "_" : machineItemId.toString();
        String blockPart = machineBlockId == null ? "_" : machineBlockId.toString();
        return itemPart + "__" + blockPart;
    }

    private static String sanitizeMachineLabel(String canonicalKey) {
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < canonicalKey.length(); index++) {
            char current = canonicalKey.charAt(index);
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')) {
                safe.append(current);
            } else {
                safe.append('_');
            }
            if (safe.length() >= 48) {
                break;
            }
        }
        return safe.isEmpty() ? "machine" : safe.toString();
    }

    private void writeJson(Path path, JsonElement root) {
        try {
            writeText(path, toCrlfJson(root));
        } catch (IOException exception) {
            LOGGER.warn("Failed to write AE direct processing config {}", path, exception);
        }
    }

    private String toConfigRelativePath(Path path) {
        return configDirectory.relativize(path).toString().replace('\\', '/');
    }

    private Path resolveConfigRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path resolved = configDirectory.resolve(relativePath).normalize();
        return resolved.startsWith(configDirectory.normalize()) ? resolved : null;
    }

    private void writeText(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(content == null ? "" : content);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path candidate : paths) {
                Files.deleteIfExists(candidate);
            }
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

    private static JsonObject toJson(
            MachineRecipeConfigImportRequest request,
            HolderLookup.Provider registries,
            List<MachineRecipeImportedSignature> inlineSignatures,
            List<String> signatureShardPaths
    ) {
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
        if (signatureShardPaths != null && !signatureShardPaths.isEmpty()) {
            JsonArray shards = new JsonArray();
            for (String path : signatureShardPaths) {
                if (path != null && !path.isBlank()) {
                    shards.add(path);
                }
            }
            if (!shards.isEmpty()) {
                object.add(SIGNATURE_SHARDS_MEMBER_NAME, shards);
            }
        } else if (inlineSignatures != null && !inlineSignatures.isEmpty()) {
            JsonArray signatures = new JsonArray();
            for (MachineRecipeImportedSignature signature : inlineSignatures) {
                JsonObject encoded = signature == null ? null : signature.toJsonObject(registries);
                if (encoded != null) {
                    signatures.add(encoded);
                }
            }
            if (!signatures.isEmpty()) {
                object.add(SIGNATURES_MEMBER_NAME, signatures);
            }
        }
        return object;
    }

    private static ResourceLocation readResourceLocation(JsonObject object, String memberName) {
        if (!object.has(memberName) || !object.get(memberName).isJsonPrimitive()) {
            return null;
        }
        return ResourceLocation.tryParse(object.get(memberName).getAsString());
    }

    private static String toCrlfJson(JsonElement root) {
        return GSON.toJson(root).replace("\r\n", "\n").replace("\n", CRLF);
    }

    private static Path defaultConfigDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve("chexsonsaeutils");
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
        modExample.addProperty("_comment", "示例 2：把 machine 与 recipe_types 改成目标模组的机器和配方类型。");
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
                "# AE 直连机用户映射指南",
                "",
                "- 配置文件路径：`config/chexsonsaeutils/direct_processing_machines.json`。",
                "- 大量 JEI 导入签名会拆分到 `config/chexsonsaeutils/direct_processing_machines.signatures/`。",
                "- 主文件继续保存机器元数据；大体积签名优先通过 `signature_shards` 引用外部分片。",
                "",
                "## 字段说明",
                "",
                "- `machine_item`：机器物品 ID，例如 `minecraft:furnace`。",
                "- `machine_block`：机器方块 ID。",
                "- `recipe_types`：该机器允许的 `recipe type` 列表。",
                "- `default_ticks`：默认处理时长，最小值为 `1`。",
                "- `enabled`：是否启用该映射。",
                "- `io_mode`：当前保持 `generic`。",
                "- `key_types`：可填 `item`、`fluid`、`mixed`、`other` 或 `any`。",
                "- `signature_shards`：可选。相对 `config/chexsonsaeutils/` 的签名分片路径数组。",
                "- `signatures`：旧内联格式仍可读取；新写回优先使用 `signature_shards`。",
                "- `signatures[*].inputs[*]` 与 `signatures[*].outputs[*]` 统一使用 `AEKey + amount` 结构。",
                "",
                "## 分片规则",
                "",
                "- 每个 shard 文件内容都是纯 JSON array。",
                "- 每个 shard 最多保存 256 条 signature。",
                "- 重新导入某台机器时，只会重写该机器自己的 shard 目录与主文件入口。",
                "",
                "## 兼容性",
                "",
                "- 旧 `key_type/key_id/amount` 栈结构仍可读取。",
                "- 旧内联 `signatures` 仍可读取；重新导入或写回后会升级到 `signature_shards`。",
                "- `configPathForUi()` 仍指向主文件，不需要改 UI 路径。",
                "",
                "## 排查",
                "",
                "- 导入后仍不支持时，先检查 `recipe_types` 是否填对。",
                "- 若主文件体积明显上涨，确认对应机器是否已经写出 `signature_shards`。",
                "- 若 shard 丢失或被手工删除，重新执行一次 JEI 导入即可重建。"
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

    private static final class Holder {
        private static final MachineRecipeUserConfigStore INSTANCE = new MachineRecipeUserConfigStore();
    }
}
