package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.integration.kubejs.DirectProcessingKubeEvent;
import git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptableObject;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DirectProcessingPackRulesTest {
    private static final ResourceLocation FURNACE = ResourceLocation.parse("minecraft:furnace");
    private static final ResourceLocation SMELTING = ResourceLocation.parse("minecraft:smelting");
    private static final ResourceLocation BLASTING = ResourceLocation.parse("minecraft:blasting");
    private static final MachineIdentity MACHINE = new MachineIdentity(FURNACE, FURNACE, null, "minecraft", null, null);
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    @TempDir Path directory;
    @AfterEach void restore() {
        DirectProcessingPackRulesRegistry.reset();
        DirectProcessingPackRulesRegistry.applyClientSnapshot(new DirectProcessingPackRulesBuilder().build().toJson());
    }

    @Test void importBansKeepExecutionAndNativeRecipesCannotBeSnapshotted() {
        var rules = new DirectProcessingPackRulesBuilder().disableImportType(SMELTING).build();
        assertFalse(rules.allowsImport(MACHINE, SMELTING));
        assertTrue(rules.allowsType(MACHINE, SMELTING));
        assertTrue(rules.allowsImport(MACHINE, BLASTING));
        assertFalse(rules.allowsImport(MACHINE, DirectProcessingPackRecipe.TYPE_ID));
        assertFalse(new DirectProcessingPackRulesBuilder().disableImportMachine(FURNACE).build()
                .allowsImport(MACHINE, BLASTING));
        assertFalse(new DirectProcessingPackRulesBuilder().disableMachine(FURNACE).build()
                .allowsType(MACHINE, DirectProcessingPackRecipe.TYPE_ID));
        assertFalse(new DirectProcessingPackRulesBuilder().importTypeWhitelist(List.of()).build()
                .allowsImport(MACHINE, BLASTING));
        assertFalse(new DirectProcessingPackRulesBuilder().jeiImport(false).build().allowsImport(MACHINE, BLASTING));
    }

    @Test void overrideRestrictsExplicitAndAutomaticCandidates() {
        var explicit = new RecipeTypeCandidate(null, RecipeType.SMELTING, MachineRecipeCandidateSource.EXPLICIT_ADAPTER, 10);
        var resolver = new RecipeTypeCandidateResolver(new MachineAdapterRegistry(List.of(identity -> List.of(explicit))));
        DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder()
                .replaceMachineTypes(FURNACE, List.of(BLASTING)).build());
        assertEquals(List.of(RecipeType.BLASTING), resolver.resolve(MACHINE).candidates().stream()
                .map(RecipeTypeCandidate::recipeType).toList());
        assertTrue(resolver.resolveFallbackHints(MACHINE).isEmpty());
        DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder().disableMachine(FURNACE).build());
        assertEquals(MachineSupportReasonCode.PACK_RULE_DISABLED, resolver.resolve(MACHINE).reasonCode());
        DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder().automaticDiscovery(false).build());
        assertTrue(new RecipeTypeCandidateResolver(MachineAdapterRegistry.empty()).resolve(MACHINE).candidates().isEmpty());
    }

    @Test void persistedImportsObeyExecutionBanWithoutDeletingData() {
        var store = new MachineRecipeUserConfigStore(directory);
        var payload = new MachineRecipeImportedSignature(SMELTING,
                List.of(new MachineRecipeImportedStack(AEItemKey.of(Items.RAW_IRON), 3)),
                List.of(new MachineRecipeImportedStack(AEItemKey.of(Items.IRON_INGOT), 2)));
        store.upsertMapping(new MachineRecipeConfigImportRequest(FURNACE, FURNACE, List.of(SMELTING), 20,
                "item_only", "item", true, MachineRecipeImportedSignature.toJson(REGISTRIES, List.of(payload))), REGISTRIES);
        DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder().disableImportType(SMELTING).build());
        assertEquals(1, store.loadImportedSignatures(REGISTRIES, MACHINE, List.of()).signatures().size());
        DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder().disableType(SMELTING).build());
        assertTrue(store.loadImportedSignatures(REGISTRIES, MACHINE, List.of()).signatures().isEmpty());
        DirectProcessingPackRulesRegistry.reset();
        assertEquals(1, store.loadImportedSignatures(REGISTRIES, MACHINE, List.of()).signatures().size());
    }

    @Test void javascriptArraysAndOverloadsConfigureActualEvent() {
        var event = new DirectProcessingKubeEvent();
        var cx = new ContextFactory().enter();
        var scope = cx.initStandardObjects();
        ScriptableObject.putProperty(scope, "event", cx.javaToJS(event, scope), cx);
        cx.evaluateString(scope, """
                event.marker('minecraft:paper', ['minecraft:smelting']);
                event.marker('minecraft:stick');
                event.disableImportType('minecraft:blasting');
                event.disableImportMachine('minecraft:furnace');
                event.replaceMachineTypes('minecraft:paper', ['minecraft:smelting']);
                event.importTypeWhitelist(['minecraft:smelting']);
                event.disableAutomaticDiscovery('minecraft:paper');
                """, "direct_processing_test.js", 1, null);
        var rules = event.finish();
        var paper = MachineIdentity.fromBindingStack(Items.PAPER.getDefaultInstance());
        assertTrue(rules.isCustomMarker(ResourceLocation.parse("minecraft:paper")));
        assertTrue(rules.isCustomMarker(ResourceLocation.parse("minecraft:stick")));
        assertEquals(List.of(SMELTING), rules.addedTypes(paper));
        assertTrue(rules.allowsImport(paper, SMELTING));
        assertFalse(rules.allowsImport(MACHINE, SMELTING));
        assertFalse(rules.allowsAutomaticDiscovery(paper));
        assertThrows(IllegalStateException.class, () -> event.jeiImport(false));
        assertTrue(new DirectProcessingKubeEvent().finish().allowsImport(MACHINE, BLASTING));
    }

    @Test void scriptErrorsRejectPartiallyBuiltPolicy() {
        var event = new DirectProcessingKubeEvent();
        event.disableType(SMELTING.toString());
        assertThrows(IllegalArgumentException.class, () -> event.marker("missing:unknown"));
        assertThrows(IllegalStateException.class, event::finish);
        var syntaxFailure = new DirectProcessingKubeEvent();
        syntaxFailure.markFailed();
        assertThrows(IllegalStateException.class, syntaxFailure::finish);
    }

    @Test void wireSnapshotReplacesClientRulesWithoutMutatingServerOrEpoch() {
        var marker = ResourceLocation.parse("minecraft:paper");
        var builder = new DirectProcessingPackRulesBuilder().marker(marker, List.of(SMELTING))
                .replaceMachineTypes(marker, List.of(BLASTING)).disableType(SMELTING)
                .disableImportMachine(FURNACE).importTypeWhitelist(List.of(BLASTING))
                .automaticDiscovery(false).jeiImport(false);
        var server = new DirectProcessingPackRulesBuilder().build();
        DirectProcessingPackRulesRegistry.replace(server);
        long epoch = DirectProcessingPackRulesRegistry.epoch();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            var sent = new DirectProcessingPackRulesPayload(17, builder.build().toJson());
            DirectProcessingPackRulesPayload.STREAM_CODEC.encode(buffer, sent);
            var decoded = DirectProcessingPackRulesPayload.STREAM_CODEC.decode(buffer);
            assertEquals(sent, decoded);
            assertEquals(0, buffer.readableBytes());
            DirectProcessingPackRulesRegistry.applyClientSnapshot(decoded.json());
            var client = DirectProcessingPackRulesRegistry.clientCurrent();
            assertTrue(client.isCustomMarker(marker));
            assertFalse(client.allowsImport(MACHINE, BLASTING));
            assertFalse(client.allowsType(MACHINE, SMELTING));
            assertEquals(Set.of(BLASTING), Set.copyOf(client.addedTypes(MachineIdentity.fromBindingStack(Items.PAPER.getDefaultInstance()))));
            assertSame(server, DirectProcessingPackRulesRegistry.current());
            assertEquals(epoch, DirectProcessingPackRulesRegistry.epoch());
            DirectProcessingPackRulesRegistry.applyClientSnapshot(server.toJson());
            assertFalse(DirectProcessingPackRulesRegistry.clientCurrent().isCustomMarker(marker));
        } finally { buffer.release(); }
    }
}
