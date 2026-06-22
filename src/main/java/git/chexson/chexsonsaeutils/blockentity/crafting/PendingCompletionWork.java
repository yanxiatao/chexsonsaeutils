package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PendingCompletionWork {

    private static final String NBT_TASK = "task";
    private static final String NBT_COMPLETED_EXECUTIONS = "completedExecutions";
    private static final String NBT_PRIMARY = "primary";
    private static final String NBT_REMAINDER_TOTALS = "remainderTotals";
    private static final String NBT_TEMPLATED = "templated";
    private static final String NBT_TEMPLATE_PRIMARY = "templatePrimary";
    private static final String NBT_TEMPLATE_REMAINDERS = "templateRemainders";
    private static final String NBT_ROUTE = "route";
    private static final String NBT_LAST_SLICE_SIZE = "lastSliceSize";
    private static final String NBT_SAVE_THROTTLE = "saveThrottle";

    private final CompiledTask compiledTask;
    private int completedExecutions;
    @Nullable
    private GenericStack aggregatedPrimary;
    private final Map<AEItemKey, Long> remainderTotals;
    private boolean templated;
    @Nullable
    private GenericStack templatePrimary;
    private final Map<AEItemKey, Long> templateRemainders;
    private int lastSliceSize;
    private int unsavedSliceCounter;

    public PendingCompletionWork(CompiledTask compiledTask) {
        this(
                compiledTask,
                0,
                null,
                new LinkedHashMap<>(),
                false,
                null,
                new LinkedHashMap<>(),
                0,
                0
        );
        if (compiledTask != null && compiledTask.hasCompletionTemplate()) {
            setTemplate(
                    compiledTask.getCompletionTemplatePrimary(),
                    compiledTask.getCompletionTemplateRemainders()
            );
        }
    }

    private PendingCompletionWork(
            CompiledTask compiledTask,
            int completedExecutions,
            @Nullable GenericStack aggregatedPrimary,
            Map<AEItemKey, Long> remainderTotals,
            boolean templated,
            @Nullable GenericStack templatePrimary,
            Map<AEItemKey, Long> templateRemainders,
            int lastSliceSize,
            int unsavedSliceCounter
    ) {
        this.compiledTask = compiledTask;
        this.completedExecutions = Math.max(0, completedExecutions);
        this.aggregatedPrimary = aggregatedPrimary == null
                ? null
                : new GenericStack(aggregatedPrimary.what(), aggregatedPrimary.amount());
        this.remainderTotals = new LinkedHashMap<>(remainderTotals);
        this.templated = templated;
        this.templatePrimary = templatePrimary == null
                ? null
                : new GenericStack(templatePrimary.what(), templatePrimary.amount());
        this.templateRemainders = new LinkedHashMap<>(templateRemainders);
        this.lastSliceSize = Math.max(0, lastSliceSize);
        this.unsavedSliceCounter = Math.max(0, unsavedSliceCounter);
        this.compiledTask.markPendingCompletion();
    }

    public CompiledTask compiledTask() {
        return compiledTask;
    }

    public int completedExecutions() {
        return completedExecutions;
    }

    public int totalExecutions() {
        return compiledTask.getExecutionCount();
    }

    public int remainingExecutions() {
        return Math.max(0, totalExecutions() - completedExecutions);
    }

    public boolean isComplete() {
        return completedExecutions >= totalExecutions();
    }

    public TaskCompletionRoute completionRoute() {
        return compiledTask.getCompletionRoute();
    }

    public boolean isTemplated() {
        return templated;
    }

    public void setTemplate(GenericStack primary, Map<AEItemKey, Long> remainders) {
        this.templated = primary != null;
        this.templatePrimary = primary == null ? null : new GenericStack(primary.what(), primary.amount());
        this.templateRemainders.clear();
        if (remainders != null) {
            this.templateRemainders.putAll(remainders);
        }
    }

    @Nullable
    public GenericStack templatePrimary() {
        return templatePrimary == null ? null : new GenericStack(templatePrimary.what(), templatePrimary.amount());
    }

    public Map<AEItemKey, Long> templateRemainders() {
        return Map.copyOf(templateRemainders);
    }

    public boolean hasTemplate() {
        return templated && templatePrimary != null;
    }

    public int lastSliceSize() {
        return lastSliceSize;
    }

    public void markSliceProcessed(int sliceExecutions) {
        this.lastSliceSize = Math.max(0, sliceExecutions);
        this.unsavedSliceCounter++;
    }

    public int unsavedSliceCounter() {
        return unsavedSliceCounter;
    }

    public void resetUnsavedSliceCounter() {
        this.unsavedSliceCounter = 0;
    }

    public void appendPrimary(GenericStack primary) {
        if (primary == null) {
            return;
        }
        if (aggregatedPrimary == null) {
            aggregatedPrimary = new GenericStack(primary.what(), primary.amount());
            return;
        }
        if (!aggregatedPrimary.what().equals(primary.what())) {
            throw new IllegalStateException("Mismatched primary output while aggregating completion payload");
        }
        aggregatedPrimary = new GenericStack(
                aggregatedPrimary.what(),
                saturatedLongAdd(aggregatedPrimary.amount(), primary.amount())
        );
    }

    public void appendRemainders(Map<AEItemKey, Long> remainders) {
        if (remainders == null || remainders.isEmpty()) {
            return;
        }
        for (Map.Entry<AEItemKey, Long> entry : remainders.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                remainderTotals.merge(entry.getKey(), entry.getValue(), PendingCompletionWork::saturatedLongAdd);
            }
        }
    }

    public void advanceExecutions(int executions) {
        completedExecutions = Math.min(totalExecutions(), completedExecutions + Math.max(0, executions));
    }

    @Nullable
    public GenericStack aggregatedPrimary() {
        return aggregatedPrimary == null ? null : new GenericStack(aggregatedPrimary.what(), aggregatedPrimary.amount());
    }

    public Map<AEItemKey, Long> aggregatedRemainders() {
        return Map.copyOf(remainderTotals);
    }

    @Nullable
    public PendingAeReturn toPendingAeReturn() {
        if (!isComplete() || aggregatedPrimary == null) {
            return null;
        }
        List<GenericStack> remainderStacks = new ArrayList<>(remainderTotals.size());
        for (Map.Entry<AEItemKey, Long> entry : remainderTotals.entrySet()) {
            if (entry.getValue() > 0) {
                remainderStacks.add(new GenericStack(entry.getKey(), entry.getValue()));
            }
        }
        return new PendingAeReturn(
                aggregatedPrimary,
                List.copyOf(remainderStacks),
                compiledTask.getExecutionCount(),
                compiledTask.getCompletionRoute(),
                compiledTask.getSourceCraftingId()
        );
    }

    public CompoundTag writeToTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(NBT_TASK, compiledTask.writeToTag(registries));
        tag.putInt(NBT_COMPLETED_EXECUTIONS, completedExecutions);
        if (aggregatedPrimary != null) {
            tag.put(NBT_PRIMARY, GenericStack.writeTag(registries, aggregatedPrimary));
        }
        tag.put(NBT_REMAINDER_TOTALS, writeStackMap(registries, remainderTotals));
        tag.putBoolean(NBT_TEMPLATED, templated);
        if (templatePrimary != null) {
            tag.put(NBT_TEMPLATE_PRIMARY, GenericStack.writeTag(registries, templatePrimary));
        }
        tag.put(NBT_TEMPLATE_REMAINDERS, writeStackMap(registries, templateRemainders));
        tag.putString(NBT_ROUTE, completionRoute().name());
        tag.putInt(NBT_LAST_SLICE_SIZE, lastSliceSize);
        tag.putInt(NBT_SAVE_THROTTLE, unsavedSliceCounter);
        return tag;
    }

    @Nullable
    public static PendingCompletionWork readFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty() || !tag.contains(NBT_TASK)) {
            return null;
        }
        CompiledTask compiledTask = CompiledTask.readFromTag(tag.getCompound(NBT_TASK), registries);
        compiledTask.markPendingCompletion();
        TaskCompletionRoute route = tag.contains(NBT_ROUTE)
                ? TaskCompletionRoute.valueOf(tag.getString(NBT_ROUTE))
                : compiledTask.getCompletionRoute();
        compiledTask.setCompletionRoute(route);
        GenericStack primary = tag.contains(NBT_PRIMARY)
                ? GenericStack.readTag(registries, tag.getCompound(NBT_PRIMARY))
                : null;
        GenericStack templatePrimary = tag.contains(NBT_TEMPLATE_PRIMARY)
                ? GenericStack.readTag(registries, tag.getCompound(NBT_TEMPLATE_PRIMARY))
                : null;
        return new PendingCompletionWork(
                compiledTask,
                Math.max(0, tag.getInt(NBT_COMPLETED_EXECUTIONS)),
                primary,
                readStackMap(tag.getCompound(NBT_REMAINDER_TOTALS), registries),
                tag.getBoolean(NBT_TEMPLATED),
                templatePrimary,
                readStackMap(tag.getCompound(NBT_TEMPLATE_REMAINDERS), registries),
                Math.max(0, tag.getInt(NBT_LAST_SLICE_SIZE)),
                Math.max(0, tag.getInt(NBT_SAVE_THROTTLE))
        );
    }

    public static boolean supportsTemplateForPattern(IMolecularAssemblerSupportedPattern pattern) {
        return AbstractHighCapacityCraftingHostBlockEntity.supportsCompletionTemplate(pattern);
    }

    private static CompoundTag writeStackMap(HolderLookup.Provider registries, Map<AEItemKey, Long> stacks) {
        ListTag listTag = new ListTag();
        for (Map.Entry<AEItemKey, Long> entry : stacks.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            listTag.add(GenericStack.writeTag(registries, new GenericStack(entry.getKey(), entry.getValue())));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("entries", listTag);
        return tag;
    }

    private static Map<AEItemKey, Long> readStackMap(CompoundTag tag, HolderLookup.Provider registries) {
        Map<AEItemKey, Long> stacks = new LinkedHashMap<>();
        if (tag == null || tag.isEmpty()) {
            return stacks;
        }
        ListTag listTag = tag.getList("entries", Tag.TAG_COMPOUND);
        for (Tag stackEntry : listTag) {
            if (!(stackEntry instanceof CompoundTag stackTag)) {
                continue;
            }
            GenericStack stack = GenericStack.readTag(registries, stackTag);
            if (stack != null && stack.what() instanceof AEItemKey itemKey) {
                stacks.merge(itemKey, stack.amount(), PendingCompletionWork::saturatedLongAdd);
            }
        }
        return stacks;
    }

    private static long saturatedLongAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + Math.max(0L, right);
    }
}
