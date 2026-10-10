package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 直接执行磁盘增量导入、类型去重、分包和多机器索引合并。 */
class MachineRecipeStorageRefactorTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final ResourceLocation MACHINE = ResourceLocation.parse("test:machine");
    private static final ResourceLocation TYPE_A = ResourceLocation.parse("minecraft:smelting");
    private static final ResourceLocation TYPE_B = ResourceLocation.parse("minecraft:blasting");
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey STONE = AEItemKey.of(Items.STONE);
    @TempDir Path directory;

    @Test
    void incrementalImportRetainsTypesAndReusesUnchangedShards() throws Exception {
        MachineRecipeUserConfigStore store = new MachineRecipeUserConfigStore(directory);
        assertTrue(store.upsertMapping(request(List.of(TYPE_A), List.of(payload(TYPE_A, 1))), REGISTRIES));
        Path config = directory.resolve("direct_processing_machines.json");
        var firstRoot = JsonParser.parseString(Files.readString(config)).getAsJsonArray();
        var first = firstRoot.asList().stream().filter(element -> element.getAsJsonObject().has("signature_shards"))
                .findFirst().orElseThrow().getAsJsonObject();
        String oldShard = first.getAsJsonArray("signature_shards").get(0).getAsString();
        byte[] oldBytes = Files.readAllBytes(directory.resolve(oldShard));
        assertTrue(store.upsertMapping(request(List.of(TYPE_B), List.of(payload(TYPE_B, 2))), REGISTRIES));
        MachineIdentity identity = new MachineIdentity(MACHINE, null, null, "test", null, null);
        var loaded = store.loadImportedSignatures(REGISTRIES, identity, List.of());
        assertEquals(Set.of(TYPE_A, TYPE_B), Set.copyOf(loaded.recipeTypeIds()));
        assertEquals(2, loaded.signatures().size());
        assertArrayEquals(oldBytes, Files.readAllBytes(directory.resolve(oldShard)));
        byte[] before = Files.readAllBytes(config);
        assertFalse(store.upsertMapping(request(List.of(TYPE_B), List.of(payload(TYPE_B, 2))), REGISTRIES));
        assertArrayEquals(before, Files.readAllBytes(config));
    }

    @Test
    void appendingSameTypeRetiresOldTailOnlyAfterNewMappingIsReadable() throws Exception {
        var store = new MachineRecipeUserConfigStore(directory);
        store.upsertMapping(request(List.of(TYPE_A), List.of(payload(TYPE_A, 1))), REGISTRIES);
        var root = JsonParser.parseString(Files.readString(directory.resolve("direct_processing_machines.json"))).getAsJsonArray();
        String oldPath = root.asList().stream().map(element -> element.getAsJsonObject())
                .filter(object -> object.has("signature_shards")).findFirst().orElseThrow()
                .getAsJsonArray("signature_shards").get(0).getAsString();
        store.upsertMapping(request(List.of(TYPE_A), List.of(payload(TYPE_A, 2))), REGISTRIES);
        var identity = new MachineIdentity(MACHINE, null, null, "test", null, null);
        assertEquals(2, store.loadImportedSignatures(REGISTRIES, identity, List.of()).signatures().size());
        assertFalse(Files.exists(directory.resolve(oldPath)));
    }

    @Test
    void missingOldShardRejectsImportAndPreservesConfig() throws Exception {
        MachineRecipeUserConfigStore store = new MachineRecipeUserConfigStore(directory);
        store.upsertMapping(request(List.of(TYPE_A), List.of(payload(TYPE_A, 1))), REGISTRIES);
        Path config = directory.resolve("direct_processing_machines.json");
        byte[] before = Files.readAllBytes(config);
        var root = JsonParser.parseString(Files.readString(config)).getAsJsonArray();
        for (var entry : root) {
            var object = entry.getAsJsonObject();
            if (object.has("signature_shards")) {
                Files.delete(directory.resolve(object.getAsJsonArray("signature_shards").get(0).getAsString()));
            }
        }
        assertThrows(IllegalStateException.class,
                () -> store.upsertMapping(request(List.of(TYPE_B), List.of(payload(TYPE_B, 2))), REGISTRIES));
        assertArrayEquals(before, Files.readAllBytes(config));
    }

    @Test
    void typeAndExactUnorderedAmountsDefineDuplicateIdentity() {
        var first = new MachineRecipeImportedSignature(TYPE_A,
                List.of(new MachineRecipeImportedStack(IRON, 1), new MachineRecipeImportedStack(STONE, 2)),
                List.of(new MachineRecipeImportedStack(GOLD, 1)));
        var reordered = new MachineRecipeImportedSignature(TYPE_A,
                List.of(new MachineRecipeImportedStack(STONE, 2), new MachineRecipeImportedStack(IRON, 1)), first.outputs());
        var otherType = new MachineRecipeImportedSignature(TYPE_B, first.inputs(), first.outputs());
        Map<ResourceLocation, Map<RecipeSignatureKey, MachineRecipeImportedSignature>> grouped = new LinkedHashMap<>();
        MachineRecipeUserConfigStore.mergeImportedSignatures(grouped, List.of(first, reordered, otherType));
        assertEquals(1, grouped.get(TYPE_A).size());
        assertEquals(1, grouped.get(TYPE_B).size());
    }

    @Test
    void largeImportSplitsWithoutLosingRecipesAndRoundTripsEachBatch() {
        List<MachineRecipeImportedSignature> signatures = new ArrayList<>();
        for (int amount = 1; amount <= 1500; amount++) signatures.add(payload(TYPE_A, amount));
        var batches = MachineRecipeImportBatches.split(REGISTRIES, request(List.of(TYPE_A, TYPE_B), signatures));
        assertTrue(batches.size() > 2);
        int restored = 0;
        for (var batch : batches) {
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), REGISTRIES);
            try {
                MachineRecipeConfigImportRequest.STREAM_CODEC.encode(buffer, batch);
                var decoded = MachineRecipeConfigImportRequest.STREAM_CODEC.decode(buffer);
                restored += MachineRecipeImportedSignature.parseJson(REGISTRIES, decoded.signatureHintsJson()).size();
                assertEquals(batch, decoded);
            } finally { buffer.release(); }
        }
        assertEquals(1500, restored);
        assertTrue(batches.stream().anyMatch(batch -> batch.recipeTypeIds().equals(List.of(TYPE_B))));
    }

    @Test
    void multipleMachinesMergeWholeRecipesAndPreserveTheirSources() {
        RecipeSignature a = payload(TYPE_A, 1).toRecipeSignature();
        RecipeSignature b = new RecipeSignature(null, GOLD, 2, STONE, 1);
        var identity = new MachineIdentity(MACHINE, null, null, "test", null, null);
        var explicit = new MachineRecipeIndex(identity, Set.of(), List.of(TYPE_A), Set.of(a),
                MachineSupportStatus.SUPPORTED_EXPLICIT, MachineSupportReasonCode.NONE, 1);
        var imported = new MachineRecipeIndex(identity, Set.of(), List.of(TYPE_B), Set.of(b),
                MachineSupportStatus.SUPPORTED_CONFIG, MachineSupportReasonCode.NONE, 1);
        var merged = MachineRecipeIndex.merge(List.of(explicit, imported), 2);
        assertEquals(2, merged.recipeSignatureCount());
        assertEquals(a, merged.findSignature(IRON, 1, GOLD, 1));
        assertEquals(b, merged.findSignature(GOLD, 2, STONE, 1));
        assertNull(merged.findSignature(IRON, 1, STONE, 1));
        assertEquals(MachineSupportStatus.SUPPORTED_EXPLICIT, merged.statusFor(a));
        assertEquals(MachineSupportStatus.SUPPORTED_CONFIG, merged.withVersion(3).statusFor(b));
        assertNull(MachineRecipeIndex.merge(List.of(explicit), 4).findSignature(GOLD, 2, STONE, 1));
        assertTrue(MachineRecipeIndex.merge(List.of(), 5).isEmpty());
    }

    private static MachineRecipeImportedSignature payload(ResourceLocation type, long amount) {
        return MachineRecipeImportedSignature.fromRecipeSignature(type, new RecipeSignature(null, IRON, amount, GOLD, 1));
    }

    private static MachineRecipeConfigImportRequest request(List<ResourceLocation> types,
                                                            List<MachineRecipeImportedSignature> signatures) {
        return new MachineRecipeConfigImportRequest(MACHINE, null, types, 20, "generic", "any", true,
                MachineRecipeImportedSignature.toJson(REGISTRIES, signatures));
    }
}
