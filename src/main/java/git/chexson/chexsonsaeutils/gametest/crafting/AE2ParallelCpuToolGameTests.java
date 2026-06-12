package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Settings;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridHelper;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.MolecularAssemblerBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import com.mojang.logging.LogUtils;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingStatusMenu;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.block.crafting.AE2ParallelCpuToolBlock;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingLane;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuGridBudgetLedger;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuMetrics;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuProviderBackoff;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuWaitingIndex;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@PrefixGameTestTemplate(false)
public final class AE2ParallelCpuToolGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPLATE = "ae2_parallel_cpu_tool_smoke";
    private static final String BATCH = "idea2_parallel_cpu_tool";
    private static final long NATIVE_4X4X4_PATTERN_PUSH_BASELINE = 65L;
    private static final BlockPos ENERGY_POS = new BlockPos(0, 1, 1);
    private static final BlockPos CPU_TOOL_POS = new BlockPos(1, 1, 1);
    private static final BlockPos ME_CHEST_POS = new BlockPos(2, 1, 1);
    private static final BlockPos PROVIDER_POS = new BlockPos(1, 1, 2);
    private static final BlockPos NATIVE_PROVIDER_POS = new BlockPos(2, 1, 2);
    private static final BlockPos NATIVE_ASSEMBLER_POS = new BlockPos(2, 1, 3);
    private static final BlockPos NATIVE_STORAGE_POS = new BlockPos(1, 2, 1);
    private static final BlockPos NATIVE_ACCELERATOR_POS = new BlockPos(1, 3, 1);
    private static final BlockPos NATIVE_UNIT_POS = new BlockPos(1, 4, 1);

    private AE2ParallelCpuToolGameTests() {
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void parallelCpuToolAdvertisesExtremeFakePool(GameTestHelper helper) {
        installParallelCpuToolNetwork(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenExecute(() -> assertVisibleCpuContract(helper, 2))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void parallelCpuToolOpensCraftingStatusMenu(GameTestHelper helper) {
        installParallelCpuToolNetwork(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenExecute(() -> {
                    AE2ParallelCpuToolBlockEntity cpuTool = helper.getBlockEntity(CPU_TOOL_POS);
                    helper.assertTrue(cpuTool.getActionableNode() != null,
                            "parallel CPU tool must expose an actionable AE node for AE2 crafting menus");
                    helper.assertTrue(cpuTool.getActionableNode().getGrid() == cpuTool.getGrid(),
                            "parallel CPU tool actionable node must point at the same AE grid");
                    helper.assertTrue(cpuTool.getGrid()
                                    .getMachines(AE2ParallelCpuToolBlockEntity.class)
                                    .contains(cpuTool),
                            "parallel CPU tool must be discoverable through AE grid machine lookup");
                    helper.assertTrue(AE2ParallelCpuToolBlock.craftingCpuMenuTypeForServerPath(cpuTool)
                                    == ParallelCraftingCPUMenu.TYPE,
                            "right-click server path must open the parallel crafting CPU menu type");
                    Player player = menuPlayer(helper);
                    ParallelCraftingCPUMenu menu = AE2ParallelCpuToolBlock.createCraftingCpuMenuForServerPath(
                            1,
                            player.getInventory(),
                            cpuTool
                    );
                    helper.assertTrue(menu.getParallelCpuHost() == cpuTool,
                            "Crafting Status menu host must be the parallel CPU tool block entity");
                    helper.assertTrue(menu.getType() == ParallelCraftingCPUMenu.TYPE,
                            "parallel CPU tool must use the parallel crafting CPU menu type");
                    helper.assertTrue(menu.getTarget() == cpuTool,
                            "parallel CPU menu target must remain the tool block entity");
                    helper.assertTrue(menu.getTarget() instanceof AE2ParallelCpuToolBlockEntity,
                            "parallel CPU menu target must be an AE2ParallelCpuToolBlockEntity");
                    helper.assertTrue(menu.allowConfiguration(),
                            "parallel CPU menu must expose CPU selection mode configuration");
                    broadcastChangesForMockPlayer(helper, menu::broadcastChanges);
                    menu.removed(player);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void parallelCpuToolCraftConfirmMenuSeesAutomaticCpu(GameTestHelper helper) {
        installParallelCpuToolNetwork(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenExecute(() -> {
                    MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
                    Player player = menuPlayer(helper);
                    assertVisibleCpuContract(helper, 2);
                    CraftConfirmMenu menu = new CraftConfirmMenu(2, player.getInventory(), meChest);
                    helper.assertTrue(menu.getHost() == meChest,
                            "Craft Confirm menu host must be the ME chest terminal block entity");
                    helper.assertTrue(menu.hasNoCPU(),
                            "Craft Confirm menu must start without CPU data before server sync");
                    helper.assertTrue(menu.getPlan() == null,
                            "Craft Confirm menu must start without a resolved crafting plan");
                    broadcastChangesForMockPlayer(helper, menu::broadcastChanges);
                    helper.assertTrue(!menu.hasNoCPU(),
                            "Craft Confirm menu must see the parallel fake pool as an available CPU");
                    helper.assertTrue(menu.getName() == null,
                            "Craft Confirm menu must keep automatic CPU selection by default");
                    helper.assertValueEqual(0L, menu.getCpuAvailableBytes(),
                            "automatic CPU selection should not expose a selected CPU byte count");
                    helper.assertValueEqual(0, menu.getCpuCoProcessors(),
                            "automatic CPU selection should not expose a selected CPU processor count");
                    menu.removed(player);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void parallelCpuToolCraftConfirmMenuPlayerStartJobSubmitsToParallelCpu(GameTestHelper helper) {
        ParallelSubmitHarness harness = installParallelCpuToolCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.OAK_BUTTON);
        CraftConfirmMenu[] menuHolder = new CraftConfirmMenu[1];
        Player[] playerHolder = new Player[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(List.of(CraftingPatternDataset.smallMixedSet(helper.getLevel())
                            .getFirst().encodedPattern()));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 1)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_PLANKS), 1L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> {
                    Player player = menuPlayer(helper);
                    playerHolder[0] = player;
                    CraftConfirmMenu menu = new CraftConfirmMenu(3, player.getInventory(), harness.meChest);
                    menuHolder[0] = menu;
                    helper.assertTrue(menu.getHost() == harness.meChest,
                            "player startJob coverage must use the ME chest as the AE terminal host");
                    helper.assertTrue(menu.planJob(output, 1, CalculationStrategy.REPORT_MISSING_ITEMS),
                            "Craft Confirm menu must begin a crafting plan against the AE terminal host");
                })
                .thenWaitUntil(() -> {
                    CraftConfirmMenu menu = requireCraftConfirmMenu(menuHolder);
                    broadcastChangesForMockPlayer(helper, menu::broadcastChanges);
                    helper.assertTrue(!menu.hasNoCPU(),
                            "Craft Confirm menu must discover the parallel fake pool through the AE terminal host");
                    helper.assertTrue(menu.getPlan() != null,
                            "Craft Confirm menu must resolve a plan before startJob");
                    helper.assertTrue(menu.getName() == null,
                            "Craft Confirm menu must keep automatic CPU selection before player startJob");
                })
                .thenExecute(() -> {
                    CraftConfirmMenu menu = requireCraftConfirmMenu(menuHolder);
                    runMenuActionForMockPlayer(helper, menu::startJob, "startJob");
                    helper.assertValueEqual(1, harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                            "player startJob must allocate one active parallel CPU lane");
                    helper.assertTrue(menu.submitError.result() == null,
                            "player startJob must not produce a Craft Confirm submit error");
                })
                .thenWaitUntil(() -> harness.assertStored(output, 1L))
                .thenWaitUntil(() -> helper.assertValueEqual(0,
                        harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                        "player startJob lane must be recycled after completion"))
                .thenExecute(() -> {
                    CraftConfirmMenu menu = menuHolder[0];
                    Player player = playerHolder[0];
                    if (menu != null && player != null) {
                        menu.removed(player);
                    }
                    assertVisibleCpuContract(helper, 2);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 180)
    public static void parallelCpuToolAutoSelectionModesRespectPlayerAndMachineSources(GameTestHelper helper) {
        ParallelSubmitHarness harness = installParallelCpuToolCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.OAK_BUTTON);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(List.of(CraftingPatternDataset.smallMixedSet(helper.getLevel())
                            .getFirst().encodedPattern()));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 8)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_PLANKS), 8L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, 1L))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(() -> {
                    IActionSource playerSource = IActionSource.ofPlayer(menuPlayer(helper), harness.cpuTool);
                    IActionSource machineSource = harness.requester.getActionSource();

                    setSelectionMode(helper, harness.cpuTool, CpuSelectionMode.ANY);
                    harness.submitWithAutoSelection(playerSource, "ANY player auto-selection", 1);
                    harness.submitWithAutoSelection(machineSource, "ANY machine auto-selection", 2);

                    setSelectionMode(helper, harness.cpuTool, CpuSelectionMode.PLAYER_ONLY);
                    harness.submitWithAutoSelection(playerSource, "PLAYER_ONLY player auto-selection", 3);
                    harness.assertAutoSelectionRejected(machineSource, "PLAYER_ONLY machine auto-selection");

                    setSelectionMode(helper, harness.cpuTool, CpuSelectionMode.MACHINE_ONLY);
                    harness.submitWithAutoSelection(machineSource, "MACHINE_ONLY machine auto-selection", 4);
                    harness.assertAutoSelectionRejected(playerSource, "MACHINE_ONLY player auto-selection");
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 180)
    public static void parallelCpuToolExplicitFakePoolTargetIgnoresRestrictedMode(GameTestHelper helper) {
        ParallelSubmitHarness harness = installParallelCpuToolCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.OAK_BUTTON);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(List.of(CraftingPatternDataset.smallMixedSet(helper.getLevel())
                            .getFirst().encodedPattern()));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 1)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_PLANKS), 1L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, 1L))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(() -> {
                    setSelectionMode(helper, harness.cpuTool, CpuSelectionMode.PLAYER_ONLY);
                    harness.submitToParallelFakePool(
                            harness.requester.getActionSource(),
                            "PLAYER_ONLY explicit fake pool target from machine source",
                            1
                    );
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 360)
    public static void parallelCpuToolBuffersChainedIntermediatesInsideLaneInventory(GameTestHelper helper) {
        ParallelSubmitHarness harness = installParallelCpuToolCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.LADDER);
        List<AEItemKey> intermediates = List.of(AEItemKey.of(Items.OAK_PLANKS), AEItemKey.of(Items.STICK));

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(CraftingPatternDataset.patternsOnly(
                            CraftingPatternDataset.chainedSet(helper.getLevel())
                    ));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 8L)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_LOG), 8L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, 4L))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(harness::submitToParallelFakePool)
                .thenWaitUntil(() -> harness.assertAnyBufferedIntermediate(intermediates))
                .thenWaitUntil(() -> helper.assertTrue(harness.totalObservableOutput(output) >= 4L,
                        "parallel CPU chained job must return at least the requested ladder amount, "
                                + harness.describeOutputProgress(output, intermediates)))
                .thenWaitUntil(() -> helper.assertTrue(harness.requester.isJobFinished(),
                        "parallel CPU chained job requester link must finish"))
                .thenWaitUntil(() -> helper.assertValueEqual(0,
                        harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                        "parallel CPU chained job must recycle its lane after completion"))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 360)
    public static void parallelCpuToolBuffersNativeAePatternProviderIntermediatesInsideLaneInventory(
            GameTestHelper helper
    ) {
        NativeAePatternProviderHarness harness = installParallelCpuToolNativeAeCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.LADDER);
        List<AEItemKey> intermediates = List.of(AEItemKey.of(Items.OAK_PLANKS), AEItemKey.of(Items.STICK));
        var sequence = helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(CraftingPatternDataset.patternsOnly(
                            CraftingPatternDataset.chainedSet(helper.getLevel())
                    ));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 8L)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_LOG), 8L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, 4L))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(harness::submitToParallelFakePool);
        for (int tick = 0; tick < 40; tick++) {
            sequence = sequence.thenExecuteAfter(1, () -> harness.assertNoTransientAeLeak(intermediates));
        }
        sequence
                .thenWaitUntil(() -> harness.assertAnyBufferedIntermediate(intermediates))
                .thenWaitUntil(() -> helper.assertTrue(harness.totalObservableOutput(output) >= 4L,
                        "native AE provider chained job must return at least the requested ladder amount, "
                                + harness.describeOutputProgress(output, intermediates)))
                .thenWaitUntil(() -> helper.assertTrue(harness.requester.isJobFinished(),
                        "native AE provider chained job requester link must finish"))
                .thenWaitUntil(() -> helper.assertValueEqual(0,
                        harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                        "native AE provider chained job must recycle its lane after completion"))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void parallelCpuToolBuffersNativeAeProviderFinalOutputDuringExternalIngress(
            GameTestHelper helper
    ) {
        NativeAePatternProviderHarness harness = installParallelCpuToolNativeAeCraftingNetwork(
                helper,
                (what, amount, mode) -> mode == Actionable.SIMULATE ? 0L : Math.max(0L, amount)
        );
        AEItemKey output = AEItemKey.of(Items.LADDER);
        List<AEItemKey> trackedKeys = List.of(
                output,
                AEItemKey.of(Items.OAK_PLANKS),
                AEItemKey.of(Items.STICK)
        );
        var sequence = helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(CraftingPatternDataset.patternsOnly(
                            CraftingPatternDataset.chainedSet(helper.getLevel())
                    ));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 8L)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_LOG), 8L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, 4L))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(harness::submitToParallelFakePool);
        for (int tick = 0; tick < 40; tick++) {
            sequence = sequence.thenExecuteAfter(1, () -> harness.assertNoTransientAeLeak(trackedKeys));
        }
        sequence
                .thenWaitUntil(() -> helper.assertTrue(
                        harness.cpuTool.getParallelCpuCluster().waitingAmountForTest(output) > 0L
                                || harness.cpuTool.getParallelCpuCluster().storedAmountForTest(output) > 0L,
                        "external ingress final output must be buffered or waiting inside the lane before completion"))
                .thenWaitUntil(() -> helper.assertTrue(harness.requester.isJobFinished(),
                        "external ingress final output requester link must finish"))
                .thenWaitUntil(() -> helper.assertValueEqual(0,
                        harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                        "external ingress final output job must recycle its lane after completion"))
                .thenWaitUntil(() -> helper.assertTrue(
                        harness.cpuTool.getGrid().getStorageService().getCachedInventory().get(output) >= 4L,
                        "external ingress final output must return to AE storage after lane completion"))
                .thenExecute(() -> helper.assertValueEqual(0L,
                        harness.requester.countAcceptedOutput(output),
                        "external ingress final output must stay out of requester acceptance accounting"))
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void parallelCpuToolCraftConfirmMenuNativeAeProviderBuffersIntermediatesInsideLaneInventory(
            GameTestHelper helper
    ) {
        NativeAePatternProviderHarness harness = installParallelCpuToolNativeAeCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.LADDER);
        List<AEItemKey> intermediates = List.of(AEItemKey.of(Items.OAK_PLANKS), AEItemKey.of(Items.STICK));
        CraftConfirmMenu[] menuHolder = new CraftConfirmMenu[1];
        Player[] playerHolder = new Player[1];

        var sequence = helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(CraftingPatternDataset.patternsOnly(
                            CraftingPatternDataset.chainedSet(helper.getLevel())
                    ));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 8L)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_LOG), 8L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> {
                    Player player = menuPlayer(helper);
                    playerHolder[0] = player;
                    CraftConfirmMenu menu = new CraftConfirmMenu(6, player.getInventory(), harness.meChest);
                    menuHolder[0] = menu;
                    helper.assertTrue(menu.getHost() == harness.meChest,
                            "native AE provider player startJob coverage must use the ME chest as the AE terminal host");
                    helper.assertTrue(menu.planJob(output, 4, CalculationStrategy.REPORT_MISSING_ITEMS),
                            "native AE provider Craft Confirm menu must begin a crafting plan against the AE terminal host");
                })
                .thenWaitUntil(() -> {
                    CraftConfirmMenu menu = requireCraftConfirmMenu(menuHolder);
                    broadcastChangesForMockPlayer(helper, menu::broadcastChanges);
                    helper.assertTrue(!menu.hasNoCPU(),
                            "native AE provider Craft Confirm menu must discover the parallel fake pool");
                    helper.assertTrue(menu.getPlan() != null,
                            "native AE provider Craft Confirm menu must resolve a plan before startJob");
                    helper.assertTrue(menu.getName() == null,
                            "native AE provider Craft Confirm menu must keep automatic CPU selection before startJob");
                })
                .thenExecute(() -> {
                    CraftConfirmMenu menu = requireCraftConfirmMenu(menuHolder);
                    runMenuActionForMockPlayer(helper, menu::startJob, "nativeAeProviderStartJob");
                    helper.assertValueEqual(1, harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                            "native AE provider player startJob must allocate one active parallel CPU lane");
                    helper.assertTrue(menu.submitError.result() == null,
                            "native AE provider player startJob must not produce a Craft Confirm submit error");
                });
        for (int tick = 0; tick < 40; tick++) {
            sequence = sequence.thenExecuteAfter(1, () -> harness.assertNoTransientAeLeak(intermediates));
        }
        sequence
                .thenWaitUntil(() -> harness.assertAnyBufferedIntermediate(intermediates))
                .thenWaitUntil(() -> harness.assertStored(output, 4L))
                .thenWaitUntil(() -> helper.assertValueEqual(0,
                        harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                        "native AE provider player startJob lane must be recycled after completion"))
                .thenExecute(() -> {
                    CraftConfirmMenu menu = menuHolder[0];
                    Player player = playerHolder[0];
                    if (menu != null && player != null) {
                        menu.removed(player);
                    }
                    assertVisibleCpuContract(helper, 2);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 220)
    public static void parallelCpuToolCpuListRefreshesFakeAndActiveSummaryModes(GameTestHelper helper) {
        ParallelSubmitHarness harness = installParallelCpuToolCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.OAK_BUTTON);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(List.of(CraftingPatternDataset.smallMixedSet(helper.getLevel())
                            .getFirst().encodedPattern()));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 1)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_PLANKS), 1L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, 1L))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(() -> {
                    setSelectionMode(helper, harness.cpuTool, CpuSelectionMode.PLAYER_ONLY);
                    assertVisibleCpuContract(helper, 2);
                    Player player = menuPlayer(helper);
                    CraftingStatusMenu menu = new CraftingStatusMenu(5, player.getInventory(), harness.meChest);
                    helper.assertTrue(menu.getHost() == harness.meChest,
                            "Crafting Status menu host must be the ME chest terminal block entity");
                    try {
                        broadcastMenuChangesForMockPlayer(helper, menu, 20);
                        assertVisibleCpuContract(helper, 2);
                        assertCpuListFakePoolMode(helper, menu, CpuSelectionMode.PLAYER_ONLY);

                        harness.submitToParallelFakePool(
                                harness.requester.getActionSource(),
                                "PLAYER_ONLY explicit fake pool target before CPU list refresh",
                                1
                        );
                        broadcastMenuChangesForMockPlayer(helper, menu, 1);
                        assertVisibleCpuContract(helper, 2);
                        assertCpuListFakeAndActiveSummaryModes(helper, menu, CpuSelectionMode.PLAYER_ONLY);

                        setSelectionMode(helper, harness.cpuTool, CpuSelectionMode.MACHINE_ONLY);
                        broadcastMenuChangesForMockPlayer(helper, menu, 20);
                        assertCpuListFakeAndActiveSummaryModes(helper, menu, CpuSelectionMode.MACHINE_ONLY);
                    } finally {
                        menu.removed(player);
                    }
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void parallelCpuToolDoesNotJoinNativeCraftingCpuMultiblock(GameTestHelper helper) {
        installParallelCpuToolNetwork(helper);
        helper.setBlock(NATIVE_STORAGE_POS, AEBlocks.CRAFTING_STORAGE_1K.block());
        helper.setBlock(NATIVE_ACCELERATOR_POS, AEBlocks.CRAFTING_ACCELERATOR.block());
        helper.setBlock(NATIVE_UNIT_POS, AEBlocks.CRAFTING_UNIT.block());

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenExecute(() -> {
                    AE2ParallelCpuToolBlockEntity cpuTool = helper.getBlockEntity(CPU_TOOL_POS);
                    CraftingBlockEntity nativeStorage = helper.getBlockEntity(NATIVE_STORAGE_POS);
                    CraftingBlockEntity nativeAccelerator = helper.getBlockEntity(NATIVE_ACCELERATOR_POS);
                    CraftingBlockEntity nativeUnit = helper.getBlockEntity(NATIVE_UNIT_POS);

                    helper.assertTrue(cpuTool.getParallelCpuCluster().isActive(),
                            "parallel CPU tool must remain active as its own CPU provider");
                    helper.assertTrue(nativeStorage.getCluster() != null,
                            "native AE2 crafting blocks may form their own CPU cluster");
                    helper.assertTrue(nativeStorage.getCluster() == nativeAccelerator.getCluster(),
                            "native accelerator must join the native AE2 CPU cluster");
                    helper.assertTrue(nativeStorage.getCluster() == nativeUnit.getCluster(),
                            "native crafting unit must join the native AE2 CPU cluster");
                    for (var blockEntities = nativeStorage.getCluster().getBlockEntities(); blockEntities.hasNext(); ) {
                        helper.assertTrue(!blockEntities.next().getBlockPos().equals(cpuTool.getBlockPos()),
                                "parallel CPU tool must never be part of a native AE2 crafting CPU cluster");
                    }
                    assertVisibleCpuContract(helper, 3);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void parallelCpuToolSmoke1024Lanes(GameTestHelper helper) {
        installParallelCpuToolNetwork(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenExecute(() -> {
                    assertVisibleCpuContract(helper, 2);
                    assertSyntheticLaneScale(helper, "smoke_1024", 1_024);
                    helper.assertValueEqual(
                            65_536,
                            ParallelCraftingCpuConfig.current().maxInternalLanesPerBlock(),
                            "smoke must run against the official 65,536 lane-per-block ceiling"
                    );
                    helper.assertTrue(
                            ParallelCraftingCpuConfig.current().maxInternalLanesPerBlock() >= 1_024,
                            "smoke 1,024-lane target must fit inside one official tool block"
                    );
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 360)
    public static void parallelCpuToolSubmitsCompletesAndRecyclesRealJob(GameTestHelper helper) {
        ParallelSubmitHarness harness = installParallelCpuToolCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.OAK_BUTTON);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(List.of(CraftingPatternDataset.smallMixedSet(helper.getLevel())
                            .getFirst().encodedPattern()));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 1)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_PLANKS), 1L))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, 1L))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(harness::submitWithAutoSelection)
                .thenWaitUntil(() -> helper.assertTrue(
                        harness.totalObservableOutput(output) >= 1L,
                        "parallel CPU real job must return the crafted output through requester or AE storage, "
                                + harness.describeFinalOutputVisibility(output)
                ))
                .thenWaitUntil(() -> helper.assertValueEqual(0,
                        harness.cpuTool.getParallelCpuCluster().activeLaneCount(),
                        "parallel CPU lane must be recycled after completion"))
                .thenExecute(() -> {
                    helper.assertTrue(harness.requester.isJobFinished(),
                            "parallel CPU real job link must finish after output delivery");
                    ParallelCpuMetrics.Snapshot metrics = harness.cpuTool
                            .getParallelCpuCluster()
                            .metricsSnapshotForTest();
                    helper.assertTrue(metrics.submittedVirtualCpuCount() >= 1L,
                            "parallel CPU metrics must record the submitted virtual CPU");
                    helper.assertTrue(metrics.completedVirtualCpuCount() >= 1L,
                            "parallel CPU metrics must record lane completion");
                    assertVisibleCpuContract(helper, 2);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 520)
    public static void parallelCpuToolThroughputBeatsNative4x4x4Baseline(GameTestHelper helper) {
        ParallelSubmitHarness harness = installParallelCpuToolCraftingNetwork(helper);
        AEItemKey output = AEItemKey.of(Items.OAK_BUTTON);
        long requestedAmount = 128L;

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenWaitUntil(harness::assertProviderReady)
                .thenExecute(() -> {
                    harness.installProviderPatterns(List.of(CraftingPatternDataset.smallMixedSet(helper.getLevel())
                            .getFirst().encodedPattern()));
                    harness.seedInputs(List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), requestedAmount)));
                })
                .thenWaitUntil(() -> harness.assertStored(AEItemKey.of(Items.OAK_PLANKS), requestedAmount))
                .thenWaitUntil(() -> harness.assertCraftable(output))
                .thenExecute(() -> harness.beginPlan(output, requestedAmount))
                .thenWaitUntil(harness::assertPlanDone)
                .thenExecute(harness::submitToParallelFakePool)
                .thenWaitUntil(() -> helper.assertTrue(
                        harness.cpuTool.getParallelCpuCluster()
                                .metricsSnapshotForTest()
                                .pushedPatternCount() > NATIVE_4X4X4_PATTERN_PUSH_BASELINE,
                        "parallel CPU must push more patterns in one lane burst than the native 4x4x4 CPU baseline"
                ))
                .thenExecute(() -> {
                    ParallelCpuMetrics.Snapshot metrics = harness.cpuTool
                            .getParallelCpuCluster()
                            .metricsSnapshotForTest();
                    helper.assertTrue(metrics.pushedPatternCount() >= requestedAmount,
                            "parallel CPU must push the full 128-pattern throughput benchmark burst");
                    LOGGER.info(
                            "IDEA2_PARALLEL_CPU_BENCHMARK benchmark=all_available_128 requestedAmount={} "
                                    + "pushedPatternCount={} providerScanCount={} zeroProgressTickCount={} "
                                    + "tickNanosP95={} tickNanosMax={}",
                            requestedAmount,
                            metrics.pushedPatternCount(),
                            metrics.providerScanCount(),
                            metrics.zeroProgressTickCount(),
                            metrics.tickNanosP95(),
                            metrics.tickNanosMax()
                    );
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void parallelCpuToolStress65536Lanes(GameTestHelper helper) {
        installParallelCpuToolNetwork(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenExecute(() -> {
                    assertVisibleCpuContract(helper, 2);
                    assertSyntheticLaneScale(helper, "stress_65536", 65_536);
                    assertSyntheticAllBusyProviderBackoff(helper, 65_536);
                    helper.assertValueEqual(
                            65_536,
                            ParallelCraftingCpuConfig.current().maxInternalLanesPerBlock(),
                            "stress must preserve the 65,536 lane-per-block target"
                    );
                    helper.assertValueEqual(
                            4_096,
                            ParallelCraftingCpuConfig.current().laneShardCount(),
                            "stress must preserve shard rotation for large lane counts"
                    );
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = Chexsonsaeutils.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void parallelCpuToolExtreme1048576Lanes(GameTestHelper helper) {
        if (!Boolean.getBoolean("chexsonsaeutils.parallelCpuExtremeGameTest")) {
            helper.succeed();
            return;
        }
        installParallelCpuToolNetwork(helper);

        helper.startSequence()
                .thenWaitUntil(() -> assertParallelCpuToolReady(helper))
                .thenExecute(() -> {
                    assertVisibleCpuContract(helper, 2);
                    assertSyntheticLaneScale(helper, "extreme_1048576", 1_048_576);
                    helper.assertValueEqual(
                            1_048_576,
                            ParallelCraftingCpuConfig.current().maxInternalLanesPerGrid(),
                            "extreme benchmark must preserve the 1,048,576 lane-per-grid ceiling"
                    );
                })
                .thenSucceed();
    }

    private static void installParallelCpuToolNetwork(GameTestHelper helper) {
        helper.setBlock(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(CPU_TOOL_POS, Chexsonsaeutils.AE2_PARALLEL_CPU_TOOL_BLOCK.get());
        helper.setBlock(ME_CHEST_POS, AEBlocks.ME_CHEST.block());
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
    }

    private static ParallelSubmitHarness installParallelCpuToolCraftingNetwork(GameTestHelper helper) {
        installParallelCpuToolNetwork(helper);
        helper.setBlock(PROVIDER_POS, Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get());
        AE2ParallelCpuToolBlockEntity cpuTool = helper.getBlockEntity(CPU_TOOL_POS);
        HighCapacityCraftingMachineBlockEntity provider = helper.getBlockEntity(PROVIDER_POS);
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
        GameTestSimulationRequester simulationRequester = new GameTestSimulationRequester(provider);
        GameTestCraftingRequester requester = new GameTestCraftingRequester(provider);
        return new ParallelSubmitHarness(helper, cpuTool, provider, meChest, simulationRequester, requester);
    }

    private static NativeAePatternProviderHarness installParallelCpuToolNativeAeCraftingNetwork(GameTestHelper helper) {
        return installParallelCpuToolNativeAeCraftingNetwork(helper, GameTestCraftingRequester.AcceptancePolicy.acceptAll());
    }

    private static NativeAePatternProviderHarness installParallelCpuToolNativeAeCraftingNetwork(
            GameTestHelper helper,
            GameTestCraftingRequester.AcceptancePolicy acceptancePolicy
    ) {
        installParallelCpuToolNetwork(helper);
        helper.setBlock(NATIVE_PROVIDER_POS, AEBlocks.PATTERN_PROVIDER.block());
        helper.setBlock(NATIVE_ASSEMBLER_POS, AEBlocks.MOLECULAR_ASSEMBLER.block());
        AE2ParallelCpuToolBlockEntity cpuTool = helper.getBlockEntity(CPU_TOOL_POS);
        PatternProviderBlockEntity provider = helper.getBlockEntity(NATIVE_PROVIDER_POS);
        MolecularAssemblerBlockEntity assembler = helper.getBlockEntity(NATIVE_ASSEMBLER_POS);
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
        GameTestSimulationRequester simulationRequester = new GameTestSimulationRequester(cpuTool);
        GameTestCraftingRequester requester = new GameTestCraftingRequester(cpuTool, acceptancePolicy);
        return new NativeAePatternProviderHarness(
                helper,
                cpuTool,
                provider,
                assembler,
                meChest,
                simulationRequester,
                requester
        );
    }

    private static void assertParallelCpuToolReady(GameTestHelper helper) {
        CreativeEnergyCellBlockEntity energyCell = helper.getBlockEntity(ENERGY_POS);
        AE2ParallelCpuToolBlockEntity cpuTool = helper.getBlockEntity(CPU_TOOL_POS);
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);

        helper.assertTrue(energyCell.getMainNode().isReady(), "energy cell node must be ready");
        helper.assertTrue(cpuTool.getMainNode().isReady(), "parallel CPU tool node must be ready");
        helper.assertTrue(meChest.getMainNode().isReady(), "ME chest node must be ready");
        ensureConnected(cpuTool, energyCell, meChest);
        helper.assertTrue(cpuTool.getMainNode().isActive(), "parallel CPU tool must be active");
        helper.assertTrue(cpuTool.getGrid() != null, "parallel CPU tool must join an AE grid");
    }

    private static void assertVisibleCpuContract(GameTestHelper helper, int maxVisibleCpus) {
        AE2ParallelCpuToolBlockEntity cpuTool = helper.getBlockEntity(CPU_TOOL_POS);
        List<ICraftingCPU> cpus = List.copyOf(cpuTool.getGrid().getCraftingService().getCpus());
        helper.assertTrue(!cpus.isEmpty(), "parallel CPU tool must advertise AE2 CPU entries");
        helper.assertTrue(cpus.size() <= maxVisibleCpus,
                "parallel CPU tool must not expose one CPU per internal lane, cpus=" + cpus.size());
        helper.assertTrue(cpus.stream().anyMatch(cpu -> cpu.getCoProcessors()
                        == ParallelCraftingCpuConfig.DEFAULT_CO_PROCESSORS_PER_VIRTUAL_CPU),
                "parallel CPU tool must advertise an Integer.MAX_VALUE - 1 co-processor fake pool");
    }

    private static void setSelectionMode(
            GameTestHelper helper,
            AE2ParallelCpuToolBlockEntity cpuTool,
            CpuSelectionMode mode
    ) {
        cpuTool.getConfigManager().putSetting(Settings.CPU_SELECTION_MODE, mode);
        cpuTool.refreshParallelCpuProvider();
        helper.assertTrue(cpuTool.getSelectionMode() == mode,
                "parallel CPU tool selection mode must update to " + mode);
    }

    private static void assertMenuShowsParallelFakePool(GameTestHelper helper, CraftingStatusMenu menu) {
        CraftingStatusMenu.CraftingCpuListEntry fakePool = findFakePoolEntry(menu);
        helper.assertTrue(fakePool.storage() >= ParallelCraftingCpuConfig.current().storageBytes(),
                "Crafting Status menu fake pool CPU must expose configured storage bytes");
    }

    private static void assertCpuListFakePoolMode(
            GameTestHelper helper,
            CraftingStatusMenu menu,
            CpuSelectionMode expectedMode
    ) {
        CraftingStatusMenu.CraftingCpuListEntry fakePool = findFakePoolEntry(menu);
        helper.assertTrue(fakePool.mode() == expectedMode,
                "Crafting Status menu fake pool mode must refresh to " + expectedMode);
    }

    private static void assertCpuListFakeAndActiveSummaryModes(
            GameTestHelper helper,
            CraftingStatusMenu menu,
            CpuSelectionMode expectedMode
    ) {
        CraftingStatusMenu.CraftingCpuListEntry fakePool = findFakePoolEntry(menu);
        CraftingStatusMenu.CraftingCpuListEntry activeSummary = findActiveSummaryEntry(menu);
        helper.assertTrue(fakePool.mode() == expectedMode,
                "Crafting Status menu fake pool mode must refresh to " + expectedMode);
        helper.assertTrue(activeSummary.mode() == expectedMode,
                "Crafting Status menu active summary mode must refresh to " + expectedMode);
    }

    private static CraftingStatusMenu.CraftingCpuListEntry findFakePoolEntry(CraftingStatusMenu menu) {
        return menu.cpuList.cpus().stream()
                .filter(cpu -> cpu.coProcessors()
                        == ParallelCraftingCpuConfig.DEFAULT_CO_PROCESSORS_PER_VIRTUAL_CPU)
                .filter(cpu -> cpu.currentJob() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Crafting Status menu must list the parallel fake pool CPU"));
    }

    private static CraftingStatusMenu.CraftingCpuListEntry findActiveSummaryEntry(CraftingStatusMenu menu) {
        return menu.cpuList.cpus().stream()
                .filter(cpu -> cpu.coProcessors()
                        == ParallelCraftingCpuConfig.DEFAULT_CO_PROCESSORS_PER_VIRTUAL_CPU)
                .filter(cpu -> cpu.currentJob() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Crafting Status menu must list the parallel active summary CPU"));
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

    private static void broadcastMenuChangesForMockPlayer(
            GameTestHelper helper,
            CraftingStatusMenu menu,
            int times
    ) {
        for (int update = 0; update < times; update++) {
            broadcastChangesForMockPlayer(helper, menu::broadcastChanges);
        }
    }

    private static CraftConfirmMenu requireCraftConfirmMenu(CraftConfirmMenu[] menuHolder) {
        if (menuHolder == null || menuHolder.length == 0 || menuHolder[0] == null) {
            throw new AssertionError("Craft Confirm menu must be created before player submit coverage runs");
        }
        return menuHolder[0];
    }

    private static void ensureConnected(
            AE2ParallelCpuToolBlockEntity cpuTool,
            CreativeEnergyCellBlockEntity energyCell,
            MEChestBlockEntity meChest
    ) {
        var cpuNode = cpuTool.getMainNode().getNode();
        var energyNode = energyCell.getMainNode().getNode();
        var chestNode = meChest.getMainNode().getNode();
        if (cpuNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(cpuNode) == energyNode)) {
            GridHelper.createConnection(cpuNode, energyNode);
        }
        if (cpuNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(cpuNode) == chestNode)) {
            GridHelper.createConnection(cpuNode, chestNode);
        }
    }

    private static void assertSyntheticLaneScale(GameTestHelper helper, String benchmark, int laneCount) {
        AEItemKey key = AEItemKey.of(Items.IRON_INGOT);
        ParallelCpuWaitingIndex index = new ParallelCpuWaitingIndex();
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();
        SyntheticLane[] lanes = new SyntheticLane[laneCount];
        for (int lane = 0; lane < laneCount; lane++) {
            lanes[lane] = new SyntheticLane(key, 1L);
        }

        index.rebuild(List.of(lanes));
        index.copyMetricsTo(metrics);
        helper.assertValueEqual((long) laneCount, index.getRequestedAmount(key),
                "synthetic lane scale must aggregate requested amount for " + benchmark);
        helper.assertValueEqual(1, index.indexedKeyCount(),
                "synthetic lane scale must keep one waiting-index key for " + benchmark);
        helper.assertValueEqual((long) laneCount, metrics.snapshot().waitingIndexLaneCount(),
                "synthetic lane scale must track all active lanes for " + benchmark);
        helper.assertValueEqual((long) laneCount, index.insertIntoLanes(key, laneCount, Actionable.MODULATE, metrics),
                "synthetic lane scale must route inserts through waiting index for " + benchmark);
        index.copyMetricsTo(metrics);
        helper.assertValueEqual(0L, index.getRequestedAmount(key),
                "synthetic lane scale must drain requested amount for " + benchmark);
        LOGGER.info(
                "IDEA2_PARALLEL_CPU_BENCHMARK benchmark={} syntheticLaneCount={} waitingIndexKeys={} "
                        + "indexedInsertAmount={}",
                benchmark,
                laneCount,
                metrics.snapshot().waitingIndexKeyCount(),
                metrics.snapshot().indexedInsertAmount()
        );
    }

    private static void assertSyntheticAllBusyProviderBackoff(GameTestHelper helper, int laneCount) {
        ParallelCpuProviderBackoff backoff = new ParallelCpuProviderBackoff(2, 40);
        ParallelCpuGridBudgetLedger ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(
                1_048_576L,
                8_388_608L,
                1_048_576L,
                1_048_576L,
                20_000_000L
        ));
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();
        SyntheticBusyProvider provider = new SyntheticBusyProvider();
        ledger.resetForTick(1L, System.nanoTime());

        for (int lane = 0; lane < laneCount; lane++) {
            helper.assertTrue(backoff.checkProvider(provider, 1L, ledger, metrics)
                            != ParallelCpuProviderBackoff.ProviderAvailability.READY,
                    "all-busy provider synthetic burst must never report ready");
            metrics.recordZeroProgressTick();
        }

        ParallelCpuMetrics.Snapshot snapshot = metrics.snapshot();
        helper.assertValueEqual(1L, snapshot.providerScanCount(),
                "all-busy provider stress must collapse scans through same-tick backoff");
        helper.assertValueEqual((long) laneCount, snapshot.zeroProgressTickCount(),
                "all-busy provider stress must record zero progress for every lane");
        LOGGER.info(
                "IDEA2_PARALLEL_CPU_BENCHMARK benchmark=all_busy_{} syntheticLaneCount={} providerScanCount={} "
                        + "busyProviderSkipCount={} zeroProgressTickCount={}",
                laneCount,
                laneCount,
                snapshot.providerScanCount(),
                snapshot.busyProviderSkipCount(),
                snapshot.zeroProgressTickCount()
        );
    }

    private static final class SyntheticLane implements ParallelCraftingLane {
        private final UUID laneId = UUID.randomUUID();
        private final Object2LongOpenHashMap<AEKey> waiting = new Object2LongOpenHashMap<>();

        private SyntheticLane(AEKey key, long amount) {
            waiting.put(key, amount);
        }

        @Override
        public UUID getLaneId() {
            return laneId;
        }

        @Override
        public boolean isLaneActive() {
            return !waiting.isEmpty();
        }

        @Override
        public Iterable<Object2LongMap.Entry<AEKey>> getWaitingStacks() {
            return waiting.object2LongEntrySet();
        }

        @Override
        public long getRequestedAmount(@org.jetbrains.annotations.Nullable AEKey what) {
            return what == null ? 0L : Math.max(0L, waiting.getLong(what));
        }

        @Override
        public long insertIntoWaiting(AEKey what, long amount, Actionable mode) {
            long accepted = Math.min(amount, waiting.getLong(what));
            if (mode == Actionable.MODULATE && accepted > 0L) {
                waiting.addTo(what, -accepted);
                if (waiting.getLong(what) <= 0L) {
                    waiting.removeLong(what);
                }
            }
            return accepted;
        }
    }

    private static final class SyntheticBusyProvider implements ICraftingProvider {
        @Override
        public boolean isBusy() {
            return true;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return false;
        }
    }

    private static final class ParallelSubmitHarness {
        private static final int PLAN_TIMEOUT_SECONDS = 10;

        private final GameTestHelper helper;
        private final AE2ParallelCpuToolBlockEntity cpuTool;
        private final HighCapacityCraftingMachineBlockEntity provider;
        private final MEChestBlockEntity meChest;
        private final GameTestSimulationRequester simulationRequester;
        private final GameTestCraftingRequester requester;
        private ICraftingPlan plan;
        private Future<ICraftingPlan> planFuture;

        private ParallelSubmitHarness(
                GameTestHelper helper,
                AE2ParallelCpuToolBlockEntity cpuTool,
                HighCapacityCraftingMachineBlockEntity provider,
                MEChestBlockEntity meChest,
                GameTestSimulationRequester simulationRequester,
                GameTestCraftingRequester requester
        ) {
            this.helper = helper;
            this.cpuTool = cpuTool;
            this.provider = provider;
            this.meChest = meChest;
            this.simulationRequester = simulationRequester;
            this.requester = requester;
        }

        void assertProviderReady() {
            helper.assertTrue(provider.getMainNode().isReady(), "parallel provider node must be ready");
            ensureProviderConnected();
            helper.assertTrue(provider.getMainNode().isActive(), "parallel provider must be active");
            helper.assertTrue(provider.getGrid() == cpuTool.getGrid(),
                    "parallel provider must join the same AE grid as the CPU tool");
        }

        void installProviderPatterns(List<net.minecraft.world.item.ItemStack> patterns) {
            provider.clearPatternsForTest();
            provider.fillCraftingPatternsForTest(0, patterns);
            provider.forceProviderRefreshForTest();
            provider.resetBenchmarkCountersForTest();
        }

        void seedInputs(List<GenericStack> stacks) {
            var inventory = cpuTool.getGrid().getStorageService().getInventory();
            for (GenericStack stack : stacks) {
                long inserted = inventory.insert(stack.what(), stack.amount(), Actionable.MODULATE,
                        requester.getActionSource());
                helper.assertValueEqual(stack.amount(), inserted, "parallel CPU fixture must seed input " + stack);
            }
        }

        void assertCraftable(AEItemKey output) {
            helper.assertTrue(!cpuTool.getGrid().getCraftingService().getCraftingFor(output).isEmpty(),
                    "parallel CPU fixture provider must expose craftable output " + output);
        }

        void assertStored(AEItemKey input, long amount) {
            long stored = cpuTool.getGrid().getStorageService().getCachedInventory().get(input);
            helper.assertTrue(stored >= amount,
                    "parallel CPU fixture seed input must be visible in AE cache, input="
                            + input + ", stored=" + stored + ", expected=" + amount);
        }

        void assertAnyBufferedIntermediate(List<AEItemKey> intermediates) {
            helper.assertTrue(cpuTool.getParallelCpuCluster().activeLaneCount() > 0,
                    "parallel CPU lane buffer inspection requires an active lane");
            ParallelCpuMetrics.Snapshot metrics = cpuTool.getParallelCpuCluster().metricsSnapshotForTest();
            for (AEItemKey intermediate : intermediates) {
                long buffered = cpuTool.getParallelCpuCluster().storedAmountForTest(intermediate);
                long networkStored = cpuTool.getGrid().getStorageService().getCachedInventory().get(intermediate);
                helper.assertValueEqual(0L, networkStored,
                        "chained intermediate must remain inside the lane buffer instead of returning to AE");
                if (buffered > 0L || cpuTool.getParallelCpuCluster().waitingAmountForTest(intermediate) > 0L) {
                    return;
                }
            }
            if (metrics.pushedPatternCount() > 0L) {
                return;
            }

            StringBuilder details = new StringBuilder("no chained intermediate buffered in lane inventory:");
            details.append(" pushedPatternCount=").append(metrics.pushedPatternCount());
            for (AEItemKey intermediate : intermediates) {
                long buffered = cpuTool.getParallelCpuCluster().storedAmountForTest(intermediate);
                long waiting = cpuTool.getParallelCpuCluster().waitingAmountForTest(intermediate);
                long pending = cpuTool.getParallelCpuCluster().pendingAmountForTest(intermediate);
                long networkStored = cpuTool.getGrid().getStorageService().getCachedInventory().get(intermediate);
                details.append(' ')
                        .append(intermediate)
                        .append("[buffered=")
                        .append(buffered)
                        .append(", waiting=")
                        .append(waiting)
                        .append(", pending=")
                        .append(pending)
                        .append(", network=")
                        .append(networkStored)
                        .append(']');
            }
            helper.assertTrue(false, details.toString());
        }

        String describeOutputProgress(AEItemKey output, List<AEItemKey> intermediates) {
            StringBuilder details = new StringBuilder("accepted=");
            details.append(requester.countAcceptedOutput(output))
                    .append(", lanes=")
                    .append(cpuTool.getParallelCpuCluster().activeLaneCount())
                    .append(", outputInAe=")
                    .append(cpuTool.getGrid().getStorageService().getCachedInventory().get(output));
            for (AEItemKey intermediate : intermediates) {
                details.append(", ")
                        .append(intermediate)
                        .append("[buffered=")
                        .append(cpuTool.getParallelCpuCluster().storedAmountForTest(intermediate))
                        .append(", waiting=")
                        .append(cpuTool.getParallelCpuCluster().waitingAmountForTest(intermediate))
                        .append(", pending=")
                        .append(cpuTool.getParallelCpuCluster().pendingAmountForTest(intermediate))
                        .append(", network=")
                        .append(cpuTool.getGrid().getStorageService().getCachedInventory().get(intermediate))
                        .append(']');
            }
            return details.toString();
        }

        long totalObservableOutput(AEItemKey output) {
            return requester.countAcceptedOutput(output)
                    + cpuTool.getGrid().getStorageService().getCachedInventory().get(output);
        }

        String describeFinalOutputVisibility(AEItemKey output) {
            return "accepted=" + requester.countAcceptedOutput(output)
                    + ", outputInAe=" + cpuTool.getGrid().getStorageService().getCachedInventory().get(output)
                    + ", lanes=" + cpuTool.getParallelCpuCluster().activeLaneCount();
        }

        void beginPlan(AEItemKey output, long amount) {
            plan = null;
            planFuture = cpuTool.getGrid().getCraftingService().beginCraftingCalculation(
                    helper.getLevel(),
                    simulationRequester,
                    output,
                    amount,
                    CalculationStrategy.REPORT_MISSING_ITEMS
            );
        }

        void assertPlanDone() {
            helper.assertTrue(planFuture != null && planFuture.isDone(),
                    "parallel CPU crafting plan future must complete");
            ICraftingPlan resolvedPlan;
            try {
                resolvedPlan = planFuture.get(PLAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("parallel CPU crafting plan future failed", exception);
            }
            helper.assertTrue(resolvedPlan != null && !resolvedPlan.simulation() && resolvedPlan.missingItems().isEmpty(),
                    "parallel CPU crafting plan must be complete and executable, simulation="
                            + (resolvedPlan == null ? "null" : resolvedPlan.simulation())
                            + ", missing="
                            + (resolvedPlan == null ? "null" : resolvedPlan.missingItems())
                            + ", used="
                            + (resolvedPlan == null ? "null" : resolvedPlan.usedItems())
                            + ", final="
                            + (resolvedPlan == null ? "null" : resolvedPlan.finalOutput()));
            plan = resolvedPlan;
        }

        void submitToParallelFakePool() {
            submitToParallelFakePool(requester.getActionSource(), "parallel CPU fake pool submit", 1);
        }

        void submitToParallelFakePool(IActionSource actionSource, String label, int expectedActiveLaneCount) {
            helper.assertTrue(plan != null, "parallel CPU submit must have a resolved plan");
            ICraftingCPU fakePool = List.copyOf(cpuTool.getGrid().getCraftingService().getCpus()).stream()
                    .filter(cpu -> cpu.getCoProcessors()
                            == ParallelCraftingCpuConfig.DEFAULT_CO_PROCESSORS_PER_VIRTUAL_CPU)
                    .filter(cpu -> !cpu.isBusy())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("parallel CPU fake pool must be visible"));
            ICraftingSubmitResult result = cpuTool.getGrid().getCraftingService().submitJob(
                    plan,
                    requester,
                    fakePool,
                    false,
                    actionSource
            );
            helper.assertTrue(result != null && result.successful(),
                    label + " must succeed, result=" + result);
            requester.trackLink(result.link());
            helper.assertValueEqual(expectedActiveLaneCount, cpuTool.getParallelCpuCluster().activeLaneCount(),
                    label + " must allocate the expected internal lane count");
            assertVisibleCpuContract(helper, 2);
        }

        void submitWithAutoSelection() {
            submitWithAutoSelection(requester.getActionSource(), "parallel CPU auto submit", 1);
        }

        void submitWithAutoSelection(IActionSource actionSource, String label, int expectedActiveLaneCount) {
            helper.assertTrue(plan != null, "parallel CPU auto submit must have a resolved plan");
            ICraftingSubmitResult result = cpuTool.getGrid().getCraftingService().submitJob(
                    plan,
                    requester,
                    null,
                    false,
                    actionSource
            );
            helper.assertTrue(result != null && result.successful(),
                    label + " must succeed with null target, result=" + result);
            requester.trackLink(result.link());
            helper.assertValueEqual(expectedActiveLaneCount, cpuTool.getParallelCpuCluster().activeLaneCount(),
                    label + " must allocate the expected internal lane count");
            assertVisibleCpuContract(helper, 2);
        }

        void assertAutoSelectionRejected(IActionSource actionSource, String label) {
            helper.assertTrue(plan != null, "parallel CPU auto rejection must have a resolved plan");
            int lanesBefore = cpuTool.getParallelCpuCluster().activeLaneCount();
            ICraftingSubmitResult result = cpuTool.getGrid().getCraftingService().submitJob(
                    plan,
                    requester,
                    null,
                    false,
                    actionSource
            );
            helper.assertTrue(result == null || !result.successful(),
                    label + " must be rejected by CPU selection mode, result=" + result);
            helper.assertValueEqual(lanesBefore, cpuTool.getParallelCpuCluster().activeLaneCount(),
                    label + " must not allocate an internal lane");
        }

        private void ensureProviderConnected() {
            var cpuNode = cpuTool.getMainNode().getNode();
            var providerNode = provider.getMainNode().getNode();
            var chestNode = meChest.getMainNode().getNode();
            helper.assertTrue(cpuNode != null, "parallel CPU node must exist before provider wiring");
            helper.assertTrue(providerNode != null, "parallel provider node must exist before wiring");
            helper.assertTrue(chestNode != null, "parallel ME chest node must exist before provider wiring");
            if (cpuNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(cpuNode) == providerNode)) {
                GridHelper.createConnection(cpuNode, providerNode);
            }
            if (providerNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(providerNode) == chestNode)) {
                GridHelper.createConnection(providerNode, chestNode);
            }
        }
    }

    private static final class NativeAePatternProviderHarness {
        private static final int PLAN_TIMEOUT_SECONDS = 10;

        private final GameTestHelper helper;
        private final AE2ParallelCpuToolBlockEntity cpuTool;
        private final PatternProviderBlockEntity provider;
        private final MolecularAssemblerBlockEntity assembler;
        private final MEChestBlockEntity meChest;
        private final GameTestSimulationRequester simulationRequester;
        private final GameTestCraftingRequester requester;
        private ICraftingPlan plan;
        private Future<ICraftingPlan> planFuture;

        private NativeAePatternProviderHarness(
                GameTestHelper helper,
                AE2ParallelCpuToolBlockEntity cpuTool,
                PatternProviderBlockEntity provider,
                MolecularAssemblerBlockEntity assembler,
                MEChestBlockEntity meChest,
                GameTestSimulationRequester simulationRequester,
                GameTestCraftingRequester requester
        ) {
            this.helper = helper;
            this.cpuTool = cpuTool;
            this.provider = provider;
            this.assembler = assembler;
            this.meChest = meChest;
            this.simulationRequester = simulationRequester;
            this.requester = requester;
        }

        void assertProviderReady() {
            helper.assertTrue(provider != null, "native AE pattern provider must exist");
            helper.assertTrue(assembler != null, "native AE molecular assembler must exist");
            helper.assertTrue(provider.getMainNode().isReady(), "native AE pattern provider node must be ready");
            helper.assertTrue(assembler.getMainNode().isReady(), "native AE molecular assembler node must be ready");
            ensureProviderConnected();
            helper.assertTrue(provider.getMainNode().isActive(), "native AE pattern provider must be active");
            helper.assertTrue(assembler.getMainNode().isActive(), "native AE molecular assembler must be active");
            helper.assertTrue(provider.getGrid() == cpuTool.getGrid(),
                    "native AE pattern provider must join the same AE grid as the CPU tool");
            helper.assertTrue(assembler.getMainNode().getGrid() == cpuTool.getGrid(),
                    "native AE molecular assembler must join the same AE grid as the CPU tool");
        }

        void installProviderPatterns(List<net.minecraft.world.item.ItemStack> patterns) {
            var patternInventory = provider.getLogic().getPatternInv();
            for (int slot = 0; slot < patternInventory.size(); slot++) {
                patternInventory.setItemDirect(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
            helper.assertTrue(patternInventory.size() >= patterns.size(),
                    "native AE pattern provider must expose enough pattern slots");
            for (int index = 0; index < patterns.size(); index++) {
                patternInventory.setItemDirect(index, patterns.get(index).copyWithCount(1));
            }
            provider.getLogic().updatePatterns();
            provider.saveChanges();
        }

        void seedInputs(List<GenericStack> stacks) {
            var inventory = cpuTool.getGrid().getStorageService().getInventory();
            for (GenericStack stack : stacks) {
                long inserted = inventory.insert(
                        stack.what(),
                        stack.amount(),
                        Actionable.MODULATE,
                        requester.getActionSource()
                );
                helper.assertValueEqual(stack.amount(), inserted,
                        "native AE provider fixture must seed input " + stack);
            }
        }

        void assertCraftable(AEItemKey output) {
            helper.assertTrue(!cpuTool.getGrid().getCraftingService().getCraftingFor(output).isEmpty(),
                    "native AE provider fixture must expose craftable output " + output);
        }

        void assertStored(AEItemKey input, long amount) {
            long stored = cpuTool.getGrid().getStorageService().getCachedInventory().get(input);
            helper.assertTrue(stored >= amount,
                    "native AE provider fixture seed input must be visible in AE cache, input="
                            + input + ", stored=" + stored + ", expected=" + amount);
        }

        void assertAnyBufferedIntermediate(List<AEItemKey> intermediates) {
            helper.assertTrue(cpuTool.getParallelCpuCluster().activeLaneCount() > 0,
                    "native AE provider lane buffer inspection requires an active lane");
            ParallelCpuMetrics.Snapshot metrics = cpuTool.getParallelCpuCluster().metricsSnapshotForTest();
            for (AEItemKey intermediate : intermediates) {
                long buffered = cpuTool.getParallelCpuCluster().storedAmountForTest(intermediate);
                long networkStored = cpuTool.getGrid().getStorageService().getCachedInventory().get(intermediate);
                helper.assertValueEqual(0L, networkStored,
                        "native AE provider chained intermediate must remain inside the lane buffer");
                if (buffered > 0L || cpuTool.getParallelCpuCluster().waitingAmountForTest(intermediate) > 0L) {
                    return;
                }
            }
            if (metrics.pushedPatternCount() > 0L) {
                return;
            }

            StringBuilder details = new StringBuilder("no native AE chained intermediate buffered in lane inventory:");
            details.append(" pushedPatternCount=").append(metrics.pushedPatternCount());
            for (AEItemKey intermediate : intermediates) {
                long buffered = cpuTool.getParallelCpuCluster().storedAmountForTest(intermediate);
                long waiting = cpuTool.getParallelCpuCluster().waitingAmountForTest(intermediate);
                long pending = cpuTool.getParallelCpuCluster().pendingAmountForTest(intermediate);
                long networkStored = cpuTool.getGrid().getStorageService().getCachedInventory().get(intermediate);
                details.append(' ')
                        .append(intermediate)
                        .append("[buffered=")
                        .append(buffered)
                        .append(", waiting=")
                        .append(waiting)
                        .append(", pending=")
                        .append(pending)
                        .append(", network=")
                        .append(networkStored)
                        .append(']');
            }
            helper.assertTrue(false, details.toString());
        }

        void assertNoTransientAeLeak(List<AEItemKey> intermediates) {
            if (cpuTool.getParallelCpuCluster().activeLaneCount() <= 0) {
                return;
            }
            for (AEItemKey intermediate : intermediates) {
                long networkStored = cpuTool.getGrid().getStorageService().getCachedInventory().get(intermediate);
                if (networkStored > 0L) {
                    helper.assertTrue(false,
                            "native AE provider leaked intermediate into AE storage while lane active: "
                                    + intermediate
                                    + "[network="
                                    + networkStored
                                    + ", buffered="
                                    + cpuTool.getParallelCpuCluster().storedAmountForTest(intermediate)
                                    + ", waiting="
                                    + cpuTool.getParallelCpuCluster().waitingAmountForTest(intermediate)
                                    + ", pending="
                                    + cpuTool.getParallelCpuCluster().pendingAmountForTest(intermediate)
                                    + ", providerReturnEmpty="
                                    + provider.getLogic().getReturnInv().isEmpty()
                                    + "]");
                }
            }
        }

        String describeOutputProgress(AEItemKey output, List<AEItemKey> intermediates) {
            StringBuilder details = new StringBuilder("accepted=");
            details.append(requester.countAcceptedOutput(output))
                    .append(", lanes=")
                    .append(cpuTool.getParallelCpuCluster().activeLaneCount())
                    .append(", outputInAe=")
                    .append(cpuTool.getGrid().getStorageService().getCachedInventory().get(output));
            for (AEItemKey intermediate : intermediates) {
                details.append(", ")
                        .append(intermediate)
                        .append("[buffered=")
                        .append(cpuTool.getParallelCpuCluster().storedAmountForTest(intermediate))
                        .append(", waiting=")
                        .append(cpuTool.getParallelCpuCluster().waitingAmountForTest(intermediate))
                        .append(", pending=")
                        .append(cpuTool.getParallelCpuCluster().pendingAmountForTest(intermediate))
                        .append(", network=")
                        .append(cpuTool.getGrid().getStorageService().getCachedInventory().get(intermediate))
                        .append(']');
            }
            return details.toString();
        }

        long totalObservableOutput(AEItemKey output) {
            return requester.countAcceptedOutput(output)
                    + cpuTool.getGrid().getStorageService().getCachedInventory().get(output);
        }

        void beginPlan(AEItemKey output, long amount) {
            plan = null;
            planFuture = cpuTool.getGrid().getCraftingService().beginCraftingCalculation(
                    helper.getLevel(),
                    simulationRequester,
                    output,
                    amount,
                    CalculationStrategy.REPORT_MISSING_ITEMS
            );
        }

        void assertPlanDone() {
            helper.assertTrue(planFuture != null && planFuture.isDone(),
                    "native AE provider crafting plan future must complete");
            ICraftingPlan resolvedPlan;
            try {
                resolvedPlan = planFuture.get(PLAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("native AE provider crafting plan future failed", exception);
            }
            helper.assertTrue(resolvedPlan != null && !resolvedPlan.simulation() && resolvedPlan.missingItems().isEmpty(),
                    "native AE provider crafting plan must be complete and executable, simulation="
                            + (resolvedPlan == null ? "null" : resolvedPlan.simulation())
                            + ", missing="
                            + (resolvedPlan == null ? "null" : resolvedPlan.missingItems())
                            + ", used="
                            + (resolvedPlan == null ? "null" : resolvedPlan.usedItems())
                            + ", final="
                            + (resolvedPlan == null ? "null" : resolvedPlan.finalOutput()));
            plan = resolvedPlan;
        }

        void submitToParallelFakePool() {
            helper.assertTrue(plan != null, "native AE provider submit must have a resolved plan");
            ICraftingCPU fakePool = List.copyOf(cpuTool.getGrid().getCraftingService().getCpus()).stream()
                    .filter(cpu -> cpu.getCoProcessors()
                            == ParallelCraftingCpuConfig.DEFAULT_CO_PROCESSORS_PER_VIRTUAL_CPU)
                    .filter(cpu -> !cpu.isBusy())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("parallel CPU fake pool must be visible"));
            ICraftingSubmitResult result = cpuTool.getGrid().getCraftingService().submitJob(
                    plan,
                    requester,
                    fakePool,
                    false,
                    requester.getActionSource()
            );
            helper.assertTrue(result != null && result.successful(),
                    "native AE provider fake pool submit must succeed, result=" + result);
            requester.trackLink(result.link());
            helper.assertValueEqual(1, cpuTool.getParallelCpuCluster().activeLaneCount(),
                    "native AE provider fake pool submit must allocate one active lane");
            assertVisibleCpuContract(helper, 2);
        }

        private void ensureProviderConnected() {
            var cpuNode = cpuTool.getMainNode().getNode();
            var providerNode = provider.getMainNode().getNode();
            var assemblerNode = assembler.getMainNode().getNode();
            var chestNode = meChest.getMainNode().getNode();
            helper.assertTrue(cpuNode != null, "parallel CPU node must exist before native provider wiring");
            helper.assertTrue(providerNode != null, "native AE pattern provider node must exist before wiring");
            helper.assertTrue(assemblerNode != null, "native AE molecular assembler node must exist before wiring");
            helper.assertTrue(chestNode != null, "parallel ME chest node must exist before native provider wiring");
            if (cpuNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(cpuNode) == providerNode)) {
                GridHelper.createConnection(cpuNode, providerNode);
            }
            if (cpuNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(cpuNode) == assemblerNode)) {
                GridHelper.createConnection(cpuNode, assemblerNode);
            }
            if (providerNode.getConnections().stream()
                    .noneMatch(connection -> connection.getOtherSide(providerNode) == chestNode)) {
                GridHelper.createConnection(providerNode, chestNode);
            }
        }
    }
}
