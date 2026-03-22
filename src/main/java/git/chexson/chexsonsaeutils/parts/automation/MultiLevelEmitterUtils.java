package git.chexson.chexsonsaeutils.parts.automation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

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

    public static List<MultiLevelEmitterPart.LogicRelation> readLogicRelationsFromNBT(CompoundTag tag, String key) {
        if (tag == null || key == null || !tag.contains(key, Tag.TAG_LIST)) {
            return new ArrayList<>();
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        List<MultiLevelEmitterPart.LogicRelation> out = new ArrayList<>(list.size());
        for (Tag element : list) {
            out.add(MultiLevelEmitterPart.LogicRelation.fromPersisted(element.getAsString()));
        }
        return out;
    }

    public static List<MultiLevelEmitterPart.ComparisonMode> readComparisonModesFromNBT(CompoundTag tag, String key) {
        if (tag == null || key == null || !tag.contains(key, Tag.TAG_LIST)) {
            return new ArrayList<>();
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        List<MultiLevelEmitterPart.ComparisonMode> out = new ArrayList<>(list.size());
        for (Tag element : list) {
            out.add(MultiLevelEmitterPart.ComparisonMode.fromPersisted(element.getAsString()));
        }
        return out;
    }

    public static List<MultiLevelEmitterPart.MatchingMode> readMatchingModesFromNBT(CompoundTag tag, String key) {
        if (tag == null || key == null || !tag.contains(key, Tag.TAG_LIST)) {
            return new ArrayList<>();
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        List<MultiLevelEmitterPart.MatchingMode> out = new ArrayList<>(list.size());
        for (Tag element : list) {
            out.add(MultiLevelEmitterPart.MatchingMode.fromPersisted(element.getAsString()));
        }
        return out;
    }

    public static void writeLogicRelationsToNBT(
            List<MultiLevelEmitterPart.LogicRelation> relations,
            CompoundTag target,
            String key
    ) {
        ListTag list = new ListTag();
        if (relations != null) {
            for (MultiLevelEmitterPart.LogicRelation relation : relations) {
                MultiLevelEmitterPart.LogicRelation value =
                        relation == null ? MultiLevelEmitterPart.LogicRelation.OR : relation;
                list.add(StringTag.valueOf(value.name()));
            }
        }
        target.put(key, list);
    }

    public static void writeComparisonModesToNBT(
            List<MultiLevelEmitterPart.ComparisonMode> modes,
            CompoundTag target,
            String key
    ) {
        ListTag list = new ListTag();
        if (modes != null) {
            for (MultiLevelEmitterPart.ComparisonMode mode : modes) {
                MultiLevelEmitterPart.ComparisonMode value =
                        mode == null ? MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL : mode;
                list.add(StringTag.valueOf(value.name()));
            }
        }
        target.put(key, list);
    }

    public static void writeMatchingModesToNBT(
            List<MultiLevelEmitterPart.MatchingMode> modes,
            CompoundTag target,
            String key
    ) {
        ListTag list = new ListTag();
        if (modes != null) {
            for (MultiLevelEmitterPart.MatchingMode mode : modes) {
                MultiLevelEmitterPart.MatchingMode value =
                        mode == null ? MultiLevelEmitterPart.MatchingMode.STRICT : mode;
                list.add(StringTag.valueOf(value.name()));
            }
        }
        target.put(key, list);
    }
}
