package git.chexson.chexsonsaeutils.mixin.ae2;

import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.BuildingGadgets2IntegrationFeatureGate;
import git.chexson.chexsonsaeutils.config.ContinuationFeatureGate;
import git.chexson.chexsonsaeutils.config.DyeablePatternsFeatureGate;
import git.chexson.chexsonsaeutils.config.EnhancedCraftingStatusFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachineCraftingDispatchFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachinePlanningAggregationFeatureGate;
import git.chexson.chexsonsaeutils.config.FtbUltimineMemoryCardFeatureGate;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuFeatureGate;
import git.chexson.chexsonsaeutils.config.ProcessingPatternReplacementFeatureGate;
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
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCPUClusterFormalMachineStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingStatusFormalMachineMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuFormalMachineHeartbeatMixin"
    );
    private static final Set<String> CRAFTING_CPU_PUSH_CONTEXT_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicFormalMachineSourceContextMixin"
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
    private static final Set<String> AEA_DYEABLE_PATTERN_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.item.EncodedPatternItemDyeableClientMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.client.style.StyleManagerDyeablePatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsDyeablePatternMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessAccessor",
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeDyeablePatternMixin"
    );
    private static final Set<String> AEA_ENHANCED_CRAFTING_STATUS_MIXINS = Set.of(
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingStatusEnhancedStatusMixin",
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingStatusEntryEnhancedStatusMixin",
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
                    || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()
                    || EnhancedCraftingStatusFeatureGate.isEnabledAtStartup();
        }
        if (FORMAL_MACHINE_ONLY_MIXINS.contains(mixinClassName)) {
            return FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()
                    || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup();
        }
        if (CRAFTING_CPU_PUSH_CONTEXT_MIXINS.contains(mixinClassName)) {
            return FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()
                    || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()
                    || EnhancedCraftingStatusFeatureGate.isEnabledAtStartup();
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
        if (AEA_DYEABLE_PATTERN_MIXINS.contains(mixinClassName)) {
            return DyeablePatternsFeatureGate.isEnabledAtStartup();
        }
        if (AEA_ENHANCED_CRAFTING_STATUS_MIXINS.contains(mixinClassName)) {
            return EnhancedCraftingStatusFeatureGate.isEnabledAtStartup();
        }
        if (AEA_BUILDING_GADGETS2_MIXINS.contains(mixinClassName)) {
            return BuildingGadgets2IntegrationFeatureGate.isEnabledAtStartup()
                    && isLoadedDuringMixinScan(BUILDING_GADGETS2_MOD_ID, mixinClassName);
        }
        if (AEA_FTB_ULTIMINE_MIXINS.contains(mixinClassName)) {
            return FtbUltimineMemoryCardFeatureGate.isEnabledAtStartup()
                    && isLoadedDuringMixinScan(FTB_ULTIMINE_MOD_ID, mixinClassName);
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
