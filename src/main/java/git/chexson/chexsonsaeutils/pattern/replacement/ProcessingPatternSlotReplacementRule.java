package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record ProcessingPatternSlotReplacementRule(
        int slotIndex,
        ResourceLocation sourceItemId,
        Set<ResourceLocation> selectedTagIds,
        Set<ResourceLocation> explicitCandidateIds
) {
    public ProcessingPatternSlotReplacementRule {
        selectedTagIds = Collections.unmodifiableSet(new LinkedHashSet<>(selectedTagIds));
        explicitCandidateIds = Collections.unmodifiableSet(new LinkedHashSet<>(explicitCandidateIds));
    }

    public boolean hasSelections() {
        return !selectedTagIds.isEmpty() || !explicitCandidateIds.isEmpty();
    }
}
