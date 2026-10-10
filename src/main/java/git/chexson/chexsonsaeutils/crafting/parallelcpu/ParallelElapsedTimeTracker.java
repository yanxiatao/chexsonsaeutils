package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import java.math.BigDecimal;
import java.math.MathContext;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

final class ParallelElapsedTimeTracker {

    private static final String NBT_ELAPSED_TIME = "elapsedTime";
    private static final String NBT_STARTED_WORK = "startedWork";
    private static final String NBT_COMPLETED_WORK = "completedWork";

    private long lastTime = System.nanoTime();
    private long elapsedTime;
    private final Reference2ObjectMap<AEKeyType, PlanAmount> startedWorkByType = new Reference2ObjectOpenHashMap<>(
            AEKeyTypes.getAll().size());
    private final Reference2ObjectMap<AEKeyType, PlanAmount> completedWorkByType = new Reference2ObjectOpenHashMap<>(
            AEKeyTypes.getAll().size());

    ParallelElapsedTimeTracker() {
    }

    ParallelElapsedTimeTracker(CompoundTag data) {
        this.elapsedTime = data.getLong(NBT_ELAPSED_TIME);
        readByTypeMap(data.getCompound(NBT_STARTED_WORK), startedWorkByType);
        readByTypeMap(data.getCompound(NBT_COMPLETED_WORK), completedWorkByType);
    }

    CompoundTag writeToNBT() {
        CompoundTag data = new CompoundTag();
        data.putLong(NBT_ELAPSED_TIME, elapsedTime);
        data.put(NBT_STARTED_WORK, writeByTypeMap(startedWorkByType));
        data.put(NBT_COMPLETED_WORK, writeByTypeMap(completedWorkByType));
        return data;
    }

    void addMaxItems(long itemDiff, AEKeyType keyType) {
        addMaxItems(PlanAmount.of(itemDiff), keyType);
    }

    /** 计划总工作量保留精确值，跨 long 后也不能截断分母。 */
    void addMaxItems(PlanAmount itemDiff, AEKeyType keyType) {
        if (itemDiff.signum() <= 0 || keyType == null) return;
        updateTime();
        startedWorkByType.put(keyType, startedWorkByType.getOrDefault(keyType, PlanAmount.ZERO).add(itemDiff));
    }

    void decrementItems(long itemDiff, AEKeyType keyType) {
        decrementItems(PlanAmount.of(itemDiff), keyType);
    }

    /** 只有实际回交或真实直投交割才推进已完成量，单批保持宽域。 */
    void decrementItems(PlanAmount itemDiff, AEKeyType keyType) {
        if (itemDiff.signum() <= 0 || keyType == null) return;
        updateTime();
        completedWorkByType.put(keyType, completedWorkByType.getOrDefault(keyType, PlanAmount.ZERO).add(itemDiff));
    }

    long getElapsedTime() {
        return isAllDone() ? elapsedTime : elapsedTime + (System.nanoTime() - lastTime);
    }

    float getProgress() {
        var total = getScaledUnits(startedWorkByType);
        if (total.signum() <= 0) return 0.0f;
        var completed = getScaledUnits(completedWorkByType);
        if (completed.compareTo(total) >= 0) return 1.0f;
        return Math.max(0.0f, Math.min(Math.nextDown(1.0f),
                new BigDecimal(completed).divide(new BigDecimal(total), MathContext.DECIMAL128).floatValue()));
    }

    long getRemainingItemCount() {
        var total = getScaledUnits(startedWorkByType);
        if (total.signum() <= 0) return 0;
        var remaining = total.subtract(getScaledUnits(completedWorkByType)).max(java.math.BigInteger.ZERO);
        if (remaining.signum() == 0) return 0;
        return Math.max(1, remaining.min(total).multiply(java.math.BigInteger.valueOf(Integer.MAX_VALUE))
                .divide(total).longValueExact());
    }

    long getStartItemCount() {
        return getScaledUnits(startedWorkByType).signum() > 0 ? Integer.MAX_VALUE : 0L;
    }

    /** 单位通分为整数权重，最后一份未完成工作也不能在浮点除法中消失。 */
    private java.math.BigInteger getScaledUnits(Reference2ObjectMap<AEKeyType, PlanAmount> byType) {
        java.math.BigInteger denominator = java.math.BigInteger.ONE;
        for (var keyType : AEKeyTypes.getAll()) {
            var unit = java.math.BigInteger.valueOf(keyType.getAmountPerUnit());
            denominator = denominator.divide(denominator.gcd(unit)).multiply(unit);
        }
        java.math.BigInteger units = java.math.BigInteger.ZERO;
        for (var keyType : AEKeyTypes.getAll()) {
            units = units.add(byType.getOrDefault(keyType, PlanAmount.ZERO).exactValue()
                    .multiply(denominator.divide(java.math.BigInteger.valueOf(keyType.getAmountPerUnit()))));
        }
        return units;
    }

    double getStartedUnits() { return getUnits(startedWorkByType).doubleValue(); }
    double getCompletedUnits() { return getUnits(completedWorkByType).doubleValue(); }

    private boolean isAllDone() {
        for (var keyType : AEKeyTypes.getAll()) {
            if (completedWorkByType.getOrDefault(keyType, PlanAmount.ZERO)
                    .compareTo(startedWorkByType.getOrDefault(keyType, PlanAmount.ZERO)) < 0) return false;
        }
        return true;
    }

    private void updateTime() {
        long currentTime = System.nanoTime();
        elapsedTime += currentTime - lastTime;
        lastTime = currentTime;
    }

    /** 按 AE2 各资源的单位归一化；先算精确比例，再转换为界面浮点数。 */
    private BigDecimal getUnits(Reference2ObjectMap<AEKeyType, PlanAmount> byType) {
        BigDecimal units = BigDecimal.ZERO;
        for (var keyType : AEKeyTypes.getAll()) {
            units = units.add(new BigDecimal(byType.getOrDefault(keyType, PlanAmount.ZERO).exactValue())
                    .divide(BigDecimal.valueOf(keyType.getAmountPerUnit()), MathContext.DECIMAL128));
        }
        return units;
    }

    private static void readByTypeMap(CompoundTag tag, Reference2ObjectMap<AEKeyType, PlanAmount> output) {
        for (var keyType : AEKeyTypes.getAll()) {
            String key = keyType.getId().toString();
            output.put(keyType, tag.contains(key, Tag.TAG_BYTE_ARRAY)
                    ? PlanAmount.of(new java.math.BigInteger(tag.getByteArray(key))) : PlanAmount.of(tag.getLong(key)));
        }
    }

    private static CompoundTag writeByTypeMap(Reference2ObjectMap<AEKeyType, PlanAmount> input) {
        CompoundTag result = new CompoundTag();
        for (var entry : input.reference2ObjectEntrySet()) {
            String key = entry.getKey().getId().toString();
            PlanAmount amount = entry.getValue();
            if (amount.isFast()) result.putLong(key, amount.fastValue());
            else result.putByteArray(key, amount.exactValue().toByteArray());
        }
        return result;
    }
}
