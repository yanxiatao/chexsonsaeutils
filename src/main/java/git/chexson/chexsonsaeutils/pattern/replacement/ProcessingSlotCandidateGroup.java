package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ProcessingSlotCandidateGroup(
        ResourceLocation tagId,
        List<ResourceLocation> itemIds
) {
    public ProcessingSlotCandidateGroup {
        itemIds = List.copyOf(itemIds);
    }
}
