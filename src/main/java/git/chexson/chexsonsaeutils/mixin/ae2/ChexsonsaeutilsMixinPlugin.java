package git.chexson.chexsonsaeutils.mixin.ae2;

import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ChexsonsaeutilsMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

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
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingStatusMenuParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CPUSelectionListParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftConfirmScreenParallelCpuMixin"
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
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED,
                    "processingPatternReplacementEnabled");
        }
        if (CONTINUATION_ONLY_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED,
                    "craftingContinuationEnabled");
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
        if (AEA_ENHANCED_CRAFTING_STATUS_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED, "enhancedCraftingStatusEnabled");
        }
        return true;
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
