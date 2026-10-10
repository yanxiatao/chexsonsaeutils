package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 递归计划：在普通 AE2 计划外携带递归执行所需的额外契约字段。
 *
 * <p>计划契约（{@link RecursivePlanContract} 与
 * {@link SelectedInputsPlan}）被执行侧消费（CPU 预取催化物、
 * formal 聚合折叠）。历史方法名保持兼容，颜色不参与字段语义。
 */
public record RecursiveCraftingPlan(
        CraftingPlan delegate,
        KeyCounter recursiveInitialItems,
        KeyCounter recursiveInternalItems,
        long recursiveFinalOutputAmount,
        Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs)
        implements ICraftingPlan, RecursivePlanContract, SelectedInputsPlan {

    @Override
    public GenericStack finalOutput() {
        return delegate.finalOutput();
    }

    @Override
    public long bytes() {
        return delegate.bytes();
    }

    @Override
    public boolean simulation() {
        return delegate.simulation();
    }

    @Override
    public boolean multiplePaths() {
        return delegate.multiplePaths();
    }

    @Override
    public KeyCounter usedItems() {
        return delegate.usedItems();
    }

    @Override
    public KeyCounter emittedItems() {
        return delegate.emittedItems();
    }

    @Override
    public KeyCounter missingItems() {
        return delegate.missingItems();
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return delegate.patternTimes();
    }

    @Override
    public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
        return true;
    }

    @Override
    public ICraftingPlan chexsonsaeutils$getDelegate() {
        return delegate;
    }

    @Override
    public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
        return copyCounter(recursiveInitialItems);
    }

    @Override
    public KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
        return copyCounter(recursiveInternalItems);
    }

    @Override
    public long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
        return recursiveFinalOutputAmount;
    }

    @Override
    public Map<AEItemKey, Map<AEKey, Long>> chexsonsaeutils$dyeableSelectedPatternInputs() {
        return copySelectedPatternInputs(selectedPatternInputs);
    }

    private static KeyCounter copyCounter(KeyCounter original) {
        KeyCounter copy = new KeyCounter();
        if (original != null) {
            copy.addAll(original);
        }
        return copy;
    }

    private static Map<AEItemKey, Map<AEKey, Long>> copySelectedPatternInputs(
            @Nullable Map<AEItemKey, Map<AEKey, Long>> original) {
        if (original == null || original.isEmpty()) {
            return Map.of();
        }
        Map<AEItemKey, Map<AEKey, Long>> copy = new HashMap<>();
        for (var entry : original.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copy.put(entry.getKey(), Map.copyOf(new HashMap<>(entry.getValue())));
        }
        return Map.copyOf(copy);
    }
}
