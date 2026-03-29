package git.chexson.chexsonsaeutils.pattern.replacement;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public interface ProcessingSlotRuleHost {
    @Nullable
    ProcessingSlotRuleDraft buildProcessingSlotRuleDraft(int slotIndex);

    @Nullable
    ProcessingSlotRuleDraft getProcessingSlotRuleDraft(int slotIndex);

    ProcessingSlotRuleStatus getProcessingSlotRuleStatus(int slotIndex);

    ItemStack getProcessingSlotRuleSourceStack(int slotIndex);

    void requestSaveProcessingSlotRuleDraft(ProcessingSlotRulePayload payload);

    void requestClearProcessingSlotRuleDraft(int slotIndex);
}
