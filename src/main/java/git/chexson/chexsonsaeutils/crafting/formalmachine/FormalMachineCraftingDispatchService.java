package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.CompiledTask;
import git.chexson.chexsonsaeutils.blockentity.crafting.DynamicExecutionBudgetModel;
import git.chexson.chexsonsaeutils.blockentity.crafting.FormalMachineCompletionTemplateHelper;
import git.chexson.chexsonsaeutils.blockentity.crafting.TaskCompletionRoute;
import git.chexson.chexsonsaeutils.config.FormalMachineCraftingDispatchFeatureGate;
import git.chexson.chexsonsaeutils.crafting.SourceCpuHandle;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingLane;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobTaskProgressAccessor;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FormalMachineCraftingDispatchService {

    private static final double POWER_EPSILON = 0.01D;
    private static final ConcurrentMap<UUID, SourceCpuHandle> SOURCE_CPUS_BY_JOB = new ConcurrentHashMap<>();
    private static final Comparator<IFormalMachineCraftingProvider> PROVIDER_ORDER = Comparator
            .comparingInt(IFormalMachineCraftingProvider::getDispatchBackpressure)
            .thenComparing(IFormalMachineCraftingProvider::getMachineIdentity);

    private FormalMachineCraftingDispatchService() {
    }

    public static void dispatchOnServerEndTick(
            IGrid grid,
            Iterable<CraftingCPUCluster> craftingCpuClusters,
            CraftingService craftingService,
            IEnergyService energyService
    ) {
        if (!FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()
                || grid == null
                || craftingCpuClusters == null
                || craftingService == null
                || energyService == null) {
            return;
        }

        for (CraftingCPUCluster cpu : craftingCpuClusters) {
            dispatchCpu(cpu, craftingService, energyService);
        }
    }

    public static void onInsertIntoCpus(
            IGrid grid,
            Iterable<CraftingCPUCluster> craftingCpuClusters,
            @Nullable AEKey what,
            long amount,
            Actionable type
    ) {
    }

    public static void onUpdateCpuClusters(IGrid grid, Iterable<CraftingCPUCluster> craftingCpuClusters) {
    }

    public static void onSubmitJobHead() {
    }

    private static void debugFormalDispatch(String message) {
        if (Boolean.getBoolean("chexsonsaeutils.debugFormalDispatch")) {
            System.out.println("[CHEXSONSAEUTILS][FORMAL_DISPATCH] " + message);
        }
    }

    public static void onSubmitJobTail(
            CraftingService craftingService,
            @Nullable ICraftingPlan job,
            @Nullable ICraftingRequester requestingMachine,
            @Nullable ICraftingCPU targetCpu,
            @Nullable ICraftingSubmitResult submitResult
    ) {
        if (craftingService == null || job == null || submitResult == null || !submitResult.successful()) {
            return;
        }
        if (!isFormalMachinePlan(craftingService, job)) {
            return;
        }
        AbstractHighCapacityCraftingHostBlockEntity host = resolveFormalMachineRequester(requestingMachine);
        if (host == null) {
            host = resolveFormalMachinePlanHost(craftingService, job);
        }
        UUID craftingId = submitResult.link() == null
                ? findSubmittedCpuCraftingId(craftingService, targetCpu, job)
                : submitResult.link().getCraftingID();
        if (craftingId == null) {
            return;
        }
        SourceCpuHandle sourceCpu = resolveSubmittedSourceCpuHandle(craftingService, targetCpu, craftingId, job);
        if (sourceCpu != null) {
            SOURCE_CPUS_BY_JOB.put(craftingId, sourceCpu);
        }
        FormalMachineCraftingTimingService.beginSubmittedJob(
                craftingId,
                host,
                job
        );
    }

    public static long getSourceCpuRequestedAmount(@Nullable UUID craftingId, @Nullable AEKey what) {
        return getSourceCpuRequestedAmount(null, craftingId, what);
    }

    public static long getSourceCpuRequestedAmount(
            @Nullable CraftingService craftingService,
            @Nullable UUID craftingId,
            @Nullable AEKey what
    ) {
        SourceCpuHandle cpu = liveSourceCpu(craftingService, craftingId);
        if (cpu == null || what == null) {
            return -1L;
        }
        return Math.max(0L, cpu.getRequestedAmount(what));
    }

    public static long insertIntoSourceCpu(
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable type,
            IActionSource source
    ) {
        return insertIntoSourceCpu(null, craftingId, what, amount, type, source);
    }

    public static long insertIntoSourceCpu(
            @Nullable CraftingService craftingService,
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable type,
            IActionSource source
    ) {
        SourceCpuHandle cpu = liveSourceCpu(craftingService, craftingId);
        if (cpu == null || what == null || amount <= 0L) {
            return -1L;
        }
        return Math.max(0L, cpu.insert(what, amount, type, source));
    }

    @Nullable
    public static SourceCpuHandle getSourceCpuHandle(
            @Nullable CraftingService craftingService,
            @Nullable UUID craftingId
    ) {
        return liveSourceCpu(craftingService, craftingId);
    }

    public static void clearSourceCpu(@Nullable UUID craftingId) {
        if (craftingId != null) {
            SOURCE_CPUS_BY_JOB.remove(craftingId);
        }
    }

    private static int dispatchCpu(
            CraftingCPUCluster cpu,
            CraftingService craftingService,
            IEnergyService energyService
    ) {
        if (cpu == null || !cpu.isActive()) {
            return 0;
        }

        CraftingCpuLogic logic = cpu.craftingLogic;
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) logic;
        ExecutingCraftingJob job = logicAccessor.getJob();
        if (job == null) {
            return 0;
        }

        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) job;
        if (jobAccessor.isSuspended() || cpu.getLevel() == null) {
            return 0;
        }

        int acceptedBatchCount = 0;
        List<DeferredTaskProgressAddition> deferredTaskProgressAdditions = new ArrayList<>();
        Iterator<Map.Entry<IPatternDetails, Object>> iterator = jobAccessor.getTasks().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<IPatternDetails, Object> entry = iterator.next();
            if (tryDispatchTask(
                    cpu,
                    craftingService,
                    energyService,
                    logicAccessor,
                    jobAccessor,
                    iterator,
                    entry,
                    deferredTaskProgressAdditions
            )) {
                acceptedBatchCount++;
            }
        }
        applyDeferredTaskProgressAdditions(jobAccessor, deferredTaskProgressAdditions);
        return acceptedBatchCount;
    }

    private static boolean tryDispatchTask(
            CraftingCPUCluster cpu,
            CraftingService craftingService,
            IEnergyService energyService,
            CraftingCpuLogicAccessor logicAccessor,
            ExecutingCraftingJobAccessor jobAccessor,
            Iterator<Map.Entry<IPatternDetails, Object>> iterator,
            Map.Entry<IPatternDetails, Object> entry,
            List<DeferredTaskProgressAddition> deferredTaskProgressAdditions
    ) {
        if (!(entry.getValue() instanceof ExecutingCraftingJobTaskProgressAccessor taskProgress)) {
            return false;
        }
        if (taskProgress.getValue() <= 0) {
            iterator.remove();
            return false;
        }
        if (!(entry.getKey() instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
            return false;
        }

        IFormalMachineCraftingProvider provider = selectProvider(craftingService, entry.getKey());
        if (provider == null) {
            debugFormalDispatch("skip provider=null pattern=" + entry.getKey() + " progress=" + taskProgress.getValue());
            return false;
        }
        DynamicExecutionBudgetModel budgetModel = providerBudgetModel(provider);
        if (!budgetModel.canDispatchExecution()) {
            return false;
        }

        BatchExtraction batchExtraction = extractBatch(
                cpu,
                logicAccessor.getInventory(),
                supportedPattern,
                provider,
                taskProgress.getValue(),
                budgetModel
        );
        if (batchExtraction == null) {
            debugFormalDispatch("skip batch=null pattern=" + entry.getKey() + " progress=" + taskProgress.getValue());
            return false;
        }
        if (!provider.canAcceptBatchKey(batchExtraction.batchKey())) {
            batchExtraction.reinject(logicAccessor.getInventory());
            return false;
        }

        double requiredPower = batchExtraction.patternPower() * batchExtraction.logicalExecutions();
        if (energyService.extractAEPower(requiredPower, Actionable.SIMULATE, PowerMultiplier.CONFIG)
                < requiredPower - POWER_EPSILON) {
            batchExtraction.reinject(logicAccessor.getInventory());
            return false;
        }

        FormalMachineFastPathResult result = provider.offerFastBatch(new FormalMachineBatchRequest(
                batchExtraction.compiledTask(),
                batchExtraction.batchKey(),
                batchExtraction.logicalExecutions(),
                jobAccessor.getLink() == null ? null : jobAccessor.getLink().getCraftingID(),
                cpu.toString(),
                jobAccessor.getLink() == null ? null : jobAccessor.getLink().getCraftingID(),
                budgetModel.dispatchTarget()
        ));
        if (result.disposition() != FormalMachineFastPathDisposition.ACCEPTED
                || result.acceptedExecutions() != batchExtraction.logicalExecutions()) {
            batchExtraction.reinject(logicAccessor.getInventory());
            return false;
        }

        energyService.extractAEPower(requiredPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
        registerAcceptedBatch(cpu, logicAccessor, jobAccessor, provider, batchExtraction);
        long remainingExecutions = taskProgress.getValue() - batchExtraction.taskExecutionsConsumed();
        taskProgress.setValue(remainingExecutions);
        if (remainingExecutions <= 0) {
            iterator.remove();
        }
        if (batchExtraction.deferredPattern() != null && batchExtraction.deferredPatternExecutions() > 0L) {
            debugFormalDispatch("defer pattern=" + batchExtraction.deferredPattern()
                    + " executions=" + batchExtraction.deferredPatternExecutions()
                    + " acceptedLogical=" + batchExtraction.logicalExecutions()
                    + " consumed=" + batchExtraction.taskExecutionsConsumed());
            deferredTaskProgressAdditions.add(new DeferredTaskProgressAddition(
                    batchExtraction.deferredPattern(),
                    batchExtraction.deferredPatternExecutions()
            ));
        }
        return true;
    }

    @Nullable
    private static IFormalMachineCraftingProvider selectProvider(CraftingService craftingService, IPatternDetails patternDetails) {
        IFormalMachineCraftingProvider bestProvider = null;
        for (ICraftingProvider provider : craftingService.getProviders(patternDetails)) {
            if (!(provider instanceof IFormalMachineCraftingProvider formalProvider)) {
                continue;
            }
            if (!formalProvider.supportsFastBatch(patternDetails)) {
                continue;
            }
            if (bestProvider == null || PROVIDER_ORDER.compare(formalProvider, bestProvider) < 0) {
                bestProvider = formalProvider;
            }
        }
        return bestProvider;
    }

    @Nullable
    private static BatchExtraction extractBatch(
            CraftingCPUCluster cpu,
            ListCraftingInventory inventory,
            IMolecularAssemblerSupportedPattern supportedPattern,
            IFormalMachineCraftingProvider provider,
            long remainingTaskExecutions,
            DynamicExecutionBudgetModel budgetModel
    ) {
        if (!budgetModel.canDispatchExecution()) {
            return null;
        }
        if (supportedPattern instanceof IFormalMachineScaledPattern scaledPattern) {
            return extractScaledBatch(cpu, inventory, scaledPattern, provider);
        }
        SingleExtraction firstExtraction = extractSingle(inventory, cpu.getLevel(), supportedPattern);
        if (firstExtraction == null) {
            return null;
        }

        double patternPower = CraftingCpuHelper.calculatePatternPower(firstExtraction.reinjectableInputs());
        CompiledTask compiledTask = compileForProvider(supportedPattern, provider, firstExtraction.inputs());
        if (compiledTask == null) {
            CraftingCpuHelper.reinjectPatternInputs(inventory, firstExtraction.reinjectableInputs());
            return null;
        }

        FormalMachineBatchKey batchKey = FormalMachineBatchKey.fromCompiledTask(supportedPattern, compiledTask);
        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.addAll(firstExtraction.expectedOutputs());
        KeyCounter expectedContainerItems = new KeyCounter();
        expectedContainerItems.addAll(firstExtraction.expectedContainerItems());
        List<KeyCounter[]> reinjectableInputs = new ArrayList<>();
        reinjectableInputs.add(firstExtraction.reinjectableInputs());
        int logicalExecutions = 1;
        int maxLogicalExecutions = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, remainingTaskExecutions));

        FormalMachineCompletionTemplateHelper.CompletionTemplate template =
                FormalMachineCompletionTemplateHelper.probeStableTemplate(cpu.getLevel(), supportedPattern, compiledTask);
        if (template != null) {
            compiledTask.setCompletionTemplate(template.primary(), template.remainders());
            if (provider instanceof AbstractHighCapacityCraftingHostBlockEntity host) {
                host.recordTemplatedDispatchHitForTest();
            }
        } else {
            compiledTask.setSupportsTemplatedCompletion(
                    FormalMachineCompletionTemplateHelper.supportsTemplateForPattern(supportedPattern)
            );
        }

        if (expectedContainerItems.isEmpty() && maxLogicalExecutions > 1) {
            int maxAdditionalExecutions = Math.min(
                    BulkPatternExtractionPlanner.capAdditionalExecutionsForTask(compiledTask, remainingTaskExecutions),
                    Math.max(0, maxLogicalExecutions - logicalExecutions)
            );
            BulkPatternExtractionPlanner.BulkExtractionResult bulkExtraction =
                    BulkPatternExtractionPlanner.extractAdditionalExecutions(
                            inventory,
                            firstExtraction.reinjectableInputs(),
                            maxAdditionalExecutions
                    );
            if (bulkExtraction != null && bulkExtraction.logicalExecutions() > 0) {
                if (compiledTask.tryAppendExecutionCount(bulkExtraction.logicalExecutions())) {
                    addScaled(expectedOutputs, firstExtraction.expectedOutputs(), bulkExtraction.logicalExecutions());
                    reinjectableInputs.add(bulkExtraction.reinjectableInputs());
                    logicalExecutions += bulkExtraction.logicalExecutions();
                    if (provider instanceof AbstractHighCapacityCraftingHostBlockEntity host) {
                        host.recordBulkExtractionResult(bulkExtraction.logicalExecutions());
                    }
                } else {
                    reinjectCounters(inventory, bulkExtraction.reinjectableInputs());
                }
            } else if (provider instanceof AbstractHighCapacityCraftingHostBlockEntity host) {
                host.recordBulkExtractionFallback();
            }
        }

        while (logicalExecutions < remainingTaskExecutions && logicalExecutions < maxLogicalExecutions) {
            if (!provider.canAcceptBatchKey(batchKey)) {
                break;
            }
            SingleExtraction nextExtraction = extractSingle(inventory, cpu.getLevel(), supportedPattern);
            if (nextExtraction == null) {
                break;
            }
            if (!batchKey.matchesPatternInputs(
                    supportedPattern,
                    nextExtraction.inputs(),
                    provider.getDispatchOperationTicks()
            )) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, nextExtraction.reinjectableInputs());
                break;
            }
            if (!compiledTask.tryAppendExecutionCount(1)) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, nextExtraction.reinjectableInputs());
                break;
            }
            expectedOutputs.addAll(nextExtraction.expectedOutputs());
            expectedContainerItems.addAll(nextExtraction.expectedContainerItems());
            reinjectableInputs.add(nextExtraction.reinjectableInputs());
            logicalExecutions++;
        }

        compiledTask.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        return new BatchExtraction(
                compiledTask,
                batchKey,
                logicalExecutions,
                logicalExecutions,
                patternPower,
                expectedOutputs,
                expectedContainerItems,
                List.copyOf(reinjectableInputs),
                null,
                0L
        );
    }

    @Nullable
    private static BatchExtraction extractScaledBatch(
            CraftingCPUCluster cpu,
            ListCraftingInventory inventory,
            IFormalMachineScaledPattern scaledPattern,
            IFormalMachineCraftingProvider provider
    ) {
        SingleExtraction firstExtraction = extractSingle(inventory, cpu.getLevel(), scaledPattern.basePattern());
        if (firstExtraction == null) {
            return null;
        }

        int extractedExecutions = 1;
        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.addAll(firstExtraction.expectedOutputs());
        KeyCounter expectedContainerItems = new KeyCounter();
        expectedContainerItems.addAll(firstExtraction.expectedContainerItems());
        List<KeyCounter[]> reinjectableInputs = new ArrayList<>();
        reinjectableInputs.add(firstExtraction.reinjectableInputs());

        if (expectedContainerItems.isEmpty() && scaledPattern.multiplier() > 1) {
            BulkPatternExtractionPlanner.BulkExtractionResult bulkExtraction =
                    BulkPatternExtractionPlanner.extractAdditionalExecutions(
                            inventory,
                            firstExtraction.reinjectableInputs(),
                            scaledPattern.multiplier() - 1
                    );
            if (bulkExtraction != null && bulkExtraction.logicalExecutions() > 0) {
                extractedExecutions += bulkExtraction.logicalExecutions();
                addScaled(expectedOutputs, firstExtraction.expectedOutputs(), bulkExtraction.logicalExecutions());
                reinjectableInputs.add(bulkExtraction.reinjectableInputs());
                if (provider instanceof AbstractHighCapacityCraftingHostBlockEntity host) {
                    host.recordBulkExtractionResult(extractedExecutions);
                }
            } else if (provider instanceof AbstractHighCapacityCraftingHostBlockEntity host) {
                host.recordBulkExtractionFallback();
            }
        }

        int deferredMultiplier = Math.max(0, scaledPattern.multiplier() - extractedExecutions);
        IPatternDetails deferredPattern;
        long deferredPatternExecutions;
        if (deferredMultiplier <= 0) {
            deferredPattern = null;
            deferredPatternExecutions = 0L;
        } else if (deferredMultiplier == 1) {
            deferredPattern = scaledPattern.basePattern();
            deferredPatternExecutions = 1L;
        } else {
            deferredPattern = scaledVariant(scaledPattern, deferredMultiplier);
            deferredPatternExecutions = deferredPattern == null ? 0L : 1L;
        }

        CompiledTask compiledTask = CompiledTask.compile(
                scaledPattern.basePattern(),
                firstExtraction.inputs(),
                provider.getDispatchOperationTicks(),
                extractedExecutions
        );
        if (compiledTask == null) {
            reinjectAll(inventory, reinjectableInputs);
            return null;
        }

        if (scaledPattern.templatePrimary() != null) {
            compiledTask.setCompletionTemplate(
                    scaledPattern.templatePrimary(),
                    scaledPattern.templateRemainders()
            );
            if (provider instanceof AbstractHighCapacityCraftingHostBlockEntity host) {
                host.recordTemplatedDispatchHitForTest();
            }
        } else {
            compiledTask.setSupportsTemplatedCompletion(
                    FormalMachineCompletionTemplateHelper.supportsTemplateForPattern(scaledPattern.basePattern())
            );
        }

        compiledTask.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        FormalMachineBatchKey batchKey = FormalMachineBatchKey.fromCompiledTask(scaledPattern.basePattern(), compiledTask);
        double patternPower = CraftingCpuHelper.calculatePatternPower(firstExtraction.reinjectableInputs());
        return new BatchExtraction(
                compiledTask,
                batchKey,
                extractedExecutions,
                1,
                patternPower,
                expectedOutputs,
                expectedContainerItems,
                List.copyOf(reinjectableInputs),
                deferredPattern,
                deferredPatternExecutions
        );
    }

    @Nullable
    private static SingleExtraction extractSingle(
            ListCraftingInventory inventory,
            net.minecraft.world.level.Level level,
            IMolecularAssemblerSupportedPattern supportedPattern
    ) {
        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter expectedContainerItems = new KeyCounter();
        KeyCounter[] inputs = CraftingCpuHelper.extractPatternInputs(
                supportedPattern,
                inventory,
                level,
                expectedOutputs,
                expectedContainerItems
        );
        if (inputs == null) {
            return null;
        }
        return new SingleExtraction(
                inputs,
                copyCounters(inputs),
                expectedOutputs,
                expectedContainerItems
        );
    }

    @Nullable
    private static CompiledTask compileForProvider(
            IMolecularAssemblerSupportedPattern supportedPattern,
            IFormalMachineCraftingProvider provider,
            KeyCounter[] inputs
    ) {
        CompiledTask compiledTask = CompiledTask.compile(
                supportedPattern,
                inputs,
                provider.getDispatchOperationTicks(),
                1
        );
        if (compiledTask != null) {
            compiledTask.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        }
        return compiledTask;
    }

    private static void registerAcceptedBatch(
            CraftingCPUCluster cpu,
            CraftingCpuLogicAccessor logicAccessor,
            ExecutingCraftingJobAccessor jobAccessor,
            IFormalMachineCraftingProvider provider,
            BatchExtraction batchExtraction
    ) {
        for (var expectedOutput : batchExtraction.expectedOutputs()) {
            jobAccessor.getWaitingFor().insert(expectedOutput.getKey(), expectedOutput.getLongValue(), Actionable.MODULATE);
            logicAccessor.invokePostChange(expectedOutput.getKey());
        }
        for (var expectedContainerItem : batchExtraction.expectedContainerItems()) {
            jobAccessor.getWaitingFor().insert(
                    expectedContainerItem.getKey(),
                    expectedContainerItem.getLongValue(),
                    Actionable.MODULATE
            );
            addTrackedWaitingTime(jobAccessor, expectedContainerItem.getLongValue(), expectedContainerItem.getKey());
            logicAccessor.invokePostChange(expectedContainerItem.getKey());
        }
        UUID craftingId = jobAccessor.getLink() == null ? null : jobAccessor.getLink().getCraftingID();
        if (craftingId != null) {
            SOURCE_CPUS_BY_JOB.put(craftingId, new NativeSourceCpuHandle(cpu, craftingId));
        }
        FormalMachineCraftingTimingService.recordAcceptedBatch(
                craftingId,
                provider,
                batchExtraction.logicalExecutions(),
                batchExtraction.expectedOutputs(),
                batchExtraction.expectedContainerItems()
        );
        cpu.markDirty();
    }

    @Nullable
    private static SourceCpuHandle liveSourceCpu(
            @Nullable CraftingService craftingService,
            @Nullable UUID craftingId
    ) {
        if (craftingId == null) {
            return null;
        }
        SourceCpuHandle cpu = SOURCE_CPUS_BY_JOB.get(craftingId);
        if (isMatchingSourceCpu(cpu, craftingId)) {
            return cpu;
        }
        SOURCE_CPUS_BY_JOB.remove(craftingId);
        if (craftingService != null) {
            SourceCpuHandle resolved = findMatchingSourceCpuHandle(craftingService, craftingId);
            if (resolved != null) {
                SOURCE_CPUS_BY_JOB.put(craftingId, resolved);
                return resolved;
            }
        }
        return null;
    }

    private static boolean isMatchingSourceCpu(@Nullable SourceCpuHandle cpu, UUID craftingId) {
        return cpu != null && cpu.isActive() && craftingId.equals(cpu.craftingId());
    }

    private static boolean isMatchingSourceCpu(@Nullable CraftingCPUCluster cpu, UUID craftingId) {
        if (cpu == null || !cpu.isActive()) {
            return false;
        }
        ExecutingCraftingJob job = ((CraftingCpuLogicAccessor) cpu.craftingLogic).getJob();
        if (job == null) {
            return false;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        if (accessor.getLink() == null || !craftingId.equals(accessor.getLink().getCraftingID())) {
            return false;
        }
        return true;
    }

    private static void addTrackedWaitingTime(ExecutingCraftingJobAccessor accessor, long amount, AEKey key) {
        try {
            Method addMaxItems = accessor.getTimeTracker().getClass()
                    .getDeclaredMethod("addMaxItems", long.class, AEKeyType.class);
            addMaxItems.setAccessible(true);
            addMaxItems.invoke(accessor.getTimeTracker(), amount, key.getType());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to extend AE2 time tracker for formal machine dispatch", exception);
        }
    }

    private static KeyCounter[] copyCounters(KeyCounter[] source) {
        KeyCounter[] copied = new KeyCounter[source.length];
        for (int index = 0; index < source.length; index++) {
            copied[index] = new KeyCounter();
            if (source[index] != null) {
                copied[index].addAll(source[index]);
            }
        }
        return copied;
    }

    private static void reinjectAll(ListCraftingInventory inventory, List<KeyCounter[]> snapshots) {
        for (KeyCounter[] snapshot : snapshots) {
            reinjectCounters(inventory, snapshot);
        }
    }

    private static void reinjectCounters(ListCraftingInventory inventory, KeyCounter[] counters) {
        if (inventory == null || counters == null) {
            return;
        }
        for (KeyCounter counter : counters) {
            if (counter == null) {
                continue;
            }
            for (var entry : counter) {
                inventory.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            }
        }
    }

    private static DynamicExecutionBudgetModel providerBudgetModel(IFormalMachineCraftingProvider provider) {
        if (provider instanceof git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity host) {
            return host.getCurrentBudgetModel();
        }
        return new DynamicExecutionBudgetModel(1, 1, 1, 1, 1, 1, 1, 0, 0, 0, false);
    }

    private static @Nullable AbstractHighCapacityCraftingHostBlockEntity resolveFormalMachineRequester(
            @Nullable ICraftingRequester requestingMachine
    ) {
        if (!(requestingMachine instanceof IActionHost actionHost)) {
            return null;
        }
        if (actionHost.getActionableNode() == null) {
            return null;
        }
        Object owner = actionHost.getActionableNode().getOwner();
        if (owner instanceof AbstractHighCapacityCraftingHostBlockEntity host) {
            return host;
        }
        return null;
    }

    private static boolean isFormalMachinePlan(CraftingService craftingService, ICraftingPlan job) {
        if (craftingService == null || job == null || job.patternTimes().isEmpty()) {
            return false;
        }
        for (IPatternDetails patternDetails : job.patternTimes().keySet()) {
            boolean hasFormalProvider = false;
            for (ICraftingProvider provider : craftingService.getProviders(patternDetails)) {
                if (provider instanceof IFormalMachineCraftingProvider) {
                    hasFormalProvider = true;
                }
            }
            if (!hasFormalProvider) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static SourceCpuHandle findMatchingSourceCpuHandle(CraftingService craftingService, UUID craftingId) {
        for (ICraftingCPU candidate : craftingService.getCpus()) {
            SourceCpuHandle handle = sourceCpuHandleForCandidate(candidate, craftingId, null);
            if (handle != null) {
                return handle;
            }
        }
        return null;
    }

    private static @Nullable AbstractHighCapacityCraftingHostBlockEntity resolveFormalMachinePlanHost(
            CraftingService craftingService,
            ICraftingPlan job
    ) {
        AbstractHighCapacityCraftingHostBlockEntity matchedHost = null;
        for (IPatternDetails patternDetails : job.patternTimes().keySet()) {
            for (ICraftingProvider provider : craftingService.getProviders(patternDetails)) {
                if (!(provider instanceof AbstractHighCapacityCraftingHostBlockEntity host)) {
                    continue;
                }
                if (matchedHost != null && matchedHost != host) {
                    return null;
                }
                matchedHost = host;
            }
        }
        return matchedHost;
    }

    private static @Nullable UUID findSubmittedCpuCraftingId(
            CraftingService craftingService,
            @Nullable ICraftingCPU targetCpu,
            ICraftingPlan job
    ) {
        if (craftingService == null || job == null || job.finalOutput() == null) {
            return null;
        }
        UUID matchedTarget = matchingCpuCraftingId(targetCpu, job);
        if (matchedTarget != null) {
            return matchedTarget;
        }
        for (ICraftingCPU candidate : craftingService.getCpus()) {
            UUID matched = matchingCpuCraftingId(candidate, job);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    @Nullable
    private static SourceCpuHandle resolveSubmittedSourceCpuHandle(
            CraftingService craftingService,
            @Nullable ICraftingCPU targetCpu,
            UUID craftingId,
            ICraftingPlan job
    ) {
        SourceCpuHandle targetHandle = sourceCpuHandleForCandidate(targetCpu, craftingId, job);
        if (targetHandle != null) {
            return targetHandle;
        }
        for (ICraftingCPU candidate : craftingService.getCpus()) {
            SourceCpuHandle handle = sourceCpuHandleForCandidate(candidate, craftingId, job);
            if (handle != null) {
                return handle;
            }
        }
        return null;
    }

    @Nullable
    private static SourceCpuHandle sourceCpuHandleForCandidate(
            @Nullable ICraftingCPU candidate,
            UUID craftingId,
            @Nullable ICraftingPlan job
    ) {
        if (candidate instanceof CraftingCPUCluster cluster) {
            boolean matches = job == null
                    ? isMatchingSourceCpu(cluster, craftingId)
                    : craftingId.equals(matchingCpuCraftingId(cluster, job));
            if (matches) {
                return new NativeSourceCpuHandle(cluster, craftingId);
            }
        }
        if (candidate instanceof ParallelCraftingCPU parallelCpu) {
            if (!parallelCpu.isActiveVirtualCpu()) {
                return null;
            }
            ParallelCraftingCpuCluster cluster = parallelCpu.cluster();
            ParallelCraftingLane lane = cluster.findLaneByCraftingId(craftingId);
            boolean matches = lane != null
                    && parallelCpu == cluster.findActiveCpuByCraftingId(craftingId)
                    && (job == null || craftingId.equals(cluster.findCraftingIdForPlan(job)));
            if (matches) {
                return new ParallelActiveCpuHandle(cluster, craftingId);
            }
        }
        return null;
    }

    @Nullable
    private static UUID matchingCpuCraftingId(@Nullable ICraftingCPU candidate, ICraftingPlan job) {
        if (candidate instanceof CraftingCPUCluster cluster) {
            if (!cluster.isBusy()) {
                return null;
            }
            return matchingCpuCraftingId(cluster, job);
        }
        if (candidate instanceof ParallelCraftingCPU parallelCpu) {
            if (!parallelCpu.isActiveVirtualCpu()) {
                return null;
            }
            UUID craftingId = parallelCpu.laneId();
            if (craftingId == null) {
                return null;
            }
            return craftingId.equals(parallelCpu.cluster().findCraftingIdForPlan(job)) ? craftingId : null;
        }
        return null;
    }

    private static @Nullable UUID matchingCpuCraftingId(CraftingCPUCluster cluster, ICraftingPlan job) {
        ExecutingCraftingJob activeJob = ((CraftingCpuLogicAccessor) cluster.craftingLogic).getJob();
        if (activeJob == null) {
            return null;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) activeJob;
        GenericStack activeOutput = accessor.getFinalOutput();
        if (accessor.getLink() == null || activeOutput == null) {
            return null;
        }
        if (!activeOutput.what().equals(job.finalOutput().what())) {
            return null;
        }
        if (accessor.getRemainingAmount() != job.finalOutput().amount()) {
            return null;
        }
        return samePatternTimes(activeJob, job) ? accessor.getLink().getCraftingID() : null;
    }

    private static boolean samePatternTimes(ExecutingCraftingJob activeJob, ICraftingPlan job) {
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) activeJob;
        return normalizedPatternTimes(accessor.getTasks()).equals(normalizedPatternTimes(job.patternTimes()));
    }

    private static void addScaled(KeyCounter target, KeyCounter source, long multiplier) {
        if (target == null || source == null || multiplier <= 0L) {
            return;
        }
        for (var entry : source) {
            target.add(entry.getKey(), Math.multiplyExact(entry.getLongValue(), multiplier));
        }
    }

    private record SingleExtraction(
            KeyCounter[] inputs,
            KeyCounter[] reinjectableInputs,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
    }

    private record BatchExtraction(
            CompiledTask compiledTask,
            FormalMachineBatchKey batchKey,
            int logicalExecutions,
            int taskExecutionsConsumed,
            double patternPower,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            List<KeyCounter[]> reinjectableInputs,
            @Nullable IPatternDetails deferredPattern,
            long deferredPatternExecutions
    ) {
        private void reinject(ListCraftingInventory inventory) {
            reinjectAll(inventory, reinjectableInputs);
        }
    }

    private record DeferredTaskProgressAddition(
            IPatternDetails pattern,
            long executions
    ) {
    }

    private record PatternTaskKey(@Nullable AEItemKey definition, int multiplier) {
    }

    private static void decrementTrackedWaitingTime(ExecutingCraftingJobAccessor accessor, long amount, AEKey key) {
        try {
            Method decrementItems = accessor.getTimeTracker().getClass()
                    .getDeclaredMethod("decrementItems", long.class, AEKeyType.class);
            decrementItems.setAccessible(true);
            decrementItems.invoke(accessor.getTimeTracker(), amount, key.getType());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to decrement AE2 time tracker for formal machine dispatch", exception);
        }
    }

    @Nullable
    private static IFormalMachineScaledPattern scaledVariant(IFormalMachineScaledPattern pattern, int multiplier) {
        if (pattern == null || multiplier <= 0) {
            return null;
        }
        if (multiplier == pattern.multiplier()) {
            return pattern;
        }
        return new ScaledCraftingPattern(
                pattern.basePattern(),
                multiplier,
                pattern.templatePrimary(),
                pattern.templateRemainders()
        );
    }

    private static void applyDeferredTaskProgressAdditions(
            ExecutingCraftingJobAccessor jobAccessor,
            List<DeferredTaskProgressAddition> deferredTaskProgressAdditions
    ) {
        if (jobAccessor == null || deferredTaskProgressAdditions == null || deferredTaskProgressAdditions.isEmpty()) {
            return;
        }
        Map<IPatternDetails, Object> tasks = jobAccessor.getTasks();
        for (DeferredTaskProgressAddition addition : deferredTaskProgressAdditions) {
            if (addition == null || addition.executions() <= 0L || addition.pattern() == null) {
                continue;
            }
            Map.Entry<IPatternDetails, Object> matchingEntry = findNormalizedTaskProgressEntry(tasks, addition.pattern());
            if (matchingEntry != null
                    && matchingEntry.getValue() instanceof ExecutingCraftingJobTaskProgressAccessor progressAccessor) {
                debugFormalDispatch("defer merge pattern=" + addition.pattern()
                        + " add=" + addition.executions()
                        + " existing=" + progressAccessor.getValue());
                progressAccessor.setValue(progressAccessor.getValue() + addition.executions());
                continue;
            }
            debugFormalDispatch("defer add pattern=" + addition.pattern() + " add=" + addition.executions());
            tasks.put(addition.pattern(), newTaskProgress(addition.executions()));
        }
    }

    private static @Nullable Map.Entry<IPatternDetails, Object> findNormalizedTaskProgressEntry(
            Map<IPatternDetails, Object> tasks,
            IPatternDetails pattern
    ) {
        if (tasks == null || tasks.isEmpty() || pattern == null) {
            return null;
        }
        Object exact = tasks.get(pattern);
        if (exact != null) {
            for (Map.Entry<IPatternDetails, Object> entry : tasks.entrySet()) {
                if (entry.getKey() == pattern || pattern.equals(entry.getKey())) {
                    return entry;
                }
            }
        }
        PatternTaskKey expectedKey = patternTaskKey(pattern);
        for (Map.Entry<IPatternDetails, Object> entry : tasks.entrySet()) {
            if (expectedKey.equals(patternTaskKey(entry.getKey()))) {
                return entry;
            }
        }
        return null;
    }

    private static Map<PatternTaskKey, Long> normalizedPatternTimes(Map<IPatternDetails, ?> patternTimes) {
        Map<PatternTaskKey, Long> normalized = new HashMap<>();
        if (patternTimes == null || patternTimes.isEmpty()) {
            return normalized;
        }
        for (Map.Entry<IPatternDetails, ?> entry : patternTimes.entrySet()) {
            long value = patternProgressValue(entry.getValue());
            normalized.merge(patternTaskKey(entry.getKey()), value, Long::sum);
        }
        return normalized;
    }

    private static long patternProgressValue(@Nullable Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value instanceof ExecutingCraftingJobTaskProgressAccessor progressAccessor) {
            return Math.max(0L, progressAccessor.getValue());
        }
        return Long.MIN_VALUE;
    }

    private static PatternTaskKey patternTaskKey(@Nullable IPatternDetails patternDetails) {
        if (patternDetails instanceof IFormalMachineScaledPattern scaledPattern) {
            return new PatternTaskKey(scaledPattern.basePattern().getDefinition(), Math.max(1, scaledPattern.multiplier()));
        }
        return new PatternTaskKey(patternDetails == null ? null : patternDetails.getDefinition(), 1);
    }

    private static Object newTaskProgress(long value) {
        try {
            Class<?> taskProgressClass = Class.forName("appeng.crafting.execution.ExecutingCraftingJob$TaskProgress");
            Constructor<?> constructor = taskProgressClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            ((ExecutingCraftingJobTaskProgressAccessor) instance).setValue(Math.max(0L, value));
            return instance;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create AE2 task progress for formal machine scaled dispatch", exception);
        }
    }

    private record NativeSourceCpuHandle(CraftingCPUCluster cpu, UUID craftingId) implements SourceCpuHandle {

        @Override
        public boolean isActive() {
            return isMatchingSourceCpu(cpu, craftingId);
        }

        @Override
        public long getRequestedAmount(@Nullable AEKey what) {
            return what == null ? 0L : Math.max(0L, cpu.craftingLogic.getWaitingFor(what));
        }

        @Override
        public long insert(@Nullable AEKey what, long amount, Actionable mode, IActionSource source) {
            if (what == null || amount <= 0L) {
                return 0L;
            }
            long simulatedAccepted = simulateExternalIngressFinalOutput(what, amount, mode);
            if (simulatedAccepted >= 0L) {
                return simulatedAccepted;
            }
            long standaloneAccepted = insertStandaloneFinalOutput(what, amount, mode);
            if (standaloneAccepted >= 0L) {
                return standaloneAccepted;
            }
            return Math.max(0L, cpu.insert(what, amount, mode, source));
        }

        private long simulateExternalIngressFinalOutput(AEKey what, long amount, Actionable mode) {
            if (mode != Actionable.SIMULATE) {
                return -1L;
            }
            CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
            ExecutingCraftingJob job = logicAccessor.getJob();
            if (job == null) {
                return -1L;
            }
            ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) job;
            if (jobAccessor.getLink() == null || jobAccessor.getLink().isStandalone()) {
                return -1L;
            }
            GenericStack finalOutput = jobAccessor.getFinalOutput();
            if (finalOutput == null || !what.matches(finalOutput)) {
                return -1L;
            }
            long waiting = jobAccessor.getWaitingFor().extract(what, amount, Actionable.SIMULATE);
            return waiting <= 0L ? 0L : Math.min(amount, waiting);
        }

        private long insertStandaloneFinalOutput(AEKey what, long amount, Actionable mode) {
            CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
            ExecutingCraftingJob job = logicAccessor.getJob();
            if (job == null) {
                return -1L;
            }
            ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) job;
            if (jobAccessor.getLink() == null || !jobAccessor.getLink().isStandalone()) {
                return -1L;
            }
            GenericStack finalOutput = jobAccessor.getFinalOutput();
            if (finalOutput == null || !what.matches(finalOutput)) {
                return -1L;
            }

            long waiting = jobAccessor.getWaitingFor().extract(what, amount, Actionable.SIMULATE);
            if (waiting <= 0L) {
                return 0L;
            }
            long accepted = Math.min(amount, waiting);
            if (mode != Actionable.MODULATE) {
                return accepted;
            }

            decrementTrackedWaitingTime(jobAccessor, accepted, what);
            jobAccessor.getWaitingFor().extract(what, accepted, Actionable.MODULATE);
            cpu.markDirty();

            logicAccessor.getInventory().insert(what, accepted, Actionable.MODULATE);
            logicAccessor.invokePostChange(what);

            long remainingAmount = Math.max(0L, jobAccessor.getRemainingAmount() - accepted);
            jobAccessor.setRemainingAmount(remainingAmount);
            if (remainingAmount <= 0L) {
                logicAccessor.invokeFinishJob(true);
                cpu.updateOutput(null);
            } else {
                cpu.updateOutput(new GenericStack(finalOutput.what(), remainingAmount));
            }
            return accepted;
        }
    }

    private record ParallelActiveCpuHandle(ParallelCraftingCpuCluster cluster, UUID craftingId)
            implements SourceCpuHandle {

        @Override
        public boolean isActive() {
            return cluster != null && cluster.isCraftActive(craftingId);
        }

        @Override
        public long getRequestedAmount(@Nullable AEKey what) {
            return cluster == null ? 0L : cluster.getRequestedAmountForCraft(craftingId, what);
        }

        @Override
        public long insert(@Nullable AEKey what, long amount, Actionable mode, IActionSource source) {
            return cluster == null ? 0L
                    : cluster.insertIntoWaitingForCraft(craftingId, what, amount, mode, true);
        }
    }
}
