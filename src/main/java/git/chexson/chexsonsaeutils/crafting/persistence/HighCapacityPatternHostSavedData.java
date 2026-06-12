package git.chexson.chexsonsaeutils.crafting.persistence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HighCapacityPatternHostSavedData extends SavedData {

    private static final String DATA_NAME = "chexsonsaeutils_high_capacity_pattern_hosts";
    private static final String HOSTS_KEY = "hosts";
    private static final String HOST_POS_KEY = "pos";
    private static final String HOST_SLOTS_KEY = "slots";
    private static final String SLOT_INDEX_KEY = "slot";
    private static final String SLOT_STACK_KEY = "stack";
    private static final Factory<HighCapacityPatternHostSavedData> FACTORY =
            new Factory<>(HighCapacityPatternHostSavedData::new, HighCapacityPatternHostSavedData::read);

    private final Map<Long, Map<Integer, ItemStack>> slotsByHost = new LinkedHashMap<>();

    public HighCapacityPatternHostSavedData() {
    }

    private HighCapacityPatternHostSavedData(CompoundTag tag, HolderLookup.Provider provider) {
        if (tag == null || !tag.contains(HOSTS_KEY, Tag.TAG_LIST)) {
            return;
        }

        ListTag hostTags = tag.getList(HOSTS_KEY, Tag.TAG_COMPOUND);
        for (Tag hostTag : hostTags) {
            if (!(hostTag instanceof CompoundTag hostCompound)) {
                continue;
            }

            long hostPos = hostCompound.getLong(HOST_POS_KEY);
            ListTag slotTags = hostCompound.getList(HOST_SLOTS_KEY, Tag.TAG_COMPOUND);
            Map<Integer, ItemStack> hostSlots = new LinkedHashMap<>();
            for (Tag slotTag : slotTags) {
                if (!(slotTag instanceof CompoundTag slotCompound)) {
                    continue;
                }
                int slot = slotCompound.getInt(SLOT_INDEX_KEY);
                ItemStack stack = ItemStack.parseOptional(provider, slotCompound.getCompound(SLOT_STACK_KEY));
                if (!stack.isEmpty()) {
                    hostSlots.put(slot, stack);
                }
            }
            if (!hostSlots.isEmpty()) {
                slotsByHost.put(hostPos, hostSlots);
            }
        }
    }

    public static HighCapacityPatternHostSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Map<Integer, ItemStack> snapshotSlots(BlockPos pos) {
        Map<Integer, ItemStack> hostSlots = slotsByHost.get(pos.asLong());
        if (hostSlots == null || hostSlots.isEmpty()) {
            return Map.of();
        }

        Map<Integer, ItemStack> copy = new LinkedHashMap<>(hostSlots.size());
        hostSlots.forEach((slot, stack) -> {
            if (!stack.isEmpty()) {
                copy.put(slot, stack.copy());
            }
        });
        return Map.copyOf(copy);
    }

    public void setSlot(BlockPos pos, int slot, ItemStack stack) {
        if (pos == null || slot < 0) {
            return;
        }

        long key = pos.asLong();
        Map<Integer, ItemStack> hostSlots = slotsByHost.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        if (stack == null || stack.isEmpty()) {
            if (hostSlots.remove(slot) != null) {
                if (hostSlots.isEmpty()) {
                    slotsByHost.remove(key);
                }
                setDirty();
            }
            return;
        }

        ItemStack normalized = stack.copyWithCount(1);
        ItemStack previous = hostSlots.put(slot, normalized);
        if (previous == null || !ItemStack.isSameItemSameComponents(previous, normalized)) {
            setDirty();
        }
    }

    public void removeHost(BlockPos pos) {
        if (pos == null) {
            return;
        }

        if (slotsByHost.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag hostTags = new ListTag();
        slotsByHost.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue().isEmpty()) {
                        return;
                    }
                    CompoundTag hostTag = new CompoundTag();
                    hostTag.putLong(HOST_POS_KEY, entry.getKey());
                    ListTag slotTags = new ListTag();
                    entry.getValue().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(slotEntry -> {
                                ItemStack stack = slotEntry.getValue();
                                if (stack.isEmpty()) {
                                    return;
                                }
                                CompoundTag slotTag = new CompoundTag();
                                slotTag.putInt(SLOT_INDEX_KEY, slotEntry.getKey());
                                slotTag.put(SLOT_STACK_KEY, stack.saveOptional(provider));
                                slotTags.add(slotTag);
                            });
                    if (!slotTags.isEmpty()) {
                        hostTag.put(HOST_SLOTS_KEY, slotTags);
                        hostTags.add(hostTag);
                    }
                });
        tag.put(HOSTS_KEY, hostTags);
        return tag;
    }

    private static HighCapacityPatternHostSavedData read(CompoundTag tag, HolderLookup.Provider provider) {
        return new HighCapacityPatternHostSavedData(tag, provider);
    }
}
