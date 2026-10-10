package git.chexson.chexsonsaeutils.client.gui.implementations;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.StyleManager;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import git.chexson.chexsonsaeutils.menu.implementations.HighCapacityCraftingMachineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class HighCapacityCraftingMachineScreen extends AEBaseScreen<HighCapacityCraftingMachineMenu> {

    private static final int PANEL_BORDER = 0xFF6F6F6F;
    private static final int PANEL_FILL = 0xFFE3E3E3;
    private static final int SLOT_BORDER = 0xFF7A7A7A;
    private static final int SLOT_FILL = 0xFFCFCFCF;
    private static final int HIGHLIGHT_BORDER = 0xFFFFD44A;
    private static final int PRIMARY_HIGHLIGHT_BORDER = 0xFFFFF18A;

    private Button previousPageButton;
    private Button nextPageButton;
    private Button clearSearchButton;
    private EditBox searchBox;

    public HighCapacityCraftingMachineScreen(
            HighCapacityCraftingMachineMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/high_capacity_crafting_machine.json"));
    }

    @Override
    protected void init() {
        super.init();
        previousPageButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> menu.previousPage())
                .bounds(leftPos + 148, topPos + 52, 14, 16)
                .build());
        nextPageButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> menu.nextPage())
                .bounds(leftPos + 166, topPos + 52, 14, 16)
                .build());
        clearSearchButton = addRenderableWidget(Button.builder(Component.literal("x"), button -> {
            searchBox.setValue("");
            menu.clearSearch();
        }).bounds(leftPos + 148, topPos + 37, 14, 14).build());
        searchBox = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 38, 136, 16, Component.literal("search")));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("gui.chexsonsaeutils.high_capacity_crafting_machine.search_hint"));
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        previousPageButton.active = menu.canGoToPreviousPage();
        nextPageButton.active = menu.canGoToNextPage();
        clearSearchButton.active = !searchBox.getValue().isEmpty();
        if (!searchBox.isFocused() && !searchBox.getValue().equals(menu.lastSearchQuery)) {
            searchBox.setValue(menu.lastSearchQuery);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox.isFocused() && (keyCode == 257 || keyCode == 335)) {
            menu.searchPatterns(searchBox.getValue());
            return true;
        }
        if (searchBox.keyPressed(keyCode, scanCode, modifiers) || searchBox.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox.isFocused() && searchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        guiGraphics.drawString(
                font,
                Component.translatable(
                        "gui.chexsonsaeutils.high_capacity_crafting_machine.page_status",
                        menu.pageIndex + 1,
                        menu.pageCount
                ),
                8,
                19,
                0x404040,
                false
        );
        guiGraphics.drawString(
                font,
                Component.translatable(
                        "gui.chexsonsaeutils.high_capacity_crafting_machine.slot_range",
                        menu.pageIndex * menu.getHost().getVisiblePatternSlots() + 1,
                        Math.min(menu.totalPatternSlots, (menu.pageIndex + 1) * menu.getHost().getVisiblePatternSlots()),
                        menu.totalPatternSlots
                ),
                8,
                29,
                0x404040,
                false
        );
        var slots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        for (int pageSlot = 0; pageSlot < slots.size(); pageSlot++) {
            if ((menu.highlightedPageSlotMask & (1 << pageSlot)) != 0) {
                drawSlotHighlightBorder(guiGraphics, slots.get(pageSlot), HIGHLIGHT_BORDER, 1);
            }
        }
        int primaryHighlightedSlot = resolvedHighlightedPageSlot();
        if (primaryHighlightedSlot >= 0 && primaryHighlightedSlot < slots.size()) {
            drawSlotHighlightBorder(guiGraphics, slots.get(primaryHighlightedSlot), PRIMARY_HIGHLIGHT_BORDER, 2);
        }
        if (menu.highlightedPageSlotMask == 0) {
            int fallbackHighlightedSlot = fallbackHighlightedPageSlot();
            if (fallbackHighlightedSlot >= 0 && fallbackHighlightedSlot < slots.size()) {
                drawSlotHighlightBorder(guiGraphics, slots.get(fallbackHighlightedSlot), PRIMARY_HIGHLIGHT_BORDER, 2);
            }
        }
    }

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        drawPanel(guiGraphics, offsetX + 4, offsetY + 4, 192, 66);
        // drawPanel(guiGraphics, offsetX + 4, offsetY + 78, 166, 64);
        // drawPanel(guiGraphics, offsetX + 173, offsetY + 80, 25, 94);
        // drawPanel(guiGraphics, offsetX + 4, offsetY + 160, 166, 60);
        // drawPanel(guiGraphics, offsetX + 4, offsetY + 228, 166, 24);
        drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.ENCODED_PATTERN);
        drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.PLAYER_INVENTORY);
        drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.PLAYER_HOTBAR);
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> tooltip = super.getTooltipFromContainerItem(stack);
        Slot slot = getSlotUnderMouse();
        if (!hasShiftDown() || slot == null || stack.isEmpty()) {
            return tooltip;
        }
        int pageSlotIndex = menu.getPatternPageSlotIndex(slot);
        if (pageSlotIndex < 0) {
            return tooltip;
        }
        List<Component> augmentedTooltip = new ArrayList<>(tooltip);
        augmentedTooltip.add(menu.getPatternSlotPageTooltip(pageSlotIndex));
        return List.copyOf(augmentedTooltip);
    }

    private int resolvedHighlightedPageSlot() {
        if (menu.highlightedPageSlot >= 0 && menu.highlightedPageSlot < menu.getHost().getVisiblePatternSlots()) {
            return menu.highlightedPageSlot;
        }
        return fallbackHighlightedPageSlot();
    }

    private int fallbackHighlightedPageSlot() {
        if (menu.highlightedGlobalSlot < 0) {
            return -1;
        }
        int visibleSlots = Math.max(1, menu.getHost().getVisiblePatternSlots());
        int highlightedPage = menu.highlightedGlobalSlot / visibleSlots;
        if (highlightedPage != menu.pageIndex) {
            return -1;
        }
        return menu.highlightedGlobalSlot % visibleSlots;
    }

    private void drawSlotHighlightBorder(GuiGraphics guiGraphics, Slot slot, int color, int thickness) {
        int left = slot.x - 2;
        int top = slot.y - 2;
        int right = slot.x + 18;
        int bottom = slot.y + 18;
        guiGraphics.fill(left, top, right, top + thickness, color);
        guiGraphics.fill(left, bottom - thickness, right, bottom, color);
        guiGraphics.fill(left, top, left + thickness, bottom, color);
        guiGraphics.fill(right - thickness, top, right, bottom, color);
    }

    private void drawPanel(GuiGraphics guiGraphics, int left, int top, int width, int height) {
        guiGraphics.fill(left, top, left + width, top + height, PANEL_BORDER);
        guiGraphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, PANEL_FILL);
    }

    private void drawSlotBackgrounds(GuiGraphics guiGraphics, int offsetX, int offsetY, SlotSemantic semantics) {
        for (Slot slot : menu.getSlots(semantics)) {
            int left = offsetX + slot.x - 1;
            int top = offsetY + slot.y - 1;
            guiGraphics.fill(left, top, left + 18, top + 18, SLOT_BORDER);
            guiGraphics.fill(left + 1, top + 1, left + 17, top + 17, SLOT_FILL);
        }
    }
}
