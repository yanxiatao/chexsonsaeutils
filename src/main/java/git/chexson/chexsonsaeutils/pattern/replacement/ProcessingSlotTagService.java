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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public class ProcessingSlotTagService {
    private final Function<ItemStack, List<ResourceLocation>> sourceTagExtractor;
    private final Function<ResourceLocation, List<ResourceLocation>> tagMemberResolver;
    private final Function<ResourceLocation, Set<ResourceLocation>> itemTagResolver;

    public ProcessingSlotTagService() {
        this(
                ProcessingSlotTagService::extractSourceTagIdsFromRegistry,
                ProcessingSlotTagService::resolveTagMembersFromRegistry,
                ProcessingSlotTagService::resolveItemTagsFromRegistry
        );
    }

    public ProcessingSlotTagService(
            Function<ItemStack, List<ResourceLocation>> sourceTagExtractor,
            Function<ResourceLocation, List<ResourceLocation>> tagMemberResolver,
            Function<ResourceLocation, Set<ResourceLocation>> itemTagResolver
    ) {
        this.sourceTagExtractor = Objects.requireNonNull(sourceTagExtractor);
        this.tagMemberResolver = Objects.requireNonNull(tagMemberResolver);
        this.itemTagResolver = Objects.requireNonNull(itemTagResolver);
    }

    public List<ResourceLocation> extractSourceTagIds(ItemStack sourceStack) {
        List<ResourceLocation> sourceTagIds = sourceTagExtractor.apply(sourceStack);
        if (sourceTagIds == null || sourceTagIds.isEmpty()) {
            return List.of();
        }

        return List.copyOf(new LinkedHashSet<>(sourceTagIds));
    }

    public List<ProcessingSlotCandidateGroup> buildCandidateGroups(ItemStack sourceStack) {
        return buildCandidateGroups(extractSourceTagIds(sourceStack));
    }

    public List<ProcessingSlotCandidateGroup> buildCandidateGroups(Collection<ResourceLocation> sourceTagIds) {
        List<ProcessingSlotCandidateGroup> candidateGroups = new ArrayList<>();
        if (sourceTagIds == null) {
            return List.of();
        }

        for (ResourceLocation sourceTagId : new LinkedHashSet<>(sourceTagIds)) {
            List<ResourceLocation> members = List.copyOf(new LinkedHashSet<>(tagMemberResolver.apply(sourceTagId)));
            candidateGroups.add(new ProcessingSlotCandidateGroup(sourceTagId, members));
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

        Set<ResourceLocation> candidateTagIds = itemTagResolver.apply(candidateItemId);
        return candidateTagIds != null && candidateTagIds.stream().anyMatch(sourceTagIds::contains);
    }

    private static List<ResourceLocation> extractSourceTagIdsFromRegistry(ItemStack sourceStack) {
        if (sourceStack == null || sourceStack.isEmpty()) {
            return List.of();
        }

        return sourceStack.getTags()
                .map(TagKey::location)
                .distinct()
                .toList();
    }

    private static List<ResourceLocation> resolveTagMembersFromRegistry(ResourceLocation tagId) {
        TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagId);
        return BuiltInRegistries.ITEM.getTag(tagKey)
                .stream()
                .flatMap(HolderSet.Named::stream)
                .map(Holder::value)
                .map(BuiltInRegistries.ITEM::getKey)
                .toList();
    }

    private static Set<ResourceLocation> resolveItemTagsFromRegistry(ResourceLocation itemId) {
        if (itemId == null) {
            return Set.of();
        }

        return BuiltInRegistries.ITEM.getOptional(itemId)
                .map(item -> {
                    Set<ResourceLocation> tagIds = new LinkedHashSet<>(new ItemStack(item).getTags()
                            .map(TagKey::location)
                            .toList());
                    return tagIds;
                })
                .orElseGet(Set::of);
    }
}
