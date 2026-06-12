package git.chexson.chexsonsaeutils.crafting.directprocessing;

public record ProcessingLatencyOrigin(long acceptedTickSum, int acceptedPushCount) {

    public ProcessingLatencyOrigin {
        acceptedPushCount = Math.max(0, acceptedPushCount);
        if (acceptedPushCount == 0) {
            acceptedTickSum = 0L;
        }
    }

    public static ProcessingLatencyOrigin single(long acceptedTick) {
        return new ProcessingLatencyOrigin(acceptedTick, 1);
    }

    public ProcessingLatencyOrigin merge(ProcessingLatencyOrigin other) {
        if (other == null || other.acceptedPushCount <= 0) {
            return this;
        }
        return new ProcessingLatencyOrigin(
                saturatingAdd(acceptedTickSum, other.acceptedTickSum),
                saturatingAdd(acceptedPushCount, other.acceptedPushCount)
        );
    }

    public long averageLatencyTicks(long returnedTick) {
        if (acceptedPushCount <= 0) {
            return 0L;
        }
        long returnedTickSum = saturatingMultiply(returnedTick, acceptedPushCount);
        return Math.max(0L, (returnedTickSum - acceptedTickSum) / acceptedPushCount);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private static int saturatingAdd(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, left + right);
    }

    private static long saturatingMultiply(long value, int count) {
        if (value <= 0L || count <= 0) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / count) {
            return Long.MAX_VALUE;
        }
        return value * count;
    }
}
