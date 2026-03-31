package git.chexson.chexsonsaeutils.pattern.replacement;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public class ReplacementAwareProcessingPattern extends AEProcessingPattern {
    private final Map<Integer, ProcessingPatternSlotReplacementRule> replacementRules;
    private final ProcessingSlotTagService tagService;
    private final ProcessingSlotRuleValidation validation;
    private final Function<ResourceLocation, AEItemKey> itemKeyResolver;
    private final int[] nonEmptySparseSlots;
    private final IPatternDetails.IInput[] replacementAwareInputs;

    public ReplacementAwareProcessingPattern(
            AEItemKey definition,
            Collection<ProcessingPatternSlotReplacementRule> replacementRules,
            ProcessingSlotTagService tagService,
            ProcessingSlotRuleValidation validation
    ) {
        this(
                definition,
                replacementRules,
                tagService,
                validation,
                ReplacementAwareProcessingPattern::resolveItemKey
        );
    }

    ReplacementAwareProcessingPattern(
            AEItemKey definition,
            Collection<ProcessingPatternSlotReplacementRule> replacementRules,
            ProcessingSlotTagService tagService,
            ProcessingSlotRuleValidation validation,
            Function<ResourceLocation, AEItemKey> itemKeyResolver
    ) {
        super(definition);
        this.tagService = Objects.requireNonNull(tagService);
        this.validation = Objects.requireNonNull(validation);
        this.itemKeyResolver = Objects.requireNonNull(itemKeyResolver);
        this.replacementRules = indexRules(replacementRules);
        this.nonEmptySparseSlots = collectNonEmptySparseSlots(getSparseInputs());
        this.replacementAwareInputs = buildInputs();
    }

    @Override
    public IPatternDetails.IInput[] getInputs() {
        return replacementAwareInputs.clone();
    }

    @Override
    public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink sink) {
        KeyCounter availableInputs = new KeyCounter();
        if (inputHolder != null) {
            for (KeyCounter counter : inputHolder) {
                if (counter != null) {
                    availableInputs.addAll(counter);
                }
            }
        }

        List<GenericStack> sparseInputs = getSparseInputs();
        for (int inputIndex = 0; inputIndex < nonEmptySparseSlots.length; inputIndex++) {
            int sparseSlot = nonEmptySparseSlots[inputIndex];
            GenericStack sparseInput = sparseInputs.get(sparseSlot);
            if (sparseInput == null) {
                continue;
            }

            long requiredAmount = sparseInput.amount();
            pushInputToSink(replacementAwareInputs[inputIndex], availableInputs, requiredAmount, sink, sparseSlot);
        }
    }

    private static void pushInputToSink(
            IPatternDetails.IInput input,
            KeyCounter availableInputs,
            long requiredAmount,
            PatternInputSink sink,
            int sparseSlot
    ) {
        AEKey selectedKey = findAvailableCandidate(input, availableInputs, requiredAmount);
        if (selectedKey == null) {
            throw new RuntimeException(
                    "Expected a valid input for processing slot " + sparseSlot + ", but none was available"
            );
        }

        sink.pushInput(selectedKey, requiredAmount);
        availableInputs.remove(selectedKey, requiredAmount);
    }

    private static AEKey findAvailableCandidate(
            IPatternDetails.IInput input,
            KeyCounter availableInputs,
            long requiredAmount
    ) {
        for (GenericStack possibleInput : input.getPossibleInputs()) {
            AEKey key = possibleInput.what();
            if (availableInputs.get(key) >= requiredAmount) {
                return key;
            }
        }
        return null;
    }

    private IPatternDetails.IInput[] buildInputs() {
        List<IPatternDetails.IInput> inputs = new ArrayList<>();
        List<GenericStack> sparseInputs = getSparseInputs();
        for (int sparseSlot : nonEmptySparseSlots) {
            inputs.add(buildInputForSlot(sparseSlot, sparseInputs.get(sparseSlot)));
        }
        return inputs.toArray(IPatternDetails.IInput[]::new);
    }

    private IPatternDetails.IInput buildInputForSlot(int slotIndex, GenericStack sparseInput) {
        if (!(sparseInput.what() instanceof AEItemKey itemKey)) {
            return new ReplacementAwareInput(List.of(new GenericStack(sparseInput.what(), 1L)), sparseInput.amount());
        }

        ItemStack sourceStack = itemKey.toStack(1);
        ResourceLocation sourceItemId = itemKey.getId();
        LinkedHashMap<ResourceLocation, GenericStack> possibleInputs = new LinkedHashMap<>();
        for (ResourceLocation itemId : buildPossibleItemIds(
                sourceItemId,
                tagService.extractSourceTagIds(sourceStack),
                replacementRules.get(slotIndex),
                tagService,
                validation
        )) {
            if (sourceItemId.equals(itemId)) {
                possibleInputs.put(itemId, new GenericStack(itemKey, 1L));
                continue;
            }
            addCandidate(possibleInputs, itemId);
        }

        return new ReplacementAwareInput(possibleInputs.values(), sparseInput.amount());
    }

    private void addCandidate(Map<ResourceLocation, GenericStack> possibleInputs, ResourceLocation itemId) {
        if (possibleInputs.containsKey(itemId)) {
            return;
        }

        AEItemKey candidateKey = itemKeyResolver.apply(itemId);
        if (candidateKey != null) {
            possibleInputs.put(itemId, new GenericStack(candidateKey, 1L));
        }
    }

    private static Map<Integer, ProcessingPatternSlotReplacementRule> indexRules(
            Collection<ProcessingPatternSlotReplacementRule> replacementRules
    ) {
        Map<Integer, ProcessingPatternSlotReplacementRule> indexedRules = new LinkedHashMap<>();
        if (replacementRules == null) {
            return Map.copyOf(indexedRules);
        }

        for (ProcessingPatternSlotReplacementRule replacementRule : replacementRules) {
            if (replacementRule != null && replacementRule.hasSelections()) {
                indexedRules.put(replacementRule.slotIndex(), replacementRule);
            }
        }
        return Map.copyOf(indexedRules);
    }

    private static int[] collectNonEmptySparseSlots(List<GenericStack> sparseInputs) {
        List<Integer> nonEmptySlots = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < sparseInputs.size(); slotIndex++) {
            if (sparseInputs.get(slotIndex) != null) {
                nonEmptySlots.add(slotIndex);
            }
        }
        return nonEmptySlots.stream().mapToInt(Integer::intValue).toArray();
    }

    public static List<ResourceLocation> buildPossibleItemIds(
            ResourceLocation originalItemId,
            Collection<ResourceLocation> sourceTagIds,
            @Nullable ProcessingPatternSlotReplacementRule rule,
            ProcessingSlotTagService tagService,
            ProcessingSlotRuleValidation validation
    ) {
        LinkedHashMap<ResourceLocation, ResourceLocation> possibleItems = new LinkedHashMap<>();
        possibleItems.put(originalItemId, originalItemId);

        if (originalItemId == null || rule == null || !originalItemId.equals(rule.sourceItemId())) {
            return List.copyOf(possibleItems.keySet());
        }

        Set<ResourceLocation> sanitizedSourceTags = sourceTagIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(sourceTagIds));
        Set<ResourceLocation> selectedTagIds = validation.sanitizeSelectedTags(sanitizedSourceTags, rule.selectedTagIds());
        Set<ResourceLocation> explicitCandidateIds =
                validation.validateExplicitCandidates(sanitizedSourceTags, rule.explicitCandidateIds());

        for (ProcessingSlotCandidateGroup candidateGroup : tagService.buildCandidateGroups(sanitizedSourceTags)) {
            if (!selectedTagIds.contains(candidateGroup.tagId())) {
                continue;
            }
            for (ResourceLocation itemId : candidateGroup.itemIds()) {
                possibleItems.putIfAbsent(itemId, itemId);
            }
        }

        for (ResourceLocation explicitCandidateId : explicitCandidateIds) {
            possibleItems.putIfAbsent(explicitCandidateId, explicitCandidateId);
        }

        return List.copyOf(possibleItems.keySet());
    }

    private static @Nullable AEItemKey resolveItemKey(ResourceLocation itemId) {
        if (itemId == null) {
            return null;
        }

        return BuiltInRegistries.ITEM.getOptional(itemId)
                .map(AEItemKey::of)
                .orElse(null);
    }

    public static boolean matchesAnyPossibleInput(@Nullable IPatternDetails.IInput input, @Nullable AEKey key) {
        if (input == null || key == null) {
            return false;
        }

        for (GenericStack possibleInput : input.getPossibleInputs()) {
            if (key.matches(possibleInput)) {
                return true;
            }
        }
        return false;
    }

    private static final class ReplacementAwareInput implements IPatternDetails.IInput {
        private final GenericStack[] possibleInputs;
        private final long multiplier;

        private ReplacementAwareInput(Collection<GenericStack> possibleInputs, long multiplier) {
            this.possibleInputs = possibleInputs.stream().toArray(GenericStack[]::new);
            this.multiplier = multiplier;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (GenericStack possibleInput : possibleInputs) {
                if (input.matches(possibleInput)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
