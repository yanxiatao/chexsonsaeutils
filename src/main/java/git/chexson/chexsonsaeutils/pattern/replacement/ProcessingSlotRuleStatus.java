package git.chexson.chexsonsaeutils.pattern.replacement;

import org.jetbrains.annotations.Nullable;

public record ProcessingSlotRuleStatus(
        int slotIndex,
        ProcessingSlotRuleVisualState visualState,
        @Nullable ProcessingSlotRuleDraft visibleDraft
) {
}
