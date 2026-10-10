package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
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
import java.util.UUID;

public final class ProcessingTask {

    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
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
    /** 整批工时（每发 tick × 份数）；与执行计数同宽，不得再夹在 int 上。 */
    private final long totalTicks;
    private long executionCount;
    private long remainingTicks;
    @Nullable
    private UUID sourceCraftingId;

    public ProcessingTask(
            ItemStack patternDefinition,
            List<GenericStack> selectedInputs,
            List<GenericStack> outputsPerExecution,
            long totalTicks,
            long remainingTicks,
            long executionCount
    ) {
        this.patternDefinition = patternDefinition == null ? ItemStack.EMPTY : patternDefinition.copy();
        this.selectedInputs = List.copyOf(selectedInputs == null ? List.of() : selectedInputs);
        this.outputsPerExecution = List.copyOf(outputsPerExecution == null ? List.of() : outputsPerExecution);
        this.totalTicks = Math.max(1L, totalTicks);
        this.remainingTicks = Math.max(0L, remainingTicks);
        this.executionCount = normalizeExecutionCount(executionCount);
        this.sourceCraftingId = null;
    }

    @Nullable
    public static ProcessingTask compile(
            IPatternDetails pattern,
            RecipeSignature signature,
            KeyCounter[] inputHolder,
            int totalTicks
    ) {
        if (pattern == null || signature == null || inputHolder == null) {
            return null;
        }
        // ICraftingMachine 接收的是 CPU 已抽出的整批材料；外部库存投料接口会重排稀疏槽，
        // AEProcessingPattern 在压缩槽时仅推送一份样板量，不能用它核算整批投入。
        List<GenericStack> received = collectReceivedInputs(inputHolder);
        List<RecipeSignatureInput> patternInputs = DirectProcessingStackSupport.patternInputs(pattern, signature);
        List<GenericStack> outputsPerExecution = DirectProcessingStackSupport.normalizeStacks(signature.outputs());
        if (received.isEmpty() || patternInputs.isEmpty() || outputsPerExecution.isEmpty()) return null;
        Long recipesPerPattern = DirectProcessingStackSupport.deriveExecutionCount(patternInputs, signature.inputs());
        Long patternCount = DirectProcessingStackSupport.deriveExecutionCount(
                DirectProcessingStackSupport.toSignatureInputs(received), patternInputs);
        if (recipesPerPattern == null || patternCount == null) return null;
        List<GenericStack> patternOutputs = DirectProcessingStackSupport.scaleStacks(outputsPerExecution, recipesPerPattern);
        if (patternOutputs.isEmpty() || !DirectProcessingStackSupport.stacksEqual(pattern.getOutputs(), patternOutputs)) return null;
        long executionCount = DirectProcessingStackSupport.multiplyOrZero(recipesPerPattern, patternCount);
        long ticks = DirectProcessingStackSupport.multiplyOrZero(Math.max(1L, totalTicks), patternCount);
        if (executionCount <= 0 || ticks <= 0
                || DirectProcessingStackSupport.scaleStacks(outputsPerExecution, executionCount).isEmpty()) return null;
        // 校验和入队前不修改 holder；拒收时调用方仍持有所有材料，接收成功后转移所有权。
        return new ProcessingTask(pattern.getDefinition().toStack(),
                DirectProcessingStackSupport.toGenericStacks(signature.inputs()), outputsPerExecution,
                ticks, ticks, executionCount);
    }

    /** 在兼容性缓存的合法配方中核验实收替换材料；拒收不改变 holder。 */
    @Nullable
    public static ProcessingTask compile(PatternCompatibility compatibility, KeyCounter[] holders, int ticks) {
        for (var signature : compatibility.signatures()) {
            var task = compile(compatibility.pattern(), signature, holders, ticks);
            if (task != null) return task;
        }
        return null;
    }

    @Nullable
    public static ProcessingTask compileNeoEcoBatch(
            IPatternDetails pattern,
            RecipeSignature signature,
            List<GenericStack> inputsPerExecution,
            List<GenericStack> outputsPerExecution,
            List<GenericStack> remaindersPerExecution,
            long count,
            int totalTicks,
            UUID sourceCraftingId
    ) {
        if (pattern == null || signature == null || sourceCraftingId == null
                || count <= 0L) {
            return null;
        }
        // NeoEco 的显式单次输入/输出是批次契约，批次入口不要求提供普通 IInput 视图。
        List<GenericStack> normalizedInputs = DirectProcessingStackSupport.normalizeStacks(inputsPerExecution);
        List<GenericStack> normalizedOutputs = DirectProcessingStackSupport.normalizeStacks(outputsPerExecution);
        Long recipeScale = DirectProcessingStackSupport.deriveExecutionCountFromStacks(normalizedOutputs, signature.outputs());
        if (recipeScale == null) return null;
        var expectedInputs = DirectProcessingStackSupport.scaleStacks(
                DirectProcessingStackSupport.toGenericStacks(signature.inputs()), recipeScale);
        if (expectedInputs.isEmpty() || !DirectProcessingStackSupport.stacksEqual(normalizedInputs, expectedInputs)) {
            return null;
        }
        List<GenericStack> payloadPerExecution = mergeBatchPayload(
                normalizedOutputs,
                remaindersPerExecution
        );
        // 份数本身不再夹 int：执行计数与工时都是 long，越界只剩 long 乘法溢出这一条
        // 真拒收理由（原来这里是 (int) count 的强转 + count > Integer.MAX_VALUE 判据）。
        long executionCount = count;
        long ticksPerExecution = Math.max(1L, totalTicks);
        if (normalizedInputs.isEmpty() || payloadPerExecution == null || payloadPerExecution.isEmpty()
                || executionCount > Long.MAX_VALUE / ticksPerExecution
                || DirectProcessingStackSupport.scaleStacks(payloadPerExecution, executionCount).isEmpty()) {
            return null;
        }
        long scaledTicks = ticksPerExecution * executionCount;
        ProcessingTask task = new ProcessingTask(
                pattern.getDefinition().toStack(),
                normalizedInputs,
                payloadPerExecution,
                scaledTicks,
                scaledTicks,
                executionCount
        );
        task.setSourceCraftingId(sourceCraftingId);
        return task;
    }

