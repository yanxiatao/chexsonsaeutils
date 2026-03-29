package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ITerminalHost;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.slot.FakeSlot;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.EncodingMode;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementPersistence;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternSlotReplacementRule;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleDraft;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleHost;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRulePayload;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleStatus;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleValidation;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleVisualState;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(value = PatternEncodingTermMenu.class, remap = false)
public abstract class PatternEncodingTermMenuRuleMixin extends MEStorageMenu implements ProcessingSlotRuleHost {
    @Shadow
    @Final
    private RestrictedInputSlot encodedPatternSlot;

    @Shadow(remap = false)
    @Final
    private FakeSlot[] processingInputSlots;

    @Unique
    private final Map<Integer, ProcessingSlotRuleDraft> chexsonsaeutils$processingSlotRuleDrafts = new HashMap<>();
    @Unique
    private final ProcessingSlotTagService chexsonsaeutils$processingSlotTagService = new ProcessingSlotTagService();
    @Unique
    private final ProcessingSlotRuleValidation chexsonsaeutils$processingSlotRuleValidation =
            new ProcessingSlotRuleValidation(chexsonsaeutils$processingSlotTagService);
    @Unique
    private final ProcessingPatternReplacementPersistence chexsonsaeutils$processingPatternReplacementPersistence =
            new ProcessingPatternReplacementPersistence(
                    chexsonsaeutils$processingSlotTagService,
                    chexsonsaeutils$processingSlotRuleValidation
            );

