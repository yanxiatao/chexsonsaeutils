package git.chexson.chexsonsaeutils.crafting.directprocessing;

public final class DirectProcessingValueBaselineModel {

    public static final int DIRECT_AE_RETURN_DEVICE_COUNT = 0;
    public static final int ORIGINAL_AE_RETURN_DEVICE_COUNT = 1;
    public static final int DIRECT_ITEM_CONTACT_COUNT = 2;
    public static final int ORIGINAL_ITEM_CONTACT_COUNT = 4;
    public static final int ORIGINAL_RETURN_OVERHEAD_TICKS = 2;
    public static final int SHORT_THROUGHPUT_WINDOW_TICKS = 64;

    private DirectProcessingValueBaselineModel() {
    }

    public static Snapshot shortRecipeSnapshot(int processingTicks) {
        int normalizedProcessingTicks = Math.max(1, processingTicks);
        return new Snapshot(
                DIRECT_AE_RETURN_DEVICE_COUNT,
                ORIGINAL_AE_RETURN_DEVICE_COUNT,
                DIRECT_ITEM_CONTACT_COUNT,
                ORIGINAL_ITEM_CONTACT_COUNT,
                normalizedProcessingTicks,
                modeledOriginalRoundTripTicks(normalizedProcessingTicks)
        );
    }

    public static int modeledOriginalRoundTripTicks(int processingTicks) {
        return Math.max(1, processingTicks) + ORIGINAL_RETURN_OVERHEAD_TICKS;
    }

    public static long modeledOriginalOneMachineCompletions(int processingTicks, int windowTicks) {
        int roundTripTicks = modeledOriginalRoundTripTicks(processingTicks);
        return Math.max(1, windowTicks) / (long) roundTripTicks;
    }

    public record Snapshot(
            int directAeReturnDeviceCount,
            int originalAeReturnDeviceCount,
            int directItemContactCount,
            int originalItemContactCount,
            int directRoundTripTicks,
            int originalRoundTripTicks
    ) {
        public int aeReturnDeviceReduction() {
            return originalAeReturnDeviceCount - directAeReturnDeviceCount;
        }

        public int itemContactReduction() {
            return originalItemContactCount - directItemContactCount;
        }

        public int roundTripTickReduction() {
            return originalRoundTripTicks - directRoundTripTicks;
        }
    }
}
