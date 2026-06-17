package git.chexson.chexsonsaeutils.mixin.ae2;

import git.chexson.chexsonsaeutils.config.ContinuationFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachineCraftingDispatchFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachinePlanningAggregationFeatureGate;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuFeatureGate;
import git.chexson.chexsonsaeutils.config.ProcessingPatternReplacementFeatureGate;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ChexsonsaeutilsMixinPlugin implements IMixinConfigPlugin {

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
    private static final Set<String> FORMAL_MACHINE_ONLY_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceFormalMachineMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicFormalMachineSourceContextMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCPUClusterFormalMachineStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingStatusFormalMachineMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuFormalMachineHeartbeatMixin"
    );
    private static final Set<String> FORMAL_MACHINE_PLANNING_ENTRY_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServicePlanningAggregationMixin"
    );
    private static final Set<String> FORMAL_MACHINE_PLANNING_SCALED_PATTERN_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicFormalMachineScaledPatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobFormalMachineScaledPatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceGetProvidersFormalScaledPatternMixin"
    );
    private static final Set<String> PARALLEL_CPU_ONLY_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingStatusMenuParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CPUSelectionListParallelCpuMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftConfirmScreenParallelCpuMixin"
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
            return ProcessingPatternReplacementFeatureGate.isEnabledAtStartup();
        }
        if (CONTINUATION_ONLY_MIXINS.contains(mixinClassName)) {
            return ContinuationFeatureGate.isEnabledAtStartup();
        }
        if (CRAFTING_CPU_ACCESSOR_MIXINS.contains(mixinClassName)) {
            return ContinuationFeatureGate.isEnabledAtStartup()
                    || FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()
                    || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup();
        }
        if (FORMAL_MACHINE_ONLY_MIXINS.contains(mixinClassName)) {
            return FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()
                    || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup();
        }
        if (FORMAL_MACHINE_PLANNING_ENTRY_MIXINS.contains(mixinClassName)) {
            return FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup();
        }
        if (FORMAL_MACHINE_PLANNING_SCALED_PATTERN_MIXINS.contains(mixinClassName)) {
            return FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()
                    || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup();
        }
        if (PARALLEL_CPU_ONLY_MIXINS.contains(mixinClassName)) {
            return ParallelCraftingCpuFeatureGate.isEnabledAtStartup();
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
