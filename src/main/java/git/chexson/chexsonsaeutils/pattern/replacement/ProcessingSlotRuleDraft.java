package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ProcessingSlotRuleDraft(
        int slotIndex,
        List<ResourceLocation> sourceTagIds,
        Set<ResourceLocation> selectedTagIds,
        Set<ResourceLocation> explicitCandidateIds
) {
    public ProcessingSlotRuleDraft {
        sourceTagIds = List.copyOf(sourceTagIds);
        selectedTagIds = Collections.unmodifiableSet(new LinkedHashSet<>(selectedTagIds));
        explicitCandidateIds = Collections.unmodifiableSet(new LinkedHashSet<>(explicitCandidateIds));
    }
}
