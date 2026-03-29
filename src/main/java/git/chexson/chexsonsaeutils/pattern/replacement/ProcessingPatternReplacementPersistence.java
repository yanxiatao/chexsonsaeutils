package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleVisualState.CONFIGURED;
import static git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleVisualState.PARTIALLY_INVALID;
import static git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleVisualState.UNCONFIGURED;

public class ProcessingPatternReplacementPersistence {
    public static final String ROOT_TAG = "chexsonsaeutils_processing_replacements";
    public static final String SLOTS_TAG = "slots";
    public static final String SLOT_TAG = "slot";
    public static final String SOURCE_ITEM_TAG = "source_item";
    public static final String SELECTED_TAGS_TAG = "selected_tags";
    public static final String EXPLICIT_CANDIDATES_TAG = "explicit_candidates";

    private final ProcessingSlotTagService tagService;
    private final ProcessingSlotRuleValidation validation;

    public ProcessingPatternReplacementPersistence() {
        this(new ProcessingSlotTagService(), new ProcessingSlotRuleValidation());
    }

    public ProcessingPatternReplacementPersistence(
            ProcessingSlotTagService tagService,
            ProcessingSlotRuleValidation validation
    ) {
        this.tagService = Objects.requireNonNull(tagService);
        this.validation = Objects.requireNonNull(validation);
    }

    public boolean hasReplacementMetadata(ItemStack patternStack) {
        return patternStack != null
                && !patternStack.isEmpty()
                && hasReplacementMetadata(patternStack.getTag());
    }

    public boolean hasReplacementMetadata(@Nullable CompoundTag patternTag) {
        return patternTag != null && patternTag.contains(ROOT_TAG, Tag.TAG_COMPOUND);
    }

    public void writeRules(ItemStack patternStack, Collection<ProcessingPatternSlotReplacementRule> rules) {
        if (patternStack == null || patternStack.isEmpty()) {
            return;
        }

        writeRules(patternStack.getOrCreateTag(), rules);
    }

    public void writeRules(CompoundTag patternTag, Collection<ProcessingPatternSlotReplacementRule> rules) {
        if (patternTag == null) {
            return;
        }

        List<ProcessingPatternSlotReplacementRule> persistedRules = rules == null
                ? List.of()
                : rules.stream()
                .filter(Objects::nonNull)
                .filter(ProcessingPatternSlotReplacementRule::hasSelections)
                .toList();

        if (persistedRules.isEmpty()) {
            patternTag.remove(ROOT_TAG);
            return;
        }

        CompoundTag rootTag = new CompoundTag();
        ListTag slotList = new ListTag();
        for (ProcessingPatternSlotReplacementRule rule : persistedRules) {
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt(SLOT_TAG, rule.slotIndex());
            slotTag.putString(SOURCE_ITEM_TAG, rule.sourceItemId().toString());
            slotTag.put(SELECTED_TAGS_TAG, writeIdList(rule.selectedTagIds()));
            slotTag.put(EXPLICIT_CANDIDATES_TAG, writeIdList(rule.explicitCandidateIds()));
            slotList.add(slotTag);
        }
        rootTag.put(SLOTS_TAG, slotList);
        patternTag.put(ROOT_TAG, rootTag);
    }

    public List<ProcessingPatternSlotReplacementRule> readRules(ItemStack patternStack) {
        if (patternStack == null || patternStack.isEmpty()) {
            return List.of();
        }

        return readRules(patternStack.getTag());
    }

    public List<ProcessingPatternSlotReplacementRule> readRules(@Nullable CompoundTag patternTag) {
        if (!hasReplacementMetadata(patternTag)) {
            return List.of();
        }

        CompoundTag rootTag = patternTag.getCompound(ROOT_TAG);
        ListTag slotList = rootTag.getList(SLOTS_TAG, Tag.TAG_COMPOUND);
        List<ProcessingPatternSlotReplacementRule> rules = new ArrayList<>();
        for (Tag entry : slotList) {
            if (!(entry instanceof CompoundTag slotTag) || !slotTag.contains(SOURCE_ITEM_TAG, Tag.TAG_STRING)) {
                continue;
            }

            ResourceLocation sourceItemId = ResourceLocation.tryParse(slotTag.getString(SOURCE_ITEM_TAG));
            if (sourceItemId == null) {
                continue;
            }

            rules.add(new ProcessingPatternSlotReplacementRule(
                    slotTag.getInt(SLOT_TAG),
                    sourceItemId,
                    readIdSet(slotTag, SELECTED_TAGS_TAG),
                    readIdSet(slotTag, EXPLICIT_CANDIDATES_TAG)
            ));
        }

        return List.copyOf(rules);
    }

