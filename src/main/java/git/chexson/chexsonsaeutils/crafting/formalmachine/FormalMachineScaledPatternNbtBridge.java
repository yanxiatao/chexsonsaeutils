package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingLinkAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobTaskProgressAccessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public final class FormalMachineScaledPatternNbtBridge {

    public static final String TASKS_TAG = "tasks";
    public static final String CRAFTING_PROGRESS_TAG = "#craftingProgress";
    public static final String FORMAL_MULTIPLIER_TAG = "formalMultiplier";

    private FormalMachineScaledPatternNbtBridge() {
    }

    public static void rewriteTaskListForWrite(
            ExecutingCraftingJob job,
            HolderLookup.Provider registries,
            CompoundTag output
    ) {
        if (job == null || registries == null || output == null) {
            return;
        }
        ListTag rewritten = new ListTag();
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        for (Map.Entry<IPatternDetails, Object> entry : accessor.getTasks().entrySet()) {
            CompoundTag item = entry.getKey().getDefinition().toTag(registries);
            if (entry.getKey() instanceof IFormalMachineScaledPattern scaledPattern) {
                item.putInt(FORMAL_MULTIPLIER_TAG, Math.max(1, scaledPattern.multiplier()));
            }
            if (entry.getValue() instanceof ExecutingCraftingJobTaskProgressAccessor progressAccessor) {
                item.putLong(CRAFTING_PROGRESS_TAG, progressAccessor.getValue());
            } else {
                item.putLong(CRAFTING_PROGRESS_TAG, 0L);
            }
            rewritten.add(item);
        }
        output.put(TASKS_TAG, rewritten);
    }

    public static void rebuildTasksAfterRead(
            ExecutingCraftingJob job,
            CompoundTag data,
            HolderLookup.Provider registries
    ) {
        if (job == null || data == null || registries == null) {
            return;
        }
        ListTag tasksTag = data.getList(TASKS_TAG, Tag.TAG_COMPOUND);
        if (tasksTag.isEmpty()) {
            return;
        }
        CraftingCPUCluster cpuCluster = resolveCpuCluster(job);
        if (cpuCluster == null) {
            return;
        }
        Level level = cpuCluster.getLevel();
        if (level == null) {
            return;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        Map<AEItemKey, Object> existingProgressByDefinition = new java.util.LinkedHashMap<>();
        for (Map.Entry<IPatternDetails, Object> entry : accessor.getTasks().entrySet()) {
            if (entry.getKey() != null && entry.getKey().getDefinition() instanceof AEItemKey definition) {
                existingProgressByDefinition.put(definition, entry.getValue());
            }
        }
        accessor.getTasks().clear();
        int restoredScaledPatterns = 0;
        for (int index = 0; index < tasksTag.size(); index++) {
            CompoundTag item = tasksTag.getCompound(index);
            AEItemKey definition = AEItemKey.fromTag(registries, item);
            if (definition == null) {
                continue;
            }
            IPatternDetails decoded = PatternDetailsHelper.decodePattern(definition, level);
            if (decoded == null) {
                continue;
            }
            int multiplier = Math.max(1, item.getInt(FORMAL_MULTIPLIER_TAG));
            if (multiplier > 1) {
                ScaledCraftingPatternAnalyzer.Eligibility eligibility =
                        ScaledCraftingPatternAnalyzer.analyze(level, decoded);
                if (eligibility != null) {
                    decoded = ScaledCraftingPatternAnalyzer.createScaledPattern(eligibility, multiplier);
                    restoredScaledPatterns++;
                }
            }
            Object taskProgress = existingProgressByDefinition.remove(definition);
            if (taskProgress == null) {
                continue;
            }
            if (taskProgress instanceof ExecutingCraftingJobTaskProgressAccessor progressAccessor) {
                progressAccessor.setValue(item.getLong(CRAFTING_PROGRESS_TAG));
            }
            accessor.getTasks().put(decoded, taskProgress);
        }
    }

    private static @Nullable CraftingCPUCluster resolveCpuCluster(ExecutingCraftingJob job) {
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        if (accessor.getLink() == null) {
            return null;
        }
        ICraftingCPU cpu = ((CraftingLinkAccessor) accessor.getLink()).getCpu();
        if (cpu instanceof CraftingCPUCluster cluster) {
            return cluster;
        }
        return null;
    }

    private static @Nullable AbstractHighCapacityCraftingHostBlockEntity resolveFormalMachineHost(
            CraftingCPUCluster cpuCluster,
            Map<IPatternDetails, Object> tasks
    ) {
        IGrid grid = cpuCluster.getGrid();
        if (grid == null || !(grid.getCraftingService() instanceof CraftingService craftingService)) {
            return null;
        }
        AbstractHighCapacityCraftingHostBlockEntity resolved = null;
        for (IPatternDetails patternDetails : tasks.keySet()) {
            for (ICraftingProvider provider : craftingService.getProviders(patternDetails)) {
                if (!(provider instanceof AbstractHighCapacityCraftingHostBlockEntity host)) {
                    continue;
                }
                if (resolved != null && resolved != host) {
                    return null;
                }
                resolved = host;
            }
        }
        return resolved;
    }
}