    protected PatternEncodingTermMenuRuleMixin(
            MenuType<?> menuType,
            int id,
            Inventory playerInventory,
            ITerminalHost host,
            boolean bindInventory
    ) {
        super(menuType, id, playerInventory, host, bindInventory);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lappeng/helpers/IPatternTerminalMenuHost;Z)V", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$initProcessingSlotRuleActions(
            MenuType<?> menuType,
            int id,
            Inventory playerInventory,
            IPatternTerminalMenuHost host,
            boolean bindInventory,
            CallbackInfo ci
    ) {
        registerClientAction("chexsonSaveProcessingSlotRuleDraft", ProcessingSlotRulePayload.class,
                this::chexsonsaeutils$applySaveProcessingSlotRuleDraft);
        registerClientAction("chexsonClearProcessingSlotRuleDraft", Integer.class,
                this::chexsonsaeutils$applyClearProcessingSlotRuleDraft);
    }

    @Override
    public @Nullable ProcessingSlotRuleDraft buildProcessingSlotRuleDraft(int slotIndex) {
        return getProcessingSlotRuleStatus(slotIndex).visibleDraft();
    }

    @Override
    public @Nullable ProcessingSlotRuleDraft getProcessingSlotRuleDraft(int slotIndex) {
        return chexsonsaeutils$processingSlotRuleDrafts.get(slotIndex);
    }

    @Override
    public ProcessingSlotRuleStatus getProcessingSlotRuleStatus(int slotIndex) {
        ProcessingSlotRuleDraft existingDraft = chexsonsaeutils$processingSlotRuleDrafts.get(slotIndex);
        if (existingDraft != null) {
            return chexsonsaeutils$buildProcessingSlotRuleStatus(slotIndex, existingDraft);
        }

        return chexsonsaeutils$restoreEncodedPatternRuleStatus(slotIndex);
    }

    @Override
    public ItemStack getProcessingSlotRuleSourceStack(int slotIndex) {
        ItemStack sourceStack = chexsonsaeutils$getSourceStack(slotIndex);
        return sourceStack == null ? ItemStack.EMPTY : sourceStack.copy();
    }

    @Override
    public void requestSaveProcessingSlotRuleDraft(ProcessingSlotRulePayload payload) {
        if (payload == null) {
            return;
        }
        if (isClientSide()) {
            chexsonsaeutils$applySaveProcessingSlotRuleDraft(payload);
            sendClientAction("chexsonSaveProcessingSlotRuleDraft", payload);
            return;
        }
        chexsonsaeutils$applySaveProcessingSlotRuleDraft(payload);
    }

    @Override
    public void requestClearProcessingSlotRuleDraft(int slotIndex) {
        if (isClientSide()) {
            chexsonsaeutils$applyClearProcessingSlotRuleDraft(slotIndex);
            sendClientAction("chexsonClearProcessingSlotRuleDraft", slotIndex);
            return;
        }
        chexsonsaeutils$applyClearProcessingSlotRuleDraft(slotIndex);
    }

    @Unique
    private void chexsonsaeutils$applySaveProcessingSlotRuleDraft(ProcessingSlotRulePayload payload) {
        if (payload == null) {
            return;
        }

        ProcessingSlotRuleDraft baseDraft = chexsonsaeutils$createDraft(payload.slotIndex(), Set.of(), Set.of());
        chexsonsaeutils$processingSlotRuleValidation.saveDraft(
                chexsonsaeutils$processingSlotRuleDrafts,
                baseDraft,
                payload
        );
    }

    @Unique
    private void chexsonsaeutils$applyClearProcessingSlotRuleDraft(Integer slotIndex) {
        if (slotIndex == null) {
            return;
        }
        chexsonsaeutils$processingSlotRuleValidation.clearDraft(chexsonsaeutils$processingSlotRuleDrafts, slotIndex);
    }

    @Inject(method = "encodeProcessingPattern", at = @At("RETURN"), cancellable = true, remap = false)
    private void chexsonsaeutils$encodeProcessingPattern(CallbackInfoReturnable<ItemStack> cir) {
        ItemStack encodedPattern = cir.getReturnValue();
        if (encodedPattern == null || encodedPattern.isEmpty()
                || ((PatternEncodingTermMenu) (Object) this).getMode() != EncodingMode.PROCESSING) {
            return;
        }

        // Persist slot-bound replacements into the encoded child tag: chexsonsaeutils_processing_replacements.
        chexsonsaeutils$processingPatternReplacementPersistence.writeRules(
                encodedPattern,
                chexsonsaeutils$processingSlotRuleDrafts.entrySet().stream()
                        .sorted(Comparator.comparingInt(Map.Entry::getKey))
                        .map(entry -> chexsonsaeutils$toReplacementRule(entry.getKey(), entry.getValue()))
                        .filter(rule -> rule != null && rule.hasSelections())
                        .toList()
        );
        cir.setReturnValue(encodedPattern);
    }

    @Unique
    private @Nullable ProcessingSlotRuleDraft chexsonsaeutils$createDraft(
            int slotIndex,
            Set<ResourceLocation> selectedTagIds,
            Set<ResourceLocation> explicitCandidateIds
    ) {
        ItemStack sourceStack = chexsonsaeutils$getSourceStack(slotIndex);
        if (((PatternEncodingTermMenu) (Object) this).getMode() != EncodingMode.PROCESSING || sourceStack == null) {
            return null;
        }

        List<ResourceLocation> sourceTagIds = chexsonsaeutils$processingSlotTagService.extractSourceTagIds(sourceStack);
        return new ProcessingSlotRuleDraft(slotIndex, sourceTagIds, selectedTagIds, explicitCandidateIds);
    }

    @Unique
    private @Nullable ProcessingSlotRuleDraft chexsonsaeutils$restoreEncodedPatternDraft(int slotIndex) {
        if (this.encodedPatternSlot == null) {
            return null;
        }

        ItemStack encodedPattern = this.encodedPatternSlot.getItem();
        ItemStack sourceStack = chexsonsaeutils$getSourceStack(slotIndex);
        if (encodedPattern == null || encodedPattern.isEmpty() || sourceStack == null || sourceStack.isEmpty()) {
            return null;
        }

        return chexsonsaeutils$processingPatternReplacementPersistence.restoreRuleDraft(
                encodedPattern,
                slotIndex,
                sourceStack
        );
    }

    @Unique
    private ProcessingSlotRuleStatus chexsonsaeutils$restoreEncodedPatternRuleStatus(int slotIndex) {
        ItemStack sourceStack = chexsonsaeutils$getSourceStack(slotIndex);
        if (sourceStack == null || sourceStack.isEmpty()) {
            return new ProcessingSlotRuleStatus(slotIndex, ProcessingSlotRuleVisualState.UNCONFIGURED, null);
        }
        if (this.encodedPatternSlot == null) {
            return chexsonsaeutils$buildProcessingSlotRuleStatus(
                    slotIndex,
                    chexsonsaeutils$createDraft(slotIndex, Set.of(), Set.of())
            );
        }

        ItemStack encodedPattern = this.encodedPatternSlot.getItem();
        if (encodedPattern == null || encodedPattern.isEmpty()) {
            return chexsonsaeutils$buildProcessingSlotRuleStatus(
                    slotIndex,
                    chexsonsaeutils$createDraft(slotIndex, Set.of(), Set.of())
            );
        }

        return chexsonsaeutils$processingPatternReplacementPersistence.restoreRuleStatus(
                encodedPattern,
                slotIndex,
                sourceStack
        );
    }

    @Unique
    private @Nullable ItemStack chexsonsaeutils$getSourceStack(int slotIndex) {
        if (((PatternEncodingTermMenu) (Object) this).getMode() != EncodingMode.PROCESSING
                || slotIndex < 0
                || slotIndex >= this.processingInputSlots.length) {
            return null;
        }

        Slot slot = this.processingInputSlots[slotIndex];
        if (slot == null || getSlotSemantic(slot) != SlotSemantics.PROCESSING_INPUTS) {
            return null;
        }

        GenericStack currentStack = GenericStack.fromItemStack(slot.getItem());
        if (currentStack == null || !(currentStack.what() instanceof AEItemKey itemKey)) {
            return null;
        }

        return itemKey.toStack(1);
    }

    @Unique
    private ProcessingSlotRuleStatus chexsonsaeutils$buildProcessingSlotRuleStatus(
            int slotIndex,
            @Nullable ProcessingSlotRuleDraft visibleDraft
    ) {
        if (visibleDraft == null) {
            return new ProcessingSlotRuleStatus(slotIndex, ProcessingSlotRuleVisualState.UNCONFIGURED, null);
        }

        ProcessingSlotRuleVisualState visualState =
                visibleDraft.selectedTagIds().isEmpty() && visibleDraft.explicitCandidateIds().isEmpty()
                        ? ProcessingSlotRuleVisualState.UNCONFIGURED
                        : ProcessingSlotRuleVisualState.CONFIGURED;
        return new ProcessingSlotRuleStatus(slotIndex, visualState, visibleDraft);
    }

    @Unique
    private @Nullable ProcessingPatternSlotReplacementRule chexsonsaeutils$toReplacementRule(
            int slotIndex,
            @Nullable ProcessingSlotRuleDraft draft
    ) {
        if (draft == null || (draft.selectedTagIds().isEmpty() && draft.explicitCandidateIds().isEmpty())) {
            return null;
        }

        ItemStack sourceStack = chexsonsaeutils$getSourceStack(slotIndex);
        if (sourceStack == null || sourceStack.isEmpty()) {
            return null;
        }

        ResourceLocation sourceItemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem());
        if (sourceItemId == null) {
            return null;
        }

        return new ProcessingPatternSlotReplacementRule(
                slotIndex,
                sourceItemId,
                draft.selectedTagIds(),
                draft.explicitCandidateIds()
        );
    }
}
