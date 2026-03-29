package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ProcessingSlotRuleValidation {
    private final ProcessingSlotTagService tagService;

    public ProcessingSlotRuleValidation() {
        this(new ProcessingSlotTagService());
    }

    public ProcessingSlotRuleValidation(ProcessingSlotTagService tagService) {
        this.tagService = Objects.requireNonNull(tagService);
    }

    public Set<ResourceLocation> sanitizeSelectedTags(ItemStack sourceStack, Set<ResourceLocation> selectedTagIds) {
        return sanitizeSelectedTags(new LinkedHashSet<>(tagService.extractSourceTagIds(sourceStack)), selectedTagIds);
    }

    public Set<ResourceLocation> validateExplicitCandidates(ItemStack sourceStack, Set<ResourceLocation> explicitCandidateIds) {
        return validateExplicitCandidates(new LinkedHashSet<>(tagService.extractSourceTagIds(sourceStack)),
                explicitCandidateIds);
    }

    public Set<ResourceLocation> sanitizeSelectedTags(Set<ResourceLocation> sourceTagIds, Set<ResourceLocation> selectedTagIds) {
        Set<ResourceLocation> availableTags = sourceTagIds == null ? Set.of() : new LinkedHashSet<>(sourceTagIds);
        Set<ResourceLocation> sanitized = new LinkedHashSet<>();
        if (selectedTagIds == null || selectedTagIds.isEmpty() || availableTags.isEmpty()) {
            return Collections.unmodifiableSet(sanitized);
        }

        for (ResourceLocation selectedTagId : selectedTagIds) {
            if (availableTags.contains(selectedTagId)) {
                sanitized.add(selectedTagId);
            }
        }
        return Collections.unmodifiableSet(sanitized);
    }

    public Set<ResourceLocation> validateExplicitCandidates(
            Set<ResourceLocation> sourceTagIds,
            Set<ResourceLocation> explicitCandidateIds
    ) {
        Set<ResourceLocation> sanitized = new LinkedHashSet<>();
        if (explicitCandidateIds == null || explicitCandidateIds.isEmpty()) {
            return Collections.unmodifiableSet(sanitized);
        }

        for (ResourceLocation explicitCandidateId : explicitCandidateIds) {
            if (tagService.sharesAnySourceTag(sourceTagIds, explicitCandidateId)) {
                sanitized.add(explicitCandidateId);
            }
        }
        return Collections.unmodifiableSet(sanitized);
    }

    public @Nullable ProcessingSlotRuleDraft sanitizeDraft(
            @Nullable ProcessingSlotRuleDraft baseDraft,
            @Nullable ProcessingSlotRulePayload payload
    ) {
        if (baseDraft == null || payload == null || baseDraft.slotIndex() != payload.slotIndex()) {
            return null;
        }

        Set<ResourceLocation> sourceTagIds = new LinkedHashSet<>(baseDraft.sourceTagIds());
        List<ResourceLocation> orderedSourceTags = baseDraft.sourceTagIds();
        return new ProcessingSlotRuleDraft(
                baseDraft.slotIndex(),
                orderedSourceTags,
                sanitizeSelectedTags(sourceTagIds, payload.selectedTagIds()),
                validateExplicitCandidates(sourceTagIds, payload.explicitCandidateIds())
        );
    }

    public @Nullable ProcessingSlotRuleDraft saveDraft(
            Map<Integer, ProcessingSlotRuleDraft> draftStore,
            @Nullable ProcessingSlotRuleDraft baseDraft,
            @Nullable ProcessingSlotRulePayload payload
    ) {
        if (draftStore == null || payload == null) {
            return null;
        }

        ProcessingSlotRuleDraft sanitizedDraft = sanitizeDraft(baseDraft, payload);
        if (sanitizedDraft == null) {
            draftStore.remove(payload.slotIndex());
            return null;
        }

        draftStore.put(payload.slotIndex(), sanitizedDraft);
        return sanitizedDraft;
    }

    public void clearDraft(Map<Integer, ProcessingSlotRuleDraft> draftStore, int slotIndex) {
        if (draftStore == null) {
            return;
        }
        draftStore.remove(slotIndex);
    }
}