    @Nullable
    private static List<GenericStack> mergeBatchPayload(
            List<GenericStack> outputs,
            List<GenericStack> remainders
    ) {
        Map<AEKey, Long> merged = new LinkedHashMap<>();
        List<GenericStack> payload = new ArrayList<>(outputs);
        if (remainders != null) {
            payload.addAll(remainders);
        }
        for (GenericStack stack : payload) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            long previous = merged.getOrDefault(stack.what(), 0L);
            if (previous > Long.MAX_VALUE - stack.amount()) {
                return null;
            }
            merged.put(stack.what(), previous + stack.amount());
        }
        return merged.entrySet().stream()
                .map(entry -> new GenericStack(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<GenericStack> collectReceivedInputs(KeyCounter[] holders) {
        Map<AEKey, Long> amounts = new LinkedHashMap<>();
        for (KeyCounter holder : holders) {
            if (holder == null) return List.of();
            for (var entry : holder) {
                long amount = entry.getLongValue();
                if (amount == 0) continue;
                long previous = amounts.getOrDefault(entry.getKey(), 0L);
                if (amount < 0 || previous > Long.MAX_VALUE - amount) {
                    LOG.warn("Direct processing received input amount is invalid or overflows for {}", entry.getKey());
                    return List.of();
                }
                amounts.put(entry.getKey(), previous + amount);
            }
        }
        return amounts.entrySet().stream().map(entry -> new GenericStack(entry.getKey(), entry.getValue())).toList();
    }

    public void advance(int workUnits) {
        remainingTicks = Math.max(0L, remainingTicks - Math.max(1, workUnits));
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

    public long executionCount() {
        return executionCount;
    }

    @Nullable
    public UUID sourceCraftingId() {
        return sourceCraftingId;
    }

    public void setSourceCraftingId(@Nullable UUID sourceCraftingId) {
        this.sourceCraftingId = sourceCraftingId;
    }

    public CompoundTag writeToTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(NBT_PATTERN, patternDefinition.saveOptional(registries));
        tag.put(NBT_INPUTS, writeStacks(registries, selectedInputs));
        tag.put(NBT_OUTPUTS, writeStacks(registries, outputsPerExecution));
        tag.putLong(NBT_TOTAL_TICKS, totalTicks);
        tag.putLong(NBT_REMAINING_TICKS, remainingTicks);
        tag.putLong(NBT_EXECUTION_COUNT, executionCount);
        if (sourceCraftingId != null) {
            tag.putUUID(NBT_SOURCE_CRAFTING_ID, sourceCraftingId);
        }
        return tag;
    }

    @Nullable
    public static ProcessingTask readFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        if (tag.contains("pipelineSteps")) {
            LOG.error("发现已废弃的聚合流水任务，拒绝将其载入为普通批处理；请先使用旧版本结清任务");
            throw new IllegalStateException("存档含已废弃的聚合流水任务");
        }
        ItemStack patternDefinition = ItemStack.parseOptional(registries, tag.getCompound(NBT_PATTERN));
        List<GenericStack> inputs = readStacks(registries, tag.getList(NBT_INPUTS, Tag.TAG_COMPOUND));
        List<GenericStack> outputs = readStacks(registries, tag.getList(NBT_OUTPUTS, Tag.TAG_COMPOUND));
        if (patternDefinition.isEmpty() || inputs.isEmpty() || outputs.isEmpty()) {
            return null;
        }
        long totalTicks = tag.contains(NBT_TOTAL_TICKS) ? tag.getLong(NBT_TOTAL_TICKS) : 1L;
        long remainingTicks = tag.contains(NBT_REMAINING_TICKS) ? tag.getLong(NBT_REMAINING_TICKS) : totalTicks;
        long executionCount = tag.contains(NBT_EXECUTION_COUNT) ? tag.getLong(NBT_EXECUTION_COUNT) : 1L;
        ProcessingTask task = new ProcessingTask(
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

    private static long normalizeExecutionCount(long executionCount) {
        return Math.max(1L, executionCount);
    }

    private static long multiplyOrZero(long amount, long count) {
        if (amount <= 0L || count <= 0L) {
            return 0L;
        }
        if (amount > Long.MAX_VALUE / count) {
            LOG.warn("Processing output amount {} x {} overflows long: payload dropped", amount, count);
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
