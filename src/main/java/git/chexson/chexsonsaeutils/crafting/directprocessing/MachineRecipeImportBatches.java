package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按配方类型和字符串线限分包；每包都能独立增量提交，不因配方总量大而丢弃整个导入。 */
public final class MachineRecipeImportBatches {
    private static final int MAX_JSON_CHARACTERS = 30_000;
    private static final int SIGNATURES_PER_BATCH = 128;

    private MachineRecipeImportBatches() {}

    public static List<MachineRecipeConfigImportRequest> split(
            HolderLookup.Provider registries, MachineRecipeConfigImportRequest request) {
        return split(registries, request, MachineRecipeImportedSignature.parseJson(registries, request.signatureHintsJson()));
    }

    /** 客户端已持有签名对象时直接分包，避免先拼接并再次解析整份 JSON。 */
    public static List<MachineRecipeConfigImportRequest> split(
            HolderLookup.Provider registries, MachineRecipeConfigImportRequest request,
            List<MachineRecipeImportedSignature> signaturesToImport) {
        Map<ResourceLocation, List<MachineRecipeImportedSignature>> signaturesByType = new LinkedHashMap<>();
        for (ResourceLocation type : request.recipeTypeIds()) signaturesByType.put(type, new ArrayList<>());
        for (MachineRecipeImportedSignature signature : signaturesToImport) {
            List<MachineRecipeImportedSignature> signatures = signaturesByType.get(signature.recipeTypeId());
            if (signatures == null) throw new IllegalArgumentException("导入签名的类型不在请求中");
            signatures.add(signature);
        }
        List<MachineRecipeConfigImportRequest> batches = new ArrayList<>();
        for (var entry : signaturesByType.entrySet()) {
            List<String> encoded = new ArrayList<>();
            int length = 2;
            for (MachineRecipeImportedSignature signature : entry.getValue()) {
                String json = signature.toJsonObject(registries).toString();
                if (json.length() + 2 > MAX_JSON_CHARACTERS) {
                    throw new IllegalArgumentException("单个配方超过导入包大小限制: " + entry.getKey());
                }
                if (!encoded.isEmpty() && (length + json.length() + 1 > MAX_JSON_CHARACTERS
                        || encoded.size() >= SIGNATURES_PER_BATCH)) {
                    batches.add(batch(request, entry.getKey(), encoded));
                    encoded.clear();
                    length = 2;
                }
                encoded.add(json);
                length += json.length() + 1;
            }
            if (!encoded.isEmpty() || entry.getValue().isEmpty()) batches.add(batch(request, entry.getKey(), encoded));
        }
        return List.copyOf(batches);
    }

    private static MachineRecipeConfigImportRequest batch(
            MachineRecipeConfigImportRequest source, ResourceLocation type, List<String> signatures) {
        return new MachineRecipeConfigImportRequest(source.machineItemId(), source.machineBlockId(), List.of(type),
                source.defaultTicks(), source.ioMode(), source.keyTypes(), source.enabled(),
                "[" + String.join(",", signatures) + "]");
    }
}
