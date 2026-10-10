package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchStep;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.WideKeyCounter;
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
    private static final String NBT_STEPS = "pipelineSteps";
    private static final String NBT_STEP_CURSOR = "pipelineStepCursor";
    private static final String NBT_BUFFER = "pipelineBuffer";

    private final ItemStack patternDefinition;
    private List<GenericStack> selectedInputs;
    private final List<GenericStack> outputsPerExecution;
    private final int totalTicks;
    private int executionCount;
    private int remainingTicks;
    @Nullable
    private UUID sourceCraftingId;

    // ---- Phase 5 分步流水（仅聚合任务启用；空列表 = 旧单步模式）----
    /** 步序列（批次量纲），NBT 往返保留。 */
    private List<HostBatchStep> steps;
    /** 每步的 tick 预算（totalTicks 按 executionCount 加权分配）。 */
    private int[] stepTicks;
    private int stepCursor;
    private int currentStepRemainingTicks;
    /** 当前步外部输入已就位（第一步由 push 抽取，后续步由宿主运行中抽取）。 */
    private boolean currentStepFed;
    /**
     * 任务自持原料池：步完成产出与运行中网络抽取都进这里，步启动统一
     * 消费——链内中间品与外部输入同池，终态剩余即最终产出与余料。
     */
    private final WideKeyCounter intermediateBuffer = new WideKeyCounter();

    public ProcessingTask(
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
        this.steps = List.of();
        this.stepTicks = new int[0];
        this.stepCursor = 0;
        this.currentStepRemainingTicks = 0;
        this.currentStepFed = false;
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
        KeyCounter[] originalInputs = copyInputHolders(inputHolder);
        List<RecipeSignatureInput> selectedInputs = DirectProcessingStackSupport.toSignatureInputs(
                collectSelectedInputs(pattern, inputHolder)
        );
        List<GenericStack> normalizedPatternOutputs = DirectProcessingStackSupport.normalizeStacks(pattern.getOutputs());
        List<GenericStack> outputsPerExecution = DirectProcessingStackSupport.normalizeStacks(signature.outputs());
        if (selectedInputs.isEmpty() || outputsPerExecution.isEmpty() || normalizedPatternOutputs.isEmpty()) {
            restoreInputHolders(inputHolder, originalInputs);
            return null;
        }
        Integer executionCount = DirectProcessingStackSupport.deriveExecutionCount(selectedInputs, signature.inputs());
        if (executionCount == null || executionCount <= 0) {
            restoreInputHolders(inputHolder, originalInputs);
            return null;
        }
        List<GenericStack> scaledOutputs = DirectProcessingStackSupport.scaleStacks(outputsPerExecution, executionCount);
        if (DirectProcessingStackSupport.stacksEqual(normalizedPatternOutputs, scaledOutputs)) {
            return new ProcessingTask(
                    pattern.getDefinition().toStack(),
                    DirectProcessingStackSupport.toGenericStacks(signature.inputs()),
                    outputsPerExecution,
                    totalTicks,
                    totalTicks,
                    executionCount
            );
        }
        if (executionCount == 1
                || !DirectProcessingStackSupport.stacksEqual(normalizedPatternOutputs, outputsPerExecution)) {
            restoreInputHolders(inputHolder, originalInputs);
            return null;
        }
        long scaledTicks = Math.min(Integer.MAX_VALUE, (long) totalTicks * executionCount);
        return new ProcessingTask(
                pattern.getDefinition().toStack(),
                DirectProcessingStackSupport.toGenericStacks(signature.inputs()),
                outputsPerExecution,
                (int) scaledTicks,
                (int) scaledTicks,
                executionCount
        );
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
                || count <= 0L || count > Integer.MAX_VALUE) {
            return null;
        }
        List<GenericStack> normalizedInputs = DirectProcessingStackSupport.normalizeStacks(inputsPerExecution);
        if (!DirectProcessingStackSupport.signatureInputsEqual(
                DirectProcessingStackSupport.toSignatureInputs(normalizedInputs),
                signature.inputs())) {
            return null;
        }
        List<GenericStack> normalizedOutputs = DirectProcessingStackSupport.normalizeStacks(outputsPerExecution);
        if (!DirectProcessingStackSupport.stacksEqual(normalizedOutputs, signature.outputs())) {
            return null;
        }
        List<GenericStack> payloadPerExecution = mergeBatchPayload(
                normalizedOutputs,
                remaindersPerExecution
        );
        int executionCount = (int) count;
        long ticksPerExecution = Math.max(1, totalTicks);
        if (normalizedInputs.isEmpty() || payloadPerExecution == null || payloadPerExecution.isEmpty()
                || ticksPerExecution > Integer.MAX_VALUE / count
                || DirectProcessingStackSupport.scaleStacks(payloadPerExecution, executionCount).isEmpty()) {
            return null;
        }
        int scaledTicks = (int) (ticksPerExecution * count);
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

    /**
     * Phase 5 分步流水：push 只抽取第一步的外部输入（步输入 − 空缓冲），
     * 后续步的外部输入由宿主运行中从网络流式供给——链条与上游真正重叠。
     * 第一步外部输入不齐即拒绝（CPU 将 inputHolder 剩余退回网络）。
     */
    @Nullable
    public static ProcessingTask compileAggregated(
            HostBatchPattern aggregatedPattern,
            KeyCounter[] inputHolder,
            int totalTicks
    ) {
        if (aggregatedPattern == null || inputHolder == null) {
            return null;
        }
        List<HostBatchStep> steps = aggregatedPattern.steps();
        List<GenericStack> outputs = aggregatedPattern.aggregatedOutputs();
        if (steps == null || steps.isEmpty() || outputs.isEmpty()) {
            return null;
        }
        long executionTotal = 0L;
        for (HostBatchStep step : steps) {
            if (step == null || step.executionCount() <= 0L) {
                return null;
            }
            executionTotal += step.executionCount();
        }

        ProcessingTask task = new ProcessingTask(
                aggregatedPattern.getDefinition().toStack(),
                List.of(),
                outputs,
                totalTicks,
                totalTicks,
                (int) Math.min(Integer.MAX_VALUE, Math.max(1L, executionTotal))
        );
        task.initPipeline(steps);

        // push 契约：inputHolder 已由 CPU 抽出；这里只收走第一步需要量，
        // 剩余留在 holder 由 AE2 退回网络。
        Map<AEKey, Long> firstNeeds = task.currentStepNeeds();
        KeyCounter[] originalInputs = copyInputHolders(inputHolder);
        Map<AEKey, Long> collected = new LinkedHashMap<>();
        for (Map.Entry<AEKey, Long> need : firstNeeds.entrySet()) {
            long extracted = 0L;
            for (KeyCounter holder : inputHolder) {
                long available = holder.get(need.getKey());
                if (available > 0) {
                    long toExtract = Math.min(available, need.getValue() - extracted);
                    holder.remove(need.getKey(), toExtract);
                    extracted += toExtract;
                    if (extracted >= need.getValue()) {
                        break;
                    }
                }
            }
            if (extracted != need.getValue()) {
                restoreInputHolders(inputHolder, originalInputs);
                return null;
            }
            collected.put(need.getKey(), need.getValue());
        }
        task.feedExternalInputs(collected);
        task.selectedInputs = collected.entrySet().stream()
                .map(entry -> new GenericStack(entry.getKey(), entry.getValue()))
                .toList();
        return task;
    }

    /**
     * 步序列与 tick 预算初始化：totalTicks 按 executionCount 加权分配到步，
     * 整除余数给末步——总 tick 消耗与整批模式一致。
     */
    private void initPipeline(List<HostBatchStep> pipelineSteps) {
        this.steps = List.copyOf(pipelineSteps);
        this.stepTicks = new int[this.steps.size()];
        long weightedTotal = 0L;
        for (HostBatchStep step : this.steps) {
            weightedTotal += step.executionCount();
        }
        int allocated = 0;
        for (int index = 0; index < this.steps.size(); index++) {
            if (index == this.steps.size() - 1) {
                this.stepTicks[index] = Math.max(1, this.totalTicks - allocated);
            } else {
                long share = this.totalTicks * this.steps.get(index).executionCount() / Math.max(1L, weightedTotal);
                this.stepTicks[index] = (int) Math.max(1L, share);
                allocated += this.stepTicks[index];
            }
        }
        this.stepCursor = 0;
        this.currentStepRemainingTicks = this.stepTicks[0];
        this.currentStepFed = false;
    }

    // ---- 分步流水 API（仅 steps 非空时生效）----

    /** 是否处于分步流水模式。 */
    public boolean isPipelined() {
        return !steps.isEmpty();
    }

    /**
     * 当前步还缺的原料：步输入 − 缓冲（键级，逐键 max(0, need − buffer)）。
     * 链内中间品与外部输入统一从缓冲出——缓冲由上游步产出与运行中网络
     * 抽取共同填充。
     */
    public Map<AEKey, Long> currentStepNeeds() {
        if (!isPipelined() || stepCursor >= steps.size()) {
            return Map.of();
        }
        Map<AEKey, Long> needs = new LinkedHashMap<>();
        for (GenericStack input : steps.get(stepCursor).stepInputs()) {
            if (input == null || input.what() == null || input.amount() <= 0L) {
                continue;
            }
            long buffered = intermediateBuffer.getAmount(input.what()).fastValue();
            long missing = input.amount() - buffered;
            if (missing > 0L) {
                needs.merge(input.what(), missing, Long::sum);
            }
        }
        return needs;
    }

    /** 当前步外部输入是否已就位（已 feed 或无缺口）。 */
    public boolean currentStepFed() {
        if (!isPipelined()) {
            return true;
        }
        return currentStepFed || currentStepNeeds().isEmpty();
    }

    /** 运行中网络抽取（两阶段提交的 MODULATE 半程）写入缓冲并解锁当前步。 */
    public void feedExternalInputs(Map<AEKey, Long> fed) {
        for (Map.Entry<AEKey, Long> entry : fed.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                intermediateBuffer.add(entry.getKey(), entry.getValue());
            }
        }
        currentStepFed = true;
    }

    /** 消费缓冲并启动下一步的推进（调用方已确认当前步完成）。 */
    private void completeCurrentStep() {
        HostBatchStep step = steps.get(stepCursor);
        if (step.stepPrimaryOutput() != null
                && step.stepPrimaryOutput().what() != null
                && step.stepPrimaryOutput().amount() > 0L) {
            intermediateBuffer.add(step.stepPrimaryOutput().what(), step.stepPrimaryOutput().amount());
        }
        for (GenericStack remainder : step.stepRemainders()) {
            if (remainder != null && remainder.what() != null && remainder.amount() > 0L) {
                intermediateBuffer.add(remainder.what(), remainder.amount());
            }
        }
        // 消耗本步输入（缓冲先扣——链内中间品与外部抽取同池）
        for (GenericStack input : step.stepInputs()) {
            if (input == null || input.what() == null || input.amount() <= 0L) {
                continue;
            }
            intermediateBuffer.add(input.what(), -input.amount());
        }
        stepCursor++;
        if (stepCursor < steps.size()) {
            currentStepRemainingTicks = stepTicks[stepCursor];
            currentStepFed = false;
        }
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

    private static List<GenericStack> collectSelectedInputs(IPatternDetails pattern, KeyCounter[] inputHolder) {
        List<GenericStack> inputs = new ArrayList<>();
        pattern.pushInputsToExternalInventory(inputHolder, (AEKey key, long amount) -> {
            if (key != null && amount > 0) {
                inputs.add(new GenericStack(key, amount));
            }
        });
        return List.copyOf(inputs);
    }

    private static KeyCounter[] copyInputHolders(KeyCounter[] inputHolder) {
        KeyCounter[] copy = new KeyCounter[inputHolder.length];
        for (int index = 0; index < inputHolder.length; index++) {
            KeyCounter original = inputHolder[index];
            KeyCounter holderCopy = new KeyCounter();
            if (original != null) {
                for (var entry : original) {
                    holderCopy.add(entry.getKey(), entry.getLongValue());
                }
            }
            copy[index] = holderCopy;
        }
        return copy;
    }

    private static void restoreInputHolders(KeyCounter[] inputHolder, KeyCounter[] snapshot) {
        for (int index = 0; index < inputHolder.length; index++) {
            KeyCounter holder = inputHolder[index];
            if (holder == null) {
                continue;
            }
            holder.reset();
            for (var entry : snapshot[index]) {
                holder.add(entry.getKey(), entry.getLongValue());
            }
        }
    }

    public void advance(int workUnits) {
        if (isPipelined()) {
            // 当前步外部输入未就位 → 挂起（不消耗 tick，等待宿主供给）
            if (!currentStepFed()) {
                return;
            }
            currentStepRemainingTicks -= Math.max(1, workUnits);
            while (currentStepRemainingTicks <= 0 && stepCursor < steps.size()) {
                completeCurrentStep();
            }
            return;
        }
        remainingTicks = Math.max(0, remainingTicks - Math.max(1, workUnits));
    }

    public boolean isReadyToComplete() {
        if (isPipelined()) {
            return stepCursor >= steps.size();
        }
        return remainingTicks <= 0;
    }

    public List<GenericStack> buildOutputPayload() {
        if (isPipelined()) {
            // 终态缓冲 = 净产出（aggregatedOutputs + remainders）——Σ产出 − Σ消耗
            List<GenericStack> payload = new ArrayList<>();
            for (AEKey key : intermediateBuffer.keys()) {
                long amount = Ae2Amounts.toLong(intermediateBuffer.getAmount(key));
                if (amount > 0L) {
                    payload.add(new GenericStack(key, amount));
                }
            }
            return List.copyOf(payload);
        }
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
        if (isPipelined()) {
            ListTag stepsTag = new ListTag();
            for (HostBatchStep step : steps) {
                stepsTag.add(step.writeToTag(registries));
            }
            tag.put(NBT_STEPS, stepsTag);
            tag.putInt(NBT_STEP_CURSOR, stepCursor);
            tag.put(NBT_BUFFER, writeBuffer(registries));
        }
        return tag;
    }

    private ListTag writeBuffer(HolderLookup.Provider registries) {
        ListTag listTag = new ListTag();
        for (AEKey key : intermediateBuffer.keys()) {
            long amount = Ae2Amounts.toLong(intermediateBuffer.getAmount(key));
            if (amount != 0L) {
                listTag.add(GenericStack.writeTag(registries, new GenericStack(key, amount)));
            }
        }
        return listTag;
    }

    @Nullable
    public static ProcessingTask readFromTag(CompoundTag tag, HolderLookup.Provider registries) {
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
        if (tag.contains(NBT_STEPS)) {
            List<HostBatchStep> steps = new ArrayList<>();
            ListTag stepsTag = tag.getList(NBT_STEPS, Tag.TAG_COMPOUND);
            for (Tag entry : stepsTag) {
                if (entry instanceof CompoundTag compoundTag) {
                    steps.add(HostBatchStep.readFromTag(compoundTag, registries));
                }
            }
            if (!steps.isEmpty()) {
                task.restorePipeline(steps, tag.getInt(NBT_STEP_CURSOR));
                task.readBuffer(tag.getList(NBT_BUFFER, Tag.TAG_COMPOUND), registries);
            }
        }
        return task;
    }

    /** 恢复游标与 tick 预算（步内容已反序列化；缓冲随后注入）。 */
    private void restorePipeline(List<HostBatchStep> pipelineSteps, int cursor) {
        this.steps = List.copyOf(pipelineSteps);
        this.stepTicks = new int[this.steps.size()];
        long weightedTotal = 0L;
        for (HostBatchStep step : this.steps) {
            weightedTotal += step.executionCount();
        }
        int allocated = 0;
        for (int index = 0; index < this.steps.size(); index++) {
            if (index == this.steps.size() - 1) {
                this.stepTicks[index] = Math.max(1, this.totalTicks - allocated);
            } else {
                long share = this.totalTicks * this.steps.get(index).executionCount() / Math.max(1L, weightedTotal);
                this.stepTicks[index] = (int) Math.max(1L, share);
                allocated += this.stepTicks[index];
            }
        }
        this.stepCursor = Math.max(0, Math.min(cursor, this.steps.size()));
        this.currentStepRemainingTicks = this.stepCursor < this.steps.size() ? this.stepTicks[this.stepCursor] : 0;
        this.currentStepFed = false;
    }

    private void readBuffer(ListTag listTag, HolderLookup.Provider registries) {
        for (Tag entry : listTag) {
            if (entry instanceof CompoundTag compoundTag) {
                GenericStack stack = GenericStack.readTag(registries, compoundTag);
                if (stack != null && stack.what() != null && stack.amount() != 0L) {
                    intermediateBuffer.add(stack.what(), stack.amount());
                }
            }
        }
        // 恢复后当前步视为未就位——宿主按缓冲余量补足外部输入（防重启重推）
        this.currentStepFed = false;
    }


    private static int normalizeExecutionCount(int executionCount) {
        return Math.max(1, executionCount);
    }

    private static long multiplyOrZero(long amount, int count) {
        if (amount <= 0L || count <= 0) {
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