    public @Nullable ProcessingPatternSlotReplacementRule restoreRule(
            ItemStack patternStack,
            int slotIndex,
            ItemStack sourceStack
    ) {
        if (patternStack == null || patternStack.isEmpty() || sourceStack == null || sourceStack.isEmpty()) {
            return null;
        }

        ResourceLocation currentSourceItemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem());
        if (currentSourceItemId == null) {
            return null;
        }

        return restoreRule(
                patternStack.getTag(),
                slotIndex,
                currentSourceItemId,
                tagService.extractSourceTagIds(sourceStack)
        );
    }

    public @Nullable ProcessingPatternSlotReplacementRule restoreRule(
            @Nullable CompoundTag patternTag,
            int slotIndex,
            @Nullable ResourceLocation currentSourceItemId,
            Collection<ResourceLocation> sourceTagIds
    ) {
        if (currentSourceItemId == null) {
            return null;
        }

        Set<ResourceLocation> sanitizedSourceTagIds = sourceTagIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(sourceTagIds));

        ProcessingPatternSlotReplacementRule matchingRule = findMatchingRule(patternTag, slotIndex, currentSourceItemId);
        if (matchingRule == null) {
            return null;
        }

        return new ProcessingPatternSlotReplacementRule(
                slotIndex,
                currentSourceItemId,
                validation.sanitizeSelectedTags(sanitizedSourceTagIds, matchingRule.selectedTagIds()),
                validation.validateExplicitCandidates(sanitizedSourceTagIds, matchingRule.explicitCandidateIds())
        );
    }

    public ProcessingSlotRuleStatus restoreRuleStatus(
            ItemStack patternStack,
            int slotIndex,
            ItemStack sourceStack
    ) {
        if (sourceStack == null || sourceStack.isEmpty()) {
            return new ProcessingSlotRuleStatus(slotIndex, UNCONFIGURED, null);
        }

        ResourceLocation currentSourceItemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem());
        if (currentSourceItemId == null) {
            return new ProcessingSlotRuleStatus(slotIndex, UNCONFIGURED, null);
        }

        return restoreRuleStatus(
                patternStack == null ? null : patternStack.getTag(),
                slotIndex,
                currentSourceItemId,
                tagService.extractSourceTagIds(sourceStack)
        );
    }

    public ProcessingSlotRuleStatus restoreRuleStatus(
            @Nullable CompoundTag patternTag,
            int slotIndex,
            @Nullable ResourceLocation currentSourceItemId,
            Collection<ResourceLocation> sourceTagIds
    ) {
        List<ResourceLocation> visibleSourceTagIds = sourceTagIds == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(sourceTagIds));
        if (currentSourceItemId == null) {
            return new ProcessingSlotRuleStatus(slotIndex, UNCONFIGURED, null);
        }

        ProcessingPatternSlotReplacementRule matchingRule = findMatchingRule(patternTag, slotIndex, currentSourceItemId);
        if (matchingRule == null) {
            return buildUnconfiguredStatus(slotIndex, visibleSourceTagIds);
        }

        Set<ResourceLocation> sanitizedSourceTagIds = new LinkedHashSet<>(visibleSourceTagIds);
        Set<ResourceLocation> sanitizedSelectedTagIds = validation.sanitizeSelectedTags(
                sanitizedSourceTagIds,
                matchingRule.selectedTagIds()
        );
        Set<ResourceLocation> sanitizedExplicitCandidateIds = validation.validateExplicitCandidates(
                sanitizedSourceTagIds,
                matchingRule.explicitCandidateIds()
        );
        ProcessingSlotRuleDraft visibleDraft = new ProcessingSlotRuleDraft(
                slotIndex,
                visibleSourceTagIds,
                sanitizedSelectedTagIds,
                sanitizedExplicitCandidateIds
        );
        if (!hasSelections(visibleDraft)) {
            return buildUnconfiguredStatus(slotIndex, visibleSourceTagIds);
        }

        ProcessingSlotRuleVisualState visualState =
                sanitizedSelectedTagIds.size() < matchingRule.selectedTagIds().size()
                        || sanitizedExplicitCandidateIds.size() < matchingRule.explicitCandidateIds().size()
                        ? PARTIALLY_INVALID
                        : CONFIGURED;
        return new ProcessingSlotRuleStatus(slotIndex, visualState, visibleDraft);
    }

    public @Nullable ProcessingSlotRuleDraft restoreRuleDraft(
            ItemStack patternStack,
            int slotIndex,
            ItemStack sourceStack
    ) {
        if (sourceStack == null || sourceStack.isEmpty()) {
            return null;
        }

        ResourceLocation currentSourceItemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem());
        if (currentSourceItemId == null) {
            return null;
        }

        return restoreRuleDraft(
                patternStack == null ? null : patternStack.getTag(),
                slotIndex,
                currentSourceItemId,
                tagService.extractSourceTagIds(sourceStack)
        );
    }

    public @Nullable ProcessingSlotRuleDraft restoreRuleDraft(
            @Nullable CompoundTag patternTag,
            int slotIndex,
            @Nullable ResourceLocation currentSourceItemId,
            Collection<ResourceLocation> sourceTagIds
    ) {
        ProcessingPatternSlotReplacementRule restoredRule = restoreRule(
                patternTag,
                slotIndex,
                currentSourceItemId,
                sourceTagIds
        );
        if (restoredRule == null) {
            return null;
        }

        return new ProcessingSlotRuleDraft(
                slotIndex,
                sourceTagIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(sourceTagIds)),
                restoredRule.selectedTagIds(),
                restoredRule.explicitCandidateIds()
        );
    }

    private @Nullable ProcessingPatternSlotReplacementRule findMatchingRule(
            @Nullable CompoundTag patternTag,
            int slotIndex,
            ResourceLocation currentSourceItemId
    ) {
        for (ProcessingPatternSlotReplacementRule rule : readRules(patternTag)) {
            if (rule.slotIndex() == slotIndex && currentSourceItemId.equals(rule.sourceItemId())) {
                return rule;
            }
        }
        return null;
    }

    private static ProcessingSlotRuleStatus buildUnconfiguredStatus(
            int slotIndex,
            List<ResourceLocation> visibleSourceTagIds
    ) {
        return new ProcessingSlotRuleStatus(
                slotIndex,
                UNCONFIGURED,
                new ProcessingSlotRuleDraft(slotIndex, visibleSourceTagIds, Set.of(), Set.of())
        );
    }

    private static boolean hasSelections(ProcessingSlotRuleDraft draft) {
        return !draft.selectedTagIds().isEmpty() || !draft.explicitCandidateIds().isEmpty();
    }

    private static ListTag writeIdList(Collection<ResourceLocation> ids) {
        ListTag values = new ListTag();
        if (ids == null) {
            return values;
        }

        for (ResourceLocation id : ids) {
            if (id != null) {
                values.add(StringTag.valueOf(id.toString()));
            }
        }
        return values;
    }

    private static Set<ResourceLocation> readIdSet(CompoundTag parentTag, String key) {
        if (!parentTag.contains(key, Tag.TAG_LIST)) {
            return Set.of();
        }

        ListTag values = parentTag.getList(key, Tag.TAG_STRING);
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (Tag value : values) {
            if (!(value instanceof StringTag stringTag)) {
                continue;
            }

            ResourceLocation id = ResourceLocation.tryParse(stringTag.getAsString());
            if (id != null) {
                ids.add(id);
            }
        }
        return Collections.unmodifiableSet(ids);
    }
}
