package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record MachineRecipeConfigImportRequest(
        ResourceLocation machineItemId,
        ResourceLocation machineBlockId,
        List<ResourceLocation> recipeTypeIds,
        int defaultTicks,
        String ioMode,
        String keyTypes,
        boolean enabled,
        String signatureHintsJson
) {
    public static final int MAX_NETWORK_RECIPE_TYPE_IDS = 256;
    public static final int MAX_NETWORK_SIGNATURES = 8_192;
    private static final StreamCodec<RegistryFriendlyByteBuf, List<MachineRecipeImportedSignature>>
            SIGNATURE_HINTS_STREAM_CODEC =
            MachineRecipeImportedSignature.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_NETWORK_SIGNATURES));
    public static final StreamCodec<RegistryFriendlyByteBuf, MachineRecipeConfigImportRequest> STREAM_CODEC =
            StreamCodec.of(
                    (data, request) -> request.writeToNetwork(data),
                    MachineRecipeConfigImportRequest::readFromNetwork
            );

    public MachineRecipeConfigImportRequest {
        recipeTypeIds = recipeTypeIds == null ? List.of() : List.copyOf(recipeTypeIds);
        defaultTicks = Math.max(1, defaultTicks);
        ioMode = ioMode == null || ioMode.isBlank() ? "generic" : ioMode;
        keyTypes = keyTypes == null || keyTypes.isBlank() ? "any" : keyTypes;
        signatureHintsJson = signatureHintsJson == null ? "" : signatureHintsJson;
    }

    private void writeToNetwork(RegistryFriendlyByteBuf data) {
        writeOptionalResourceLocation(data, machineItemId);
        writeOptionalResourceLocation(data, machineBlockId);
        writeRecipeTypeIds(data, recipeTypeIds);
        data.writeVarInt(defaultTicks);
        ByteBufCodecs.STRING_UTF8.encode(data, ioMode);
        ByteBufCodecs.STRING_UTF8.encode(data, keyTypes);
        data.writeBoolean(enabled);
        List<MachineRecipeImportedSignature> signatureHints =
                MachineRecipeImportedSignature.parseJson(data.registryAccess(), signatureHintsJson);
        ensureWithinBound("signatureHints", signatureHints.size(), MAX_NETWORK_SIGNATURES);
        SIGNATURE_HINTS_STREAM_CODEC.encode(data, signatureHints);
    }

    private static MachineRecipeConfigImportRequest readFromNetwork(RegistryFriendlyByteBuf data) {
        ResourceLocation machineItemId = readOptionalResourceLocation(data);
        ResourceLocation machineBlockId = readOptionalResourceLocation(data);
        List<ResourceLocation> recipeTypeIds = readRecipeTypeIds(data);
        int defaultTicks = data.readVarInt();
        String ioMode = ByteBufCodecs.STRING_UTF8.decode(data);
        String keyTypes = ByteBufCodecs.STRING_UTF8.decode(data);
        boolean enabled = data.readBoolean();
        List<MachineRecipeImportedSignature> signatureHints = SIGNATURE_HINTS_STREAM_CODEC.decode(data);
        return new MachineRecipeConfigImportRequest(
                machineItemId,
                machineBlockId,
                recipeTypeIds,
                defaultTicks,
                ioMode,
                keyTypes,
                enabled,
                MachineRecipeImportedSignature.toJson(data.registryAccess(), signatureHints)
        );
    }

    private static void writeOptionalResourceLocation(RegistryFriendlyByteBuf data, ResourceLocation value) {
        data.writeBoolean(value != null);
        if (value != null) {
            ResourceLocation.STREAM_CODEC.encode(data, value);
        }
    }

    private static ResourceLocation readOptionalResourceLocation(RegistryFriendlyByteBuf data) {
        return data.readBoolean() ? ResourceLocation.STREAM_CODEC.decode(data) : null;
    }

    private static void writeRecipeTypeIds(RegistryFriendlyByteBuf data, List<ResourceLocation> recipeTypeIds) {
        List<ResourceLocation> safeRecipeTypeIds = recipeTypeIds == null ? List.of() : recipeTypeIds;
        ensureWithinBound("recipeTypeIds", safeRecipeTypeIds.size(), MAX_NETWORK_RECIPE_TYPE_IDS);
        data.writeVarInt(safeRecipeTypeIds.size());
        for (ResourceLocation recipeTypeId : safeRecipeTypeIds) {
            ResourceLocation.STREAM_CODEC.encode(data, recipeTypeId);
        }
    }

    private static List<ResourceLocation> readRecipeTypeIds(RegistryFriendlyByteBuf data) {
        int size = data.readVarInt();
        ensureWithinBound("recipeTypeIds", size, MAX_NETWORK_RECIPE_TYPE_IDS);
        if (size <= 0) {
            return List.of();
        }
        List<ResourceLocation> recipeTypeIds = new java.util.ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            recipeTypeIds.add(ResourceLocation.STREAM_CODEC.decode(data));
        }
        return List.copyOf(recipeTypeIds);
    }

    private static void ensureWithinBound(String label, int size, int limit) {
        if (size > limit) {
            throw new IllegalArgumentException(label + " exceeds network limit " + limit + " (" + size + ")");
        }
    }
}
