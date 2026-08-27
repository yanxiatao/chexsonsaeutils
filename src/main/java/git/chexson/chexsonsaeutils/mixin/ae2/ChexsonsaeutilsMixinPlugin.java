package git.chexson.chexsonsaeutils.mixin.ae2;

import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ChexsonsaeutilsMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BUILDING_GADGETS2_MOD_ID = "buildinggadgets2";
    private static final String FTB_ULTIMINE_MOD_ID = "ftbultimine";
    private static final String AE2CT_MOD_ID = "ae2ct";

    private static final Set<String> REPLACEMENT_TERMINAL_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.PatternEncodingTermMenuRuleMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.PatternEncodingTermScreenRuleMixin"
    );
    private static final Set<String> REPLACEMENT_RUNTIME_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessReplacementMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeReplacementMixin"
    );
    private static final Set<String> REPLACEMENT_ONLY_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.PatternEncodingTermMenuRuleMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.PatternEncodingTermScreenRuleMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessReplacementMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeReplacementMixin"
    );
    private static final Set<String> CONTINUATION_ONLY_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftConfirmMenuContinuationMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftConfirmScreenContinuationMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceContinuationMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuContinuationMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftingCPUScreenContinuationMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftingStatusTableRendererContinuationMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.AbstractTableRendererAccessor"
    );
    private static final Set<String> CRAFTING_CPU_ACCESSOR_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingLinkAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobTaskProgressAccessor"
    );
    private static final Set<String> CRAFTING_CPU_PUSH_CONTEXT_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicFormalMachineSourceContextMixin"
    );
    private static final Set<String> FORMAL_MACHINE_PLANNING_ENTRY_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceGetProvidersFormalScaledPatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServicePlanningAggregationMixin"
    );
    private static final Set<String> PARALLEL_CPU_ONLY_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationFastPausingMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeInvoker",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessFastAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeFastBatchMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.DyeablePatternCraftingCalculationFastPausingMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingStatusMenuParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CPUSelectionListParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftConfirmScreenParallelCpuMixin"
    );
    private static final Set<String> AEA_DYEABLE_PATTERN_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceDyeablePatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceDyeablePatternAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicDyeablePatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.item.EncodedPatternItemDyeableClientMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsDyeablePatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeDyeablePatternMixin"
    );
    private static final Set<String> AEA_ENHANCED_CRAFTING_STATUS_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingStatusEnhancedStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingStatusEntryEnhancedStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftingCPUScreenEnhancedStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftingStatusTableRendererEnhancedStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingPlanSummaryEnhancedStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingPlanSummaryEntryEnhancedStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftConfirmTableRendererEnhancedStatusMixin"
    );
    private static final Set<String> AEA_BUILDING_GADGETS2_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.buildinggadgets2.TemplateManagerHandlerMixin",
            "git.chexson.chexsonsaeutils.mixin.buildinggadgets2.PacketUpdateTemplateManagerMixin"
    );
    private static final Set<String> AEA_FTB_ULTIMINE_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ftbultimine.RightClickDispatcherMemoryCardMixin"
    );
    private static final Set<String> AE2CT_COMPAT_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2ct.AE2CraftingPlanSummaryCompatMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2ct.AE2CTRecipeHelperCompatMixin"
    );

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (REPLACEMENT_ONLY_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED, "processingPatternReplacementEnabled");
        }
        if (CONTINUATION_ONLY_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED, "craftingContinuationEnabled");
        }
        if (CRAFTING_CPU_ACCESSOR_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED, "craftingContinuationEnabled")
                    || FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED, "dyeablePatternsEnabled")
                    || FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED, "formalMachinePlanningAggregationEnabled")
                    || FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED, "enhancedCraftingStatusEnabled");
        }
        if (CRAFTING_CPU_PUSH_CONTEXT_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED, "formalMachinePlanningAggregationEnabled")
                    || FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED, "enhancedCraftingStatusEnabled");
        }
        if (FORMAL_MACHINE_PLANNING_ENTRY_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED, "formalMachinePlanningAggregationEnabled");
        }
        if (PARALLEL_CPU_ONLY_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED, "parallelCraftingCpuEnabled");
        }
        if (AEA_DYEABLE_PATTERN_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED, "dyeablePatternsEnabled");
        }
        if (AEA_ENHANCED_CRAFTING_STATUS_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED, "enhancedCraftingStatusEnabled");
        }
        if (AEA_BUILDING_GADGETS2_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.BUILDING_GADGETS2_INTEGRATION_ENABLED, "buildingGadgets2IntegrationEnabled")
                    && isLoadedDuringMixinScan(BUILDING_GADGETS2_MOD_ID, mixinClassName);
        }
        if (AEA_FTB_ULTIMINE_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.FTB_ULTIMINE_MEMORY_CARD_ENABLED, "ftbUltimineMemoryCardEnabled")
                    && isLoadedDuringMixinScan(FTB_ULTIMINE_MOD_ID, mixinClassName);
        }
        if (AE2CT_COMPAT_MIXINS.contains(mixinClassName)) {
            return isLoadedDuringMixinScan(AE2CT_MOD_ID, mixinClassName);
        }
        return true;
    }

    private static boolean isLoadedDuringMixinScan(String modId, String mixinClassName) {
        try {
            LoadingModList loadingModList = FMLLoader.getLoadingModList();
            if (loadingModList == null) {
                LOGGER.warn("Skipping mixin {} because loading mod list is unavailable for {}", mixinClassName, modId);
                return false;
            }
            boolean loaded = loadingModList.getModFileById(modId) != null;
            if (!loaded) {
                LOGGER.info("Skipping mixin {} because optional mod {} is not loaded", mixinClassName, modId);
            }
            return loaded;
        } catch (RuntimeException exception) {
            LOGGER.warn("Skipping mixin {} because optional mod {} could not be checked", mixinClassName, modId,
                    exception);
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
    }
}
