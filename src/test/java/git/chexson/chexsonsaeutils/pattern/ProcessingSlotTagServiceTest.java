package git.chexson.chexsonsaeutils.pattern;

import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotCandidateGroup;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleValidation;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingSlotTagServiceTest {
    private static final ResourceLocation SOURCE_TAG_ALPHA = id("chexsonsaeutils", "source/alpha");
    private static final ResourceLocation SOURCE_TAG_BETA = id("chexsonsaeutils", "source/beta");
    private static final ResourceLocation SOURCE_TAG_GAMMA = id("chexsonsaeutils", "source/gamma");
    private static final ResourceLocation SHARED_ITEM = id("chexsonsaeutils", "shared_item");
    private static final ResourceLocation ALPHA_ONLY_ITEM = id("chexsonsaeutils", "alpha_only_item");
    private static final ResourceLocation BETA_ONLY_ITEM = id("chexsonsaeutils", "beta_only_item");
    private static final ResourceLocation INVALID_ITEM = id("chexsonsaeutils", "invalid_item");

    @Test
    void sourceTagsAreOnlyTagsAlreadyOnIngredient() {
        ItemStack sourceStack = null;
        ProcessingSlotTagService service = createService(
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                Map.of(),
                Map.of()
        );
        ProcessingSlotRuleValidation validation = new ProcessingSlotRuleValidation(service);

        assertEquals(List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA), service.extractSourceTagIds(sourceStack));
        assertEquals(
                orderedSet(SOURCE_TAG_ALPHA),
                validation.sanitizeSelectedTags(sourceStack, orderedSet(SOURCE_TAG_ALPHA, SOURCE_TAG_GAMMA))
        );
    }

    @Test
    void candidatePoolIsGroupedByTagWithoutCrossGroupDeduplication() {
        ItemStack sourceStack = null;
        ProcessingSlotTagService service = createService(
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                orderedMap(
                        SOURCE_TAG_ALPHA, List.of(ALPHA_ONLY_ITEM, SHARED_ITEM),
                        SOURCE_TAG_BETA, List.of(BETA_ONLY_ITEM, SHARED_ITEM)
                ),
                Map.of()
        );

        List<ProcessingSlotCandidateGroup> groups = service.buildCandidateGroups(sourceStack);

        assertEquals(2, groups.size());
        assertEquals(new ProcessingSlotCandidateGroup(SOURCE_TAG_ALPHA, List.of(ALPHA_ONLY_ITEM, SHARED_ITEM)), groups.get(0));
        assertEquals(new ProcessingSlotCandidateGroup(SOURCE_TAG_BETA, List.of(BETA_ONLY_ITEM, SHARED_ITEM)), groups.get(1));
    }

    @Test
    void sameItemCanAppearInMultipleTagGroups() {
        ItemStack sourceStack = null;
        ProcessingSlotTagService service = createService(
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                orderedMap(
                        SOURCE_TAG_ALPHA, List.of(ALPHA_ONLY_ITEM, SHARED_ITEM),
                        SOURCE_TAG_BETA, List.of(BETA_ONLY_ITEM, SHARED_ITEM)
                ),
                Map.of()
        );

        long sharedOccurrences = service.buildCandidateGroups(sourceStack).stream()
                .flatMap(group -> group.itemIds().stream())
                .filter(SHARED_ITEM::equals)
                .count();

        assertEquals(2, sharedOccurrences);
    }

    @Test
    void rejectsExplicitCandidateWithoutSharedTag() {
        ItemStack sourceStack = null;
        ProcessingSlotTagService service = createService(
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                Map.of(),
                orderedMap(
                        SHARED_ITEM, orderedSet(SOURCE_TAG_ALPHA),
                        INVALID_ITEM, orderedSet(SOURCE_TAG_GAMMA)
                )
        );
        ProcessingSlotRuleValidation validation = new ProcessingSlotRuleValidation(service);

        assertTrue(service.isSelectableCandidate(sourceStack, SHARED_ITEM));
        assertFalse(service.isSelectableCandidate(sourceStack, INVALID_ITEM));
        assertEquals(
                orderedSet(SHARED_ITEM),
                validation.validateExplicitCandidates(sourceStack, orderedSet(SHARED_ITEM, INVALID_ITEM))
        );
    }

    private static ProcessingSlotTagService createService(
            List<ResourceLocation> sourceTags,
            Map<ResourceLocation, List<ResourceLocation>> tagMembers,
            Map<ResourceLocation, Set<ResourceLocation>> itemTags
    ) {
        return new ProcessingSlotTagService(
                ignored -> sourceTags,
                tagId -> tagMembers.getOrDefault(tagId, List.of()),
                itemId -> itemTags.getOrDefault(itemId, Set.of())
        );
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
