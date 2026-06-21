package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record ProcessingSlotRulePayload(
        int slotIndex,
        Set<ResourceLocation> selectedTagIds,
        Set<ResourceLocation> explicitCandidateIds
) {
}
