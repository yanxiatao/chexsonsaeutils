package git.chexson.chexsonsaeutils.parts.automation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class MultiLevelEmitterUtils {

    private static final int BASE_VISIBLE_ROWS = 1;

    private MultiLevelEmitterUtils() {
    }

    public static int calculateVisibleSlotCount(int configuredSlots, int capacity) {
        int safeCapacity = Math.max(1, capacity);
        int normalizedConfigured = Math.max(BASE_VISIBLE_ROWS, configuredSlots);
        return Math.min(safeCapacity, normalizedConfigured);
    }

    public static List<Long> normalizeObservedValuesForSlotCount(List<Long> observedValues, int slotCount) {
        int normalizedSlotCount = Math.max(0, slotCount);
        List<Long> normalized = new ArrayList<>(normalizedSlotCount);
        for (int slot = 0; slot < normalizedSlotCount; slot++) {
            Long value = observedValues != null && slot < observedValues.size()
                    ? observedValues.get(slot)
                    : null;
            normalized.add(value == null ? 0L : value);
        }
        return normalized;
    }

    public static <T extends Enum<T>> List<T> readEnumListFromNBT(
            CompoundTag tag,
            String key,
            Function<String, T> parser
    ) {
        if (tag == null || key == null || !tag.contains(key, Tag.TAG_LIST)) {
            return new ArrayList<>();
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        List<T> out = new ArrayList<>(list.size());
        for (Tag element : list) {
            out.add(parser.apply(element.getAsString()));
        }
        return out;
    }

    public static <T extends Enum<T>> void writeEnumListToNBT(
            List<T> values,
            CompoundTag target,
            String key,
            T defaultValue
    ) {
        ListTag list = new ListTag();
        if (values != null) {
            for (T value : values) {
                T safeValue = value == null ? defaultValue : value;
                list.add(StringTag.valueOf(safeValue.name()));
            }
        }
        target.put(key, list);
    }
}
