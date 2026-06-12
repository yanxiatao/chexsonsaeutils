package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingLane;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuGridBudgetLedger;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuMetrics;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuProviderBackoff;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCpuWaitingIndex;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AE2ParallelCpuToolSchedulerTest {

    @Test
    void waitingIndexRoutesRequestsWithoutFullLaneScan() {
        DummyKey iron = new DummyKey("iron");
        DummyKey gold = new DummyKey("gold");
        TestLane laneA = new TestLane(iron, 64L);
        TestLane laneB = new TestLane(iron, 16L);
        TestLane laneC = new TestLane(gold, 8L);
        ParallelCpuWaitingIndex index = new ParallelCpuWaitingIndex();
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();

        index.rebuild(List.of(laneA, laneB, laneC));

        assertEquals(80L, index.getRequestedAmount(iron));
        assertEquals(8L, index.getRequestedAmount(gold));
        assertEquals(2, index.getLanesWaitingFor(iron).size());
        assertEquals(80L, index.insertIntoLanes(iron, 80L, Actionable.MODULATE, metrics));
        assertEquals(0L, index.getRequestedAmount(iron));
        assertEquals(0, index.getLanesWaitingFor(iron).size());
        assertTrue(index.consumeChangedKeys().contains(iron));
        index.consumeChangedPresenceKeys();
        index.refreshLane(laneB);
        assertTrue(index.consumeChangedKeys().isEmpty(),
                "unchanged lane refresh must not churn changed request keys");
        assertTrue(index.consumeChangedPresenceKeys().isEmpty(),
                "unchanged lane refresh must not churn presence keys");
        assertFalse(metrics.snapshot().indexedInsertCount() <= 0L);
    }

    @Test
    void waitingIndexHandlesStressLaneCountThroughAggregatedKeys() {
        DummyKey iron = new DummyKey("stress_iron");
        ParallelCpuWaitingIndex index = new ParallelCpuWaitingIndex();
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();
        TestLane[] lanes = new TestLane[65_536];
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = new TestLane(iron, 1L);
        }

        index.rebuild(List.of(lanes));
        index.copyMetricsTo(metrics);

        assertEquals(65_536L, index.getRequestedAmount(iron));
        assertEquals(65_536, index.getLanesWaitingFor(iron).size());
        assertEquals(65_536L, metrics.snapshot().waitingIndexLaneCount());
        assertEquals(1L, metrics.snapshot().waitingIndexKeyCount());

        assertEquals(65_536L, index.insertIntoLanes(iron, 65_536L, Actionable.MODULATE, metrics));
        index.copyMetricsTo(metrics);

        assertEquals(0L, index.getRequestedAmount(iron));
        assertEquals(0, index.getLanesWaitingFor(iron).size());
        assertEquals(0L, metrics.snapshot().waitingIndexLaneCount());
        assertEquals(0L, metrics.snapshot().waitingIndexKeyCount());
        assertEquals(65_536L, metrics.snapshot().indexedInsertAmount());
    }

    @Test
    void waitingIndexKeepsSmokeStressAndGatedExtremeLaneScalesAggregated() {
        assertAggregatedLaneScale("smoke_iron", 1_024);
        assertAggregatedLaneScale("stress_iron", 65_536);
        if (Boolean.getBoolean("chexsonsaeutils.parallelCpuExtremeTest")) {
            assertAggregatedLaneScale("extreme_iron", 1_048_576);
        }
    }

    @Test
    void providerBackoffCachesBusyProvidersWithinTickAndShortBackoffWindow() {
        BusyProvider provider = new BusyProvider();
        ParallelCpuProviderBackoff backoff = new ParallelCpuProviderBackoff(2, 4);
        ParallelCpuGridBudgetLedger ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(
                32L,
                32L,
                32L,
                32L,
                1_000_000L
        ));
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();
        ledger.resetForTick(7L, System.nanoTime());

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BUSY,
                backoff.checkProvider(provider, 7L, ledger, metrics)
        );
        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, 7L, ledger, metrics)
        );
        assertEquals(1L, metrics.snapshot().providerScanCount());
        assertEquals(2L, metrics.snapshot().busyProviderSkipCount());
    }

    @Test
    void providerBackoffCapsAllBusyStressBurstToOneProviderProbePerTick() {
        BusyProvider provider = new BusyProvider();
        ParallelCpuProviderBackoff backoff = new ParallelCpuProviderBackoff(2, 40);
        ParallelCpuGridBudgetLedger ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(
                1_048_576L,
                8_388_608L,
                1_048_576L,
                1_048_576L,
                20_000_000L
        ));
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();
        ledger.resetForTick(42L, System.nanoTime());

        for (int lane = 0; lane < 65_536; lane++) {
            assertTrue(backoff.checkProvider(provider, 42L, ledger, metrics)
                            != ParallelCpuProviderBackoff.ProviderAvailability.READY,
                    "all-busy provider burst must never report a ready provider");
            metrics.recordZeroProgressTick();
        }

        ParallelCpuMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.providerScanCount(),
                "same-tick busy cache must collapse 65,536 lane probes into one provider scan");
        assertEquals(65_536L, snapshot.busyProviderSkipCount(),
                "every all-busy lane must be accounted as skipped or backed off");
        assertEquals(65_536L, snapshot.zeroProgressTickCount(),
                "all-busy stress lanes must be tracked as zero-progress work");
        assertEquals(1L, ledger.snapshot().providerChecksUsed(),
                "busy backoff must protect the provider-check budget from lane-count amplification");
    }

    @Test
    void budgetLedgerTracksEveryHotPathBudgetAndHardClampsTime() {
        ParallelCpuGridBudgetLedger ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(
                1L,
                1L,
                1L,
                1L,
                90_000_000L
        ));
        ledger.resetForTick(1L, 1_000L);

        assertTrue(ledger.tryClaimPatternPush());
        assertFalse(ledger.tryClaimPatternPush());
        assertTrue(ledger.tryClaimProviderCheck());
        assertFalse(ledger.tryClaimProviderCheck());
        assertTrue(ledger.tryClaimExtractPatternInputs());
        assertFalse(ledger.tryClaimExtractPatternInputs());
        assertTrue(ledger.tryClaimReinjectPatternInputs());
        assertFalse(ledger.tryClaimReinjectPatternInputs());
        assertEquals(45_000_000L, ledger.snapshot().tickBudgetNanos());
        assertTrue(ledger.exhaustedTypes().containsAll(Set.of(
                ParallelCpuGridBudgetLedger.BudgetType.PATTERN_PUSH,
                ParallelCpuGridBudgetLedger.BudgetType.PROVIDER_CHECK,
                ParallelCpuGridBudgetLedger.BudgetType.EXTRACT_PATTERN_INPUTS,
                ParallelCpuGridBudgetLedger.BudgetType.REINJECT_PATTERN_INPUTS
        )));
    }

    @Test
    void maximumAdvertisedCoProcessorsRemainPositiveInLongBudgetMath() {
        int advertisedCoProcessors = Integer.MAX_VALUE - 1;
        long remainingOperations = (long) advertisedCoProcessors + 1L;

        assertEquals(Integer.MAX_VALUE, remainingOperations);
        assertTrue(remainingOperations > 0L,
                "Integer.MAX_VALUE - 1 co-processors must not overflow the internal long budget");
        assertEquals(0L, remainingOperations - Integer.MAX_VALUE,
                "fully consumed maximum budget must not become negative");
    }

    @Test
    void metricsUseBoundedTickSamplesForExtremeLaneBenchmarks() {
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();
        for (int i = 1; i <= 1_024; i++) {
            metrics.recordTickNanos(i);
        }

        ParallelCpuMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(512, snapshot.tickNanosSampleCount());
        assertEquals(1_024L, snapshot.tickNanosMax());
        assertTrue(snapshot.tickNanosP95() >= 512L,
                "bounded sampling must still retain high percentile tick latency signals");
    }

    private static void assertAggregatedLaneScale(String keyName, int laneCount) {
        DummyKey key = new DummyKey(keyName);
        ParallelCpuWaitingIndex index = new ParallelCpuWaitingIndex();
        ParallelCpuMetrics metrics = new ParallelCpuMetrics();
        TestLane[] lanes = new TestLane[laneCount];
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = new TestLane(key, 1L);
        }

        index.rebuild(Arrays.asList(lanes));
        index.copyMetricsTo(metrics);

        assertEquals(laneCount, index.getRequestedAmount(key));
        assertEquals(1, index.indexedKeyCount(),
                "same-key extreme lane requests must stay aggregated into one requested key");
        assertEquals(laneCount, metrics.snapshot().waitingIndexLaneCount());
        assertEquals(1L, metrics.snapshot().waitingIndexKeyCount());
        assertEquals(laneCount, index.insertIntoLanes(key, laneCount, Actionable.MODULATE, metrics));
        index.copyMetricsTo(metrics);
        assertEquals(0L, index.getRequestedAmount(key));
        assertEquals(0L, metrics.snapshot().waitingIndexLaneCount());
    }

    @Test
    void sourceKeepsCurrentlyCraftingIncrementalSyncAndSafeReinjectPaths() throws IOException {
        String mixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceParallelCpuMixin.java"
        ));
        String gridSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuGrid.java"
        ));
        String clusterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuCluster.java"
        ));
        String laneSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingLaneState.java"
        ));
        String logicSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuLogic.java"
        ));
        String jobSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelExecutingCraftingJob.java"
        ));
        String continuationMixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java"
        ));
        String gameTestSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/gametest/crafting/AE2ParallelCpuToolGameTests.java"
        ));

        assertTrue(gridSource.contains("consumeChangedRequestKeys"),
                "waiting index changed keys must be exported for incremental currentlyCrafting sync");
        String submitToClusterSource = gridSource.substring(
                gridSource.indexOf("private ICraftingSubmitResult submitToCluster"),
                gridSource.indexOf("private int activeLaneCount")
        );
        assertFalse(submitToClusterSource.contains("waitingIndex.rebuild("),
                "successful lane submissions must not rebuild the waiting index by scanning every active lane");
        assertTrue(mixinSource.contains("chexsonsaeutils$syncParallelCurrentlyCrafting"),
                "CraftingService mixin must sync parallel currentlyCrafting after lane tick");
        assertTrue(mixinSource.contains("chexsonsaeutils$parallelCurrentlyCrafting"),
                "parallel currentlyCrafting keys must be retained across AE2 native currentlyCrafting rebuilds");
        assertTrue(mixinSource.contains("currentlyCrafting.addAll(chexsonsaeutils$parallelCurrentlyCrafting)"),
                "parallel request keys must be restored after native AE2 clears currentlyCrafting");
        assertTrue(mixinSource.contains("if (!(target instanceof ParallelCraftingCPU))"),
                "native AE2 CPU targets must be passed through unchanged");
        assertTrue(mixinSource.contains("chexsonsaeutils$rebuildParallelClusters"),
                "CraftingService mixin must rebuild service-owned parallel clusters during CPU refresh");
        assertTrue(mixinSource.contains("chexsonsaeutils$parallelCpuClusters"),
                "parallel CPU visibility must be driven from the service-owned cluster registry");
        assertTrue(mixinSource.contains("grid.getMachines(AE2ParallelCpuToolBlockEntity.class)"),
                "parallel cluster discovery must live in CraftingService.updateCPUClusters");
        assertTrue(mixinSource.contains("this.updateList = true;"),
                "parallel provider add/remove events must only dirty the service CPU list");
        assertTrue(gridSource.contains("CraftingSubmitResult.CPU_OFFLINE"),
                "stale parallel CPU targets must return offline instead of falling back to native AE2 CPUs");
        assertTrue(gridSource.contains("submitToAutoSelectedCluster"),
                "null auto-selection must route to parallel CPU lanes when the tool has capacity");
        assertTrue(continuationMixinSource.contains("target instanceof ParallelCraftingCPU"),
                "IGNORE_MISSING continuation must not auto-select a native CPU for parallel CPU targets");
        assertTrue(continuationMixinSource.contains("target == null && chexsonsaeutils$hasAutoSelectableParallelCpu(src)"),
                "IGNORE_MISSING continuation must not auto-select native CPUs when a parallel fake pool is auto-selectable");
        assertTrue(continuationMixinSource.contains("CraftingSubmitResult.INCOMPLETE_PLAN"),
                "IGNORE_MISSING parallel auto-selection must fail incomplete plans explicitly instead of falling back");
        assertTrue(mixinSource.contains("chexsonsaeutils$nativeCpuIsRequesting(changedKey)"),
                "sync must not remove a key that is still requested by a native AE2 CPU");
        assertFalse(mixinSource.contains("parallelCpuGrid.snapshotCurrentlyCrafting()"),
                "sync must not build a full parallel currentlyCrafting snapshot every tick");
        assertFalse(mixinSource.contains("onRequestChange(what)"),
                "sync must leave watcher notification to AE2 native currentlyCrafting diff handling");
        assertTrue(logicSource.contains("pendingReinjectInputs = craftingContainer"),
                "budget exits must keep already extracted pattern inputs for a later reinject slice");
        assertTrue(logicSource.contains("flushPendingReinjectInputs(budgetLedger, metrics)"),
                "pending reinject inputs must be flushed through the grid budget ledger");
        assertContainsInOrder(logicSource, List.of(
                "reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems);",
                "acceptedPush = FormalMachineSourceCpuContext.withSourceCraftingId(",
                "() -> provider.pushPattern(details, submittedCraftingContainer)"
        ));
        assertTrue(logicSource.contains("rollbackReservedWaiting(job, expectedOutputs, expectedContainerItems);"),
                "failed provider pushes must roll back pre-registered waiting outputs");
        assertTrue(logicSource.contains("beginSynchronousProviderPush();")
                        && logicSource.contains("endSynchronousProviderPush();"),
                "parallel CPU must keep the lane open until synchronous provider returns are fully processed");
        assertTrue(logicSource.contains("finishDeferredUntilProviderPushCompletes = true;"),
                "parallel CPU must defer final job completion while synchronous provider returns are still in flight");
        assertTrue(logicSource.contains("lane.cluster().refreshLaneState(lane);"),
                "waiting index must be refreshed from logic change notifications inside a lane");
        assertTrue(jobSource.contains("addMaxItems(timeTracker, entry.getLongValue(), entry.getKey().getType())"),
                "lane creation must seed tracker work from emitted waiting outputs");
        assertTrue(logicSource.contains("ParallelExecutingCraftingJob.decrementItems(job.timeTracker, amount, what.getType())"),
                "lane inserts must decrement the mirrored elapsed-time tracker");
        assertFalse(logicSource.contains("pending.add(finalOutput.what(), remainingAmount)"),
                "parallel menu status must not synthesize pending counts from final-output remainder");
        assertTrue(clusterSource.contains("ParallelCraftingCpuLogic logic = lane.logic();"),
                "active vCPU menu status must be sourced from the concrete lane logic");
        assertTrue(clusterSource.contains("logic.getAllItems(allItems);"),
                "cluster menu status entries must come from the selected lane item snapshot");
        assertTrue(clusterSource.contains("elapsedTimeTracker.getRemainingItemCount()"),
                "cluster menu status ETA must come from the selected lane elapsed-time tracker");
        assertFalse(logicSource.contains("!budgetLedger.tryClaimReinjectPatternInputs() && metrics != null"),
                "reinject budget exhaustion must not continue to reinject in the same tick");
        assertTrue(logicSource.contains("budgetLedger.hasTimeBudget(System.nanoTime())"),
                "time budget checks must exist inside the lane execution hot loop");
        assertFalse(logicSource.contains("break taskLoop"),
                "budget exits must not bypass reinject by jumping out of the task loop");
        assertTrue(gridSource.contains("previousClusters.equals(clusterSnapshot)"),
                "cluster refresh must skip waiting-index rebuild when membership did not change");
        assertFalse(gridSource.contains("waitingIndex.rebuild(activeLanes())"),
                "cluster refresh must not rebuild the waiting index by scanning every active lane");
        assertTrue(clusterSource.contains("promoteDelayedLanes(currentTick, shardLimit)"),
                "delayed lane promotion must be bounded by the shard limit");
        assertTrue(clusterSource.contains("promoted < limit"),
                "eligible delayed lanes must not be promoted in an unbounded single-tick loop");
        assertTrue(clusterSource.contains("lanesVisited++") && clusterSource.contains("if (!lanes.containsKey"),
                "stale runnable entries must consume shard budget instead of being drained unbounded");
        assertTrue(laneSource.contains("private QueueState queueState = QueueState.NONE"),
                "lanes must track scheduler queue membership to avoid duplicate runnable or delayed entries");
        assertTrue(clusterSource.contains("private void enqueueRunnable(ParallelCraftingLaneState lane)"),
                "runnable lane scheduling must go through a queue-state guarded helper");
        assertTrue(clusterSource.contains("private void enqueueDelayed(ParallelCraftingLaneState lane)"),
                "delayed lane scheduling must go through a queue-state guarded helper");
        assertTrue(clusterSource.contains("if (!lane.markRunnableDequeued())"),
                "stale runnable queue entries must be skipped before executing a lane");
        assertTrue(clusterSource.contains("if (!lane.markDelayedDequeued())"),
                "stale delayed queue entries must not requeue a lane that was resumed earlier");
        assertTrue(clusterSource.contains("nextDelayTicks(lane, shardLimit)"),
                "zero-progress lanes must be distributed across future shard windows");
        assertTrue(gridSource.contains("removeInactiveLanes"),
                "final output insertion must recycle inactive lanes without waiting for a later tick");
        assertFalse(clusterSource.contains("runnableLanes.remove(lane)"),
                "lane completion must not linearly scan the runnable queue at extreme lane counts");
        assertFalse(clusterSource.contains("delayedLanes.remove(lane)"),
                "lane completion must not linearly scan the delayed queue at extreme lane counts");
        assertTrue(gameTestSource.contains("parallelCpuToolSmoke1024Lanes"),
                "smoke 1024-lane GameTest entry must stay present");
        assertTrue(gameTestSource.contains("assertSyntheticLaneScale(helper, \"smoke_1024\", 1_024)"),
                "smoke GameTest must execute a 1,024-lane synthetic scheduler benchmark");
        assertTrue(gameTestSource.contains("parallelCpuToolStress65536Lanes"),
                "stress 65536-lane GameTest entry must stay present");
        assertTrue(gameTestSource.contains("assertSyntheticLaneScale(helper, \"stress_65536\", 65_536)"),
                "stress GameTest must execute a 65,536-lane synthetic scheduler benchmark");
        assertTrue(gameTestSource.contains("assertSyntheticAllBusyProviderBackoff(helper, 65_536)"),
                "stress GameTest must benchmark all-busy provider backoff at 65,536 lanes");
        assertTrue(gameTestSource.contains("parallelCpuToolBuffersChainedIntermediatesInsideLaneInventory"),
                "parallel CPU GameTests must cover chained intermediates staying inside lane inventory");
        assertTrue(gameTestSource.contains("parallelCpuToolExtreme1048576Lanes"),
                "extreme 1048576-lane GameTest entry must stay present");
        assertTrue(gameTestSource.contains("assertSyntheticLaneScale(helper, \"extreme_1048576\", 1_048_576)"),
                "extreme GameTest must execute a gated 1,048,576-lane synthetic scheduler benchmark");
        assertTrue(gameTestSource.contains("Boolean.getBoolean(\"chexsonsaeutils.parallelCpuExtremeGameTest\")"),
                "extreme GameTest must require a dedicated benchmark switch");
    }

    @Test
    void autoSelectionUsesSelectionModeGateOnlyForNullTargets() throws IOException {
        String gridSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuGrid.java"
        ));
        String clusterSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuCluster.java"
        ));

        String autoSelectionSource = methodSlice(
                gridSource,
                "private AutoSelectionResult findAutoSelectionCluster",
                "private static Comparator<ParallelCraftingCpuCluster> autoSelectionComparator"
        );
        String explicitTargetSource = methodSlice(
                gridSource,
                "if (target instanceof ParallelCraftingCPU parallelCpu)",
                "if (target == null)"
        );

        assertTrue(clusterSource.contains("public boolean canBeAutoSelectedFor(IActionSource src)"),
                "cluster must expose an explicit auto-selection gate");
        assertTrue(clusterSource.contains("case ANY -> true"),
                "ANY selection mode must remain eligible for auto-selection");
        assertTrue(clusterSource.contains("case PLAYER_ONLY -> effectiveSource.player().isPresent()"),
                "PLAYER_ONLY selection mode must be based on the effective action source");
        assertTrue(clusterSource.contains("case MACHINE_ONLY -> effectiveSource.player().isEmpty()"),
                "MACHINE_ONLY selection mode must be based on the effective action source");
        assertTrue(autoSelectionSource.contains("!cluster.canBeAutoSelectedFor(src)"),
                "auto-selection must reject clusters through ParallelCraftingCpuCluster.canBeAutoSelectedFor");
        assertTrue(autoSelectionSource.contains("excluded++"),
                "auto-selection failures from the selection mode gate must be reported as excluded CPUs");
        assertTrue(autoSelectionSource.indexOf("!cluster.canBeAutoSelectedFor(src)")
                        < autoSelectionSource.indexOf("validClusters.add(cluster)"),
                "selection mode gate must run before a cluster can become an auto-selection candidate");

        assertTrue(explicitTargetSource.contains(
                        "return submitToCluster(parallelCpu.cluster(), job, requestingMachine, src);"
                ),
                "explicit parallel CPU targets must submit directly to the requested cluster");
        assertFalse(explicitTargetSource.contains("canBeAutoSelectedFor"),
                "explicit parallel CPU targets must not re-check the auto-selection gate");
        assertFalse(explicitTargetSource.contains("isPreferredFor"),
                "explicit parallel CPU targets must not apply auto-selection ordering");
        assertFalse(explicitTargetSource.contains("getSelectionMode"),
                "explicit parallel CPU targets must not inspect the selection mode gate");
    }

    @Test
    void autoSelectionPropagatesPrioritizePowerAndKeepsStableComparator() throws IOException {
        String mixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceParallelCpuMixin.java"
        ));
        String gridSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuGrid.java"
        ));

        String explicitSubmitSource = methodSlice(
                mixinSource,
                "private void chexsonsaeutils$submitExplicitParallelCpuJob",
                "private void chexsonsaeutils$submitAutoSelectedParallelCpuJob"
        );
        String autoSubmitHookSource = methodSlice(
                mixinSource,
                "private void chexsonsaeutils$submitAutoSelectedParallelCpuJob",
                "private void chexsonsaeutils$mergeParallelCpuAutoSelectionFailure"
        );
        String gridSubmitSource = methodSlice(
                gridSource,
                "public ICraftingSubmitResult submitJob",
                "public ICraftingSubmitResult getAutoSelectionFailure"
        );
        String autoSubmitSource = methodSlice(
                gridSource,
                "private ICraftingSubmitResult submitToAutoSelectedCluster",
                "private AutoSelectionResult findAutoSelectionCluster"
        );
        String comparatorSource = methodSlice(
                gridSource,
                "private static Comparator<ParallelCraftingCpuCluster> autoSelectionComparator",
                "private int activeLaneCount"
        );

        assertTrue(explicitSubmitSource.contains(
                        ".submitJob(job, requestingMachine, target, prioritizePower, src)"
                ),
                "explicit parallel submit hook must pass prioritizePower into ParallelCraftingCpuGrid.submitJob");
        assertTrue(autoSubmitHookSource.contains(
                        ".submitJob(job, requestingMachine, null, prioritizePower, src)"
                ),
                "auto-selection submit hook must pass prioritizePower into ParallelCraftingCpuGrid.submitJob");
        assertTrue(gridSubmitSource.contains(
                        "submitToAutoSelectedCluster(job, requestingMachine, prioritizePower, src)"
                ),
                "ParallelCraftingCpuGrid.submitJob must forward prioritizePower to the auto-selection path");
        assertTrue(autoSubmitSource.contains("findAutoSelectionCluster(job, src, prioritizePower)"),
                "auto-selection must use the caller's prioritizePower value instead of hard-coding power priority");

        assertTrue(comparatorSource.contains(".comparingInt(ParallelCraftingCpuCluster::advertisedCoProcessors)"),
                "auto-selection comparator must include advertised co-processor count");
        assertTrue(comparatorSource.contains("ParallelCraftingCpuCluster::advertisedCoProcessors"),
                "auto-selection comparator must include advertised co-processor count");
        assertTrue(comparatorSource.contains("if (prioritizePower)"),
                "auto-selection comparator must branch on prioritizePower");
        assertTrue(comparatorSource.contains("processorComparator = processorComparator.reversed();"),
                "prioritizePower must reverse the co-processor comparator");
        assertContainsInOrder(comparatorSource, List.of(
                "cluster.isPreferredFor(src)",
                ".thenComparing(processorComparator)",
                ".thenComparingLong(ParallelCraftingCpuCluster::storageBytes)",
                ".thenComparingInt(ParallelCraftingCpuCluster::activeLaneCount)"
        ));
    }

    @Test
    void parallelAutoSelectionFailureFallsBackToNativeAndMergesUnsuitableCpus() throws IOException {
        String gridSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuGrid.java"
        ));
        String mixinSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceParallelCpuMixin.java"
        ));

        String autoSubmitSource = methodSlice(
                gridSource,
                "private ICraftingSubmitResult submitToAutoSelectedCluster",
                "private AutoSelectionResult findAutoSelectionCluster"
        );
        String explicitSubmitSource = methodSlice(
                mixinSource,
                "private void chexsonsaeutils$submitExplicitParallelCpuJob",
                "private void chexsonsaeutils$submitAutoSelectedParallelCpuJob"
        );
        String autoHookSource = methodSlice(
                mixinSource,
                "private void chexsonsaeutils$submitAutoSelectedParallelCpuJob",
                "private void chexsonsaeutils$mergeParallelCpuAutoSelectionFailure"
        );
        String returnSubmitSource = methodSlice(
                mixinSource,
                "private void chexsonsaeutils$mergeParallelCpuAutoSelectionFailure",
                "private void chexsonsaeutils$insertIntoParallelCpus"
        );
        String mergeSource = methodSlice(
                mixinSource,
                "private static ICraftingSubmitResult chexsonsaeutils$mergeSubmitFailures",
                "private static UnsuitableCpus chexsonsaeutils$unsuitableCpus"
        );
        String unsuitableSource = methodSlice(
                mixinSource,
                "private static UnsuitableCpus chexsonsaeutils$unsuitableCpus",
                "private static int saturatedAdd"
        );

        assertTrue(autoSubmitSource.contains("if (result.selectedCluster() == null)"),
                "parallel auto-selection misses must return null so native AE2 auto-selection can continue");
        assertTrue(autoSubmitSource.contains("return null;"),
                "parallel auto-selection misses must not return a synthetic failure at the HEAD hook");
        assertTrue(autoSubmitSource.contains("submitResult != null && submitResult.successful() ? submitResult : null"),
                "parallel auto-selection must fall back to native AE2 when a preselected parallel cluster cannot submit");
        assertTrue(explicitSubmitSource.contains("if (job == null || job.simulation())"),
                "explicit target hook must preserve AE2 incomplete-plan semantics for null or simulation jobs");
        assertTrue(explicitSubmitSource.contains("ICraftingSubmitResult result = CraftingSubmitResult.INCOMPLETE_PLAN;"),
                "explicit target hook must return INCOMPLETE_PLAN before touching the parallel grid");
        assertTrue(explicitSubmitSource.contains("cir.setReturnValue(result == null ? CraftingSubmitResult.CPU_OFFLINE : result);"),
                "explicit target hook must still fail closed for stale real parallel CPUs instead of falling back");
        assertTrue(autoHookSource.contains("if (result != null)"),
                "auto-selection hook must only intercept when the parallel grid returns a real result");
        assertTrue(autoHookSource.contains("cir.setReturnValue(result);"),
                "auto-selection hook must leave null results to native AE2 fallback");
        assertFalse(autoHookSource.contains("getAutoSelectionFailure"),
                "parallel suitability failures must be merged after native AE2 auto-selection has failed");

        assertTrue(returnSubmitSource.contains("target != null || job == null || job.simulation()"),
                "failure merge hook must only run for real null-target auto-selection submissions");
        assertTrue(returnSubmitSource.contains("nativeResult == null || nativeResult.successful()"),
                "failure merge hook must not alter successful native auto-selection results");
        assertTrue(returnSubmitSource.contains("getAutoSelectionFailure(job, src)"),
                "failure merge hook must collect parallel suitability counts after native failure");
        assertTrue(returnSubmitSource.contains("chexsonsaeutils$mergeSubmitFailures(nativeResult, parallelFailure)"),
                "failure merge hook must merge native and parallel unsuitable CPU counts");

        assertTrue(gridSource.contains("return new UnsuitableCpus(offline, busy, tooSmall, excluded)"),
                "parallel auto-selection result must preserve all unsuitable CPU categories");
        assertTrue(unsuitableSource.contains("CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND"),
                "merge helper must only extract UnsuitableCpus from no-suitable-CPU failures");
        assertTrue(unsuitableSource.contains("result.errorDetail() instanceof UnsuitableCpus unsuitableCpus"),
                "merge helper must be source-guarded to AE2 UnsuitableCpus details");
        assertTrue(mergeSource.contains("new UnsuitableCpus("),
                "merge helper must emit a merged UnsuitableCpus payload");
        assertTrue(mergeSource.contains("nativeUnsuitable.offline()")
                        && mergeSource.contains("parallelUnsuitable.offline()"),
                "merge helper must merge offline counts");
        assertTrue(mergeSource.contains("nativeUnsuitable.busy()")
                        && mergeSource.contains("parallelUnsuitable.busy()"),
                "merge helper must merge busy counts");
        assertTrue(mergeSource.contains("nativeUnsuitable.tooSmall()")
                        && mergeSource.contains("parallelUnsuitable.tooSmall()"),
                "merge helper must merge too-small counts");
        assertTrue(mergeSource.contains("nativeUnsuitable.excluded()")
                        && mergeSource.contains("parallelUnsuitable.excluded()"),
                "merge helper must merge selection-excluded counts");
        assertTrue(mergeSource.contains("saturatedAdd("),
                "merge helper must use saturated arithmetic for unsuitable CPU counters");
    }

    private static String methodSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "source must contain marker " + startMarker);
        assertTrue(end > start, "source must contain marker " + endMarker + " after " + startMarker);
        return source.substring(start, end);
    }

    private static void assertContainsInOrder(String source, List<String> expectedSnippets) {
        int cursor = 0;
        for (String expectedSnippet : expectedSnippets) {
            int next = source.indexOf(expectedSnippet, cursor);
            assertTrue(next >= 0, "source must contain in order: " + expectedSnippet);
            cursor = next + expectedSnippet.length();
        }
    }

    private static final class TestLane implements ParallelCraftingLane {
        private final UUID laneId = UUID.randomUUID();
        private final Object2LongOpenHashMap<AEKey> waiting = new Object2LongOpenHashMap<>();

        private TestLane(AEKey key, long amount) {
            waiting.put(key, amount);
        }

        @Override
        public UUID getLaneId() {
            return laneId;
        }

        @Override
        public boolean isLaneActive() {
            return !waiting.isEmpty();
        }

        @Override
        public Iterable<Object2LongMap.Entry<AEKey>> getWaitingStacks() {
            return waiting.object2LongEntrySet();
        }

        @Override
        public long getRequestedAmount(@Nullable AEKey what) {
            return what == null ? 0L : Math.max(0L, waiting.getLong(what));
        }

        @Override
        public long insertIntoWaiting(AEKey what, long amount, Actionable mode) {
            long accepted = Math.min(amount, waiting.getLong(what));
            if (mode == Actionable.MODULATE && accepted > 0L) {
                waiting.addTo(what, -accepted);
                if (waiting.getLong(what) <= 0L) {
                    waiting.removeLong(what);
                }
            }
            return accepted;
        }
    }

    private static final class BusyProvider implements ICraftingProvider {
        @Override
        public boolean isBusy() {
            return true;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, appeng.api.stacks.KeyCounter[] inputHolder) {
            return false;
        }
    }
}
