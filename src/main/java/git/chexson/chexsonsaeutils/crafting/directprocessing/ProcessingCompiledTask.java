package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.formalmachine.IFormalMachineAggregatedPattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ProcessingCompiledTask {

    private static final String NBT_PATTERN = "pattern";
    private static final String NBT_INPUTS = "inputs";
    private static final String NBT_OUTPUTS = "outputs";
    private static final String NBT_TOTAL_TICKS = "totalTicks";
    private static final String NBT_REMAINING_TICKS = "remainingTicks";
    private static final String NBT_EXECUTION_COUNT = "executionCount";
    private static final String NBT_SOURCE_CRAFTING_ID = "sourceCraftingId";

    private final ItemStack patternDefinition;
    private final List<GenericStack> selectedInputs;
    private final List<GenericStack> outputsPerExecution;
    private final int totalTicks;
    private int executionCount;
    private int remainingTicks;
    @Nullable
    private UUID sourceCraftingId;

    public ProcessingCompiledTask(
            ItemStack patternDefinition,
            List<GenericStack> selectedInputs,
            List<GenericStack> outputsPerExecution,
            int totalTicks,
            int remainingTicks,
            int executionCount
    ) {
        this.patternDefinition = patternDefinition == null ? ItemStack.EMPTY : patternDefinition.copy();
        this.selectedInputs = List.copyOf(selectedInputs == null ? List.of() : selectedInputs);
        this.outputsPerExecution = List.copyOf(outputsPerExecution == null ? List.of() : outputsPerExecution);
        this.totalTicks = Math.max(1, totalTicks);
        this.remainingTicks = Math.max(0, remainingTicks);
        this.executionCount = normalizeExecutionCount(executionCount);
        this.sourceCraftingId = null;
    }

    @Nullable
    public static ProcessingCompiledTask compile(
            IPatternDetails pattern,
            RecipeSignature signature,
            KeyCounter[] inputHolder,
            int totalTicks
    ) {
        if (pattern == null || signature == null || inputHolder == null) {
            return null;
        }
        List<RecipeSignatureInput> selectedInputs = DirectProcessingStackSupport.toSignatureInputs(
                collectSelectedInputs(pattern, inputHolder)
        );
        List<GenericStack> normalizedPatternOutputs = DirectProcessingStackSupport.normalizeStacks(pattern.getOutputs());
        List<GenericStack> outputsPerExecution = DirectProcessingStackSupport.normalizeStacks(signature.outputs());
        if (selectedInputs.isEmpty() || outputsPerExecution.isEmpty() || normalizedPatternOutputs.isEmpty()) {
            return null;
        }
        Integer executionCount = DirectProcessingStackSupport.deriveExecutionCount(selectedInputs, signature.inputs());
        if (executionCount == null || executionCount <= 0) {
            return null;
        }
        List<GenericStack> scaledOutputs = DirectProcessingStackSupport.scaleStacks(outputsPerExecution, executionCount);
        if (!normalizedPatternOutputs.equals(scaledOutputs)) {
            return null;
        }
        return new ProcessingCompiledTask(
                pattern.getDefinition().toStack(),
                DirectProcessingStackSupport.toGenericStacks(signature.inputs()),
                outputsPerExecution,
                totalTicks,
                totalTicks,
                executionCount
        );
    }

    @Nullable
    public static ProcessingCompiledTask compileAggregated(
            IFormalMachineAggregatedPattern aggregatedPattern,
            KeyCounter[] inputHolder,
            int totalTicks
    ) {
        if (aggregatedPattern == null || inputHolder == null) {
            return null;
        }
        List<GenericStack> selectedInputs = new ArrayList<>();
        for (GenericStack input : aggregatedPattern.aggregatedInputs()) {
            if (input == null || input.what() == null || input.amount() <= 0) {
                return null;
            }
            long extracted = 0;
            for (KeyCounter holder : inputHolder) {
                long available = holder.get(input.what());
                if (available > 0) {
                    long toExtract = Math.min(available, input.amount() - extracted);
                    holder.remove(input.what(), toExtract);
                    extracted += toExtract;
                    if (extracted >= input.amount()) {
                        break;
                    }
                }
            }
            if (extracted != input.amount()) {
                return null;
            }
            selectedInputs.add(input);
        }
        List<GenericStack> outputs = aggregatedPattern.aggregatedOutputs();
        if (outputs.isEmpty()) {
            return null;
        }
        return new ProcessingCompiledTask(
                aggregatedPattern.getDefinition().toStack(),
                selectedInputs,
                outputs,
                totalTicks,
                totalTicks,
                1
        );
    }

    private static List<GenericStack> collectSelectedInputs(IPatternDetails pattern, KeyCounter[] inputHolder) {
        List<GenericStack> inputs = new ArrayList<>();
        pattern.pushInputsToExternalInventory(inputHolder, (AEKey key, long amount) -> {
            if (key != null && amount > 0) {
                inputs.add(new GenericStack(key, amount));
            }
        });
        return List.copyOf(inputs);
    }

    public void advance(int workUnits) {
        remainingTicks = Math.max(0, remainingTicks - Math.max(1, workUnits));
    }

    public boolean isReadyToComplete() {
        return remainingTicks <= 0;
    }

    public List<GenericStack> buildOutputPayload() {
        List<GenericStack> payload = new ArrayList<>();
        for (GenericStack output : outputsPerExecution) {
            if (output != null && output.amount() > 0) {
                long amount = multiplyOrZero(output.amount(), executionCount);
                if (amount <= 0L) {
                    return List.of();
                }
                payload.add(new GenericStack(output.what(), amount));
            }
        }
        return List.copyOf(payload);
    }

    public int executionCount() {
        return executionCount;
    }

    @Nullable
    public UUID sourceCraftingId() {
        return sourceCraftingId;
    }

    public void setSourceCraftingId(@Nullable UUID sourceCraftingId) {
        this.sourceCraftingId = sourceCraftingId;
    }

    public boolean canDrainCoalesceWith(ProcessingCompiledTask other) {
        if (other == null) {
            return false;
        }
        if (other.remainingTicks != other.totalTicks) {
            return false;
        }
        boolean isQueued = this.remainingTicks == this.totalTicks;
        boolean isRunning = this.remainingTicks > 0 && this.remainingTicks < this.totalTicks;
        if (!isQueued && !isRunning) {
            return false;
        }
        return selectedInputs.equals(other.selectedInputs)
                && outputsPerExecution.equals(other.outputsPerExecution)
                && totalTicks == other.totalTicks
                && Objects.equals(sourceCraftingId, other.sourceCraftingId)
                && canBuildOutputPayloadFor(executionCount + other.executionCount);
    }

    public boolean canCoalesceWith(ProcessingCompiledTask other, int maxExecutionCount) {
        if (other == null) {
            return false;
        }
        if (this.remainingTicks != this.totalTicks || other.remainingTicks != other.totalTicks) {
            return false;
        }
        return selectedInputs.equals(other.selectedInputs)
                && outputsPerExecution.equals(other.outputsPerExecution)
                && totalTicks == other.totalTicks
                && Objects.equals(sourceCraftingId, other.sourceCraftingId)
                && canBuildOutputPayloadFor(executionCount + other.executionCount);
    }

    public boolean tryAppendExecutionCount(int additionalExecutions, int maxExecutionCount) {
        if (additionalExecutions <= 0) {
            return false;
        }
        int nextExecutionCount = executionCount + additionalExecutions;
        if (!canBuildOutputPayloadFor(nextExecutionCount)) {
            return false;
        }
        executionCount = nextExecutionCount;
        return true;
    }

    public CompoundTag writeToTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(NBT_PATTERN, patternDefinition.saveOptional(registries));
        tag.put(NBT_INPUTS, writeStacks(registries, selectedInputs));
        tag.put(NBT_OUTPUTS, writeStacks(registries, outputsPerExecution));
        tag.putInt(NBT_TOTAL_TICKS, totalTicks);
        tag.putInt(NBT_REMAINING_TICKS, remainingTicks);
        tag.putInt(NBT_EXECUTION_COUNT, executionCount);
        if (sourceCraftingId != null) {
            tag.putUUID(NBT_SOURCE_CRAFTING_ID, sourceCraftingId);
        }
        return tag;
    }

    @Nullable
    public static ProcessingCompiledTask readFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        ItemStack patternDefinition = ItemStack.parseOptional(registries, tag.getCompound(NBT_PATTERN));
        List<GenericStack> inputs = readStacks(registries, tag.getList(NBT_INPUTS, Tag.TAG_COMPOUND));
        List<GenericStack> outputs = readStacks(registries, tag.getList(NBT_OUTPUTS, Tag.TAG_COMPOUND));
        if (patternDefinition.isEmpty() || inputs.isEmpty() || outputs.isEmpty()) {
            return null;
        }
        int totalTicks = tag.contains(NBT_TOTAL_TICKS) ? tag.getInt(NBT_TOTAL_TICKS) : 1;
        int remainingTicks = tag.contains(NBT_REMAINING_TICKS) ? tag.getInt(NBT_REMAINING_TICKS) : totalTicks;
        int executionCount = tag.contains(NBT_EXECUTION_COUNT) ? tag.getInt(NBT_EXECUTION_COUNT) : 1;
        ProcessingCompiledTask task = new ProcessingCompiledTask(
                patternDefinition,
                inputs,
                outputs,
                totalTicks,
                remainingTicks,
                executionCount
        );
        if (tag.contains(NBT_SOURCE_CRAFTING_ID)) {
            task.setSourceCraftingId(tag.getUUID(NBT_SOURCE_CRAFTING_ID));
        }
        return task;
    }

    private boolean canBuildOutputPayloadFor(int candidateExecutionCount) {
        int normalizedExecutionCount = normalizeExecutionCount(candidateExecutionCount);
        if (normalizedExecutionCount != candidateExecutionCount) {
            return false;
        }
        for (GenericStack output : outputsPerExecution) {
            if (output != null && output.amount() > 0L
                    && multiplyOrZero(output.amount(), normalizedExecutionCount) <= 0L) {
                return false;
            }
        }
        return true;
    }

    private static int normalizeExecutionCount(int executionCount) {
        return Math.max(1, executionCount);
    }

    private static long multiplyOrZero(long amount, int count) {
        if (amount <= 0L || count <= 0) {
            return 0L;
        }
        if (amount > Long.MAX_VALUE / count) {
            return 0L;
        }
        return amount * count;
    }

    private static ListTag writeStacks(HolderLookup.Provider registries, List<GenericStack> stacks) {
        ListTag tag = new ListTag();
        for (GenericStack stack : stacks) {
            if (stack != null) {
                tag.add(GenericStack.writeTag(registries, stack));
            }
        }
        return tag;
    }

    private static List<GenericStack> readStacks(HolderLookup.Provider registries, ListTag tag) {
        List<GenericStack> stacks = new ArrayList<>();
        for (Tag entry : tag) {
            if (entry instanceof CompoundTag stackTag) {
                GenericStack stack = GenericStack.readTag(registries, stackTag);
                if (stack != null) {
                    stacks.add(stack);
                }
            }
        }
        return List.copyOf(stacks);
    }
}
