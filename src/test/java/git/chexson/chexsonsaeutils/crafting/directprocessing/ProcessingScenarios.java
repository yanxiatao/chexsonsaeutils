package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.latvian.mods.kubejs.recipe.RecipeKey;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaRegistry;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaStorage;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptableObject;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.integration.kubejs.ChexsonsaeutilsKubeJSPlugin;
import git.chexson.chexsonsaeutils.integration.kubejs.DirectProcessingKubeEvent;
import git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 直连加工的行为场景；由类别测试统一调用。 */
public final class ProcessingScenarios {
    private ProcessingScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.directprocessing",
                () -> DirectProcessingPackRecipeCases.verify(temporaryRoot),
                () -> DirectProcessingPackRulesCases.verify(temporaryRoot),
                () -> DirectProcessingRegistryContractCases.verify(temporaryRoot),
                () -> MachineRecipeStorageRefactorCases.verify(temporaryRoot),
                () -> ProcessingExecutionBudgetCases.verify(temporaryRoot),
                () -> ProcessingTaskBatchCases.verify(temporaryRoot),
                () -> ProcessingTaskInputAccountingCases.verify(temporaryRoot),
                () -> RecipeAutomaticDiscoveryRefactorCases.verify(temporaryRoot)
        );
    }

    /** DirectProcessingPackRecipeCases 的独立场景夹具，按类别统一执行。 */
    public static final class DirectProcessingPackRecipeCases {
        private static final ResourceLocation MARKER = ResourceLocation.parse("minecraft:paper");
        private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        private static DirectProcessingPackRecipe mixedRecipe() {
            return new DirectProcessingPackRecipe(List.of(MARKER),
                    List.of(new SizedIngredient(Ingredient.of(Items.RAW_IRON, Items.RAW_GOLD), 3)),
                    List.of(SizedFluidIngredient.of(Fluids.WATER, 500)),
                    List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.STONE, 1)),
                    List.of(new FluidStack(Fluids.LAVA, 250)));
        }

        void alternativesPreserveExactAmountsAndAllByproducts() {
            var recipe = mixedRecipe();
            var signatures = recipe.signatures();
            assertEquals(2, signatures.size());
            assertEquals(Set.of(AEItemKey.of(Items.RAW_IRON), AEItemKey.of(Items.RAW_GOLD)),
                    signatures.stream().flatMap(signature -> signature.inputs().stream())
                            .filter(input -> input.input() instanceof AEItemKey).map(RecipeSignatureInput::input).collect(Collectors.toSet()));
            for (var signature : signatures) {
                var inputs = DirectProcessingStackSupport.signatureInputAmounts(signature.inputs());
                assertEquals(500L, inputs.get(AEFluidKey.of(Fluids.WATER)));
                assertTrue(signature.inputs().stream().anyMatch(input -> input.input() instanceof AEItemKey && input.amount() == 3));
                var outputs = DirectProcessingStackSupport.stackAmounts(signature.outputs());
                assertEquals(2L, outputs.get(AEItemKey.of(Items.IRON_INGOT)));
                assertEquals(1L, outputs.get(AEItemKey.of(Items.STONE)));
                assertEquals(250L, outputs.get(AEFluidKey.of(Fluids.LAVA)));
            }
            assertTrue(recipe.supports(MachineIdentity.fromBindingStack(Items.PAPER.getDefaultInstance())));
            assertFalse(recipe.supports(MachineIdentity.fromBindingStack(Items.FURNACE.getDefaultInstance())));
            recipe.itemOutputs().getFirst().setCount(99);
            assertEquals(2, recipe.itemOutputs().getFirst().getCount());
        }

        void datapackAndNetworkRoundtripsKeepMachineAndIo() {
            var recipe = mixedRecipe();
            var ops = REGISTRIES.createSerializationContext(JsonOps.INSTANCE);
            var json = DirectProcessingPackRecipe.CODEC.codec().encodeStart(ops, recipe).getOrThrow();
            var fromJson = DirectProcessingPackRecipe.CODEC.codec().parse(ops, json).getOrThrow();
            assertEquals(recipe.signatures(), fromJson.signatures());
            assertEquals(recipe.machines(), fromJson.machines());
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
            try {
                DirectProcessingPackRecipe.STREAM_CODEC.encode(buffer, recipe);
                var decoded = DirectProcessingPackRecipe.STREAM_CODEC.decode(buffer);
                assertEquals(recipe.signatures(), decoded.signatures());
                assertEquals(recipe.machines(), decoded.machines());
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }

        void itemOnlyMatchingRequiresFullInputAmount() {
            var recipe = new DirectProcessingPackRecipe(List.of(MARKER),
                    List.of(SizedIngredient.of(Items.RAW_IRON, 3)), List.of(),
                    List.of(new ItemStack(Items.IRON_INGOT, 2)), List.of());
            assertTrue(recipe.matches(new SingleRecipeInput(new ItemStack(Items.RAW_IRON, 3)), null));
            assertFalse(recipe.matches(new SingleRecipeInput(new ItemStack(Items.RAW_IRON, 2)), null));
            assertFalse(recipe.matches(new SingleRecipeInput(new ItemStack(Items.RAW_GOLD, 3)), null));
            assertFalse(mixedRecipe().matches(new SingleRecipeInput(new ItemStack(Items.RAW_IRON, 3)), null));
        }

        void fluidOnlyRecipeNeedsNoDummyItem() {
            var recipe = new DirectProcessingPackRecipe(List.of(MARKER), List.of(),
                    List.of(SizedFluidIngredient.of(Fluids.WATER, 1000)), List.of(),
                    List.of(new FluidStack(Fluids.LAVA, 500)));
            assertEquals(1, recipe.signatures().size());
            assertTrue(recipe.getResultItem(REGISTRIES).isEmpty());
            assertEquals(500L, recipe.signatures().iterator().next().outputs().getFirst().amount());
        }

        void emptyInputsAndOversizedAlternativesFailAsWholeRecipe() {
            assertThrows(IllegalArgumentException.class, () -> new DirectProcessingPackRecipe(List.of(MARKER),
                    List.of(), List.of(), List.of(Items.STONE.getDefaultInstance()), List.of()));
            var empty = new DirectProcessingPackRecipe(List.of(MARKER),
                    List.of(new SizedIngredient(Ingredient.EMPTY, 1)), List.of(),
                    List.of(Items.STONE.getDefaultInstance()), List.of());
            assertThrows(IllegalArgumentException.class, empty::signatures);
            var many = new DirectProcessingPackRecipe(List.of(MARKER),
                    Collections.nCopies(9, new SizedIngredient(Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT), 1)),
                    List.of(), List.of(Items.STONE.getDefaultInstance()), List.of());
            assertThrows(IllegalArgumentException.class, many::signatures);
        }

        void reloadedIndexHonorsMarkersRemovalAndExecutionRules() {
            var builder = new MachineRecipeIndexBuilder(MachineAdapterRegistry.empty(), new GenericRecipeShapeReader(DirectProcessingStackConverterRegistry.builtins(),
                    new DirectProcessingExternalRecipeShapeRegistry(List.of())));
            var marker = MachineIdentity.fromBindingStack(Items.PAPER.getDefaultInstance());
            var other = MachineIdentity.fromBindingStack(Items.FURNACE.getDefaultInstance());
            var valid = new RecipeHolder<>(ResourceLocation.parse("test:valid"), mixedRecipe());
            var invalid = new RecipeHolder<>(ResourceLocation.parse("test:empty_tag"),
                    new DirectProcessingPackRecipe(List.of(MARKER), List.of(new SizedIngredient(Ingredient.EMPTY, 1)),
                            List.of(), List.of(Items.STONE.getDefaultInstance()), List.of()));
            assertEquals(2, builder.buildPackRecipeIndex(marker, List.of(invalid, valid)).recipeSignatureCount());
            assertTrue(builder.buildPackRecipeIndex(other, List.of(valid)).isEmpty());
            assertTrue(builder.buildPackRecipeIndex(marker, List.of()).isEmpty());
            try {
                DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder()
                        .disableType(DirectProcessingPackRecipe.TYPE_ID).build());
                assertTrue(builder.buildPackRecipeIndex(marker, List.of(valid)).isEmpty());
                DirectProcessingPackRulesRegistry.replace(new DirectProcessingPackRulesBuilder().disableMachine(MARKER).build());
                assertTrue(builder.buildPackRecipeIndex(marker, List.of(valid)).isEmpty());
            } finally { DirectProcessingPackRulesRegistry.reset(); }
        }

        void registeredKubeSchemaProducesNativeRecipeJsonWithFluentIoNames() {
            // 此处只注册 schema，不启动脚本管理器或加载资源，因此 storage 不需要服务器上下文。
            var storage = new RecipeSchemaStorage(null);
            new ChexsonsaeutilsKubeJSPlugin().registerRecipeSchemas(new RecipeSchemaRegistry(storage));
            var schema = storage.namespace("chexsonsaeutils").getRegisteredOrThrow("direct_processing").schema;
            assertEquals(List.of("item_outputs", "item_inputs", "machines"),
                    schema.constructors().get(3).keys.stream().map(key -> key.name).toList());
            assertEquals(List.of("machines"), schema.constructors().get(1).keys.stream().map(key -> key.name).toList());
            var recipe = mixedRecipe();
            var json = new JsonObject();
            for (var key : schema.keys) {
                Object value = switch (key.name) {
                    case "machines" -> recipe.machines().stream().map(ResourceLocation::toString).toList();
                    case "item_inputs" -> recipe.itemInputs();
                    case "item_outputs" -> recipe.itemOutputs();
                    case "fluid_inputs" -> recipe.fluidInputs();
                    case "fluid_outputs" -> recipe.fluidOutputs();
                    default -> throw new AssertionError(key.name);
                };
                encodeSchemaValue(json, key, value);
            }
            assertEquals(recipe.signatures(), DirectProcessingPackRecipe.CODEC.codec()
                    .parse(REGISTRIES.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow().signatures());
            assertTrue(schema.keys.stream().filter(key -> key.name.equals("fluid_inputs"))
                    .findFirst().orElseThrow().getValidFunctionNames().contains("fluidInputs"));
            assertTrue(schema.keys.stream().filter(key -> key.name.equals("fluid_outputs"))
                    .findFirst().orElseThrow().getValidFunctionNames().contains("fluidOutputs"));
        }

        @SuppressWarnings("unchecked")
        private static <T> void encodeSchemaValue(JsonObject json, RecipeKey<T> key, Object value) {
            json.add(key.name, key.codec.encodeStart(REGISTRIES.createSerializationContext(JsonOps.INSTANCE),
                    (T) value).getOrThrow());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases.alternativesPreserveExactAmountsAndAllByproducts", () -> new DirectProcessingPackRecipeCases().alternativesPreserveExactAmountsAndAllByproducts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases.datapackAndNetworkRoundtripsKeepMachineAndIo", () -> new DirectProcessingPackRecipeCases().datapackAndNetworkRoundtripsKeepMachineAndIo()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases.itemOnlyMatchingRequiresFullInputAmount", () -> new DirectProcessingPackRecipeCases().itemOnlyMatchingRequiresFullInputAmount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases.fluidOnlyRecipeNeedsNoDummyItem", () -> new DirectProcessingPackRecipeCases().fluidOnlyRecipeNeedsNoDummyItem()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases.emptyInputsAndOversizedAlternativesFailAsWholeRecipe", () -> new DirectProcessingPackRecipeCases().emptyInputsAndOversizedAlternativesFailAsWholeRecipe()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases.reloadedIndexHonorsMarkersRemovalAndExecutionRules", () -> new DirectProcessingPackRecipeCases().reloadedIndexHonorsMarkersRemovalAndExecutionRules()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRecipeCases.registeredKubeSchemaProducesNativeRecipeJsonWithFluentIoNames", () -> new DirectProcessingPackRecipeCases().registeredKubeSchemaProducesNativeRecipeJsonWithFluentIoNames())
            );
        }
    }

    /** DirectProcessingPackRulesCases 的独立场景夹具，按类别统一执行。 */
    public static final class DirectProcessingPackRulesCases {
        private static final ResourceLocation FURNACE = ResourceLocation.parse("minecraft:furnace");
        private static final ResourceLocation SMELTING = ResourceLocation.parse("minecraft:smelting");
        private static final ResourceLocation BLASTING = ResourceLocation.parse("minecraft:blasting");
        private static final MachineIdentity MACHINE = new MachineIdentity(FURNACE, FURNACE, null, "minecraft", null, null);
        private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Path directory;
        void restore() {
            DirectProcessingPackRulesRegistry.reset();
            DirectProcessingPackRulesRegistry.applyClientSnapshot(new DirectProcessingPackRulesBuilder().build().toJson());
        }

        void importBansKeepExecutionAndNativeRecipesCannotBeSnapshotted() {
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

        void overrideRestrictsExplicitAndAutomaticCandidates() {
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

        void persistedImportsObeyExecutionBanWithoutDeletingData() {
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

        void javascriptArraysAndOverloadsConfigureActualEvent() {
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

        void scriptErrorsRejectPartiallyBuiltPolicy() {
            var event = new DirectProcessingKubeEvent();
            event.disableType(SMELTING.toString());
            assertThrows(IllegalArgumentException.class, () -> event.marker("missing:unknown"));
            assertThrows(IllegalStateException.class, event::finish);
            var syntaxFailure = new DirectProcessingKubeEvent();
            syntaxFailure.markFailed();
            assertThrows(IllegalStateException.class, syntaxFailure::finish);
        }

        void wireSnapshotReplacesClientRulesWithoutMutatingServerOrEpoch() {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRulesCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRulesCases.importBansKeepExecutionAndNativeRecipesCannotBeSnapshotted", () -> {
                            var scenario = new DirectProcessingPackRulesCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "DirectProcessingPackRulesCases-");
                            try {
                                scenario.importBansKeepExecutionAndNativeRecipesCannotBeSnapshotted();
                            } finally {
                                scenario.restore();
                            }
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRulesCases.overrideRestrictsExplicitAndAutomaticCandidates", () -> {
                            var scenario = new DirectProcessingPackRulesCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "DirectProcessingPackRulesCases-");
                            try {
                                scenario.overrideRestrictsExplicitAndAutomaticCandidates();
                            } finally {
                                scenario.restore();
                            }
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRulesCases.persistedImportsObeyExecutionBanWithoutDeletingData", () -> {
                            var scenario = new DirectProcessingPackRulesCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "DirectProcessingPackRulesCases-");
                            try {
                                scenario.persistedImportsObeyExecutionBanWithoutDeletingData();
                            } finally {
                                scenario.restore();
                            }
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRulesCases.javascriptArraysAndOverloadsConfigureActualEvent", () -> {
                            var scenario = new DirectProcessingPackRulesCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "DirectProcessingPackRulesCases-");
                            try {
                                scenario.javascriptArraysAndOverloadsConfigureActualEvent();
                            } finally {
                                scenario.restore();
                            }
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRulesCases.scriptErrorsRejectPartiallyBuiltPolicy", () -> {
                            var scenario = new DirectProcessingPackRulesCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "DirectProcessingPackRulesCases-");
                            try {
                                scenario.scriptErrorsRejectPartiallyBuiltPolicy();
                            } finally {
                                scenario.restore();
                            }
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingPackRulesCases.wireSnapshotReplacesClientRulesWithoutMutatingServerOrEpoch", () -> {
                            var scenario = new DirectProcessingPackRulesCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "DirectProcessingPackRulesCases-");
                            try {
                                scenario.wireSnapshotReplacesClientRulesWithoutMutatingServerOrEpoch();
                            } finally {
                                scenario.restore();
                            }
                        })
            );
        }
    }

    /** DirectProcessingRegistryContractCases 的独立场景夹具，按类别统一执行。 */
    public static final class DirectProcessingRegistryContractCases {
        private static final RecipeTypeCandidate CANDIDATE =
                new RecipeTypeCandidate(null, null, MachineRecipeCandidateSource.EXPLICIT_ADAPTER, 20);
        private static final MachineIdentity MACHINE = new MachineIdentity(
                ResourceLocation.fromNamespaceAndPath("test", "machine"), null, null, "test", null, null);
        private static final Recipe<?> RECIPE = new UnreadRecipe();

        void machineAdaptersKeepFirstNonemptyResultAndSnapshotTheirRoster() {
            List<String> calls = new ArrayList<>();
            List<MachineAdapterRegistry.MachineRecipeAdapter> roster = new ArrayList<>(List.of(
                    machine -> {
                        assertSame(MACHINE, machine);
                        calls.add("empty");
                        return List.of();
                    },
                    machine -> {
                        calls.add("matched");
                        return List.of(CANDIDATE);
                    },
                    machine -> {
                        fail("首命中后不应再调用后续机器适配器");
                        return List.of();
                    }
            ));
            MachineAdapterRegistry registry = new MachineAdapterRegistry(roster);
            roster.clear();

            assertEquals(List.of(CANDIDATE), registry.resolveCandidates(MACHINE));
            assertEquals(List.of("empty", "matched"), calls);
        }

        void handledUnreadableShapeStopsAllLaterSupports() {
            List<String> calls = new ArrayList<>();
            var unreadable = DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unreadable();
            var registry = new DirectProcessingExternalRecipeShapeRegistry(List.of(
                    (candidate, recipe) -> {
                        assertSame(CANDIDATE, candidate);
                        assertSame(RECIPE, recipe);
                        calls.add("unhandled");
                        return DirectProcessingExternalRecipeShapeRegistry.ShapeResult.unhandled();
                    },
                    (candidate, recipe) -> {
                        calls.add("unreadable");
                        return unreadable;
                    },
                    (candidate, recipe) -> {
                        fail("已处理的不可读配方不能继续交给其他支持器");
                        return null;
                    }
            ));

            assertSame(unreadable, registry.readShape(CANDIDATE, RECIPE));
            assertEquals(List.of("unhandled", "unreadable"), calls);
        }

        void supportedShapeKeepsTheFirstResult() {
            var supported = DirectProcessingExternalRecipeShapeRegistry.ShapeResult.supported(List.of(), List.of());
            var registry = new DirectProcessingExternalRecipeShapeRegistry(List.of(
                    (candidate, recipe) -> null,
                    (candidate, recipe) -> supported,
                    (candidate, recipe) -> {
                        fail("首个受支持结果必须终止后续形状读取");
                        return null;
                    }
            ));

            assertSame(supported, registry.readShape(CANDIDATE, RECIPE));
        }

        void extensionConvertersKeepFirstValidResultAndOptionalUnwrapping() {
            Object value = new Object();
            GenericStack expected = new GenericStack(AEItemKey.of(Items.IRON_INGOT), 7);
            List<String> calls = new ArrayList<>();
            var registry = new DirectProcessingStackConverterRegistry(List.of(
                    input -> {
                        assertSame(value, input);
                        calls.add("unhandled");
                        return null;
                    },
                    input -> {
                        calls.add("matched");
                        return expected;
                    },
                    input -> {
                        fail("首个有效转换应终止后续通道");
                        return null;
                    }
            ));

            assertEquals(expected, registry.convert(Optional.of(value)));
            assertEquals(List.of("unhandled", "matched"), calls);
        }

        void builtinItemAndFluidChannelsDoNotVisitExtensions() {
            var registry = new DirectProcessingStackConverterRegistry(List.of(value -> {
                fail("内建物品和流体不能进入扩展转换器");
                return null;
            }));
            ItemStack item = new ItemStack(Items.IRON_INGOT, 3);
            FluidStack fluid = new FluidStack(Fluids.WATER, 250);

            assertEquals(new GenericStack(AEItemKey.of(item), 3), registry.convert(item));
            assertEquals(new GenericStack(AEFluidKey.of(fluid), 250), registry.convert(fluid));
            assertEquals(new MachineRecipeImportedStack(AEItemKey.of(item), 3),
                    MachineRecipeImportedStack.fromItemStack(item));
            assertEquals(new MachineRecipeImportedStack(AEFluidKey.of(fluid), 250),
                    MachineRecipeImportedStack.fromFluidStack(fluid));
        }

        void missingInjectedCollaboratorsFailDuringConstruction() {
            assertThrows(NullPointerException.class, () -> new MachineAdapterRegistry(null));
            assertThrows(NullPointerException.class, () -> new DirectProcessingStackConverterRegistry(null));
            assertThrows(NullPointerException.class, () -> new DirectProcessingExternalRecipeShapeRegistry(null));
            assertThrows(NullPointerException.class, () -> new GenericRecipeShapeReader(null,
                    new DirectProcessingExternalRecipeShapeRegistry(List.of())));
            assertThrows(NullPointerException.class, () -> new MachineRecipeIndexBuilder(MachineAdapterRegistry.empty(), null));
        }

        /** 注册器只传递配方引用，任何配方数据读取都是本测试的意外调用。 */
        private static final class UnreadRecipe implements Recipe<RecipeInput> {
            @Override
            public boolean matches(RecipeInput input, Level level) {
                throw new AssertionError("注册器不应读取匹配条件");
            }

            @Override
            public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
                throw new AssertionError("注册器不应执行合成");
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                throw new AssertionError("注册器不应读取合成尺寸");
            }

            @Override
            public ItemStack getResultItem(HolderLookup.Provider registries) {
                throw new AssertionError("注册器不应读取产物");
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                throw new AssertionError("注册器不应读取序列化器");
            }

            @Override
            public RecipeType<?> getType() {
                throw new AssertionError("注册器不应读取配方类型");
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("DirectProcessingRegistryContractCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingRegistryContractCases.machineAdaptersKeepFirstNonemptyResultAndSnapshotTheirRoster", () -> new DirectProcessingRegistryContractCases().machineAdaptersKeepFirstNonemptyResultAndSnapshotTheirRoster()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingRegistryContractCases.handledUnreadableShapeStopsAllLaterSupports", () -> new DirectProcessingRegistryContractCases().handledUnreadableShapeStopsAllLaterSupports()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingRegistryContractCases.supportedShapeKeepsTheFirstResult", () -> new DirectProcessingRegistryContractCases().supportedShapeKeepsTheFirstResult()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingRegistryContractCases.extensionConvertersKeepFirstValidResultAndOptionalUnwrapping", () -> new DirectProcessingRegistryContractCases().extensionConvertersKeepFirstValidResultAndOptionalUnwrapping()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingRegistryContractCases.builtinItemAndFluidChannelsDoNotVisitExtensions", () -> new DirectProcessingRegistryContractCases().builtinItemAndFluidChannelsDoNotVisitExtensions()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DirectProcessingRegistryContractCases.missingInjectedCollaboratorsFailDuringConstruction", () -> new DirectProcessingRegistryContractCases().missingInjectedCollaboratorsFailDuringConstruction())
            );
        }
    }

    /** MachineRecipeStorageRefactorCases 的独立场景夹具，按类别统一执行。 */
    public static final class MachineRecipeStorageRefactorCases {
        private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        private static final ResourceLocation MACHINE = ResourceLocation.parse("test:machine");
        private static final ResourceLocation TYPE_A = ResourceLocation.parse("minecraft:smelting");
        private static final ResourceLocation TYPE_B = ResourceLocation.parse("minecraft:blasting");
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey STONE = AEItemKey.of(Items.STONE);
        Path directory;

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MachineRecipeStorageRefactorCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MachineRecipeStorageRefactorCases.incrementalImportRetainsTypesAndReusesUnchangedShards", () -> {
                            var scenario = new MachineRecipeStorageRefactorCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "MachineRecipeStorageRefactorCases-");
                            scenario.incrementalImportRetainsTypesAndReusesUnchangedShards();
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("MachineRecipeStorageRefactorCases.appendingSameTypeRetiresOldTailOnlyAfterNewMappingIsReadable", () -> {
                            var scenario = new MachineRecipeStorageRefactorCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "MachineRecipeStorageRefactorCases-");
                            scenario.appendingSameTypeRetiresOldTailOnlyAfterNewMappingIsReadable();
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("MachineRecipeStorageRefactorCases.missingOldShardRejectsImportAndPreservesConfig", () -> {
                            var scenario = new MachineRecipeStorageRefactorCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "MachineRecipeStorageRefactorCases-");
                            scenario.missingOldShardRejectsImportAndPreservesConfig();
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("MachineRecipeStorageRefactorCases.typeAndExactUnorderedAmountsDefineDuplicateIdentity", () -> {
                            var scenario = new MachineRecipeStorageRefactorCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "MachineRecipeStorageRefactorCases-");
                            scenario.typeAndExactUnorderedAmountsDefineDuplicateIdentity();
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("MachineRecipeStorageRefactorCases.largeImportSplitsWithoutLosingRecipesAndRoundTripsEachBatch", () -> {
                            var scenario = new MachineRecipeStorageRefactorCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "MachineRecipeStorageRefactorCases-");
                            scenario.largeImportSplitsWithoutLosingRecipesAndRoundTripsEachBatch();
                        }),
                () -> org.junit.jupiter.api.Assertions.assertAll("MachineRecipeStorageRefactorCases.multipleMachinesMergeWholeRecipesAndPreserveTheirSources", () -> {
                            var scenario = new MachineRecipeStorageRefactorCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "MachineRecipeStorageRefactorCases-");
                            scenario.multipleMachinesMergeWholeRecipesAndPreserveTheirSources();
                        })
            );
        }
    }

    /** ProcessingExecutionBudgetCases 的独立场景夹具，按类别统一执行。 */
    public static final class ProcessingExecutionBudgetCases {
        void wallBudgetStopsExactlyAtDeadlineAndResetsForNextTick() {
            var budget = new ProcessingExecutionBudget(new ProcessingExecutionBudget.Limits(1, 1, 100, 1));
            budget.resetForTick(1000);
            assertTrue(budget.hasTimeBudget(1099));
            assertFalse(budget.hasTimeBudget(1100));
            assertFalse(budget.hasTimeBudget(1101));
            budget.resetForTick(2000);
            assertTrue(budget.hasTimeBudget(2000));
            assertFalse(budget.hasTimeBudget(2100));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ProcessingExecutionBudgetCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingExecutionBudgetCases.wallBudgetStopsExactlyAtDeadlineAndResetsForNextTick", () -> new ProcessingExecutionBudgetCases().wallBudgetStopsExactlyAtDeadlineAndResetsForNextTick())
            );
        }
    }

    /** ProcessingTaskBatchCases 的独立场景夹具，按类别统一执行。 */
    public static final class ProcessingTaskBatchCases {
        private static final RegistryAccess REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static final AEItemKey RAW = AEItemKey.of(new ItemStack(Items.IRON_ORE));
        private static final AEItemKey MID = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
        private static final AEItemKey FUEL = AEItemKey.of(new ItemStack(Items.COAL));
        private static final AEItemKey OUT = AEItemKey.of(new ItemStack(Items.IRON_BLOCK));

        private static KeyCounter holders(GenericStack... stacks) {
            KeyCounter holder = new KeyCounter();
            for (GenericStack stack : stacks) {
                holder.add(stack.what(), stack.amount());
            }
            return holder;
        }

        /**
         * K2 调用点钉子（夹顶值就是顶）：{@link ProcessingLatencyOrigin#merge} 的 tick 总和累加已委托
         * 共享双向档 {@code ClampedMath.add}——两份 {@link Long#MAX_VALUE} 相加必须停在顶上，不许回绕成负数。
         * 同文件的样本数累加那一档夹的是 0 底（业务底不是数域顶），按 K2 派工单留在原位，本条同时钉它没被顺手换掉。
         */
        void mergeOfTwoTopTickSumsStaysAtTop() {
            var merged = new ProcessingLatencyOrigin(Long.MAX_VALUE, 1)
                    .merge(new ProcessingLatencyOrigin(Long.MAX_VALUE, 1));

            assertEquals(Long.MAX_VALUE, merged.acceptedTickSum(),
                    "两份顶值 tick 总和相加必须停在顶上，不许回绕成负数");
            assertEquals(2, merged.acceptedPushCount(), "样本数累加仍走本文件的夹 0 底 int 档");

        }

        void ordinaryCompileValidationFailureRestoresEveryHolder() {
            IPatternDetails pattern = FakePlanGrid.FakePattern.withOutputs(
                    new IPatternDetails.IInput[]{
                            new FakePlanGrid.FakeInput(RAW, 4L),
                            new FakePlanGrid.FakeInput(FUEL, 1L)
                    },
                    new GenericStack(OUT, 1L)
            );
            RecipeSignature incompatible = new RecipeSignature(
                    MachineRecipeKind.FURNACE,
                    List.of(new RecipeSignatureInput(RAW, 4L)),
                    List.of(new GenericStack(OUT, 1L))
            );
            KeyCounter rawHolder = holders(new GenericStack(RAW, 4L));
            KeyCounter fuelHolder = holders(new GenericStack(FUEL, 1L));

            assertNull(ProcessingTask.compile(
                    pattern,
                    incompatible,
                    new KeyCounter[]{rawHolder, fuelHolder},
                    20
            ));
            assertEquals(4L, rawHolder.get(RAW));
            assertEquals(1L, fuelHolder.get(FUEL));
        }

        void neoEcoBatchCompilesOneQueueTaskWithCountedPayloadAndSource() {
            RecipeSignature signature = new RecipeSignature(
                    MachineRecipeKind.FURNACE,
                    List.of(new RecipeSignatureInput(RAW, 2L)),
                    List.of(new GenericStack(OUT, 3L))
            );
            UUID sourceCraftingId = UUID.randomUUID();

            ProcessingTask task = ProcessingTask.compileNeoEcoBatch(
                    new BatchPattern(
                            List.of(new GenericStack(RAW, 2L)),
                            List.of(new GenericStack(OUT, 3L))),
                    signature,
                    List.of(new GenericStack(RAW, 2L)),
                    List.of(new GenericStack(OUT, 3L)),
                    List.of(new GenericStack(FUEL, 1L)),
                    4L,
                    20,
                    sourceCraftingId
            );

            assertNotNull(task);
            assertEquals(4, task.executionCount());
            assertEquals(sourceCraftingId, task.sourceCraftingId());
            assertEquals(Map.of(OUT, 12L, FUEL, 4L), totals(task.buildOutputPayload()));
            ProcessingExecutionQueue queue = new ProcessingExecutionQueue(1);
            assertEquals(1, queue.remainingCapacity());
            assertTrue(queue.offer(task));
            assertEquals(1, queue.queuedTaskCount());
            assertEquals(0, queue.remainingCapacity());
            assertFalse(queue.offer(task), "counted batch 只占一个 task，满槽后不得重复接收");
            advanceSteps(task, 79);
            assertFalse(task.isReadyToComplete());
            task.advance(1);
            assertTrue(task.isReadyToComplete());
        }

        /**
         * 份数不再夹在 int 上：越过 {@code Integer.MAX_VALUE} 必须照收，工时随份数同宽；
         * 唯一的拒收理由是「份数 × 每发 tick」越过 long 上界。
         */
        void neoEcoBatchKeepsWideCountsAndRejectsOnlyLongOverflowWorkBudget() {
            RecipeSignature signature = new RecipeSignature(
                    MachineRecipeKind.FURNACE,
                    RAW,
                    1L,
                    OUT,
                    1L
            );
            BatchPattern pattern = new BatchPattern(
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(OUT, 1L))
            );

            ProcessingTask aboveInt = ProcessingTask.compileNeoEcoBatch(
                    pattern,
                    signature,
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(OUT, 1L)),
                    List.of(),
                    (long) Integer.MAX_VALUE + 1L,
                    1,
                    UUID.randomUUID()
            );
            assertNotNull(aboveInt, "份数越过 int 上限不得再被拒收");
            assertEquals((long) Integer.MAX_VALUE + 1L, aboveInt.executionCount(), "执行计数不得被截断");

            ProcessingTask wideTicks = ProcessingTask.compileNeoEcoBatch(
                    pattern,
                    signature,
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(OUT, 1L)),
                    List.of(),
                    Integer.MAX_VALUE,
                    2,
                    UUID.randomUUID()
            );
            assertNotNull(wideTicks, "工时乘份数越 int 不再是拒收理由：工时已随份数同宽");
            // 整批工时 = 2 × Integer.MAX_VALUE 发：推一发 Integer.MAX_VALUE 还差一半
            wideTicks.advance(Integer.MAX_VALUE);
            assertFalse(wideTicks.isReadyToComplete(), "工时没推满就不得提前完成（防饱和提速）");
            wideTicks.advance(Integer.MAX_VALUE);
            assertTrue(wideTicks.isReadyToComplete(), "推满 long 倍工时后才允许完成");

            assertNull(ProcessingTask.compileNeoEcoBatch(
                    pattern,
                    signature,
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(OUT, 1L)),
                    List.of(),
                    Long.MAX_VALUE,
                    2,
                    UUID.randomUUID()
            ), "份数 × 每发 tick 越过 long 上界才是真拒收");
        }

        /**
         * 直连机 2.1T 读数的回归口径：每发 1000 mB 流体的处理任务，份数越过
         * {@code Integer.MAX_VALUE} 时不拒收、计数不截断，实际推进份数与申报一致
         * （2.1T = Integer.MAX_VALUE × 1000 就是那条 int 链夹出来的读数）。
         */
        void fluidBatchAboveIntExecutionLimitAdvancesEveryDeclaredExecution() {
            AEKey lava = AEFluidKey.of(Fluids.LAVA);
            long declared = (long) Integer.MAX_VALUE + 1L;
            RecipeSignature signature = new RecipeSignature(
                    MachineRecipeKind.FURNACE,
                    RAW,
                    1L,
                    lava,
                    1000L
            );
            BatchPattern pattern = new BatchPattern(
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(lava, 1000L))
            );

            ProcessingTask task = ProcessingTask.compileNeoEcoBatch(
                    pattern,
                    signature,
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(lava, 1000L)),
                    List.of(),
                    declared,
                    1,
                    UUID.randomUUID()
            );

            assertNotNull(task, "每发 1000 mB 的流体批越过 int 份数不得被拒收");
            assertEquals(declared, task.executionCount(), "执行计数不得夹在 2.1e9");
            // 一发一 tick：申报 2.1e9+1 份就要推满 2.1e9+1 发，不被 int 工时夹成提前完成
            task.advance(Integer.MAX_VALUE);
            assertFalse(task.isReadyToComplete(), "只差一份时不得提前完成");
            task.advance(1);
            assertTrue(task.isReadyToComplete(), "推满申报份数才允许完成");
            assertEquals(Map.of(lava, declared * 1000L), totals(task.buildOutputPayload()),
                    "产出量 = 每发 1000 mB × 申报份数，不得停在 2.1T");
        }

        void neoEcoBatchRejectsMergedPayloadOverflow() {
            RecipeSignature signature = new RecipeSignature(
                    MachineRecipeKind.FURNACE,
                    RAW,
                    1L,
                    OUT,
                    Long.MAX_VALUE
            );
            BatchPattern pattern = new BatchPattern(
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(OUT, Long.MAX_VALUE))
            );

            assertNull(ProcessingTask.compileNeoEcoBatch(
                    pattern,
                    signature,
                    List.of(new GenericStack(RAW, 1L)),
                    List.of(new GenericStack(OUT, Long.MAX_VALUE)),
                    List.of(new GenericStack(OUT, 1L)),
                    1L,
                    1,
                    UUID.randomUUID()
            ));
        }

        void componentSpecificSignatureIsPermutationInvariantAndRejectsSiblingVariant() {
            AEItemKey variantA = namedKey(Items.AMETHYST_SHARD, "variant-a");
            AEItemKey variantB = namedKey(Items.AMETHYST_SHARD, "variant-b");
            List<RecipeSignatureInput> ordered = List.of(
                    new RecipeSignatureInput(variantA, 1L),
                    new RecipeSignatureInput(variantB, 1L));
            List<RecipeSignatureInput> reversed = List.of(
                    new RecipeSignatureInput(variantB, 1L),
                    new RecipeSignatureInput(variantA, 1L));
            var signature = new RecipeSignature(
                    MachineRecipeKind.FURNACE,
                    ordered,
                    List.of(new GenericStack(OUT, 1L)));
            var index = new MachineRecipeIndex(
                    new MachineIdentity(
                            ResourceLocation.fromNamespaceAndPath("test", "machine"),
                            null, null, "test", null, null),
                    Set.of(MachineRecipeKind.FURNACE),
                    List.of(),
                    Set.of(signature),
                    MachineSupportStatus.SUPPORTED_EXPLICIT,
                    MachineSupportReasonCode.NONE,
                    1L);

            assertEquals(RecipeSignatureKey.of(ordered, signature.outputs()),
                    RecipeSignatureKey.of(reversed, signature.outputs()));
            assertEquals(signature, index.findSignature(reversed, signature.outputs()));
            assertNull(index.findSignature(
                    List.of(new RecipeSignatureInput(variantA, 2L)), signature.outputs()));
            assertNotEquals(variantA, variantB);
        }

        private static AEItemKey namedKey(Item item, String name) {
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            return AEItemKey.of(stack);
        }

        private static Map<AEKey, Long> totals(List<GenericStack> stacks) {
            Map<AEKey, Long> totals = new LinkedHashMap<>();
            for (GenericStack stack : stacks) {
                totals.merge(stack.what(), stack.amount(), Long::sum);
            }
            return totals;
        }

        /**
         * K2 钳位调用点钉子：{@link DirectProcessingStackSupport#normalizeStacks} 的合并口"夹顶值就是顶"——
         * 同键两份栈量相加越过 long 域顶时读数夹在 Long.MAX_VALUE，不回绕成负数、也不读成 0。
         * 算术契约（含负向夹底那一半）由 bigint/ClampedMathTest 钉；本条只钉这个调用点走的是同一份钳位，
         * 因为该口按 amount&lt;=0 过滤，负方向在这条腿上走不到。
         */
        void normalizeStacksClampsMergedAmountAtLongTopInsteadOfWrapping() {
            List<GenericStack> merged = DirectProcessingStackSupport.normalizeStacks(List.of(
                    new GenericStack(MID, Long.MAX_VALUE - 1L),
                    new GenericStack(MID, 5L)));
            assertEquals(1, merged.size(), "同键两份要并成一份");
            assertEquals(Long.MAX_VALUE, merged.get(0).amount(),
                    () -> "越过 long 域顶的合并量必须夹在 Long.MAX_VALUE, 实际=" + merged.get(0).amount());
        }

        private static void advanceSteps(ProcessingTask task, int ticks) {
            for (int i = 0; i < ticks; i++) {
                task.advance(1);
            }
        }

        /** 普通样板替身，批处理使用显式输入/输出契约。 */
        private static final class BatchPattern implements IPatternDetails {
            private final IInput[] inputs;
            private final List<GenericStack> outputs;
            BatchPattern(List<GenericStack> inputs, List<GenericStack> outputs) {
                this.inputs = inputs.stream().map(stack -> new FakePlanGrid.FakeInput(stack.what(), stack.amount()))
                        .toArray(IInput[]::new);
                this.outputs = outputs;
            }
            @Override public AEItemKey getDefinition() { return AEItemKey.of(new ItemStack(Items.PAPER)); }
            @Override public IInput[] getInputs() { return inputs; }
            @Override public List<GenericStack> getOutputs() { return outputs; }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.mergeOfTwoTopTickSumsStaysAtTop", () -> new ProcessingTaskBatchCases().mergeOfTwoTopTickSumsStaysAtTop()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.ordinaryCompileValidationFailureRestoresEveryHolder", () -> new ProcessingTaskBatchCases().ordinaryCompileValidationFailureRestoresEveryHolder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.neoEcoBatchCompilesOneQueueTaskWithCountedPayloadAndSource", () -> new ProcessingTaskBatchCases().neoEcoBatchCompilesOneQueueTaskWithCountedPayloadAndSource()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.neoEcoBatchKeepsWideCountsAndRejectsOnlyLongOverflowWorkBudget", () -> new ProcessingTaskBatchCases().neoEcoBatchKeepsWideCountsAndRejectsOnlyLongOverflowWorkBudget()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.fluidBatchAboveIntExecutionLimitAdvancesEveryDeclaredExecution", () -> new ProcessingTaskBatchCases().fluidBatchAboveIntExecutionLimitAdvancesEveryDeclaredExecution()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.neoEcoBatchRejectsMergedPayloadOverflow", () -> new ProcessingTaskBatchCases().neoEcoBatchRejectsMergedPayloadOverflow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.componentSpecificSignatureIsPermutationInvariantAndRejectsSiblingVariant", () -> new ProcessingTaskBatchCases().componentSpecificSignatureIsPermutationInvariantAndRejectsSiblingVariant()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskBatchCases.normalizeStacksClampsMergedAmountAtLongTopInsteadOfWrapping", () -> new ProcessingTaskBatchCases().normalizeStacksClampsMergedAmountAtLongTopInsteadOfWrapping())
            );
        }
    }

    /** ProcessingTaskInputAccountingCases 的独立场景夹具，按类别统一执行。 */
    public static final class ProcessingTaskInputAccountingCases {
        private static final AEItemKey RAW = AEItemKey.of(Items.RAW_IRON);
        private static final AEItemKey OUT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey BYPRODUCT = AEItemKey.of(Items.STONE);
        private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
        private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static AEProcessingPattern pattern(List<GenericStack> inputs, List<GenericStack> outputs) {
            ItemStack definition = new ItemStack(Items.PAPER);
            AEProcessingPattern.encode(definition, inputs, outputs);
            return new AEProcessingPattern(AEItemKey.of(definition));
        }
        private static KeyCounter holder(AEKey key, long amount) {
            var holder = new KeyCounter(); holder.add(key, amount); return holder;
        }
        private static RecipeSignature signature(long raw, long out) {
            return new RecipeSignature(null, List.of(new RecipeSignatureInput(RAW, raw)), List.of(new GenericStack(OUT, out)));
        }

        void replacementMatchesOnlyRealMachineRecipesAndProcessesFullBatch() {
            var replacement = git.chexson.chexsonsaeutils.pattern.replacement.RegistrationScenarios.ReplacementAwarePatternContractCases.wrap(
                    pattern(List.of(new GenericStack(RAW, 4)), List.of(new GenericStack(OUT, 2))));
            var alternate = git.chexson.chexsonsaeutils.pattern.replacement.RegistrationScenarios.ReplacementAwarePatternContractCases.ALT;
            var originalRecipe = signature(2, 1);
            var alternateRecipe = new RecipeSignature(null, List.of(new RecipeSignatureInput(alternate, 2)),
                    List.of(new GenericStack(OUT, 1)));
            var index = new MachineRecipeIndex(MachineIdentity.fromBindingStack(new ItemStack(Items.FURNACE)),
                    java.util.Set.of(), List.of(), new java.util.LinkedHashSet<>(List.of(originalRecipe, alternateRecipe)),
                    MachineSupportStatus.SUPPORTED_GENERIC, MachineSupportReasonCode.NONE, 1);
            var matches = index.matchingSignatures(replacement);
            assertEquals(List.of(originalRecipe, alternateRecipe), matches);
            var compatibility = PatternCompatibility.supported(MachineSupportStatus.SUPPORTED_GENERIC, replacement, matches);
            var holder = holder(alternate, 40);
            var task = ProcessingTask.compile(compatibility, new KeyCounter[]{holder}, 1);
            assertNotNull(task); assertEquals(20, task.executionCount());
            assertEquals(Map.of(OUT, 20L), DirectProcessingStackSupport.stackAmounts(task.buildOutputPayload()));
            assertEquals(40, holder.get(alternate));
            assertNull(ProcessingTask.compile(compatibility, new KeyCounter[]{holder(alternate, 42)}, 1));
            assertNull(ProcessingTask.compile(compatibility, new KeyCounter[]{holder(BYPRODUCT, 40)}, 1));
            assertNull(ProcessingTask.compile(PatternCompatibility.supportedGeneric(replacement, originalRecipe),
                    new KeyCounter[]{holder}, 1));
        }
        void unsupportedOriginalCandidateIsRemovedBeforePlanning() {
            var replacement = git.chexson.chexsonsaeutils.pattern.replacement.RegistrationScenarios.ReplacementAwarePatternContractCases.wrap(
                    pattern(List.of(new GenericStack(RAW, 4)), List.of(new GenericStack(OUT, 2))));
            var alternate = git.chexson.chexsonsaeutils.pattern.replacement.RegistrationScenarios.ReplacementAwarePatternContractCases.ALT;
            var recipe = new RecipeSignature(null, List.of(new RecipeSignatureInput(alternate, 2)),
                    List.of(new GenericStack(OUT, 1)));
            var compatibility = PatternCompatibility.supported(MachineSupportStatus.SUPPORTED_GENERIC,
                    replacement, List.of(recipe));
            assertArrayEquals(new GenericStack[]{new GenericStack(alternate, 4)},
                    compatibility.pattern().getInputs()[0].getPossibleInputs());
            assertEquals(replacement.getDefinition(), compatibility.pattern().getDefinition());
            assertNotNull(ProcessingTask.compile(compatibility, new KeyCounter[]{holder(alternate, 40)}, 1));
        }

        void overlappingCandidatesCanReassignWithoutGreedyMismatch() {
            var pattern = git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern.of(OUT, 1,
                    git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput.multi(1, RAW, BYPRODUCT),
                    git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput.multi(1, RAW));
            var recipe = new RecipeSignature(null, List.of(new RecipeSignatureInput(RAW, 1),
                    new RecipeSignatureInput(BYPRODUCT, 1)), List.of(new GenericStack(OUT, 1)));
            assertFalse(DirectProcessingStackSupport.patternInputs(pattern, recipe).isEmpty());
        }

        void repeatedInputSlotsProcessEveryDeliveredExecution() {
            var pattern = pattern(List.of(new GenericStack(RAW, 1), new GenericStack(RAW, 1)),
                    List.of(new GenericStack(OUT, 2)));
            var holder = holder(RAW, 64);
            var task = ProcessingTask.compile(pattern, signature(2, 2), new KeyCounter[]{holder}, 1);
            assertNotNull(task);
            assertEquals(32, task.executionCount());
            assertEquals(Map.of(OUT, 64L), DirectProcessingStackSupport.stackAmounts(task.buildOutputPayload()));
            assertEquals(64, holder.get(RAW), "编译阶段只读取投入，调用方在接收成功后转移所有权");
        }

        void scaledPatternAndBatchMultiplierAreIndependent() {
            var pattern = pattern(List.of(new GenericStack(RAW, 1), new GenericStack(RAW, 1)),
                    List.of(new GenericStack(OUT, 4)));
            var task = ProcessingTask.compile(pattern, signature(1, 2), new KeyCounter[]{holder(RAW, 64)}, 1);
            assertNotNull(task);
            assertEquals(64, task.executionCount());
            assertEquals(Map.of(OUT, 128L), DirectProcessingStackSupport.stackAmounts(task.buildOutputPayload()));
            task.advance(31);
            assertFalse(task.isReadyToComplete());
            task.advance(1);
            assertTrue(task.isReadyToComplete());
        }

        void mixedItemFluidBatchPreservesEveryOutputThroughQueueAndSave() {
            var pattern = pattern(List.of(new GenericStack(RAW, 1), new GenericStack(RAW, 1), new GenericStack(WATER, 250)),
                    List.of(new GenericStack(OUT, 2), new GenericStack(BYPRODUCT, 1)));
            var recipe = new RecipeSignature(null,
                    List.of(new RecipeSignatureInput(RAW, 2), new RecipeSignatureInput(WATER, 250)),
                    List.of(new GenericStack(OUT, 2), new GenericStack(BYPRODUCT, 1)));
            var task = ProcessingTask.compile(pattern, recipe, new KeyCounter[]{holder(RAW, 64), holder(WATER, 8000)}, 1);
            assertNotNull(task);
            var restored = ProcessingTask.readFromTag(task.writeToTag(REGISTRIES), REGISTRIES);
            assertNotNull(restored);
            var queue = new ProcessingExecutionQueue(1);
            assertTrue(queue.offer(restored));
            var delivered = new ArrayList<GenericStack>();
            var host = new ProcessingTaskCompletionHost() {
                @Override public int getProcessingWorkUnitsPerTick() { return 64; }
                @Override public boolean isWaitingForOutputReturn() { return false; }
                @Override public void completeProcessingTask(ProcessingTask task) { delivered.addAll(task.buildOutputPayload()); }
            };
            queue.tick(host, 1);
            assertEquals(Map.of(OUT, 64L, BYPRODUCT, 32L), DirectProcessingStackSupport.stackAmounts(delivered));
            assertTrue(queue.isIdle());
        }

        void imbalancedOrPartialPatternBatchIsRejectedWithoutChangingAnyHolder() {
            var pattern = pattern(List.of(new GenericStack(RAW, 2), new GenericStack(WATER, 250)),
                    List.of(new GenericStack(OUT, 2)));
            var recipe = new RecipeSignature(null,
                    List.of(new RecipeSignatureInput(RAW, 2), new RecipeSignatureInput(WATER, 250)),
                    List.of(new GenericStack(OUT, 2)));
            var raw = holder(RAW, 64); var water = holder(WATER, 7750);
            assertNull(ProcessingTask.compile(pattern, recipe, new KeyCounter[]{raw, water}, 1));
            assertEquals(64, raw.get(RAW)); assertEquals(7750, water.get(WATER));
            var scaled = pattern(List.of(new GenericStack(RAW, 2)), List.of(new GenericStack(OUT, 4)));
            var partial = holder(RAW, 3);
            assertNull(ProcessingTask.compile(scaled, signature(1, 2), new KeyCounter[]{partial}, 1));
            assertEquals(3, partial.get(RAW));
        }

        void quantityOverflowRejectsBeforeOwnershipTransfer() {
            var pattern = pattern(List.of(new GenericStack(RAW, 1)), List.of(new GenericStack(OUT, 2)));
            var raw = holder(RAW, Long.MAX_VALUE);
            assertNull(ProcessingTask.compile(pattern, signature(1, 2), new KeyCounter[]{raw}, 1));
            assertEquals(Long.MAX_VALUE, raw.get(RAW));
            var left = holder(RAW, Long.MAX_VALUE); var right = holder(RAW, 1);
            assertNull(ProcessingTask.compile(pattern, signature(1, 2), new KeyCounter[]{left, right}, 1));
            assertEquals(Long.MAX_VALUE, left.get(RAW)); assertEquals(1, right.get(RAW));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.replacementMatchesOnlyRealMachineRecipesAndProcessesFullBatch", () -> new ProcessingTaskInputAccountingCases().replacementMatchesOnlyRealMachineRecipesAndProcessesFullBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.unsupportedOriginalCandidateIsRemovedBeforePlanning", () -> new ProcessingTaskInputAccountingCases().unsupportedOriginalCandidateIsRemovedBeforePlanning()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.overlappingCandidatesCanReassignWithoutGreedyMismatch", () -> new ProcessingTaskInputAccountingCases().overlappingCandidatesCanReassignWithoutGreedyMismatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.repeatedInputSlotsProcessEveryDeliveredExecution", () -> new ProcessingTaskInputAccountingCases().repeatedInputSlotsProcessEveryDeliveredExecution()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.scaledPatternAndBatchMultiplierAreIndependent", () -> new ProcessingTaskInputAccountingCases().scaledPatternAndBatchMultiplierAreIndependent()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.mixedItemFluidBatchPreservesEveryOutputThroughQueueAndSave", () -> new ProcessingTaskInputAccountingCases().mixedItemFluidBatchPreservesEveryOutputThroughQueueAndSave()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.imbalancedOrPartialPatternBatchIsRejectedWithoutChangingAnyHolder", () -> new ProcessingTaskInputAccountingCases().imbalancedOrPartialPatternBatchIsRejectedWithoutChangingAnyHolder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ProcessingTaskInputAccountingCases.quantityOverflowRejectsBeforeOwnershipTransfer", () -> new ProcessingTaskInputAccountingCases().quantityOverflowRejectsBeforeOwnershipTransfer())
            );
        }
    }

    /** RecipeAutomaticDiscoveryRefactorCases 的独立场景夹具，按类别统一执行。 */
    public static final class RecipeAutomaticDiscoveryRefactorCases {
        void itemAndBlockNamesBothContributeWithoutJeiAndKeepConfiguredType() {
            var mappings = MachineRecipeConfigMappingRegistry.instance();
            var snapshot = mappings.snapshot();
            var item = ResourceLocation.parse("test:heater");
            var machine = new MachineIdentity(item, ResourceLocation.parse("minecraft:furnace"), null, "test", null, null);
            try {
                mappings.registerMapping(item, RecipeType.BLASTING, 20);
                var resolver = new RecipeTypeCandidateResolver(MachineAdapterRegistry.empty(), mappings, true);
                var result = resolver.resolve(machine);
                assertEquals(Set.of(RecipeType.SMELTING, RecipeType.BLASTING),
                        result.candidates().stream().map(RecipeTypeCandidate::recipeType).collect(Collectors.toSet()));
                assertEquals(MachineRecipeCandidateSource.CONFIG_MAPPING, result.source());
                assertFalse(new RecipeTypeCandidateResolver(MachineAdapterRegistry.empty(), mappings, false)
                        .resolve(machine).candidates().stream().anyMatch(candidate -> candidate.recipeType() == RecipeType.SMELTING));
            } finally { mappings.restore(snapshot); }
        }

        void explicitAdaptersKeepPriorityWhileIndependentConfiguredTypesRemainAvailable() {
            var mappings = MachineRecipeConfigMappingRegistry.instance();
            var snapshot = mappings.snapshot();
            var item = ResourceLocation.parse("test:machine");
            var machine = new MachineIdentity(item, null, null, "test", null, null);
            var explicit = new RecipeTypeCandidate(null, RecipeType.SMELTING, MachineRecipeCandidateSource.EXPLICIT_ADAPTER, 10);
            try {
                mappings.registerMapping(item, RecipeType.BLASTING, 20);
                mappings.registerMapping(item, RecipeType.SMELTING, 30);
                var adapters = new MachineAdapterRegistry(List.of(identity -> List.of(explicit)));
                var result = new RecipeTypeCandidateResolver(adapters, mappings, true).resolve(machine);
                assertEquals(2, result.candidates().size());
                assertSame(explicit, result.candidates().getFirst());
                assertEquals(MachineRecipeCandidateSource.EXPLICIT_ADAPTER, result.source());
            } finally { mappings.restore(snapshot); }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RecipeAutomaticDiscoveryRefactorCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RecipeAutomaticDiscoveryRefactorCases.itemAndBlockNamesBothContributeWithoutJeiAndKeepConfiguredType", () -> new RecipeAutomaticDiscoveryRefactorCases().itemAndBlockNamesBothContributeWithoutJeiAndKeepConfiguredType()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RecipeAutomaticDiscoveryRefactorCases.explicitAdaptersKeepPriorityWhileIndependentConfiguredTypesRemainAvailable", () -> new RecipeAutomaticDiscoveryRefactorCases().explicitAdaptersKeepPriorityWhileIndependentConfiguredTypesRemainAvailable())
            );
        }
    }
}
