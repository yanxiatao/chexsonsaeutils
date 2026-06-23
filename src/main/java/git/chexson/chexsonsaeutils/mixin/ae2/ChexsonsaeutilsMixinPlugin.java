package git.chexson.chexsonsaeutils.mixin.ae2;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ChexsonsaeutilsMixinPlugin implements IMixinConfigPlugin {

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
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.AbstractTableRendererAccessor"
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
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED,
                    "processingPatternReplacementEnabled");
        }
        if (CONTINUATION_ONLY_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED,
                    "craftingContinuationEnabled");
        }
        if (PARALLEL_CPU_ONLY_MIXINS.contains(mixinClassName)) {
            return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED, "parallelCraftingCpuEnabled");
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
