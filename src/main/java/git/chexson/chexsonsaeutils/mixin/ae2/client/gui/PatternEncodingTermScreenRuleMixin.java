package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.FakeSlot;
import appeng.parts.encoding.EncodingMode;
import com.mojang.blaze3d.platform.InputConstants;
import git.chexson.chexsonsaeutils.client.gui.implementations.ProcessingPatternReplacementScreen;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleHost;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleStatus;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleVisualState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = PatternEncodingTermScreen.class, remap = false)
public abstract class PatternEncodingTermScreenRuleMixin extends MEStorageScreen<PatternEncodingTermMenu> {
    @Unique
    private static final int chexsonsaeutils$BADGE_SIZE = 8;
    @Unique
    private static final int chexsonsaeutils$BADGE_INSET = 4;
    @Unique
    private static final int chexsonsaeutils$CONFIGURED_BADGE_COLOR = 0xFF3D8C56;
    @Unique
    private static final int chexsonsaeutils$PARTIALLY_INVALID_BADGE_COLOR = 0xFFDDD28C;
    @Unique
    private static final String chexsonsaeutils$STATUS_NOT_CONFIGURED =
            "gui.chexsonsaeutils.processing_pattern_rule.status.not_configured";
    @Unique
    private static final String chexsonsaeutils$STATUS_CONFIGURED =
            "gui.chexsonsaeutils.processing_pattern_rule.status.configured";
    @Unique
    private static final String chexsonsaeutils$STATUS_PARTIALLY_INVALID =
            "gui.chexsonsaeutils.processing_pattern_rule.status.partially_invalid";
    @Unique
    private @Nullable Slot chexsonsaeutils$tooltipSlot;

    protected PatternEncodingTermScreenRuleMixin(
            PatternEncodingTermMenu menu,
            Inventory playerInventory,
            Component title,
            ScreenStyle style
    ) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$openReplacementRuleScreen(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Slot slot = chexsonsaeutils$findActiveSlot(mouseX, mouseY);
        Integer processingInputIndex = chexsonsaeutils$getProcessingInputIndex(slot);
        if (!(Screen.hasControlDown()
                && button == InputConstants.MOUSE_BUTTON_LEFT
                && slot != null
                && processingInputIndex != null
                && menu.getMode() == EncodingMode.PROCESSING
                && menu.getSlotSemantic(slot) == SlotSemantics.PROCESSING_INPUTS
                && chexsonsaeutils$isSupportedProcessingInput(slot))) {
            return;
        }

        switchToScreen(new ProcessingPatternReplacementScreen<>(chexsonsaeutils$self(), processingInputIndex));
        cir.setReturnValue(true);
    }

    @Inject(method = "renderSlot", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$renderProcessingSlotStatusBadge(
            GuiGraphics guiGraphics,
            Slot slot,
            CallbackInfo ci
    ) {
        ProcessingSlotRuleStatus status = chexsonsaeutils$getProcessingSlotRuleStatus(slot);
        if (status == null) {
            return;
        }

        int badgeColor = chexsonsaeutils$getBadgeColor(status.visualState());
        if (badgeColor == 0) {
            return;
        }

        int badgeLeft = leftPos + slot.x + 6;
        int badgeTop = topPos + slot.y + 4;
        guiGraphics.fill(
                badgeLeft,
                badgeTop,
                badgeLeft + chexsonsaeutils$BADGE_SIZE,
                badgeTop + chexsonsaeutils$BADGE_SIZE,
                badgeColor
        );
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$captureTooltipSlot(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        chexsonsaeutils$tooltipSlot = chexsonsaeutils$findActiveSlot(mouseX, mouseY);
    }

    @Inject(method = "renderTooltip", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$clearTooltipSlot(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        chexsonsaeutils$tooltipSlot = null;
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true, remap = false)
    private void chexsonsaeutils$appendProcessingSlotStatusTooltip(
            ItemStack stack,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        ProcessingSlotRuleStatus status = chexsonsaeutils$getProcessingSlotRuleStatus(chexsonsaeutils$tooltipSlot);
        if (status == null) {
            return;
        }

        List<Component> tooltipLines = new ArrayList<>(cir.getReturnValue());
        tooltipLines.addAll(chexsonsaeutils$getStatusTooltipLines(status.visualState()));
        cir.setReturnValue(List.copyOf(tooltipLines));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private PatternEncodingTermScreen<PatternEncodingTermMenu> chexsonsaeutils$self() {
        return (PatternEncodingTermScreen<PatternEncodingTermMenu>) (Object) this;
    }

    @Unique
    private boolean chexsonsaeutils$isSupportedProcessingInput(Slot slot) {
        GenericStack currentStack = GenericStack.fromItemStack(slot.getItem());
        return currentStack != null && currentStack.what() instanceof AEItemKey;
    }

    @Unique
    private @Nullable Slot chexsonsaeutils$findActiveSlot(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (slot.isActive() && isHovering(slot, mouseX, mouseY)) {
                return slot;
            }
        }
        return null;
    }

    @Unique
    private @Nullable Integer chexsonsaeutils$getProcessingInputIndex(@Nullable Slot slot) {
        if (slot == null) {
            return null;
        }

        FakeSlot[] processingInputSlots = menu.getProcessingInputSlots();
        for (int index = 0; index < processingInputSlots.length; index++) {
            if (processingInputSlots[index] == slot) {
                return index;
            }
        }
        return null;
    }

    @Unique
    private @Nullable ProcessingSlotRuleStatus chexsonsaeutils$getProcessingSlotRuleStatus(@Nullable Slot slot) {
        Integer processingInputIndex = chexsonsaeutils$getProcessingInputIndex(slot);
        if (processingInputIndex == null
                || slot == null
                || menu.getMode() != EncodingMode.PROCESSING
                || menu.getSlotSemantic(slot) != SlotSemantics.PROCESSING_INPUTS
                || !chexsonsaeutils$isSupportedProcessingInput(slot)) {
            return null;
        }

        return ((ProcessingSlotRuleHost) menu).getProcessingSlotRuleStatus(processingInputIndex);
    }

    @Unique
    private int chexsonsaeutils$getBadgeColor(ProcessingSlotRuleVisualState visualState) {
        if (visualState == ProcessingSlotRuleVisualState.CONFIGURED) {
            return chexsonsaeutils$CONFIGURED_BADGE_COLOR;
        }
        if (visualState == ProcessingSlotRuleVisualState.PARTIALLY_INVALID) {
            return chexsonsaeutils$PARTIALLY_INVALID_BADGE_COLOR;
        }
        return 0;
    }

    @Unique
    private List<Component> chexsonsaeutils$getStatusTooltipLines(ProcessingSlotRuleVisualState visualState) {
        Component statusLine = switch (visualState) {
            case UNCONFIGURED -> Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.status.not_configured");
            case CONFIGURED -> Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.status.configured");
            case PARTIALLY_INVALID -> Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.status.partially_invalid");
        };
        return List.of(statusLine);
    }
}
