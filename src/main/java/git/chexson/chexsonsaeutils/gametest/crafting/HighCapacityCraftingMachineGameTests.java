package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingStatus;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.blockentity.crafting.BatchExecutionMode;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.PatternBenchmarkSnapshot;
import git.chexson.chexsonsaeutils.crafting.formalmachine.IFormalMachineScaledPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineCraftingTimingService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;

@GameTestHolder(Chexsonsaeutils.MODID)
@PrefixGameTestTemplate(false)
public final class HighCapacityCraftingMachineGameTests {

    private static final String TEMPLATE = "high_capacity_formal_machine_smoke";
    private static final String DUAL_TEMPLATE = "high_capacity_formal_machine_dual";
    private static final String AUTOCRAFT_TEMPLATE = "high_capacity_formal_machine_autocrafting";
    private static final String BATCH = "idea1_formal_machine";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockPos ADJACENT_MACHINE_POS = new BlockPos(1, 1, 1);
    private static final BlockPos ADJACENT_ENERGY_POS = new BlockPos(0, 1, 1);
    private static final BlockPos ADJACENT_CHEST_POS = new BlockPos(2, 1, 1);
    private static final long STATUS_SAMPLE_MAX_ELAPSED_STEP_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long STATUS_SAMPLE_MAX_ETA_NANOS = TimeUnit.HOURS.toNanos(1L);
    private static final long AE2_STATUS_TOTAL = Integer.MAX_VALUE;

