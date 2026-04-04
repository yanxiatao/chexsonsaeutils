package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.crafting.persistence.CraftingContinuationSavedData;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingBranch;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingDetail;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterCraftingContinuationPersistenceCompatibilityTest {

    private static final HolderLookup.Provider LOOKUP_PROVIDER = RegistryAccess.EMPTY;

    @Test
    void retainLiveCraftsClearsDetachedWaitingDetails() {
        UUID liveCraftId = UUID.fromString("7aaf8cf3-7f7d-47d0-92ce-3efab01e32e0");
        UUID staleCraftId = UUID.fromString("c816290f-78fe-46f6-bf29-c965d8d18c12");
        CraftingContinuationSavedData savedData = new CraftingContinuationSavedData();
        CraftingContinuationWaitingDetail liveDetail = new CraftingContinuationWaitingDetail(
                liveCraftId,
                "minecraft:controller",
                3L,
                List.of(new CraftingContinuationWaitingBranch(
                        "controller_core",
                        0,
                        orderedStacks(Map.of("minecraft:fluix_crystal", 4L))
                )),
                List.of("controller_shell")
        );
        CraftingContinuationWaitingDetail staleDetail = new CraftingContinuationWaitingDetail(
                staleCraftId,
                "minecraft:drive",
                2L,
                List.of(new CraftingContinuationWaitingBranch(
                        "drive_cell",
                        1,
                        orderedStacks(Map.of("minecraft:glass", 8L))
                )),
                List.of("drive_casing")
        );

        savedData.putWaitingDetail(liveCraftId, liveDetail);
        savedData.putWaitingDetail(staleCraftId, staleDetail);
        assertEquals(Set.of(liveCraftId, staleCraftId), savedData.snapshotWaitingDetails().keySet());

        savedData.retainLiveCrafts(Set.of(liveCraftId));

        CraftingContinuationSavedData restored = roundTripSavedData(savedData);
        Map<UUID, CraftingContinuationWaitingDetail> restoredSnapshot = restored.snapshotWaitingDetails();

        assertEquals(Set.of(liveCraftId), restoredSnapshot.keySet());
        assertEquals(liveDetail, restoredSnapshot.get(liveCraftId));
        assertNull(restoredSnapshot.get(staleCraftId));
        assertNull(restored.getWaitingDetail(staleCraftId));
    }

    @Test
    void roundTripsRequestedAmountAndRunningBranchLabels() {
        UUID liveCraftId = UUID.fromString("8fc29e11-d3a3-42b9-9b05-0432a73d71c4");
        List<CraftingContinuationWaitingBranch> waitingBranches = List.of(
                new CraftingContinuationWaitingBranch(
                        "processor_branch",
                        0,
                        orderedStacks(Map.of(
                                "minecraft:redstone", 3L,
                                "minecraft:silicon", 2L
                        ))
                ),
                new CraftingContinuationWaitingBranch(
                        "final_assembly",
                        1,
                        orderedStacks(Map.of("minecraft:printed_calculation_processor", 1L))
                )
        );
        List<String> runningBranchLabels = List.of("processor_branch", "final_assembly");
        CraftingContinuationWaitingDetail detail = new CraftingContinuationWaitingDetail(
                liveCraftId,
                "minecraft:calculation_processor",
                64L,
                waitingBranches,
                runningBranchLabels
        );
        CraftingContinuationSavedData savedData = new CraftingContinuationSavedData();

        savedData.putWaitingDetail(liveCraftId, detail);

        CraftingContinuationSavedData restored = roundTripSavedData(savedData);
        Map<UUID, CraftingContinuationWaitingDetail> restoredSnapshot = restored.snapshotWaitingDetails();
        CraftingContinuationWaitingDetail restoredDetail = restoredSnapshot.get(liveCraftId);

        assertTrue(restoredSnapshot.containsKey(liveCraftId));
        assertEquals("minecraft:calculation_processor", restoredDetail.finalOutputKey());
        assertEquals(64L, restoredDetail.requestedAmount());
        assertEquals(waitingBranches, restoredDetail.waitingBranches());
        assertEquals(runningBranchLabels, restoredDetail.runningBranchLabels());
        assertEquals(detail, restoredDetail);
    }

    private static CraftingContinuationSavedData roundTripSavedData(CraftingContinuationSavedData savedData) {
        try {
            CompoundTag savedTag = savedData.save(new CompoundTag(), LOOKUP_PROVIDER);
            Method readMethod = CraftingContinuationSavedData.class.getDeclaredMethod(
                    "read",
                    CompoundTag.class,
                    HolderLookup.Provider.class
            );
            readMethod.setAccessible(true);
            return (CraftingContinuationSavedData) readMethod.invoke(null, savedTag, LOOKUP_PROVIDER);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to round-trip crafting continuation saved data", exception);
        }
    }

    private static Map<String, Long> orderedStacks(Map<String, Long> rawStacks) {
        Map<String, Long> orderedStacks = new LinkedHashMap<>();
        rawStacks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> orderedStacks.put(entry.getKey(), entry.getValue()));
        return orderedStacks;
    }
}
