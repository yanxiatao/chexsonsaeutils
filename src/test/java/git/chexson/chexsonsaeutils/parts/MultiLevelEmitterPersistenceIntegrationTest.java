package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterPersistenceIntegrationTest {

    @Test
    void thresholdComparisonRelationAndMatchingRoundTripIsStable() {
        CompoundTag root = new CompoundTag();

        Map<Integer, Long> thresholds = MultiLevelEmitterPart.normalizeThresholdsForSlotCount(
                Map.of(0, 5L, 1, 9L), 2);
        List<MultiLevelEmitterPart.ComparisonMode> comparisons = List.of(
                MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                MultiLevelEmitterPart.ComparisonMode.LESS_THAN
        );
        List<MultiLevelEmitterPart.LogicRelation> relations = List.of(MultiLevelEmitterPart.LogicRelation.OR);
        List<MultiLevelEmitterPart.MatchingMode> matching = List.of(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL
        );

        MultiLevelEmitterPart.writeThresholdsToNbt(thresholds, root, "reportingValues");
        MultiLevelEmitterUtils.writeComparisonModesToNBT(comparisons, root, "comparison_modes");
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(relations, root, "logic_relations");
        MultiLevelEmitterUtils.writeMatchingModesToNBT(matching, root, "matching_modes");

        Map<Integer, Long> loadedThresholds = MultiLevelEmitterPart.readThresholdsFromNbt(root, "reportingValues");
        List<MultiLevelEmitterPart.ComparisonMode> loadedComparisons =
                MultiLevelEmitterUtils.readComparisonModesFromNBT(root, "comparison_modes");
        List<MultiLevelEmitterPart.LogicRelation> loadedRelations =
                MultiLevelEmitterUtils.readLogicRelationsFromNBT(root, "logic_relations");
        List<MultiLevelEmitterPart.MatchingMode> loadedMatching =
                MultiLevelEmitterUtils.readMatchingModesFromNBT(root, "matching_modes");

        assertEquals(thresholds, loadedThresholds);
        assertEquals(comparisons, loadedComparisons);
        assertEquals(relations, loadedRelations);
        assertEquals(matching, loadedMatching);
    }

    @Test
    void matchingAndCraftingModesRoundTripIsStable() {
        CompoundTag root = new CompoundTag();
        List<MultiLevelEmitterPart.MatchingMode> matching = List.of(
                MultiLevelEmitterPart.MatchingMode.PERCENT_50,
                MultiLevelEmitterPart.MatchingMode.STRICT
        );
        List<MultiLevelEmitterPart.CraftingMode> crafting = List.of(
                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
        );

        MultiLevelEmitterUtils.writeMatchingModesToNBT(matching, root, "matching_modes");
        MultiLevelEmitterUtils.writeCraftingModesToNBT(crafting, root, "crafting_modes");

        List<MultiLevelEmitterPart.MatchingMode> loadedMatching =
                MultiLevelEmitterUtils.readMatchingModesFromNBT(root, "matching_modes");
        List<MultiLevelEmitterPart.CraftingMode> loadedCrafting =
                MultiLevelEmitterUtils.readCraftingModesFromNBT(root, "crafting_modes");

        assertEquals(matching, loadedMatching);
        assertEquals(crafting, loadedCrafting);
    }

    @Test
    void invalidMatchingModeFallsBackToStrictOnLoad() {
        CompoundTag root = new CompoundTag();
        root.putString("invalid", "FUZZY_NOT_SUPPORTED");
        MultiLevelEmitterPart.MatchingMode parsed =
                MultiLevelEmitterPart.MatchingMode.fromPersisted(root.getString("invalid"));
        MultiLevelEmitterPart.MatchingMode effective =
                MultiLevelEmitterPart.resolveMatchingMode(parsed, false);

        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, effective);
        assertTrue(effective != MultiLevelEmitterPart.MatchingMode.IGNORE_ALL);
    }

    @Test
    void invalidCraftingModeFallsBackToNoneOnLoad() {
        CompoundTag root = new CompoundTag();
        root.putString("invalid", "CRAFTING_NOT_SUPPORTED");
        MultiLevelEmitterPart.CraftingMode parsed =
                MultiLevelEmitterPart.CraftingMode.fromPersisted(root.getString("invalid"));
        MultiLevelEmitterPart.CraftingMode effective =
                MultiLevelEmitterPart.resolveCraftingMode(parsed, false);

        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, effective);
        assertTrue(effective != MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT);
    }
}

