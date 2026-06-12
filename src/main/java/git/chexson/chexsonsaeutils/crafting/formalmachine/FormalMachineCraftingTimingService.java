package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.menu.me.crafting.CraftingStatus;
import java.util.List;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FormalMachineCraftingTimingService {

    private static final Map<UUID, TimingState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, TimingState> PENDING_STATES = new ConcurrentHashMap<>();

    private FormalMachineCraftingTimingService() {
    }

    public static void beginSubmittedJob(
            @Nullable UUID craftingId,
            @Nullable AbstractHighCapacityCraftingHostBlockEntity provider
    ) {
        beginSubmittedJob(craftingId, provider, null);
    }

    public static void beginSubmittedJob(
            @Nullable UUID craftingId,
            @Nullable AbstractHighCapacityCraftingHostBlockEntity provider,
            @Nullable appeng.api.networking.crafting.ICraftingPlan plan
    ) {
        if (craftingId == null) {
            return;
        }
        TimingState state = STATES.get(craftingId);
        if (state == null) {
            state = PENDING_STATES.computeIfAbsent(craftingId, ignored -> new TimingState());
        }
        if (provider != null) {
            state.provider = provider;
        }
    }

    public static void recordAcceptedBatch(
            @Nullable UUID craftingId,
            IFormalMachineCraftingProvider provider,
            int logicalExecutions,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        if (craftingId == null || logicalExecutions <= 0) {
            return;
        }
        TimingState state = STATES.get(craftingId);
        if (state == null) {
            state = PENDING_STATES.remove(craftingId);
            if (state == null) {
                state = new TimingState();
            }
            TimingState existing = STATES.putIfAbsent(craftingId, state);
            if (existing != null) {
                state = existing;
            }
        }
        state.provider = provider instanceof AbstractHighCapacityCraftingHostBlockEntity host ? host : null;
    }

    public static void recordCpuWaitingReturn(@Nullable UUID craftingId, @Nullable AEKey key, long amount) {
    }

    public static void recordLocalCompletion(
            @Nullable UUID craftingId,
            @Nullable GenericStack primary,
            @Nullable Map<? extends AEKey, Long> remainders
    ) {
    }

    public static void clearJob(@Nullable UUID craftingId) {
        if (craftingId == null) {
            return;
        }
        STATES.remove(craftingId);
        PENDING_STATES.remove(craftingId);
        FormalMachineCraftingDispatchService.clearSourceCpu(craftingId);
    }

    public static boolean hasActiveState(CraftingCpuLogic logic) {
        return activeState(logic) != null;
    }

    public static boolean shouldSendHeartbeat(CraftingCpuLogic logic) {
        if (activeState(logic) == null) {
            return false;
        }
        long start = Math.max(1L, logic.getElapsedTimeTracker().getStartItemCount());
        long observedRemaining = Math.max(0L, Math.min(start, logic.getElapsedTimeTracker().getRemainingItemCount()));
        long observedCompleted = Math.max(0L, start - observedRemaining);
        return observedCompleted > 0L;
    }

    public static void recordFormalStatusHeartbeat(CraftingCpuLogic logic) {
        UUID craftingId = craftingId(logic);
        TimingState state = craftingId == null ? null : STATES.get(craftingId);
        if (state != null) {
            recordFormalStatusHeartbeat(state);
        }
    }

    public static CraftingStatus createHeartbeatStatus(CraftingCpuLogic logic) {
        if (logic == null) {
            return CraftingStatus.EMPTY;
        }
        long elapsed = logic.getElapsedTimeTracker().getElapsedTime();
        long remaining = logic.getElapsedTimeTracker().getRemainingItemCount();
        long start = logic.getElapsedTimeTracker().getStartItemCount();
        CraftingStatus status = new CraftingStatus(
                false,
                elapsed,
                remaining,
                start,
                List.of(),
                logic.isJobSuspended()
        );
        return correctStatus(logic, status);
    }

    public static CraftingStatus correctStatus(CraftingCpuLogic logic, CraftingStatus status) {
        if (logic == null || status == null || status == CraftingStatus.EMPTY) {
            return status;
        }
        UUID craftingId = craftingId(logic);
        TimingState state = craftingId == null ? null : STATES.get(craftingId);
        if (state == null) {
            long start = Math.max(1L, status.getStartItemCount());
            long observedRemaining = Math.max(0L, Math.min(start, status.getRemainingItemCount()));
            discardPendingStateIfNativeProgressObserved(craftingId, start, start - observedRemaining);
            return status;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) ((CraftingCpuLogicAccessor) logic).getJob();
        boolean jobFinished = accessor.getRemainingAmount() <= 0L;

        long start = Math.max(1L, status.getStartItemCount());
        long observedRemaining = Math.max(0L, Math.min(start, status.getRemainingItemCount()));
        long observedCompleted = Math.max(0L, start - observedRemaining);
        long completed = state.sanitizeObservedProgress(observedCompleted, jobFinished, start);
        long elapsed = state.sanitizeStatusElapsed(status.getElapsedTime(), completed, jobFinished);
        long remaining = Math.max(0L, start - completed);

        boolean elapsedClamped = elapsed != status.getElapsedTime();
        boolean remainingClamped = remaining != status.getRemainingItemCount();
        boolean progressClamped = completed != observedCompleted;
        if (elapsedClamped || remainingClamped || progressClamped) {
            recordCorrection(state, progressClamped || remainingClamped, elapsedClamped || remainingClamped);
        }

        state.recordCanonical(start, completed, jobFinished);
        if (jobFinished) {
            clearJob(craftingId);
        }
        if (!elapsedClamped && !remainingClamped && !progressClamped) {
            return status;
        }
        return new CraftingStatus(
                status.isFullStatus(),
                elapsed,
                remaining,
                start,
                status.getEntries(),
                status.isSuspended()
        );
    }

    public static CraftingJobStatus correctJobStatus(CraftingCpuLogic logic, CraftingJobStatus status) {
        if (logic == null || status == null) {
            return status;
        }
        UUID craftingId = craftingId(logic);
        TimingState state = craftingId == null ? null : STATES.get(craftingId);
        if (state == null) {
            discardPendingStateIfNativeProgressObserved(craftingId, status.totalItems(), status.progress());
            return status;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) ((CraftingCpuLogicAccessor) logic).getJob();
        boolean jobFinished = accessor.getRemainingAmount() <= 0L;

        long total = Math.max(1L, status.totalItems());
        long correctedProgress = state.sanitizeObservedProgress(status.progress(), jobFinished, total);
        long elapsed = state.sanitizeJobElapsedNanos(status.elapsedTimeNanos(), correctedProgress, jobFinished);

        boolean elapsedClamped = elapsed != status.elapsedTimeNanos();
        boolean progressClamped = correctedProgress != status.progress();
        if (elapsedClamped || progressClamped) {
            recordCorrection(state, progressClamped, progressClamped);
        }

        state.recordCanonical(total, correctedProgress, jobFinished);
        if (jobFinished) {
            clearJob(craftingId);
        }
        if (!elapsedClamped && !progressClamped) {
            return status;
        }
        GenericStack crafting = status.crafting();
        return new CraftingJobStatus(crafting, total, correctedProgress, elapsed);
    }

    private static @Nullable TimingState activeState(CraftingCpuLogic logic) {
        UUID craftingId = craftingId(logic);
        return craftingId == null ? null : STATES.get(craftingId);
    }

    private static @Nullable UUID craftingId(CraftingCpuLogic logic) {
        if (logic == null) {
            return null;
        }
        ExecutingCraftingJob job = ((CraftingCpuLogicAccessor) logic).getJob();
        if (job == null) {
            return null;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        return accessor.getLink() == null ? null : accessor.getLink().getCraftingID();
    }

    private static void recordFormalStatusHeartbeat(TimingState state) {
        state.formalStatusHeartbeatCount = safeAdd(state.formalStatusHeartbeatCount, 1L);
        if (state.provider != null) {
            state.provider.recordFormalStatusHeartbeatForTest();
        }
    }

    private static void recordCorrection(TimingState state, boolean progressClamp, boolean etaClamp) {
        if (state.provider != null) {
            boolean correctionEvent = !state.correctionRecorded;
            boolean progressEvent = progressClamp && !state.progressClampRecorded;
            boolean etaEvent = etaClamp && !state.etaClampRecorded;
            if (correctionEvent || progressEvent || etaEvent) {
                state.provider.recordFormalTimingCorrectionForTest(correctionEvent, progressEvent, etaEvent);
                state.correctionRecorded |= correctionEvent;
                state.progressClampRecorded |= progressEvent;
                state.etaClampRecorded |= etaEvent;
            }
        }
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + Math.max(0L, right);
    }

    private static void discardPendingStateIfNativeProgressObserved(
            @Nullable UUID craftingId,
            long totalItems,
            long observedProgress
    ) {
        if (craftingId == null || !PENDING_STATES.containsKey(craftingId)) {
            return;
        }
        long boundedTotal = Math.max(1L, totalItems);
        long boundedProgress = Math.max(0L, Math.min(boundedTotal, observedProgress));
        if (boundedProgress > 0L) {
            clearJob(craftingId);
        }
    }

    private static final class TimingState {
        private AbstractHighCapacityCraftingHostBlockEntity provider;
        private long canonicalStatusElapsedTime;
        private long canonicalJobElapsedNanos;
        private long canonicalTotalItems;
        private long canonicalProgress;
        private long formalStatusHeartbeatCount;
        private boolean correctionRecorded;
        private boolean progressClampRecorded;
        private boolean etaClampRecorded;

        private void recordCanonical(long totalItems, long progress, boolean jobFinished) {
            canonicalTotalItems = Math.max(canonicalTotalItems, Math.max(1L, totalItems));
            long maxProgress = jobFinished ? canonicalTotalItems : Math.max(0L, canonicalTotalItems - 1L);
            canonicalProgress = Math.max(canonicalProgress, Math.max(0L, Math.min(progress, maxProgress)));
        }

        private void recordCanonicalStatusElapsed(long elapsedTime) {
            canonicalStatusElapsedTime = Math.max(canonicalStatusElapsedTime, Math.max(0L, elapsedTime));
        }

        private void recordCanonicalJobElapsedNanos(long elapsedTimeNanos) {
            canonicalJobElapsedNanos = Math.max(canonicalJobElapsedNanos, Math.max(0L, elapsedTimeNanos));
        }

        private long sanitizeStatusElapsed(long observedElapsed, long progress, boolean jobFinished) {
            if (!jobFinished && progress <= 0L) {
                return 0L;
            }
            long sanitized = Math.max(canonicalStatusElapsedTime, Math.max(0L, observedElapsed));
            recordCanonicalStatusElapsed(sanitized);
            return sanitized;
        }

        private long sanitizeJobElapsedNanos(long observedElapsedNanos, long progress, boolean jobFinished) {
            if (!jobFinished && progress <= 0L) {
                return 0L;
            }
            long sanitized = Math.max(canonicalJobElapsedNanos, Math.max(0L, observedElapsedNanos));
            recordCanonicalJobElapsedNanos(sanitized);
            return sanitized;
        }

        private long sanitizeObservedProgress(long observedProgress, boolean jobFinished, long totalItems) {
            return capProgress(
                    Math.max(0L, Math.max(Math.min(totalItems, observedProgress), canonicalProgress)),
                    jobFinished,
                    totalItems
            );
        }

        private static long capProgress(long progress, boolean jobFinished, long totalItems) {
            long capped = Math.max(0L, Math.min(totalItems, progress));
            return jobFinished ? capped : Math.min(Math.max(0L, totalItems - 1L), capped);
        }
    }
}
