package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CompiledTask {

    private static final String NBT_ID = "id";
    private static final String NBT_PATTERN = "pattern";
    private static final String NBT_GRID = "grid";
    private static final String NBT_TOTAL_TICKS = "totalTicks";
    private static final String NBT_REMAINING_TICKS = "remainingTicks";
    private static final String NBT_STATE = "state";
    private static final String NBT_EXECUTION_COUNT = "executionCount";
    private static final String NBT_COMPLETION_ROUTE = "completionRoute";
    private static final String NBT_TEMPLATED_COMPLETION_SUPPORTED = "templatedCompletionSupported";
    private static final String NBT_COMPLETION_TEMPLATE_PRIMARY = "completionTemplatePrimary";
    private static final String NBT_COMPLETION_TEMPLATE_REMAINDERS = "completionTemplateRemainders";
    private static final String NBT_SOURCE_CRAFTING_ID = "sourceCraftingId";

    private final UUID id;
    private final ItemStack patternDefinition;
    private final ItemStack[] craftingGrid;
    private final int totalTicks;
    private int executionCount;
    private int remainingTicks;
    private TaskState state;
    private TaskCompletionRoute completionRoute;
    private boolean templatedCompletionSupported;
    @Nullable
    private GenericStack completionTemplatePrimary;
    private final Map<AEItemKey, Long> completionTemplateRemainders;
    @Nullable
    private UUID sourceCraftingId;
    private transient IMolecularAssemblerSupportedPattern resolvedPattern;

    private CompiledTask(
            UUID id,
            ItemStack patternDefinition,
            ItemStack[] craftingGrid,
            int totalTicks,
            int executionCount,
            int remainingTicks,
            TaskState state,
            @Nullable IMolecularAssemblerSupportedPattern resolvedPattern
    ) {
        this.id = id;
        this.patternDefinition = patternDefinition;
        this.craftingGrid = craftingGrid;
        this.totalTicks = totalTicks;
        this.executionCount = executionCount;
        this.remainingTicks = remainingTicks;
        this.state = state;
        this.completionRoute = TaskCompletionRoute.AE_STORAGE;
        this.templatedCompletionSupported = false;
        this.completionTemplatePrimary = null;
        this.completionTemplateRemainders = new LinkedHashMap<>();
        this.sourceCraftingId = null;
        this.resolvedPattern = resolvedPattern;
    }

    @Nullable
    public static CompiledTask compile(
            IMolecularAssemblerSupportedPattern pattern,
            KeyCounter[] inputHolders,
            int totalTicks,
            int executionCount
    ) {
        ItemStack[] craftingGrid = new ItemStack[9];
        for (int i = 0; i < craftingGrid.length; i++) {
            craftingGrid[i] = ItemStack.EMPTY;
        }
        pattern.fillCraftingGrid(inputHolders, (slot, stack) -> craftingGrid[slot] = stack.copy());
        for (KeyCounter inputHolder : inputHolders) {
            inputHolder.removeZeros();
            if (!inputHolder.isEmpty()) {
                return null;
            }
        }
        int sanitizedTicks = Math.max(1, totalTicks);
        return new CompiledTask(
                UUID.randomUUID(),
                pattern.getDefinition().toStack(),
                craftingGrid,
                sanitizedTicks,
                Math.max(1, executionCount),
                sanitizedTicks,
                TaskState.QUEUED,
                pattern
        );
    }

    @Nullable
    public static CompiledTask compileWithCraftingGrid(
            ItemStack patternDefinition,
            ItemStack[] craftingGrid,
            int totalTicks,
            int executionCount,
            @Nullable IMolecularAssemblerSupportedPattern resolvedPattern
    ) {
        if (patternDefinition == null || patternDefinition.isEmpty() || craftingGrid == null || craftingGrid.length != 9) {
            return null;
        }
        ItemStack[] copiedGrid = new ItemStack[craftingGrid.length];
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            ItemStack stack = craftingGrid[slot];
            copiedGrid[slot] = stack == null ? ItemStack.EMPTY : stack.copy();
        }
        int sanitizedTicks = Math.max(1, totalTicks);
        return new CompiledTask(
                UUID.randomUUID(),
                patternDefinition.copy(),
                copiedGrid,
                sanitizedTicks,
                Math.max(1, executionCount),
                sanitizedTicks,
                TaskState.QUEUED,
                resolvedPattern
        );
    }

    @Nullable
    public IMolecularAssemblerSupportedPattern resolvePattern(Level level) {
        if (resolvedPattern != null) {
            return resolvedPattern;
        }
        if (level == null || patternDefinition == null || patternDefinition.isEmpty()) {
            return null;
        }
        if (PatternDetailsHelper.decodePattern(patternDefinition, level) instanceof IMolecularAssemblerSupportedPattern supportedPattern) {
            this.resolvedPattern = supportedPattern;
            return supportedPattern;
        }
        return null;
    }

    public void advance(int ticks) {
        if (remainingTicks <= 0) {
            return;
        }
        state = TaskState.RUNNING;
        remainingTicks = Math.max(0, remainingTicks - Math.max(1, ticks));
        if (remainingTicks == 0) {
            state = TaskState.WAITING_OUTPUT;
        }
    }

    public boolean isReadyToComplete() {
        return remainingTicks <= 0;
    }

    public void markComplete() {
        state = TaskState.COMPLETE;
    }

    public void markPendingCompletion() {
        state = TaskState.PENDING_COMPLETION;
    }

    public void markFailed() {
        state = TaskState.FAILED;
    }

    public boolean canCoalesceWith(CompiledTask other, int maxExecutionCount) {
        if (other == null || maxExecutionCount < 1) {
            return false;
        }
        if (this.state != TaskState.QUEUED || other.state != TaskState.QUEUED) {
            return false;
        }
        if (this.remainingTicks != this.totalTicks || other.remainingTicks != other.totalTicks) {
            return false;
        }
        return hasMatchingBatchKey(other, maxExecutionCount);
    }

    public boolean canDrainCoalesceWith(CompiledTask other, int maxExecutionCount) {
        if (other == null || maxExecutionCount < 1) {
            return false;
        }
        if (!canDrainMergeKeyWith(other)) {
            return false;
        }
        return hasRoomForExecutionCount(other, maxExecutionCount);
    }

    public boolean canDrainMergeKeyWith(CompiledTask other) {
        if (other == null) {
            return false;
        }
        if (!canAcceptDrainMerge() || other.state != TaskState.QUEUED) {
            return false;
        }
        if (other.remainingTicks != other.totalTicks) {
            return false;
        }
        return hasSameBatchKey(other);
    }

    public boolean hasSameBatchKey(CompiledTask other) {
        if (other == null) {
            return false;
        }
        if (this.completionRoute != other.completionRoute) {
            return false;
        }
        if (!Objects.equals(this.sourceCraftingId, other.sourceCraftingId)) {
            return false;
        }
        if (this.totalTicks != other.totalTicks) {
            return false;
        }
        if (!isSameItemSameTags(this.patternDefinition, other.patternDefinition)) {
            return false;
        }
        if (this.craftingGrid.length != other.craftingGrid.length) {
            return false;
        }
        for (int slot = 0; slot < this.craftingGrid.length; slot++) {
            if (!isSameItemSameTags(this.craftingGrid[slot], other.craftingGrid[slot])) {
                return false;
            }
            if (getCountOrZero(this.craftingGrid[slot]) != getCountOrZero(other.craftingGrid[slot])) {
                return false;
            }
        }
        return true;
    }

    private boolean canAcceptDrainMerge() {
        if (state == TaskState.QUEUED) {
            return remainingTicks == totalTicks;
        }
        return state == TaskState.RUNNING && remainingTicks > 0;
    }

    private boolean hasMatchingBatchKey(CompiledTask other, int maxExecutionCount) {
        return hasSameBatchKey(other) && hasRoomForExecutionCount(other, maxExecutionCount);
    }

    private boolean hasRoomForExecutionCount(CompiledTask other, int maxExecutionCount) {
        return (long) this.executionCount + other.executionCount <= maxExecutionCount;
    }

    public void appendExecutionCount(int additional) {
        if (additional <= 0) {
            return;
        }
        this.executionCount = Math.addExact(this.executionCount, additional);
    }

    public boolean canSafelyAppendExecutionCount(int additional) {
        if (additional <= 0) {
            return true;
        }
        return executionCount <= Integer.MAX_VALUE - additional;
    }

    public boolean tryAppendExecutionCount(int additional) {
        if (!canSafelyAppendExecutionCount(additional)) {
            return false;
        }
        appendExecutionCount(additional);
        return true;
    }

    public int remainingAppendCapacity() {
        return Math.max(0, Integer.MAX_VALUE - executionCount);
    }

    public int capAdditionalExecutions(int requestedAdditional) {
        if (requestedAdditional <= 0) {
            return 0;
        }
        return Math.min(requestedAdditional, remainingAppendCapacity());
    }

    private static boolean isSameItemSameTags(@Nullable ItemStack left, @Nullable ItemStack right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        return ItemStack.isSameItemSameTags(left, right);
    }

    private static int getCountOrZero(@Nullable ItemStack stack) {
        return stack == null ? 0 : stack.getCount();
    }

    public ItemStack[] getCraftingGridCopies() {
        ItemStack[] copies = new ItemStack[craftingGrid.length];
        for (int i = 0; i < craftingGrid.length; i++) {
            copies[i] = craftingGrid[i] == null ? ItemStack.EMPTY : craftingGrid[i].copy();
        }
        return copies;
    }

    public ItemStack getPatternDefinition() {
        return patternDefinition == null ? ItemStack.EMPTY : patternDefinition.copy();
    }

    public int getRemainingTicks() {
        return remainingTicks;
    }

    public int getExecutionCount() {
        return executionCount;
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public TaskState getState() {
        return state;
    }

    public TaskCompletionRoute getCompletionRoute() {
        return completionRoute;
    }

    public void setCompletionRoute(TaskCompletionRoute completionRoute) {
        this.completionRoute = completionRoute == null ? TaskCompletionRoute.AE_STORAGE : completionRoute;
    }

    public boolean supportsTemplatedCompletion() {
        return templatedCompletionSupported;
    }

    public void setSupportsTemplatedCompletion(boolean templatedCompletionSupported) {
        this.templatedCompletionSupported = templatedCompletionSupported;
        if (!templatedCompletionSupported) {
            clearCompletionTemplate();
        }
    }

    public boolean hasCompletionTemplate() {
        return completionTemplatePrimary != null;
    }

    public void setCompletionTemplate(@Nullable GenericStack primary, Map<AEItemKey, Long> remainders) {
        if (primary == null) {
            clearCompletionTemplate();
            return;
        }
        this.completionTemplatePrimary = new GenericStack(primary.what(), primary.amount());
        this.completionTemplateRemainders.clear();
        if (remainders != null) {
            for (Map.Entry<AEItemKey, Long> entry : remainders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                    this.completionTemplateRemainders.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.templatedCompletionSupported = true;
    }

    public void clearCompletionTemplate() {
        this.completionTemplatePrimary = null;
        this.completionTemplateRemainders.clear();
    }

    @Nullable
    public GenericStack getCompletionTemplatePrimary() {
        return completionTemplatePrimary == null
                ? null
                : new GenericStack(completionTemplatePrimary.what(), completionTemplatePrimary.amount());
    }

    public Map<AEItemKey, Long> getCompletionTemplateRemainders() {
        return Map.copyOf(completionTemplateRemainders);
    }

    @Nullable
    public UUID getSourceCraftingId() {
        return sourceCraftingId;
    }

    public void setSourceCraftingId(@Nullable UUID sourceCraftingId) {
        this.sourceCraftingId = sourceCraftingId;
    }

    public CompoundTag writeToTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(NBT_ID, id);
        if (patternDefinition != null && !patternDefinition.isEmpty()) {
            tag.put(NBT_PATTERN, patternDefinition.save(new CompoundTag()));
        }
        CompoundTag gridTag = new CompoundTag();
        for (int i = 0; i < craftingGrid.length; i++) {
            if (craftingGrid[i] != null && !craftingGrid[i].isEmpty()) {
                gridTag.put("slot" + i, craftingGrid[i].save(new CompoundTag()));
            }
        }
        tag.put(NBT_GRID, gridTag);
        tag.putInt(NBT_TOTAL_TICKS, totalTicks);
        tag.putInt(NBT_EXECUTION_COUNT, executionCount);
        tag.putInt(NBT_REMAINING_TICKS, remainingTicks);
        tag.putString(NBT_STATE, state.name());
        tag.putString(NBT_COMPLETION_ROUTE, completionRoute.name());
        tag.putBoolean(NBT_TEMPLATED_COMPLETION_SUPPORTED, templatedCompletionSupported);
        if (completionTemplatePrimary != null) {
            tag.put(NBT_COMPLETION_TEMPLATE_PRIMARY, GenericStack.writeTag(completionTemplatePrimary));
        }
        tag.put(NBT_COMPLETION_TEMPLATE_REMAINDERS, writeTemplateRemainders());
        if (sourceCraftingId != null) {
            tag.putUUID(NBT_SOURCE_CRAFTING_ID, sourceCraftingId);
        }
        return tag;
    }

    public static CompiledTask readFromTag(CompoundTag tag) {
        ItemStack[] craftingGrid = new ItemStack[9];
        CompoundTag gridTag = tag.getCompound(NBT_GRID);
        for (int i = 0; i < craftingGrid.length; i++) {
            if (gridTag.contains("slot" + i)) {
                craftingGrid[i] = ItemStack.of(gridTag.getCompound("slot" + i));
            }
        }
        CompiledTask compiledTask = new CompiledTask(
                tag.contains(NBT_ID) ? tag.getUUID(NBT_ID) : UUID.randomUUID(),
                tag.contains(NBT_PATTERN) ? ItemStack.of(tag.getCompound(NBT_PATTERN)) : null,
                craftingGrid,
                Math.max(1, tag.getInt(NBT_TOTAL_TICKS)),
                Math.max(1, tag.contains(NBT_EXECUTION_COUNT) ? tag.getInt(NBT_EXECUTION_COUNT) : 1),
                Math.max(0, tag.getInt(NBT_REMAINING_TICKS)),
                TaskState.valueOf(tag.getString(NBT_STATE)),
                null
        );
        compiledTask.setCompletionRoute(tag.contains(NBT_COMPLETION_ROUTE)
                ? TaskCompletionRoute.valueOf(tag.getString(NBT_COMPLETION_ROUTE))
                : TaskCompletionRoute.AE_STORAGE);
        compiledTask.setSupportsTemplatedCompletion(tag.getBoolean(NBT_TEMPLATED_COMPLETION_SUPPORTED));
        GenericStack templatePrimary = tag.contains(NBT_COMPLETION_TEMPLATE_PRIMARY)
                ? GenericStack.readTag(tag.getCompound(NBT_COMPLETION_TEMPLATE_PRIMARY))
                : null;
        if (templatePrimary != null) {
            compiledTask.setCompletionTemplate(
                    templatePrimary,
                    readTemplateRemainders(tag.getCompound(NBT_COMPLETION_TEMPLATE_REMAINDERS))
            );
        }
        if (tag.contains(NBT_SOURCE_CRAFTING_ID)) {
            compiledTask.setSourceCraftingId(tag.getUUID(NBT_SOURCE_CRAFTING_ID));
        }
        return compiledTask;
    }

    private CompoundTag writeTemplateRemainders() {
        CompoundTag tag = new CompoundTag();
        ListTag entries = new ListTag();
        for (Map.Entry<AEItemKey, Long> entry : completionTemplateRemainders.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            entries.add(GenericStack.writeTag(new GenericStack(entry.getKey(), entry.getValue())));
        }
        tag.put("entries", entries);
        return tag;
    }

    private static Map<AEItemKey, Long> readTemplateRemainders(CompoundTag tag) {
        Map<AEItemKey, Long> remainders = new LinkedHashMap<>();
        if (tag == null || tag.isEmpty()) {
            return remainders;
        }
        ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
        for (Tag entry : entries) {
            if (!(entry instanceof CompoundTag entryTag)) {
                continue;
            }
            GenericStack stack = GenericStack.readTag(entryTag);
            if (stack != null && stack.what() instanceof AEItemKey itemKey && stack.amount() > 0L) {
                remainders.put(itemKey, stack.amount());
            }
        }
        return remainders;
    }
}
