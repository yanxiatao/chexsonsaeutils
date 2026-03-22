package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.persistence.CraftingContinuationSavedData;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusSnapshot;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingBranch;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingDetail;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterCraftingContinuationStateTest {

    private static final Path STATUS_SERVICE = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/crafting/status/CraftingContinuationStatusService.java");
    private static final Path EXECUTING_JOB_ACCESSOR = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/ExecutingCraftingJobAccessor.java");

    @Test
    void defaultsOff() {
        assertEquals(CraftingContinuationMode.DEFAULT, CraftingContinuationMode.defaultMode());
        assertFalse(CraftingContinuationMode.DEFAULT.allowsSimulationStart());
        assertTrue(CraftingContinuationMode.IGNORE_MISSING.allowsSimulationStart());
    }

    @Test
    void roundTripsWaitingDetail() {
        UUID craftId = UUID.fromString("8d8ef3b1-33b2-4c4e-84f8-7c6f21b9f920");
        CraftingContinuationWaitingDetail detail = new CraftingContinuationWaitingDetail(
                craftId,
                "minecraft:crafting_table",
                12L,
                List.of(new CraftingContinuationWaitingBranch(
                        "oak_planks",
                        0,
                        orderedStacks(Map.of(
                                "minecraft:oak_log", 4L,
                                "minecraft:stick", 2L
                        ))
                )),
                List.of("oak_planks", "stick_output")
        );

        CraftingContinuationWaitingDetail restored =
                CraftingContinuationWaitingDetail.readFromTag(detail.writeToTag());

        assertEquals(craftId, restored.craftId());
        assertEquals("minecraft:crafting_table", restored.finalOutputKey());
        assertEquals(12L, restored.requestedAmount());
        assertIterableEquals(List.of("oak_planks", "stick_output"), restored.runningBranchLabels());
        assertEquals(detail.waitingBranches(), restored.waitingBranches());
        assertEquals(4L, restored.waitingBranches().get(0).missingStacks().get("minecraft:oak_log"));
        assertEquals(2L, restored.waitingBranches().get(0).missingStacks().get("minecraft:stick"));
    }

    @Test
    void preservesPlanOrder() {
        List<CraftingContinuationWaitingBranch> waitingBranches = List.of(
                new CraftingContinuationWaitingBranch("first_branch", 0, orderedStacks(Map.of("minecraft:redstone", 1L))),
                new CraftingContinuationWaitingBranch("second_branch", 1, orderedStacks(Map.of("minecraft:iron_ingot", 64L))),
                new CraftingContinuationWaitingBranch("third_branch", 2, orderedStacks(Map.of("minecraft:glass", 3L)))
        );
        CraftingContinuationWaitingDetail detail = new CraftingContinuationWaitingDetail(
                UUID.fromString("3c09ad4c-51f2-486a-99f0-bbaeddb7c1d1"),
                "minecraft:observer",
                3L,
                waitingBranches,
                List.of("running_branch")
        );

        CraftingContinuationWaitingDetail restored =
                CraftingContinuationWaitingDetail.readFromTag(detail.writeToTag());

        assertIterableEquals(waitingBranches, restored.waitingBranches());
        assertEquals(List.of("first_branch", "second_branch", "third_branch"),
                restored.waitingBranches().stream().map(CraftingContinuationWaitingBranch::branchLabel).toList());
    }

    @Test
    void retainsLiveCraftIds() {
        UUID liveCraftId = UUID.fromString("016d82ab-cfe8-40be-8df7-c64db2d6fbdf");
        UUID staleCraftId = UUID.fromString("23f31537-1c8b-4a4a-a95d-4a3a04cf55c1");
        CraftingContinuationSavedData savedData = new CraftingContinuationSavedData();
        CraftingContinuationWaitingDetail liveDetail = new CraftingContinuationWaitingDetail(
                liveCraftId,
                "minecraft:controller",
                1L,
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

        savedData.retainLiveCrafts(Set.of(liveCraftId));

        assertEquals(liveDetail, savedData.getWaitingDetail(liveCraftId));
        assertNull(savedData.getWaitingDetail(staleCraftId));
    }

    @Test
    void statusSnapshotCarriesOutputIdentity() {
        UUID craftId = UUID.fromString("2ec18b75-8f87-47e1-a50f-c1190b74e522");
        List<CraftingContinuationWaitingBranch> waitingBranches = List.of(
                new CraftingContinuationWaitingBranch(
                        "processor_branch",
                        0,
                        orderedStacks(Map.of("minecraft:redstone", 3L))
                )
        );
        CraftingContinuationStatusSnapshot snapshot = new CraftingContinuationStatusSnapshot(
                craftId,
                "minecraft:calculation_processor",
                5L,
                true,
                waitingBranches,
                List.of("assembly_branch")
        );

        assertEquals(craftId, snapshot.craftId());
        assertEquals("minecraft:calculation_processor", snapshot.finalOutputKey());
        assertEquals(5L, snapshot.requestedAmount());
        assertTrue(snapshot.hasWaitingBranches());
        assertIterableEquals(waitingBranches, snapshot.waitingBranches());
        assertIterableEquals(List.of("assembly_branch"), snapshot.runningBranchLabels());
    }

    @Test
    void selectedCpuSnapshotUsesLiveWaitingState() throws IOException {
        assertContains(STATUS_SERVICE, "buildLiveSnapshot(");
        assertContains(STATUS_SERVICE, "getWaitingFor().list");
        assertContains(STATUS_SERVICE, "clearCompletedJob(craftId)");
        assertContains(EXECUTING_JOB_ACCESSOR, "getRemainingAmount");
        assertContains(EXECUTING_JOB_ACCESSOR, "getPlayerId");
    }

    @Test
    void storageAvailabilityDeltaDrivesResume() throws IOException {
        assertContains(STATUS_SERVICE, "observedAvailableWaitingStacks");
        assertContains(STATUS_SERVICE, "cachedInventory::get");
        assertContains(STATUS_SERVICE, "currentAvailableWaitingStacks");
        assertContains(STATUS_SERVICE, "previousAvailableWaitingStacks");
        assertContains(STATUS_SERVICE, "hasAvailabilityIncrease(");
        assertContains(STATUS_SERVICE, "resolveRefillActionSource(");
        assertContains(STATUS_SERVICE, "storage.insert(liveKey, extracted - inserted, Actionable.MODULATE, refillActionSource)");
    }

    private static Map<String, Long> orderedStacks(Map<String, Long> rawStacks) {
        Map<String, Long> orderedStacks = new LinkedHashMap<>();
        rawStacks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> orderedStacks.put(entry.getKey(), entry.getValue()));
        return orderedStacks;
    }
}
