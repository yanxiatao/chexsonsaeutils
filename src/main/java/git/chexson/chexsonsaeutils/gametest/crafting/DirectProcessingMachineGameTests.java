package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingValueBaselineModel;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportReasonCode;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportStatus;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@PrefixGameTestTemplate(false)
public final class DirectProcessingMachineGameTests {

    private static final String TEMPLATE = "ae_direct_processing_machine_smoke";
    private static final String BATCH = "idea3_direct_processing_machine";
    private static final String CONFIG_MAPPING_ADD_BATCH = "idea3_direct_processing_machine_config_mapping_add";
    private static final String CONFIG_MAPPING_CUSTOM_EXECUTE_BATCH =
            "idea3_direct_processing_machine_config_mapping_custom_execute";
    private static final String CONFIG_MAPPING_IMPORT_BATCH = "idea3_direct_processing_machine_config_mapping_import";
    private static final String CONFIG_MAPPING_REMOVE_CUSTOM_BATCH =
            "idea3_direct_processing_machine_config_mapping_remove_custom";
    private static final String CONFIG_MAPPING_REMOVE_PATTERN_BATCH =
            "idea3_direct_processing_machine_config_mapping_remove_pattern";
    private static final String CONFIG_MAPPING_NAMING_BATCH =
            "idea3_direct_processing_machine_config_mapping_naming";
    private static final ResourceLocation CRAFTING_TABLE_ID = ResourceLocation.withDefaultNamespace("crafting_table");
    private static final ResourceLocation DIRECT_PROCESSING_MOCK_MACHINE_ID =
            ResourceLocation.withDefaultNamespace("crafting_table");
    private static final ResourceLocation DIRECT_PROCESSING_MOCK_RECIPE_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "direct_processing_mock");
    private static final ResourceLocation MEKANISM_CRUSHER_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "crusher");
    private static final ResourceLocation MEKANISM_ENRICHMENT_CHAMBER_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "enrichment_chamber");
    private static final ResourceLocation MEKANISM_ENERGIZED_SMELTER_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "energized_smelter");
    private static final ResourceLocation MEKANISM_PRECISION_SAWMILL_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "precision_sawmill");
    private static final ResourceLocation MEKANISM_OSMIUM_COMPRESSOR_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "osmium_compressor");
    private static final ResourceLocation MEKANISM_DUST_OSMIUM_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "dust_osmium");
    private static final ResourceLocation MEKANISM_INGOT_OSMIUM_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "ingot_osmium");
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectProcessingMachineGameTests.class);
    private static final long PUSH_CACHE_LOOKUP_P95_LIMIT_NANOS = 50_000L;
    private static final int DIRECT_SHORT_RECIPE_TICKS = 4;
    private static final int DIRECT_THROUGHPUT_BENCHMARK_PUSHES = 256;

    private DirectProcessingMachineGameTests() {
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 240)
    public static void directProcessingDiscoversVanillaMachines(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, Items.FURNACE, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.FURNACE, Items.STONE))
                .thenIdle(1)
                .thenExecute(() -> installMachinePattern(fixture, Items.SMOKER, Items.BEEF, Items.COOKED_BEEF))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.SMOKER, Items.COOKED_BEEF))
                .thenIdle(1)
                .thenExecute(() -> installMachinePattern(fixture, Items.BLAST_FURNACE, Items.RAW_IRON, Items.IRON_INGOT))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.BLAST_FURNACE, Items.IRON_INGOT))
                .thenIdle(1)
                .thenExecute(() -> installMachinePattern(fixture, Items.STONECUTTER, Items.STONE, Items.STONE_BRICKS))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.STONECUTTER, Items.STONE_BRICKS))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void directProcessingAutoConfiguresMekanismCrusher(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        Item crusher = requireItem(helper, MEKANISM_CRUSHER_ID);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, crusher, Items.COBBLESTONE, Items.GRAVEL))
                .thenWaitUntil(() -> assertMachineSupportsExplicit(helper, fixture, crusher, Items.GRAVEL))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.COBBLESTONE, Items.GRAVEL))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.GRAVEL, 1))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void directProcessingAutoConfiguresMekanismEnrichmentChamber(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        Item enrichmentChamber = requireItem(helper, MEKANISM_ENRICHMENT_CHAMBER_ID);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, enrichmentChamber, Items.SAND, Items.GRAVEL))
                .thenWaitUntil(() -> assertMachineSupportsExplicit(helper, fixture, enrichmentChamber, Items.GRAVEL))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.SAND, Items.GRAVEL))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.GRAVEL, 1))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void directProcessingAutoConfiguresMekanismEnergizedSmelter(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        Item energizedSmelter = requireItem(helper, MEKANISM_ENERGIZED_SMELTER_ID);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, energizedSmelter, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, energizedSmelter, Items.STONE))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.STONE, 1))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void directProcessingEliminatesAeImportDeviceAndReducesItemContacts(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        long[] countersBefore = new long[4];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, Items.FURNACE, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.FURNACE, Items.STONE))
                .thenExecute(() -> {
                    DirectProcessingValueBaselineModel.Snapshot route =
                            DirectProcessingValueBaselineModel.shortRecipeSnapshot(20);
                    helper.assertTrue(route.aeReturnDeviceReduction() >= 1,
                            "direct processing route must remove at least one AE recovery/import device");
                    helper.assertTrue(route.itemContactReduction() > 0,
                            "direct processing route must reduce item contact count");
                    captureDirectExecutionCounters(fixture, countersBefore);
                })
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.STONE, 1))
                .thenExecute(() -> assertDirectExecutionAdvanced(helper, fixture, countersBefore))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void directProcessingShortRecipeReturnsAtLeastTwoTicksFasterThanBaseline(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installSpeedCards(3);
                    installMachinePattern(fixture, Items.FURNACE, Items.COBBLESTONE, Items.STONE);
                })
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.FURNACE, Items.STONE))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.STONE, 1))
                .thenExecute(() -> {
                    DirectProcessingValueBaselineModel.Snapshot route =
                            DirectProcessingValueBaselineModel.shortRecipeSnapshot(DIRECT_SHORT_RECIPE_TICKS);
                    helper.assertTrue(fixture.machine().getPushToAeReturnLatencySampleCountForTest() > 0L,
                            "direct machine must record push-to-AE-return tick latency samples");
                    helper.assertTrue(fixture.machine().getPushToAeReturnLatencyTicksAverageForTest()
                                    <= route.originalRoundTripTicks() - 2L,
                            "direct machine short recipe must return at least two ticks faster than baseline");
                    helper.assertTrue(fixture.machine().getMetricsSnapshotForTest()
                                    .outputReturnLatencyTicksSampleCount() > 0,
                            "direct machine metrics must expose output return latency samples");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void directProcessingCompletes10xThroughputWhenEnabled(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        String[] previousProfile = new String[1];
        long[] throughputCounters = new long[2];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    if (!Boolean.getBoolean("chexsonsaeutils.directProcessingThroughputGameTest")) {
                        return;
                    }
                    previousProfile[0] = ChexsonsaeutilsCompatibilityConfig
                            .AE_DIRECT_PROCESSING_MACHINE_BUDGET_PROFILE
                            .get();
                    ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_BUDGET_PROFILE.set("benchmark");
                    fixture.installSpeedCards(5);
                    installMachinePattern(fixture, Items.FURNACE, Items.COBBLESTONE, Items.STONE);
                })
                .thenWaitUntil(() -> {
                    if (!Boolean.getBoolean("chexsonsaeutils.directProcessingThroughputGameTest")) {
                        return;
                    }
                    helper.assertTrue(!fixture.machine().getAvailablePatterns().isEmpty(),
                            "direct machine must expose one supported pattern before throughput benchmark");
                    helper.assertValueEqual(0, fixture.machine().getPendingDirtyPatternSlotCountForTest(),
                            "direct machine must drain dirty slots before throughput benchmark");
                })
                .thenExecute(() -> {
                    if (!Boolean.getBoolean("chexsonsaeutils.directProcessingThroughputGameTest")) {
                        return;
                    }
                    IPatternDetails pattern = fixture.machine().getAvailablePatterns().getFirst();
                    long fullScansBefore = fixture.machine().getRecipeFullScanCountForTest();
                    long dirtyScansBefore = fixture.machine().getDirtyRefreshScanCountForTest();
                    long acceptedBefore = fixture.machine().getPushPatternAcceptedCountForTest();
                    long completedLogicalBefore = fixture.machine().getCompletedLogicalExecutionCountForTest();
                    for (int attempt = 0; attempt < DIRECT_THROUGHPUT_BENCHMARK_PUSHES; attempt++) {
                        fixture.machine().pushPattern(pattern, inputHolder(AEItemKey.of(Items.COBBLESTONE), 1));
                    }
                    long acceptedPushes = fixture.machine().getPushPatternAcceptedCountForTest() - acceptedBefore;
                    long modeledOriginal = DirectProcessingValueBaselineModel.modeledOriginalOneMachineCompletions(
                            DIRECT_SHORT_RECIPE_TICKS,
                            DirectProcessingValueBaselineModel.SHORT_THROUGHPUT_WINDOW_TICKS
                    );
                    helper.assertTrue(acceptedPushes >= modeledOriginal * 10L,
                            "direct benchmark must accept at least 10x modeled original short-chain throughput");
                    helper.assertValueEqual(fullScansBefore, fixture.machine().getRecipeFullScanCountForTest(),
                            "throughput benchmark submissions must not rescan recipes");
                    helper.assertValueEqual(dirtyScansBefore, fixture.machine().getDirtyRefreshScanCountForTest(),
                            "throughput benchmark submissions must not rescan pattern slots");
                    throughputCounters[0] = completedLogicalBefore;
                    throughputCounters[1] = acceptedPushes;
                })
                .thenWaitUntil(() -> assertThroughputCompleted(helper, fixture, throughputCounters))
                .thenExecute(() -> restoreBudgetProfile(previousProfile[0]))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 520)
    public static void directProcessingExecutesVanillaMachinesBackToAe(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, Items.FURNACE, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.FURNACE, Items.STONE))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.COBBLESTONE, Items.STONE))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.STONE, 1))
                .thenExecute(() -> installMachinePattern(fixture, Items.SMOKER, Items.BEEF, Items.COOKED_BEEF))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.SMOKER, Items.COOKED_BEEF))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.BEEF, Items.COOKED_BEEF))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.COOKED_BEEF, 1))
                .thenExecute(() -> installMachinePattern(fixture, Items.BLAST_FURNACE, Items.RAW_IRON, Items.IRON_INGOT))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.BLAST_FURNACE, Items.IRON_INGOT))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.RAW_IRON, Items.IRON_INGOT))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.IRON_INGOT, 1))
                .thenExecute(() -> installMachinePattern(fixture, Items.STONECUTTER, Items.STONE, Items.STONE_BRICKS))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.STONECUTTER, Items.STONE_BRICKS))
                .thenExecute(() -> pushFirstPattern(helper, fixture, Items.STONE, Items.STONE_BRICKS))
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.STONE_BRICKS, 1))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 320)
    public static void directProcessingAcceptsScaledPatterns(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> fixture.installProcessingPattern(0, Items.COBBLESTONE, 64, Items.STONE, 64))
                .thenExecute(() -> fixture.bindMachine(Items.FURNACE))
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.FURNACE, Items.STONE))
                .thenExecute(() -> {
                    long completedBefore = fixture.machine().getCompletedLogicalExecutionCountForTest();
                    helper.assertTrue(
                            fixture.machine().pushPattern(
                                    fixture.machine().getAvailablePatterns().getFirst(),
                                    inputHolder(AEItemKey.of(Items.COBBLESTONE), 64)
                            ),
                            "scaled direct-processing pattern must be accepted for cached execution"
                    );
                    helper.assertTrue(fixture.machine().getPushPatternAcceptedCountForTest() > 0,
                            "scaled direct-processing pattern must increment accepted push count");
                    helper.assertTrue(fixture.machine().getQueuedTaskCountForMenu() >= 0,
                            "scaled direct-processing pattern must keep the execution queue observable");
                    helper.assertTrue(fixture.machine().getCompletedLogicalExecutionCountForTest() >= completedBefore,
                            "scaled direct-processing execution counter must stay monotonic");
                })
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.STONE, 64))
                .thenExecute(() -> helper.assertTrue(
                        fixture.machine().getCompletedLogicalExecutionCountForTest() >= 64L,
                        "scaled direct-processing pattern must complete all logical executions"
                ))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 240)
    public static void directProcessingCampfireRequiresExplicitMapping(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.CAMPFIRE);
                    fixture.installProcessingPattern(0, Items.BEEF, 1, Items.COOKED_BEEF, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                        fixture.machine().getPatternStatusForTest(0),
                        "campfire cooking must require an explicit direct-processing mapping"))
                .thenExecute(() -> {
                    helper.assertValueEqual(0, fixture.machine().getActivePatternCount(),
                            "campfire cooking must not be exposed through generic direct-machine discovery");
                    helper.assertTrue(fixture.machine().getGrid().getCraftingService()
                                    .getCraftingFor(AEItemKey.of(Items.COOKED_BEEF))
                                    .isEmpty(),
                            "campfire cooking must not expose provider outputs without config mapping");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void directProcessingMekanismSawmillStaysUnsupported(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        Item sawmill = requireItem(helper, MEKANISM_PRECISION_SAWMILL_ID);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, sawmill, Items.TORCH, Items.STICK))
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.machine().getPatternStatusForTest(0) == MachineSupportStatus.UNSAFE_DYNAMIC
                                || fixture.machine().getPatternStatusForTest(0) == MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                        "Mekanism sawmill must stay unsupported because it has deterministic plus secondary output semantics"
                ))
                .thenExecute(() -> helper.assertValueEqual(0, fixture.machine().getActivePatternCount(),
                        "unsupported Mekanism sawmill pattern must not be exposed through the provider"))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void directProcessingMekanismChemicalMachineStaysUnsupported(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        Item osmiumCompressor = requireItem(helper, MEKANISM_OSMIUM_COMPRESSOR_ID);
        Item dustOsmium = requireItem(helper, MEKANISM_DUST_OSMIUM_ID);
        Item ingotOsmium = requireItem(helper, MEKANISM_INGOT_OSMIUM_ID);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, osmiumCompressor, dustOsmium, ingotOsmium))
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.machine().getPatternStatusForTest(0) == MachineSupportStatus.UNSAFE_DYNAMIC
                                || fixture.machine().getPatternStatusForTest(0) == MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                        "Mekanism chemical machines must stay unsupported on the local item-to-item direct path"
                ))
                .thenExecute(() -> helper.assertValueEqual(0, fixture.machine().getActivePatternCount(),
                        "unsupported Mekanism chemical machine pattern must not be exposed through the provider"))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 520)
    public static void directProcessingRefreshes1024PatternsWithoutIdleScanning(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        long[] idleScanBaseline = new long[2];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.FURNACE);
                    for (int slot = 0; slot < 1024; slot++) {
                        fixture.installProcessingPattern(slot, Items.COBBLESTONE, 1, Items.STONE, 1);
                    }
                })
                .thenWaitUntil(() -> helper.assertValueEqual(0,
                        fixture.machine().getPendingDirtyPatternSlotCountForTest(),
                        "direct machine must drain dirty pattern slots with the local per-tick budget"))
                .thenExecute(() -> {
                    helper.assertValueEqual(1024, fixture.machine().getActivePatternCount(),
                            "direct machine must support 1024 local processing pattern slots");
                    helper.assertValueEqual(1, fixture.machine().getAvailablePatterns().size(),
                            "duplicate direct patterns must be exposed to AE as one provider pattern");
                    helper.assertTrue(fixture.machine().getRecipeDiscoveryCountForTest() > 0,
                            "direct machine must request its local recipe index during dirty events");
                    helper.assertTrue(fixture.machine().getDirtyRefreshScanCountForTest() >= 1024,
                            "direct machine must have refreshed at least the installed 1024 pattern slots");
                })
                .thenExecute(() -> captureIdleScanCounters(fixture, idleScanBaseline))
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(0, fixture.machine().getPendingDirtyPatternSlotCountForTest(),
                            "direct machine must remain idle after dirty slots are drained");
                    helper.assertValueEqual(idleScanBaseline[0],
                            fixture.machine().getRecipeFullScanCountForTest(),
                            "idle direct machine tick must not rescan recipes");
                    helper.assertValueEqual(idleScanBaseline[1],
                            fixture.machine().getDirtyRefreshScanCountForTest(),
                            "idle direct machine tick must not scan pattern slots");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void directProcessingReusesRecipeIndexForSameBinding(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        long[] scanBaseline = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.FURNACE);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(fixture.machine().getMachineRecipeSignatureCountForTest() > 0,
                            "first direct furnace binding must build executable recipe signatures");
                    helper.assertTrue(!fixture.machine().isMachineRecipeIndexRefreshPendingForTest(),
                            "first direct furnace binding must finish local index refresh");
                })
                .thenExecute(() -> {
                    scanBaseline[0] = fixture.machine().getRecipeFullScanCountForTest();
                })
                .thenExecute(() -> fixture.bindMachine(Items.FURNACE))
                .thenWaitUntil(() -> {
                    helper.assertTrue(!fixture.machine().isMachineRecipeIndexRefreshPendingForTest(),
                            "same direct furnace binding must finish local index refresh");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(scanBaseline[0], fixture.machine().getRecipeFullScanCountForTest(),
                            "same direct machine binding must reuse the local recipe index cache");
                    helper.assertTrue(fixture.machine().getRecipeDiscoveryCountForTest()
                                    > fixture.machine().getRecipeFullScanCountForTest(),
                            "direct machine must distinguish rebuild requests from recipe manager full scans");
                })
                .thenSucceed();
    }

    @GameTest(
            templateNamespace = Chexsonsaeutils.MODID,
            template = TEMPLATE,
            batch = CONFIG_MAPPING_ADD_BATCH,
            timeoutTicks = 260
    )
    public static void directProcessingConfigMappingAddsUnsupportedMachine(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        MachineRecipeConfigMappingRegistry.Snapshot mappingSnapshot =
                MachineRecipeConfigMappingRegistry.instance().snapshot();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    resetDirectProcessingConfigMappings();
                    MachineRecipeConfigMappingRegistry.instance().registerMapping(
                            CRAFTING_TABLE_ID,
                            RecipeType.SMELTING,
                            20
                    );
                    fixture.bindMachine(Items.CRAFTING_TABLE);
                    fixture.installProcessingPattern(0, Items.COBBLESTONE, 1, Items.STONE, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.SUPPORTED_CONFIG,
                        fixture.machine().getPatternStatusForTest(0),
                        "configured direct machine pattern must be marked as SUPPORTED_CONFIG"))
                .thenExecute(() -> {
                    helper.assertValueEqual(1, fixture.machine().getActivePatternCount(),
                            "configured direct machine must expose the mapped smelting pattern");
                    helper.assertTrue(!fixture.machine().getGrid().getCraftingService()
                                    .getCraftingFor(AEItemKey.of(Items.STONE))
                                    .isEmpty(),
                            "configured direct machine output must be visible to AE crafting");
                    restoreDirectProcessingConfigMappings(mappingSnapshot);
                })
                .thenSucceed();
    }

    @GameTest(
            templateNamespace = Chexsonsaeutils.MODID,
            template = TEMPLATE,
            batch = CONFIG_MAPPING_CUSTOM_EXECUTE_BATCH,
            timeoutTicks = 360
    )
    public static void directProcessingConfigMappingExecutesCustomRecipeType(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.CRAFTING_TABLE);
                    fixture.installProcessingPattern(0, Items.COAL, 1, Items.DIAMOND, 1);
                })
                .thenIdle(8)
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.SUPPORTED_CONFIG,
                        fixture.machine().getPatternStatusForTest(0),
                        "custom recipe type mapping must support the configured mock machine pattern"))
                .thenExecute(() -> {
                    helper.assertValueEqual(1, fixture.machine().getMachineRecipeSignatureCountForTest(),
                            "custom recipe type mapping must build one executable recipe signature");
                    helper.assertTrue(fixture.machine().pushPattern(
                                    fixture.machine().getAvailablePatterns().getFirst(),
                                    inputHolder(AEItemKey.of(Items.COAL), 1)
                            ),
                            "custom recipe type mapping must accept the configured direct processing pattern");
                })
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.DIAMOND, 1))
                .thenSucceed();
    }

    @GameTest(
            templateNamespace = Chexsonsaeutils.MODID,
            template = TEMPLATE,
            batch = CONFIG_MAPPING_IMPORT_BATCH,
            timeoutTicks = 320
    )
    public static void directProcessingMenuImportAppliesValidatedRecipeTypes(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        MachineRecipeConfigMappingRegistry.Snapshot mappingSnapshot =
                MachineRecipeConfigMappingRegistry.instance().snapshot();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    resetDirectProcessingConfigMappings();
                    fixture.bindMachine(Items.CRAFTING_TABLE);
                    fixture.installProcessingPattern(0, Items.COAL, 1, Items.DIAMOND, 1);
                })
                .thenIdle(8)
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                        fixture.machine().getPatternStatusForTest(0),
                        "custom machine must stay unsupported before the direct menu import"))
                .thenExecute(() -> helper.assertTrue(
                        fixture.machine().importUserConfigMappingForMenu(new MachineRecipeConfigImportRequest(
                                CRAFTING_TABLE_ID,
                                CRAFTING_TABLE_ID,
                                List.of(DIRECT_PROCESSING_MOCK_RECIPE_TYPE_ID),
                                20,
                                "generic",
                                "any",
                                true,
                                ""
                        )),
                        "validated direct menu import must succeed for the mock custom recipe type"
                ))
                .thenIdle(8)
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.SUPPORTED_CONFIG,
                        fixture.machine().getPatternStatusForTest(0),
                        "validated direct menu import must promote the pattern to SUPPORTED_CONFIG"))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.machine().getMachineRecipeSignatureCountForTest() > 0,
                            "validated direct menu import must rebuild a local executable index");
                    helper.assertTrue(!fixture.machine().getGrid().getCraftingService()
                                    .getCraftingFor(AEItemKey.of(Items.DIAMOND))
                                    .isEmpty(),
                            "validated direct menu import must expose the direct pattern to AE crafting");
                    restoreDirectProcessingConfigMappings(mappingSnapshot);
                })
                .thenSucceed();
    }

    @GameTest(
            templateNamespace = Chexsonsaeutils.MODID,
            template = TEMPLATE,
            batch = CONFIG_MAPPING_REMOVE_CUSTOM_BATCH,
            timeoutTicks = 260
    )
    public static void directProcessingConfigMappingRemovalHidesCustomRecipeType(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        MachineRecipeConfigMappingRegistry.Snapshot mappingSnapshot =
                MachineRecipeConfigMappingRegistry.instance().snapshot();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.CRAFTING_TABLE);
                    fixture.installProcessingPattern(0, Items.COAL, 1, Items.DIAMOND, 1);
                })
                .thenIdle(8)
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.SUPPORTED_CONFIG,
                        fixture.machine().getPatternStatusForTest(0),
                        "custom recipe type mapping must be visible before removal"))
                .thenExecute(() -> {
                    resetDirectProcessingConfigMappings();
                    fixture.machine().invalidateDiscoveryForRecipeReload();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                        fixture.machine().getPatternStatusForTest(0),
                        "custom recipe type pattern must require config after mapping removal"))
                .thenExecute(() -> {
                    helper.assertValueEqual(0, fixture.machine().getActivePatternCount(),
                            "custom recipe type pattern must disappear from provider exposure after removal");
                    helper.assertTrue(fixture.machine().getGrid().getCraftingService()
                                    .getCraftingFor(AEItemKey.of(Items.DIAMOND))
                                    .isEmpty(),
                            "custom recipe type output must disappear from AE crafting after mapping removal");
                    restoreDirectProcessingConfigMappings(mappingSnapshot);
                })
                .thenSucceed();
    }

    @GameTest(
            templateNamespace = Chexsonsaeutils.MODID,
            template = TEMPLATE,
            batch = CONFIG_MAPPING_REMOVE_PATTERN_BATCH,
            timeoutTicks = 260
    )
    public static void directProcessingConfigMappingRemovalHidesPatterns(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        MachineRecipeConfigMappingRegistry.Snapshot mappingSnapshot =
                MachineRecipeConfigMappingRegistry.instance().snapshot();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    resetDirectProcessingConfigMappings();
                    MachineRecipeConfigMappingRegistry.instance().registerMapping(
                            CRAFTING_TABLE_ID,
                            RecipeType.SMELTING,
                            20
                    );
                    fixture.bindMachine(Items.CRAFTING_TABLE);
                    fixture.installProcessingPattern(0, Items.COBBLESTONE, 1, Items.STONE, 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(fixture.machine().getActivePatternCount() == 1,
                        "configured direct machine must expose the mapped pattern before removal"))
                .thenExecute(DirectProcessingMachineGameTests::resetDirectProcessingConfigMappings)
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.machine().getPatternStatusForTest(0) == MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                        "configured direct machine pattern must require config after mapping removal"))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.machine().getActivePatternCount() == 0,
                            "mapping removal must remove the direct pattern from provider exposure");
                    helper.assertTrue(fixture.machine().getGrid().getCraftingService()
                                    .getCraftingFor(AEItemKey.of(Items.STONE))
                                    .isEmpty(),
                            "mapping removal must hide the direct machine output from AE crafting");
                    restoreDirectProcessingConfigMappings(mappingSnapshot);
                })
                .thenSucceed();
    }

    @GameTest(
            templateNamespace = Chexsonsaeutils.MODID,
            template = TEMPLATE,
            batch = CONFIG_MAPPING_NAMING_BATCH,
            timeoutTicks = 240
    )
    public static void directProcessingNamingConventionAutoConfiguresHighConfidenceMachine(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        MachineRecipeConfigMappingRegistry.Snapshot mappingSnapshot =
                MachineRecipeConfigMappingRegistry.instance().snapshot();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    resetDirectProcessingConfigMappings();
                    fixture.bindMachine(Items.CRAFTING_TABLE);
                    fixture.installProcessingPattern(0, Items.OAK_LOG, 1, Items.OAK_PLANKS, 4);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(MachineSupportStatus.SUPPORTED_GENERIC,
                        fixture.machine().getPatternStatusForTest(0),
                        "high-confidence naming-convention machine must auto-configure generically"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MachineSupportReasonCode.NONE,
                            fixture.machine().getPatternReasonCodeForTest(0),
                            "auto-configured naming-convention pattern must have no support error");
                    helper.assertTrue(fixture.machine().getMachineRecipeSignatureCountForTest() > 0,
                            "auto-configured naming-convention machine must build executable signatures");
                    helper.assertValueEqual(1, fixture.machine().getActivePatternCount(),
                            "auto-configured naming-convention machine must expose its direct pattern");
                    helper.assertTrue(!fixture.machine().getGrid().getCraftingService()
                                    .getCraftingFor(AEItemKey.of(Items.OAK_PLANKS))
                                    .isEmpty(),
                            "auto-configured naming-convention output must be visible to AE crafting");
                    helper.assertTrue(fixture.machine().pushPattern(
                                    fixture.machine().getAvailablePatterns().getFirst(),
                                    inputHolder(AEItemKey.of(Items.OAK_LOG), 1)
                            ),
                            "auto-configured naming-convention machine must accept the direct pattern");
                })
                .thenWaitUntil(() -> {
                    assertStoredAtLeast(helper, fixture, Items.OAK_PLANKS, 4);
                    restoreDirectProcessingConfigMappings(mappingSnapshot);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 320)
    public static void directProcessingPushHotPathUsesCacheFor100kSubmissions(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.FURNACE);
                    fixture.installProcessingPattern(0, Items.COBBLESTONE, 1, Items.STONE, 1);
                })
                .thenWaitUntil(() -> helper.assertTrue(!fixture.machine().getAvailablePatterns().isEmpty(),
                        "direct machine must expose one supported pattern before hot path benchmark"))
                .thenExecute(() -> {
                    IPatternDetails pattern = fixture.machine().getAvailablePatterns().getFirst();
                    long fullScansBefore = fixture.machine().getRecipeFullScanCountForTest();
                    long dirtyScansBefore = fixture.machine().getDirtyRefreshScanCountForTest();
                    long lookupsBefore = fixture.machine().getPushPatternCacheLookupCountForTest();
                    long acceptedBefore = fixture.machine().getPushPatternAcceptedCountForTest();
                    for (int attempt = 0; attempt < 100_000; attempt++) {
                        fixture.machine().pushPattern(pattern, inputHolder(AEItemKey.of(Items.COBBLESTONE), 1));
                    }
                    helper.assertValueEqual(fullScansBefore, fixture.machine().getRecipeFullScanCountForTest(),
                            "100k direct push attempts must not rescan recipes");
                    helper.assertValueEqual(dirtyScansBefore, fixture.machine().getDirtyRefreshScanCountForTest(),
                            "100k direct push attempts must not scan pattern slots");
                    helper.assertValueEqual(100_000L,
                            fixture.machine().getPushPatternCacheLookupCountForTest() - lookupsBefore,
                            "100k direct push attempts must use compatibility cache lookups");
                    helper.assertTrue(fixture.machine().getPushPatternCacheLookupNanosMaxForTest() > 0L,
                            "100k direct push attempts must expose local cache lookup timing");
                    helper.assertTrue(fixture.machine().getMetricsSnapshotForTest()
                                    .pushPatternCacheLookupNanosSampleCount() >= 1024,
                            "100k direct push attempts must fill the bounded push lookup sample window");
                    helper.assertTrue(fixture.machine().getPushPatternCacheLookupNanosP95ForTest()
                                    <= PUSH_CACHE_LOOKUP_P95_LIMIT_NANOS,
                            "direct push cache lookup P95 must stay under 50us");
                    helper.assertTrue(fixture.machine().getPushPatternAcceptedCountForTest() > acceptedBefore,
                            "direct machine must accept at least one cached push during the hot path benchmark");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 280)
    public static void directProcessingMekanismBindingReusesRecipeIndex(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        Item crusher = requireItem(helper, MEKANISM_CRUSHER_ID);
        long[] scanBaseline = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> installMachinePattern(fixture, crusher, Items.COBBLESTONE, Items.GRAVEL))
                .thenWaitUntil(() -> assertMachineSupportsExplicit(helper, fixture, crusher, Items.GRAVEL))
                .thenExecute(() -> scanBaseline[0] = fixture.machine().getRecipeFullScanCountForTest())
                .thenExecute(() -> fixture.bindMachine(crusher))
                .thenWaitUntil(() -> helper.assertTrue(!fixture.machine().isMachineRecipeIndexRefreshPendingForTest(),
                        "same Mekanism binding must finish local index refresh"))
                .thenExecute(() -> helper.assertValueEqual(scanBaseline[0], fixture.machine().getRecipeFullScanCountForTest(),
                        "same Mekanism binding must reuse the local recipe index cache"))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void directProcessingPushHotPathUsesCacheFor1mSubmissionsWhenEnabled(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    if (!Boolean.getBoolean("chexsonsaeutils.directProcessingMillionGameTest")) {
                        return;
                    }
                    fixture.bindMachine(Items.FURNACE);
                    fixture.installProcessingPattern(0, Items.COBBLESTONE, 1, Items.STONE, 1);
                })
                .thenWaitUntil(() -> {
                    if (!Boolean.getBoolean("chexsonsaeutils.directProcessingMillionGameTest")) {
                        return;
                    }
                    helper.assertTrue(!fixture.machine().getAvailablePatterns().isEmpty(),
                            "direct machine must expose one supported pattern before 1M hot path benchmark");
                })
                .thenExecute(() -> {
                    if (!Boolean.getBoolean("chexsonsaeutils.directProcessingMillionGameTest")) {
                        return;
                    }
                    runPushHotPathBenchmark(helper, fixture, 1_000_000);
                    LOGGER.info(
                            "IDEA3_DIRECT_PROCESSING_PUSH_BENCHMARK attempts={} lookupP95={} lookupMax={} samples={}",
                            1_000_000,
                            fixture.machine().getPushPatternCacheLookupNanosP95ForTest(),
                            fixture.machine().getPushPatternCacheLookupNanosMaxForTest(),
                            fixture.machine().getMetricsSnapshotForTest().pushPatternCacheLookupNanosSampleCount()
                    );
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 240)
    public static void directProcessingHidesUnsupportedPatterns(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.FURNACE);
                    fixture.installProcessingPattern(0, Items.COBBLESTONE, 1, Items.STONE, 1);
                    fixture.installProcessingPattern(1, Items.STONE, 1, Items.STONE_BRICKS, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(1, fixture.machine().getActivePatternCount(),
                        "direct furnace must expose only the supported smelting pattern"))
                .thenExecute(() -> {
                    helper.assertTrue(isSupported(fixture.machine().getPatternStatusForTest(0)),
                            "direct furnace smelting slot must be supported");
                    helper.assertTrue(!isSupported(fixture.machine().getPatternStatusForTest(1)),
                            "direct furnace must hide stonecutter-only pattern");
                    helper.assertTrue(fixture.machine().getGrid().getCraftingService()
                                    .getCraftingFor(AEItemKey.of(Items.STONE_BRICKS))
                                    .isEmpty(),
                            "unsupported direct pattern output must not be visible to AE crafting");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 240)
    public static void directProcessingRecipeReloadInvalidatesIndex(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        long[] previousVersion = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.FURNACE);
                    fixture.installProcessingPattern(0, Items.COBBLESTONE, 1, Items.STONE, 1);
                })
                .thenIdle(8)
                .thenExecute(() -> {
                    previousVersion[0] = fixture.machine().getMachineRecipeIndexVersionForTest();
                    int previousSignatureCount = fixture.machine().getMachineRecipeSignatureCountForTest();
                    helper.assertTrue(previousSignatureCount > 0, "direct furnace index must have signatures before reload");
                    fixture.machine().invalidateDiscoveryForRecipeReload();
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(fixture.machine().getMachineRecipeIndexVersionForTest() > previousVersion[0],
                            "direct recipe reload must bump only this machine index version");
                    helper.assertTrue(!fixture.machine().isMachineRecipeIndexRefreshPendingForTest(),
                            "direct recipe reload must finish local index refresh");
                })
                .thenExecute(() -> {
                    helper.assertTrue(fixture.machine().getMachineRecipeSignatureCountForTest() > 0,
                            "direct furnace index must rebuild signatures after reload");
                    helper.assertValueEqual(1, fixture.machine().getActivePatternCount(),
                            "direct furnace pattern must become visible again after reload");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 320)
    public static void directProcessingReturnsMultipleCompletedBatchesBackToAe(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        long[] idleScanBaseline = new long[2];
        long[] counters = new long[3];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installSpeedCards(5);
                    installMachinePattern(fixture, Items.FURNACE, Items.COBBLESTONE, Items.STONE);
                })
                .thenWaitUntil(() -> assertMachineSupports(helper, fixture, Items.FURNACE, Items.STONE))
                .thenExecute(() -> {
                    captureIdleScanCounters(fixture, idleScanBaseline);
                    counters[0] = fixture.machine().getCompletedLogicalExecutionCountForTest();
                    counters[1] = fixture.machine().getMetricsSnapshotForTest().outputReturnNanosSampleCount();
                    counters[2] = pushFirstPatternTimes(helper, fixture, Items.COBBLESTONE, 8);
                    helper.assertTrue(counters[2] > 1L,
                            "direct machine must accept multiple cached pattern pushes for batch return");
                })
                .thenWaitUntil(() -> assertStoredAtLeast(helper, fixture, Items.STONE, counters[2]))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.machine().getCompletedLogicalExecutionCountForTest()
                                    >= counters[0] + counters[2],
                            "direct machine must complete all accepted logical executions");
                    helper.assertTrue(fixture.machine().getMetricsSnapshotForTest().outputReturnNanosSampleCount()
                                    > counters[1],
                            "direct machine must record batched output return attempts");
                    helper.assertTrue(!fixture.machine().isWaitingForOutputReturn(),
                            "direct machine must drain batched pending output");
                    helper.assertTrue(fixture.machine().getRecipeFullScanCountForTest() <= idleScanBaseline[0],
                            "batched output return must not rescan recipes");
                    helper.assertTrue(fixture.machine().getDirtyRefreshScanCountForTest() >= idleScanBaseline[1],
                            "batched output return must not corrupt dirty refresh counters");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 320)
    public static void directProcessingRetriesWhenStorageBackpressured(GameTestHelper helper) {
        DirectProcessingMachineGameTestFixture fixture = DirectProcessingMachineGameTestFixture.create(helper);
        long[] idleScanBaseline = new long[2];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.bindMachine(Items.FURNACE);
                    fixture.installProcessingPattern(0, Items.COBBLESTONE, 1, Items.STONE, 1);
                    fixture.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.COBBLESTONE), 1)));
                    fixture.removeStorageCell();
                })
                .thenWaitUntil(() -> helper.assertTrue(!fixture.machine().getAvailablePatterns().isEmpty(),
                        "direct machine must expose a supported pattern before backpressure push"))
                .thenExecute(() -> helper.assertTrue(
                        fixture.machine().pushPattern(
                                fixture.machine().getAvailablePatterns().getFirst(),
                                inputHolder(AEItemKey.of(Items.COBBLESTONE), 1)
                        ),
                        "direct machine must accept the supported pattern push"
                ))
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(fixture.machine().isWaitingForOutputReturn(),
                            "direct machine must wait when AE output return is backpressured");
                    helper.assertTrue(fixture.machine().getPendingOutputRetryBackoffTicksForTest() > 0,
                            "direct machine must increase output return retry backoff");
                    helper.assertTrue(fixture.machine().getCompletedTaskCountForTest() > 0L,
                            "direct machine must complete at least one cached task before output backpressure");
                    helper.assertTrue(fixture.machine().getCompletedLogicalExecutionCountForTest() > 0L,
                            "direct machine must expose completed logical execution count");
                    captureIdleScanCounters(fixture, idleScanBaseline);
                })
                .thenIdle(8)
                .thenExecute(() -> {
                    helper.assertValueEqual(idleScanBaseline[0],
                            fixture.machine().getRecipeFullScanCountForTest(),
                            "backpressured output retry must not rescan recipes");
                    helper.assertTrue(fixture.machine().getDirtyRefreshScanCountForTest() >= idleScanBaseline[1],
                            "backpressured output retry must not corrupt local dirty refresh counters");
                    fixture.installStorageCell();
                })
                .thenIdle(40)
                .thenExecute(() -> helper.assertTrue(!fixture.machine().isWaitingForOutputReturn(),
                        "direct machine must clear pending output after storage returns"))
                .thenSucceed();
    }

    private static void installMachinePattern(
            DirectProcessingMachineGameTestFixture fixture,
            net.minecraft.world.level.ItemLike machine,
            net.minecraft.world.level.ItemLike input,
            net.minecraft.world.level.ItemLike output
    ) {
        fixture.bindMachine(machine);
        fixture.installProcessingPattern(0, input, 1, output, 1);
    }

    private static void pushFirstPattern(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            net.minecraft.world.level.ItemLike input,
            net.minecraft.world.level.ItemLike output
    ) {
        helper.assertTrue(
                fixture.machine().pushPattern(
                        fixture.machine().getAvailablePatterns().getFirst(),
                        inputHolder(AEItemKey.of(input), 1)
                ),
                "direct machine must accept and execute supported pattern for " + output.asItem()
        );
    }

    private static void pushFirstPattern(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            net.minecraft.world.level.ItemLike input,
            long inputAmount,
            net.minecraft.world.level.ItemLike output
    ) {
        helper.assertTrue(
                fixture.machine().pushPattern(
                        fixture.machine().getAvailablePatterns().getFirst(),
                        inputHolder(AEItemKey.of(input), inputAmount)
                ),
                "direct machine must accept and execute supported scaled pattern for " + output.asItem()
        );
    }

    private static long pushFirstPatternTimes(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            net.minecraft.world.level.ItemLike input,
            int attempts
    ) {
        IPatternDetails pattern = fixture.machine().getAvailablePatterns().getFirst();
        long accepted = 0L;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (fixture.machine().pushPattern(pattern, inputHolder(AEItemKey.of(input), 1))) {
                accepted++;
            }
        }
        helper.assertTrue(accepted > 0L, "direct machine must accept at least one batched pattern push");
        return accepted;
    }

    private static void captureIdleScanCounters(
            DirectProcessingMachineGameTestFixture fixture,
            long[] idleScanBaseline
    ) {
        idleScanBaseline[0] = fixture.machine().getRecipeFullScanCountForTest();
        idleScanBaseline[1] = fixture.machine().getDirtyRefreshScanCountForTest();
    }

    private static void captureDirectExecutionCounters(
            DirectProcessingMachineGameTestFixture fixture,
            long[] counters
    ) {
        counters[0] = fixture.machine().getPushPatternAcceptedCountForTest();
        counters[1] = fixture.machine().getCompletedTaskCountForTest();
        counters[2] = fixture.machine().getCompletedLogicalExecutionCountForTest();
        counters[3] = fixture.machine().getMetricsSnapshotForTest().outputReturnNanosSampleCount();
    }

    private static void assertDirectExecutionAdvanced(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            long[] countersBefore
    ) {
        helper.assertValueEqual(countersBefore[0] + 1L,
                fixture.machine().getPushPatternAcceptedCountForTest(),
                "direct route must accept one pattern push");
        helper.assertTrue(fixture.machine().getCompletedTaskCountForTest() > countersBefore[1],
                "direct route must complete at least one physical task");
        helper.assertTrue(fixture.machine().getCompletedLogicalExecutionCountForTest() > countersBefore[2],
                "direct route must complete at least one logical execution");
        helper.assertTrue(fixture.machine().getMetricsSnapshotForTest().outputReturnNanosSampleCount()
                        > countersBefore[3],
                "direct route must return output through the direct machine output sink");
    }

    private static void assertThroughputCompleted(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            long[] counters
    ) {
        if (!Boolean.getBoolean("chexsonsaeutils.directProcessingThroughputGameTest")) {
            return;
        }
        long completedLogicalBefore = counters[0];
        long acceptedPushes = counters[1];
        helper.assertTrue(acceptedPushes > 0L, "throughput benchmark must accept direct pushes");
        helper.assertTrue(fixture.machine().getCompletedLogicalExecutionCountForTest()
                        >= completedLogicalBefore + acceptedPushes,
                "direct benchmark must complete accepted logical executions");
        helper.assertTrue(fixture.countStored(AEItemKey.of(Items.STONE)) >= acceptedPushes,
                "direct benchmark must return accepted logical outputs to AE storage");
        LOGGER.info(
                "IDEA3_DIRECT_PROCESSING_THROUGHPUT accepted={} completedLogical={} stored={} latencyP95={} "
                        + "serverTickP95={} outputReturnP95={}",
                acceptedPushes,
                fixture.machine().getCompletedLogicalExecutionCountForTest() - completedLogicalBefore,
                fixture.countStored(AEItemKey.of(Items.STONE)),
                fixture.machine().getMetricsSnapshotForTest().outputReturnLatencyTicksP95(),
                fixture.machine().getServerTickNanosP95ForTest(),
                fixture.machine().getOutputReturnNanosP95ForTest()
        );
    }

    private static void assertMachineSupports(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            net.minecraft.world.level.ItemLike machine,
            net.minecraft.world.level.ItemLike output
    ) {
        helper.assertTrue(fixture.machine().getMachineRecipeSignatureCountForTest() > 0,
                "direct machine must discover recipe signatures for " + machine.asItem());
        helper.assertTrue(isSupported(fixture.machine().getPatternStatusForTest(0)),
                "direct machine must support generic processing pattern for " + output.asItem());
        helper.assertTrue(!fixture.machine().getGrid().getCraftingService()
                        .getCraftingFor(AEItemKey.of(output))
                        .isEmpty(),
                "direct machine must expose supported output to AE crafting");
    }

    private static void assertMachineSupportsExplicit(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            net.minecraft.world.level.ItemLike machine,
            net.minecraft.world.level.ItemLike output
    ) {
        assertMachineSupports(helper, fixture, machine, output);
        helper.assertValueEqual(MachineSupportStatus.SUPPORTED_EXPLICIT,
                fixture.machine().getPatternStatusForTest(0),
                "Mekanism whitelist machine must be supported through the local explicit adapter path");
    }

    private static void runPushHotPathBenchmark(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            int attempts
    ) {
        IPatternDetails pattern = fixture.machine().getAvailablePatterns().getFirst();
        long fullScansBefore = fixture.machine().getRecipeFullScanCountForTest();
        long dirtyScansBefore = fixture.machine().getDirtyRefreshScanCountForTest();
        long lookupsBefore = fixture.machine().getPushPatternCacheLookupCountForTest();
        long acceptedBefore = fixture.machine().getPushPatternAcceptedCountForTest();
        for (int attempt = 0; attempt < attempts; attempt++) {
            fixture.machine().pushPattern(pattern, inputHolder(AEItemKey.of(Items.COBBLESTONE), 1));
        }
        helper.assertValueEqual(fullScansBefore, fixture.machine().getRecipeFullScanCountForTest(),
                attempts + " direct push attempts must not rescan recipes");
        helper.assertValueEqual(dirtyScansBefore, fixture.machine().getDirtyRefreshScanCountForTest(),
                attempts + " direct push attempts must not scan pattern slots");
        helper.assertValueEqual((long) attempts,
                fixture.machine().getPushPatternCacheLookupCountForTest() - lookupsBefore,
                attempts + " direct push attempts must use compatibility cache lookups");
        helper.assertTrue(fixture.machine().getPushPatternAcceptedCountForTest() > acceptedBefore,
                "direct machine must accept at least one cached push during the hot path benchmark");
    }

    private static void assertStoredAtLeast(
            GameTestHelper helper,
            DirectProcessingMachineGameTestFixture fixture,
            net.minecraft.world.level.ItemLike item,
            long amount
    ) {
        long stored = fixture.countStored(AEItemKey.of(item));
        helper.assertTrue(stored >= amount,
                "direct machine must return at least " + amount + " " + item.asItem() + " to AE, got " + stored);
    }

    private static KeyCounter[] inputHolder(AEItemKey key, long amount) {
        KeyCounter counter = new KeyCounter();
        counter.add(key, amount);
        return new KeyCounter[]{counter};
    }

    private static boolean isSupported(MachineSupportStatus status) {
        return status == MachineSupportStatus.SUPPORTED_GENERIC
                || status == MachineSupportStatus.SUPPORTED_CONFIG
                || status == MachineSupportStatus.SUPPORTED_EXPLICIT;
    }

    private static Item requireItem(GameTestHelper helper, ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        helper.assertTrue(item != null && item != Items.AIR, "Missing required item for GameTest: " + id);
        return item;
    }

    private static void resetDirectProcessingConfigMappings() {
        MachineRecipeConfigMappingRegistry.instance().replaceAllMappingsForTest(List.of());
    }

    private static void restoreDirectProcessingConfigMappings(MachineRecipeConfigMappingRegistry.Snapshot snapshot) {
        MachineRecipeConfigMappingRegistry.instance().restore(snapshot);
    }

    private static void restoreBudgetProfile(String profileName) {
        if (profileName != null) {
            ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_BUDGET_PROFILE.set(profileName);
        }
    }
}
