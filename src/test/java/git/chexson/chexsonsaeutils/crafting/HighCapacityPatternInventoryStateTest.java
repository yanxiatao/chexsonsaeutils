package git.chexson.chexsonsaeutils.crafting;

import git.chexson.chexsonsaeutils.blockentity.crafting.DirtySlotPatternRefreshScheduler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.projectPath;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighCapacityPatternInventoryStateTest {

    @Test
    void pagedPatternInventoryAnchorsStayLocalIncremental() throws IOException {
        String source = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/PagedPatternInventory.java"
        ));

        assertTrue(source.contains("public void setVirtualSlot(int globalSlot, ItemStack stack)"),
                "setVirtualSlot anchor is missing");
        assertTrue(source.contains("if (globalSlot / pageSize == activePage)"),
                "active page synchronization must stay local");
        assertTrue(source.contains("refreshScheduler.markDirty(globalSlot);"),
                "single-slot writes must remain dirty-slot incremental");
        assertTrue(source.contains("public void clear()"),
                "clear anchor is missing");
        assertTrue(source.contains("refreshScheduler.markRangeDirty(0, virtualSlots.size());"),
                "clear must mark the full virtual range dirty");
        assertTrue(source.contains("public void clearWithoutDirtyMarksForTest()"),
                "test-only clear helper for isolated benchmarks must remain available");
        assertTrue(source.contains("public void loadFromExternalSnapshot(Map<Integer, ItemStack> slotSnapshot)"),
                "paged inventory must keep the external snapshot hydration hook");
    }

    @Test
    void localPatternProviderFacadeAnchorsStayCacheAware() throws IOException {
        String source = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/LocalPatternProviderFacade.java"
        ));

        assertTrue(source.contains("decodedPatternEntryCache.get(globalSlot)"),
                "decoded pattern entry cache must stay in the local provider path");
        assertTrue(source.contains("if (!localOptimizationEnabled)"),
                "local provider path must keep the full-refresh baseline switch");
        assertTrue(source.contains("for (int globalSlot = 0; globalSlot < pagedPatternInventory.getTotalSlots(); globalSlot++)"),
                "full-refresh baseline must remain capable of scanning the entire local host");
        assertTrue(source.contains("host.recordDecodeCacheHit();"),
                "decode cache hit telemetry must remain available");
        assertTrue(source.contains("host.recordLocalOptimizationHit();"),
                "local optimization hit telemetry must remain local to the host");
        assertTrue(source.contains("providerVisibleSetUpdatePending = true;"),
                "local provider path must defer AE provider updates out of synchronous pattern queries");
        assertFalse(source.contains("ICraftingProvider.requestUpdate(host.getMainNode());"),
                "getAvailablePatterns must not request AE provider updates while AE2 is reading provider snapshots");
    }

    @Test
    void highCapacityBlockEntityAnchorsStayCraftingOnly() throws IOException {
        String hostCoreSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/AbstractHighCapacityCraftingHostBlockEntity.java"
        ));

        assertTrue(hostCoreSource.contains("public int fillCraftingPatternsForTest(int startSlot, int count, ItemStack[] craftingGrid)"),
                "crafting-pattern fill helper must remain available");
        assertTrue(hostCoreSource.contains("public int fillCraftingPatternsRoundRobinForTest(List<ItemStack> patterns, int slotCount)"),
                "round-robin crafting-pattern fill helper must remain available");
        assertTrue(hostCoreSource.contains("public int submitAvailablePatternsRoundRobinForTest(int totalJobs, int uniquePatternBudget)"),
                "round-robin submit helper must remain available");
        assertTrue(hostCoreSource.contains("public int countAvailablePatternsForOutputForTest(AEItemKey output)"),
                "output availability counter must remain available");
        assertTrue(hostCoreSource.contains("public int countUniquePatternDefinitionsForTest()"),
                "unique crafting-pattern definition counter must remain available");
        assertTrue(hostCoreSource.contains("public int countCompletedOutputForTest(AEItemKey output)"),
                "completed output counter must remain available");
        assertTrue(hostCoreSource.contains("public int getPendingLogicalExecutionCountForTest()"),
                "pending logical execution count must remain observable for batch tests");
        assertTrue(hostCoreSource.contains("public void setSpeedCardsForTest(int count)"),
                "speed-card test helper must remain available");
        assertTrue(hostCoreSource.contains("return PatternDetailsHelper.encodeCraftingPattern(recipe, encodedInputs, result, false, false);"),
                "test host must continue encoding ordinary crafting patterns through AE2");
        assertTrue(hostCoreSource.contains("if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern))"),
                "test host must continue rejecting non-crafting assembler-incompatible patterns");
        assertTrue(hostCoreSource.contains("!localPatternProviderFacade.contains(unwrapFormalScaledPattern(patternDetails))"),
                "ordinary provider pushPattern must resolve scaled patterns against the base provider registry");
        assertTrue(hostCoreSource.contains("private CompiledTask compileScaledProviderTask(")
                        && hostCoreSource.contains("scaledPattern.basePattern()"),
                "ordinary provider pushPattern must compile scaled patterns into base-pattern executions");
        assertFalse(hostCoreSource.contains("formalMachineDispatchHost && patternDetails instanceof IFormalMachineScaledPattern"),
                "ordinary provider pushPattern must not hard-reject scaled patterns");
        assertTrue(hostCoreSource.contains("public void setLocalOptimizationEnabledForTest(boolean enabled)"),
                "test host must expose a local optimization toggle for A/B benchmarking");
        assertTrue(hostCoreSource.contains("localExecutionQueue.offerOrCoalesce("),
                "host task submission must route through queue-level coalescing");
        assertTrue(hostCoreSource.contains("coalescedTaskCount++"),
                "host must track coalesced task telemetry");
        assertTrue(hostCoreSource.contains("coalescedJobsSaved += compiledTask.getExecutionCount();"),
                "host must track saved logical jobs when coalescing");
        assertTrue(hostCoreSource.contains("request.compiledTask().setCompletionRoute(TaskCompletionRoute.CPU_WAITING);"),
                "formal-machine fast path must retarget accepted tasks to CPU-aware completion");
        assertTrue(hostCoreSource.contains("fastPathAcceptedCount += Math.max(0, request.logicalExecutions());"),
                "formal-machine fast path must record accepted logical executions");
        assertTrue(hostCoreSource.contains(
                        "private PendingAeReturn tryDeliverPendingReturn(PendingAeReturn pending, long hardDeadlineNanos)"),
                "host must route pending AE returns through a shared completion bridge");
        assertTrue(hostCoreSource.contains("private List<GenericStack> routePayloadThroughCpu(")
                        && hostCoreSource.contains("long hardDeadlineNanos"),
                "CPU-aware completion bridge must stay available for formal-machine batches");
        assertTrue(hostCoreSource.contains("private List<GenericStack> routePayloadIntoAeNetwork("),
                "formal-machine host must keep a shared AE ingress routing bridge");
        assertTrue(hostCoreSource.contains("FormalMachineCraftingDispatchService.getSourceCpuHandle("),
                "CPU_WAITING return must resolve an exact source CPU handle before global CPU fallback");
        assertTrue(hostCoreSource.contains("AeCpuIngressRouter.routeStack(")
                        && hostCoreSource.contains("AeCpuIngressRouter.routePayload("),
                "external AE returns must route through the shared CPU-first ingress router");
        assertFalse(hostCoreSource.contains("craftingService.getRequestedAmount(genericStack.what())"),
                "CPU_WAITING return must not block source-bound payloads on unrelated global AE CPU waiting");
        assertTrue(hostCoreSource.contains("routingResult.acceptedByAnyCpu() > 0L")
                        && hostCoreSource.contains("FormalMachineCraftingTimingService.recordCpuWaitingReturn("),
                "CPU_WAITING return telemetry must count payload accepted by source or fallback CPU waiting");
        assertFalse(hostCoreSource.contains("FormalMachineCraftingDispatchService.withCpuInsertionBypassed"),
                "formal-machine host must not bypass AE2 CPU interception on external returns");
        assertTrue(hostCoreSource.contains("public FormalMachineTickBudgetSnapshot getTickBudgetSnapshotForTest()"),
                "formal machine tick budget snapshot must stay observable to runtime tests");
        assertTrue(hostCoreSource.contains("maxTickBudgetNanosObserved = Math.max(maxTickBudgetNanosObserved, elapsed);"),
                "formal machine must record max observed tick budget time");
        assertTrue(hostCoreSource.contains("public void setPatternInSlotDeferredRefreshForTest(int slot, ItemStack stack)"),
                "test host must expose deferred dirty-slot writes for provider refresh benchmarks");
        assertTrue(hostCoreSource.contains("public int refreshAvailablePatternsForTest()"),
                "test host must expose direct provider refresh benchmarks");
        assertTrue(hostCoreSource.contains("public void clearPatternsDeferredRefreshForTest()"),
                "test host must expose deferred clear hooks for isolated bulk-import benchmarks");
        assertTrue(hostCoreSource.contains("HighCapacityPatternHostSavedData.get(serverLevel)"),
                "high-capacity hosts must persist pattern payloads outside block-entity NBT");
        assertTrue(hostCoreSource.contains("new PendingAeReturn("),
                "batch execution must persist aggregated logical execution counts in pending AE returns");
        assertTrue(hostCoreSource.contains("private void maybeAttachCompletionTemplate("),
                "formal-machine host must keep the dispatch-time completion template hook");
        assertTrue(hostCoreSource.contains("recordBulkExtractionResult("),
                "formal-machine host must expose bulk extraction hit telemetry");
        assertTrue(hostCoreSource.contains("recordBulkExtractionFallback("),
                "formal-machine host must expose bulk extraction fallback telemetry");
        assertTrue(hostCoreSource.contains("recordTemplatedDispatchHitForTest("),
                "formal-machine host must expose dispatch-time template hit telemetry");
        assertFalse(hostCoreSource.contains("pagedPatternInventory.writeToTag("),
                "block-entity NBT must not inline all virtual pattern slots");
        assertFalse(hostCoreSource.contains("pagedPatternInventory.readFromTag("),
                "block-entity NBT must not reload all virtual pattern slots directly");
    }

    @Test
    void formalMachineDispatchKeepsAeTimeTrackerAccountingScoped() throws IOException {
        String dispatchSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/formalmachine/FormalMachineCraftingDispatchService.java"
        ));
        String acceptedBatchSource = dispatchSource.substring(
                dispatchSource.indexOf("private static void registerAcceptedBatch"),
                dispatchSource.indexOf("private static void addTrackedWaitingTime")
        );
        int outputLoopStart = acceptedBatchSource.indexOf("for (var expectedOutput : batchExtraction.expectedOutputs())");
        int containerLoopStart = acceptedBatchSource.indexOf("for (var expectedContainerItem : batchExtraction.expectedContainerItems())");
        assertTrue(outputLoopStart >= 0, "formal machine dispatch must register ordinary expected outputs");
        assertTrue(containerLoopStart > outputLoopStart,
                "formal machine dispatch must register container items after ordinary outputs");
        String outputLoop = acceptedBatchSource.substring(outputLoopStart, containerLoopStart);
        String containerLoop = acceptedBatchSource.substring(containerLoopStart);

        assertTrue(outputLoop.contains("jobAccessor.getWaitingFor().insert"),
                "ordinary formal-machine outputs must still enter AE2 waitingFor");
        assertFalse(outputLoop.contains("addTrackedWaitingTime"),
                "ordinary formal-machine outputs must not extend AE2 started-work time tracker");
        assertTrue(containerLoop.contains("jobAccessor.getWaitingFor().insert"),
                "formal-machine container items must enter AE2 waitingFor");
        assertTrue(containerLoop.contains("addTrackedWaitingTime"),
                "formal-machine container items must extend AE2 started-work time tracker like stock AE2");
        assertTrue(dispatchSource.contains("getDeclaredMethod(\"addMaxItems\", long.class, AEKeyType.class)"),
                "formal machine dispatch must use AE2's addMaxItems bridge only for tracked container items");
        assertTrue(dispatchSource.contains("BulkPatternExtractionPlanner.extractAdditionalExecutions("),
                "formal-machine dispatch must keep the bulk extraction planner fast path");
        assertTrue(dispatchSource.contains("FormalMachineCompletionTemplateHelper.probeStableTemplate("),
                "formal-machine dispatch must probe completion templates before queue handoff");
        String scaledEligibilitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/formalmachine/ScaledCraftingPatternEligibilityService.java"
        ));
        assertTrue(scaledEligibilitySource.contains("CompiledTask.compileWithCraftingGrid(")
                        && scaledEligibilitySource.contains("FormalMachineCompletionTemplateHelper.probeStableTemplate("),
                "scaled formal-machine eligibility must build provider tasks from scaled crafting grids");
        assertTrue(dispatchSource.contains("deferredPattern = scaledVariant(scaledPattern, deferredMultiplier);"),
                "partial scaled formal-machine dispatch must keep the deferred remainder as a scaled task");
        assertTrue(dispatchSource.contains("deferredPatternExecutions = deferredPattern == null ? 0L : 1L;"),
                "scaled deferred tasks must re-enter AE2 as one remaining scaled execution");
    }

    @Test
    void formalMachineFastPathExtractionBudgetStaysTelemetryOnly() throws IOException {
        String budgetSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/DynamicExecutionBudgetModel.java"
        ));
        String methodSource = budgetSource.substring(
                budgetSource.indexOf("public boolean tryClaimFastPathExtractionExecution()"),
                budgetSource.indexOf("public boolean canCompleteAnotherTask")
        );

        assertTrue(methodSource.contains("return canDispatchExecution();"),
                "fast path extraction gate must stay a zero-cost gate instead of a batch-size cap");
        assertFalse(methodSource.contains("remainingFastPathExtractionBudget--"),
                "fast path extraction telemetry must not be decremented as a hard budget");
        assertFalse(methodSource.contains("remainingFastPathExtractionBudget -="),
                "fast path extraction telemetry must not be consumed as a hard budget");
    }

    @Test
    void formalMachineSubmitCreatesTimingStateBeforeFastPathCompletion() throws IOException {
        String timingSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/formalmachine/FormalMachineCraftingTimingService.java"
        ));
        String dispatchSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/formalmachine/FormalMachineCraftingDispatchService.java"
        ));
        String serviceMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceFormalMachineMixin.java"
        ));
        String cpuLogicSourceContextMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingCpuLogicFormalMachineSourceContextMixin.java"
        ));
        String gameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/HighCapacityCraftingMachineGameTests.java"
        ));
        String submitTailSource = dispatchSource.substring(
                dispatchSource.indexOf("public static void onSubmitJobTail"),
                dispatchSource.indexOf("public static long getSourceCpuRequestedAmount(")
        );

        assertTrue(timingSource.contains("public static void beginSubmittedJob("),
                "formal timing bridge must expose a submit-time state creation hook");
        assertTrue(submitTailSource.contains("FormalMachineCraftingTimingService.beginSubmittedJob("),
                "formal-machine submit tail must create timing state before fast-path completion returns output");
        assertTrue(serviceMixinSource.contains("FormalMachineCraftingDispatchService.onSubmitJobTail(")
                        && serviceMixinSource.contains("cir.getReturnValue()"),
                "formal CraftingService submit mixin must pass the real submit result into submit-tail timing setup");
        assertTrue(cpuLogicSourceContextMixinSource.contains("FormalMachineSourceCpuContext.withSourceCraftingId(")
                        && cpuLogicSourceContextMixinSource.contains("provider.pushPattern(patternDetails, inputHolder)"),
                "native AE2 CraftingCpuLogic provider push must carry the source crafting id into formal-machine queues");
        assertTrue(gameTestSource.contains("formal machine timing state should stay pending until fast path accepts the job"),
                "formal machine GameTest must keep submit-time timing state pending until fast-path acceptance");
        assertTrue(gameTestSource.contains("formal machine timing state should be active before requester receives final output"),
                "formal machine GameTest must assert timing state exists before final output completion");
    }

    @Test
    void formalMachineClientScreenFallsBackToVanillaAe2Behavior() throws IOException {
        String mixinPluginSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java"
        ));
        String mixinConfigSource = readUtf8(resourcePath("chexsonsaeutils.mixins.json"));

        assertFalse(mixinConfigSource.contains("\"ae2.client.gui.CraftingCPUScreenFormalMachineStatusMixin\""),
                "formal machine client CPU screen mixin must be removed so AE2 keeps vanilla ETA behavior");
        assertFalse(mixinPluginSource.contains("CraftingCPUScreenFormalMachineStatusMixin"),
                "formal machine mixin plugin must not keep a stale client CPU screen override");
    }

    @Test
    void highCapacityFormalMachineRuntimeTelemetryAnchorsStayObservable() throws IOException {
        String hostCoreSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/AbstractHighCapacityCraftingHostBlockEntity.java"
        ));
        String snapshotSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/PatternBenchmarkSnapshot.java"
        ));
        String tickBudgetSnapshotSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/FormalMachineTickBudgetSnapshot.java"
        ));
        String planningSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/planning/FormalMachinePlanningAggregationService.java"
        ));
        String mixinPluginSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java"
        ));
        String mixinConfigSource = readUtf8(resourcePath("chexsonsaeutils.mixins.json"));
        String gameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/HighCapacityCraftingMachineGameTests.java"
        ));
        String smokeHandlerSource = readUtf8(projectPath(
                "src",
                "minecraftMcpCompat",
                "java",
                "xyz",
                "langyo",
                "minecraft",
                "mcp",
                "mod",
                "ChexsonSmokeInputHandler.java"
        ));

        assertTrue(hostCoreSource.contains("private static final long TICK_HARD_BUDGET_NANOS = 5_000_000L"),
                "formal machine hard tick budget must stay at the planned 5ms observable budget");
        assertTrue(hostCoreSource.contains("boolean hardStop = elapsed >= TICK_HARD_BUDGET_NANOS"),
                "formal machine hard-stop accounting must use the 5ms hard budget");
        assertTrue(hostCoreSource.contains("if (elapsed < TICK_HARD_BUDGET_NANOS)"),
                "formal machine should stop heavy backlog work at the 5ms hard budget");
        assertTrue(hostCoreSource.contains("LOCAL_EXECUTION_QUEUE_CAPACITY = 1_024"),
                "formal machine physical task queue must stay wider than the legacy 128-task backpressure");
        assertTrue(hostCoreSource.contains("FAST_BATCH_BACKPRESSURE_TASK_LIMIT = LOCAL_EXECUTION_QUEUE_CAPACITY"),
                "formal machine fast-batch backpressure must be a named physical backlog limit");
        assertFalse(hostCoreSource.contains("new LocalExecutionQueue(128)"),
                "formal machine must not restore the legacy 128-task physical queue cap");
        assertFalse(hostCoreSource.contains("localExecutionQueue.totalTaskCount() < 128"),
                "formal machine fast-batch backpressure must not restore the legacy 128-task cap");
        assertFalse(hostCoreSource.contains("CPU_WAITING_RETURN_MAX_AMOUNT_PER_STACK"),
                "CPU_WAITING return must not reintroduce a fixed per-stack cap");
        assertFalse(hostCoreSource.contains("CPU_WAITING_RETURN_MAX_STACKS_PER_TICK"),
                "CPU_WAITING return must not reintroduce a fixed stack-count cap");
        assertTrue(hostCoreSource.contains("? new PayloadSlice(List.copyOf(payload), List.of())"),
                "CPU_WAITING return must not keep a fixed amount or stack-count pre-slice");
        assertFalse(hostCoreSource.contains("initializeCpuWaitingReturnWindow()"),
                "CPU_WAITING return must not reintroduce budget-derived amount caps before deadline checks");
        assertFalse(hostCoreSource.contains("takeCpuWaitingPayloadSlice"),
                "CPU_WAITING return must route the full payload through the deadline-driven CPU bridge");
        assertFalse(hostCoreSource.contains("CPU_WAITING_RETURN_STACKS_PER_PREFERRED_LANE"),
                "CPU_WAITING return must not restore a lane-derived stack window");
        assertTrue(hostCoreSource.contains("if (isDeadlineReached(hardDeadlineNanos))"),
                "CPU_WAITING return must check the 5ms tick deadline before the next CPU insertion");
        assertTrue(hostCoreSource.contains("if (pending.completionRoute() == TaskCompletionRoute.CPU_WAITING)"),
                "CPU_WAITING return leftovers must stay on the CPU retry path");
        assertTrue(hostCoreSource.indexOf("? routePayloadThroughCpu(pending, payloadSlice.slice(), hardDeadlineNanos)")
                        < hostCoreSource.indexOf("List<GenericStack> remainingSlicePayload = routePayloadIntoAeNetwork("),
                "CPU_WAITING return leftovers must not fall through to AE storage before CPU retry");
        assertFalse(hostCoreSource.contains("adaptiveCpuWaitingReturnStackLimit"),
                "CPU_WAITING return must not retain an adaptive stack cap after deadline stops");
        assertTrue(hostCoreSource.contains("new FormalMachineTickBudgetSnapshot("),
                "formal machine must publish tick budget snapshots from runtime ticks");
        assertTrue(tickBudgetSnapshotSource.contains("long softBudgetNanos"),
                "tick budget snapshot must expose soft budget nanos");
        assertTrue(tickBudgetSnapshotSource.contains("long elapsedNanos"),
                "tick budget snapshot must expose elapsed nanos");
        assertTrue(planningSource.contains("CompletableFuture<ICraftingPlan> future = new CompletableFuture<>()"),
                "formal planning aggregation must complete a future after server-thread telemetry");
        assertTrue(planningSource.contains("future.completeOnTimeout("),
                "formal planning aggregation future must not remain pending forever if server-thread completion stalls");
        assertTrue(planningSource.contains("level.getServer().execute(completion)"),
                "formal planning telemetry must be applied on the server thread");
        assertTrue(planningSource.contains("catch (RejectedExecutionException exception)"),
                "formal planning aggregation must complete the future if server-thread callback scheduling is rejected");
        String planningWorkerSource = planningSource.substring(
                planningSource.indexOf("private static PlanningComputationResult runFormalPlanning"),
                planningSource.indexOf("private static void applyPlanningTelemetry")
        );
        assertFalse(planningWorkerSource.contains("recordDeterministicPlanningHitForTest"),
                "formal planning worker must not write block-entity deterministic telemetry directly");
        assertFalse(planningWorkerSource.contains("recordPlanningAggregationFailureForTest"),
                "formal planning worker must not write block-entity failure telemetry directly");
        assertTrue(snapshotSource.contains("long cpuWaitingReturnAmount"),
                "benchmark snapshot must expose CPU_WAITING return amount telemetry");
        assertTrue(snapshotSource.contains("long formalTimingCorrectionCount"),
                "benchmark snapshot must expose formal timing correction telemetry");
        assertTrue(snapshotSource.contains("long formalTimingProgressClampCount"),
                "benchmark snapshot must expose formal timing progress clamp telemetry");
        assertTrue(snapshotSource.contains("long formalTimingEtaClampCount"),
                "benchmark snapshot must expose formal timing ETA clamp telemetry");
        assertTrue(snapshotSource.contains("long formalStatusHeartbeatCount"),
                "benchmark snapshot must expose formal status heartbeat telemetry");
        assertTrue(snapshotSource.contains("long cpuWaitingReturnBudgetStopCount"),
                "benchmark snapshot must expose CPU_WAITING budget-stop telemetry");
        assertTrue(snapshotSource.contains("long largestCpuWaitingReturnAmount"),
                "benchmark snapshot must expose largest CPU_WAITING payload telemetry");
        assertTrue(snapshotSource.contains("long cpuWaitingReturnOverBudgetCount"),
                "benchmark snapshot must expose CPU_WAITING over-budget telemetry");
        assertTrue(snapshotSource.contains("long cpuWaitingAeFallbackPartialInsertCount"),
                "benchmark snapshot must expose CPU_WAITING AE fallback partial insert telemetry");
        assertTrue(snapshotSource.contains("long cpuWaitingNoProgressRetries"),
                "benchmark snapshot must expose CPU_WAITING no-progress retry telemetry");
        assertTrue(snapshotSource.contains("long cpuWaitingRouteNanosMax"),
                "benchmark snapshot must expose CPU_WAITING route nanos telemetry");
        assertTrue(snapshotSource.contains("long tickBudgetHardStopCount"),
                "benchmark snapshot must expose tick budget hard-stop telemetry");
        assertTrue(snapshotSource.contains("long maxTickBudgetNanosObserved"),
                "benchmark snapshot must expose max observed tick nanos");
        assertTrue(snapshotSource.contains("long maxExecutableRunsHitCount"),
                "benchmark snapshot must expose bulk extraction hit telemetry");
        assertTrue(snapshotSource.contains("long maxExecutableRunsFallbackCount"),
                "benchmark snapshot must expose bulk extraction fallback telemetry");
        assertTrue(snapshotSource.contains("int bulkExtractionLogicalExecutionsMax"),
                "benchmark snapshot must expose max bulk extraction size telemetry");
        assertTrue(snapshotSource.contains("long templatedDispatchHitCount"),
                "benchmark snapshot must expose dispatch-time template hit telemetry");
        assertTrue(snapshotSource.contains("long compileCacheHitCount"),
                "benchmark snapshot must expose dispatch compile-cache hit telemetry");
        assertTrue(snapshotSource.contains("long providerOverpressureRejectCount"),
                "benchmark snapshot must expose dispatch overpressure rejection telemetry");
        assertTrue(hostCoreSource.contains("private static final long TICK_SOFT_BUDGET_NANOS = 4_000_000L"),
                "formal machine soft tick budget must stay at the planned 4ms target");
        assertTrue(hostCoreSource.contains("recordFormalTimingCorrectionForTest(boolean correction, boolean progressClamp, boolean etaClamp)"),
                "formal timing correction counter must distinguish job correction from progress and ETA categories");
        String timingSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/formalmachine/FormalMachineCraftingTimingService.java"
        ));
        assertTrue(timingSource.contains("public static boolean hasActiveState(CraftingCpuLogic logic)"),
                "formal timing bridge must expose the active-state probe for status mixins");
        assertTrue(timingSource.contains("public static void recordFormalStatusHeartbeat(CraftingCpuLogic logic)"),
                "formal timing bridge must expose a formal status heartbeat recording entry");
        assertTrue(timingSource.contains("formalStatusHeartbeatCount"),
                "formal timing bridge must count formal status heartbeats in TimingState");
        assertTrue(timingSource.contains("private long canonicalStatusElapsedTime;"),
                "formal timing bridge must keep a dedicated AE2 status elapsed tracker");
        assertTrue(timingSource.contains("private long canonicalJobElapsedNanos;"),
                "formal timing bridge must keep a dedicated CPU-list elapsed tracker");
        assertTrue(timingSource.contains("public static boolean shouldSendHeartbeat(CraftingCpuLogic logic)"),
                "formal timing bridge must expose a heartbeat gate tied to visible AE2 progress");
        assertTrue(timingSource.contains("private long sanitizeStatusElapsed(long observedElapsed, long progress, boolean jobFinished)"),
                "formal timing bridge must sanitize AE2 status elapsed without mixing in raw wall-clock nanos");
        assertTrue(timingSource.contains("private long sanitizeJobElapsedNanos(long observedElapsedNanos, long progress, boolean jobFinished)"),
                "formal timing bridge must sanitize CPU-list elapsed in nanos");
        assertTrue(timingSource.contains("if (!jobFinished && progress <= 0L)"),
                "formal timing bridge must suppress elapsed growth before AE2 observes native progress");
        assertFalse(timingSource.contains("progressFloor("),
                "formal timing bridge must not synthesize progress floors from formal-machine local completion");
        assertFalse(timingSource.contains("expectedOutputAmount"),
                "formal timing bridge must not derive display progress from local expected output totals");
        assertFalse(timingSource.contains("createdAtNanos"),
                "formal timing bridge must not synthesize CPU-list elapsed from local wall-clock age");
        assertFalse(timingSource.contains("ELAPSED_TIME_GRACE_NANOS"),
                "formal timing bridge must not clamp AE2 elapsed time down to local state age");
        assertFalse(timingSource.contains("if (state.isAllExpectedOutputCompleted())"),
                "formal timing bridge must not clear the job before the source AE2 CPU has actually finished");
        assertFalse(timingSource.contains("ACTIVE_FORMAL_JOB_MIN_DISPLAY_PROGRESS"),
                "formal timing bridge must not create synthetic progress before CPU_WAITING output returns");
        String statusCorrectionSource = timingSource.substring(
                timingSource.indexOf("public static CraftingStatus correctStatus"),
                timingSource.indexOf("public static CraftingJobStatus correctJobStatus")
        );
        int stateMiss = statusCorrectionSource.indexOf("if (state == null)");
        int stateMissReturn = statusCorrectionSource.indexOf("return status;", stateMiss);
        int heartbeatAfterStateMiss = statusCorrectionSource.indexOf("recordFormalStatusHeartbeat(state);", stateMiss);
        assertTrue(stateMiss >= 0 && stateMissReturn > stateMiss && heartbeatAfterStateMiss < 0,
                "ordinary AE2 status must be returned unchanged when no formal TimingState exists");
        assertTrue(timingSource.contains("canonicalStatusElapsedTime"),
                "formal timing bridge must share AE2 status elapsed state across detail-view samples");
        assertTrue(timingSource.contains("canonicalJobElapsedNanos"),
                "formal timing bridge must share CPU-list elapsed nanos across CPU samples");
        assertTrue(timingSource.contains("canonicalTotalItems"),
                "formal timing bridge must share total-item state across status views");
        assertTrue(timingSource.contains("boolean jobFinished = accessor.getRemainingAmount() <= 0L"),
                "formal timing bridge must detect unfinished AE2 jobs before clamping display progress");
        assertFalse(timingSource.contains("completed <= 0L ? 0L : elapsed"),
                "formal timing bridge must not hide elapsed time while progress is still zero");
        assertFalse(timingSource.contains("lastJobElapsedTime"),
                "formal timing bridge must not keep a separate CPU-list elapsed state");
        assertFalse(timingSource.contains("lastElapsedTime"),
                "formal timing bridge must not keep a separate details-view elapsed state");
        String dispatchSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/formalmachine/FormalMachineCraftingDispatchService.java"
        ));
        assertTrue(dispatchSource.contains("private static final ConcurrentMap<UUID, SourceCpuHandle> SOURCE_CPUS_BY_JOB"),
                "formal dispatch service must track source CPUs through the SourceCpuHandle abstraction");
        assertTrue(dispatchSource.contains("public static SourceCpuHandle getSourceCpuHandle("),
                "formal dispatch service must expose source CPU handle lookup for external AE returns");
        assertTrue(dispatchSource.contains("new NativeSourceCpuHandle")
                        && dispatchSource.contains("new ParallelActiveCpuHandle"),
                "formal dispatch service must bridge both native and parallel CPUs into source CPU handles");
        assertTrue(dispatchSource.contains("cluster.findLaneByCraftingId(craftingId)"),
                "formal dispatch service must resolve parallel source CPUs through exact craft-id lane lookup");
        assertFalse(hostCoreSource.contains("withCpuInsertionBypassed("),
                "formal machine host must not bypass AE2 CPU insertion on external returns");
        assertTrue(dispatchSource.contains("FormalMachineCraftingTimingService.beginSubmittedJob(")
                        && dispatchSource.contains("submitResult.link().getCraftingID()")
                        && dispatchSource.contains("host,")
                        && dispatchSource.contains("job"),
                "formal dispatch submit tail must seed timing state with the whole AE2 plan");
        String formalServiceMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceFormalMachineMixin.java"
        ));
        String parallelServiceMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceParallelCpuMixin.java"
        ));
        assertFalse(formalServiceMixinSource.contains("chexsonsaeutils$bypassFormalMachineCpuInsertion"),
                "formal CraftingService mixin must no longer short-circuit AE2 CPU interception");
        assertFalse(formalServiceMixinSource.contains("cir.setReturnValue(0L)"),
                "formal CraftingService mixin must not bypass insertIntoCpus anymore");
        assertTrue(formalServiceMixinSource.contains("FormalMachineCraftingDispatchService.onInsertIntoCpus("),
                "formal CraftingService mixin must still observe AE2 CPU insert wakeups");
        assertTrue(parallelServiceMixinSource.contains("chexsonsaeutils$registerFormalMachineSubmitResult(")
                        && parallelServiceMixinSource.contains("FormalMachineCraftingDispatchService.onSubmitJobTail("),
                "parallel fake-pool submit path must explicitly seed formal submit-tail registration after early return");
        assertTrue(hostCoreSource.contains("routePayloadIntoAeNetwork(")
                        && hostCoreSource.contains("AeCpuIngressRouter.routePayload("),
                "formal-machine AE return path must reuse the shared CPU-first ingress router");
        assertTrue(smokeHandlerSource.contains(
                        "result.put(\"maxExecutableRunsHitCount\", snapshot.maxExecutableRunsHitCount())"),
                "MCP benchmark probe must expose bulk extraction hit telemetry");
        assertTrue(smokeHandlerSource.contains(
                        "result.put(\"maxExecutableRunsFallbackCount\", snapshot.maxExecutableRunsFallbackCount())"),
                "MCP benchmark probe must expose bulk extraction fallback telemetry");
        assertTrue(smokeHandlerSource.contains(
                        "result.put(\"bulkExtractionLogicalExecutionsMax\", snapshot.bulkExtractionLogicalExecutionsMax())"),
                "MCP benchmark probe must expose max bulk extraction size telemetry");
        assertTrue(smokeHandlerSource.contains(
                        "result.put(\"templatedDispatchHitCount\", snapshot.templatedDispatchHitCount())"),
                "MCP benchmark probe must expose dispatch-time template hit telemetry");
        assertTrue(smokeHandlerSource.contains(
                        "result.put(\"compileCacheHitCount\", snapshot.compileCacheHitCount())"),
                "MCP benchmark probe must expose dispatch compile-cache hit telemetry");
        assertTrue(smokeHandlerSource.contains(
                        "result.put(\"providerOverpressureRejectCount\", snapshot.providerOverpressureRejectCount())"),
                "MCP benchmark probe must expose dispatch overpressure rejection telemetry");
        assertTrue(gameTestSource.contains("snapshot.cpuWaitingReturnAmount()"),
                "formal machine GameTests must observe CPU_WAITING return telemetry");
        assertTrue(gameTestSource.contains("snapshot.formalTimingCorrectionCount()"),
                "formal machine GameTests must observe formal timing correction telemetry");
        assertTrue(gameTestSource.contains("snapshot.formalTimingEtaClampCount()"),
                "formal machine GameTests must observe formal timing ETA telemetry");
        assertTrue(gameTestSource.contains("assertCraftingCpuMenuHeartbeat"),
                "formal machine GameTests must cover the real CraftingCPUMenu heartbeat path");
        assertTrue(gameTestSource.contains("formalStatusHeartbeatCount()"),
                "formal machine GameTests must observe formal status heartbeat telemetry");
        assertTrue(gameTestSource.contains("snapshot.cpuWaitingReturnBudgetStopCount()"),
                "formal machine GameTests must observe CPU_WAITING budget-stop telemetry");
        assertTrue(gameTestSource.contains("snapshot.largestCpuWaitingReturnAmount()"),
                "formal machine GameTests must observe largest CPU_WAITING payload telemetry");
        assertTrue(gameTestSource.contains("snapshot.cpuWaitingReturnOverBudgetCount()"),
                "formal machine GameTests must observe CPU_WAITING over-budget telemetry");
        assertTrue(gameTestSource.contains("snapshot.tickBudgetHardStopCount()"),
                "formal machine GameTests must observe tick budget hard-stop telemetry");
        assertTrue(gameTestSource.contains("snapshot.maxTickBudgetNanosObserved()"),
                "formal machine GameTests must observe max tick budget telemetry");
        assertFalse(gameTestSource.contains("snapshot.maxTickBudgetNanosObserved() <= 6_000_000L"),
                "formal machine GameTests must not treat the 6ms emergency threshold as the normal hard target");
        assertTrue(gameTestSource.contains("STATUS_SAMPLE_MAX_ELAPSED_STEP_NANOS"),
                "formal machine status GameTest must bound elapsed-time sample jumps");
        assertTrue(gameTestSource.contains("formal machine status timing test should sample at least two live CPU statuses"),
                "formal machine status GameTest must compare live status samples");
        assertTrue(gameTestSource.contains("formal machine status elapsed time must not go backwards"),
                "formal machine status GameTest must reject detail-view elapsed regressions");
        assertTrue(gameTestSource.contains("formal machine status elapsed time must not jump between live samples"),
                "formal machine status GameTest must reject jumped detail-view elapsed time");
        assertTrue(gameTestSource.contains("formal machine status elapsed time must stay at zero before AE2 observes progress"),
                "formal machine status GameTest must reject elapsed growth before native AE2 progress exists");
        assertTrue(gameTestSource.contains("formal machine status remaining amount must not increase"),
                "formal machine status GameTest must reject remaining-count rebound");
        assertTrue(gameTestSource.contains("STATUS_SAMPLE_MAX_ETA_NANOS"),
                "formal machine status GameTest must bound ETA instead of only elapsed deltas");
        assertTrue(gameTestSource.contains("ETA must stay bounded"),
                "formal machine status GameTest must reject impossible ETA values");
        assertTrue(gameTestSource.contains("formal machine CPU list elapsed time must not go backwards"),
                "formal machine status GameTest must reject CPU-list elapsed regressions");
        assertTrue(gameTestSource.contains("formal machine CPU list elapsed time must not jump between live samples"),
                "formal machine status GameTest must reject jumped CPU-list elapsed time");
        assertTrue(gameTestSource.contains("formal machine CPU list elapsed time must stay at zero before AE2 observes progress"),
                "formal machine status GameTest must reject CPU-list elapsed growth before native AE2 progress exists");
        assertTrue(gameTestSource.contains("formalMachineUnsupportedLargePlanningFailsFast"),
                "formal machine GameTests must cover bounded fail-fast planning for unsupported formal paths");
        assertTrue(planningSource.contains("return CompletableFuture.completedFuture(missingPlan(what, amount));"),
                "unsupported large formal-machine planning must not fall through to AE2 native planning");
        assertTrue(planningSource.contains("strategy == CalculationStrategy.CRAFT_LESS"),
                "formal planning aggregation must cover the real CraftConfirmMenu CRAFT_LESS path");
        assertTrue(planningSource.contains("FormalProviderOwnership")
                        && planningSource.contains("formalProviderOwnership("),
                "formal planning analysis must preserve formal ownership for duplicate-output unsupported paths");
        String heartbeatMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftingCPUMenuFormalMachineHeartbeatMixin.java"
        ));
        assertTrue(heartbeatMixinSource.contains("CHEXSONSAEUTILS_FORMAL_STATUS_HEARTBEAT_INTERVAL_TICKS = 20"),
                "formal status heartbeat must use the planned 20-tick detail-view cadence");
        assertTrue(heartbeatMixinSource.contains("FormalMachineCraftingTimingService.createHeartbeatStatus"),
                "formal status heartbeat must send a timing-only incremental status");
        assertTrue(heartbeatMixinSource.contains("FormalMachineCraftingTimingService.shouldSendHeartbeat"),
                "formal status heartbeat must only send timing refreshes once formal progress is visible");
        assertFalse(heartbeatMixinSource.contains("IncrementalUpdateHelper heartbeatChanges = new IncrementalUpdateHelper()"),
                "formal status heartbeat must not create a fresh full-update helper");
        assertTrue(timingSource.contains("public static CraftingStatus createHeartbeatStatus(CraftingCpuLogic logic)"),
                "formal timing bridge must own status-only heartbeat construction");
        String heartbeatStatusSource = timingSource.substring(
                timingSource.indexOf("public static CraftingStatus createHeartbeatStatus"),
                timingSource.indexOf("public static CraftingStatus correctStatus")
        );
        assertTrue(heartbeatStatusSource.contains("false,"),
                "formal status heartbeat must not clear client-side crafting entries");
        assertTrue(heartbeatStatusSource.contains("List.of()"),
                "formal status heartbeat must not resend or delete crafting entries");
        assertTrue(heartbeatStatusSource.contains("logic.getElapsedTimeTracker().getRemainingItemCount()"),
                "formal status heartbeat must reuse AE2 remaining-item counts");
        assertTrue(heartbeatStatusSource.contains("logic.getElapsedTimeTracker().getStartItemCount()"),
                "formal status heartbeat must reuse AE2 start-item counts");
        assertFalse(timingSource.contains("HEARTBEAT_DISPLAY_TOTAL_WORK"),
                "formal status updates must not keep a client-only timing signature");
        assertFalse(timingSource.contains("DISPLAY_TOTAL_WORK = 1_000_000L"),
                "formal timing bridge must not rescale AE2 progress into a synthetic display total");
        assertTrue(statusCorrectionSource.contains("long start = Math.max(1L, status.getStartItemCount())"),
                "formal corrected status must preserve AE2 start-item scale");
        assertTrue(statusCorrectionSource.contains("long observedRemaining = Math.max(0L, Math.min(start, status.getRemainingItemCount()))"),
                "formal corrected status must read AE2 native remaining work first");
        assertTrue(statusCorrectionSource.contains("long remaining = Math.max(0L, start - completed)"),
                "formal corrected status must derive remaining work directly from AE2-native totals");
        assertTrue(hostCoreSource.contains("createSlicePendingReturn("),
                "formal machine CPU_WAITING completion must be able to hand off slice payloads before full batch completion");
        assertFalse(mixinConfigSource.contains("\"ae2.client.gui.CraftingCPUScreenFormalMachineStatusMixin\""),
                "formal machine client status mixin must be removed so AE2 keeps vanilla screen behavior");
        assertFalse(mixinPluginSource.contains("CraftingCPUScreenFormalMachineStatusMixin"),
                "formal machine mixin plugin must not keep a stale client CPU screen override");
        assertFalse(heartbeatMixinSource.contains("selectedCpu.craftingLogic.getAllItems(allItems)"),
                "formal status heartbeat must not force full item-key updates");
        assertFalse(heartbeatMixinSource.contains("CraftingStatus.create(this.incrementalUpdateHelper"),
                "formal status heartbeat must not send an empty delta from AE2's menu helper");
        assertFalse(gameTestSource.contains("OldHighCapacityCraftingMachine"),
                "formal machine tests must not restore legacy test-machine coverage");
    }

    @Test
    void schedulerDeduplicatesDirtySlotsAndRangeMarks() {
        DirtySlotPatternRefreshScheduler scheduler = new DirtySlotPatternRefreshScheduler(16);

        scheduler.markDirty(2);
        scheduler.markDirty(2);
        scheduler.markRangeDirty(4, 7);
        scheduler.markRangeDirty(6, 9);

        assertTrue(scheduler.hasPendingWork());
        assertEquals(List.of(2, 4, 5, 6, 7, 8), scheduler.drainDirtySlots());
        assertFalse(scheduler.hasPendingWork());
    }
}
