package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.util.Arrays;

public final class ParallelCpuMetrics {

    private static final int TICK_NANOS_SAMPLE_SIZE = 512;

    private final long[] tickNanosSamples = new long[TICK_NANOS_SAMPLE_SIZE];
    private long activeVirtualCpuCount;
    private long fakeCpuAdvertisedCount;
    private long submittedVirtualCpuCount;
    private long completedVirtualCpuCount;
    private long tickCraftingLogicCount;
    private long executeCraftingCalls;
    private long providerScanCount;
    private long busyProviderSkipCount;
    private long pushedPatternCount;
    private long extractPatternInputsCount;
    private long reinjectPatternInputsCount;
    private long zeroProgressTickCount;
    private long indexedInsertCount;
    private long indexedInsertAmount;
    private long waitingIndexLaneCount;
    private long waitingIndexKeyCount;
    private long budgetExhaustedCount;
    private long patternPushBudgetExhaustedCount;
    private long providerCheckBudgetExhaustedCount;
    private long extractPatternInputsBudgetExhaustedCount;
    private long reinjectPatternInputsBudgetExhaustedCount;
    private long timeBudgetExhaustedCount;
    private int tickNanosCursor;
    private int tickNanosSampleCount;
    private long tickNanosMax;

    public synchronized void setCpuGauges(long activeVirtualCpuCount, long fakeCpuAdvertisedCount) {
        this.activeVirtualCpuCount = Math.max(0L, activeVirtualCpuCount);
        this.fakeCpuAdvertisedCount = Math.max(0L, fakeCpuAdvertisedCount);
    }

    public synchronized void setWaitingIndexGauges(long waitingIndexLaneCount, long waitingIndexKeyCount) {
        this.waitingIndexLaneCount = Math.max(0L, waitingIndexLaneCount);
        this.waitingIndexKeyCount = Math.max(0L, waitingIndexKeyCount);
    }

    public synchronized void recordSubmittedVirtualCpu() {
        submittedVirtualCpuCount++;
    }

    public synchronized void recordCompletedVirtualCpu() {
        completedVirtualCpuCount++;
    }

    public synchronized void recordTickCraftingLogic() {
        tickCraftingLogicCount++;
    }

    public synchronized void recordExecuteCraftingCall() {
        executeCraftingCalls++;
    }

    public synchronized void recordProviderScan() {
        providerScanCount++;
    }

    public synchronized void recordBusyProviderSkip() {
        busyProviderSkipCount++;
    }

    public synchronized void recordPushedPattern(long count) {
        pushedPatternCount = saturatedAdd(pushedPatternCount, Math.max(0L, count));
    }

    public synchronized void recordExtractPatternInputs(long count) {
        extractPatternInputsCount = saturatedAdd(extractPatternInputsCount, Math.max(0L, count));
    }

    public synchronized void recordReinjectPatternInputs(long count) {
        reinjectPatternInputsCount = saturatedAdd(reinjectPatternInputsCount, Math.max(0L, count));
    }

    public synchronized void recordZeroProgressTick() {
        zeroProgressTickCount++;
    }

    public synchronized void recordIndexedInsert(long amount) {
        indexedInsertCount++;
        indexedInsertAmount = saturatedAdd(indexedInsertAmount, Math.max(0L, amount));
    }

    public synchronized void recordBudgetExhausted(ParallelCpuGridBudgetLedger.BudgetType budgetType) {
        budgetExhaustedCount++;
        if (budgetType == null) {
            return;
        }
        switch (budgetType) {
            case PATTERN_PUSH -> patternPushBudgetExhaustedCount++;
            case PROVIDER_CHECK -> providerCheckBudgetExhaustedCount++;
            case EXTRACT_PATTERN_INPUTS -> extractPatternInputsBudgetExhaustedCount++;
            case REINJECT_PATTERN_INPUTS -> reinjectPatternInputsBudgetExhaustedCount++;
            case TIME -> timeBudgetExhaustedCount++;
        }
    }

    public synchronized void recordLedgerExhaustion(ParallelCpuGridBudgetLedger budgetLedger) {
        if (budgetLedger == null) {
            return;
        }
        for (ParallelCpuGridBudgetLedger.BudgetType budgetType : budgetLedger.exhaustedTypes()) {
            recordBudgetExhausted(budgetType);
        }
    }

    public synchronized void recordTickNanos(long nanos) {
        long normalized = Math.max(0L, nanos);
        tickNanosSamples[tickNanosCursor] = normalized;
        tickNanosCursor = (tickNanosCursor + 1) % tickNanosSamples.length;
        tickNanosSampleCount = Math.min(tickNanosSampleCount + 1, tickNanosSamples.length);
        tickNanosMax = Math.max(tickNanosMax, normalized);
    }

    public synchronized Snapshot snapshot() {
        long[] samples = Arrays.copyOf(tickNanosSamples, tickNanosSampleCount);
        Arrays.sort(samples);
        return new Snapshot(
                activeVirtualCpuCount,
                fakeCpuAdvertisedCount,
                submittedVirtualCpuCount,
                completedVirtualCpuCount,
                tickCraftingLogicCount,
                executeCraftingCalls,
                providerScanCount,
                busyProviderSkipCount,
                pushedPatternCount,
                extractPatternInputsCount,
                reinjectPatternInputsCount,
                zeroProgressTickCount,
                indexedInsertCount,
                indexedInsertAmount,
                waitingIndexLaneCount,
                waitingIndexKeyCount,
                budgetExhaustedCount,
                patternPushBudgetExhaustedCount,
                providerCheckBudgetExhaustedCount,
                extractPatternInputsBudgetExhaustedCount,
                reinjectPatternInputsBudgetExhaustedCount,
                timeBudgetExhaustedCount,
                percentile(samples, 0.50D),
                percentile(samples, 0.95D),
                percentile(samples, 0.99D),
                tickNanosMax,
                tickNanosSampleCount
        );
    }

    private static long percentile(long[] sortedSamples, double percentile) {
        if (sortedSamples.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(sortedSamples.length * percentile) - 1;
        index = Math.max(0, Math.min(sortedSamples.length - 1, index));
        return sortedSamples[index];
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public record Snapshot(
            long activeVirtualCpuCount,
            long fakeCpuAdvertisedCount,
            long submittedVirtualCpuCount,
            long completedVirtualCpuCount,
            long tickCraftingLogicCount,
            long executeCraftingCalls,
            long providerScanCount,
            long busyProviderSkipCount,
            long pushedPatternCount,
            long extractPatternInputsCount,
            long reinjectPatternInputsCount,
            long zeroProgressTickCount,
            long indexedInsertCount,
            long indexedInsertAmount,
            long waitingIndexLaneCount,
            long waitingIndexKeyCount,
            long budgetExhaustedCount,
            long patternPushBudgetExhaustedCount,
            long providerCheckBudgetExhaustedCount,
            long extractPatternInputsBudgetExhaustedCount,
            long reinjectPatternInputsBudgetExhaustedCount,
            long timeBudgetExhaustedCount,
            long tickNanosP50,
            long tickNanosP95,
            long tickNanosP99,
            long tickNanosMax,
            int tickNanosSampleCount
    ) {
    }
}
