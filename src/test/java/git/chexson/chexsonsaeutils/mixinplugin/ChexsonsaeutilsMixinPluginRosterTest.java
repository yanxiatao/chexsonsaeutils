package git.chexson.chexsonsaeutils.mixinplugin;

import com.google.gson.JsonParser;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.mixin.ae2.ChexsonsaeutilsMixinPlugin;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证在册补丁的配置门控及无关配置独立性，通过公开配置接口设置并恢复测试状态。 */
final class ChexsonsaeutilsMixinPluginRosterTest {
    private static final String MIXIN_PACKAGE = "git.chexson.chexsonsaeutils.mixin.";
    private static final String KEY_REPLACEMENT = "processingPatternReplacementEnabled";
    private static final String KEY_CONTINUATION = "craftingContinuationEnabled";
    private static final String KEY_DYEABLE = "dyeablePatternsEnabled";
    private static final String KEY_FORMAL = "formalMachinePlanningAggregationEnabled";
    private static final String KEY_ENHANCED = "enhancedCraftingStatusEnabled";
    private static final String KEY_PARALLEL = "parallelCraftingCpuEnabled";
    private static final String KEY_BUILDING_GADGETS2 = "buildingGadgets2IntegrationEnabled";
    private static final String KEY_FTB_ULTIMINE = "ftbUltimineMemoryCardEnabled";
    private static final String KEY_MATTER_MASS = "matterMassPatternProviderEnabled";

    private static final Map<String, ModConfigSpec.BooleanValue> CONFIG = Map.ofEntries(
            Map.entry(KEY_REPLACEMENT, ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED),
            Map.entry(KEY_CONTINUATION, ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED),
            Map.entry(KEY_DYEABLE, ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED),
            Map.entry(KEY_FORMAL, ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED),
            Map.entry(KEY_ENHANCED, ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED),
            Map.entry(KEY_PARALLEL, ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED),
            Map.entry(KEY_BUILDING_GADGETS2, ChexsonsaeutilsCompatibilityConfig.BUILDING_GADGETS2_INTEGRATION_ENABLED),
            Map.entry(KEY_FTB_ULTIMINE, ChexsonsaeutilsCompatibilityConfig.FTB_ULTIMINE_MEMORY_CARD_ENABLED),
            Map.entry(KEY_MATTER_MASS, ChexsonsaeutilsCompatibilityConfig.MATTER_MASS_PATTERN_PROVIDER_ENABLED));

    private final Map<String, Boolean> previous = new LinkedHashMap<>();
    private final ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

    @BeforeEach
    void saveConfig() {
        assertTrue(ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded());
        CONFIG.forEach((key, value) -> previous.put(key, value.get()));
    }

    @AfterEach
    void restoreConfig() {
        previous.forEach((key, value) -> CONFIG.get(key).set(value));
    }

    private enum Shape { KEY, ANY_OF, ALWAYS, KEY_AND_MOD, MOD_ONLY }
    private record Row(String entry, Shape shape, List<String> keys, String modId) {
        Row(String entry, Shape shape, List<String> keys) { this(entry, shape, keys, null); }
    }
    private static Row ofKey(String entry, String key) {
        return new Row(entry, Shape.KEY, List.of(key));
    }
    private static Row ofAny(String entry, List<String> keys) {
        return new Row(entry, Shape.ANY_OF, keys);
    }
    private static Row ofAlways(String entry) {
        return new Row(entry, Shape.ALWAYS, List.of());
    }
    private static Row ofKeyAndMod(String entry, String key) {
        return new Row(entry, Shape.KEY_AND_MOD, List.of(key));
    }
    private static Row ofModOnly(String entry) {
        return new Row(entry, Shape.MOD_ONLY, List.of());
    }
    private static final List<Row> TABLE = buildTable();

