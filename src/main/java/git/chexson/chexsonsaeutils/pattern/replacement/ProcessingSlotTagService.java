package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ProcessingSlotTagService {

    public List<ResourceLocation> extractSourceTagIds(ItemStack sourceStack) {
        if (sourceStack == null || sourceStack.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> sourceTagIds = sourceStack.getTags().map(TagKey::location).distinct().toList();
        if (sourceTagIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(sourceTagIds));
    }

    public List<ProcessingSlotCandidateGroup> buildCandidateGroups(ItemStack sourceStack) {
        return buildCandidateGroups(extractSourceTagIds(sourceStack));
    }

    public List<ProcessingSlotCandidateGroup> buildCandidateGroups(Collection<ResourceLocation> sourceTagIds) {
        if (sourceTagIds == null) {
            return List.of();
        }
        List<ProcessingSlotCandidateGroup> candidateGroups = new ArrayList<>();
        for (ResourceLocation sourceTagId : new LinkedHashSet<>(sourceTagIds)) {
            TagKey<Item> tagKey = TagKey.create(Registries.ITEM, sourceTagId);
            List<ResourceLocation> members = BuiltInRegistries.ITEM.getTag(tagKey)
                    .stream()
                    .flatMap(HolderSet.Named::stream)
                    .map(Holder::value)
                    .map(BuiltInRegistries.ITEM::getKey)
                    .toList();
            candidateGroups.add(new ProcessingSlotCandidateGroup(sourceTagId, List.copyOf(new LinkedHashSet<>(members))));
        }
        return List.copyOf(candidateGroups);
    }

    public boolean isSelectableCandidate(ItemStack sourceStack, ResourceLocation candidateItemId) {
        return sharesAnySourceTag(new LinkedHashSet<>(extractSourceTagIds(sourceStack)), candidateItemId);
    }

    public boolean sharesAnySourceTag(Set<ResourceLocation> sourceTagIds, ResourceLocation candidateItemId) {
        if (candidateItemId == null || sourceTagIds == null || sourceTagIds.isEmpty()) {
            return false;
        }
        Set<ResourceLocation> candidateTagIds = BuiltInRegistries.ITEM.getOptional(candidateItemId)
                .map(item -> (Set<ResourceLocation>) new LinkedHashSet<>(new ItemStack(item).getTags().map(TagKey::location).toList()))
                .orElse(Set.of());
        return !candidateTagIds.isEmpty() && candidateTagIds.stream().anyMatch(sourceTagIds::contains);
    }
}
