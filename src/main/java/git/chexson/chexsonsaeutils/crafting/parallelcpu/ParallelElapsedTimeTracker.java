package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.util.Mth;

final class ParallelElapsedTimeTracker {

    private long lastTime = System.nanoTime();
    private long elapsedTime;
    private final Reference2LongMap<AEKeyType> startedWorkByType = new Reference2LongOpenHashMap<>(
            AEKeyTypes.getAll().size());
    private final Reference2LongMap<AEKeyType> completedWorkByType = new Reference2LongOpenHashMap<>(
            AEKeyTypes.getAll().size());

    void addMaxItems(long itemDiff, AEKeyType keyType) {
        if (itemDiff <= 0L || keyType == null) {
            return;
        }
        updateTime();
        startedWorkByType.put(keyType, saturatedSum(startedWorkByType.getLong(keyType), itemDiff));
    }

    void decrementItems(long itemDiff, AEKeyType keyType) {
        if (itemDiff <= 0L || keyType == null) {
            return;
        }
        updateTime();
        completedWorkByType.put(keyType, saturatedSum(completedWorkByType.getLong(keyType), itemDiff));
    }

    long getElapsedTime() {
        return isAllDone() ? elapsedTime : elapsedTime + (System.nanoTime() - lastTime);
    }

    float getProgress() {
        double startedUnits = getStartedUnits();
        if (startedUnits <= 0.0d) {
            return 0.0f;
        }
        return Mth.clamp((float) (getCompletedUnits() / startedUnits), 0.0f, 1.0f);
    }

    long getRemainingItemCount() {
        return getStartItemCount() <= 0L
                ? 0L
                : Math.max(0L, (long) (Integer.MAX_VALUE - (double) getProgress() * Integer.MAX_VALUE));
    }

    long getStartItemCount() {
        return getStartedUnits() > 0.0d ? Integer.MAX_VALUE : 0L;
    }

    double getStartedUnits() {
        return getUnits(startedWorkByType);
    }

    double getCompletedUnits() {
        return getUnits(completedWorkByType);
    }

    private boolean isAllDone() {
        for (var keyType : AEKeyTypes.getAll()) {
            if (completedWorkByType.getLong(keyType) < startedWorkByType.getLong(keyType)) {
                return false;
            }
        }
        return true;
    }

    private void updateTime() {
        long currentTime = System.nanoTime();
        elapsedTime += currentTime - lastTime;
        lastTime = currentTime;
    }

    private double getUnits(Reference2LongMap<AEKeyType> byType) {
        double units = 0.0d;
        for (var keyType : AEKeyTypes.getAll()) {
            units += byType.getLong(keyType) / (double) keyType.getAmountPerUnit();
        }
        return units;
    }

    private long saturatedSum(long left, long right) {
        long result = left + right;
        return result < 0L ? Long.MAX_VALUE : result;
    }
}