    private static List<Row> buildTable() {
        List<Row> rows = new ArrayList<>();

        for (String entry : List.of(
                "ae2.menu.PatternEncodingTermMenuRuleMixin",
                "ae2.client.gui.PatternEncodingTermScreenRuleMixin",
                "ae2.crafting.PatternDetailsHelperAccessor",
                "ae2.crafting.CraftingCalculationAccessor",
                "ae2.crafting.CraftingTreeProcessReplacementMixin",
                "ae2.crafting.CraftingTreeNodeReplacementMixin")) {
            rows.add(ofKey(entry, KEY_REPLACEMENT));
        }

        for (String entry : List.of(
                "ae2.menu.CraftConfirmMenuContinuationMixin",
                "ae2.client.gui.CraftConfirmScreenContinuationMixin",
                "ae2.crafting.CraftingServiceContinuationMixin",
                "ae2.menu.CraftingCPUMenuContinuationMixin",
                "ae2.client.gui.CraftingCPUScreenContinuationMixin",
                "ae2.client.gui.CraftingStatusTableRendererContinuationMixin",
                "ae2.menu.CraftingCPUMenuAccessor",
                "ae2.client.gui.AbstractTableRendererAccessor")) {
            rows.add(ofKey(entry, KEY_CONTINUATION));
        }

        List<String> cpuAccessorKeys = List.of(
                KEY_CONTINUATION, KEY_DYEABLE, KEY_FORMAL, KEY_ENHANCED);
        for (String entry : List.of(
                "ae2.crafting.CraftingCpuLogicAccessor",
                "ae2.crafting.CraftingLinkAccessor",
                "ae2.crafting.ExecutingCraftingJobAccessor",
                "ae2.crafting.ExecutingCraftingJobTaskProgressAccessor")) {
            rows.add(ofAny(entry, cpuAccessorKeys));
        }

        rows.add(ofAny("ae2.crafting.CraftingCpuLogicHostBatchSourceContextMixin",
                List.of(KEY_FORMAL, KEY_ENHANCED)));

        for (String entry : List.of(
                "ae2.crafting.CraftingServiceAccessor",
                "ae2.crafting.CraftingServiceGetProvidersHostBatchPatternMixin")) {
            rows.add(ofKey(entry, KEY_FORMAL));
        }

        for (String entry : List.of(
                "ae2.crafting.CraftingServiceParallelCpuMixin",
                "ae2.crafting.CraftingTreeNodeInvoker",
                "ae2.crafting.CraftingTreeProcessFastAccessor",
                "ae2.crafting.CraftingTreeNodeFastAccessor",
                "ae2.menu.CraftingCPUMenuParallelCpuMixin",
                "ae2.menu.CraftingStatusMenuParallelCpuMixin",
                "ae2.client.gui.CPUSelectionListParallelCpuMixin",
                "ae2.client.gui.CraftConfirmScreenParallelCpuMixin")) {
            rows.add(ofKey(entry, KEY_PARALLEL));
        }

        rows.add(ofAlways("ae2.crafting.CraftingServiceOrderedPlanningMixin"));

        for (String entry : List.of(
                "ae2.crafting.CraftingCpuLogicRecursivePlanMixin",
                "ae2.item.EncodedPatternItemDyeableClientMixin",
                "ae2.crafting.PatternDetailsColorMixin",
                "ae2.crafting.CraftingTreeProcessAccessor")) {
            rows.add(ofKey(entry, KEY_DYEABLE));
        }

        for (String entry : List.of(
                "ae2.crafting.CraftingStatusEnhancedStatusMixin",
                "ae2.menu.CraftingStatusEntryEnhancedStatusMixin",
                "ae2.client.gui.CraftingCPUScreenEnhancedStatusMixin",
                "ae2.client.gui.CraftingStatusTableRendererEnhancedStatusMixin",
                "ae2.menu.CraftingPlanSummaryEnhancedStatusMixin",
                "ae2.menu.CraftingPlanSummaryEntryEnhancedStatusMixin",
                "ae2.client.gui.CraftConfirmTableRendererEnhancedStatusMixin")) {
            rows.add(ofKey(entry, KEY_ENHANCED));
        }

        for (String entry : List.of(
                "buildinggadgets2.TemplateManagerHandlerMixin",
                "buildinggadgets2.PacketUpdateTemplateManagerMixin")) {
            rows.add(ofKeyAndMod(entry, KEY_BUILDING_GADGETS2));
        }

        rows.add(ofKeyAndMod("ftbultimine.RightClickDispatcherMemoryCardMixin", KEY_FTB_ULTIMINE));

        for (String entry : List.of(
                "ae2.blockentity.IOPortBlockEntityMatterMassMixin",
                "ae2.parts.encoding.PatternEncodingLogicMatterMassMixin")) {
            rows.add(ofKey(entry, KEY_MATTER_MASS));
        }

        for (String entry : List.of(
                "ae2ct.AE2CraftingPlanSummaryCompatMixin",
                "ae2ct.AE2CraftingPlanSummaryJobGuardMixin",
                "ae2ct.AE2CTRecipeHelperCompatMixin")) {
            rows.add(ofModOnly(entry));
        }

        rows.add(new Row("extendedae.InfinityCellSupplyMixin", Shape.MOD_ONLY, List.of(), "extendedae"));
        rows.add(new Row("cell4.InfinityStorageSupplyMixin", Shape.MOD_ONLY, List.of(), "cell4"));

        for (String entry : List.of(
                "neoecoae.ExtremeAssemblerNeoEcoBatchProviderMixin",
                "neoecoae.NeoEcoInfiniteCpuStorageDisplayMixin",
                "neoecoae.NeoEcoNetworkStorageMixin",
                "neoecoae.NeoEcoInventoryHandlerMixin",
                "neoecoae.NeoEcoDriveWatcherMixin",
                "neoecoae.NeoEcoWideCraftingCpuMixin",
                "neoecoae.NeoEcoCraftingBusBatchMixin",
                "neoecoae.NeoEcoExternalCpuOutputMixin",
                "neoecoae.NeoEcoParallelPlanAdmissionMixin",
                "neoecoae.NeoEcoJobOutputAccessor",
                "neoecoae.NeoEcoExactProgressMixin",
                "neoecoae.NeoEcoExactCpuOutputMixin",
                "neoecoae.NeoEcoOwnPlanPreviewMixin")) {
            rows.add(ofModOnly(entry));
        }

        for (String entry : List.of(
                "useless.SmartDoublingPlansFoldSkipMixin",
                "useless.AdvancedAlloyFurnaceBatchHostMixin",
                "useless.MePatternAssemblyBatchHostMixin")) {
            rows.add(ofModOnly(entry));
        }

        for (String entry : List.of(
                "ae2.storage.CreativeCellSupplyMixin",
                "ae2.storage.NetworkExactStorageMixin",
                "ae2.storage.InventoryExactStorageMixin",
                "ae2.storage.DriveExactStorageMixin",
                "ae2.automation.StorageLevelEmitterPartAccessor",
                "ae2.menu.CraftConfirmMenuRemoteAccessor",
                "ae2.menu.CraftConfirmMenuPlanningProgressMixin",
                "ae2.crafting.CraftingTreeNodeNativeLimitQtyBatchMixin",
                "ae2.crafting.CraftingSimulationStateFastResetMixin",
                "ae2.crafting.ChildCraftingSimulationStateFastMixin",
                "ae2.menu.CraftingPlanSummaryProvenanceMixin",
                "ae2.crafting.CraftingCalculationProvenanceMixin",
                "ae2.crafting.NetworkCraftingSimulationStateAccessor",
                "ae2.crafting.CraftingCalculationGridCaptureMixin",
                "ae2.crafting.CraftingSimulationStateHostBatchAccessor",
                "ae2.crafting.CraftingServiceProvidersTickAccessor",
                "ae2.crafting.ElapsedTimeTrackerAccessor",
                "ae2.parts.encoding.PatternEncodingLogicMixin",
                "ae2.client.gui.EditBoxAccessor",
                "ae2.client.gui.SlotGridLayoutMultiPageMixin",
                "minecraft.client.MenuScreensAccessor",
                "minecraft.world.inventory.SlotAccessor")) {
            rows.add(ofAlways(entry));
        }

        return List.copyOf(rows);
    }

