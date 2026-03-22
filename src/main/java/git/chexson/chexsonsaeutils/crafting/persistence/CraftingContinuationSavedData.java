package git.chexson.chexsonsaeutils.crafting.persistence;

import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingDetail;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CraftingContinuationSavedData extends SavedData {

    private static final String DATA_NAME = "chexsonsaeutils_crafting_continuation";
    private static final String WAITING_DETAILS_KEY = "waiting_details";

    private final Map<UUID, CraftingContinuationWaitingDetail> waitingDetails = new LinkedHashMap<>();

    public CraftingContinuationSavedData() {
    }

    private CraftingContinuationSavedData(CompoundTag tag) {
        if (tag == null || !tag.contains(WAITING_DETAILS_KEY, Tag.TAG_LIST)) {
            return;
        }

        ListTag waitingDetailTags = tag.getList(WAITING_DETAILS_KEY, Tag.TAG_COMPOUND);
        for (Tag waitingDetailTag : waitingDetailTags) {
            CraftingContinuationWaitingDetail waitingDetail =
                    CraftingContinuationWaitingDetail.readFromTag((CompoundTag) waitingDetailTag);
            waitingDetails.put(waitingDetail.craftId(), waitingDetail);
        }
    }

    public static CraftingContinuationSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                CraftingContinuationSavedData::read,
                CraftingContinuationSavedData::new,
                DATA_NAME
        );
    }

    public void putWaitingDetail(UUID craftId, CraftingContinuationWaitingDetail detail) {
        if (craftId == null || detail == null) {
            return;
        }

        CraftingContinuationWaitingDetail normalizedDetail = detail.craftId().equals(craftId)
                ? detail
                : new CraftingContinuationWaitingDetail(
                        craftId,
                        detail.finalOutputKey(),
                        detail.requestedAmount(),
                        detail.waitingBranches(),
                        detail.runningBranchLabels()
                );
        waitingDetails.put(craftId, normalizedDetail);
        setDirty();
    }

    public @Nullable CraftingContinuationWaitingDetail getWaitingDetail(UUID craftId) {
        if (craftId == null) {
            return null;
        }
        return waitingDetails.get(craftId);
    }

    public void removeWaitingDetail(UUID craftId) {
        if (craftId == null) {
            return;
        }

        if (waitingDetails.remove(craftId) != null) {
            setDirty();
        }
    }

    public void retainLiveCrafts(Set<UUID> liveCraftIds) {
        Set<UUID> safeLiveCraftIds = liveCraftIds == null ? Set.of() : Set.copyOf(liveCraftIds);
        boolean removedAny = waitingDetails.entrySet().removeIf(entry -> !safeLiveCraftIds.contains(entry.getKey()));
        if (removedAny) {
            setDirty();
        }
    }

    public Map<UUID, CraftingContinuationWaitingDetail> snapshotWaitingDetails() {
        return Map.copyOf(waitingDetails);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag waitingDetailTags = new ListTag();
        waitingDetails.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> waitingDetailTags.add(entry.getValue().writeToTag()));
        tag.put(WAITING_DETAILS_KEY, waitingDetailTags);
        return tag;
    }

    private static CraftingContinuationSavedData read(CompoundTag tag) {
        return new CraftingContinuationSavedData(tag);
    }
}
