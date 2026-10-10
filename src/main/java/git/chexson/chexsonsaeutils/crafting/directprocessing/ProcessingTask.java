package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchStep;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
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
    /** 整批工时（每发 tick × 份数）；与执行计数同宽，不得再夹在 int 上。 */
    private final long totalTicks;
    private long executionCount;
    private long remainingTicks;
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
        // NeoEco 的显式单次输入/输出是批次契约，聚合样板不要求提供普通 IInput 视图。
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
                // Σ步执行数本就是 long：这里曾按 int 夹一次，把聚合任务的份数读数压在 2.1e9。
                Math.max(1L, executionTotal)
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
        this.stepTicks = allocateStepTicks();
        this.stepCursor = 0;
        this.currentStepRemainingTicks = this.stepTicks[0];
        this.currentStepFed = false;
    }

    /**
     * totalTicks 按步执行数加权拆成每步 tick 预算，整除余数给末步——总 tick 消耗与
     * 整批模式一致。步预算仍是 int：它受聚合样板的 int 工时口径
     * （{@code HostBatchPattern.totalTicks()}）约束，与本次放宽的执行份数无关。
     */
    private int[] allocateStepTicks() {
        int[] ticks = new int[this.steps.size()];
        long weightedTotal = 0L;
        for (HostBatchStep step : this.steps) {
            weightedTotal += step.executionCount();
        }
        long allocated = 0L;
        for (int index = 0; index < this.steps.size(); index++) {
            if (index == this.steps.size() - 1) {
                ticks[index] = (int) Math.max(1L, saturatingStepTicks(this.totalTicks - allocated));
            } else {
                long share = this.totalTicks * this.steps.get(index).executionCount() / Math.max(1L, weightedTotal);
                ticks[index] = (int) Math.max(1L, saturatingStepTicks(share));
                allocated += ticks[index];
            }
        }
        return ticks;
    }

    private static long saturatingStepTicks(long ticks) {
        return Math.min((long) Integer.MAX_VALUE, Math.max(0L, ticks));
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
            PlanAmount bufferedAmount = intermediateBuffer.getAmount(input.what());
            // 缓冲越出 long 时 fastValue() 恒为 0（那是规范化留下的空位，不是"一格都没有"），
            // 直读会把满缓冲读成空缓冲、缺口按全额再要一次料。宽值一律按"够这一口"读。
            long buffered = bufferedAmount.isFast() ? bufferedAmount.fastValue() : Long.MAX_VALUE;
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
        remainingTicks = Math.max(0L, remainingTicks - Math.max(1, workUnits));
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

    /**
     * 缓冲落盘：老 {@code long} 位存饱和读数，越出 {@code long} 的那一格多挂
     * {@link WideCraftingInventory#NBT_ENTRY_AMOUNT_WIDE}（BigInteger 补码全量）。
     *
     * <p>编码形状与 {@code WideCraftingInventory.writeToNBT} 逐字同一条（同一覆盖键、同一"饱和底 +
     * 全量覆盖"规则），不新造第 8 套落盘格式。旧写法只落 {@code Ae2Amounts.toLong} 那份饱和读数，
     * 越界那一截在第一次存盘就永久消失——磁盘不是 AE2 出口，不该在这里夹顶。
     */
    private ListTag writeBuffer(HolderLookup.Provider registries) {
        ListTag listTag = new ListTag();
        for (AEKey key : intermediateBuffer.keys()) {
            var exact = intermediateBuffer.getExact(key);
            if (exact.signum() == 0L) {
                continue;
            }
            boolean insideLong = exact.bitLength() <= 63;
            long view = insideLong ? exact.longValueExact()
                    : Ae2Amounts.toLong(PlanAmount.of(exact));
            CompoundTag entry = GenericStack.writeTag(registries, new GenericStack(key, view));
            if (!insideLong) {
                entry.putByteArray(WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE, exact.toByteArray());
            }
            listTag.add(entry);
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
        this.stepTicks = allocateStepTicks();
        this.stepCursor = Math.max(0, Math.min(cursor, this.steps.size()));
        this.currentStepRemainingTicks = this.stepCursor < this.steps.size() ? this.stepTicks[this.stepCursor] : 0;
        this.currentStepFed = false;
    }

    /** 读回缓冲：条目带覆盖键就按补码还原精确值，否则按老 {@code long} 值走（与 {@code writeToNBT} 成对）。 */
    private void readBuffer(ListTag listTag, HolderLookup.Provider registries) {
        for (Tag entry : listTag) {
            if (entry instanceof CompoundTag compoundTag) {
                GenericStack stack = GenericStack.readTag(registries, compoundTag);
                if (stack == null || stack.what() == null) {
                    continue;
                }
                BigInteger exact = compoundTag.contains(
                        WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)
                        ? new BigInteger(compoundTag.getByteArray(WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE))
                        : BigInteger.valueOf(stack.amount());
                if (exact.signum() != 0L) {
                    intermediateBuffer.add(stack.what(), exact);
                }
            }
        }
        // 恢复后当前步视为未就位——宿主按缓冲余量补足外部输入（防重启重推）
        this.currentStepFed = false;
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
