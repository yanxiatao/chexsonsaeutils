package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobTaskProgressAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 染色样板递归任务排序。
 *
 * 递归作业优先推送能产出内部催化物的样板，避免下游同色样板抢走最后一个 seed。
 */
public final class DyeablePatternRecursiveTaskOrdering {
    private DyeablePatternRecursiveTaskOrdering() {
    }

    /**
     * 把 AE2 普通 CPU 的任务表重排为递归友好顺序。
     */
    public static void reorderMixinTaskMap(Map<IPatternDetails, Object> tasks, KeyCounter internalItems) {
        if (tasks == null || tasks.size() <= 1 || internalItems == null || internalItems.isEmpty()) {
            return;
        }
        List<Map.Entry<IPatternDetails, Object>> entries = new ArrayList<>(tasks.entrySet());
        entries.sort(Comparator.<Map.Entry<IPatternDetails, Object>>comparingInt(
                        entry -> hasPositiveInternalNetOutput(entry.getKey(), internalItems) ? 0 : 1)
                .thenComparingInt(entry -> consumesInternalItem(entry.getKey(), internalItems) ? 1 : 0));
        Map<IPatternDetails, Object> ordered = new LinkedHashMap<>();
        for (Map.Entry<IPatternDetails, Object> entry : entries) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        tasks.clear();
        tasks.putAll(ordered);
    }

    /**
     * 返回 parallel CPU 当前应该优先尝试的任务列表。
     */
    public static <T> List<Map.Entry<IPatternDetails, T>> orderedEntries(
            Map<IPatternDetails, T> tasks,
            KeyCounter internalItems
    ) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<IPatternDetails, T>> entries = new ArrayList<>(tasks.entrySet());
        if (internalItems == null || internalItems.isEmpty()) {
            return entries;
        }
        entries.sort(Comparator.<Map.Entry<IPatternDetails, T>>comparingInt(
                        entry -> hasPositiveInternalNetOutput(entry.getKey(), internalItems) ? 0 : 1)
                .thenComparingInt(entry -> consumesInternalItem(entry.getKey(), internalItems) ? 1 : 0));
        return entries;
    }

    /**
     * 判断样板当前是否应被内部催化物保留规则阻止。
     */
    public static boolean shouldDeferConsumer(
            IPatternDetails pattern,
            KeyCounter internalItems,
            ICraftingInventory inventory
    ) {
        if (pattern == null || internalItems == null || internalItems.isEmpty() || inventory == null) {
            return false;
        }
        for (var internalItem : internalItems) {
            AEKey key = internalItem.getKey();
            long retainedAmount = internalItem.getLongValue();
            if (key == null
                    || retainedAmount <= 0L
                    || !consumesKey(pattern, key)
                    || hasPositiveNetOutput(pattern, key)) {
                continue;
            }
            long available = inventory.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE);
            if (available <= retainedAmount) {
                return true;
            }
        }
        return false;
    }

    public interface ParallelTaskProgressView {
        long chexsonsaeutils$dyeableRecursiveTaskProgressValue();
    }

    /**
     * 判断任务表里是否还有能补充内部催化物的任务。
     */
    public static boolean hasPendingProducer(Map<IPatternDetails, ?> tasks, KeyCounter internalItems) {
        if (tasks == null || tasks.isEmpty() || internalItems == null || internalItems.isEmpty()) {
            return false;
        }
        for (Map.Entry<IPatternDetails, ?> entry : tasks.entrySet()) {
            if (!hasPositiveInternalNetOutput(entry.getKey(), internalItems)) {
                continue;
            }
            Object value = entry.getValue();
            long progress = value instanceof ExecutingCraftingJobTaskProgressAccessor accessor
                    ? accessor.getValue()
                    : value instanceof ParallelTaskProgressView progressView
                            ? progressView.chexsonsaeutils$dyeableRecursiveTaskProgressValue()
                            : 0L;
            if (progress > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断任务表里是否还存在正数剩余次数。
     */
    public static boolean hasPendingTasks(Map<IPatternDetails, ?> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }
        for (Object value : tasks.values()) {
            if (taskProgressValue(value) > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回普通 CPU 任务剩余次数。
     */
    public static long mixinTaskProgressValue(@Nullable Object taskProgress) {
        return taskProgressValue(taskProgress);
    }

    private static long taskProgressValue(@Nullable Object taskProgress) {
        if (taskProgress instanceof ExecutingCraftingJobTaskProgressAccessor accessor) {
            return accessor.getValue();
        }
        if (taskProgress instanceof ParallelTaskProgressView progressView) {
            return progressView.chexsonsaeutils$dyeableRecursiveTaskProgressValue();
        }
        return 0L;
    }

    private static boolean hasPositiveInternalNetOutput(IPatternDetails pattern, KeyCounter internalItems) {
        if (pattern == null || internalItems == null) {
            return false;
        }
        for (var entry : internalItems) {
            if (entry.getKey() != null && hasPositiveNetOutput(pattern, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPositiveNetOutput(IPatternDetails pattern, AEKey key) {
        return outputAmount(pattern, key) > inputAmount(pattern, key);
    }

    private static boolean consumesInternalItem(IPatternDetails pattern, KeyCounter internalItems) {
        if (pattern == null || internalItems == null) {
            return false;
        }
        for (var entry : internalItems) {
            if (entry.getKey() != null && consumesKey(pattern, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean consumesKey(IPatternDetails pattern, AEKey key) {
        return inputAmount(pattern, key) > 0L;
    }

    private static long outputAmount(IPatternDetails pattern, AEKey key) {
        long amount = 0L;
        if (pattern == null || key == null) {
            return 0L;
        }
        for (var output : pattern.getOutputs()) {
            if (output != null && output.what() != null && key.matches(output)) {
                amount += output.amount();
            }
        }
        return amount;
    }

    private static long inputAmount(IPatternDetails pattern, AEKey key) {
        long amount = 0L;
        if (pattern == null || key == null) {
            return 0L;
        }
        for (var input : pattern.getInputs()) {
            if (input == null || input.getPossibleInputs() == null) {
                continue;
            }
            long matchedAmount = 0L;
            for (var possibleInput : input.getPossibleInputs()) {
                if (possibleInput != null && possibleInput.what() != null && key.matches(possibleInput)) {
                    matchedAmount = Math.max(matchedAmount, possibleInput.amount());
                }
            }
            amount += matchedAmount * input.getMultiplier();
        }
        return amount;
    }
}
