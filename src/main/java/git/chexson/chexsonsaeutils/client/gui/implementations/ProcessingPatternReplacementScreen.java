package git.chexson.chexsonsaeutils.client.gui.implementations;

import appeng.api.config.ActionItems;
import appeng.client.Point;
import appeng.client.gui.AESubScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.Scrollbar;
import appeng.menu.me.items.PatternEncodingTermMenu;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotCandidateGroup;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleDraft;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleHost;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRulePayload;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ProcessingPatternReplacementScreen<C extends PatternEncodingTermMenu>
        extends AESubScreen<C, PatternEncodingTermScreen<C>> {
    private static final Component TITLE = Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.title");
    private static final Component GROUPED_CONTENT_LABEL =
            Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.grouped_content");
    private static final Component INTERACTION_HINT =
            Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.interaction_hint");
    private static final Component EMPTY_STATE =
            Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.empty");
    private static final Component SAVE_LABEL =
            Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.save");
    private static final Component CLEAR_LABEL =
            Component.translatable("gui.chexsonsaeutils.processing_pattern_rule.clear");
    private static final int SOURCE_SLOT_X = 8;
    private static final int SOURCE_SLOT_Y = 24;
    private static final int SOURCE_SLOT_SIZE = 18;
    private static final int GROUPED_CONTENT_X = 8;
    private static final int GROUPED_CONTENT_Y = 62;
    private static final int GROUPED_CONTENT_WIDTH = 182;
    private static final int GROUPED_CONTENT_HEIGHT = 110;
    private static final int GROUPED_SCROLLBAR_X = 192;
    private static final int GROUPED_SCROLLBAR_Y = 62;
    private static final int SECTION_PADDING = 4;
    private static final int SECTION_HEADER_HEIGHT = 14;
    private static final int SECTION_GAP = 4;
    private static final int TAG_COLUMN_WIDTH = 74;
    private static final int CONTENT_DIVIDER_X = TAG_COLUMN_WIDTH + 8;
    private static final int ITEM_GRID_X = CONTENT_DIVIDER_X + 6;
    private static final int ITEM_GRID_WIDTH = GROUPED_CONTENT_WIDTH - ITEM_GRID_X - SECTION_PADDING;
    private static final int ICON_COLUMNS = 5;
    private static final int ICON_CELL_SIZE = 18;
    private static final int ICON_INSET = 1;
    private static final int CHECKBOX_SIZE = 10;
    private static final int TEXT_PRIMARY = 0x404040;
    private static final int TEXT_MUTED = 0x707070;
    private static final int TEXT_SELECTED = 0x1D6B3B;
    private static final int PANEL_BORDER = 0xFF8B8B8B;
    private static final int PANEL_FILL = 0x55222222;
    private static final int HEADER_FILL = 0x336B6B6B;
    private static final int CHECKBOX_BORDER = 0xFF9D9D9D;
    private static final int CHECKBOX_FILL = 0xFF2F2F2F;
    private static final int CHECKBOX_MARK = 0xFF3D8C56;
    private static final int CELL_BORDER = 0xFF7A7A7A;
    private static final int CELL_FILL = 0x99252525;
    private static final int CELL_SELECTED_FILL = 0x664C9D62;
    private static final int HOVER_OUTLINE = 0xFFDDD28C;

    private final int slotIndex;
    private final ProcessingSlotRuleHost processingSlotRuleHost;
    private final ProcessingSlotTagService processingSlotTagService = new ProcessingSlotTagService();
    private final Scrollbar groupedScrollbar = new Scrollbar(Scrollbar.SMALL);
    private final Set<ResourceLocation> selectedTagIds = new LinkedHashSet<>();
    private final Set<ResourceLocation> explicitCandidateIds = new LinkedHashSet<>();
    private List<ProcessingSlotCandidateGroup> candidateGroups = List.of();
    private List<TagSectionRow> tagSectionRows = List.of();
    private ActionButton closeButton;
    private ActionButton saveButton;
    private ActionButton clearButton;

    public ProcessingPatternReplacementScreen(PatternEncodingTermScreen<C> parentScreen, int slotIndex) {
        super(parentScreen, "/screens/processing_pattern_replacement.json");
        this.slotIndex = slotIndex;
        this.processingSlotRuleHost = (ProcessingSlotRuleHost) parentScreen.getMenu();
        this.groupedScrollbar.setHeight(GROUPED_CONTENT_HEIGHT);
        this.groupedScrollbar.setPosition(new Point(GROUPED_SCROLLBAR_X, GROUPED_SCROLLBAR_Y));
        this.groupedScrollbar.setCaptureMouseWheel(false);
        refreshDraftView();
    }

    @Override
    protected void init() {
        super.init();
        refreshDraftView();

        closeButton = addRenderableWidget(new ActionButton(ActionItems.CLOSE, this::returnToParent));
        closeButton.setMessage(TITLE);
        closeButton.setX(leftPos + 176);
        closeButton.setY(topPos + 4);

        saveButton = addRenderableWidget(new ActionButton(ActionItems.ENCODE, this::saveDraft));
        saveButton.setMessage(SAVE_LABEL);
        saveButton.setX(leftPos + 144);
        saveButton.setY(topPos + 24);

        clearButton = addRenderableWidget(new ActionButton(ActionItems.CLOSE, this::clearDraft));
        clearButton.setMessage(CLEAR_LABEL);
        clearButton.setX(leftPos + 162);
        clearButton.setY(topPos + 24);

        updateGroupedScrollbar();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        groupedScrollbar.tick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            returnToParent();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Point relativeMouse = relativeMouse(mouseX, mouseY);
        if (isInGroupedScrollbar(relativeMouse) && groupedScrollbar.onMouseDown(relativeMouse, button)) {
            return true;
        }
        if (button == 0 && handleTagToggleClick(relativeMouse)) {
            return true;
        }
        if (button == 0 && handleItemIconClick(relativeMouse)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (groupedScrollbar.onMouseUp(relativeMouse(mouseX, mouseY), button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (groupedScrollbar.onMouseDrag(relativeMouse(mouseX, mouseY), button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
        Point relativeMouse = relativeMouse(mouseX, mouseY);
        if (isInGroupedContent(relativeMouse) && groupedScrollbar.onMouseWheel(relativeMouse, verticalDelta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        Point relativeMouse = relativeMouse(mouseX, mouseY);
        TagSectionRow hoveredTagSection = findTagSectionAt(relativeMouse);
        ItemIconCell hoveredItemIconCell = findItemIconCellAt(relativeMouse);
        guiGraphics.drawString(font, TITLE, 28, 8, TEXT_PRIMARY, false);
        drawSourceSlot(guiGraphics);
        guiGraphics.drawString(font, GROUPED_CONTENT_LABEL, GROUPED_CONTENT_X + 24, 28, TEXT_PRIMARY, false);
        guiGraphics.drawString(
                font,
                Component.translatable(
                        "gui.chexsonsaeutils.processing_pattern_rule.selected_summary",
                        selectedTagIds.size(),
                        explicitCandidateIds.size()
                ),
                GROUPED_CONTENT_X + 24,
                40,
                TEXT_MUTED,
                false
        );
        guiGraphics.drawString(
                font,
                Component.literal(shortenTextToWidth(INTERACTION_HINT.getString(), GROUPED_CONTENT_WIDTH - 4)),
                GROUPED_CONTENT_X,
                184,
                TEXT_MUTED,
                false
        );

        drawGroupedContentBackground(guiGraphics);
        drawTagSectionRows(guiGraphics, hoveredTagSection, hoveredItemIconCell);
        groupedScrollbar.drawForegroundLayer(
                guiGraphics,
                new Rect2i(0, 0, imageWidth, imageHeight),
                relativeMouse
        );
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    @Nullable
    public ProcessingSlotRuleDraft getDraft() {
        ProcessingSlotRuleDraft draft = processingSlotRuleHost.getProcessingSlotRuleDraft(slotIndex);
        return draft != null ? draft : processingSlotRuleHost.buildProcessingSlotRuleDraft(slotIndex);
    }

    private void saveDraft() {
        processingSlotRuleHost.requestSaveProcessingSlotRuleDraft(
                new ProcessingSlotRulePayload(slotIndex, selectedTagIds, explicitCandidateIds)
        );
        returnToParent();
    }

    private void clearDraft() {
        processingSlotRuleHost.requestClearProcessingSlotRuleDraft(slotIndex);
        selectedTagIds.clear();
        explicitCandidateIds.clear();
        returnToParent();
    }

    private void refreshDraftView() {
        ProcessingSlotRuleDraft draft = getDraft();
        selectedTagIds.clear();
        explicitCandidateIds.clear();
        if (draft != null) {
            selectedTagIds.addAll(draft.selectedTagIds());
            explicitCandidateIds.addAll(draft.explicitCandidateIds());
        }
        candidateGroups = processingSlotTagService.buildCandidateGroups(getSourceStack());
        tagSectionRows = buildTagSectionRows(candidateGroups);
        updateGroupedScrollbar();
    }

    private void updateGroupedScrollbar() {
        int contentHeight = tagSectionRows.isEmpty()
                ? 0
                : tagSectionRows.get(tagSectionRows.size() - 1).y() + tagSectionRows.get(tagSectionRows.size() - 1).height();
        int maxScroll = Math.max(0, contentHeight - GROUPED_CONTENT_HEIGHT);
        groupedScrollbar.setRange(0, maxScroll, 9);
        groupedScrollbar.setVisible(maxScroll > 0);
    }

    private void drawGroupedContentBackground(GuiGraphics guiGraphics) {
        guiGraphics.fill(
                GROUPED_CONTENT_X - 1,
                GROUPED_CONTENT_Y - 1,
                GROUPED_CONTENT_X + GROUPED_CONTENT_WIDTH + 1,
                GROUPED_CONTENT_Y + GROUPED_CONTENT_HEIGHT + 1,
                PANEL_BORDER
        );
        guiGraphics.fill(
                GROUPED_CONTENT_X,
                GROUPED_CONTENT_Y,
                GROUPED_CONTENT_X + GROUPED_CONTENT_WIDTH,
                GROUPED_CONTENT_Y + GROUPED_CONTENT_HEIGHT,
                PANEL_FILL
        );
        guiGraphics.fill(
                GROUPED_CONTENT_X + CONTENT_DIVIDER_X,
                GROUPED_CONTENT_Y + SECTION_PADDING,
                GROUPED_CONTENT_X + CONTENT_DIVIDER_X + 1,
                GROUPED_CONTENT_Y + GROUPED_CONTENT_HEIGHT - SECTION_PADDING,
                PANEL_BORDER
        );
    }

    private void drawSourceSlot(GuiGraphics guiGraphics) {
        guiGraphics.fill(SOURCE_SLOT_X, SOURCE_SLOT_Y, SOURCE_SLOT_X + SOURCE_SLOT_SIZE, SOURCE_SLOT_Y + SOURCE_SLOT_SIZE,
                CELL_BORDER);
        guiGraphics.fill(
                SOURCE_SLOT_X + 1,
                SOURCE_SLOT_Y + 1,
                SOURCE_SLOT_X + SOURCE_SLOT_SIZE - 1,
                SOURCE_SLOT_Y + SOURCE_SLOT_SIZE - 1,
                CELL_FILL
        );
        ItemStack sourceStack = getSourceStack();
        if (!sourceStack.isEmpty()) {
            guiGraphics.renderItem(sourceStack, SOURCE_SLOT_X + ICON_INSET, SOURCE_SLOT_Y + ICON_INSET);
        }
    }

    private void drawTagSectionRows(
            GuiGraphics guiGraphics,
            @Nullable TagSectionRow hoveredTagSection,
            @Nullable ItemIconCell hoveredItemIconCell
    ) {
        if (tagSectionRows.isEmpty()) {
            guiGraphics.drawString(font, EMPTY_STATE, GROUPED_CONTENT_X + 6, GROUPED_CONTENT_Y + 6, TEXT_MUTED, false);
            return;
        }

        int scroll = groupedScrollbar.getCurrentScroll();
        guiGraphics.enableScissor(
                leftPos + GROUPED_CONTENT_X,
                topPos + GROUPED_CONTENT_Y,
                leftPos + GROUPED_CONTENT_X + GROUPED_CONTENT_WIDTH,
                topPos + GROUPED_CONTENT_Y + GROUPED_CONTENT_HEIGHT
        );
        for (TagSectionRow tagSectionRow : tagSectionRows) {
            int sectionTop = tagSectionRow.y() - scroll;
            int sectionBottom = sectionTop + tagSectionRow.height();
            if (sectionBottom <= 0 || sectionTop >= GROUPED_CONTENT_HEIGHT) {
                continue;
            }

            int headerTop = GROUPED_CONTENT_Y + sectionTop;
            guiGraphics.fill(
                    GROUPED_CONTENT_X + SECTION_PADDING,
                    headerTop,
                    GROUPED_CONTENT_X + CONTENT_DIVIDER_X - SECTION_PADDING,
                    headerTop + tagSectionRow.height() - SECTION_GAP,
                    HEADER_FILL
            );
            drawSelectionCheckbox(
                    guiGraphics,
                    GROUPED_CONTENT_X + tagSectionRow.checkboxX(),
                    GROUPED_CONTENT_Y + tagSectionRow.checkboxY() - scroll,
                    tagSectionRow.selected(),
                    hoveredTagSection != null && hoveredTagSection.tagId().equals(tagSectionRow.tagId())
            );
            int labelX = GROUPED_CONTENT_X + SECTION_PADDING + CHECKBOX_SIZE + 5;
            int labelY = headerTop + 3;
            for (Component line : tagSectionRow.labelLines()) {
                guiGraphics.drawString(
                        font,
                        line,
                        labelX,
                        labelY,
                        tagSectionRow.selected() ? TEXT_SELECTED : TEXT_PRIMARY,
                        false
                );
                labelY += font.lineHeight;
            }

            for (ItemIconCell itemIconCell : tagSectionRow.itemIconCells()) {
                int renderY = GROUPED_CONTENT_Y + itemIconCell.y() - scroll;
                if (renderY + ICON_CELL_SIZE <= GROUPED_CONTENT_Y || renderY >= GROUPED_CONTENT_Y + GROUPED_CONTENT_HEIGHT) {
                    continue;
                }
                drawItemIconCell(
                        guiGraphics,
                        itemIconCell,
                        GROUPED_CONTENT_X + itemIconCell.x(),
                        renderY,
                        hoveredItemIconCell != null && hoveredItemIconCell.itemId().equals(itemIconCell.itemId())
                );
            }
        }
        guiGraphics.disableScissor();
    }

    private void drawSelectionCheckbox(GuiGraphics guiGraphics, int x, int y, boolean selected, boolean hovered) {
        guiGraphics.fill(x, y, x + CHECKBOX_SIZE, y + CHECKBOX_SIZE, hovered ? HOVER_OUTLINE : CHECKBOX_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + CHECKBOX_SIZE - 1, y + CHECKBOX_SIZE - 1, CHECKBOX_FILL);
        if (selected) {
            guiGraphics.fill(x + 2, y + 2, x + CHECKBOX_SIZE - 2, y + CHECKBOX_SIZE - 2, CHECKBOX_MARK);
        }
    }

    private void drawItemIconCell(
            GuiGraphics guiGraphics,
            ItemIconCell itemIconCell,
            int renderX,
            int renderY,
            boolean hovered
    ) {
        guiGraphics.fill(renderX, renderY, renderX + ICON_CELL_SIZE, renderY + ICON_CELL_SIZE, hovered ? HOVER_OUTLINE : CELL_BORDER);
        guiGraphics.fill(
                renderX + 1,
                renderY + 1,
                renderX + ICON_CELL_SIZE - 1,
                renderY + ICON_CELL_SIZE - 1,
                itemIconCell.selected() ? CELL_SELECTED_FILL : CELL_FILL
        );
        if (!itemIconCell.stack().isEmpty()) {
            guiGraphics.renderItem(itemIconCell.stack(), renderX + ICON_INSET, renderY + ICON_INSET);
        }
    }

    private boolean handleTagToggleClick(Point relativeMouse) {
        TagSectionRow tagSectionRow = findTagSectionAt(relativeMouse);
        if (tagSectionRow != null) {
            if (!selectedTagIds.add(tagSectionRow.tagId())) {
                selectedTagIds.remove(tagSectionRow.tagId());
            }
            tagSectionRows = buildTagSectionRows(candidateGroups);
            return true;
        }
        return false;
    }

    private boolean handleItemIconClick(Point relativeMouse) {
        ItemIconCell itemIconCell = findItemIconCellAt(relativeMouse);
        if (itemIconCell != null) {
            if (!explicitCandidateIds.add(itemIconCell.itemId())) {
                explicitCandidateIds.remove(itemIconCell.itemId());
            }
            tagSectionRows = buildTagSectionRows(candidateGroups);
            return true;
        }
        return false;
    }

    @Nullable
    private TagSectionRow findTagSectionAt(Point relativeMouse) {
        if (!isInGroupedContent(relativeMouse)) {
            return null;
        }

        int contentX = relativeMouse.getX() - GROUPED_CONTENT_X;
        int contentY = relativeMouse.getY() - GROUPED_CONTENT_Y + groupedScrollbar.getCurrentScroll();
        for (TagSectionRow tagSectionRow : tagSectionRows) {
            if (contentX >= tagSectionRow.checkboxX()
                    && contentX <= tagSectionRow.checkboxX() + CHECKBOX_SIZE
                    && contentY >= tagSectionRow.checkboxY()
                    && contentY <= tagSectionRow.checkboxY() + CHECKBOX_SIZE) {
                return tagSectionRow;
            }
        }
        return null;
    }

    @Nullable
    private ItemIconCell findItemIconCellAt(Point relativeMouse) {
        if (!isInGroupedContent(relativeMouse)) {
            return null;
        }

        int contentX = relativeMouse.getX() - GROUPED_CONTENT_X;
        int contentY = relativeMouse.getY() - GROUPED_CONTENT_Y + groupedScrollbar.getCurrentScroll();
        for (TagSectionRow tagSectionRow : tagSectionRows) {
            for (ItemIconCell itemIconCell : tagSectionRow.itemIconCells()) {
                if (contentX >= itemIconCell.x()
                        && contentX < itemIconCell.x() + ICON_CELL_SIZE
                        && contentY >= itemIconCell.y()
                        && contentY < itemIconCell.y() + ICON_CELL_SIZE) {
                    return itemIconCell;
                }
            }
        }
        return null;
    }

    private boolean isInGroupedContent(Point relativeMouse) {
        return relativeMouse.getX() >= GROUPED_CONTENT_X
                && relativeMouse.getX() <= GROUPED_CONTENT_X + GROUPED_CONTENT_WIDTH
                && relativeMouse.getY() >= GROUPED_CONTENT_Y
                && relativeMouse.getY() <= GROUPED_CONTENT_Y + GROUPED_CONTENT_HEIGHT;
    }

    private boolean isInGroupedScrollbar(Point relativeMouse) {
        return relativeMouse.getX() >= GROUPED_SCROLLBAR_X
                && relativeMouse.getX() <= GROUPED_SCROLLBAR_X + 7
                && relativeMouse.getY() >= GROUPED_SCROLLBAR_Y
                && relativeMouse.getY() <= GROUPED_SCROLLBAR_Y + GROUPED_CONTENT_HEIGHT;
    }

    private Point relativeMouse(double mouseX, double mouseY) {
        return new Point((int) Math.round(mouseX - leftPos), (int) Math.round(mouseY - topPos));
    }

    private ItemStack getSourceStack() {
        return processingSlotRuleHost.getProcessingSlotRuleSourceStack(slotIndex);
    }

    private List<TagSectionRow> buildTagSectionRows(List<ProcessingSlotCandidateGroup> groups) {
        List<TagSectionRow> rows = new ArrayList<>();
        int nextY = 0;
        for (ProcessingSlotCandidateGroup group : groups) {
            List<ItemIconCell> itemIconCells = new ArrayList<>();
            int iconRows = Math.max(1, (group.itemIds().size() + ICON_COLUMNS - 1) / ICON_COLUMNS);
            int iconsTop = nextY + 2;
            for (int index = 0; index < group.itemIds().size(); index++) {
                ResourceLocation itemId = group.itemIds().get(index);
                ItemStack itemStack = BuiltInRegistries.ITEM.get(itemId).getDefaultInstance();
                itemIconCells.add(new ItemIconCell(
                        itemId,
                        itemStack,
                        explicitCandidateIds.contains(itemId),
                        ITEM_GRID_X + (index % ICON_COLUMNS) * ICON_CELL_SIZE,
                        iconsTop + (index / ICON_COLUMNS) * ICON_CELL_SIZE
                ));
            }

            List<Component> labelLines = wrapLabelToWidth(
                    "#" + group.tagId() + " (" + group.itemIds().size() + ")",
                    TAG_COLUMN_WIDTH - CHECKBOX_SIZE - SECTION_PADDING - 6
            );
            int labelHeight = Math.max(SECTION_HEADER_HEIGHT, labelLines.size() * font.lineHeight + 4);
            int sectionHeight = Math.max(labelHeight, iconRows * ICON_CELL_SIZE) + SECTION_GAP + 2;
            rows.add(new TagSectionRow(
                    group.tagId(),
                    List.copyOf(labelLines),
                    selectedTagIds.contains(group.tagId()),
                    SECTION_PADDING,
                    nextY + 3,
                    nextY,
                    sectionHeight,
                    List.copyOf(itemIconCells)
            ));
            nextY += sectionHeight;
        }
        return List.copyOf(rows);
    }

    private List<Component> wrapLabelToWidth(String value, int maxWidth) {
        List<Component> lines = new ArrayList<>();
        if (value.isEmpty()) {
            lines.add(Component.empty());
            return lines;
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            String candidate = builder.toString() + current;
            if (builder.length() > 0 && font.width(candidate) > maxWidth) {
                lines.add(Component.literal(builder.toString()));
                builder.setLength(0);
            }
            builder.append(current);
        }
        if (builder.length() > 0) {
            lines.add(Component.literal(builder.toString()));
        }
        return lines.isEmpty() ? List.of(Component.empty()) : List.copyOf(lines);
    }

    private String shortenTextToWidth(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }

        String ellipsis = "...";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            String candidate = builder.toString() + current + ellipsis;
            if (builder.length() > 0 && font.width(candidate) > maxWidth) {
                break;
            }
            builder.append(current);
        }
        return builder.isEmpty() ? ellipsis : builder + ellipsis;
    }

    private record TagSectionRow(
            ResourceLocation tagId,
            List<Component> labelLines,
            boolean selected,
            int checkboxX,
            int checkboxY,
            int y,
            int height,
            List<ItemIconCell> itemIconCells
    ) {
    }

    private record ItemIconCell(
            ResourceLocation itemId,
            ItemStack stack,
            boolean selected,
            int x,
            int y
    ) {
    }
}
