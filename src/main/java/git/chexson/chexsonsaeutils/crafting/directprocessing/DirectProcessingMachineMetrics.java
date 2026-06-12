package git.chexson.chexsonsaeutils.crafting.directprocessing;

import java.util.Arrays;

public final class DirectProcessingMachineMetrics {

    private static final int SAMPLE_SIZE = 1024;

    private final SampleWindow dirtyRefreshNanos = new SampleWindow();
    private final SampleWindow pushPatternCacheLookupNanos = new SampleWindow();
    private final SampleWindow outputReturnNanos = new SampleWindow();
    private final SampleWindow outputReturnLatencyTicks = new SampleWindow();
    private final SampleWindow serverTickNanos = new SampleWindow();

    public void recordDirtyRefreshNanos(long nanos) {
        dirtyRefreshNanos.record(nanos);
    }

    public void recordPushPatternCacheLookupNanos(long nanos) {
        pushPatternCacheLookupNanos.record(nanos);
    }

    public void recordOutputReturnNanos(long nanos) {
        outputReturnNanos.record(nanos);
    }

    public void recordOutputReturnLatencyTicks(long ticks) {
        outputReturnLatencyTicks.record(ticks);
    }

    public void recordServerTickNanos(long nanos) {
        serverTickNanos.record(nanos);
    }

    public Snapshot snapshot() {
        SampleWindow.Snapshot dirtyRefresh = dirtyRefreshNanos.snapshot();
        SampleWindow.Snapshot pushPatternCacheLookup = pushPatternCacheLookupNanos.snapshot();
        SampleWindow.Snapshot outputReturn = outputReturnNanos.snapshot();
        SampleWindow.Snapshot outputReturnLatency = outputReturnLatencyTicks.snapshot();
        SampleWindow.Snapshot serverTick = serverTickNanos.snapshot();
        return new Snapshot(
                dirtyRefresh.p95(),
                dirtyRefresh.max(),
                dirtyRefresh.sampleCount(),
                pushPatternCacheLookup.p95(),
                pushPatternCacheLookup.max(),
                pushPatternCacheLookup.sampleCount(),
                outputReturn.p95(),
                outputReturn.max(),
                outputReturn.sampleCount(),
                outputReturnLatency.p95(),
                outputReturnLatency.max(),
                outputReturnLatency.sampleCount(),
                serverTick.p95(),
                serverTick.max(),
                serverTick.sampleCount()
        );
    }

    private static final class SampleWindow {

        private final long[] samples = new long[SAMPLE_SIZE];
        private int cursor;
        private int sampleCount;
        private long max;

        private synchronized void record(long nanos) {
            long normalized = Math.max(0L, nanos);
            samples[cursor] = normalized;
            cursor = (cursor + 1) % samples.length;
            sampleCount = Math.min(sampleCount + 1, samples.length);
            max = Math.max(max, normalized);
        }

        private synchronized Snapshot snapshot() {
            long[] sortedSamples = Arrays.copyOf(samples, sampleCount);
            Arrays.sort(sortedSamples);
            return new Snapshot(percentile(sortedSamples, 0.95D), max, sampleCount);
        }

        private static long percentile(long[] sortedSamples, double percentile) {
            if (sortedSamples.length == 0) {
                return 0L;
            }
            int index = (int) Math.ceil(sortedSamples.length * percentile) - 1;
            index = Math.max(0, Math.min(sortedSamples.length - 1, index));
            return sortedSamples[index];
        }

        private record Snapshot(long p95, long max, int sampleCount) {
        }
    }

    public record Snapshot(
            long dirtyRefreshNanosP95,
            long dirtyRefreshNanosMax,
            int dirtyRefreshNanosSampleCount,
            long pushPatternCacheLookupNanosP95,
            long pushPatternCacheLookupNanosMax,
            int pushPatternCacheLookupNanosSampleCount,
            long outputReturnNanosP95,
            long outputReturnNanosMax,
            int outputReturnNanosSampleCount,
            long outputReturnLatencyTicksP95,
            long outputReturnLatencyTicksMax,
            int outputReturnLatencyTicksSampleCount,
            long serverTickNanosP95,
            long serverTickNanosMax,
            int serverTickNanosSampleCount
    ) {
    }
}