    @Test
    void tableCoversRegisteredMixins() throws Exception {
        var entries = new LinkedHashSet<String>();
        for (Row row : TABLE) {
            assertTrue(entries.add(row.entry()), row.entry());
        }
        var roster = new LinkedHashSet<String>();
        var resource = getClass().getResourceAsStream("/chexsonsaeutils.mixins.json");
        assertNotNull(resource);
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            for (String section : List.of("mixins", "client")) {
                for (var entry : json.getAsJsonArray(section)) {
                    assertTrue(roster.add(entry.getAsString()), entry.getAsString());
                }
            }
        }
        assertEquals(roster, entries);
    }

    @Test
    void registeredMixinsFollowTheirConfigGates() {
        for (Row row : TABLE) {
            for (int mask = 0; mask < (1 << row.keys().size()); mask++) {
                var column = new LinkedHashMap<String, Boolean>();
                for (int i = 0; i < row.keys().size(); i++) {
                    column.put(row.keys().get(i), (mask & (1 << i)) != 0);
                }
                boolean expected = switch (row.shape()) {
                    case KEY -> column.get(row.keys().getFirst());
                    case ANY_OF -> column.values().stream().anyMatch(Boolean::booleanValue);
                    case ALWAYS -> true;
                    // 默认单元测试环境未加载这些可选模组。
                    case KEY_AND_MOD -> false;
                    case MOD_ONLY -> row.modId() != null && net.neoforged.fml.ModList.get().isLoaded(row.modId());
                };
                for (boolean otherKeys : List.of(false, true)) {
                    CONFIG.forEach((key, value) -> value.set(column.getOrDefault(key, otherKeys)));
                    assertEquals(expected, plugin.shouldApplyMixin("", MIXIN_PACKAGE + row.entry()),
                            row.entry() + " " + column + " 无关配置=" + otherKeys);
                }
            }
        }
    }
}