    private HighCapacityCraftingMachineGameTests() {
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void formalMachineDefaultBatchModeOnNewPlacement(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    helper.assertTrue(
                            machine.getBatchExecutionModeForTest() == BatchExecutionMode.SAME_PATTERN_DRAIN,
                            "new formal machine placement must default to SAME_PATTERN_DRAIN"
                    );
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertValueEqual(0L, snapshot.formalMachineOptimizationHitCount(),
                            "formal machine fixture must start with zero formal machine optimization hits");
                    helper.assertValueEqual(0L, snapshot.nonFormalProviderHitCount(),
                            "formal machine fixture must start with zero non-formal provider hits");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void formalMachineAdjacentAeBlocksDiscoverNodeHostCapability(GameTestHelper helper) {
        helper.setBlock(ADJACENT_MACHINE_POS, Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get());
        helper.setBlock(ADJACENT_ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(ADJACENT_CHEST_POS, AEBlocks.ME_CHEST.block());
        HighCapacityCraftingMachineBlockEntity machine = helper.getBlockEntity(ADJACENT_MACHINE_POS);
        CreativeEnergyCellBlockEntity energyCell = helper.getBlockEntity(ADJACENT_ENERGY_POS);
        MEChestBlockEntity meChest = helper.getBlockEntity(ADJACENT_CHEST_POS);
        helper.assertTrue(machine != null, "adjacent AE discovery test should place formal machine");
        helper.assertTrue(energyCell != null, "adjacent AE discovery test should place creative energy cell");
        helper.assertTrue(meChest != null, "adjacent AE discovery test should place ME chest");
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(machine.getMainNode().isReady(),
                        "formal machine node should become ready"))
                .thenWaitUntil(() -> helper.assertTrue(energyCell.getMainNode().isReady(),
                        "creative energy cell node should become ready"))
                .thenWaitUntil(() -> helper.assertTrue(meChest.getMainNode().isReady(),
                        "ME chest node should become ready"))
                .thenWaitUntil(() -> helper.assertTrue(machine.getMainNode().isActive(),
                        "formal machine should become active through adjacent AE discovery"))
                .thenExecute(() -> {
                    helper.assertTrue(machine.hasInWorldNodeHostCapabilityForTest(),
                            "formal machine should expose AE2 in-world node host capability");
                    helper.assertTrue(machine.getMainNode().getNode() != null
                                    && machine.getMainNode().getNode().getConnections().size() >= 2,
                            "formal machine should auto-connect to adjacent AE blocks without manual GridHelper wiring");
                    machine.forceProviderRefreshForTest();
                    helper.assertTrue(machine.snapshotBenchmark().forcedProviderRefreshCount() > 0,
                            "formal machine should support forced provider refresh after node ready");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void formalMachineLegacyMissingBatchModeFallsBackToOff(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    helper.assertTrue(machine.getBatchExecutionModeForTest() == BatchExecutionMode.SAME_PATTERN_DRAIN,
                            "new formal machine must start with batch mode enabled before legacy fallback check");
                    machine.loadTag(new CompoundTag(), helper.getLevel().registryAccess());
                    helper.assertTrue(machine.getBatchExecutionModeForTest() == BatchExecutionMode.OFF,
                            "legacy formal machine data without batchMode must fall back to OFF");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void formalMachineSpeedCardsScaleToSixteenLanes(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    int previousSoftBudget = 0;
                    int previousHardBudget = 0;
                    for (int speedCards = 0; speedCards < 5; speedCards++) {
                        machine.setSpeedCardsForTest(speedCards);
                        PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                        helper.assertTrue(snapshot.softBudget() >= previousSoftBudget,
                                "formal machine soft budget must not shrink when speed cards increase");
                        helper.assertTrue(snapshot.hardBudget() >= previousHardBudget,
                                "formal machine hard budget must not shrink when speed cards increase");
                        helper.assertTrue(snapshot.effectiveLaneCount() >= 1,
                                "formal machine must keep at least one effective lane");
                        previousSoftBudget = snapshot.softBudget();
                        previousHardBudget = snapshot.hardBudget();
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void formalMachineSpeedCardsDoNotDoubleAccelerateTaskTicks(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> specs = CraftingPatternDataset.smallMixedSet(helper.getLevel());

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_BUTTON);
                    machine.fillCraftingPatternsForTest(0, List.of(specs.get(0).encodedPattern()));
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    machine.setBaseOperationTicksForTest(20);
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                    int submitted = machine.submitPatternByDefinitionWithExecutionCountForTest(
                            specs.get(0).encodedPattern(),
                            1
                    );
                    helper.assertValueEqual(1, submitted, "speed card timing regression should submit one task");
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertValueEqual(4, snapshot.currentOperationTicks(),
                            "four speed cards should reduce a 20 tick task to four physical ticks");
                    helper.assertValueEqual(1, snapshot.perTickWorkUnits(),
                            "per tick work units must stay fixed to avoid double acceleration");
                })
                .thenExecuteAfter(1, () -> helper.assertValueEqual(0L, machine.snapshotBenchmark().jobsCompleted(),
                        "speed card timing regression must not complete in one tick"))
                .thenExecuteAfter(3, () -> helper.assertValueEqual(1L, machine.snapshotBenchmark().jobsCompleted(),
                        "speed card timing regression should complete after four physical ticks"))
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 180)
    public static void formalMachineSearchHighlightsPageMatchesAndRotates(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<ItemStack> patterns = CraftingPatternDataset.patternsOnly(
                CraftingPatternDataset.smallMixedSet(helper.getLevel())
        );

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.fillCraftingPatternsForTest(0, List.of(patterns.get(0), patterns.get(1)));
                    machine.fillCraftingPatternsForTest(27, List.of(patterns.get(2)));

                    int first = machine.searchAndHighlightNext("oak");
                    helper.assertValueEqual(0, first,
                            "first search should jump to the first oak match");
                    helper.assertValueEqual(0, machine.getPageIndex(),
                            "first search should stay on the first page");
                    helper.assertValueEqual(0b11, machine.getHighlightedPageSlotMask() & 0b11,
                            "first search should highlight all oak matches on the current page");

                    int second = machine.searchAndHighlightNext("oak");
                    helper.assertValueEqual(1, second,
                            "second search should rotate to the next oak match on the same page");
                    helper.assertValueEqual(0, machine.getPageIndex(),
                            "second search should keep page 1 while next match is still visible");

                    int third = machine.searchAndHighlightNext("oak");
                    helper.assertValueEqual(27, third,
                            "third search should rotate to the next oak match page");
                    helper.assertValueEqual(1, machine.getPageIndex(),
                            "third search should jump to page 2");
                    helper.assertValueEqual(1, machine.getHighlightedPageSlotMask(),
                            "page 2 should highlight its visible oak match");

                    machine.clearSearchState();
                    helper.assertValueEqual(0, machine.getHighlightedPageSlotMask(),
                            "clearing search should clear the page highlight mask");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 320)
    public static void formalMachineNetworkExposureSmoke(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> specs = CraftingPatternDataset.smallMixedChainedSet(helper.getLevel());
        List<AEItemKey> outputs = CraftingPatternDataset.outputKeys(specs);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    machine.fillCraftingPatternsRoundRobinForTest(CraftingPatternDataset.patternsOnly(specs), 64);
                })
                .thenWaitUntil(() -> {
                    for (AEItemKey output : outputs) {
                        List<IPatternDetails> patterns = List.copyOf(fixture.lookupCraftables(output));
                        helper.assertTrue(!patterns.isEmpty(), "formal machine should expose " + output);
                    }
                })
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.networkPatternExposureCount() >= outputs.size(),
                            "formal machine should record network exposure telemetry");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void formalMachineFixtureHasNoDedicatedCraftingCpuSmoke(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(fixture::assertNoDedicatedCraftingCpuPresent)
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void formalMachineAe2NativePatternCompatibility(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> specs = CraftingPatternDataset.ae2NativePatternSet(helper.getLevel().getServer().overworld());
        List<AEItemKey> outputs = CraftingPatternDataset.ae2NativeOutputs();
        List<GenericStack> nativeInputs = List.of(
                new GenericStack(AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 2),
                new GenericStack(AEItemKey.of(Items.DIAMOND_SWORD), 2),
                new GenericStack(AEItemKey.of(Items.NETHERITE_INGOT), 2),
                new GenericStack(AEItemKey.of(Items.STONE), 16),
                new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 16)
        );
        Map<AEItemKey, Future<ICraftingPlan>> futures = new LinkedHashMap<>();

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    machine.fillCraftingPatternsForTest(0, CraftingPatternDataset.patternsOnly(specs));
                    fixture.seedInputs(nativeInputs);
                })
                .thenWaitUntil(() -> fixture.assertSeedInputsVisible(nativeInputs))
                .thenWaitUntil(() -> {
                    for (AEItemKey output : outputs) {
                        helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                                "formal machine should expose native AE2 pattern output " + output);
                    }
                })
                .thenExecute(() -> {
                    fixture.assertNoDedicatedCraftingCpuPresent();
                    assertLookupContainsDefinitions(helper, fixture.lookupCraftables(AEItemKey.of(Items.OAK_SLAB)), specs.get(0), specs.get(1));
                    for (AEItemKey output : outputs) {
                        futures.put(output, fixture.beginCraftingPlanFuture(output, 1));
                    }
                })
                .thenWaitUntil(() -> {
                    for (Map.Entry<AEItemKey, Future<ICraftingPlan>> entry : futures.entrySet()) {
                        helper.assertTrue(entry.getValue().isDone(),
                                "native AE2 planning future should complete for " + entry.getKey());
                    }
                })
                .thenExecute(() -> {
                    for (Map.Entry<AEItemKey, Future<ICraftingPlan>> entry : futures.entrySet()) {
                        ICraftingPlan plan = resolveCompletedPlan(helper, fixture, entry.getValue(), entry.getKey());
                        assertPlanMatches(helper, entry.getKey(), plan);
                    }
                    helper.assertTrue(machine.isSupportedEncodedPattern(specs.get(1).encodedPattern()),
                            "formal machine must accept substitution-enabled crafting patterns");
                    helper.assertTrue(machine.isSupportedEncodedPattern(specs.get(2).encodedPattern()),
                            "formal machine must accept stonecutting patterns");
                    helper.assertTrue(machine.isSupportedEncodedPattern(specs.get(4).encodedPattern()),
                            "formal machine must accept smithing patterns");
                    fixture.clearAeStorage();
                    int submitted = machine.submitPatternsByDefinitionSequenceForTest(
                            CraftingPatternDataset.patternsOnly(specs)
                    );
                    helper.assertValueEqual(specs.size(), submitted,
                            "formal machine should locally execute every native AE2 pattern definition once");
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= specs.size(),
                            "formal machine should complete native AE2 local execution");
                    for (AEItemKey output : outputs) {
                        helper.assertTrue(fixture.countStored(output) > 0,
                                "formal machine should return native AE2 output to AE storage: " + output);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void formalMachineUnsupportedLargePlanningFailsFast(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> specs =
                CraftingPatternDataset.ae2NativePatternSet(helper.getLevel().getServer().overworld());
        AEItemKey output = AEItemKey.of(Items.OAK_SLAB);
        long requestedAmount = 16_384L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.fillCraftingPatternsForTest(0, List.of(
                            specs.get(0).encodedPattern(),
                            specs.get(1).encodedPattern()
                    ));
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                            "formal machine should expose unsupported duplicate-output slab patterns");
                    helper.assertTrue(machine.countAvailablePatternsForOutputForTest(output) >= 2,
                            "formal machine unsupported planning test must keep duplicate-output patterns visible");
                })
                .thenExecute(() -> futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount))
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "unsupported formal-machine large planning future should fail fast"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    helper.assertTrue(plan != null,
                            "unsupported formal-machine large planning should return a bounded missing plan");
                    helper.assertTrue(plan.simulation(),
                            "unsupported formal-machine large planning result should remain a simulation");
                    helper.assertTrue(plan.patternTimes().isEmpty(),
                            "unsupported formal-machine large planning must not submit ambiguous pattern work");
                    helper.assertValueEqual(requestedAmount, plan.missingItems().get(output),
                            "unsupported formal-machine large planning should report the requested output as missing");

                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.planningRequestCount() >= 1,
                            "unsupported formal-machine large planning should record a planning request");
                    helper.assertValueEqual(0L, snapshot.planningAggregationHitCount(),
                            "unsupported formal-machine large planning must not report a deterministic hit");
                    helper.assertTrue(snapshot.planningAggregationFallbackCount() > 0,
                            "unsupported formal-machine large planning should record a formal fallback");
                    helper.assertTrue(snapshot.planningFailureCount() > 0,
                            "unsupported formal-machine large planning should record a bounded failure plan");
                    helper.assertValueEqual(0L, snapshot.nonFormalProviderHitCount(),
                            "unsupported formal-machine large planning must not leak into non-formal providers");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 320)
    public static void formalMachineWaitsForAeStorage(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> specs = CraftingPatternDataset.smallMixedSet(helper.getLevel());

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    machine.fillCraftingPatternsForTest(0, CraftingPatternDataset.patternsOnly(specs));
                    machine.setSpeedCardsForTest(3);
                    machine.setBaseOperationTicksForTest(4);
                    fixture.clearAeStorage();
                    fixture.removeStorageCell();
                    int submitted = machine.submitFirstAvailablePatternForTest(1);
                    helper.assertValueEqual(1, submitted, "formal machine should accept the initial job");
                })
                .thenExecuteAfter(8, () -> {
                    helper.assertTrue(machine.isWaitingAeReturn(), "formal machine should stall when AE storage is full");
                    helper.assertTrue(machine.isBusy(), "waiting for AE storage must count as busy");
                })
                .thenExecute(fixture::installStorageCell)
                .thenWaitUntil(() -> {
                    helper.assertTrue(!machine.isWaitingAeReturn(), "formal machine should resume after AE storage frees up");
                    helper.assertTrue(machine.snapshotBenchmark().aeStorageInsertSuccessCount() > 0,
                            "formal machine should eventually insert completed output into AE storage");
                })
                .thenSucceed();
    }

    @GameTest(template = DUAL_TEMPLATE, batch = BATCH, timeoutTicks = 240)
    public static void multipleFormalMachinesStayIsolated(GameTestHelper helper) {
        MultipleFormalMachinesGameTestFixture fixture = MultipleFormalMachinesGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machineA = fixture.machineA();
        HighCapacityCraftingMachineBlockEntity machineB = fixture.machineB();
        List<CraftingPatternDataset.EncodedPatternSpec> mixed = CraftingPatternDataset.smallMixedSet(helper.getLevel());
        List<CraftingPatternDataset.EncodedPatternSpec> chained = CraftingPatternDataset.chainedSet(helper.getLevel());

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machineA.fillCraftingPatternsForTest(0, CraftingPatternDataset.patternsOnly(mixed));
                    machineB.fillCraftingPatternsForTest(0, CraftingPatternDataset.patternsOnly(chained));
                    machineA.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    machineB.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    int highlighted = machineA.searchAndHighlightFirst("ladder");
                    helper.assertTrue(highlighted < 0, "machine A should not find machine B's pattern set");
                    int machineBHighlight = machineB.searchAndHighlightFirst("ladder");
                    helper.assertTrue(machineBHighlight >= 0, "machine B should find its own ladder pattern");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(-1, machineA.getHighlightedGlobalSlot(),
                            "machine A highlight must stay isolated");
                    helper.assertTrue(machineB.getHighlightedGlobalSlot() >= 0,
                            "machine B highlight must stay local to machine B");
                    helper.assertValueEqual(0, machineA.getSearchResultCount(),
                            "machine A search result count must remain local");
                    helper.assertTrue(machineB.getSearchResultCount() > 0,
                            "machine B search result count must remain local");
                    helper.assertTrue(machineA.getGrid() == machineB.getGrid(),
                            "both formal machines must share the same AE grid without sharing local UI state");
                })
                .thenSucceed();
    }

    @GameTest(template = DUAL_TEMPLATE, batch = BATCH, timeoutTicks = 360)
    public static void multipleFormalMachinesBatchIsolation(GameTestHelper helper) {
        MultipleFormalMachinesGameTestFixture fixture = MultipleFormalMachinesGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machineA = fixture.machineA();
        HighCapacityCraftingMachineBlockEntity machineB = fixture.machineB();
        List<CraftingPatternDataset.EncodedPatternSpec> chained = CraftingPatternDataset.chainedSet(helper.getLevel());
        List<CraftingPatternDataset.EncodedPatternSpec> mixed = CraftingPatternDataset.smallMixedChainedSet(helper.getLevel());
        List<AEItemKey> ladderBurst = repeatedOutputs(AEItemKey.of(Items.LADDER), 16);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machineA.fillCraftingPatternsRoundRobinForTest(CraftingPatternDataset.patternsOnly(chained), 64);
                    machineB.fillCraftingPatternsRoundRobinForTest(CraftingPatternDataset.patternsOnly(mixed), 64);
                    machineA.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    machineB.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    machineA.setBaseOperationTicksForTest(40);
                    machineB.setBaseOperationTicksForTest(40);
                    machineA.setSpeedCardsForTest(3);
                    machineB.setSpeedCardsForTest(3);
                    int submittedA = machineA.submitPatternsByOutputSequenceForTest(ladderBurst);
                    int submittedB = machineB.submitAvailablePatternsRoundRobinForTest(16, 16);
                    helper.assertValueEqual(16, submittedA, "machine A should accept chained batch burst");
                    helper.assertValueEqual(16, submittedB, "machine B should accept mixed OFF run");
                })
                .thenExecuteAfter(10, () -> {
                    PatternBenchmarkSnapshot snapshotA = machineA.snapshotBenchmark();
                    PatternBenchmarkSnapshot snapshotB = machineB.snapshotBenchmark();
                    helper.assertTrue(snapshotA.jobsSubmitted() > 0,
                            "machine A should keep local batch work isolated");
                    helper.assertValueEqual(0L, snapshotB.coalescedJobsSaved(),
                            "machine B OFF run must not record coalescing");
                    helper.assertTrue(snapshotA.peakRunningUniquePatterns() >= 1,
                            "machine A should remain active under chained batching");
                    helper.assertTrue(snapshotB.peakRunningUniquePatterns() >= 4,
                            "machine B fairness should remain independent of machine A batching");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 360)
    public static void formalMachineBatchWaitingAeStorage(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> chained = CraftingPatternDataset.chainedSet(helper.getLevel());
        List<AEItemKey> ladderBurst = repeatedOutputs(AEItemKey.of(Items.LADDER), 16);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.fillCraftingPatternsRoundRobinForTest(CraftingPatternDataset.patternsOnly(chained), 64);
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    machine.setBaseOperationTicksForTest(4);
                    machine.setSpeedCardsForTest(3);
                    fixture.clearAeStorage();
                    fixture.removeStorageCell();
                    int submitted = machine.submitPatternsByOutputSequenceForTest(ladderBurst);
                    helper.assertValueEqual(16, submitted, "formal machine should accept chained burst under batching");
                })
                .thenExecuteAfter(8, () -> {
                    helper.assertTrue(machine.isWaitingAeReturn(), "formal machine should enter waiting-ae under batch stall");
                    helper.assertValueEqual(1L, machine.getPendingAeReturnCount(),
                            "formal machine should only expose one pending AE return");
                    helper.assertTrue(machine.getPendingLogicalExecutionCountForTest() >= 1,
                            "pending return must preserve at least one logical execution");
                })
                .thenExecute(fixture::installStorageCell)
                .thenWaitUntil(() -> {
                    helper.assertTrue(!machine.isWaitingAeReturn(), "formal machine should recover after AE storage returns");
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertValueEqual(16L, snapshot.jobsCompleted(),
                            "formal machine should complete the whole chained batch after recovery");
                    helper.assertTrue(snapshot.batchedAeReturnCount() > 0,
                            "formal machine chained batch should preserve aggregated AE returns");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 480)
    public static void formalMachineBatchNativePatterns(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> specs = CraftingPatternDataset.ae2NativePatternSet(helper.getLevel().getServer().overworld());
        List<AEItemKey> outputs = CraftingPatternDataset.outputKeys(specs);
        List<GenericStack> nativeInputs = List.of(
                new GenericStack(AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 4),
                new GenericStack(AEItemKey.of(Items.DIAMOND_SWORD), 4),
                new GenericStack(AEItemKey.of(Items.NETHERITE_INGOT), 4),
                new GenericStack(AEItemKey.of(Items.STONE), 32),
                new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 32)
        );
        List<AEItemKey> burst = repeatedNativeOutputs(outputs, 2);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.fillCraftingPatternsForTest(0, CraftingPatternDataset.patternsOnly(specs));
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    machine.setBaseOperationTicksForTest(4);
                    machine.setSpeedCardsForTest(3);
                    fixture.clearAeStorage();
                    fixture.seedInputs(nativeInputs);
                })
                .thenWaitUntil(() -> fixture.assertSeedInputsVisible(nativeInputs))
                .thenExecute(() -> {
                    int submitted = machine.submitPatternsByOutputSequenceForTest(burst);
                    helper.assertValueEqual(burst.size(), submitted,
                            "formal machine should submit native AE2 outputs twice each under batching");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= burst.size(),
                        "formal machine native batch run should finish all logical jobs"))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    for (CraftingPatternDataset.EncodedPatternSpec spec : specs) {
                        helper.assertTrue(machine.countCompletedOutputForTest(spec.outputKey()) > 0,
                                "native AE2 batch run should execute and record output: " + spec.id());
                    }
                    helper.assertTrue(snapshot.aeStorageInsertSuccessCount() >= outputs.size(),
                            "native AE2 batch run should complete AE return inserts for native outputs");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 520)
    public static void formalMachineChainedBatchPerformanceLadderBurst(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> chained = CraftingPatternDataset.chainedSet(helper.getLevel());
        List<AEItemKey> ladderBurst = repeatedOutputs(AEItemKey.of(Items.LADDER), 32);
        long[] offStartTick = new long[1];
        long[] offEndTick = new long[1];
        long[] batchStartTick = new long[1];
        long[] batchEndTick = new long[1];
        PatternBenchmarkSnapshot[] offSnapshot = new PatternBenchmarkSnapshot[1];
        PatternBenchmarkSnapshot[] batchSnapshot = new PatternBenchmarkSnapshot[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    configureChainBenchmarkMachine(machine, fixture, chained);
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    offStartTick[0] = helper.getLevel().getGameTime();
                    int submitted = machine.submitPatternsByOutputSequenceForTest(ladderBurst);
                    helper.assertValueEqual(32, submitted,
                            "OFF baseline should accept the full chained ladder burst");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= ladderBurst.size(),
                        "OFF baseline should finish the chained ladder burst"))
                .thenExecute(() -> {
                    offEndTick[0] = helper.getLevel().getGameTime();
                    offSnapshot[0] = machine.snapshotBenchmark();

                    helper.assertTrue(!machine.isBusy(), "machine should be idle before batch benchmark rerun");
                    machine.clearPatternsForTest();
                    machine.resetBenchmarkCountersForTest();

                    configureChainBenchmarkMachine(machine, fixture, chained);
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    batchStartTick[0] = helper.getLevel().getGameTime();
                    int submitted = machine.submitPatternsByOutputSequenceForTest(ladderBurst);
                    helper.assertValueEqual(32, submitted,
                            "batch run should accept the full chained ladder burst");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= ladderBurst.size(),
                        "batch run should finish the chained ladder burst"))
                .thenExecute(() -> {
                    batchEndTick[0] = helper.getLevel().getGameTime();
                    batchSnapshot[0] = machine.snapshotBenchmark();

                    assertBatchPerformanceImprovement(
                            helper,
                            "ladder_burst_32",
                            offStartTick[0],
                            offEndTick[0],
                            offSnapshot[0],
                            batchStartTick[0],
                            batchEndTick[0],
                            batchSnapshot[0]
                    );
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 520)
    public static void formalMachineChainedBatchPerformanceGroupedBurst(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<CraftingPatternDataset.EncodedPatternSpec> chained = CraftingPatternDataset.chainedSet(helper.getLevel());
        List<AEItemKey> groupedBurst = groupedOutputs(CraftingPatternDataset.chainEndpoints(), 4);
        long[] offStartTick = new long[1];
        long[] offEndTick = new long[1];
        long[] batchStartTick = new long[1];
        long[] batchEndTick = new long[1];
        PatternBenchmarkSnapshot[] offSnapshot = new PatternBenchmarkSnapshot[1];
        PatternBenchmarkSnapshot[] batchSnapshot = new PatternBenchmarkSnapshot[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    configureChainBenchmarkMachine(machine, fixture, chained);
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    offStartTick[0] = helper.getLevel().getGameTime();
                    int submitted = machine.submitPatternsByOutputSequenceForTest(groupedBurst);
                    helper.assertValueEqual(groupedBurst.size(), submitted,
                            "OFF baseline should accept the grouped chained burst");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= groupedBurst.size(),
                        "OFF baseline should finish the grouped chained burst"))
                .thenExecute(() -> {
                    offEndTick[0] = helper.getLevel().getGameTime();
                    offSnapshot[0] = machine.snapshotBenchmark();

                    helper.assertTrue(!machine.isBusy(), "machine should be idle before grouped batch benchmark rerun");
                    machine.clearPatternsForTest();
                    machine.resetBenchmarkCountersForTest();

                    configureChainBenchmarkMachine(machine, fixture, chained);
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    batchStartTick[0] = helper.getLevel().getGameTime();
                    int submitted = machine.submitPatternsByOutputSequenceForTest(groupedBurst);
                    helper.assertValueEqual(groupedBurst.size(), submitted,
                            "batch run should accept the grouped chained burst");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= groupedBurst.size(),
                        "batch run should finish the grouped chained burst"))
                .thenExecute(() -> {
                    batchEndTick[0] = helper.getLevel().getGameTime();
                    batchSnapshot[0] = machine.snapshotBenchmark();

                    helper.assertTrue(batchSnapshot[0].peakRunningUniquePatterns() >= 4,
                            "grouped chained batch benchmark must preserve four unique running patterns");
                    assertBatchPerformanceImprovement(
                            helper,
                            "grouped_chain_endpoints_x4",
                            offStartTick[0],
                            offEndTick[0],
                            offSnapshot[0],
                            batchStartTick[0],
                            batchEndTick[0],
                            batchSnapshot[0]
                    );
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 480)
    public static void formalMachineDrainAggregatesLargePlankRequest(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<AEItemKey> plankBurst = repeatedOutputs(AEItemKey.of(Items.OAK_PLANKS), 250);
        ItemStack[] plankGrid = new ItemStack[]{
                new ItemStack(Items.OAK_LOG),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY
        };

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.clearAeStorage();
                    int inserted = machine.fillCraftingPatternsForTest(0, 1, plankGrid);
                    helper.assertValueEqual(1, inserted,
                            "formal machine should encode a single log-to-planks pattern");
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    machine.setBaseOperationTicksForTest(40);
                    machine.setSpeedCardsForTest(4);
                    int submitted = machine.submitPatternsByOutputSequenceForTest(plankBurst);
                    helper.assertValueEqual(250, submitted,
                            "1000 planks request should submit 250 logical log-to-planks jobs");

                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.coalescedJobsSaved() > 0,
                            "drain mode should coalesce the large plank burst");
                    helper.assertTrue(snapshot.maxExecutionCountPerTaskObserved() > 1,
                            "drain mode should aggregate multiple logical executions into physical tasks");
                    helper.assertTrue(snapshot.queuedTasks() < 250,
                            "drain mode should reduce queued physical task count");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= 250L,
                        "formal machine should finish all 250 logical plank jobs"))
                .thenWaitUntil(() -> helper.assertTrue(fixture.countStored(AEItemKey.of(Items.OAK_PLANKS)) == 1000L,
                        "1000 planks request should become visible in AE storage cache"))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.countStored(AEItemKey.of(Items.OAK_PLANKS)) == 1000L,
                            "1000 planks request should return exactly 1000 planks to AE storage");
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.peakRunningTasks() >= 1,
                            "1000 planks request should use at least one running physical task");
                    helper.assertTrue(snapshot.batchedAeReturnCount() > 0,
                            "1000 planks request should complete through batched AE returns");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 520)
    public static void formalMachineDrainMergesIntoRunningPlankTask(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        List<AEItemKey> remainingPlankBurst = repeatedOutputs(AEItemKey.of(Items.OAK_PLANKS), 249);
        ItemStack[] plankGrid = new ItemStack[]{
                new ItemStack(Items.OAK_LOG),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY
        };

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.clearAeStorage();
                    int inserted = machine.fillCraftingPatternsForTest(0, 1, plankGrid);
                    helper.assertValueEqual(1, inserted,
                            "formal machine should encode a single log-to-planks pattern");
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    machine.setBaseOperationTicksForTest(80);
                    int submitted = machine.submitPatternsByOutputSequenceForTest(
                            repeatedOutputs(AEItemKey.of(Items.OAK_PLANKS), 1)
                    );
                    helper.assertValueEqual(1, submitted,
                            "formal machine should accept the first plank job before running merge");
                })
                .thenExecuteAfter(5, () -> {
                    PatternBenchmarkSnapshot beforeMerge = machine.snapshotBenchmark();
                    helper.assertValueEqual(1, beforeMerge.runningTasks(),
                            "first plank job should be running before the drain merge");
                    helper.assertValueEqual(0, beforeMerge.queuedTasks(),
                            "running merge test should start without queued tasks");
                    helper.assertTrue(!machine.isBusy(),
                            "drain mode must keep accepting pushes while a compatible task can merge");

                    int submitted = machine.submitPatternsByOutputSequenceForTest(remainingPlankBurst);
                    helper.assertTrue(submitted > 0,
                            "drain mode should accept additional plank jobs while the first job is running");

                    PatternBenchmarkSnapshot afterMerge = machine.snapshotBenchmark();
                    helper.assertTrue(afterMerge.runningTasks() >= 1,
                            "drain mode should keep running physical tasks after merging");
                    helper.assertTrue(afterMerge.maxExecutionCountPerTaskObserved() > 1,
                            "drain mode should aggregate into running physical tasks");
                    helper.assertTrue(afterMerge.coalescedJobsSaved() > 0,
                            "running merge should save physical tasks");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= 250L,
                        "formal machine should finish all merged running plank jobs"))
                .thenWaitUntil(() -> helper.assertTrue(fixture.countStored(AEItemKey.of(Items.OAK_PLANKS)) == 1000L,
                        "running merge should return exactly 1000 planks to AE storage"))
                .thenExecute(() -> helper.assertTrue(machine.snapshotBenchmark().batchedAeReturnCount() > 0,
                        "running merge should complete through batched AE returns"))
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void formalMachineLargeExecutionCountSlicesCompletion(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        ItemStack[] plankGrid = new ItemStack[]{
                new ItemStack(Items.OAK_LOG),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY
        };
        int logicalExecutions = 250_000;

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.clearAeStorage();
                    fixture.installCreativeStorageCell(Items.OAK_PLANKS);
                    int inserted = machine.fillCraftingPatternsForTest(0, 1, plankGrid);
                    helper.assertValueEqual(1, inserted,
                            "formal machine large completion test should encode one plank pattern");
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    machine.setBaseOperationTicksForTest(2);
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();

                    int submitted = machine.submitFirstAvailablePatternWithExecutionCountForTest(logicalExecutions);
                    helper.assertValueEqual(logicalExecutions, submitted,
                            "formal machine large completion test should submit requested logical executions");
                })
                .thenExecuteAfter(10, () -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.completionSlicesProcessed() > 0,
                            "formal machine large completion test should process completion slices");
                    helper.assertTrue(snapshot.pendingCompletionTicks() > 0,
                            "formal machine large completion test should spend time in pending completion");
                    helper.assertTrue(snapshot.largestCompletionSliceExecutionsObserved() < logicalExecutions,
                            "formal machine large completion test must not finish the whole batch in one slice");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.snapshotBenchmark().jobsCompleted() >= logicalExecutions,
                        "formal machine large completion test should eventually complete all logical executions"))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertValueEqual((long) logicalExecutions, snapshot.jobsSubmitted(),
                            "formal machine large completion test should record all submitted executions");
                    helper.assertValueEqual((long) logicalExecutions, snapshot.jobsCompleted(),
                            "formal machine large completion test should record all completed executions");
                    helper.assertTrue(snapshot.completionSlicesProcessed() > 1,
                            "formal machine large completion test should require multiple slices");
                    helper.assertTrue(snapshot.templatedCompletionHitCount() > 0,
                            "formal machine large completion test should use templated completion");
                    helper.assertTrue(snapshot.templatedCompletionSavedExecutions() > 0,
                            "formal machine large completion test should save repeated assembly work");
                    helper.assertTrue(!machine.isWaitingAeReturn(),
                            "formal machine large completion test should not end stuck in waiting-ae");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void formalMachineLargePlanningWoodenPickaxe100000(GameTestHelper helper) {
        runLargeWoodenPickaxePlanning(helper, 100_000L);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1600)
    public static void formalMachineLargePlanningWoodenPickaxe1000000(GameTestHelper helper) {
        runLargeWoodenPickaxePlanning(helper, 1_000_000L);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void formalMachineEmptyPlanningFutureCompletes(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.DIAMOND_BLOCK);
        long requestedAmount = 16_384L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.resetBenchmarkCountersForTest();
                    helper.assertTrue(fixture.lookupCraftables(output).isEmpty(),
                            "empty planning regression must start without craftable target patterns");
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "empty formal machine planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    helper.assertTrue(plan != null, "empty planning future should resolve to a missing-item plan");
                    GenericStack finalOutput = plan.finalOutput();
                    helper.assertTrue(finalOutput != null && output.equals(finalOutput.what()),
                            "empty planning future should preserve the requested output");
                    helper.assertValueEqual(requestedAmount, finalOutput.amount(),
                            "empty planning future should preserve the requested amount");
                    helper.assertTrue(plan.simulation(), "empty planning result should be a simulation plan");
                    helper.assertTrue(plan.patternTimes().isEmpty(),
                            "empty planning result should not contain pattern work");
                    helper.assertTrue(plan.usedItems().isEmpty(),
                            "empty planning result should not consume stored inputs");
                    helper.assertTrue(plan.emittedItems().isEmpty(),
                            "empty planning result should not emit intermediate outputs");
                    helper.assertValueEqual(requestedAmount, plan.missingItems().get(output),
                            "empty planning result should report the requested output as missing");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void formalMachineDeepLecternSmallRootPlanning512(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.LECTERN);
        long requestedAmount = 512L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        long[] startedAtNanos = new long[1];
        List<GenericStack> seedInputs = List.of(
                new GenericStack(AEItemKey.of(Items.OAK_LOG), 4L),
                new GenericStack(AEItemKey.of(Items.SUGAR_CANE), 9L),
                new GenericStack(AEItemKey.of(Items.LEATHER), 3L)
        );

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.seedInputs(seedInputs);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.deepLecternPlanningSet(helper.getLevel()))
                    );
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> fixture.assertSeedInputsVisible(seedInputs))
                .thenWaitUntil(() -> {
                    List<IPatternDetails> patterns = List.copyOf(fixture.lookupCraftables(output));
                    helper.assertTrue(!patterns.isEmpty(), "formal machine should expose lectern planning path");
                })
                .thenExecute(() -> {
                    fixture.assertNoDedicatedCraftingCpuPresent();
                    startedAtNanos[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine deep lectern planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    helper.assertValueEqual(requestedAmount, plan.finalOutput().amount(),
                            "deep lectern planning should preserve requested amount");
                    helper.assertTrue(plan.missingItems().get(AEItemKey.of(Items.OAK_LOG)) > 0L,
                            "deep lectern small-root planning should report missing oak logs");
                    helper.assertTrue(plan.missingItems().get(AEItemKey.of(Items.SUGAR_CANE)) > 0L,
                            "deep lectern small-root planning should report missing sugar cane");
                    helper.assertTrue(plan.missingItems().get(AEItemKey.of(Items.LEATHER)) > 0L,
                            "deep lectern small-root planning should report missing leather");
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos[0]);
                    helper.assertTrue(snapshot.planningRequestCount() >= 1,
                            "deep lectern planning should record an aggregation request");
                    helper.assertTrue(snapshot.planningSuccessCount() >= 1,
                            "deep lectern planning should record an aggregation success");
                    helper.assertTrue(snapshot.planningAggregationHitCount() > 0,
                            "deep lectern planning should hit formal machine aggregation");
                    helper.assertTrue(snapshot.planningWorkTriggeredCount() > 0,
                            "deep lectern planning must be triggered by estimated work, not root amount");
                    helper.assertTrue(snapshot.planningEstimatedWorkMax() >= 16_384L,
                            "deep lectern estimated work should exceed the work trigger threshold");
                    helper.assertTrue(snapshot.planningChunkCount() > 1 || snapshot.deterministicPlanningHitCount() > 0,
                            "deep lectern planning should either split chunks or hit deterministic planning");
                    helper.assertValueEqual(requestedAmount, snapshot.planningRequestedAmountMax(),
                            "deep lectern telemetry should capture requested amount");
                    logPlanningBenchmark("formal_machine_deep_lectern_small_root_planning",
                            requestedAmount,
                            elapsedMillis,
                            snapshot);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void formalMachineDeepLecternSingleRootUsesDeterministicPlanning(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.LECTERN);
        long requestedAmount = 512L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.deepLecternPlanningSet(helper.getLevel()))
                    );
                })
                .thenWaitUntil(() -> {
                    List<IPatternDetails> patterns = List.copyOf(fixture.lookupCraftables(output));
                    helper.assertTrue(!patterns.isEmpty(),
                            "single-root deep chain planning should expose the target output before planning");
                })
                .thenExecute(() -> {
                    machine.resetBenchmarkCountersForTest();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "single-root deep chain planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    helper.assertValueEqual(requestedAmount, plan.finalOutput().amount(),
                            "single-root deep chain planning should preserve requested amount");
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.planningWorkTriggeredCount() > 0,
                            "single-root deep chain planning must be triggered by estimated work");
                    helper.assertTrue(snapshot.deterministicPlanningHitCount() > 0,
                            "single-root deep chain should use deterministic formal machine planning");
                    helper.assertTrue(snapshot.deterministicPlanningFallbackCount() == 0,
                            "single-root deterministic planning should not fall back");
                })
                .thenSucceed();
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 3600)
    public static void formalMachineWoodenPickaxeSubmitExecuteSmoke100000(GameTestHelper helper) {
        runLargeWoodenPickaxeSubmitExecute(helper, 100_000L, "formal_machine_submit_execute_smoke");
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 7200)
    public static void formalMachineWoodenPickaxeSubmitExecuteBenchmark1000000(GameTestHelper helper) {
        runLargeWoodenPickaxeSubmitExecute(helper, 1_000_000L, "formal_machine_submit_execute_benchmark");
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void formalMachineCraftConfirmCraftLessPlanningUsesAggregation(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.WOODEN_PICKAXE);
        CraftConfirmMenu[] menuHolder = new CraftConfirmMenu[1];
        Player[] playerHolder = new Player[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel()))
                    );
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "formal machine should expose wooden pickaxe path to CraftConfirmMenu"))
                .thenExecute(() -> {
                    Player player = menuPlayer(helper);
                    playerHolder[0] = player;
                    CraftConfirmMenu menu = new CraftConfirmMenu(2, player.getInventory(), fixture.meChest());
                    menuHolder[0] = menu;
                    helper.assertTrue(menu.getHost() == fixture.meChest(),
                            "CraftConfirmMenu planning coverage must use the AE terminal host");
                    helper.assertTrue(menu.planJob(output, 1_000_000, CalculationStrategy.CRAFT_LESS),
                            "CRAFT_LESS planning should start from the real CraftConfirmMenu path");
                })
                .thenWaitUntil(() -> {
                    CraftConfirmMenu menu = requireCraftConfirmMenu(menuHolder);
                    broadcastChangesForMockPlayer(helper, menu::broadcastChanges);
                    helper.assertTrue(menu.getPlan() != null,
                            "CRAFT_LESS planning should resolve instead of stalling");
                })
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.planningRequestCount() >= 1,
                            "CraftConfirmMenu CRAFT_LESS planning should record a planning request");
                    helper.assertTrue(snapshot.planningAggregationHitCount() > 0,
                            "CraftConfirmMenu CRAFT_LESS planning should hit formal planning aggregation");
                    helper.assertValueEqual(0L, snapshot.planningAggregationFallbackCount(),
                            "CraftConfirmMenu CRAFT_LESS planning should not fall back to native planning");
                    helper.assertTrue(snapshot.deterministicPlanningHitCount() > 0,
                            "CraftConfirmMenu CRAFT_LESS planning should use deterministic formal planning");
                    CraftConfirmMenu menu = menuHolder[0];
                    Player player = playerHolder[0];
                    if (menu != null && player != null) {
                        menu.removed(player);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 2400)
    public static void formalMachineFastPathStatusTimingMonotonic(GameTestHelper helper) {
        FormalMachineAutoCraftingGameTestFixture fixture = FormalMachineAutoCraftingGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.WOODEN_PICKAXE);
        long requestedAmount = 100_000L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        ICraftingPlan[] planHolder = new ICraftingPlan[1];
        ICraftingSubmitResult[] submitHolder = new ICraftingSubmitResult[1];
        CraftingStatus[] lastStatus = new CraftingStatus[1];
        CraftingJobStatus[] lastJobStatus = new CraftingJobStatus[1];
        long[] lastCompleted = new long[]{-1L};
        long[] lastJobProgress = new long[]{-1L};
        int[] samples = new int[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel()))
                    );
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> fixture.assertStoredLive(AEItemKey.of(Items.OAK_LOG), 1L))
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "formal machine should expose wooden pickaxe path for status timing"))
                .thenExecute(() -> futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount))
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine status timing plan should complete"))
                .thenExecute(() -> {
                    planHolder[0] = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, planHolder[0]);
                    submitHolder[0] = fixture.submitCraftingPlan(planHolder[0]);
                    helper.assertTrue(submitHolder[0] != null && submitHolder[0].successful(),
                            "formal machine status timing job should submit successfully");
                    helper.assertTrue(submitHolder[0].link() != null,
                            "formal machine status timing job should have a requester link");
                    CraftingCPUCluster submittedCpu = fixture.cpuCluster();
                    helper.assertTrue(submittedCpu != null && submittedCpu.isBusy(),
                            "formal machine status timing job should occupy the dedicated CPU immediately after submit");
                    helper.assertFalse(FormalMachineCraftingTimingService.hasActiveState(submittedCpu.craftingLogic),
                            "formal machine timing state should stay pending until fast path accepts the job");
                })
                .thenWaitUntil(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.fastPathAcceptedCount() > 0,
                            "formal machine status timing test should enter fast path");
                    CraftingCPUCluster submittedCpu = fixture.cpuCluster();
                    helper.assertTrue(submittedCpu != null
                                    && FormalMachineCraftingTimingService.hasActiveState(submittedCpu.craftingLogic),
                            "formal machine timing state should remain active before final output completes");
                    helper.assertValueEqual(0L, fixture.requester().countAcceptedOutput(output),
                            "formal machine timing state should be active before requester receives final output");
                })
                .thenExecute(() -> assertCraftingCpuMenuHeartbeat(helper, fixture))
                .thenExecuteAfter(1, () -> sampleCraftingStatus(
                        helper, fixture.cpuCluster(), lastStatus, lastJobStatus, lastCompleted, lastJobProgress, samples))
                .thenExecuteAfter(1, () -> sampleCraftingStatus(
                        helper, fixture.cpuCluster(), lastStatus, lastJobStatus, lastCompleted, lastJobProgress, samples))
                .thenExecuteAfter(1, () -> sampleCraftingStatus(
                        helper, fixture.cpuCluster(), lastStatus, lastJobStatus, lastCompleted, lastJobProgress, samples))
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.requester().countAcceptedOutput(output) >= requestedAmount,
                        "formal machine status timing job should finish all output"))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(samples[0] >= 2,
                            "formal machine status timing test should sample at least two live CPU statuses");
                    helper.assertTrue(snapshot.formalMachineOptimizationHitCount() > 0,
                            "formal machine status timing test should record formal fast path hits");
                    helper.assertTrue(snapshot.formalTimingCorrectionCount() >= 0,
                            "formal machine status timing bridge counters should stay readable");
                    helper.assertTrue(snapshot.formalTimingProgressClampCount() >= 0,
                            "formal machine status timing progress counter should stay readable");
                    helper.assertTrue(snapshot.formalTimingEtaClampCount() >= 0,
                            "formal machine status timing ETA counter should stay readable");
                    helper.assertTrue(!fixture.requester().isJobCanceled(),
                            "formal machine status timing job must not be canceled");
                    helper.assertTrue(fixture.requester().isJobFinished(),
                            "formal machine status timing job should finish");
                })
                .thenSucceed();
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void formalMachineStandaloneSubmitStoresFinalOutput(GameTestHelper helper) {
        FormalMachineAutoCraftingGameTestFixture fixture = FormalMachineAutoCraftingGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey input = AEItemKey.of(Items.OAK_LOG);
        AEItemKey output = AEItemKey.of(Items.WOODEN_PICKAXE);
        long requestedAmount = 32L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        ICraftingPlan[] planHolder = new ICraftingPlan[1];
        ICraftingSubmitResult[] submitHolder = new ICraftingSubmitResult[1];
        long expectedLogicalExecutions = computeWoodenPickaxeLogicalExecutions(requestedAmount);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.clearAeStorage();
                    long inserted = fixture.insertStored(input, 64L);
                    helper.assertValueEqual(64L, inserted,
                            "standalone submit test should seed ordinary AE storage with oak logs");
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel()))
                    );
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> fixture.assertStoredLive(AEItemKey.of(Items.OAK_LOG), 1L))
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "standalone submit test should expose wooden pickaxe path"))
                .thenExecute(() -> futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount))
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "standalone submit planning future should complete"))
                .thenExecute(() -> {
                    planHolder[0] = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, planHolder[0]);
                    logPlanShape("formal_machine_standalone_submit", requestedAmount, planHolder[0]);
                    submitHolder[0] = fixture.submitCraftingPlanStandalone(planHolder[0]);
                    helper.assertTrue(submitHolder[0] != null && submitHolder[0].successful(),
                            "standalone submit should match CraftConfirmMenu submit semantics");
                    helper.assertTrue(submitHolder[0].link() == null,
                            "standalone submit should not return a requester link");
                    helper.assertFalse(FormalMachineCraftingTimingService.hasActiveState(fixture.cpuCluster().craftingLogic),
                            "standalone submit should keep timing state pending until fast path accepts the job");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        machine.snapshotBenchmark().fastPathAcceptedCount() > 0
                                && FormalMachineCraftingTimingService.hasActiveState(fixture.cpuCluster().craftingLogic),
                        "standalone submit should activate timing only after formal fast path accepts the job"))
                .thenWaitUntil(() -> helper.assertTrue(
                        !fixture.cpuCluster().isBusy()
                                && machine.snapshotBenchmark().jobsCompleted() >= expectedLogicalExecutions,
                        "standalone formal machine job should finish instead of staying in CPU waiting, "
                                + describeFormalMachineProgress(fixture, machine, output)))
                .thenWaitUntil(() -> helper.assertTrue(fixture.countStoredLive(output) == requestedAmount,
                        "standalone final output should become visible in live AE storage"))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertValueEqual(0L, snapshot.pendingAeReturnCount(),
                            "standalone submit must not leave a CPU_WAITING payload stuck in the formal machine");
                    helper.assertTrue(snapshot.fastPathAcceptedCount() > 0,
                            "standalone submit should use formal-machine fast path");
                    helper.assertTrue(snapshot.cpuWaitingReturnAmount() > 0,
                            "standalone submit should close through CPU_WAITING return");
                    helper.assertValueEqual(requestedAmount, fixture.countStoredLive(output),
                            "standalone final output should be written back to live AE storage");
                })
                .thenSucceed();
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void formalMachineNonStandaloneExternalIngressPrefersCpuWaitingOverAeStorage(GameTestHelper helper) {
        FormalMachineAutoCraftingGameTestFixture fixture = FormalMachineAutoCraftingGameTestFixture.create(
                helper,
                (what, amount, mode) -> mode == appeng.api.config.Actionable.SIMULATE ? 0L : Math.max(0L, amount)
        );
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.WOODEN_PICKAXE);
        long requestedAmount = 32L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        ICraftingPlan[] planHolder = new ICraftingPlan[1];
        ICraftingSubmitResult[] submitHolder = new ICraftingSubmitResult[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel()))
                    );
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> fixture.assertStoredLive(AEItemKey.of(Items.OAK_LOG), 1L))
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "non-standalone external ingress regression should expose wooden pickaxe path"))
                .thenExecute(() -> futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount))
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "non-standalone external ingress planning future should complete"))
                .thenExecute(() -> {
                    planHolder[0] = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, planHolder[0]);
                    submitHolder[0] = fixture.submitCraftingPlan(planHolder[0]);
                    helper.assertTrue(submitHolder[0] != null && submitHolder[0].successful(),
                            "non-standalone external ingress regression should submit successfully");
                    helper.assertTrue(submitHolder[0].link() != null,
                            "non-standalone external ingress regression should keep a requester link");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.requester().isJobFinished(),
                        "non-standalone external ingress requester link should finish"))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertValueEqual(requestedAmount, fixture.requester().countAcceptedOutput(output),
                            "non-standalone external ingress should still deliver final output through requester MODULATE");
                    helper.assertTrue(snapshot.cpuWaitingReturnAmount() > 0L,
                            "non-standalone external ingress should still route final output through CPU waiting");
                    helper.assertTrue(snapshot.largestCpuWaitingReturnAmount() > 0L,
                            "non-standalone external ingress should attempt a non-empty payload through exact source CPU");
                    helper.assertValueEqual(0L, snapshot.aeStorageInsertSuccessCount(),
                            "non-standalone external ingress final output must not fall through into AE storage");
                    helper.assertValueEqual(0L, fixture.countStoredLive(output),
                            "non-standalone external ingress final output must stay out of AE storage before CPU completion");
                    helper.assertTrue(!fixture.cpuCluster().isBusy(),
                            "non-standalone external ingress exact source CPU should finish after CPU-waiting return");
                })
                .thenSucceed();
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 2400)
    public static void formalMachineCpuWaitingReturnUnderFiveMs(GameTestHelper helper) {
        FormalMachineAutoCraftingGameTestFixture fixture = FormalMachineAutoCraftingGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.WOODEN_PICKAXE);
        long requestedAmount = 100_000L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        ICraftingPlan[] planHolder = new ICraftingPlan[1];
        ICraftingSubmitResult[] submitHolder = new ICraftingSubmitResult[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel()))
                    );
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> fixture.assertStoredLive(AEItemKey.of(Items.OAK_LOG), 1L))
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "formal machine should expose wooden pickaxe path for CPU_WAITING budget"))
                .thenExecute(() -> futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount))
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine CPU_WAITING budget plan should complete"))
                .thenExecute(() -> {
                    planHolder[0] = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, planHolder[0]);
                    submitHolder[0] = fixture.submitCraftingPlan(planHolder[0]);
                    helper.assertTrue(submitHolder[0] != null && submitHolder[0].successful(),
                            "formal machine CPU_WAITING budget job should submit successfully");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.requester().countAcceptedOutput(output) >= requestedAmount,
                        "formal machine CPU_WAITING budget job should finish all output, "
                                + describeFormalMachineProgress(fixture, machine, output)))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.cpuWaitingReturnAmount() > 0,
                            "formal machine CPU_WAITING budget test should return items through CPU_WAITING");
                    helper.assertTrue(snapshot.largestCpuWaitingReturnAmount() > 0,
                            "formal machine CPU_WAITING budget test should observe a non-empty CPU payload");
                    helper.assertTrue(snapshot.submitBenchmarkSuccessCount() >= 1,
                            "formal machine CPU_WAITING budget test should record successful submit benchmark");
                    helper.assertValueEqual(0L, snapshot.cpuWaitingReturnOverBudgetCount(),
                            "formal machine CPU_WAITING return should avoid emergency over-budget events");
                    helper.assertTrue(snapshot.maxTickBudgetNanosObserved() <= 5_000_000L
                                    || snapshot.cpuWaitingReturnBudgetStopCount() > 0,
                            "formal machine CPU_WAITING return must either stay under 5ms or stop adaptively");
                })
                .thenSucceed();
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void formalMachineBatchExpandsWithoutFixedCap(GameTestHelper helper) {
        FormalMachineAutoCraftingGameTestFixture fixture = FormalMachineAutoCraftingGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.OAK_PLANKS);
        long requestedAmount = 16_384L;
        long expectedLogicalExecutions = 4_096L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        ICraftingPlan[] planHolder = new ICraftingPlan[1];
        ICraftingSubmitResult[] submitHolder = new ICraftingSubmitResult[1];
        ItemStack[] plankGrid = new ItemStack[]{
                new ItemStack(Items.OAK_LOG),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY
        };

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    int inserted = machine.fillCraftingPatternsForTest(0, 1, plankGrid);
                    helper.assertValueEqual(1, inserted,
                            "formal machine batch cap test should encode one plank pattern");
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.SAME_PATTERN_DRAIN);
                    machine.setBaseOperationTicksForTest(2);
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> fixture.assertStoredLive(AEItemKey.of(Items.OAK_LOG), 1L))
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "formal machine batch cap test should expose the plank auto-crafting path"))
                .thenExecute(() -> futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount))
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine batch cap planning future should complete"))
                .thenExecute(() -> {
                    planHolder[0] = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, planHolder[0]);
                    logPlanShape("formal_machine_batch_cap", requestedAmount, planHolder[0]);
                    submitHolder[0] = fixture.submitCraftingPlan(planHolder[0]);
                    helper.assertTrue(submitHolder[0] != null && submitHolder[0].successful(),
                            "formal machine batch cap test should submit successfully");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.requester().countAcceptedOutput(output) >= requestedAmount,
                        "formal machine batch cap test should finish all requested plank outputs, "
                                + describeFormalMachineProgress(fixture, machine, output)))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertValueEqual(expectedLogicalExecutions, snapshot.jobsSubmitted(),
                            "formal machine batch cap test should record all submitted executions");
                    helper.assertValueEqual(expectedLogicalExecutions, snapshot.jobsCompleted(),
                            "formal machine batch cap test should record all completed executions");
                    helper.assertTrue(snapshot.fastPathAcceptedCount() > 0,
                            "formal machine batch cap test should enter the formal-machine fast path");
                    helper.assertTrue(snapshot.formalMachineOptimizationHitCount() > 0,
                            "formal machine batch cap test should record formal fast path hits");
                    helper.assertTrue(snapshot.maxExecutionCountPerTaskObserved() > 1_024,
                            "formal machine batch should expand beyond the old 1024 logical execution cap");
                    helper.assertTrue(snapshot.largestObservedBatchSize() > 1_024,
                            "formal machine observed batch size should exceed the old fixed cap");
                    helper.assertTrue(snapshot.maxExecutableRunsHitCount() > 0,
                            "formal machine batch cap test should hit the bulk extraction planner");
                    helper.assertTrue(snapshot.bulkExtractionLogicalExecutionsMax() > 1_024,
                            "formal machine batch cap test should extract a large logical run in one pass");
                    helper.assertTrue(snapshot.templatedDispatchHitCount() > 0
                                    || snapshot.templatedCompletionHitCount() > 0,
                            "formal machine batch cap test should hit dispatch or completion templating");
                    helper.assertTrue(snapshot.compileCacheHitCount() > 0,
                            "formal machine batch cap test should reuse compiled task pattern resolution");
                    helper.assertTrue(snapshot.fastPathAcceptedCount() > 1_024L,
                            "formal machine batch cap test should accept a large fast-path batch beyond the legacy cap");
                    helper.assertValueEqual(requestedAmount, fixture.requester().countAcceptedOutput(output),
                            "formal machine batch cap test should deliver all requested planks");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1600)
    public static void formalMachineMegaStyleDeepChain100x256m(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.LECTERN);
        long requestedAmount = 100L * 256L * 1024L * 1024L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        long[] startedAtNanos = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG, Items.SUGAR_CANE, Items.LEATHER);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.deepLecternPlanningSet(helper.getLevel()))
                    );
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "formal machine mega-style deep chain should expose target output"))
                .thenExecute(() -> {
                    startedAtNanos[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine mega-style planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertDeepLecternPlanMatchesAmount(helper, requestedAmount, plan);
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos[0]);
                    helper.assertValueEqual(requestedAmount, snapshot.planningRequestedAmountMax(),
                            "formal machine mega-style telemetry should capture requested amount");
                    helper.assertTrue(snapshot.planningRequestCount() >= 1,
                            "formal machine mega-style planning should record a planning request");
                    helper.assertTrue(snapshot.planningSuccessCount() >= 1,
                            "formal machine mega-style planning should record a planning success");
                    helper.assertTrue(snapshot.planningAggregationHitCount() > 0,
                            "formal machine mega-style planning should hit aggregation");
                    helper.assertValueEqual(0L, snapshot.planningAggregationFallbackCount(),
                            "formal machine mega-style planning should not fall back to native planning");
                    helper.assertValueEqual(0L, snapshot.planningFailureCount(),
                            "formal machine mega-style planning should not record formal planning failure");
                    helper.assertTrue(snapshot.planningWorkTriggeredCount() > 0,
                            "formal machine mega-style planning should be work-triggered");
                    helper.assertTrue(snapshot.planningEstimatedWorkMax() >= requestedAmount,
                            "formal machine mega-style estimated work should scale with requested amount");
                    helper.assertTrue(snapshot.deterministicPlanningHitCount() > 0,
                            "formal machine mega-style planning should use deterministic formal-machine planning");
                    helper.assertValueEqual(0L, snapshot.deterministicPlanningFallbackCount(),
                            "formal machine mega-style deterministic planning should not fall back");
                    helper.assertTrue(snapshot.virtualScaledPatternHitCount() > 0,
                            "formal machine mega-style planning should use virtual scaled patterns");
                    helper.assertTrue(snapshot.largestVirtualPatternMultiplier() > 1,
                            "formal machine mega-style planning should record a scaled multiplier greater than one");
                    helper.assertTrue(snapshot.virtualScaledPatternLogicalExecutionsSaved() > 0,
                            "formal machine mega-style planning should save logical executions through scaling");
                    logPlanningBenchmark("formal_machine_mega_style_deep_chain_100x256m",
                            requestedAmount,
                            elapsedMillis,
                            snapshot);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1600)
    public static void formalMachineMegaCells256mStorageCellPatterns(GameTestHelper helper) {
        if (!ModList.get().isLoaded("megacells")) {
            LOGGER.info("Skipping formal_machine_megacells_256m_storage_cell_patterns because Mega Cells is absent");
            helper.succeed();
            return;
        }

        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        Item targetItem = registryItem("megacells", "item_storage_cell_256m");
        AEItemKey output = AEItemKey.of(targetItem);
        long requestedAmount = 100L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        long[] startedAtNanos = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(
                            registryItem("ae2", "sky_dust"),
                            registryItem("ae2", "cell_component_256k"),
                            registryItem("ae2", "quartz_vibrant_glass"),
                            registryItem("ae2", "matter_ball"),
                            registryItem("ae2", "ender_dust"),
                            registryItem("megacells", "accumulation_processor"),
                            registryItem("megacells", "sky_steel_ingot")
                    );
                    machine.fillCraftingPatternsForTest(
                            0,
                            megaCells256mFormalMachinePatterns(helper)
                    );
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "formal machine should expose real Mega Cells 256m item storage cell patterns"))
                .thenExecute(() -> {
                    startedAtNanos[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine Mega Cells planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, plan);
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos[0]);
                    helper.assertValueEqual(requestedAmount, snapshot.planningRequestedAmountMax(),
                            "real Mega Cells planning should capture the requested amount");
                    helper.assertTrue(snapshot.planningRequestCount() >= 1,
                            "real Mega Cells planning should record a formal planning request");
                    helper.assertTrue(snapshot.planningSuccessCount() >= 1,
                            "real Mega Cells planning should record a formal planning success");
                    helper.assertTrue(snapshot.planningAggregationHitCount() > 0,
                            "real Mega Cells formal-machine patterns should hit planning aggregation");
                    helper.assertValueEqual(0L, snapshot.planningAggregationFallbackCount(),
                            "real Mega Cells formal-machine patterns should not fall back to native planning");
                    helper.assertValueEqual(0L, snapshot.planningFailureCount(),
                            "real Mega Cells formal-machine patterns should not record formal planning failure");
                    helper.assertTrue(snapshot.deterministicPlanningHitCount() > 0,
                            "real Mega Cells formal-machine patterns should hit deterministic planning");
                    helper.assertValueEqual(0L, snapshot.deterministicPlanningFallbackCount(),
                            "real Mega Cells deterministic planning should not fall back");
                    helper.assertValueEqual(0L, snapshot.nonFormalProviderHitCount(),
                            "real Mega Cells formal-machine pattern path must not leak into non-formal providers");
                    helper.assertTrue(snapshot.virtualScaledPatternHitCount() > 0,
                            "real Mega Cells formal-machine planning should use virtual scaled patterns");
                    helper.assertTrue(snapshot.largestVirtualPatternMultiplier() > 1,
                            "real Mega Cells formal-machine planning should record a scaled multiplier greater than one");
                    helper.assertTrue(snapshot.virtualScaledPatternLogicalExecutionsSaved() > 0,
                            "real Mega Cells formal-machine planning should save logical executions through scaling");
                    logPlanningBenchmark("formal_machine_real_megacells_256m_storage_cell_100",
                            requestedAmount,
                            elapsedMillis,
                            snapshot);
                })
                .thenSucceed();
    }

    @GameTest(template = AUTOCRAFT_TEMPLATE, batch = BATCH, timeoutTicks = 2400)
    public static void formalMachineMegaCells256mStorageCellSubmitExecute(GameTestHelper helper) {
        if (!ModList.get().isLoaded("megacells")) {
            LOGGER.info("Skipping formal_machine_megacells_256m_storage_cell_submit_execute because Mega Cells is absent");
            helper.succeed();
            return;
        }

        FormalMachineAutoCraftingGameTestFixture fixture = FormalMachineAutoCraftingGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        Item targetItem = registryItem("megacells", "item_storage_cell_256m");
        AEItemKey output = AEItemKey.of(targetItem);
        long requestedAmount = 100L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        ICraftingPlan[] planHolder = new ICraftingPlan[1];
        ICraftingSubmitResult[] submitHolder = new ICraftingSubmitResult[1];
        long[] planningStartedAt = new long[1];
        long[] submitFinishedAt = new long[1];
        long[] planningElapsedMillis = new long[1];
        long[] submitElapsedMillis = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(megaCells256mBaseInputs());
                    machine.fillCraftingPatternsForTest(
                            0,
                            completeMegaCells256mFormalMachinePatterns(helper)
                    );
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "formal machine should expose full Mega Cells 256m submit/execute chain"))
                .thenExecute(() -> {
                    planningStartedAt[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine Mega Cells submit/execute planning future should complete"))
                .thenExecute(() -> {
                    planHolder[0] = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, planHolder[0]);
                    helper.assertTrue(planHolder[0].missingItems().isEmpty(),
                            "formal machine Mega Cells submit/execute plan should not miss creative base inputs");
                    planningElapsedMillis[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - planningStartedAt[0]);
                    long submitStartedAt = System.nanoTime();
                    submitHolder[0] = fixture.submitCraftingPlan(planHolder[0]);
                    submitFinishedAt[0] = System.nanoTime();
                    submitElapsedMillis[0] = TimeUnit.NANOSECONDS.toMillis(submitFinishedAt[0] - submitStartedAt);
                    helper.assertTrue(submitHolder[0] != null && submitHolder[0].successful(),
                            "formal machine Mega Cells submit/execute path should submit successfully, errorCode="
                                    + (submitHolder[0] == null ? "null" : submitHolder[0].errorCode())
                                    + ", errorDetail="
                                    + (submitHolder[0] == null ? "null" : submitHolder[0].errorDetail())
                                    + ", planBytes="
                                    + (planHolder[0] == null ? -1L : planHolder[0].bytes()));
                    helper.assertTrue(submitHolder[0].link() != null,
                            "formal machine Mega Cells submit/execute path should return a crafting link");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.requester().countAcceptedOutput(output) >= requestedAmount,
                        "formal machine Mega Cells submit/execute path should emit all requested 256m storage cells"))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long executeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submitFinishedAt[0]);
                    long totalElapsedMillis = planningElapsedMillis[0] + submitElapsedMillis[0] + executeElapsedMillis;
                    helper.assertTrue(!fixture.requester().isJobCanceled(),
                            "formal machine Mega Cells submit/execute path must not cancel the submitted job");
                    helper.assertTrue(fixture.requester().isJobFinished(),
                            "formal machine Mega Cells submit/execute path must finish the submitted job");
                    helper.assertValueEqual(requestedAmount, fixture.requester().countAcceptedOutput(output),
                            "formal machine Mega Cells submit/execute path should deliver all final outputs");
                    helper.assertTrue(snapshot.planningAggregationHitCount() > 0,
                            "formal machine Mega Cells submit/execute should hit formal planning aggregation");
                    helper.assertValueEqual(0L, snapshot.planningAggregationFallbackCount(),
                            "formal machine Mega Cells submit/execute should not fall back to native planning");
                    helper.assertValueEqual(0L, snapshot.planningFailureCount(),
                            "formal machine Mega Cells submit/execute should not record formal planning failure");
                    helper.assertTrue(snapshot.deterministicPlanningHitCount() > 0,
                            "formal machine Mega Cells submit/execute should use deterministic planning");
                    helper.assertValueEqual(0L, snapshot.deterministicPlanningFallbackCount(),
                            "formal machine Mega Cells submit/execute deterministic planning should not fall back");
                    helper.assertValueEqual(0L, snapshot.nonFormalProviderHitCount(),
                            "formal machine Mega Cells submit/execute should not leak into non-formal providers");
                    helper.assertTrue(snapshot.formalMachineOptimizationHitCount() > 0,
                            "formal machine Mega Cells submit/execute should use formal-machine execution optimization");
                    helper.assertTrue(snapshot.fastPathAcceptedCount() > 0,
                            "formal machine Mega Cells submit/execute should accept CPU fast path batches");
                    helper.assertTrue(snapshot.virtualScaledPatternHitCount() > 0,
                            "formal machine Mega Cells submit/execute should use virtual scaled patterns");
                    helper.assertTrue(snapshot.largestVirtualPatternMultiplier() > 1,
                            "formal machine Mega Cells submit/execute should record a scaled multiplier greater than one");
                    helper.assertTrue(snapshot.virtualScaledPatternLogicalExecutionsSaved() > 0,
                            "formal machine Mega Cells submit/execute should save logical executions through scaling");
                    helper.assertTrue(snapshot.maxTickBudgetNanosObserved() < 5_000_000L
                                    || snapshot.tickBudgetHardStopCount() > 0,
                            "formal machine Mega Cells submit/execute should stay inside the 5ms target or record hard stops");
                    logSubmitExecuteBenchmark(
                            "formal_machine_real_megacells_256m_storage_cell_100",
                            requestedAmount,
                            snapshot.jobsCompleted(),
                            fixture.requester().countAcceptedOutput(output),
                            planningElapsedMillis[0],
                            submitElapsedMillis[0],
                            executeElapsedMillis,
                            totalElapsedMillis,
                            snapshot
                    );
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1600)
    public static void formalMachineMegaPatternProviderDoesNotUseFormalOptimization(GameTestHelper helper) {
        if (!ModList.get().isLoaded("megacells")) {
            LOGGER.info("Skipping formal_machine_mega_pattern_provider_negative_control because Mega Cells is absent");
            helper.succeed();
            return;
        }

        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.OAK_PLANKS);
        long requestedAmount = 16L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        long[] startedAtNanos = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    fixture.installPatternProvider(
                            registryBlock("megacells", "mega_pattern_provider"),
                            List.of(encodeProcessingPattern(
                                    AEItemKey.of(Items.OAK_LOG),
                                    1L,
                                    output,
                                    4L
                            ))
                    );
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(fixture::assertAuxiliaryPatternProviderConnected)
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "Mega Pattern Provider negative control should expose the processing output"))
                .thenExecute(() -> {
                    startedAtNanos[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "Mega Pattern Provider negative-control planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, plan);
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos[0]);
                    helper.assertValueEqual(0L, snapshot.planningAggregationHitCount(),
                            "Mega Pattern Provider must not hit formal-machine planning aggregation");
                    helper.assertValueEqual(0L, snapshot.deterministicPlanningHitCount(),
                            "Mega Pattern Provider must not hit deterministic formal-machine planning");
                    helper.assertValueEqual(0L, snapshot.deterministicPlanningFallbackCount(),
                            "Mega Pattern Provider must not enter deterministic formal-machine fallback");
                    helper.assertValueEqual(0L, snapshot.planningSuccessCount(),
                            "Mega Pattern Provider must not record formal-machine planning success");
                    helper.assertValueEqual(0L, snapshot.formalMachineOptimizationHitCount(),
                            "Mega Pattern Provider must not hit formal-machine execution optimization");
                    helper.assertValueEqual(0L, snapshot.fastPathAcceptedCount(),
                            "Mega Pattern Provider must not enter formal-machine CPU fast path");
                    logPlanningBenchmark("mega_pattern_provider_negative_control",
                            requestedAmount,
                            elapsedMillis,
                            snapshot,
                            "nativePlanningTimedOut=false");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1600)
    public static void formalMachineLargeMegaPatternProviderDoesNotUseFormalOptimization(GameTestHelper helper) {
        if (!ModList.get().isLoaded("megacells")) {
            LOGGER.info("Skipping formal_machine_large_mega_pattern_provider_negative_control because Mega Cells is absent");
            helper.succeed();
            return;
        }

        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.OAK_PLANKS);
        long requestedAmount = 16_384L;
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        long[] startedAtNanos = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    fixture.installPatternProvider(
                            registryBlock("megacells", "mega_pattern_provider"),
                            List.of(encodeProcessingPattern(
                                    AEItemKey.of(Items.OAK_LOG),
                                    1L,
                                    output,
                                    4L
                            ))
                    );
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(fixture::assertAuxiliaryPatternProviderConnected)
                .thenWaitUntil(() -> helper.assertTrue(!fixture.lookupCraftables(output).isEmpty(),
                        "large Mega Pattern Provider negative control should expose the processing output"))
                .thenExecute(() -> {
                    startedAtNanos[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "large Mega Pattern Provider negative-control planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, plan);
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos[0]);
                    helper.assertValueEqual(0L, snapshot.planningAggregationHitCount(),
                            "large Mega Pattern Provider must not hit formal-machine planning aggregation");
                    helper.assertValueEqual(0L, snapshot.deterministicPlanningHitCount(),
                            "large Mega Pattern Provider must not hit deterministic formal-machine planning");
                    helper.assertValueEqual(0L, snapshot.formalMachineOptimizationHitCount(),
                            "large Mega Pattern Provider must not hit formal-machine execution optimization");
                    helper.assertValueEqual(0L, snapshot.fastPathAcceptedCount(),
                            "large Mega Pattern Provider must not enter formal-machine CPU fast path");
                    logPlanningBenchmark("large_mega_pattern_provider_negative_control",
                            requestedAmount,
                            elapsedMillis,
                            snapshot,
                            "nativePlanningTimedOut=false");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1600)
    public static void formalMachineCompletionBacklogKeepsOtherLanesRunning(GameTestHelper helper) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey plank = AEItemKey.of(Items.OAK_PLANKS);
        AEItemKey stick = AEItemKey.of(Items.STICK);
        List<CraftingPatternDataset.EncodedPatternSpec> specs = CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel());
        int[] submitted = new int[2];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(specs)
                    );
                    machine.setBatchExecutionModeForTest(BatchExecutionMode.OFF);
                    machine.setBaseOperationTicksForTest(2);
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                    fixture.installCreativeStorageCell(Items.OAK_PLANKS, Items.STICK);
                    submitted[0] = machine.submitPatternByDefinitionWithExecutionCountForTest(
                            specs.get(0).encodedPattern(),
                            40_000
                    );
                    submitted[1] = machine.submitPatternsByOutputSequenceForTest(repeatedOutputs(stick, 128));
                    helper.assertValueEqual(40_000, submitted[0],
                            "completion backlog regression should submit the large plank task");
                    helper.assertValueEqual(128, submitted[1],
                            "completion backlog regression should accept all secondary stick tasks");
                })
                .thenWaitUntil(() -> helper.assertTrue(machine.hasPendingCompletionBacklog(),
                        "completion backlog regression should enter pending completion"))
                .thenWaitUntil(() -> helper.assertTrue(
                        machine.hasPendingCompletionBacklog() && machine.snapshotBenchmark().jobsCompleted() > 0,
                        "secondary tasks must keep making forward progress while completion backlog is non-empty"))
                .thenWaitUntil(() -> helper.assertTrue(
                        machine.snapshotBenchmark().jobsCompleted() >= (long) submitted[0] + submitted[1],
                        "completion backlog regression should complete all submitted logical jobs"))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    helper.assertTrue(snapshot.completionQueuePeak() > 0,
                            "completion backlog regression must record a non-zero completion queue peak");
                    helper.assertTrue(snapshot.completionBacklogExecutionsPeak() > 0,
                            "completion backlog regression must record pending completion executions");
                    helper.assertTrue(snapshot.peakRunningTasks() > 0,
                            "completion backlog regression must keep running lanes active");
                    helper.assertTrue(snapshot.jobsCompleted() >= (long) submitted[0] + submitted[1],
                            "completion backlog regression must finish all submitted jobs");
                })
                .thenSucceed();
    }

    private static void assertLookupContainsDefinitions(
            GameTestHelper helper,
            Iterable<IPatternDetails> availablePatterns,
            CraftingPatternDataset.EncodedPatternSpec first,
            CraftingPatternDataset.EncodedPatternSpec second
    ) {
        boolean firstFound = false;
        boolean secondFound = false;
        for (IPatternDetails details : availablePatterns) {
            ItemStack definition = details.getDefinition().toStack();
            if (ItemStack.isSameItemSameComponents(definition, first.encodedPattern())) {
                firstFound = true;
            }
            if (ItemStack.isSameItemSameComponents(definition, second.encodedPattern())) {
                secondFound = true;
            }
        }
        helper.assertTrue(firstFound, "substitution-disabled oak slab pattern must stay exposed");
        helper.assertTrue(secondFound, "substitution-enabled oak slab pattern must stay exposed");
    }

    private static List<ItemStack> megaCells256mFormalMachinePatterns(GameTestHelper helper) {
        return List.of(
                encodeMegaPattern(helper, grid(
                        registryItem("ae2", "sky_dust"), registryItem("megacells", "accumulation_processor"),
                        registryItem("ae2", "sky_dust"),
                        registryItem("ae2", "cell_component_256k"), registryItem("ae2", "quartz_vibrant_glass"),
                        registryItem("ae2", "cell_component_256k"),
                        registryItem("ae2", "sky_dust"), registryItem("ae2", "cell_component_256k"),
                        registryItem("ae2", "sky_dust")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("ae2", "ender_dust"), registryItem("megacells", "accumulation_processor"),
                        registryItem("ae2", "ender_dust"),
                        registryItem("megacells", "cell_component_1m"), registryItem("ae2", "quartz_vibrant_glass"),
                        registryItem("megacells", "cell_component_1m"),
                        registryItem("ae2", "ender_dust"), registryItem("megacells", "cell_component_1m"),
                        registryItem("ae2", "ender_dust")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("ae2", "ender_dust"), registryItem("megacells", "accumulation_processor"),
                        registryItem("ae2", "ender_dust"),
                        registryItem("megacells", "cell_component_4m"), registryItem("ae2", "quartz_vibrant_glass"),
                        registryItem("megacells", "cell_component_4m"),
                        registryItem("ae2", "ender_dust"), registryItem("megacells", "cell_component_4m"),
                        registryItem("ae2", "ender_dust")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("ae2", "matter_ball"), registryItem("megacells", "accumulation_processor"),
                        registryItem("ae2", "matter_ball"),
                        registryItem("megacells", "cell_component_16m"), registryItem("ae2", "quartz_vibrant_glass"),
                        registryItem("megacells", "cell_component_16m"),
                        registryItem("ae2", "matter_ball"), registryItem("megacells", "cell_component_16m"),
                        registryItem("ae2", "matter_ball")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("ae2", "matter_ball"), registryItem("megacells", "accumulation_processor"),
                        registryItem("ae2", "matter_ball"),
                        registryItem("megacells", "cell_component_64m"), registryItem("ae2", "quartz_vibrant_glass"),
                        registryItem("megacells", "cell_component_64m"),
                        registryItem("ae2", "matter_ball"), registryItem("megacells", "cell_component_64m"),
                        registryItem("ae2", "matter_ball")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("ae2", "quartz_vibrant_glass"), registryItem("ae2", "sky_dust"),
                        registryItem("ae2", "quartz_vibrant_glass"),
                        registryItem("ae2", "sky_dust"), registryItem("megacells", "cell_component_256m"),
                        registryItem("ae2", "sky_dust"),
                        registryItem("megacells", "sky_steel_ingot"), registryItem("megacells", "sky_steel_ingot"),
                        registryItem("megacells", "sky_steel_ingot")
                ))
        );
    }

    private static List<ItemStack> completeMegaCells256mFormalMachinePatterns(GameTestHelper helper) {
        List<ItemStack> patterns = new java.util.ArrayList<>();
        patterns.addAll(ae2StorageComponentPatterns(helper));
        patterns.addAll(megaCells256mFormalMachinePatterns(helper));
        return List.copyOf(patterns);
    }

    private static List<ItemStack> ae2StorageComponentPatterns(GameTestHelper helper) {
        return List.of(
                encodeMegaPattern(helper, grid(
                        registryItem("minecraft", "redstone"), registryItem("ae2", "certus_quartz_crystal"),
                        registryItem("minecraft", "redstone"),
                        registryItem("ae2", "certus_quartz_crystal"), registryItem("ae2", "logic_processor"),
                        registryItem("ae2", "certus_quartz_crystal"),
                        registryItem("minecraft", "redstone"), registryItem("ae2", "certus_quartz_crystal"),
                        registryItem("minecraft", "redstone")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("minecraft", "redstone"), registryItem("ae2", "calculation_processor"),
                        registryItem("minecraft", "redstone"),
                        registryItem("ae2", "cell_component_1k"), registryItem("ae2", "quartz_glass"),
                        registryItem("ae2", "cell_component_1k"),
                        registryItem("minecraft", "redstone"), registryItem("ae2", "cell_component_1k"),
                        registryItem("minecraft", "redstone")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("minecraft", "glowstone_dust"), registryItem("ae2", "calculation_processor"),
                        registryItem("minecraft", "glowstone_dust"),
                        registryItem("ae2", "cell_component_4k"), registryItem("ae2", "quartz_glass"),
                        registryItem("ae2", "cell_component_4k"),
                        registryItem("minecraft", "glowstone_dust"), registryItem("ae2", "cell_component_4k"),
                        registryItem("minecraft", "glowstone_dust")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("minecraft", "glowstone_dust"), registryItem("ae2", "calculation_processor"),
                        registryItem("minecraft", "glowstone_dust"),
                        registryItem("ae2", "cell_component_16k"), registryItem("ae2", "quartz_glass"),
                        registryItem("ae2", "cell_component_16k"),
                        registryItem("minecraft", "glowstone_dust"), registryItem("ae2", "cell_component_16k"),
                        registryItem("minecraft", "glowstone_dust")
                )),
                encodeMegaPattern(helper, grid(
                        registryItem("ae2", "sky_dust"), registryItem("ae2", "calculation_processor"),
                        registryItem("ae2", "sky_dust"),
                        registryItem("ae2", "cell_component_64k"), registryItem("ae2", "quartz_glass"),
                        registryItem("ae2", "cell_component_64k"),
                        registryItem("ae2", "sky_dust"), registryItem("ae2", "cell_component_64k"),
                        registryItem("ae2", "sky_dust")
                ))
        );
    }

    private static Item[] megaCells256mBaseInputs() {
        return new Item[]{
                registryItem("minecraft", "redstone"),
                registryItem("minecraft", "glowstone_dust"),
                registryItem("ae2", "certus_quartz_crystal"),
                registryItem("ae2", "logic_processor"),
                registryItem("ae2", "calculation_processor"),
                registryItem("ae2", "quartz_glass"),
                registryItem("ae2", "sky_dust"),
                registryItem("ae2", "matter_ball"),
                registryItem("ae2", "ender_dust"),
                registryItem("ae2", "quartz_vibrant_glass"),
                registryItem("megacells", "accumulation_processor"),
                registryItem("megacells", "sky_steel_ingot")
        };
    }

    private static ItemStack encodeMegaPattern(GameTestHelper helper, ItemStack[] grid) {
        ItemStack encodedPattern =
                HighCapacityCraftingMachineBlockEntity.encodeCraftingPatternForTest(helper.getLevel(), grid);
        helper.assertTrue(!encodedPattern.isEmpty(), "formal machine should encode a real Mega Cells crafting pattern");
        return encodedPattern;
    }

    private static ItemStack encodeProcessingPattern(AEItemKey input, long inputAmount, AEItemKey output, long outputAmount) {
        return PatternDetailsHelper.encodeProcessingPattern(
                List.of(new GenericStack(input, inputAmount)),
                List.of(new GenericStack(output, outputAmount))
        );
    }

    private static ItemStack[] grid(Object slot0, Object slot1, Object slot2,
                                    Object slot3, Object slot4, Object slot5,
                                    Object slot6, Object slot7, Object slot8) {
        return new ItemStack[]{
                stack(slot0), stack(slot1), stack(slot2),
                stack(slot3), stack(slot4), stack(slot5),
                stack(slot6), stack(slot7), stack(slot8)
        };
    }

    private static ItemStack stack(Object itemLike) {
        if (itemLike instanceof Item item) {
            return new ItemStack(item);
        }
        if (itemLike instanceof ItemStack stack) {
            return stack.copy();
        }
        return ItemStack.EMPTY;
    }

    private static Item registryItem(String namespace, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) {
            throw new IllegalStateException("missing item " + id);
        }
        return item;
    }

    private static Block registryBlock(String namespace, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR) {
            throw new IllegalStateException("missing block " + id);
        }
        return block;
    }

    private static ICraftingPlan resolveCompletedPlan(
            GameTestHelper helper,
            FormalMachineAENetworkGameTestFixture fixture,
            Future<ICraftingPlan> future,
            AEItemKey output
    ) {
        try {
            ICraftingPlan plan = future.get(10, TimeUnit.SECONDS);
            fixture.recordPlanResult(plan);
            return plan;
        } catch (TimeoutException exception) {
            helper.fail("planning future timed out for " + output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            helper.fail("planning future interrupted for " + output);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            helper.fail("planning future failed for " + output + ": "
                    + (cause == null ? exception.getMessage() : cause.getMessage()));
        }
        return null;
    }

    private static ICraftingPlan resolveCompletedPlan(
            GameTestHelper helper,
            FormalMachineAutoCraftingGameTestFixture fixture,
            Future<ICraftingPlan> future,
            AEItemKey output
    ) {
        try {
            ICraftingPlan plan = future.get(10, TimeUnit.SECONDS);
            fixture.recordPlanResult(plan);
            return plan;
        } catch (TimeoutException exception) {
            helper.fail("planning future timed out for " + output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            helper.fail("planning future interrupted for " + output);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            helper.fail("planning future failed for " + output + ": "
                    + (cause == null ? exception.getMessage() : cause.getMessage()));
        }
        return null;
    }

    private static void assertPlanMatches(GameTestHelper helper, AEItemKey output, ICraftingPlan plan) {
        helper.assertTrue(plan != null, "planning future should resolve to a plan for " + output);
        helper.assertTrue(plan.missingItems().isEmpty(), "planning future should not report missing items for " + output);
        GenericStack finalOutput = plan.finalOutput();
        helper.assertTrue(finalOutput != null && output.equals(finalOutput.what()),
                "planning future should resolve to the requested output " + output);
    }

    private static void assertPlanMatchesAmount(
            GameTestHelper helper,
            AEItemKey output,
            long expectedAmount,
            ICraftingPlan plan
    ) {
        assertPlanMatches(helper, output, plan);
        helper.assertValueEqual(expectedAmount, plan.finalOutput().amount(),
                "planning future should preserve the requested amount for " + output);
    }

    private static void assertDeepLecternPlanMatchesAmount(
            GameTestHelper helper,
            long expectedAmount,
            ICraftingPlan plan
    ) {
        AEItemKey output = AEItemKey.of(Items.LECTERN);
        helper.assertTrue(plan != null, "planning future should resolve to a plan for " + output);
        GenericStack finalOutput = plan.finalOutput();
        helper.assertTrue(finalOutput != null && output.equals(finalOutput.what()),
                "planning future should resolve to the requested output " + output);
        helper.assertValueEqual(expectedAmount, finalOutput.amount(),
                "planning future should preserve the requested amount for " + output);

        List<AEItemKey> allowedBaseMissing = List.of(
                AEItemKey.of(Items.OAK_LOG),
                AEItemKey.of(Items.SUGAR_CANE),
                AEItemKey.of(Items.LEATHER)
        );
        for (var missing : plan.missingItems()) {
            helper.assertTrue(allowedBaseMissing.contains(missing.getKey()),
                    "mega-style deterministic planning may only report base material shortages, got "
                            + missing.getKey() + " x " + missing.getLongValue());
        }
        helper.assertValueEqual(0L, plan.missingItems().get(output),
                "mega-style deterministic planning must not report the target output as missing");
        helper.assertValueEqual(0L, plan.missingItems().get(AEItemKey.of(Items.OAK_PLANKS)),
                "mega-style deterministic planning must not report craftable planks as missing");
        helper.assertValueEqual(0L, plan.missingItems().get(AEItemKey.of(Items.OAK_SLAB)),
                "mega-style deterministic planning must not report craftable slabs as missing");
        helper.assertValueEqual(0L, plan.missingItems().get(AEItemKey.of(Items.PAPER)),
                "mega-style deterministic planning must not report craftable paper as missing");
        helper.assertValueEqual(0L, plan.missingItems().get(AEItemKey.of(Items.BOOK)),
                "mega-style deterministic planning must not report craftable books as missing");
        helper.assertValueEqual(0L, plan.missingItems().get(AEItemKey.of(Items.BOOKSHELF)),
                "mega-style deterministic planning must not report craftable bookshelves as missing");
    }

    private static void assertCraftingCpuMenuHeartbeat(
            GameTestHelper helper,
            FormalMachineAutoCraftingGameTestFixture fixture
    ) {
        CraftingCPUCluster cpuCluster = fixture.cpuCluster();
        helper.assertTrue(cpuCluster != null && cpuCluster.isBusy(),
                "formal machine heartbeat test needs a live crafting CPU job");
        long heartbeatBefore = fixture.machine().snapshotBenchmark().formalStatusHeartbeatCount();
        boolean shouldSendHeartbeat = FormalMachineCraftingTimingService.shouldSendHeartbeat(cpuCluster.craftingLogic);
        if (shouldSendHeartbeat) {
            FormalMachineCraftingTimingService.recordFormalStatusHeartbeat(cpuCluster.craftingLogic);
        }
        long heartbeatAfter = fixture.machine().snapshotBenchmark().formalStatusHeartbeatCount();
        CraftingStatus heartbeatStatus = FormalMachineCraftingTimingService.createHeartbeatStatus(cpuCluster.craftingLogic);
        helper.assertTrue(heartbeatAfter >= heartbeatBefore,
                "formal machine heartbeat counter must stay monotonic");
        if (shouldSendHeartbeat) {
            helper.assertTrue(heartbeatAfter > heartbeatBefore,
                    "formal machine heartbeat must advance once formal progress becomes visible to AE2");
        } else {
            helper.assertValueEqual(heartbeatBefore, heartbeatAfter,
                    "formal machine heartbeat must stay quiet before formal progress becomes visible to AE2");
        }
        helper.assertTrue(heartbeatStatus != null && heartbeatStatus != CraftingStatus.EMPTY,
                "formal machine heartbeat must produce a timing status");
        helper.assertValueEqual(AE2_STATUS_TOTAL, heartbeatStatus.getStartItemCount(),
                "formal machine heartbeat must keep AE2 native start-item scale");
        helper.assertTrue(heartbeatStatus.getEntries().isEmpty(),
                "formal machine heartbeat must not resend item entries");
        if (!shouldSendHeartbeat) {
            helper.assertValueEqual(0L, heartbeatStatus.getElapsedTime(),
                    "formal machine heartbeat elapsed time must stay at zero before AE2 observes progress");
        }
    }

    private static void sampleCraftingStatus(
            GameTestHelper helper,
            CraftingCPUCluster cpuCluster,
            CraftingStatus[] lastStatus,
            CraftingJobStatus[] lastJobStatus,
            long[] lastCompleted,
            long[] lastJobProgress,
            int[] samples
    ) {
        helper.assertTrue(cpuCluster != null, "formal machine status timing should expose a crafting CPU cluster");
        if (!cpuCluster.isBusy()) {
            return;
        }
        IncrementalUpdateHelper changes = new IncrementalUpdateHelper();
        KeyCounter allItems = new KeyCounter();
        cpuCluster.craftingLogic.getAllItems(allItems);
        for (var entry : allItems) {
            changes.addChange(entry.getKey());
        }
        CraftingStatus status = CraftingStatus.create(changes, cpuCluster.craftingLogic);
        helper.assertTrue(status != null && status != CraftingStatus.EMPTY,
                "formal machine status timing should produce a non-empty CraftingStatus");
        helper.assertTrue(status.getElapsedTime() >= 0L,
                "formal machine status elapsed time must be non-negative");
        helper.assertTrue(status.getStartItemCount() >= status.getRemainingItemCount(),
                "formal machine status start amount must stay above remaining amount");
        helper.assertTrue(status.getRemainingItemCount() >= 0L,
                "formal machine status remaining amount must be non-negative");
        long completed = status.getStartItemCount() - status.getRemainingItemCount();
        if (completed <= 0L) {
            helper.assertValueEqual(0L, status.getElapsedTime(),
                    "formal machine status elapsed time must stay at zero before AE2 observes progress");
        }
        assertStatusEtaBounded(
                helper,
                status.getElapsedTime(),
                completed,
                status.getRemainingItemCount(),
                "formal machine status"
        );
        if (lastStatus[0] != null) {
            long elapsedStep = status.getElapsedTime() - lastStatus[0].getElapsedTime();
            helper.assertTrue(elapsedStep >= 0L,
                    "formal machine status elapsed time must not go backwards");
            if (elapsedStep > 0L) {
                helper.assertTrue(elapsedStep <= STATUS_SAMPLE_MAX_ELAPSED_STEP_NANOS,
                        "formal machine status elapsed time must not jump between live samples");
            }
            helper.assertTrue(status.getStartItemCount() >= lastStatus[0].getStartItemCount(),
                    "formal machine status start amount must not go backwards");
            helper.assertTrue(status.getRemainingItemCount() <= lastStatus[0].getRemainingItemCount(),
                    "formal machine status remaining amount must not increase");
            helper.assertTrue(completed >= lastCompleted[0],
                    "formal machine status completed amount must not go backwards");
        }
        CraftingJobStatus jobStatus = cpuCluster.getJobStatus();
        helper.assertTrue(jobStatus != null, "formal machine CPU list job status should be visible while busy");
        helper.assertTrue(jobStatus.elapsedTimeNanos() >= 0L,
                "formal machine CPU list elapsed time must be non-negative");
        helper.assertTrue(jobStatus.progress() >= 0L,
                "formal machine CPU list progress must be non-negative");
        helper.assertTrue(jobStatus.totalItems() >= jobStatus.progress(),
                "formal machine CPU list total work must stay above progress");
        if (jobStatus.progress() <= 0L) {
            helper.assertValueEqual(0L, jobStatus.elapsedTimeNanos(),
                    "formal machine CPU list elapsed time must stay at zero before AE2 observes progress");
        }
        assertStatusEtaBounded(
                helper,
                jobStatus.elapsedTimeNanos(),
                jobStatus.progress(),
                Math.max(0L, jobStatus.totalItems() - jobStatus.progress()),
                "formal machine CPU list"
        );
        if (lastJobStatus[0] != null) {
            long jobElapsedStep = jobStatus.elapsedTimeNanos() - lastJobStatus[0].elapsedTimeNanos();
            helper.assertTrue(jobElapsedStep >= 0L,
                    "formal machine CPU list elapsed time must not go backwards");
            if (jobElapsedStep > 0L) {
                helper.assertTrue(jobElapsedStep <= STATUS_SAMPLE_MAX_ELAPSED_STEP_NANOS,
                        "formal machine CPU list elapsed time must not jump between live samples");
            }
            helper.assertTrue(jobStatus.totalItems() >= lastJobStatus[0].totalItems(),
                    "formal machine CPU list total work must not go backwards");
            helper.assertTrue(jobStatus.progress() >= lastJobProgress[0],
                    "formal machine CPU list progress must not go backwards");
        }
        lastStatus[0] = status;
        lastJobStatus[0] = jobStatus;
        lastCompleted[0] = completed;
        lastJobProgress[0] = jobStatus.progress();
        samples[0]++;
    }

    private static void broadcastChangesForMockPlayer(GameTestHelper helper, Runnable broadcastChanges) {
        runMenuActionForMockPlayer(helper, broadcastChanges, "broadcastChanges");
    }

    private static void runMenuActionForMockPlayer(
            GameTestHelper helper,
            Runnable action,
            String actionName
    ) {
        try {
            action.run();
        } catch (UnsupportedOperationException exception) {
            String message = exception.getMessage();
            helper.assertTrue(message != null && message.contains("may not be sent to the client"),
                    "mock player " + actionName + " must only ignore rejected menu payloads, error=" + exception);
        }
    }

    private static Player menuPlayer(GameTestHelper helper) {
        return helper.makeMockPlayer(GameType.CREATIVE);
    }

    private static CraftConfirmMenu requireCraftConfirmMenu(CraftConfirmMenu[] menuHolder) {
        if (menuHolder == null || menuHolder.length == 0 || menuHolder[0] == null) {
            throw new AssertionError("CraftConfirmMenu must be created before planning coverage runs");
        }
        return menuHolder[0];
    }

    private static void assertStatusEtaBounded(
            GameTestHelper helper,
            long elapsedNanos,
            long completed,
            long remaining,
            String label
    ) {
        if (elapsedNanos <= 0L || completed <= 0L || remaining <= 0L) {
            return;
        }
        long etaNanos = saturatedMultiplyDivide(elapsedNanos, remaining, completed);
        helper.assertTrue(etaNanos <= STATUS_SAMPLE_MAX_ETA_NANOS,
                label + " ETA must stay bounded, etaNanos=" + etaNanos);
    }

    private static long saturatedMultiplyDivide(long value, long multiplier, long divisor) {
        if (value <= 0L || multiplier <= 0L) {
            return 0L;
        }
        if (divisor <= 0L || value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return (value * multiplier) / divisor;
    }

    private static void runLargeWoodenPickaxePlanning(GameTestHelper helper, long requestedAmount) {
        FormalMachineAENetworkGameTestFixture fixture = FormalMachineAENetworkGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.WOODEN_PICKAXE);
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        long[] startedAtNanos = new long[1];

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel()))
                    );
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> {
                    List<IPatternDetails> patterns = List.copyOf(fixture.lookupCraftables(output));
                    helper.assertTrue(!patterns.isEmpty(), "formal machine should expose wooden pickaxe planning path");
                })
                .thenExecute(() -> {
                    startedAtNanos[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine large planning future should complete"))
                .thenExecute(() -> {
                    ICraftingPlan plan = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, plan);
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos[0]);
                    helper.assertTrue(snapshot.planningRequestCount() >= 1,
                            "formal machine large planning should record at least one aggregation request");
                    helper.assertTrue(snapshot.planningSuccessCount() >= 1,
                            "formal machine large planning should record at least one aggregation success");
                    helper.assertTrue(snapshot.planningAggregationHitCount() > 0,
                            "formal machine large planning should hit aggregation");
                    boolean chunkedPlanning = snapshot.planningChunkCount() > 1;
                    boolean deterministicPlanning = snapshot.deterministicPlanningHitCount() > 0;
                    helper.assertTrue(chunkedPlanning || deterministicPlanning,
                            "formal machine large planning should split chunks or hit deterministic planning");
                    if (chunkedPlanning) {
                        helper.assertTrue(snapshot.largestPlanningChunkSize() > 0,
                                "formal machine large planning should record a positive chunk size");
                    }
                    helper.assertTrue(snapshot.planningWallClockNanosMax() > 0,
                            "formal machine large planning should record wall clock telemetry");
                    helper.assertValueEqual(requestedAmount, snapshot.planningRequestedAmountMax(),
                            "formal machine planning telemetry should capture requested amount");
                    helper.assertTrue(snapshot.virtualScaledPatternHitCount() > 0,
                            "formal machine large planning should hit virtual scaled patterns");
                    helper.assertTrue(snapshot.largestVirtualPatternMultiplier() > 1,
                            "formal machine large planning should record a scaled multiplier greater than one");
                    helper.assertTrue(snapshot.virtualScaledPatternLogicalExecutionsSaved() > 0,
                            "formal machine large planning should save logical executions through scaling");
                    long plannedPatternRuns = plan.patternTimes().values().stream().mapToLong(Long::longValue).sum();
                    helper.assertTrue(plannedPatternRuns < computeWoodenPickaxeLogicalExecutions(requestedAmount),
                            "formal machine large planning should compress logical executions into fewer virtual pattern runs");
                    logPlanningBenchmark("formal_machine_planning_only", requestedAmount, elapsedMillis, snapshot);
                })
                .thenSucceed();
    }

    private static void runLargeWoodenPickaxeSubmitExecute(
            GameTestHelper helper,
            long requestedAmount,
            String benchmarkId
    ) {
        FormalMachineAutoCraftingGameTestFixture fixture = FormalMachineAutoCraftingGameTestFixture.create(helper);
        HighCapacityCraftingMachineBlockEntity machine = fixture.machine();
        AEItemKey output = AEItemKey.of(Items.WOODEN_PICKAXE);
        Future<ICraftingPlan>[] futureHolder = new Future[1];
        ICraftingPlan[] planHolder = new ICraftingPlan[1];
        ICraftingSubmitResult[] submitHolder = new ICraftingSubmitResult[1];
        long[] planningStartedAt = new long[1];
        long[] planningElapsedMillis = new long[1];
        long[] submitElapsedMillis = new long[1];
        long[] submitFinishedAt = new long[1];
        long expectedLogicalExecutions = computeWoodenPickaxeLogicalExecutions(requestedAmount);

        helper.startSequence()
                .thenWaitUntil(fixture::assertNetworkReady)
                .thenExecute(() -> {
                    fixture.installCreativeStorageCell(Items.OAK_LOG);
                    machine.fillCraftingPatternsForTest(
                            0,
                            CraftingPatternDataset.patternsOnly(
                                    CraftingPatternDataset.woodenPickaxePlanningSet(helper.getLevel()))
                    );
                    machine.setSpeedCardsForTest(4);
                    machine.resetBenchmarkCountersForTest();
                })
                .thenWaitUntil(() -> fixture.assertStoredLive(AEItemKey.of(Items.OAK_LOG), 1L))
                .thenWaitUntil(() -> {
                    List<IPatternDetails> patterns = List.copyOf(fixture.lookupCraftables(output));
                    helper.assertTrue(!patterns.isEmpty(), "formal machine should expose wooden pickaxe auto-crafting path");
                })
                .thenExecute(() -> {
                    planningStartedAt[0] = System.nanoTime();
                    futureHolder[0] = fixture.beginCraftingPlanFuture(output, requestedAmount);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        futureHolder[0] != null && futureHolder[0].isDone(),
                        "formal machine submit/execute planning future should complete"))
                .thenExecute(() -> {
                    planHolder[0] = resolveCompletedPlan(helper, fixture, futureHolder[0], output);
                    assertPlanMatchesAmount(helper, output, requestedAmount, planHolder[0]);
                    logPlanShape(benchmarkId, requestedAmount, planHolder[0]);
                    planningElapsedMillis[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - planningStartedAt[0]);
                    long submitStartedAt = System.nanoTime();
                    submitHolder[0] = fixture.submitCraftingPlan(planHolder[0]);
                    submitFinishedAt[0] = System.nanoTime();
                    submitElapsedMillis[0] = TimeUnit.NANOSECONDS.toMillis(submitFinishedAt[0] - submitStartedAt);
                    helper.assertTrue(submitHolder[0] != null && submitHolder[0].successful(),
                            "formal machine submit/execute path should submit successfully, errorCode="
                                    + (submitHolder[0] == null ? "null" : submitHolder[0].errorCode())
                                    + ", errorDetail="
                                    + (submitHolder[0] == null ? "null" : submitHolder[0].errorDetail())
                                    + ", planBytes="
                                    + (planHolder[0] == null ? -1L : planHolder[0].bytes()));
                    helper.assertTrue(submitHolder[0].link() != null,
                            "formal machine submit/execute path should return a crafting link");
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        fixture.requester().countAcceptedOutput(output) >= requestedAmount,
                        "formal machine submit/execute path should eventually emit all requested wooden pickaxes, "
                                + describeFormalMachineProgress(fixture, machine, output)))
                .thenExecute(() -> {
                    PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
                    long executeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submitFinishedAt[0]);
                    long totalElapsedMillis = planningElapsedMillis[0] + submitElapsedMillis[0] + executeElapsedMillis;
                    helper.assertTrue(snapshot.submitBenchmarkCount() >= 1,
                            "formal machine submit/execute path should record at least one submit benchmark");
                    helper.assertTrue(snapshot.submitBenchmarkSuccessCount() >= 1,
                            "formal machine submit/execute path should record at least one successful submit");
                    helper.assertTrue(snapshot.submitBenchmarkWallClockNanosMax() > 0,
                            "formal machine submit/execute path should record submit wall clock telemetry");
                    helper.assertTrue(snapshot.completionQueuePeak() > 0,
                            "formal machine submit/execute path should observe completion backlog");
                    helper.assertTrue(snapshot.completionBacklogExecutionsPeak() > 0,
                            "formal machine submit/execute path should record pending completion executions");
                    helper.assertTrue(snapshot.pendingCompletionTicks() > 0,
                            "formal machine submit/execute path should spend time in completion slicing");
                    helper.assertTrue(snapshot.completionSlicesProcessed() > 0,
                            "formal machine submit/execute path should process completion slices");
                    helper.assertTrue(!fixture.requester().isJobCanceled(),
                            "formal machine submit/execute path must not cancel the submitted job");
                    helper.assertTrue(fixture.requester().isJobFinished(),
                            "formal machine submit/execute path must eventually finish the submitted job");
                    helper.assertValueEqual(requestedAmount, fixture.requester().countAcceptedOutput(output),
                            "formal machine submit/execute path should deliver all requested final outputs");
                    helper.assertValueEqual(expectedLogicalExecutions, snapshot.jobsCompleted(),
                            "formal machine submit/execute path should complete the full wooden_pickaxe chain logical executions");
                    helper.assertTrue(snapshot.virtualScaledPatternHitCount() > 0,
                            "formal machine submit/execute path should use virtual scaled patterns");
                    helper.assertTrue(snapshot.largestVirtualPatternMultiplier() > 1,
                            "formal machine submit/execute path should record a virtual scaled multiplier greater than one");
                    helper.assertTrue(snapshot.virtualScaledPatternLogicalExecutionsSaved() > 0,
                            "formal machine submit/execute path should save logical executions through scaling");
                    logSubmitExecuteBenchmark(
                            benchmarkId,
                            requestedAmount,
                            expectedLogicalExecutions,
                            fixture.requester().countAcceptedOutput(output),
                            planningElapsedMillis[0],
                            submitElapsedMillis[0],
                            executeElapsedMillis,
                            totalElapsedMillis,
                            snapshot
                    );
                })
                .thenSucceed();
    }

    private static List<AEItemKey> repeatedOutputs(AEItemKey output, int count) {
        List<AEItemKey> outputs = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            outputs.add(output);
        }
        return List.copyOf(outputs);
    }

    private static String describeFormalMachineProgress(
            FormalMachineAutoCraftingGameTestFixture fixture,
            HighCapacityCraftingMachineBlockEntity machine,
            AEItemKey output
    ) {
        PatternBenchmarkSnapshot snapshot = machine.snapshotBenchmark();
        return "accepted="
                + fixture.requester().countAcceptedOutput(output)
                + ", stored="
                + fixture.countStoredLive(output)
                + ", jobsCompleted="
                + snapshot.jobsCompleted()
                + ", fastAccepted="
                + snapshot.fastPathAcceptedCount()
                + ", formalHits="
                + snapshot.formalMachineOptimizationHitCount()
                + ", pendingAeReturn="
                + snapshot.pendingAeReturnCount()
                + ", cpuWaitingReturn="
                + snapshot.cpuWaitingReturnAmount()
                + ", aeInsertSuccess="
                + snapshot.aeStorageInsertSuccessCount()
                + ", aeInsertAttempts="
                + snapshot.aeStorageInsertAttemptCount()
                + ", maxExecPerTask="
                + snapshot.maxExecutionCountPerTaskObserved()
                + ", largestBatch="
                + snapshot.largestObservedBatchSize()
                + ", bulkMax="
                + snapshot.bulkExtractionLogicalExecutionsMax()
                + ", templatedDispatch="
                + snapshot.templatedDispatchHitCount()
                + ", completionQueuePeak="
                + snapshot.completionQueuePeak()
                + ", completionBacklogPeak="
                + snapshot.completionBacklogExecutionsPeak()
                + ", queuedTasks="
                + snapshot.queuedTasks()
                + ", runningTasks="
                + snapshot.runningTasks()
                + ", outstanding="
                + snapshot.outstandingLogicalExecutions()
                + ", noProgressRetries="
                + snapshot.cpuWaitingNoProgressRetries();
    }

    private static void logPlanShape(String benchmarkId, long requestedAmount, ICraftingPlan plan) {
        if (plan == null) {
            LOGGER.info("IDEA1_PLAN_SHAPE benchmark={} requestedAmount={} plan=null", benchmarkId, requestedAmount);
            return;
        }
        LOGGER.info(
                "IDEA1_PLAN_SHAPE benchmark={} requestedAmount={} simulation={} bytes={} usedItems={} emittedItems={} missingItems={} patternTimes={}",
                benchmarkId,
                requestedAmount,
                plan.simulation(),
                plan.bytes(),
                formatKeyCounter(plan.usedItems()),
                formatKeyCounter(plan.emittedItems()),
                formatKeyCounter(plan.missingItems()),
                formatPatternTimes(plan.patternTimes())
        );
    }

    private static String formatKeyCounter(KeyCounter counter) {
        if (counter == null || counter.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (var entry : counter) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(formatAeKey(entry.getKey())).append('=').append(entry.getLongValue());
        }
        return builder.append('}').toString();
    }

    private static String formatPatternTimes(Map<IPatternDetails, Long> patternTimes) {
        if (patternTimes == null || patternTimes.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<IPatternDetails, Long> entry : patternTimes.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(formatPatternDetails(entry.getKey())).append('=').append(entry.getValue());
        }
        return builder.append('}').toString();
    }

    private static String formatPatternDetails(IPatternDetails patternDetails) {
        if (patternDetails == null) {
            return "null";
        }
        if (patternDetails instanceof IFormalMachineScaledPattern scaledPattern) {
            return formatPatternDetails(scaledPattern.basePattern()) + " x" + scaledPattern.multiplier();
        }
        if (patternDetails instanceof appeng.crafting.pattern.AECraftingPattern aeCraftingPattern) {
            return "AECraftingPattern[" + formatGenericStacks(aeCraftingPattern.getSparseInputs())
                    + " -> "
                    + formatGenericStacks(aeCraftingPattern.getOutputs())
                    + "]";
        }
        return patternDetails.getClass().getSimpleName() + "[" + patternDetails + "]";
    }

    private static String formatGenericStacks(List<GenericStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (GenericStack stack : stacks) {
            if (stack == null) {
                continue;
            }
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(formatAeKey(stack.what())).append('x').append(stack.amount());
        }
        return builder.append(']').toString();
    }

    private static String formatAeKey(AEKey key) {
        if (key == null) {
            return "null";
        }
        return key.toString();
    }

    private static List<AEItemKey> repeatedNativeOutputs(List<AEItemKey> outputs, int repetitions) {
        List<AEItemKey> sequence = new java.util.ArrayList<>(outputs.size() * repetitions);
        for (AEItemKey output : outputs) {
            for (int index = 0; index < repetitions; index++) {
                sequence.add(output);
            }
        }
        return List.copyOf(sequence);
    }

    private static List<AEItemKey> groupedOutputs(List<AEItemKey> outputs, int repetitionsPerOutput) {
        List<AEItemKey> sequence = new java.util.ArrayList<>(outputs.size() * Math.max(1, repetitionsPerOutput));
        for (AEItemKey output : outputs) {
            for (int index = 0; index < Math.max(1, repetitionsPerOutput); index++) {
                sequence.add(output);
            }
        }
        return List.copyOf(sequence);
    }

    private static void configureChainBenchmarkMachine(
            HighCapacityCraftingMachineBlockEntity machine,
            FormalMachineAENetworkGameTestFixture fixture,
            List<CraftingPatternDataset.EncodedPatternSpec> chained
    ) {
        machine.fillCraftingPatternsRoundRobinForTest(CraftingPatternDataset.patternsOnly(chained), 64);
        machine.setBaseOperationTicksForTest(40);
        machine.setSpeedCardsForTest(3);
        fixture.clearAeStorage();
    }

    private static void assertBatchPerformanceImprovement(
            GameTestHelper helper,
            String benchmarkId,
            long offStartTick,
            long offEndTick,
            PatternBenchmarkSnapshot offSnapshot,
            long batchStartTick,
            long batchEndTick,
            PatternBenchmarkSnapshot batchSnapshot
    ) {
        long offElapsedTicks = Math.max(1L, offEndTick - offStartTick);
        long batchElapsedTicks = Math.max(1L, batchEndTick - batchStartTick);
        long offPhysicalTasks = offSnapshot.jobsSubmitted() - offSnapshot.coalescedJobsSaved();
        long batchPhysicalTasks = batchSnapshot.jobsSubmitted() - batchSnapshot.coalescedJobsSaved();
        double offJobsPerTick = offSnapshot.jobsCompleted() / (double) offElapsedTicks;
        double batchJobsPerTick = batchSnapshot.jobsCompleted() / (double) batchElapsedTicks;
        double tickReductionRatio = 1.0D - (batchElapsedTicks / (double) offElapsedTicks);
        double throughputMultiplier = batchJobsPerTick / offJobsPerTick;

        helper.assertValueEqual(offSnapshot.jobsSubmitted(), offSnapshot.jobsCompleted(),
                "OFF baseline must complete all logical jobs for " + benchmarkId);
        helper.assertValueEqual(batchSnapshot.jobsSubmitted(), batchSnapshot.jobsCompleted(),
                "batch run must complete all logical jobs for " + benchmarkId);
        helper.assertTrue(batchSnapshot.aeStorageInsertAttemptCount() <= offSnapshot.aeStorageInsertAttemptCount(),
                "batch run must not increase AE insert attempts for " + benchmarkId);
        helper.assertTrue(batchPhysicalTasks <= offPhysicalTasks,
                "batch run must not increase physical task count for " + benchmarkId);

        LOGGER.info(
                "IDEA1_BATCH_BENCHMARK benchmark={} offTicks={} batchTicks={} tickReductionRatio={} "
                        + "offJobsPerTick={} batchJobsPerTick={} throughputMultiplier={} "
                        + "offInsertAttempts={} batchInsertAttempts={} offPhysicalTasks={} batchPhysicalTasks={} "
                        + "coalescedJobsSaved={} batchedAeReturns={} peakRunningUniquePatterns={}",
                benchmarkId,
                offElapsedTicks,
                batchElapsedTicks,
                tickReductionRatio,
                offJobsPerTick,
                batchJobsPerTick,
                throughputMultiplier,
                offSnapshot.aeStorageInsertAttemptCount(),
                batchSnapshot.aeStorageInsertAttemptCount(),
                offPhysicalTasks,
                batchPhysicalTasks,
                batchSnapshot.coalescedJobsSaved(),
                batchSnapshot.batchedAeReturnCount(),
                batchSnapshot.peakRunningUniquePatterns()
        );
    }

    private static void logPlanningBenchmark(
            String benchmarkId,
            long requestedAmount,
            long elapsedMillis,
            PatternBenchmarkSnapshot snapshot
    ) {
        logPlanningBenchmark(benchmarkId, requestedAmount, elapsedMillis, snapshot, "");
    }

    private static void logPlanningBenchmark(
            String benchmarkId,
            long requestedAmount,
            long elapsedMillis,
            PatternBenchmarkSnapshot snapshot,
            String extraFields
    ) {
        String suffix = extraFields == null || extraFields.isBlank() ? "" : " " + extraFields;
        LOGGER.info(
                "IDEA1_PLANNING_BENCHMARK benchmark={} requestedAmount={} elapsedMillis={} "
                        + "planningChunkCount={} largestPlanningChunkSize={} planningWallClockNanosMax={} "
                        + "planningAggregationHitCount={} planningAggregationFallbackCount={} "
                        + "planningEstimatedWorkMax={} planningWorkTriggeredCount={} "
                        + "deterministicPlanningHitCount={} deterministicPlanningFallbackCount={} "
                        + "virtualScaledPatternHitCount={} largestVirtualPatternMultiplier={} "
                        + "virtualScaledPatternLogicalExecutionsSaved={} "
                        + "formalMachineOptimizationHitCount={} fastPathAcceptedCount={} nonFormalProviderHitCount={} "
                        + "maxExecutableRunsHitCount={} maxExecutableRunsFallbackCount={} "
                        + "bulkExtractionLogicalExecutionsMax={} templatedDispatchHitCount={} "
                        + "compileCacheHitCount={} providerOverpressureRejectCount={} "
                        + "providerInactiveRejectCount={} providerPatternMissingRejectCount={} "
                        + "batchKeyMismatchRejectCount={} queueRejectCount={} "
                        + "backpressureRejectCount={} compiledTaskResolveRejectCount={}"
                        + suffix,
                benchmarkId,
                requestedAmount,
                elapsedMillis,
                snapshot.planningChunkCount(),
                snapshot.largestPlanningChunkSize(),
                snapshot.planningWallClockNanosMax(),
                snapshot.planningAggregationHitCount(),
                snapshot.planningAggregationFallbackCount(),
                snapshot.planningEstimatedWorkMax(),
                snapshot.planningWorkTriggeredCount(),
                snapshot.deterministicPlanningHitCount(),
                snapshot.deterministicPlanningFallbackCount(),
                snapshot.virtualScaledPatternHitCount(),
                snapshot.largestVirtualPatternMultiplier(),
                snapshot.virtualScaledPatternLogicalExecutionsSaved(),
                snapshot.formalMachineOptimizationHitCount(),
                snapshot.fastPathAcceptedCount(),
                snapshot.nonFormalProviderHitCount(),
                snapshot.maxExecutableRunsHitCount(),
                snapshot.maxExecutableRunsFallbackCount(),
                snapshot.bulkExtractionLogicalExecutionsMax(),
                snapshot.templatedDispatchHitCount(),
                snapshot.compileCacheHitCount(),
                snapshot.providerOverpressureRejectCount(),
                snapshot.providerInactiveRejectCount(),
                snapshot.providerPatternMissingRejectCount(),
                snapshot.batchKeyMismatchRejectCount(),
                snapshot.queueRejectCount(),
                snapshot.backpressureRejectCount(),
                snapshot.compiledTaskResolveRejectCount()
        );
    }

    private static void logSubmitExecuteBenchmark(
            String benchmarkId,
            long requestedAmount,
            long logicalExecutionsCompleted,
            long finalOutputsAccepted,
            long planningElapsedMillis,
            long submitElapsedMillis,
            long executeElapsedMillis,
            long totalElapsedMillis,
            PatternBenchmarkSnapshot snapshot
    ) {
        LOGGER.info(
                "IDEA1_SUBMIT_EXECUTE_BENCHMARK benchmark={} requestedAmount={} planningElapsedMillis={} "
                        + "submitElapsedMillis={} executeElapsedMillis={} totalElapsedMillis={} "
                        + "logicalExecutionsCompleted={} finalOutputsAccepted={} "
                        + "peakRunningTasks={} peakRunningUniquePatterns={} completionQueuePeak={} "
                        + "completionBacklogExecutionsPeak={} pendingCompletionTicks={} completionSlicesProcessed={} "
                        + "aeReturnBlockedTicks={} coalescedJobsSaved={} largestObservedBatchSize={} "
                        + "planningEstimatedWorkMax={} planningWorkTriggeredCount={} fastPathExtractionBudget={} "
                        + "formalTimingCorrectionCount={} formalTimingProgressClampCount={} formalTimingEtaClampCount={} "
                        + "cpuWaitingReturnAmount={} cpuWaitingReturnBudgetStopCount={} "
                        + "largestCpuWaitingReturnAmount={} cpuWaitingReturnOverBudgetCount={} "
                        + "tickBudgetHardStopCount={} maxTickBudgetNanosObserved={} "
                        + "virtualScaledPatternHitCount={} largestVirtualPatternMultiplier={} "
                        + "virtualScaledPatternLogicalExecutionsSaved={} "
                        + "maxExecutableRunsHitCount={} maxExecutableRunsFallbackCount={} "
                        + "bulkExtractionLogicalExecutionsMax={} templatedDispatchHitCount={} "
                        + "compileCacheHitCount={} providerOverpressureRejectCount={} "
                        + "providerInactiveRejectCount={} providerPatternMissingRejectCount={} "
                        + "batchKeyMismatchRejectCount={} queueRejectCount={} "
                        + "backpressureRejectCount={} compiledTaskResolveRejectCount={}",
                benchmarkId,
                requestedAmount,
                planningElapsedMillis,
                submitElapsedMillis,
                executeElapsedMillis,
                totalElapsedMillis,
                logicalExecutionsCompleted,
                finalOutputsAccepted,
                snapshot.peakRunningTasks(),
                snapshot.peakRunningUniquePatterns(),
                snapshot.completionQueuePeak(),
                snapshot.completionBacklogExecutionsPeak(),
                snapshot.pendingCompletionTicks(),
                snapshot.completionSlicesProcessed(),
                snapshot.aeReturnBlockedTicks(),
                snapshot.coalescedJobsSaved(),
                snapshot.largestObservedBatchSize(),
                snapshot.planningEstimatedWorkMax(),
                snapshot.planningWorkTriggeredCount(),
                snapshot.fastPathExtractionBudget(),
                snapshot.formalTimingCorrectionCount(),
                snapshot.formalTimingProgressClampCount(),
                snapshot.formalTimingEtaClampCount(),
                snapshot.cpuWaitingReturnAmount(),
                snapshot.cpuWaitingReturnBudgetStopCount(),
                snapshot.largestCpuWaitingReturnAmount(),
                snapshot.cpuWaitingReturnOverBudgetCount(),
                snapshot.tickBudgetHardStopCount(),
                snapshot.maxTickBudgetNanosObserved(),
                snapshot.virtualScaledPatternHitCount(),
                snapshot.largestVirtualPatternMultiplier(),
                snapshot.virtualScaledPatternLogicalExecutionsSaved(),
                snapshot.maxExecutableRunsHitCount(),
                snapshot.maxExecutableRunsFallbackCount(),
                snapshot.bulkExtractionLogicalExecutionsMax(),
                snapshot.templatedDispatchHitCount(),
                snapshot.compileCacheHitCount(),
                snapshot.providerOverpressureRejectCount(),
                snapshot.providerInactiveRejectCount(),
                snapshot.providerPatternMissingRejectCount(),
                snapshot.batchKeyMismatchRejectCount(),
                snapshot.queueRejectCount(),
                snapshot.backpressureRejectCount(),
                snapshot.compiledTaskResolveRejectCount()
        );
    }

    private static long computeWoodenPickaxeLogicalExecutions(long requestedAmount) {
        long stickCrafts = Math.ceilDiv(requestedAmount, 2L);
        long plankCrafts = Math.ceilDiv(Math.addExact(
                Math.multiplyExact(requestedAmount, 3L),
                Math.multiplyExact(stickCrafts, 2L)
        ), 4L);
        return Math.addExact(requestedAmount, Math.addExact(stickCrafts, plankCrafts));
    }
}
