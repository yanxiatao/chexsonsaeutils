package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AE2ParallelCpuToolRegistrationTest {

    @Test
    void parallelCpuToolStaysRegisteredAsExtremeOfficialTool() throws IOException {
        String modSource = readUtf8(javaSource("git/chexson/chexsonsaeutils/Chexsonsaeutils.java"));
        String contentSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/registration/ChexsonsaeutilsContent.java"
        ));
        String configSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/config/ParallelCraftingCpuConfig.java"
        ));
        String mixinPluginSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java"
        ));
        String mixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceParallelCpuMixin.java"
        ));
        String continuationMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java"
        ));
        String gridSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuGrid.java"
        ));
        String blockSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/block/crafting/AE2ParallelCpuToolBlock.java"
        ));
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/AE2ParallelCpuToolBlockEntity.java"
        ));
        String clusterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuCluster.java"
        ));
        String laneSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingLaneState.java"
        ));
        String logicSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuLogic.java"
        ));
        String highCapacityGameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/HighCapacityCraftingMachineGameTests.java"
        ));
        JsonObject enUs = JsonParser.parseString(readUtf8(
                resourcePath("assets/chexsonsaeutils/lang/en_us.json")
        )).getAsJsonObject();
        JsonObject zhCn = JsonParser.parseString(readUtf8(
                resourcePath("assets/chexsonsaeutils/lang/zh_cn.json")
        )).getAsJsonObject();

        assertTrue(modSource.contains("AE2_PARALLEL_CPU_TOOL_BLOCK"),
                "main mod class must expose the official parallel CPU tool block");
        String gameTestRegistrationSlice = methodSlice(
                modSource,
                "private void onRegisterGameTests(RegisterGameTestsEvent event)",
                "private void onRegisterCapabilities(RegisterCapabilitiesEvent event)"
        );
        assertTrue(gameTestRegistrationSlice.contains("Boolean.getBoolean(\"chexsonsaeutils.parallelCpuGameTests\")"),
                "parallel CPU GameTests must stay disabled unless the dedicated system property is enabled");
        assertTrue(gameTestRegistrationSlice.contains("event.register(AE2ParallelCpuToolGameTests.class);"),
                "parallel CPU GameTests must be registered only inside the dedicated gate");
        assertTrue(contentSource.contains("ae2_parallel_cpu_tool"),
                "content registry must register ae2_parallel_cpu_tool");
        assertTrue(contentSource.contains("output.accept(AE2_PARALLEL_CPU_TOOL_ITEM.get())"),
                "official tool must appear in the creative tab");
        assertTrue(contentSource.contains("AECapabilities.IN_WORLD_GRID_NODE_HOST")
                        && contentSource.contains("AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY.get()"),
                "official tool block entity must expose AE2 grid node host capability");
        assertTrue(blockSource.contains("extends AEBaseEntityBlock<AE2ParallelCpuToolBlockEntity>"),
                "official tool block must stay an independent AE network block");
        assertTrue(blockSource.contains("ParallelCraftingCPUMenu.TYPE"),
                "official tool right-click path must open the configurable parallel CPU menu");
        assertTrue(blockSource.contains("craftingCpuMenuTypeForServerPath"),
                "official tool right-click path must use the parallel CPU menu server entry");
        assertTrue(blockSource.contains("createCraftingCpuMenuForServerPath"),
                "GameTest must be able to verify the same parallel CPU menu host contract as right-click");
        assertTrue(blockSource.contains("MenuLocators.forBlockEntity(blockEntity)"),
                "official tool must locate its block entity as the parallel CPU menu host");
        assertTrue(blockSource.contains("neighborChanged"),
                "neighbor network changes must refresh the parallel CPU provider");
        assertTrue(blockSource.contains("refreshParallelCpuProvider"),
                "neighbor network changes must post the parallel CPU list refresh");
        assertTrue(blockSource.contains("blockEntity.getMainNode().getNode() == null || blockEntity.getGrid() == null"),
                "right-click menu entry must only require a live grid node, not a fully active CPU provider");
        assertTrue(blockEntitySource.contains("extends AENetworkedBlockEntity implements ITerminalHost"),
                "official tool block entity must remain independent and host Crafting Status");
        assertTrue(blockEntitySource.contains("new SupplierStorage"),
                "terminal inventory must follow the current grid inventory instead of capturing stale storage");
        assertTrue(blockEntitySource.contains("ILinkStatus.ofManagedNode(getMainNode())"),
                "Crafting Status must expose the AE2 link status for the tool node");
        assertTrue(blockEntitySource.contains("return configManager;"),
                "official tool must expose its real CPU selection config manager");
        assertTrue(blockEntitySource.contains("player.closeContainer()"),
                "parallel CPU tool has no separate main menu and must close when asked to return");
        assertTrue(blockEntitySource.contains("onMainNodeStateChanged"),
                "CPU list changes must be posted when the AE2 node becomes active or inactive");
        assertTrue(configSource.contains("Integer.MAX_VALUE - 1"),
                "coProcessors must default to Integer.MAX_VALUE - 1");
        assertTrue(configSource.contains("DEFAULT_MAX_INTERNAL_LANES_PER_GRID = 1_048_576"),
                "extreme lane ceiling must stay at one million lanes per grid");
        assertTrue(configSource.contains("MAX_TICK_BUDGET_NANOS_PER_GRID = 45_000_000L"),
                "tick budget hard cap must stay explicit");
        assertTrue(mixinPluginSource.contains("PARALLEL_CPU_ONLY_MIXINS"),
                "parallel CPU mixin must have an independent gate");
        assertTrue(mixinPluginSource.contains("ParallelCraftingCpuFeatureGate.isEnabledAtStartup()"),
                "parallel CPU mixin must not reuse continuation or formal machine gates");
        assertTrue(mixinPluginSource.contains("CraftingCpuLogicFormalMachineSourceContextMixin"),
                "formal-machine source-context mixin must stay behind the formal-machine feature gate");
        assertTrue(mixinSource.contains("if (!(target instanceof ParallelCraftingCPU))"),
                "native AE2 CPU targets must be passed through unchanged");
        assertTrue(gridSource.contains("CraftingSubmitResult.CPU_OFFLINE"),
                "stale or offline parallel CPU targets must not fall back to native AE2 CPU auto-selection");
        assertTrue(!mixinSource.contains("craftingCPUClusters.add"),
                "parallel CPU tool must not insert itself into AE2's native CPU cluster set");
        assertTrue(gridSource.contains("submitToAutoSelectedCluster"),
                "null auto-selection must route to the remaining-capacity CPU when it has capacity");
        assertTrue(continuationMixinSource.contains("target instanceof ParallelCraftingCPU"),
                "continuation IGNORE_MISSING must not steal parallel CPU jobs into native CPU auto-selection");
        assertTrue(continuationMixinSource.contains("target == null && chexsonsaeutils$hasAutoSelectableParallelCpu(src)"),
                "continuation IGNORE_MISSING must guard null-target parallel auto-selection from native CPU fallback");
        assertTrue(continuationMixinSource.contains("CraftingSubmitResult.INCOMPLETE_PLAN"),
                "parallel auto-selection with an incomplete plan must fail explicitly instead of selecting native CPU");
        assertTrue(mixinSource.contains("private final Set<ParallelCraftingCpuCluster> chexsonsaeutils$parallelCpuClusters"),
                "CraftingService mixin must own a stable parallel CPU cluster registry");
        assertTrue(mixinSource.contains("chexsonsaeutils$registerFormalMachineSubmitResult(")
                        && mixinSource.contains("FormalMachineCraftingDispatchService.onSubmitJobTail("),
                "parallel CPU explicit and auto-selected fake-pool submits must preserve formal submit-tail registration");
        assertTrue(mixinSource.contains("ImmutableSet.Builder<ICraftingCPU> cpus"),
                "getCpus must inject directly into AE2's ImmutableSet builder");
        assertTrue(mixinSource.contains("cluster.appendVisibleCpus("),
                "getCpus must append stable remaining-capacity and active-vcpu identities from the service registry");
        assertTrue(mixinSource.contains("grid.getMachines(AE2ParallelCpuToolBlockEntity.class)"),
                "parallel cluster discovery must rebuild from CraftingService.updateCPUClusters");
        assertTrue(gridSource.contains("public void setClusters(Collection<ParallelCraftingCpuCluster> nextClusters)"),
                "parallel CPU grid must accept a service-owned cluster snapshot");
        assertFalse(gridSource.contains("appendCpus"),
                "parallel CPU grid must not own AE-visible CPU list construction");
        assertFalse(gridSource.contains("refreshClusters"),
                "parallel CPU grid must not own runtime provider discovery anymore");
        assertFalse(gridSource.contains("grid.getMachines(AE2ParallelCpuToolBlockEntity.class)"),
                "parallel CPU grid must not scan the AE grid for provider discovery");
        assertTrue(gridSource.contains("activeLaneCount()"),
                "internal lanes must not be exposed as one CPU each");
        assertTrue(clusterSource.contains("builder.add(lane.activeCpu())"),
                "active lanes must expose their own active vCPU identities");
        assertTrue(clusterSource.contains("builder.add(remainingCapacityCpu)"),
                "each tool block must expose one remaining-capacity CPU while it has submission capacity");
        assertTrue(clusterSource.contains("public boolean isCraftActive(")
                        && clusterSource.contains("public ParallelCraftingLane findLaneByCraftingId(")
                        && clusterSource.contains("public long getRequestedAmountForCraft(")
                        && clusterSource.contains("public long insertIntoWaitingForCraft(")
                        && clusterSource.contains("public UUID findCraftingIdForPlan("),
                "parallel CPU cluster must expose craft-id based source CPU handle hooks");
        assertTrue(laneSource.contains("boolean matchesPlan(@Nullable ICraftingPlan plan)")
                        && laneSource.contains("public long getRequestedAmount(@Nullable AEKey what)")
                        && laneSource.contains("long waitingAmountForTest(@Nullable AEKey what)")
                        && laneSource.contains("public long insertIntoWaiting(AEKey what, long amount, Actionable mode)"),
                "parallel CPU lane must expose exact-craft waiting lookup and insert hooks");
        String gameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/AE2ParallelCpuToolGameTests.java"
        ));
        assertTrue(gameTestSource.contains("new CraftConfirmMenu(2, player.getInventory(), meChest)"),
                "Craft Confirm GameTest must use the ME chest as the AE2-native terminal host");
        assertTrue(gameTestSource.contains("new CraftingStatusMenu(5, player.getInventory(), harness.meChest)"),
                "Crafting Status GameTest must use the ME chest as the AE2-native terminal host");
        assertFalse(gameTestSource.contains("new CraftConfirmMenu(2, player.getInventory(), cpuTool)"),
                "Craft Confirm GameTest must not use the parallel CPU tool as the AE terminal host");
        assertFalse(gameTestSource.contains("new CraftingStatusMenu(5, player.getInventory(), harness.cpuTool)"),
                "Crafting Status GameTest must not use the parallel CPU tool as the AE terminal host");
        assertTrue(gameTestSource.contains("parallelCpuToolCraftConfirmMenuPlayerStartJobSubmitsToParallelCpu"),
                "GameTests must cover player startJob through the real Craft Confirm menu path");
        assertTrue(gameTestSource.contains(
                        "parallelCpuToolCraftConfirmMenuNativeAeProviderBuffersIntermediatesInsideLaneInventory"),
                "GameTests must cover player startJob through the native AE pattern provider path");
        assertTrue(gameTestSource.contains("menu.planJob(output, 1, CalculationStrategy.REPORT_MISSING_ITEMS)"),
                "player startJob coverage must resolve a real Craft Confirm plan before submission");
        assertTrue(gameTestSource.contains("menu.planJob(output, 4, CalculationStrategy.REPORT_MISSING_ITEMS)"),
                "native AE provider player startJob coverage must resolve a real Craft Confirm plan before submission");
        assertTrue(gameTestSource.contains("runMenuActionForMockPlayer(helper, menu::startJob, \"startJob\")"),
                "player startJob coverage must submit through CraftConfirmMenu.startJob");
        assertTrue(gameTestSource.contains("runMenuActionForMockPlayer(helper, menu::startJob, \"nativeAeProviderStartJob\")"),
                "native AE provider player startJob coverage must submit through CraftConfirmMenu.startJob");
        assertTrue(gameTestSource.contains("parallelCpuToolOpensDedicatedCpuMenu"),
                "GameTest must cover the right-click menu host contract");
        assertTrue(gameTestSource.contains("ParallelCraftingCPUMenu.TYPE"),
                "GameTest must cover the parallel crafting CPU menu type");
        assertTrue(gameTestSource.contains("parallelCpuToolDoesNotJoinNativeCraftingCpuMultiblock"),
                "GameTest must cover the native AE2 CPU multiblock negative path");
        assertTrue(logicSource.contains("long[] usedOps"),
                "lane execution must use long smoothing state");
        assertTrue(logicSource.contains("(long) lane.cluster().advertisedCoProcessors() + 1L"),
                "coProcessors + 1 must be evaluated as long to avoid int overflow");
        assertFalse(highCapacityGameTestSource.contains("Boolean.getBoolean(\"chexsonsaeutils.formalMachineMegaCellsGameTest\")"),
                "Mega Cells compatibility GameTests must run when the runtime mod is present");
        assertTrue(enUs.has("block.chexsonsaeutils.ae2_parallel_cpu_tool"),
                "English language file must name the official tool");
        assertTrue(zhCn.has("block.chexsonsaeutils.ae2_parallel_cpu_tool"),
                "Chinese language file must name the official tool");
        String blockstateSource = readUtf8(resourcePath("assets/chexsonsaeutils/blockstates/ae2_parallel_cpu_tool.json"));
        assertTrue(blockstateSource.contains("\"\"")
                        && blockstateSource.contains("chexsonsaeutils:block/ae2_parallel_cpu_tool"),
                "blockstate must point at the parallel CPU block model");
        assertTrue(readUtf8(resourcePath("data/chexsonsaeutils/recipe/ae2_parallel_cpu_tool.json"))
                        .contains("ae2:crafting_accelerator"),
                "recipe must require AE2 crafting accelerator parts");
        assertTrue(readUtf8(resourcePath("data/chexsonsaeutils/recipe/high_capacity_crafting_machine.json"))
                        .contains("ae2:blank_pattern"),
                "formal machine recipe must use a registered AE2 item in 1.21 runtime loading");
        assertTrue(readUtf8(resourcePath("data/chexsonsaeutils/recipe/network/parts/multi_level_emitter.json"))
                        .contains("\"id\": \"chexsonsaeutils:multi_level_emitter\""),
                "1.21 shapeless recipe result must use the id field, not the legacy item field");
        assertTrue(readUtf8(resourcePath("data/chexsonsaeutils/advancement/recipes/ae2_parallel_cpu_tool.json"))
                        .contains("chexsonsaeutils:ae2_parallel_cpu_tool"),
                "recipe advancement must be present in the 1.21 singular advancement path");
        assertTrue(readUtf8(resourcePath("data/chexsonsaeutils/loot_table/blocks/ae2_parallel_cpu_tool.json"))
                        .contains("chexsonsaeutils:ae2_parallel_cpu_tool"),
                "loot table must self-drop the official tool");
    }

    @Test
    void parallelCpuToolSelectionModeUsesStableConfigManagerContract() throws IOException {
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/AE2ParallelCpuToolBlockEntity.java"
        ));
        String clusterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuCluster.java"
        ));
        String cpuSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCPU.java"
        ));

        assertEquals("crafting_scheduling_mode", Settings.CPU_SELECTION_MODE.getName(),
                "AE2 CPU selection mode must keep the top-level NBT key");
        assertTrue(blockEntitySource.contains("IConfigManager.builder(this::onConfigChanged)"),
                "parallel CPU tool must build a real config manager");
        assertTrue(blockEntitySource.contains(".registerSetting(Settings.CPU_SELECTION_MODE, CpuSelectionMode.ANY)"),
                "parallel CPU tool config must register CPU_SELECTION_MODE with ANY as default");
        assertTrue(blockEntitySource.contains("return configManager.getSetting(Settings.CPU_SELECTION_MODE);"),
                "block entity selection mode must read the registered CPU selection setting");
        assertTrue(blockEntitySource.contains("public IConfigManager getConfigManager()"),
                "parallel CPU tool must expose the config manager through ITerminalHost");
        assertTrue(blockEntitySource.contains("return configManager;"),
                "parallel CPU tool must expose the registered config manager, not a null manager");
        assertTrue(!blockEntitySource.contains("NullConfigManager.INSTANCE"),
                "parallel CPU tool must not hide native CPU selection configuration behind NullConfigManager");
        assertTrue(blockEntitySource.contains("configManager.writeToNBT(data, registries);"),
                "CPU selection mode must save through ConfigManager into the block entity top-level NBT tag");
        assertTrue(blockEntitySource.contains("configManager.readFromNBT(data, registries);"),
                "CPU selection mode must load through ConfigManager from the block entity top-level NBT tag");
        assertTrue(clusterSource.contains("return owner.getSelectionMode();"),
                "parallel CPU cluster selection mode must delegate to the block entity configuration");
        assertTrue(cpuSource.contains("return cluster.getSelectionMode();"),
                "ParallelCraftingCPU.getSelectionMode must delegate to the configured cluster");
    }

    @Test
    void parallelCpuToolMenuFacadeApiStaysCoveredByContract() throws IOException {
        String contentSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/registration/ChexsonsaeutilsContent.java"
        ));
        String mixinPluginSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java"
        ));
        String mixinConfigSource = readUtf8(resourcePath("chexsonsaeutils.mixins.json"));
        String menuSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/menu/implementations/ParallelCraftingCPUMenu.java"
        ));
        String menuMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftingCPUMenuParallelCpuMixin.java"
        ));
        String cpuSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCPU.java"
        ));
        String clusterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuCluster.java"
        ));
        String gameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/AE2ParallelCpuToolGameTests.java"
        ));

        assertTrue(contentSource.contains("AE2_PARALLEL_CPU_TOOL_CPU_MENU")
                        && contentSource.contains("ParallelCraftingCPUMenu.TYPE"),
                "parallel CPU menu type must stay registered through the content registry");
        assertTrue(contentSource.contains("ParallelCraftingCPUScreen"),
                "parallel CPU menu must bind to the dedicated quantum-style crafting CPU screen");
        assertTrue(mixinPluginSource.contains("CraftingCPUMenuParallelCpuMixin"),
                "parallel CPU menu mixin must stay controlled by the parallel CPU feature gate");
        assertTrue(mixinConfigSource.contains("ae2.menu.CraftingCPUMenuParallelCpuMixin"),
                "parallel CPU menu mixin must stay declared in the mixin config");
        assertTrue(menuSource.contains("extends CraftingCPUMenu"),
                "parallel CPU menu must stay compatible with AE2's Crafting CPU screen contract");
        assertTrue(menuSource.contains("refreshCpuList()"),
                "parallel CPU menu must rebuild its visible CPU list from the host cluster");
        assertTrue(menuSource.contains("selectDefaultCpuIfNeeded()"),
                "parallel CPU menu must default-select a busy active vCPU or the remaining-capacity CPU");
        assertTrue(menuSource.contains("public boolean allowConfiguration()"),
                "parallel CPU menu must explicitly define native configuration visibility");
        assertTrue(menuSource.contains("return false;"),
                "parallel CPU menu must disable the native configuration button and rely on the custom quantum toolbar");
        assertTrue(menuSource.contains("@GuiSync(15)")
                        && menuSource.contains("@GuiSync(16)")
                        && menuSource.contains("@GuiSync(17)"),
                "parallel CPU menu must sync cpuList, selectedCpuSerial and selectionMode to the client");
        assertTrue(menuSource.contains("registerClientAction(ACTION_SELECT_CPU, Integer.class, this::selectCpu);"),
                "parallel CPU menu must expose the selectCpu client action");
        assertTrue(menuMixinSource.contains("parallelCpu.getSelectionMode()"),
                "parallel CPU menu mixin must mirror the facade selection mode");
        assertTrue(menuMixinSource.contains("parallelCpu.isCantStoreItems()"),
                "parallel CPU menu mixin must mirror facade item-storage warnings");
        assertTrue(menuMixinSource.contains("parallelCpu.createMenuStatus()"),
                "parallel CPU menu mixin must send facade-backed Crafting Status updates");
        assertTrue(menuMixinSource.contains("CraftingStatus.EMPTY"),
                "parallel CPU menu mixin must clear stale client status when a parallel CPU is deselected");
        assertTrue(menuMixinSource.contains("chexsonsaeutils$sendStatus((CraftingCPUMenu) (Object) this, CraftingStatus.EMPTY);"),
                "parallel CPU menu mixin must also clear stale client status before switching to an idle native CPU");
        assertTrue(menuMixinSource.contains("chexsonsaeutils$lastParallelStatusSignature"),
                "parallel CPU menu mixin must not send a full parallel status packet every broadcast tick");
        assertTrue(menuMixinSource.contains("signature == this.chexsonsaeutils$lastParallelStatusSignature"),
                "parallel CPU menu mixin must gate status packets by facade status changes");
        assertTrue(menuMixinSource.contains("setSuspended(!this.chexsonsaeutils$parallelCpu.isSuspended())"),
                "parallel CPU menu mixin must route suspend toggles to the facade");
        assertTrue(menuMixinSource.contains("UnsupportedOperationException ignored"),
                "parallel CPU menu mixin must tolerate GameTest mock packet connections");
        assertTrue(cpuSource.contains("public CraftingStatus createMenuStatus()"),
                "parallel CPU facade must expose Crafting Status data to the menu mixin");
        assertTrue(cpuSource.contains("public boolean isSuspended()"),
                "parallel CPU facade must expose suspend state to the menu mixin");
        assertTrue(cpuSource.contains("public void setSuspended(boolean suspended)"),
                "parallel CPU facade must expose suspend toggles to the menu mixin");
        assertTrue(cpuSource.contains("public boolean isCantStoreItems()"),
                "parallel CPU facade must expose storage warnings to the menu mixin");
        assertFalse(clusterSource.contains("public ParallelCraftingCPU menuCpu()"),
                "parallel CPU cluster must not keep the old lead-lane menuCpu compatibility helper");
        assertTrue(clusterSource.contains("public CraftingStatus createMenuStatus(ParallelCraftingLaneState lane)"),
                "cluster must build Crafting Status payloads from a concrete active lane");
        assertTrue(gameTestSource.contains("createCraftingCpuMenuForServerPath"),
                "GameTest must cover the dedicated parallel CPU menu factory");
        assertTrue(gameTestSource.contains("ParallelCraftingCPUMenu.TYPE"),
                "GameTest must verify the dedicated parallel CPU menu type");
        assertTrue(gameTestSource.contains("parallelCpuToolOpensDedicatedCpuMenu"),
                "GameTest must cover the dedicated quantum-style parallel CPU menu host contract");
    }

    @Test
    void parallelCpuToolLargeByteDisplayFixStaysCoveredByContract() throws IOException {
        String mixinPluginSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java"
        ));
        String mixinConfigSource = readUtf8(resourcePath("chexsonsaeutils.mixins.json"));
        String formatterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/gui/Ae2ByteDisplayFormatter.java"
        ));
        String compactFormatterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/gui/Ae2CompactNumberFormatter.java"
        ));
        String cpuListMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/client/gui/CPUSelectionListParallelCpuMixin.java"
        ));
        String confirmMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/client/gui/CraftConfirmScreenParallelCpuMixin.java"
        ));
        String cpuSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCPU.java"
        ));

        assertTrue(mixinPluginSource.contains("CPUSelectionListParallelCpuMixin"),
                "parallel CPU display fix must stay controlled by the parallel CPU feature gate");
        assertTrue(mixinPluginSource.contains("CraftConfirmScreenParallelCpuMixin"),
                "Craft Confirm display fix must stay controlled by the parallel CPU feature gate");
        assertTrue(mixinConfigSource.contains("ae2.client.gui.CPUSelectionListParallelCpuMixin"),
                "CPUSelectionList display fix must stay declared in the client mixin config");
        assertTrue(mixinConfigSource.contains("ae2.client.gui.CraftConfirmScreenParallelCpuMixin"),
                "Craft Confirm display fix must stay declared in the client mixin config");
        assertTrue(formatterSource.contains("BYTE_UNITS = { \"k\", \"M\", \"G\", \"T\", \"P\", \"E\" }"),
                "byte formatter must support the full k-to-E display range");
        assertTrue(formatterSource.contains("scaled >= 1000.0d"),
                "byte formatter must continue scaling large values past AE2's native tooltip limit");
        assertTrue(compactFormatterSource.contains("UNITS = { \"k\", \"M\", \"G\", \"T\", \"P\", \"E\" }"),
                "compact processor formatter must support the full k-to-E display range");
        assertTrue(compactFormatterSource.contains("scaled >= 1000.0d"),
                "compact processor formatter must scale extremely large co-processor counts");
        assertTrue(cpuListMixinSource.contains("Ae2ByteDisplayFormatter.format(cpu.storage())"),
                "CPU list row text must use the shared byte formatter");
        assertTrue(cpuListMixinSource.contains("Ae2ByteDisplayFormatter.component(cpu.storage())"),
                "CPU list tooltip storage text must use the shared byte formatter");
        assertTrue(cpuListMixinSource.contains("Ae2CompactNumberFormatter.format(coProcessors)"),
                "CPU list row text must compact long co-processor counts");
        assertFalse(cpuListMixinSource.contains("String.valueOf(cpu.coProcessors())"),
                "CPU list row text must not render raw co-processor integers anymore");
        assertFalse(cpuListMixinSource.contains("Tooltips.ofBytes(cpu.storage())"),
                "CPU list tooltip must not call AE2's unsafe byte formatter for large CPUs");
        assertTrue(confirmMixinSource.contains("Ae2ByteDisplayFormatter.component(getMenu().getCpuAvailableBytes())"),
                "Craft Confirm CPU status must use the shared byte formatter");
        assertTrue(confirmMixinSource.contains("Ae2CompactNumberFormatter.component(getMenu().getCpuCoProcessors())"),
                "Craft Confirm CPU status must compact large co-processor counts");
        assertTrue(cpuSource.contains("return cluster.storageBytes();"),
                "parallel CPU facade must keep exposing real storage bytes to AE2 scheduling");
    }

    @Test
    void parallelCpuToolMustNotJoinNativeCraftingCpuMultiblock() throws IOException {
        String contentSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/registration/ChexsonsaeutilsContent.java"
        ));
        String blockSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/block/crafting/AE2ParallelCpuToolBlock.java"
        ));
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/AE2ParallelCpuToolBlockEntity.java"
        ));
        String gridSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuGrid.java"
        ));
        String cpuSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCPU.java"
        ));
        String mixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceParallelCpuMixin.java"
        ));
        String laneSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingLaneState.java"
        ));
        String logicSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuLogic.java"
        ));
        String gameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/AE2ParallelCpuToolGameTests.java"
        ));

        assertTrue(blockSource.contains("extends AEBaseEntityBlock<AE2ParallelCpuToolBlockEntity>"),
                "parallel CPU tool must stay an independent AE network block");
        assertTrue(blockEntitySource.contains("extends AENetworkedBlockEntity implements ITerminalHost"),
                "parallel CPU tool must stay an independent AE network block entity");
        assertTrue(contentSource.contains("AE2ParallelCpuToolBlockEntity::new"),
                "parallel CPU tool block entity registration must use its own block entity factory");
        assertTrue(cpuSource.contains("implements ICraftingCPU"),
                "parallel CPU facade should only implement AE2's crafting CPU API");
        assertTrue(mixinSource.contains("private final Set<ParallelCraftingCpuCluster> chexsonsaeutils$parallelCpuClusters"),
                "CraftingService mixin must own the stable parallel cluster registry");
        assertTrue(mixinSource.contains("grid.getMachines(AE2ParallelCpuToolBlockEntity.class)"),
                "CraftingService updateCPUClusters must rebuild parallel providers from the grid machine view");
        assertTrue(mixinSource.contains("this.updateList = true;"),
                "CraftingService addNode/removeNode must only dirty the CPU list for later rebuild");
        assertTrue(blockEntitySource.contains("isParallelCpuProviderActive"),
                "parallel CPU tool must keep a dedicated provider discovery predicate");
        assertTrue(blockEntitySource.contains("canProcessParallelCpuJobs"),
                "parallel CPU tool must separate provider discovery from active job execution");
        assertTrue(logicSource.contains("return CraftingSubmitResult.successful(null);"),
                "player-sourced parallel CPU submissions must use native successful(null) semantics");
        assertFalse(logicSource.contains("StandaloneSubmitLink"),
                "player-sourced parallel CPU submissions must not keep the removed standalone link path");
        assertTrue(laneSource.contains("ICraftingLink getLastLink()"),
                "standalone submissions must retain the internal CPU link for runtime tracking");
        assertTrue(laneSource.contains("appendServiceLinks(Collection<CraftingLink> target)"),
                "active parallel lanes must expose their internal CPU links back to CraftingService");
        assertTrue(gameTestSource.contains("broadcastChangesForMockPlayer"),
                "GameTests must not crash mock players by sending AE2 menu payloads directly");
        assertTrue(gameTestSource.contains("runMenuActionForMockPlayer(helper, menu::startJob, \"startJob\")"),
                "GameTests must tolerate mock-player packet rejection on Craft Confirm startJob");
        assertTrue(mixinSource.contains("if (!(target instanceof ParallelCraftingCPU))"),
                "native AE2 CPU targets must be passed through unchanged");

        assertTrue(!blockSource.contains("AbstractCraftingUnitBlock"),
                "parallel CPU tool must not inherit AE2's native crafting unit block");
        assertTrue(!blockSource.contains("FORMED") && !blockSource.contains("POWERED"),
                "parallel CPU tool must not declare native crafting unit block states");
        assertTrue(!blockEntitySource.contains("CraftingBlockEntity"),
                "parallel CPU tool must not inherit AE2's native crafting block entity");
        assertTrue(!blockEntitySource.contains("IAEMultiBlock"),
                "parallel CPU tool must not implement AE2's native multiblock interface");
        assertTrue(!blockEntitySource.contains("CraftingCPUCalculator"),
                "parallel CPU tool must not invoke AE2's native crafting CPU calculator");
        assertTrue(!cpuSource.contains("extends CraftingCPUCluster"),
                "parallel CPU facade must not masquerade as a native AE2 CPU cluster");
        assertTrue(!mixinSource.contains("craftingCPUClusters.add"),
                "parallel facade clusters must never be inserted into AE2's native CPU cluster set");
        assertFalse(gridSource.contains("registeredOwners"),
                "parallel grid must not keep its own provider registry anymore");
        assertFalse(gridSource.contains("grid.getMachines(AE2ParallelCpuToolBlockEntity.class)"),
                "parallel grid must not perform provider discovery");
    }

    private static String methodSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "source must contain marker " + startMarker);
        assertTrue(end > start, "source must contain marker " + endMarker + " after " + startMarker);
        return source.substring(start, end);
    }
}
