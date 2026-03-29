package git.chexson.chexsonsaeutils.pattern;

import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementPersistence;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternSlotReplacementRule;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleDraft;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleStatus;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleValidation;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleVisualState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPatternReplacementPersistenceTest {
    private static final ResourceLocation SOURCE_TAG_ALPHA = id("chexsonsaeutils", "source/alpha");
    private static final ResourceLocation SOURCE_TAG_BETA = id("chexsonsaeutils", "source/beta");
    private static final ResourceLocation SOURCE_TAG_GAMMA = id("chexsonsaeutils", "source/gamma");
    private static final ResourceLocation SHARED_ITEM = id("chexsonsaeutils", "shared_item");
    private static final ResourceLocation SECOND_SHARED_ITEM = id("chexsonsaeutils", "second_shared_item");
    private static final ResourceLocation INVALID_ITEM = id("chexsonsaeutils", "invalid_item");
    private static final ResourceLocation STONE_ID = id("minecraft", "stone");
    private static final ResourceLocation DIRT_ID = id("minecraft", "dirt");

    @Test
    void writesAndReadsSlotRulesFromPatternChildTag() {
        ProcessingPatternReplacementPersistence persistence = createPersistence();
        CompoundTag patternTag = new CompoundTag();
        List<ProcessingPatternSlotReplacementRule> rules = List.of(
                new ProcessingPatternSlotReplacementRule(
                        3,
                        STONE_ID,
                        orderedSet(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                        orderedSet(SHARED_ITEM)
                ),
                new ProcessingPatternSlotReplacementRule(
                        7,
                        DIRT_ID,
                        orderedSet(SOURCE_TAG_BETA),
                        orderedSet(SECOND_SHARED_ITEM)
                )
        );

        persistence.writeRules(patternTag, rules);

        assertTrue(patternTag.contains(ProcessingPatternReplacementPersistence.ROOT_TAG, Tag.TAG_COMPOUND));
        CompoundTag rootTag = patternTag.getCompound(ProcessingPatternReplacementPersistence.ROOT_TAG);
        assertTrue(rootTag.contains(ProcessingPatternReplacementPersistence.SLOTS_TAG, Tag.TAG_LIST));
        ListTag slots = rootTag.getList(ProcessingPatternReplacementPersistence.SLOTS_TAG, Tag.TAG_COMPOUND);
        assertEquals(2, slots.size());
        assertEquals(3, slots.getCompound(0).getInt(ProcessingPatternReplacementPersistence.SLOT_TAG));
        assertEquals("minecraft:stone",
                slots.getCompound(0).getString(ProcessingPatternReplacementPersistence.SOURCE_ITEM_TAG));

        assertEquals(rules, persistence.readRules(patternTag));
    }

    @Test
    void restoreRequiresMatchingSourceItem() {
        ProcessingPatternReplacementPersistence persistence = createPersistence();
        CompoundTag patternTag = new CompoundTag();
        persistence.writeRules(patternTag, List.of(new ProcessingPatternSlotReplacementRule(
                4,
                STONE_ID,
                orderedSet(SOURCE_TAG_ALPHA),
                orderedSet(SHARED_ITEM)
        )));

        ProcessingSlotRuleDraft restored = persistence.restoreRuleDraft(
                patternTag,
                4,
                STONE_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA)
        );
        ProcessingSlotRuleDraft skipped = persistence.restoreRuleDraft(
                patternTag,
                4,
                DIRT_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA)
        );

        assertNotNull(restored);
        assertEquals(orderedSet(SOURCE_TAG_ALPHA), restored.selectedTagIds());
        assertEquals(orderedSet(SHARED_ITEM), restored.explicitCandidateIds());
        assertNull(skipped);
    }

    @Test
    void restoreSanitizesMissingTagsAndIllegalCandidates() {
        ProcessingPatternReplacementPersistence persistence = createPersistence();
        CompoundTag patternTag = new CompoundTag();
        persistence.writeRules(patternTag, List.of(new ProcessingPatternSlotReplacementRule(
                1,
                STONE_ID,
                orderedSet(SOURCE_TAG_ALPHA, SOURCE_TAG_GAMMA),
                orderedSet(SHARED_ITEM, INVALID_ITEM)
        )));

        ProcessingSlotRuleDraft restored = persistence.restoreRuleDraft(
                patternTag,
                1,
                STONE_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA)
        );

        assertNotNull(restored);
        assertEquals(List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA), restored.sourceTagIds());
        assertEquals(orderedSet(SOURCE_TAG_ALPHA), restored.selectedTagIds());
        assertEquals(orderedSet(SHARED_ITEM), restored.explicitCandidateIds());
        assertFalse(restored.selectedTagIds().contains(SOURCE_TAG_GAMMA));
        assertFalse(restored.explicitCandidateIds().contains(INVALID_ITEM));
    }

    @Test
    void restoreStatusReportsUnconfiguredWhenRuleMissingOrSourceChanged() {
        ProcessingPatternReplacementPersistence persistence = createPersistence();
        CompoundTag patternTag = new CompoundTag();
        persistence.writeRules(patternTag, List.of(new ProcessingPatternSlotReplacementRule(
                4,
                STONE_ID,
                orderedSet(SOURCE_TAG_ALPHA),
                orderedSet(SHARED_ITEM)
        )));

        ProcessingSlotRuleStatus missingRuleStatus = persistence.restoreRuleStatus(
                patternTag,
                8,
                STONE_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA)
        );
        ProcessingSlotRuleStatus sourceChangedStatus = persistence.restoreRuleStatus(
                patternTag,
                4,
                DIRT_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA)
        );

        assertEquals(ProcessingSlotRuleVisualState.UNCONFIGURED, missingRuleStatus.visualState());
        assertNotNull(missingRuleStatus.visibleDraft());
        assertTrue(missingRuleStatus.visibleDraft().selectedTagIds().isEmpty());
        assertTrue(missingRuleStatus.visibleDraft().explicitCandidateIds().isEmpty());

        assertEquals(ProcessingSlotRuleVisualState.UNCONFIGURED, sourceChangedStatus.visualState());
        assertNotNull(sourceChangedStatus.visibleDraft());
        assertTrue(sourceChangedStatus.visibleDraft().selectedTagIds().isEmpty());
        assertTrue(sourceChangedStatus.visibleDraft().explicitCandidateIds().isEmpty());
    }

    @Test
    void restoreStatusReportsConfiguredWhenSanitizedRuleIsUnchanged() {
        ProcessingPatternReplacementPersistence persistence = createPersistence();
        CompoundTag patternTag = new CompoundTag();
        persistence.writeRules(patternTag, List.of(new ProcessingPatternSlotReplacementRule(
                1,
                STONE_ID,
                orderedSet(SOURCE_TAG_ALPHA),
                orderedSet(SHARED_ITEM)
        )));

        ProcessingSlotRuleStatus restoredStatus = persistence.restoreRuleStatus(
                patternTag,
                1,
                STONE_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA)
        );

        assertEquals(ProcessingSlotRuleVisualState.CONFIGURED, restoredStatus.visualState());
        assertNotNull(restoredStatus.visibleDraft());
        assertEquals(orderedSet(SOURCE_TAG_ALPHA), restoredStatus.visibleDraft().selectedTagIds());
        assertEquals(orderedSet(SHARED_ITEM), restoredStatus.visibleDraft().explicitCandidateIds());
    }

    @Test
    void restoreStatusReportsPartiallyInvalidWhenSanitizationDropsSelections() {
        ProcessingPatternReplacementPersistence persistence = createPersistence();
        CompoundTag patternTag = new CompoundTag();
        persistence.writeRules(patternTag, List.of(new ProcessingPatternSlotReplacementRule(
                1,
                STONE_ID,
                orderedSet(SOURCE_TAG_ALPHA, SOURCE_TAG_GAMMA),
                orderedSet(SHARED_ITEM, INVALID_ITEM)
        )));

        ProcessingSlotRuleStatus restoredStatus = persistence.restoreRuleStatus(
                patternTag,
                1,
                STONE_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA)
        );

        assertEquals(ProcessingSlotRuleVisualState.PARTIALLY_INVALID, restoredStatus.visualState());
        assertNotNull(restoredStatus.visibleDraft());
        assertEquals(orderedSet(SOURCE_TAG_ALPHA), restoredStatus.visibleDraft().selectedTagIds());
        assertEquals(orderedSet(SHARED_ITEM), restoredStatus.visibleDraft().explicitCandidateIds());
        assertFalse(restoredStatus.visibleDraft().selectedTagIds().contains(SOURCE_TAG_GAMMA));
        assertFalse(restoredStatus.visibleDraft().explicitCandidateIds().contains(INVALID_ITEM));
    }

    private static ProcessingPatternReplacementPersistence createPersistence() {
        Map<ResourceLocation, Set<ResourceLocation>> itemTags = orderedMap(
                SHARED_ITEM, orderedSet(SOURCE_TAG_ALPHA),
                SECOND_SHARED_ITEM, orderedSet(SOURCE_TAG_BETA),
                INVALID_ITEM, orderedSet(SOURCE_TAG_GAMMA)
        );
        ProcessingSlotTagService service = new ProcessingSlotTagService(
                ignored -> List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                tagId -> List.of(),
                itemId -> itemTags.getOrDefault(itemId, Set.of())
        );
        return new ProcessingPatternReplacementPersistence(service, new ProcessingSlotRuleValidation(service));
    }

    private static <K, V> Map<K, V> orderedMap(Object... entries) {
        Map<K, V> ordered = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) entries[i];
            @SuppressWarnings("unchecked")
            V value = (V) entries[i + 1];
            ordered.put(key, value);
        }
        return ordered;
    }

    @SafeVarargs
    private static <T> Set<T> orderedSet(T... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
