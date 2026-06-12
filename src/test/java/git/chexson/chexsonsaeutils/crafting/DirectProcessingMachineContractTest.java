package git.chexson.chexsonsaeutils.crafting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.projectPath;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectProcessingMachineContractTest {

    @Test
    void directProcessingMachineStaysRegisteredAsIndependentProvider() throws IOException {
        String modSource = readUtf8(javaSource("git/chexson/chexsonsaeutils/Chexsonsaeutils.java"));
        String contentSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/registration/ChexsonsaeutilsContent.java"
        ));
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/directprocessing/AEDirectProcessingMachineBlockEntity.java"
        ));
        String menuSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/menu/implementations/AEDirectProcessingMachineMenu.java"
        ));
        String screenSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/gui/implementations/AEDirectProcessingMachineScreen.java"
        ));
        String gameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/DirectProcessingMachineGameTests.java"
        ));
        String configSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java"
        ));
        String mappingReloadSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeConfigMappingReloadListener.java"
        ));
        String mappingRegistrySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeConfigMappingRegistry.java"
        ));
        String indexCacheSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeIndexCache.java"
        ));
        String reloadTrackerSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeReloadTracker.java"
        ));
        String mockRecipeSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/DirectProcessingMockRecipe.java"
        ));
        String patternInventorySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/DirectProcessingPatternInventory.java"
        ));
        String patternProviderSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/DirectProcessingPatternProvider.java"
        ));
        String outputSinkSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/DirectProcessingOutputSink.java"
        ));
        String ingressRouterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/AeCpuIngressRouter.java"
        ));
        String blockSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/block/crafting/AEDirectProcessingMachineBlock.java"
        ));
        String blockState = readUtf8(resourcePath("assets/chexsonsaeutils/blockstates/ae_direct_processing_machine.json"));
        String recipe = readUtf8(resourcePath("data/chexsonsaeutils/recipe/ae_direct_processing_machine.json"));
        String lootTable = readUtf8(resourcePath("data/chexsonsaeutils/loot_table/blocks/ae_direct_processing_machine.json"));
        String screenStyle = readUtf8(resourcePath("assets/ae2/screens/ae_direct_processing_machine.json"));
        String buildGradle = readUtf8(Path.of("build.gradle"));
        String modsToml = readUtf8(Path.of("src/main/templates/META-INF/neoforge.mods.toml"));
        String jeiHintSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/integration/jei/JeiMachineRecipeTypeHint.java"
        ));
        String jeiBridgeSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/integration/jei/JeiMachineRecipeTypeHintBridge.java"
        ));
        String jeiRuntimeHolderSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/integration/jei/JeiRuntimeHolder.java"
        ));
        String jeiSignatureBridgeSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/integration/jei/JeiRecipeSignatureHintBridge.java"
        ));
        String jeiPluginSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/integration/jei/ChexsonsaeutilsJeiPlugin.java"
        ));
        String mekanismAdapterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/mekanism/MekanismMachineRecipeTypeAdapter.java"
        ));
        String mekanismSupportSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/mekanism/MekanismDirectProcessingSupport.java"
        ));
        String mekanismShapeSupportSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/mekanism/MekanismRecipeShapeSupport.java"
        ));
        String candidateResolverSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/RecipeTypeCandidateResolver.java"
        ));
        String discoveryServiceSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeDiscoveryService.java"
        ));
        String userConfigStoreSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeUserConfigStore.java"
        ));
        String vanillaFurnaceMapping = readUtf8(resourcePath(
                "data/chexsonsaeutils/chexsonsaeutils/direct_processing_machines/vanilla_furnace.json"
        ));
        String mockCustomMapping = readUtf8(resourcePath(
                "data/chexsonsaeutils/chexsonsaeutils/direct_processing_machines/mock_custom_recipe_type.json"
        ));
        String mockCustomRecipe = readUtf8(resourcePath(
                "data/chexsonsaeutils/recipe/direct_processing_mock.json"
        ));
        JsonObject enUs = readLang(resourcePath("assets/chexsonsaeutils/lang/en_us.json"));
        JsonObject zhCn = readLang(resourcePath("assets/chexsonsaeutils/lang/zh_cn.json"));

        assertTrue(contentSource.contains("RegisteredBlock<AEDirectProcessingMachineBlock>"),
                "direct processing machine block and item must be registered independently");
        assertTrue(contentSource.contains("AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY"),
                "direct processing machine block entity registration is missing");
        assertTrue(contentSource.contains("AE_DIRECT_PROCESSING_MACHINE_MENU"),
                "direct processing machine menu registration is missing");
        assertTrue(contentSource.contains("AEDirectProcessingMachineScreen::new"),
                "direct processing machine screen registration is missing");
        assertTrue(contentSource.contains("AEDirectProcessingMachineBlockEntity::serverTick"),
                "direct processing machine must register a server ticker");
        assertTrue(contentSource.contains("Capabilities.ItemHandler.BLOCK"),
                "direct processing machine must expose item handler automation capability");
        assertTrue(contentSource.contains("AECapabilities.IN_WORLD_GRID_NODE_HOST")
                        && contentSource.contains("AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY.get()"),
                "direct processing machine must expose AE in-world grid node host capability");
        assertTrue(contentSource.contains("Upgrades.add(AEItems.SPEED_CARD, AE_DIRECT_PROCESSING_MACHINE_ITEM.get(), 5)"),
                "speed cards must be installable on the direct processing machine");
        assertTrue(modSource.contains("AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY"),
                "main mod facade must expose the direct processing block entity supplier");
        assertTrue(contentSource.contains("RECIPE_TYPES.register(modEventBus)")
                        && contentSource.contains("RECIPE_SERIALIZERS.register(modEventBus)"),
                "direct processing custom recipe type and serializer registries must be registered");
        assertTrue(contentSource.contains("DIRECT_PROCESSING_MOCK_RECIPE_TYPE")
                        && contentSource.contains("DIRECT_PROCESSING_MOCK_RECIPE_SERIALIZER"),
                "direct processing mock custom recipe type and serializer must be registered");
        assertTrue(mockRecipeSource.contains("extends SingleItemRecipe")
                        && mockRecipeSource.contains("createType()")
                        && mockRecipeSource.contains("createSerializer()"),
                "direct processing mock recipe must be a real registered recipe type");
        assertTrue(patternInventorySource.contains("public final class DirectProcessingPatternInventory"),
                "direct processing machine must own a named local pattern inventory facade");
        assertTrue(patternProviderSource.contains("public final class DirectProcessingPatternProvider"),
                "direct processing machine must own a named local provider facade");
        assertTrue(outputSinkSource.contains("AeCpuIngressRouter.routePayload(")
                        && outputSinkSource.contains(".remainingPayload()"),
                "direct processing AE return path must route through the shared CPU-first ingress router");
        assertTrue(ingressRouterSource.contains("craftingService.insertIntoCpus(key, remaining, Actionable.SIMULATE)")
                        && ingressRouterSource.contains("storageService.getInventory().insert(key, remaining, Actionable.SIMULATE, actionSource)")
                        && ingressRouterSource.contains("storageService.getInventory().insert(")
                        && ingressRouterSource.contains("Actionable.MODULATE"),
                "shared ingress router must simulate CPU/network ingress before committing the accepted fallback payload");
        assertTrue(blockEntitySource.contains("DirectProcessingPatternInventory")
                        && blockEntitySource.contains("DirectProcessingPatternProvider"),
                "direct processing block entity must use the named local inventory and provider facades");
        assertTrue(blockSource.contains("extends AEBaseEntityBlock<AEDirectProcessingMachineBlockEntity>"),
                "direct processing block must use its own block entity type");
        assertTrue(blockSource.contains("Chexsonsaeutils.AE_DIRECT_PROCESSING_MACHINE_MENU.get()"),
                "direct processing block must open its own menu");
        assertTrue(blockEntitySource.contains("extends AENetworkedBlockEntity"),
                "direct processing machine must attach to AE directly");
        assertTrue(blockEntitySource.contains("implements ICraftingProvider"),
                "direct processing machine must provide AE crafting patterns itself");
        assertTrue(blockEntitySource.contains(".addService(ICraftingProvider.class, this)"),
                "direct processing machine must register its local provider service on its node");
        assertFalse(blockEntitySource.contains("extends AbstractHighCapacityCraftingHostBlockEntity"),
                "direct processing machine must not inherit the high-capacity crafting host");
        assertFalse(blockEntitySource.contains("IFormalMachineCraftingProvider"),
                "direct processing machine must not enter formal-machine dispatch mixins");
        assertFalse(blockEntitySource.contains("IMolecularAssemblerSupportedPattern"),
                "direct processing machine must not require molecular-assembler supported patterns");
        assertTrue(blockState.contains("\"chexsonsaeutils:block/ae_direct_processing_machine\""),
                "direct processing blockstate must point at its own block model");
        assertTrue(recipe.contains("\"ae2:processing_pattern\""),
                "direct processing machine recipe must require AE2 processing patterns");
        assertTrue(lootTable.contains("\"chexsonsaeutils:ae_direct_processing_machine\""),
                "direct processing machine loot table must self-drop the machine");
        assertTrue(menuSource.contains("extends UpgradeableMenu<AEDirectProcessingMachineBlockEntity>"),
                "direct processing menu must be bound to the direct machine block entity");
        assertTrue(menuSource.contains("host.isProcessingPattern(stack)"),
                "direct processing menu must restrict pattern slots to processing patterns locally");
        assertTrue(menuSource.contains("ACTION_NEXT_PAGE")
                        && menuSource.contains("ACTION_PREVIOUS_PAGE")
                        && menuSource.contains("ACTION_GOTO_PAGE")
                        && menuSource.contains("ACTION_IMPORT_JEI_HINTS"),
                "direct processing menu must expose page navigation and JEI import for large pattern inventories");
        assertTrue(menuSource.contains("getSummaryLine")
                        && menuSource.contains("recipe_types")
                        && menuSource.contains("getVisiblePatternStatusLine")
                        && menuSource.contains("getPatternSlotTooltip"),
                "direct processing menu must expose recipe types and support diagnostics");
        assertTrue(menuSource.contains("visiblePatternStatusSnapshot")
                        && menuSource.contains("ensureVisiblePatternSnapshotParsed")
                        && menuSource.contains("getVisiblePatternStatus(")
                        && menuSource.contains("getVisiblePatternReason("),
                "direct processing menu must read tooltip diagnostics from the synced page snapshot");
        assertTrue(menuSource.contains("MachineRecipeConfigImportRequest")
                        && menuSource.contains("importJeiHintsOnServer"),
                "direct processing menu must forward JEI imports through a local server action");
        assertTrue(menuSource.contains("getPatternPageSlotIndex")
                        && menuSource.contains("return null;")
                        && menuSource.contains("binding_slot_tooltip"),
                "direct processing menu must avoid overlapping custom tooltip rendering");
        assertFalse(menuSource.contains("getFirstPatternStatusLine"),
                "direct processing menu must not expose the removed first-pattern diagnostic line");
        assertFalse(menuSource.contains("firstPatternStatus"),
                "direct processing menu must not sync removed first-pattern state");
        assertFalse(screenSource.contains("getFirstPatternStatusLine"),
                "direct processing screen must not draw the removed first-pattern diagnostic line");
        assertFalse(screenSource.contains("getPageLine"),
                "direct processing screen must not draw the removed page explanation line");
        assertTrue(screenSource.contains("previousPageButton")
                        && screenSource.contains("nextPageButton")
                        && screenSource.contains("importJeiButton"),
                "direct processing screen must provide page navigation and JEI import controls");
        assertTrue(screenSource.contains("getTooltipFromContainerItem")
                        && screenSource.contains("hasShiftDown()")
                        && screenSource.contains("getPatternSlotTooltip"),
                "direct processing screen must append pattern diagnostics into the item tooltip");
        assertFalse(screenSource.contains("isHoveredOrFocused()"),
                "JEI tooltip rendering must not stay alive through focused buttons");
        assertFalse(screenSource.contains("getPatternStatusForTest("),
                "direct processing screen must not read block-entity compatibility state directly");
        assertTrue(screenSource.contains("JeiRuntimeHolder")
                        && screenSource.contains("collectVisibleHintsForMachine")
                        && screenSource.contains("collectSignatureHintsForMachine")
                        && screenSource.contains("buildJeiImportRequest")
                        && screenSource.contains("buildJeiTooltip")
                        && screenSource.contains("suppressJeiTooltipUntilMouseLeave")
                        && screenSource.contains("setFocused(false)"),
                "direct processing screen must derive current-machine JEI candidates locally");
        assertTrue(screenSource.contains("AEBaseScreen<AEDirectProcessingMachineMenu>"),
                "direct processing screen must use its own menu");
        assertTrue(screenStyle.contains("\"ENCODED_PATTERN\""),
                "direct processing screen style must include processing pattern slots");
        assertTrue(screenStyle.contains("binding_slot_label")
                        && !screenStyle.contains("binding_slot_tooltip"),
                "direct processing screen style must use a static label instead of the hover tooltip key");
        assertTrue(modSource.contains("DirectProcessingMachineGameTests.class"),
                "direct processing GameTests must be registered");
        assertTrue(modSource.contains("boolean directProcessingGameTests")
                        && modSource.contains("chexsonsaeutils.highCapacityGameTests")
                        && modSource.contains("registerHighCapacityGameTests"),
                "direct processing GameTest registration must support isolated runtime execution");
        assertTrue(modSource.contains("AddReloadListenerEvent"),
                "direct processing datapack mappings must register a server reload listener");
        assertTrue(mappingReloadSource.contains("chexsonsaeutils/direct_processing_machines"),
                "direct processing datapack mappings must use the documented datapack directory");
        assertTrue(mappingReloadSource.contains("MachineRecipeConfigMappingRegistry.instance().replaceParsedMappings"),
                "datapack reload must replace the local direct machine mapping registry");
        assertTrue(mappingReloadSource.contains("MachineRecipeReloadTracker.markRecipeReloaded()"),
                "datapack reload must bump the local direct machine recipe reload epoch");
        assertTrue(reloadTrackerSource.contains("recipeReloadEpoch()")
                        && reloadTrackerSource.contains("MachineRecipeIndexCache.instance().clear()"),
                "direct processing reload tracker must expose a local epoch and clear local indexes");
        assertTrue(mappingRegistrySource.contains("public synchronized Snapshot snapshot()")
                        && mappingRegistrySource.contains("public synchronized void restore(Snapshot snapshot)"),
                "config mapping tests must be able to restore the local registry after temporary mappings");
        assertTrue(mappingRegistrySource.contains("MachineRecipeIndexCache.instance().clear()"),
                "config mapping changes must clear the local direct-machine recipe index cache");
        assertTrue(mappingRegistrySource.contains("userConfigMappingsByMachineId")
                        && mappingRegistrySource.contains("legacyConfigMappingsByMachineId")
                        && mappingRegistrySource.contains("datapackMappingsByMachineId")
                        && mappingRegistrySource.contains("runtimeMappingsByMachineId"),
                "direct processing config, datapack, and test/runtime mappings must not overwrite each other");
        assertTrue(mappingRegistrySource.contains("replaceAllMappingsForTest"),
                "tests must be able to reset all direct-processing mapping layers without changing reload semantics");
        assertTrue(mappingRegistrySource.contains("addResolvedCandidates(candidates, runtimeMappingsByMachineId")
                        && mappingRegistrySource.contains("addResolvedCandidates(candidates, userConfigMappingsByMachineId")
                        && mappingRegistrySource.contains("addResolvedCandidates(candidates, legacyConfigMappingsByMachineId")
                        && mappingRegistrySource.contains("addResolvedCandidates(candidates, datapackMappingsByMachineId"),
                "direct processing mapping resolution must merge all local mapping sources");
        assertTrue(configSource.contains("AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED")
                        && configSource.contains("AE_DIRECT_PROCESSING_MACHINE_REFLECTIVE_DISCOVERY_ENABLED"),
                "generic and reflective direct-machine discovery paths must have local config kill switches");
        assertTrue(indexCacheSource.contains("public final class MachineRecipeIndexCache"),
                "direct processing machine must own a local recipe index cache");
        assertTrue(indexCacheSource.contains("MAX_ENTRIES"),
                "direct processing recipe index cache must have a hard capacity bound");
        assertTrue(vanillaFurnaceMapping.contains("\"minecraft:smelting\""),
                "direct processing datapack mapping examples must include vanilla furnace smelting");
        assertTrue(mockCustomMapping.contains("\"minecraft:crafting_table\"")
                        && mockCustomMapping.contains("\"chexsonsaeutils:direct_processing_mock\""),
                "direct processing datapack mappings must include a mock machine to custom recipe type example");
        assertTrue(mockCustomRecipe.contains("\"type\": \"chexsonsaeutils:direct_processing_mock\"")
                        && mockCustomRecipe.contains("\"minecraft:coal\"")
                        && mockCustomRecipe.contains("\"minecraft:diamond\""),
                "direct processing custom recipe resource must exercise a non-vanilla recipe type");
        assertTrue(gameTestSource.contains("directProcessingDiscoversVanillaMachines"),
                "direct processing vanilla discovery GameTest must exist");
        assertTrue(gameTestSource.contains("directProcessingExecutesVanillaMachinesBackToAe"),
                "direct processing vanilla execution GameTest must verify return-to-AE behavior");
        assertTrue(gameTestSource.contains("directProcessingCampfireRequiresExplicitMapping"),
                "direct processing campfire cooking must require explicit mapping by GameTest");
        assertTrue(gameTestSource.contains("directProcessingEliminatesAeImportDeviceAndReducesItemContacts"),
                "direct processing value GameTest must prove AE return device and item contact reductions");
        assertTrue(gameTestSource.contains("directProcessingShortRecipeReturnsAtLeastTwoTicksFasterThanBaseline"),
                "direct processing latency GameTest must prove short recipes return at least two ticks faster");
        assertTrue(gameTestSource.contains("directProcessingCompletes10xThroughputWhenEnabled"),
                "direct processing throughput GameTest must exist behind a dedicated gate");
        assertTrue(gameTestSource.contains("chexsonsaeutils.directProcessingThroughputGameTest"),
                "direct processing throughput benchmark must not run by default");
        assertTrue(gameTestSource.contains("directProcessingPushHotPathUsesCacheFor1mSubmissionsWhenEnabled"),
                "direct processing 1M hot path benchmark must exist behind a dedicated gate");
        assertTrue(gameTestSource.contains("chexsonsaeutils.directProcessingMillionGameTest"),
                "direct processing 1M benchmark must not run by default");
        assertTrue(gameTestSource.contains("PUSH_CACHE_LOOKUP_P95_LIMIT_NANOS"),
                "direct processing push cache lookup P95 must have a local acceptance gate");
        assertTrue(gameTestSource.contains("directProcessingRetriesWhenStorageBackpressured"),
                "direct processing backpressure GameTest must exist");
        assertTrue(gameTestSource.contains("directProcessingConfigMappingAddsUnsupportedMachine"),
                "direct processing config mapping add GameTest must exist");
        assertTrue(gameTestSource.contains("directProcessingConfigMappingExecutesCustomRecipeType"),
                "direct processing config mapping must execute a mock machine custom recipe type");
        assertTrue(gameTestSource.contains("directProcessingMenuImportAppliesValidatedRecipeTypes"),
                "direct processing must GameTest the validated menu import path");
        assertTrue(gameTestSource.contains("directProcessingConfigMappingRemovalHidesCustomRecipeType"),
                "direct processing config mapping removal must hide custom recipe type outputs");
        assertTrue(gameTestSource.contains("directProcessingConfigMappingRemovalHidesPatterns"),
                "direct processing config mapping removal GameTest must exist");
        assertTrue(gameTestSource.contains("directProcessingNamingConventionAutoConfiguresHighConfidenceMachine"),
                "direct processing naming-convention auto-config GameTest must exist");
        assertTrue(gameTestSource.contains("directProcessingAutoConfiguresMekanismCrusher")
                        && gameTestSource.contains("directProcessingAutoConfiguresMekanismEnrichmentChamber")
                        && gameTestSource.contains("directProcessingAutoConfiguresMekanismEnergizedSmelter"),
                "direct processing Mekanism positive auto-config GameTests must exist");
        assertTrue(gameTestSource.contains("directProcessingAutoConfiguresIfeuInfuser"),
                "direct processing IFEU positive auto-config GameTest must exist");
        assertTrue(gameTestSource.contains("directProcessingMekanismSawmillStaysUnsupported")
                        && gameTestSource.contains("directProcessingMekanismChemicalMachineStaysUnsupported"),
                "direct processing Mekanism negative GameTests must exist");
        assertTrue(gameTestSource.contains("directProcessingMekanismBindingReusesRecipeIndex"),
                "direct processing Mekanism cache reuse GameTest must exist");
        assertTrue(gameTestSource.contains("restoreDirectProcessingConfigMappings(mappingSnapshot)"),
                "direct processing config mapping GameTests must restore temporary registry mappings");
        assertTrue(buildGradle.contains("compileOnly \"mezz.jei:jei-${minecraft_version}-neoforge:${jei_version}\""),
                "JEI API must be compile-only for the optional client hint bridge");
        assertTrue(buildGradle.contains("compileOnly \"mekanism:Mekanism:${mekanism_version}:api\""),
                "Mekanism API must be compile-only for local direct-processing support");
        assertTrue(buildGradle.contains("localRuntime \"mekanism:Mekanism:${mekanism_version}\""),
                "Mekanism runtime must be available for local development and direct machine GameTests");
        assertTrue(modsToml.contains("modId = \"jei\"")
                        && modsToml.contains("type = \"optional\"")
                        && modsToml.contains("side = \"CLIENT\""),
                "JEI dependency must be optional and client-scoped");
        assertTrue(modsToml.contains("modId = \"mekanism\"")
                        && modsToml.contains("type = \"optional\"")
                        && modsToml.contains("side = \"BOTH\""),
                "Mekanism dependency must stay optional and both-sided");
        assertTrue(jeiHintSource.contains("source")
                        && jeiHintSource.contains("JEI_HINT")
                        && jeiBridgeSource.contains("IJeiRuntime")
                        && jeiBridgeSource.contains("createRecipeCatalystLookup")
                        && jeiBridgeSource.contains("collectHintsForMachine")
                        && !jeiBridgeSource.contains("serverRecipeType == null"),
                "JEI bridge must expose client-side machine recipe type hints");
        assertTrue(jeiRuntimeHolderSource.contains("collectHintsForMachine")
                        && jeiRuntimeHolderSource.contains("collectVisibleHintsForMachine")
                        && jeiRuntimeHolderSource.contains("collectSignatureHintsForMachine")
                        && jeiRuntimeHolderSource.contains("IJeiRuntime"),
                "JEI runtime holder must keep runtime access inside the client integration package");
        assertTrue(jeiSignatureBridgeSource.contains("IRecipeLayoutBuilder")
                        && jeiSignatureBridgeSource.contains("setRecipe(builder, recipe, EmptyFocusGroup.INSTANCE)")
                        && jeiSignatureBridgeSource.contains("RecipeIngredientRole.CATALYST")
                        && jeiSignatureBridgeSource.contains("MachineRecipeImportedSignature"),
                "JEI signature bridge must capture category layouts into local imported signatures");
        assertTrue(userConfigStoreSource.contains("\"signatures\"")
                        && userConfigStoreSource.contains("直连处理机用户映射指引")
                        && userConfigStoreSource.contains("JEI 导入会同时尝试写入静态签名"),
                "user config store must persist imported JEI signatures and create a readable guide");
        assertTrue(discoveryServiceSource.contains("validateImportRequest")
                        && discoveryServiceSource.contains("loadImportedSignatures")
                        && discoveryServiceSource.contains("MachineRecipeImportedSignature")
                        && !discoveryServiceSource.contains("BuiltInRegistries.RECIPE_TYPE.get(hint.recipeTypeId())"),
                "discovery service must validate imported signatures and merge them into local indexes");
        assertTrue(blockEntitySource.contains("validateImportRequest")
                        && !blockEntitySource.contains("validateRecipeTypeIds("),
                "block entity menu import must use the unified import validation path");
        assertTrue(jeiPluginSource.contains("@JeiPlugin")
                        && jeiPluginSource.contains("onRuntimeAvailable")
                        && jeiPluginSource.contains("onRuntimeUnavailable"),
                "JEI plugin must capture and clear runtime availability locally");
        assertFalse(blockEntitySource.contains("JeiMachineRecipeTypeHintBridge"),
                "direct processing block entity must not load the JEI hint bridge");
        assertFalse(menuSource.contains("JeiMachineRecipeTypeHintBridge")
                        || menuSource.contains("JeiRuntimeHolder"),
                "server-synced menu must not load JEI runtime classes");
        assertTrue(candidateResolverSource.contains("toAutomaticCandidates")
                        && !candidateResolverSource.contains("automaticCandidates.size() == 1"),
                "generic auto-config must not require a unique naming candidate");
        assertTrue(discoveryServiceSource.contains("collectSupportedCandidates")
                        && discoveryServiceSource.contains("validateRecipeTypeIds")
                        && discoveryServiceSource.contains("validateImportRequest")
                        && discoveryServiceSource.contains("loadImportedSignatures"),
                "discovery service must validate candidate sets and merge imported JEI signatures");
        assertTrue(userConfigStoreSource.contains("direct_processing_machines.guide.md")
                        && userConfigStoreSource.contains("upsertMappingAndApply")
                        && userConfigStoreSource.contains("buildDefaultConfigTemplate")
                        && userConfigStoreSource.contains("guidePathForUi"),
                "user config store must generate guidance files and apply imports immediately");
        assertTrue(mekanismAdapterSource.contains("MachineRecipeCandidateSource.EXPLICIT_ADAPTER")
                        && mekanismAdapterSource.contains("ModList.get().isLoaded")
                        && mekanismAdapterSource.contains("MekanismDirectProcessingSupport"),
                "Mekanism direct machine support must stay behind a local explicit adapter gate");
        assertTrue(mekanismSupportSource.contains("crusher")
                        && mekanismSupportSource.contains("enrichment_chamber")
                        && mekanismSupportSource.contains("energized_smelter"),
                "Mekanism support must whitelist the direct item-to-item machine set");
        assertTrue(mekanismShapeSupportSource.contains("ItemStackToItemStackRecipe")
                        && mekanismShapeSupportSource.contains("getInput()")
                        && mekanismShapeSupportSource.contains("getOutputDefinition()"),
                "Mekanism shape support must use public item-to-item recipe APIs");
        assertTrue(enUs.has("block.chexsonsaeutils.ae_direct_processing_machine"),
                "English translations must include the direct processing machine");
        assertTrue(enUs.has("gui.chexsonsaeutils.ae_direct_processing_machine.recipe_types")
                        && enUs.has("gui.chexsonsaeutils.ae_direct_processing_machine.visible_status")
                        && enUs.has("gui.chexsonsaeutils.ae_direct_processing_machine.binding_slot_label")
                        && enUs.has("gui.chexsonsaeutils.ae_direct_processing_machine.binding_slot_tooltip")
                        && enUs.has("gui.chexsonsaeutils.ae_direct_processing_machine.jei_import_button")
                        && enUs.has("gui.chexsonsaeutils.ae_direct_processing_machine.reason.identified_recipe_type_unreadable"),
                "English translations must include direct machine recipe types and support status");
        assertTrue(zhCn.has("block.chexsonsaeutils.ae_direct_processing_machine"),
                "Chinese translations must include the direct processing machine");
        assertTrue(zhCn.has("gui.chexsonsaeutils.ae_direct_processing_machine.recipe_types")
                        && zhCn.has("gui.chexsonsaeutils.ae_direct_processing_machine.visible_status")
                        && zhCn.has("gui.chexsonsaeutils.ae_direct_processing_machine.binding_slot_label")
                        && zhCn.has("gui.chexsonsaeutils.ae_direct_processing_machine.binding_slot_tooltip")
                        && zhCn.has("gui.chexsonsaeutils.ae_direct_processing_machine.jei_import_button")
                        && zhCn.has("gui.chexsonsaeutils.ae_direct_processing_machine.reason.identified_recipe_type_unreadable"),
                "Chinese translations must include direct machine recipe types and support status");
        assertFalse(enUs.has("gui.chexsonsaeutils.ae_direct_processing_machine.first_pattern_status"),
                "English translations must not keep removed first-pattern diagnostic copy");
        assertFalse(zhCn.has("gui.chexsonsaeutils.ae_direct_processing_machine.first_pattern_status"),
                "Chinese translations must not keep removed first-pattern diagnostic copy");
    }

    @Test
    void directProcessingHotPathDoesNotScanRecipesOrReflect() throws IOException {
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/directprocessing/AEDirectProcessingMachineBlockEntity.java"
        ));
        String pushPatternBody = methodBody(blockEntitySource, "public boolean pushPattern");
        String serverTickBody = methodBody(blockEntitySource, "private void serverTick");
        String getAvailablePatternsBody = methodBody(blockEntitySource, "public List<IPatternDetails> getAvailablePatterns");

        assertTrue(pushPatternBody.contains("compatibilityCache.get("),
                "pushPattern must use PatternCompatibilityCache lookup");
        assertTrue(pushPatternBody.contains("compatibility.signature()"),
                "pushPattern must compile from the cached matched recipe signature");
        assertFalse(pushPatternBody.contains("recipeIndex,"),
                "pushPattern must not pass MachineRecipeIndex into task compilation");
        assertFalse(pushPatternBody.contains("getRecipeManager"),
                "pushPattern must not scan recipe manager");
        assertFalse(pushPatternBody.contains("MachineRecipeDiscoveryService"),
                "pushPattern must not run discovery");
        assertFalse(pushPatternBody.contains("Class.forName"),
                "pushPattern must not reflect");
        assertFalse(pushPatternBody.contains("getDeclared"),
                "pushPattern must not reflect");
        assertFalse(pushPatternBody.contains("GenericRecipeShapeReader"),
                "pushPattern must not read recipe shapes");
        assertFalse(pushPatternBody.contains("readStaticItemRecipe"),
                "pushPattern must not read recipe shapes");
        assertFalse(pushPatternBody.contains("getIngredients"),
                "pushPattern must not inspect recipe inputs");
        assertFalse(pushPatternBody.contains("getResultItem"),
                "pushPattern must not inspect recipe outputs");
        assertFalse(pushPatternBody.contains("JEI"),
                "pushPattern must not use client integration hints");
        assertFalse(pushPatternBody.contains("REI"),
                "pushPattern must not use client integration hints");

        assertFalse(serverTickBody.contains("getRecipeManager"),
                "serverTick must not scan recipe manager");
        assertFalse(serverTickBody.contains("MachineRecipeDiscoveryService"),
                "serverTick must not run discovery");
        assertFalse(serverTickBody.contains("GenericRecipeShapeReader"),
                "serverTick must not read recipe shapes");
        assertFalse(serverTickBody.contains("readStaticItemRecipe"),
                "serverTick must not read recipe shapes");
        assertFalse(serverTickBody.contains("getDeclared"),
                "serverTick must not reflect");
        assertTrue(serverTickBody.contains("refreshDirtyPatterns();"),
                "serverTick must own budgeted dirty pattern refresh outside AE provider callbacks");
        assertTrue(getAvailablePatternsBody.contains("return patternProvider.availablePatterns();"),
                "getAvailablePatterns must return the local direct provider cache directly");
        assertFalse(getAvailablePatternsBody.contains("refreshDirtyPatterns"),
                "getAvailablePatterns must not decode or compile dirty slots synchronously");
        assertFalse(getAvailablePatternsBody.contains("getRecipeManager"),
                "getAvailablePatterns must not scan recipe manager");
        assertFalse(getAvailablePatternsBody.contains("GenericRecipeShapeReader"),
                "getAvailablePatterns must not read recipe shapes");
        assertFalse(getAvailablePatternsBody.contains("readStaticItemRecipe"),
                "getAvailablePatterns must not read recipe shapes");
    }

    @Test
    void discoveryIsLocalAndServerSideOnly() throws IOException {
        String discoverySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeDiscoveryService.java"
        ));
        String resolverSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/RecipeTypeCandidateResolver.java"
        ));
        String shapeReaderSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/GenericRecipeShapeReader.java"
        ));
        String indexCacheSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeIndexCache.java"
        ));
        String signatureSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/RecipeSignature.java"
        ));
        String signatureKeySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/RecipeSignatureKey.java"
        ));
        String stackSupportSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/DirectProcessingStackSupport.java"
        ));
        String adapterRegistrySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineAdapterRegistry.java"
        ));
        String mekanismAdapterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/mekanism/MekanismMachineRecipeTypeAdapter.java"
        ));
        String mekanismShapeSupportSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/mekanism/MekanismRecipeShapeSupport.java"
        ));
        String kindSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeKind.java"
        ));
        String identitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineIdentity.java"
        ));
        String modSource = readUtf8(javaSource("git/chexson/chexsonsaeutils/Chexsonsaeutils.java"));
        String mixinConfig = readUtf8(resourcePath("chexsonsaeutils.mixins.json"));

        assertTrue(discoverySource.contains("getAllRecipesFor(recipeType)"),
                "discovery must build its local recipe index from RecipeManager only during discovery");
        assertTrue(discoverySource.contains("buildSupportedIndex")
                        && discoverySource.contains("collectSupportedCandidates")
                        && discoverySource.contains("result.recipeTypeIds()")
                        && discoverySource.contains("buildImportedOrUnsupportedIndex"),
                "direct discovery cold path must keep only validated recipe type ids in the supported index");
        assertTrue(indexCacheSource.contains("MachineRecipeIndexCache")
                        && indexCacheSource.contains("record Key(MachineIdentity identity"),
                "direct processing recipe index cache must be keyed by local machine identity and epochs");
        assertTrue(indexCacheSource.contains("withVersion(machineIndexVersion)"),
                "shared recipe index cache must rewrap entries with each machine's local index version");
        assertTrue(indexCacheSource.contains("trimToMaxEntries"),
                "shared recipe index cache must evict instead of growing without bound");
        assertTrue(discoverySource.contains("shapeReader.readStaticItemRecipe"),
                "discovery must read recipe shapes only while building the local recipe index");
        assertTrue(discoverySource.contains("catch (RuntimeException ignored)"),
                "single recipe shape read failures must not abort the direct machine index rebuild");
        assertFalse(methodBody(discoverySource, "public PatternCompatibility compileCompatibility")
                        .contains("shapeReader"),
                "pattern compatibility compilation must use the built index instead of reading recipe shapes");
        assertFalse(methodBody(discoverySource, "public PatternCompatibility compileCompatibility")
                        .contains("getRecipeManager"),
                "pattern compatibility compilation must not scan recipe manager");
        assertTrue(discoverySource.contains("RecipeTypeCandidateResolver"),
                "discovery must delegate recipe type resolution to a local resolver");
        assertTrue(discoverySource.contains("GenericRecipeShapeReader"),
                "discovery must delegate recipe shape reading to a local reader");
        assertTrue(discoverySource.contains("MachineAdapterRegistry.directProcessingDefaults()"),
                "direct machine discovery must use the local default adapter registry");
        assertTrue(discoverySource.contains("AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED")
                        && discoverySource.contains("AE_DIRECT_PROCESSING_MACHINE_REFLECTIVE_DISCOVERY_ENABLED"),
                "direct machine discovery must consume local generic/reflective kill switches");
        assertFalse(discoverySource.contains("MachineRecipeKind.values()"),
                "discovery service must not hard-code recipe kind scanning");
        assertTrue(resolverSource.contains("MachineAdapterRegistry"),
                "candidate resolver must keep the explicit adapter extension point");
        assertTrue(resolverSource.contains("MachineRecipeCandidateSource.EXPLICIT_ADAPTER"),
                "candidate resolver must reserve explicit adapter priority");
        assertTrue(resolverSource.contains("MachineRecipeConfigMappingRegistry"),
                "candidate resolver must support local config mapping before generic fallback");
        assertTrue(resolverSource.contains("MachineRecipeCandidateSource.CONFIG_MAPPING"),
                "candidate resolver must expose the local config mapping path");
        assertTrue(resolverSource.contains("MachineRecipeCandidateSource.GENERIC_RECIPE_TYPE"),
                "candidate resolver must expose the generic RecipeType path");
        assertTrue(resolverSource.contains("genericDiscoveryEnabled")
                        && resolverSource.contains("if (genericDiscoveryEnabled)"),
                "candidate resolver must skip generic and naming-convention paths when disabled");
        assertFalse(kindSource.contains("CAMPFIRE"),
                "campfire cooking must not be part of default generic direct-machine mappings");
        assertTrue(resolverSource.contains("toAutomaticCandidates")
                        && !resolverSource.contains("automaticCandidates.size() == 1"),
                "naming-convention matches must no longer require a unique candidate before local validation");
        assertTrue(resolverSource.contains("resolveNamingConventionHints"),
                "candidate resolver must keep naming convention matching local to the direct machine resolver");
        assertTrue(discoverySource.contains("validateRecipeTypeIds")
                        && discoverySource.contains("MachineSupportReasonCode.NAMING_CONVENTION_NEEDS_MAPPING"),
                "naming-convention candidates must be validated in discovery and only fall back to diagnostics there");
        assertTrue(shapeReaderSource.contains("MAX_CANDIDATE_INPUTS_PER_RECIPE"),
                "generic candidate expansion must stay bounded");
        assertTrue(shapeReaderSource.contains("reflectionShapeReaders"),
                "reflective shape reading handles must be cached by recipe class");
        assertTrue(shapeReaderSource.contains("reflectiveDiscoveryEnabled"),
                "generic shape reader must support disabling reflective discovery");
        assertTrue(shapeReaderSource.contains("readCodecShape(level.registryAccess(), recipe, identity)")
                        && shapeReaderSource.contains("resolveRecipeCodec")
                        && shapeReaderSource.contains("JsonOps.INSTANCE"),
                "generic fallback must merge serializer codec and static CODEC json shapes into server discovery");
        assertTrue(shapeReaderSource.contains("getDeclaredFields"),
                "generic fallback must support field-only reflective shape discovery");
        assertTrue(shapeReaderSource.contains("AccessorReflectionMember")
                        && shapeReaderSource.contains("method.invoke(")
                        && shapeReaderSource.contains("currentClass.getSuperclass()"),
                "generic fallback must support cached accessor reads and inherited reflective members");
        assertTrue(shapeReaderSource.contains("contextMembers")
                        && shapeReaderSource.contains("contextMatchesIdentity")
                        && discoverySource.contains("collectSupportedCandidates(level, identity, candidates)")
                        && discoverySource.contains("readStaticItemRecipeOutcome(level, candidate, recipe, identity)"),
                "generic fallback must allow machine-context matching for locally controlled direct-processing discovery");
        assertTrue(shapeReaderSource.contains("Optional<?> optional")
                        && shapeReaderSource.contains("block.asItem()")
                        && shapeReaderSource.contains("handitem"),
                "generic fallback must unwrap Optional values and support block or hand-item style recipe members");
        assertTrue(shapeReaderSource.contains("hasUnsafeDynamicMembers"),
                "generic fallback must reject dynamic/random/byproduct recipe shapes");
        assertTrue(shapeReaderSource.contains("ShapeReadOutcome.unsafeDynamic()"),
                "generic fallback must return an explicit unsafe-dynamic outcome for dynamic recipes");
        assertTrue(discoverySource.contains("MachineSupportStatus.UNSAFE_DYNAMIC"),
                "discovery must propagate unsafe dynamic recipe shapes to machine support status");
        assertTrue(discoverySource.contains("MachineSupportReasonCode.DYNAMIC_RECIPE_SHAPE"),
                "discovery must preserve an explicit unsafe dynamic reason code");
        assertTrue(shapeReaderSource.contains("RecipeShape.unreadable()"),
                "generic fallback must degrade unreadable shapes without exposing signatures");
        assertFalse(shapeReaderSource.contains("Math.multiplyExact(candidateCount"),
                "generic candidate expansion must not overflow on large ingredient choice counts");
        assertTrue(shapeReaderSource.contains("expandSignatures"),
                "generic recipe reader must support bounded multi-input shape expansion");
        assertTrue(signatureSource.contains("List<RecipeSignatureInput> inputs"),
                "recipe signatures must preserve deterministic multi-input recipes");
        assertTrue(signatureKeySource.contains("List<RecipeSignatureInput> inputs"),
                "recipe signature lookup keys must support deterministic multi-input recipes");
        assertTrue(signatureSource.contains("normalizeSignatureInputs(inputs)")
                        && signatureKeySource.contains("normalizeSignatureInputs(inputs)")
                        && stackSupportSource.contains("normalizeSignatureInputs"),
                "direct processing signatures and lookup keys must normalize duplicate inputs through one helper");
        assertTrue(adapterRegistrySource.contains("interface MachineRecipeAdapter"),
                "adapter registry must expose a local direct-machine adapter contract");
        assertTrue(adapterRegistrySource.contains("directProcessingDefaults")
                        && adapterRegistrySource.contains("MekanismMachineRecipeTypeAdapter"),
                "adapter registry must assemble Mekanism support only inside the local direct-processing defaults");
        assertTrue(mekanismAdapterSource.contains("EXPLICIT_ADAPTER")
                        && mekanismAdapterSource.contains("resolveRecipeType")
                        && mekanismAdapterSource.contains("MekanismDirectProcessingSupport.MOD_ID"),
                "Mekanism adapter must resolve whitelist machines as explicit local candidates");
        assertTrue(mekanismShapeSupportSource.contains("ItemStackToItemStackRecipe")
                        && mekanismShapeSupportSource.contains("getRepresentations()")
                        && mekanismShapeSupportSource.contains("getNeededAmount("),
                "Mekanism shape support must derive deterministic signatures from public input representations");
        assertTrue(identitySource.contains("blockEntityTypeId"),
                "machine identity must reserve block entity type identity");
        assertTrue(identitySource.contains("capabilitySummary"),
                "machine identity must reserve capability summary identity");
        assertTrue(identitySource.contains("configProfileId"),
                "machine identity must reserve config profile identity");
        assertTrue(discoverySource.contains("MachineSupportStatus.NEEDS_CONFIG_MAPPING"),
                "unknown generic machines must expose NEEDS_CONFIG_MAPPING diagnostics");
        assertTrue(discoverySource.contains("PatternCompatibility.supported(index.status(), pattern, signature)"),
                "generic support must retain the matched signature for push hot paths");
        assertTrue(discoverySource.contains("if (index.status() == MachineSupportStatus.UNSAFE_DYNAMIC)"),
                "unsafe dynamic indexes must stay unsafe instead of degrading to mapping-missing compatibility");
        assertFalse(discoverySource.contains("JEI"),
                "server discovery must not depend on JEI");
        assertFalse(discoverySource.contains("REI"),
                "server discovery must not depend on REI");
        assertFalse(modSource.contains("MachineRecipeDiscoveryService"),
                "direct processing discovery must not be registered as a global AE2 service");
        assertFalse(modSource.contains("MachineRecipeIndexCache"),
                "direct processing index cache must not be registered as a global AE2 service");
        assertFalse(mixinConfig.contains("directprocessing"),
                "direct processing machine must not add AE2 global mixins");
    }

    @Test
    void ifeuCodecFieldPatternsStayCoveredByGenericJsonFallback() throws IOException {
        String shapeReaderSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/GenericRecipeShapeReader.java"
        ));
        String infuserRecipeSource = readUtf8(projectPath(
                "reference-sources",
                "IndustrialForegoingExtraUpgrades",
                "src",
                "main",
                "java",
                "net",
                "yxiao233",
                "ifeu",
                "common",
                "recipe",
                "InfuserRecipe.java"
        ));
        String blockRightClickRecipeSource = readUtf8(projectPath(
                "reference-sources",
                "IndustrialForegoingExtraUpgrades",
                "src",
                "main",
                "java",
                "net",
                "yxiao233",
                "ifeu",
                "common",
                "recipe",
                "BlockRightClickRecipe.java"
        ));
        String arcaneRecipeSource = readUtf8(projectPath(
                "reference-sources",
                "IndustrialForegoingExtraUpgrades",
                "src",
                "main",
                "java",
                "net",
                "yxiao233",
                "ifeu",
                "common",
                "recipe",
                "ArcaneDragonEggForgingRecipe.java"
        ));

        assertTrue(infuserRecipeSource.contains("ItemStack.CODEC.fieldOf(\"input\")")
                        && infuserRecipeSource.contains("FluidStack.CODEC.fieldOf(\"inputFluid\")")
                        && infuserRecipeSource.contains("ItemStack.CODEC.fieldOf(\"output\")"),
                "IFEU infuser recipe must stay in the item plus fluid plus output codec shape family");
        assertTrue(blockRightClickRecipeSource.contains("ItemStack.CODEC.fieldOf(\"handItem\")")
                        && blockRightClickRecipeSource.contains("Block.CODEC.fieldOf(\"block\")")
                        && blockRightClickRecipeSource.contains("Block.CODEC.fieldOf(\"result\")"),
                "IFEU block-right-click recipe must stay in the hand-item plus context-block codec shape family");
        assertTrue(arcaneRecipeSource.contains("FluidStack.CODEC.fieldOf(\"inputFluid1\")")
                        && arcaneRecipeSource.contains("FluidStack.CODEC.fieldOf(\"inputFluid2\")")
                        && arcaneRecipeSource.contains("ItemStack.CODEC.optionalFieldOf(\"output\")")
                        && arcaneRecipeSource.contains("FluidStack.CODEC.optionalFieldOf(\"outputFluid\")"),
                "IFEU arcane recipe must keep the dual-fluid plus optional output codec shape family");
        assertTrue(shapeReaderSource.contains("\"inputfluid\"")
                        && shapeReaderSource.contains("\"handitem\"")
                        && shapeReaderSource.contains("\"block\"")
                        && shapeReaderSource.contains("\"result\"")
                        && shapeReaderSource.contains("\"output\"")
                        && shapeReaderSource.contains("\"fluid\"")
                        && shapeReaderSource.contains("readJsonContextMatches")
                        && shapeReaderSource.contains("Optional<?> optional"),
                "generic json fallback must keep the field-name coverage needed by current IFEU codec recipes");
    }

    @Test
    void directProcessingReflectionIsConfinedToDiscoveryShapeReader() throws IOException {
        String shapeReaderSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/GenericRecipeShapeReader.java"
        ));
        String mekanismShapeSupportSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/mekanism/MekanismRecipeShapeSupport.java"
        ));
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/directprocessing/AEDirectProcessingMachineBlockEntity.java"
        ));
        String taskSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/ProcessingCompiledTask.java"
        ));
        String queueSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/ProcessingExecutionQueue.java"
        ));
        String cacheSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/PatternCompatibilityCache.java"
        ));

        assertTrue(shapeReaderSource.contains("java.lang.reflect.Field"),
                "field reflection must stay inside the local generic recipe shape reader");
        assertTrue(shapeReaderSource.contains("java.lang.reflect.Method"),
                "accessor reflection must stay inside the local generic recipe shape reader");
        assertFalse(mekanismShapeSupportSource.contains("java.lang.reflect"),
                "Mekanism direct recipe shape support must not use reflection");
        assertFalse(blockEntitySource.contains("java.lang.reflect"),
                "direct machine block entity must not own reflective recipe discovery");
        assertFalse(taskSource.contains("java.lang.reflect"),
                "compiled task hot path must not reflect");
        assertFalse(queueSource.contains("java.lang.reflect"),
                "execution queue hot path must not reflect");
        assertFalse(cacheSource.contains("java.lang.reflect"),
                "compatibility cache hot path must not reflect");
    }

    @Test
    void directProcessingLocalCachesStayBoundedAndObservable() throws IOException {
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/directprocessing/AEDirectProcessingMachineBlockEntity.java"
        ));
        String schedulerSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/DirtySlotPatternRefreshScheduler.java"
        ));
        String compatibilitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/PatternCompatibility.java"
        ));
        String compatibilityCacheSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/PatternCompatibilityCache.java"
        ));
        String metricsSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/DirectProcessingMachineMetrics.java"
        ));
        String valueBaselineSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/DirectProcessingValueBaselineModel.java"
        ));
        String indexSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeIndex.java"
        ));

        assertTrue(blockEntitySource.contains("DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK"),
                "dirty pattern decode/compile must have a per-tick local budget");
        assertTrue(blockEntitySource.contains("drainDirtySlots(DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK)"),
                "direct machine must not refresh all virtual pattern slots in one provider callback");
        assertTrue(schedulerSource.contains("public List<Integer> drainDirtySlots(int maxSlots)"),
                "dirty scheduler must support bounded drains");
        assertTrue(compatibilityCacheSource.contains("public void remove(AEItemKey patternDefinition"),
                "single-slot invalidation must avoid clearing all cached compatible patterns");
        assertTrue(blockEntitySource.contains("removeCachedCompatibilityForSlot"),
                "direct machine must invalidate compatibility cache by slot");
        assertTrue(blockEntitySource.contains("getPatternStatusForTest"),
                "pattern support status must be observable for GUI and tests");
        assertTrue(blockEntitySource.contains("getPatternReasonCodeForTest"),
                "pattern support reason code must be observable for GUI and tests");
        assertTrue(blockEntitySource.contains("countVisiblePatternStatusForMenu"),
                "visible page status counts must be observable for the direct processing GUI");
        assertTrue(blockEntitySource.contains("getVisiblePatternStatusSnapshotForMenu"),
                "direct processing machine must expose a per-page compatibility snapshot for menu sync");
        assertTrue(blockEntitySource.contains("setActivePage"),
                "direct processing machine must expose page navigation for all virtual pattern slots");
        assertTrue(blockEntitySource.contains("invalidateDiscoveryForRecipeReload"),
                "recipe reload invalidation must stay local to direct processing machines");
        assertTrue(blockEntitySource.contains("MachineRecipeConfigMappingRegistry.instance().epoch()"),
                "config mapping changes must invalidate only the direct processing machine index");
        assertTrue(blockEntitySource.contains("MachineRecipeReloadTracker.recipeReloadEpoch()"),
                "recipe reload changes must invalidate only the direct processing machine index");
        assertTrue(blockEntitySource.contains("observedRecipeReloadEpoch"),
                "direct processing machine must remember the last observed recipe reload epoch");
        assertTrue(blockEntitySource.contains("markMachineRecipeIndexDirty"),
                "direct processing machine must defer recipe index rebuilds after dirty events");
        assertTrue(blockEntitySource.contains("refreshMachineRecipeIndexIfReady"),
                "direct processing machine must rebuild recipe indexes only from its local guarded refresh path");
        assertTrue(blockEntitySource.contains("machineRecipeIndexRefreshPending"),
                "direct processing machine must track local pending recipe index refresh state");
        assertTrue(blockEntitySource.contains("isMachineRecipeIndexRefreshPendingForTest"),
                "direct processing machine must expose pending index refresh state for runtime acceptance tests");
        assertTrue(blockEntitySource.contains("getDirtyRefreshWallNanosMaxForTest"),
                "direct processing machine must expose local dirty refresh timing for performance acceptance");
        assertTrue(blockEntitySource.contains("getPushPatternCacheLookupNanosMaxForTest"),
                "direct processing machine must expose local push cache lookup timing for performance acceptance");
        assertTrue(blockEntitySource.contains("DirectProcessingMachineMetrics"),
                "direct processing machine metrics must stay local to the direct machine package");
        assertTrue(blockEntitySource.contains("getMetricsSnapshotForTest"),
                "direct processing machine must expose a local metrics snapshot for performance acceptance");
        assertTrue(blockEntitySource.contains("getDirtyRefreshNanosP95ForTest"),
                "direct processing machine must expose dirty refresh P95 timing");
        assertTrue(blockEntitySource.contains("getPushPatternCacheLookupNanosP95ForTest"),
                "direct processing machine must expose push cache lookup P95 timing");
        assertTrue(blockEntitySource.contains("getOutputReturnNanosP95ForTest"),
                "direct processing machine must expose output return P95 timing");
        assertTrue(blockEntitySource.contains("getServerTickNanosP95ForTest"),
                "direct processing machine must expose server tick P95 timing");
        assertTrue(blockEntitySource.contains("getPushToAeReturnLatencyTicksAverageForTest"),
                "direct processing machine must expose push-to-AE-return average latency ticks");
        assertTrue(blockEntitySource.contains("getPushToAeReturnLatencySampleCountForTest"),
                "direct processing machine must expose push-to-AE-return latency sample count");
        assertTrue(metricsSource.contains("SAMPLE_SIZE")
                        && metricsSource.contains("dirtyRefreshNanos")
                        && metricsSource.contains("pushPatternCacheLookupNanos")
                        && metricsSource.contains("outputReturnNanos")
                        && metricsSource.contains("outputReturnLatencyTicks")
                        && metricsSource.contains("serverTickNanos"),
                "direct processing metrics must use bounded local sample windows for hot-path timings");
        assertTrue(valueBaselineSource.contains("DIRECT_AE_RETURN_DEVICE_COUNT = 0")
                        && valueBaselineSource.contains("ORIGINAL_AE_RETURN_DEVICE_COUNT = 1")
                        && valueBaselineSource.contains("ORIGINAL_RETURN_OVERHEAD_TICKS = 2"),
                "direct processing value baseline must encode the local import-device and return-latency gates");
        assertTrue(compatibilitySource.contains("unsupported(MachineSupportStatus status"),
                "unsupported compatibility must preserve non-readable, unsafe, and needs-config states");
        assertTrue(indexSource.contains("Map<RecipeSignatureKey, RecipeSignature> signatureLookup"),
                "machine recipe index must use fixed-time signature lookup");
        assertTrue(indexSource.contains("findSignature("),
                "machine recipe index must expose matched signatures");
        assertTrue(indexSource.contains("findScaledMatch(")
                        && indexSource.contains("signatureShapeLookup"),
                "machine recipe index must keep a local scaled-pattern match path beside exact lookup");
        assertTrue(indexSource.contains("List<ResourceLocation> recipeTypeIds"),
                "machine recipe index must cache discovered recipe type ids for the local GUI");
    }

    @Test
    void directProcessingExecutionModelKeepsBackoffAndCoalescing() throws IOException {
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/directprocessing/AEDirectProcessingMachineBlockEntity.java"
        ));
        String queueSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/ProcessingExecutionQueue.java"
        ));
        String latencyOriginSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/ProcessingLatencyOrigin.java"
        ));
        String budgetSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/ProcessingExecutionBudgetController.java"
        ));
        String laneSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/ProcessingExecutionLane.java"
        ));
        String taskSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/ProcessingCompiledTask.java"
        ));
        String discoverySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/directprocessing/MachineRecipeDiscoveryService.java"
        ));

        assertTrue(blockEntitySource.contains("MAX_PENDING_OUTPUT_RETRY_DELAY_TICKS"),
                "failed output returns must have a bounded retry backoff");
        assertTrue(blockEntitySource.contains("createConfiguredBudgetController()"),
                "direct machine must own a local configurable execution budget controller");
        assertTrue(blockEntitySource.contains("budgetController.resetForTick("),
                "direct machine must reset its local execution budget every server tick");
        assertFalse(blockEntitySource.contains("!budgetController.tryClaimAdmit() || !executionQueue.offer"),
                "pushPattern must not double-charge admission before queue lane assignment");
        assertFalse(blockEntitySource.contains("claimAeReturnBudget"),
                "AE return must not consume a direct-machine output return budget");
        assertFalse(blockEntitySource.contains("buildReturnSlice"),
                "AE return must not slice direct-machine payloads by AE return budget");
        assertFalse(blockEntitySource.contains("mergeRejectedSliceWithRemaining"),
                "AE return must not keep unattempted payload because of AE return budgeting");
        assertTrue(blockEntitySource.contains("ArrayDeque<PendingOutputBatch> pendingOutputBatches"),
                "completed direct-machine output must be queued as local pending output batches");
        assertTrue(blockEntitySource.contains("MAX_PENDING_OUTPUT_BATCHES"),
                "pending direct-machine output batches must have a local hard bound");
        assertTrue(blockEntitySource.contains("enqueuePendingOutput(task.buildOutputPayload(), latencyOrigin)"),
                "completed direct-machine tasks must append output batches instead of overwriting a single payload");
        assertTrue(blockEntitySource.contains("pendingOutputBatches.addFirst(batch.withPayload(remainingPayload))"),
                "partial output return failure must keep only the failed batch remainder at the front");
        assertTrue(blockEntitySource.contains("outputSink.tryReturnPayload(")
                        && blockEntitySource.contains("batch.payload()"),
                "AE return must try flushing the full direct-machine batch payload without budgeting slices");
        assertTrue(blockEntitySource.contains("while (!pendingOutputBatches.isEmpty()"),
                "direct machine output return must drain multiple local batches per tick budget");
        assertFalse(blockEntitySource.contains("pendingOutputPayload"),
                "direct machine must not keep the old single pending output payload field");
        assertTrue(budgetSource.contains("tryClaimAeReturn(long amount, int operations)"),
                "AE return budget must charge both amount and operation count per local slice");
        assertTrue(blockEntitySource.contains("pendingOutputRetryBackoffTicks"),
                "retry backoff must be tracked separately from the current delay");
        assertTrue(blockEntitySource.contains("nextPendingOutputRetryDelay"),
                "direct machine must use exponential output retry backoff");
        assertTrue(blockEntitySource.contains("return !pendingOutputBatches.isEmpty() || !executionQueue.isIdle();"),
                "provider busy state must reflect active direct-machine work");
        assertTrue(queueSource.contains("COALESCE_SEARCH_WINDOW"),
                "coalescing must search a bounded local window instead of only the tail");
        assertTrue(queueSource.contains("budgetController.maxCoalescedExecutions()"),
                "queue coalescing cap must come from the local execution budget");
        assertTrue(queueSource.contains("budgetController.tryClaimComplete()"),
                "queue completions must consume local complete budget");
        assertFalse(queueSource.contains("host.isWaitingForOutputReturn()"),
                "queue must not stop after the first completed batch while output return is pending");
        assertTrue(queueSource.contains("budgetController.tryClaimAdmit()"),
                "queue lane assignment must consume local admit budget");
        assertTrue(queueSource.contains("budgetController.hasTimeBudget(System.nanoTime())"),
                "queue tick must obey a wall-clock budget");
        assertTrue(queueSource.contains("tryCoalescePendingTask"),
                "queue must coalesce compatible direct processing tasks");
        assertTrue(queueSource.contains("ProcessingLatencyOrigin")
                        && queueSource.contains("latencyOrigins.merge"),
                "queue must carry local push tick origins across coalesced tasks");
        assertTrue(latencyOriginSource.contains("averageLatencyTicks"),
                "latency origin must calculate tick latency only after AE output return succeeds");
        assertTrue(blockEntitySource.contains("recordPushToAeReturnLatency"),
                "direct machine must record latency only when pending output is returned");
        assertTrue(queueSource.contains("lane.activeTask()"),
                "running lane tasks must be persisted instead of dropped on save");
        assertTrue(laneSource.contains("public ProcessingCompiledTask activeTask()"),
                "execution lane must expose its active task for queue persistence");
        assertTrue(taskSource.contains("tryAppendExecutionCount"),
                "compiled task must support logical execution coalescing");
        assertTrue(taskSource.contains("MAX_EXECUTION_COUNT"),
                "compiled task must clamp restored and coalesced execution counts locally");
        assertTrue(taskSource.contains("multiplyOrZero"),
                "compiled task must avoid ArithmeticException while building coalesced output payloads");
        assertTrue(taskSource.contains("canBuildOutputPayloadFor"),
                "compiled task coalescing must reject execution counts that cannot build a valid payload");
        assertTrue(taskSource.contains("scaleStacks(outputsPerExecution, executionCount)")
                        && taskSource.contains("normalizedPatternOutputs"),
                "compiled task must validate scaled pattern outputs against per-execution signature outputs");
        assertFalse(taskSource.contains("Math.multiplyExact(output.amount()"),
                "compiled task output multiplication must not throw through the direct machine tick path");
        assertTrue(discoverySource.contains("multiplyOrZero"),
                "direct machine pattern compatibility must avoid overflowing deterministic input amounts");
        assertTrue(taskSource.contains("public int executionCount()"),
                "compiled task must expose coalesced logical execution count for local direct-machine metrics");
        assertTrue(blockEntitySource.contains("getCompletedLogicalExecutionCountForTest"),
                "direct processing machine must expose completed logical execution count");
        assertTrue(taskSource.contains("deriveExecutionCount(selectedInputs, signature.inputs())")
                        && taskSource.contains("DirectProcessingStackSupport.toGenericStacks(signature.inputs())"),
                "compiled task must derive logical executions from the cached base signature");
        assertFalse(taskSource.contains("MachineRecipeIndex index"),
                "compiled task must not receive MachineRecipeIndex on the push hot path");
        assertTrue(budgetSource.contains("admitTokens"),
                "budget controller must expose admit tokens");
        assertTrue(budgetSource.contains("completeTokens"),
                "budget controller must expose complete tokens");
        assertTrue(budgetSource.contains("aeReturnOps"),
                "budget controller must expose AE return operation tokens");
        assertTrue(budgetSource.contains("aeReturnAmount"),
                "budget controller must expose AE return amount budget");
        assertTrue(budgetSource.contains("wallNanos"),
                "budget controller must expose a wall-clock budget");
        assertTrue(budgetSource.contains("BENCHMARK_LIMITS"),
                "budget controller must reserve a benchmark ceiling profile");
        assertTrue(budgetSource.contains("forProfile"),
                "budget controller must expose normal/high/benchmark profile selection");
    }

    private static JsonObject readLang(Path path) throws IOException {
        return JsonParser.parseString(readUtf8(path)).getAsJsonObject();
    }

    private static String methodBody(String source, String signatureStart) {
        int start = source.indexOf(signatureStart);
        assertTrue(start >= 0, () -> "Missing method: " + signatureStart);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, () -> "Missing method body: " + signatureStart);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, index + 1);
                }
            }
        }
        throw new AssertionError("Unclosed method body: " + signatureStart);
    }
}
