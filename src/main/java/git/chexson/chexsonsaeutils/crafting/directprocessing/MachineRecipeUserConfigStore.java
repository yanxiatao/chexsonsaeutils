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
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.UncheckedIOException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        JsonArray root;
        try {
            root = readExistingArray();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to load direct processing recipe signatures {}", configPath, exception);
            return LoadedImportedSignatures.empty();
        }
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
                if (!DirectProcessingPackRulesRegistry.current().allowsType(identity, imported.recipeTypeId())
                        || imported.recipeTypeId().equals(DirectProcessingPackRecipe.TYPE_ID)) continue;
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

    public synchronized boolean upsertMapping(
            MachineRecipeConfigImportRequest request,
            HolderLookup.Provider registries
    ) {
        if (request == null || request.recipeTypeIds().isEmpty()) {
            return false;
        }
        ensureConfigFileExists();
        List<MachineRecipeImportedSignature> importedSignatures =
                MachineRecipeImportedSignature.parseJson(registries, request.signatureHintsJson());
        JsonArray root = readExistingArray();
        JsonArray original = root.deepCopy();
        Set<ResourceLocation> recipeTypes = new LinkedHashSet<>();
        Map<ResourceLocation, Map<RecipeSignatureKey, MachineRecipeImportedSignature>> signatures = new LinkedHashMap<>();
        JsonObject replacement = null;
        int firstMatch = -1;
        for (int index = 0; index < root.size(); index++) {
            JsonElement element = root.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            if (!matchesMachine(request, readResourceLocation(object, "machine_item"),
                    readResourceLocation(object, "machine_block"))) continue;
            if (firstMatch < 0) {
                firstMatch = index;
                replacement = object.deepCopy();
            }
            if (object.has("recipe_types")) {
                for (JsonElement type : object.getAsJsonArray("recipe_types")) {
                    recipeTypes.add(ResourceLocation.parse(type.getAsString()));
                }
            }
            mergeImportedSignatures(signatures, readStoredSignaturePayloadsForImport(registries, object));
        }
        recipeTypes.addAll(request.recipeTypeIds());
        mergeImportedSignatures(signatures, importedSignatures);
        List<MachineRecipeImportedSignature> merged = signatures.values().stream()
                .flatMap(byShape -> byShape.values().stream()).toList();
        MachineRecipeConfigImportRequest mergedRequest = new MachineRecipeConfigImportRequest(
                request.machineItemId(), request.machineBlockId(), List.copyOf(recipeTypes),
                request.defaultTicks(), request.ioMode(), request.keyTypes(), request.enabled(), "[]");
        JsonObject encoded = buildReplacementEntry(mergedRequest, merged, registries);
        if (replacement != null) {
            replacement.remove(SIGNATURES_MEMBER_NAME);
            replacement.remove(SIGNATURE_SHARDS_MEMBER_NAME);
            for (var member : encoded.entrySet()) replacement.add(member.getKey(), member.getValue());
        } else {
            replacement = encoded;
        }
        if (firstMatch < 0) {
            root.add(replacement);
        } else {
            root.set(firstMatch, replacement);
            for (int index = root.size() - 1; index > firstMatch; index--) {
                JsonElement element = root.get(index);
                if (element.isJsonObject() && matchesMachine(request,
                        readResourceLocation(element.getAsJsonObject(), "machine_item"),
                        readResourceLocation(element.getAsJsonObject(), "machine_block"))) root.remove(index);
            }
        }
        if (root.equals(original)) return false;
        writeJson(configPath, root);
        removeUnreferencedShards(original, root);
        return true;
    }

    public synchronized boolean upsertMappingAndApply(
            MachineRecipeConfigImportRequest request,
            HolderLookup.Provider registries
    ) {
        if (!upsertMapping(request, registries)) return false;
        MachineRecipeConfigMappingRegistry.instance().replaceUserConfigMappings(loadMappings());
        return true;
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
            throw new UncheckedIOException(exception);
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
            if (root == null || !root.isJsonArray()) {
                throw new IllegalStateException("直连处理机配置必须为数组: " + configPath);
            }
            return root.getAsJsonArray();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to reuse AE direct processing user config {}", configPath, exception);
            throw new IllegalStateException("无法读取直连处理机配置: " + configPath, exception);
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

    /** 导入前完整读取旧签名；缺失或损坏必须拒绝覆盖，不能把读取失败当成空库存。 */
    private List<MachineRecipeImportedSignature> readStoredSignaturePayloadsForImport(
            HolderLookup.Provider registries, JsonObject object) {
        List<MachineRecipeImportedSignature> signatures = new ArrayList<>();
        if (object.has(SIGNATURES_MEMBER_NAME)) {
            signatures.addAll(parseStoredSignatures(registries, object.getAsJsonArray(SIGNATURES_MEMBER_NAME)));
        }
        if (object.has(SIGNATURE_SHARDS_MEMBER_NAME)) {
            for (JsonElement element : object.getAsJsonArray(SIGNATURE_SHARDS_MEMBER_NAME)) {
                Path path = resolveConfigRelativePath(element.getAsString());
                if (path == null || !Files.isRegularFile(path)) {
                    throw new IllegalStateException("配方分片缺失或路径无效: " + element);
                }
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    signatures.addAll(parseStoredSignatures(registries, JsonParser.parseReader(reader).getAsJsonArray()));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        return signatures;
    }

    private static List<MachineRecipeImportedSignature> parseStoredSignatures(
            HolderLookup.Provider registries, JsonArray root) {
        List<MachineRecipeImportedSignature> signatures = MachineRecipeImportedSignature.parseJsonArray(registries, root);
        if (signatures.size() != root.size()) throw new IllegalStateException("已存配方签名损坏，拒绝覆盖");
        return signatures;
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
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        Files.createDirectories(machineShardDirectory);
        List<String> shardPaths = new ArrayList<>();
        Map<ResourceLocation, List<MachineRecipeImportedSignature>> byType = new LinkedHashMap<>();
        for (MachineRecipeImportedSignature signature : signatures) {
            byType.computeIfAbsent(signature.recipeTypeId(), ignored -> new ArrayList<>()).add(signature);
        }
        for (List<MachineRecipeImportedSignature> typeSignatures : byType.values()) {
            for (int start = 0; start < typeSignatures.size(); start += SIGNATURES_PER_SHARD) {
                int end = Math.min(typeSignatures.size(), start + SIGNATURES_PER_SHARD);
                JsonArray shardRoot = new JsonArray();
                for (MachineRecipeImportedSignature signature : typeSignatures.subList(start, end)) {
                    JsonObject encoded = signature == null ? null : signature.toJsonObject(registries);
                    if (encoded != null) {
                        shardRoot.add(encoded);
                    }
                }
                String content = toCrlfJson(shardRoot);
                Path shardPath = machineShardDirectory.resolve(contentDigest(content) + ".json");
                if (!Files.exists(shardPath)) writeAtomically(shardPath, content);
                shardPaths.add(toConfigRelativePath(shardPath));
            }
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
            writeAtomically(path, toCrlfJson(root));
        } catch (IOException exception) {
            LOGGER.warn("Failed to write AE direct processing config {}", path, exception);
            throw new UncheckedIOException(exception);
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

    /** 映射成功提交后才回收旧分片，其他机器仍引用的文件保留。 */
    private void removeUnreferencedShards(JsonArray previous, JsonArray current) {
        Set<String> retired = shardPaths(previous);
        retired.removeAll(shardPaths(current));
        Path shardRoot = signatureShardRootPath.toAbsolutePath().normalize();
        for (String relative : retired) {
            Path path = resolveConfigRelativePath(relative);
            if (path == null || !path.toAbsolutePath().normalize().startsWith(shardRoot)) continue;
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                LOGGER.warn("Failed to remove retired direct processing signature shard {}", path, exception);
            }
        }
    }

    private static Set<String> shardPaths(JsonArray mappings) {
        Set<String> paths = new LinkedHashSet<>();
        for (JsonElement element : mappings) {
            if (!element.isJsonObject()) continue;
            JsonObject mapping = element.getAsJsonObject();
            if (!mapping.has(SIGNATURE_SHARDS_MEMBER_NAME)) continue;
            for (JsonElement path : mapping.getAsJsonArray(SIGNATURE_SHARDS_MEMBER_NAME)) paths.add(path.getAsString());
        }
        return paths;
    }

    private void writeAtomically(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path pending = Files.createTempFile(path.getParent(), "direct-processing-", ".json.tmp");
        try {
            writeText(pending, content);
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
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

    /** 按配方类型及无序的精确数量签名合并，保留同类型内已存配方。 */
    static void mergeImportedSignatures(
            Map<ResourceLocation, Map<RecipeSignatureKey, MachineRecipeImportedSignature>> grouped,
            List<MachineRecipeImportedSignature> imported
    ) {
        for (MachineRecipeImportedSignature payload : imported) {
            RecipeSignature signature = payload.toRecipeSignature();
            RecipeSignatureKey key = RecipeSignatureKey.of(signature);
            if (key == null) throw new IllegalArgumentException("导入配方缺少有效输入或输出");
            grouped.computeIfAbsent(payload.recipeTypeId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(key, MachineRecipeImportedSignature.fromRecipeSignature(payload.recipeTypeId(), signature));
        }
    }

    private static String contentDigest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
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
                "- 增量导入按配方类型和精确输入输出数量去重；完整分片按内容哈希复用。",
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
                "- 若 shard 丢失或损坏，增量导入会拒绝覆盖；请先从备份恢复对应分片。"
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
