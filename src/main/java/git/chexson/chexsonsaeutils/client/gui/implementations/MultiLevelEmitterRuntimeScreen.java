package git.chexson.chexsonsaeutils.client.gui.implementations;

import appeng.api.upgrades.Upgrades;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.localization.GuiText;
import com.mojang.blaze3d.systems.RenderSystem;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompileResult;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionDiagnostic;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Objects;

public class MultiLevelEmitterRuntimeScreen extends AEBaseScreen<MultiLevelEmitterMenu.RuntimeMenu> {

    private static final Component EXPRESSION_LABEL = Component.translatable("gui.chexsonsaeutils.multi_level_emitter.expression");
    private static final Component EXPRESSION_HINT =
            Component.translatable("gui.chexsonsaeutils.multi_level_emitter.expression_hint");
    private static final Component PRECEDENCE_HINT =
            Component.translatable("gui.chexsonsaeutils.multi_level_emitter.precedence_hint");
    private static final Component VALID_STATUS =
            Component.translatable("gui.chexsonsaeutils.multi_level_emitter.valid_status");
    private static final Component WARNING_STATUS =
            Component.translatable("gui.chexsonsaeutils.multi_level_emitter.warning_status");
    private static final Component INVALID_STATUS =
            Component.translatable("gui.chexsonsaeutils.multi_level_emitter.invalid_status");
    private static final Component SLOT_LAYOUT_CHANGED_STATUS =
            Component.translatable("gui.chexsonsaeutils.multi_level_emitter.slot_layout_changed");
    private static final Component CRAFTING_LOCK_HELPER =
            Component.translatable("gui.chexsonsaeutils.multi_level_emitter.crafting_lock_helper");
    private static final int COLOR_LABEL = 0x404040;
    private static final int COLOR_SUBLABEL = 0x606060;
    private static final int COLOR_HELPER = 0x8A8A8A;
    private static final int COLOR_TEXT = 0x202020;
    private static final int COLOR_INPUT_TEXT = EditBox.DEFAULT_TEXT_COLOR;
    private static final int COLOR_READONLY_INPUT_TEXT = 0x7A7A7A;
    private static final int COLOR_VALID = 0x6A9955;
    private static final int COLOR_WARNING = 0xD0A146;
    private static final int COLOR_INVALID = 0xB85A5A;
    private static final int COLOR_OPERATOR = 0x6B9FB3;
    private static final int COLOR_PAREN = 0x8A8A8A;
    private static final int MAX_RENDERED_ROWS = 2;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_TOP = 104;
    private static final int ROW_WIDGET_Y_OFFSET = 1;
    private static final int ROW_SHADE_Y_OFFSET = -2;
    private static final int ROW_SHADE_HEIGHT = ROW_HEIGHT;
    private static final int CONTENT_X = 8;
    private static final int CONTENT_WIDTH = 160;
    private static final int TOP_ACTION_BUTTON_SIZE = 13;
    private static final int TOP_ACTION_BUTTON_Y = 5;
    private static final int ACTION_PRIMARY_ROW_Y = 67;
    private static final int ACTION_SECONDARY_ROW_Y = 82;
    private static final int ACTION_BUTTON_GAP = 1;
    private static final int ACTION_AND_WIDTH = 22;
    private static final int ACTION_OR_WIDTH = 18;
    private static final int ACTION_PARENS_WIDTH = 22;
    private static final int ACTION_FORMAT_WIDTH = 78;
    private static final int ACTION_APPLY_WIDTH = 81;
    private static final int CONFIG_PANEL_X = 7;
    private static final int CONFIG_PANEL_Y = 95;
    private static final int CONFIG_PANEL_WIDTH = 162;
    private static final int CONFIG_PANEL_HEIGHT = 34;
    private static final int SLOT_CHIP_X = 13;
    private static final int SLOT_X = 33;
    private static final int THRESHOLD_X = 52;
    private static final int THRESHOLD_INPUT_WIDTH = 32;
    private static final int MODE_X = 86;
    private static final int COMPARISON_BUTTON_WIDTH = 22;
    private static final int FUZZY_MODE_X = 111;
    private static final int CRAFTING_MODE_BUTTON_WIDTH = 28;
    private static final int FUZZY_MODE_BUTTON_SIZE = 16;
    private static final int HIDDEN_SLOT_X = -10_000;
    private static final int HIDDEN_SLOT_Y = -10_000;
    private static final int EXPRESSION_LABEL_Y = 22;
    private static final int EXPRESSION_INPUT_X = CONTENT_X;
    private static final int EXPRESSION_INPUT_Y = 32;
    private static final int EXPRESSION_INPUT_WIDTH = CONTENT_WIDTH;
    private static final int MARKED_STATUS_X = 103;
    private static final int MARKED_STATUS_Y = 7;
    private static final int MARKED_STATUS_WIDTH = 28;
    private static final int ROW_SHADE_LEFT = 7;
    private static final int ROW_SHADE_RIGHT = 145;
    private static final int FUZZY_HIGHLIGHT_COLOR = 0x33D0A146;
    private static final int SCROLL_BUTTON_SIZE = 12;
    private static final int SCROLL_BUTTON_X = 151;
    private static final int SCROLL_UP_BUTTON_Y = ROW_TOP + 4;
    private static final int SCROLL_DOWN_BUTTON_Y = ROW_TOP + ROW_HEIGHT - 1;
    private static final Field SLOT_X_FIELD = findField(Slot.class, "f_40220_");
    private static final Field SLOT_Y_FIELD = findField(Slot.class, "f_40221_");
    private static final Field EDIT_BOX_HIGHLIGHT_FIELD = findField(EditBox.class, "f_94102_");

    private final List<ThresholdEditBox> thresholdInputs = new ArrayList<>();
    private final List<Button> comparisonButtons = new ArrayList<>();
    private final List<FuzzyModeButton> fuzzyModeButtons = new ArrayList<>();
    private final List<FittedTextButton> craftingModeButtons = new ArrayList<>();
    private final List<Button> slotReferenceButtons = new ArrayList<>();
    private Button addSlotButton;
    private Button removeSlotButton;
    private Button scrollUpButton;
    private Button scrollDownButton;
    private Button andButton;
    private Button orButton;
    private Button parenthesesButton;
    private Button formatExpressionButton;
    private Button applyExpressionButton;
    private EditBox expressionInput;
    private int scrollOffset;
    private String expressionDraftText = "";
    private boolean expressionDirty;
    private int previousConfiguredSlots;
    private Component helperStatus = PRECEDENCE_HINT;
    private int helperStatusColor = COLOR_HELPER;
    private final List<Long> lastServerThresholds = new ArrayList<>();
    private MultiLevelEmitterExpressionCompileResult currentExpressionValidation;
    private Component currentValidationStatus = INVALID_STATUS;
    private int currentValidationColor = COLOR_INVALID;

    public MultiLevelEmitterRuntimeScreen(
            MultiLevelEmitterMenu.RuntimeMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        this(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/multi_level_emitter.json"));
    }

    private MultiLevelEmitterRuntimeScreen(
            MultiLevelEmitterMenu.RuntimeMenu menu,
            Inventory playerInventory,
            Component title,
            ScreenStyle style
    ) {
        super(menu, playerInventory, title, style);
        this.widgets.add("upgrades", new UpgradesPanel(
                menu.getSlots(SlotSemantics.UPGRADE),
                this::getCompatibleUpgrades
        ));
    }

    @Override
    protected void init() {
        super.init();
        thresholdInputs.clear();
        comparisonButtons.clear();
        fuzzyModeButtons.clear();
        craftingModeButtons.clear();
        slotReferenceButtons.clear();
        resetLocalUiState();

        addSlotButton = addRenderableWidget(Button.builder(Component.literal("+"), button -> {
            int next = MultiLevelEmitterScreen.nextConfiguredSlotCount(
                    menu.configuredSlotCount(),
                    1,
                    menu.totalSlotCapacity()
            );
            MultiLevelEmitterScreen.applyConfiguredSlotCount(menu, next);
        }).bounds(leftPos + imageWidth - 42, topPos + TOP_ACTION_BUTTON_Y, TOP_ACTION_BUTTON_SIZE, TOP_ACTION_BUTTON_SIZE)
                .tooltip(Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.add_slot")))
                .build());

        removeSlotButton = addRenderableWidget(Button.builder(Component.literal("-"), button -> {
            int next = MultiLevelEmitterScreen.nextConfiguredSlotCount(
                    menu.configuredSlotCount(),
                    -1,
                    menu.totalSlotCapacity()
            );
            MultiLevelEmitterScreen.applyConfiguredSlotCount(menu, next);
        }).bounds(leftPos + imageWidth - 24, topPos + TOP_ACTION_BUTTON_Y, TOP_ACTION_BUTTON_SIZE, TOP_ACTION_BUTTON_SIZE)
                .tooltip(Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.remove_slot")))
                .build());

        expressionInput = addRenderableWidget(new EditBox(
                font,
                leftPos + EXPRESSION_INPUT_X,
                topPos + EXPRESSION_INPUT_Y,
                EXPRESSION_INPUT_WIDTH,
                16,
                Component.literal("expressionInput")
        ));
        expressionInput.setMaxLength(1024);
        expressionInput.setHint(EXPRESSION_HINT);
        expressionInput.setResponder(this::onExpressionDraftChanged);
        expressionInput.setFormatter(this::formatDraftText);
        expressionInput.setValue(menu.appliedExpressionText());
        expressionDraftText = expressionInput.getValue();

        int actionButtonX = leftPos + CONTENT_X;
        andButton = addRenderableWidget(new FittedTextButton(
                actionButtonX,
                topPos + ACTION_PRIMARY_ROW_Y,
                ACTION_AND_WIDTH,
                16,
                Component.translatable("gui.chexsonsaeutils.multi_level_emitter.and"),
                button -> insertOperator("AND"),
                1.0f,
                0.7f,
                Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.insert_and"))
        ));

        actionButtonX += ACTION_AND_WIDTH + ACTION_BUTTON_GAP;
        orButton = addRenderableWidget(new FittedTextButton(
                actionButtonX,
                topPos + ACTION_PRIMARY_ROW_Y,
                ACTION_OR_WIDTH,
                16,
                Component.translatable("gui.chexsonsaeutils.multi_level_emitter.or"),
                button -> insertOperator("OR"),
                1.0f,
                0.7f,
                Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.insert_or"))
        ));

        actionButtonX += ACTION_OR_WIDTH + ACTION_BUTTON_GAP;
        parenthesesButton = addRenderableWidget(new FittedTextButton(
                actionButtonX,
                topPos + ACTION_PRIMARY_ROW_Y,
                ACTION_PARENS_WIDTH,
                16,
                Component.translatable("gui.chexsonsaeutils.multi_level_emitter.wrap_selection_short"),
                button -> wrapSelection(),
                1.0f,
                0.7f,
                Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.wrap_selection"))
        ));

        actionButtonX = leftPos + CONTENT_X;
        formatExpressionButton = addRenderableWidget(new FittedTextButton(
                actionButtonX,
                topPos + ACTION_SECONDARY_ROW_Y,
                ACTION_FORMAT_WIDTH,
                16,
                Component.translatable("gui.chexsonsaeutils.multi_level_emitter.format_expression"),
                button -> setExpressionDraft(MultiLevelEmitterScreen.formatDraftExpression(expressionInput.getValue())),
                0.74f,
                0.62f,
                Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.normalize_expression_spacing"))
        ));

        actionButtonX += ACTION_FORMAT_WIDTH + ACTION_BUTTON_GAP;
        applyExpressionButton = addRenderableWidget(new FittedTextButton(
                actionButtonX,
                topPos + ACTION_SECONDARY_ROW_Y,
                ACTION_APPLY_WIDTH,
                16,
                Component.translatable("gui.chexsonsaeutils.multi_level_emitter.apply_expression"),
                button -> applyExpressionDraft(),
                0.76f,
                0.64f,
                Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.save_expression"))
        ));

        scrollUpButton = addRenderableWidget(Button.builder(Component.literal("^"), button -> {
            scrollOffset = Math.max(0, scrollOffset - 1);
            syncLayoutFromMenu();
        }).bounds(leftPos + SCROLL_BUTTON_X, topPos + SCROLL_UP_BUTTON_Y, SCROLL_BUTTON_SIZE, SCROLL_BUTTON_SIZE).build());

        scrollDownButton = addRenderableWidget(Button.builder(Component.literal("v"), button -> {
            scrollOffset = Math.min(maxScrollOffset(menu.visibleSlotCount()), scrollOffset + 1);
            syncLayoutFromMenu();
        }).bounds(leftPos + SCROLL_BUTTON_X, topPos + SCROLL_DOWN_BUTTON_Y, SCROLL_BUTTON_SIZE, SCROLL_BUTTON_SIZE).build());

        for (int row = 0; row < MAX_RENDERED_ROWS; row++) {
            final int rowIndex = row;
            Button slotReferenceButton = Button.builder(Component.literal("#" + (row + 1)), button -> {
                int slotIndex = rowToSlotIndex(rowIndex);
                insertSlotReference(slotIndex + 1);
            }).bounds(0, 0, 18, 14).build();
            addRenderableWidget(slotReferenceButton);
            slotReferenceButtons.add(slotReferenceButton);

            ThresholdEditBox thresholdInput = new ThresholdEditBox(font, 0, 0, THRESHOLD_INPUT_WIDTH, 14, row);
            thresholdInput.setMaxLength(12);
            thresholdInput.setBordered(true);
            thresholdInput.setFilter(value -> value.isEmpty() || value.matches("\\d{0,12}"));
            thresholdInput.setHint(Component.literal("1"));
            addRenderableWidget(thresholdInput);
            thresholdInputs.add(thresholdInput);

            Button comparisonButton = Button.builder(Component.literal(">="), button -> {
                int slotIndex = rowToSlotIndex(rowIndex);
                if (!menu.isSlotConfigured(slotIndex)) {
                    return;
                }
                MultiLevelEmitterScreen.toggleComparisonMode(menu, slotIndex);
                button.setMessage(Component.literal(
                        MultiLevelEmitterScreen.comparisonModeLabel(menu.comparisonModeForSlot(slotIndex))
                ));
            }).bounds(0, 0, COMPARISON_BUTTON_WIDTH, 14).build();
            addRenderableWidget(comparisonButton);
            comparisonButtons.add(comparisonButton);

            FuzzyModeButton fuzzyModeButton = new FuzzyModeButton(
                    0,
                    0,
                    FUZZY_MODE_BUTTON_SIZE,
                    FUZZY_MODE_BUTTON_SIZE,
                    Component.literal("STR"),
                    button -> {
                        int slotIndex = rowToSlotIndex(rowIndex);
                        if (!menu.isSlotConfigured(slotIndex)) {
                            return;
                        }
                        MultiLevelEmitterScreen.toggleMatchingMode(menu, slotIndex);
                    },
                    Tooltip.create(Component.empty())
            );
            addRenderableWidget(fuzzyModeButton);
            fuzzyModeButtons.add(fuzzyModeButton);

            FittedTextButton craftingModeButton = new FittedTextButton(
                    0,
                    0,
                    CRAFTING_MODE_BUTTON_WIDTH,
                    14,
                    Component.literal(""),
                    button -> {
                        int slotIndex = rowToSlotIndex(rowIndex);
                        if (!menu.isSlotConfigured(slotIndex)) {
                            return;
                        }
                        MultiLevelEmitterScreen.toggleCraftingMode(menu, slotIndex);
                    },
                    0.9f,
                    0.62f,
                    Tooltip.create(Component.empty())
            );
            addRenderableWidget(craftingModeButton);
            craftingModeButtons.add(craftingModeButton);
        }

        syncLayoutFromMenu();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (expressionInput != null) {
            expressionInput.tick();
        }
        syncLayoutFromMenu();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double wheelDelta) {
        if (wheelDelta != 0
                && isMouseOverConfigPanel(mouseX, mouseY)
                && maxScrollOffset(menu.visibleSlotCount()) > 0) {
            int previousOffset = scrollOffset;
            if (wheelDelta > 0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            } else {
                scrollOffset = Math.min(maxScrollOffset(menu.visibleSlotCount()), scrollOffset + 1);
            }
            if (scrollOffset != previousOffset) {
                syncLayoutFromMenu();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, wheelDelta);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        syncLayoutFromMenu();
    }

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);

        for (int row = 0; row < MAX_RENDERED_ROWS; row++) {
            int slotIndex = rowToSlotIndex(row);
            int y = offsetY + rowBaseY(row) + ROW_SHADE_Y_OFFSET;
            MultiLevelEmitterExpressionDiagnostic diagnostic = primaryDiagnostic();
            if (diagnostic != null && diagnostic.slotIndex() == slotIndex) {
                int color = 0;
                if (diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.WARNING) {
                    color = COLOR_WARNING;
                } else if (diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.INVALID) {
                    color = COLOR_INVALID;
                }
                if (color != 0) {
                    guiGraphics.fill(offsetX + ROW_SHADE_LEFT, y, offsetX + ROW_SHADE_RIGHT, y + ROW_SHADE_HEIGHT, color);
                }
            } else if (slotIndex < state.slots().size() && state.slots().get(slotIndex).duplicateEmitToCraftTarget()) {
                guiGraphics.fill(offsetX + ROW_SHADE_LEFT, y, offsetX + ROW_SHADE_RIGHT, y + ROW_SHADE_HEIGHT, FUZZY_HIGHLIGHT_COLOR);
            } else if (slotIndex < state.slots().size() && state.slots().get(slotIndex).emphasizeFuzzyMode()) {
                guiGraphics.fill(offsetX + ROW_SHADE_LEFT, y, offsetX + ROW_SHADE_RIGHT, y + ROW_SHADE_HEIGHT, FUZZY_HIGHLIGHT_COLOR);
            }
        }
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);
        drawFittedText(
                guiGraphics,
                font,
                Component.literal(MultiLevelEmitterScreen.configuredOverTotalLabel(
                        state.markedSlots(),
                        state.configuredSlots()
                )),
                MARKED_STATUS_X,
                MARKED_STATUS_Y,
                MARKED_STATUS_WIDTH,
                COLOR_LABEL,
                0.72f,
                0.54f
        );
        guiGraphics.drawString(font, EXPRESSION_LABEL, 8, EXPRESSION_LABEL_Y, COLOR_TEXT, false);
        drawFittedText(guiGraphics, font, helperStatus, CONTENT_X, 50, CONTENT_WIDTH, helperStatusColor, 0.58f, 0.5f);
        drawFittedText(guiGraphics, font, currentValidationStatus, CONTENT_X, 58, CONTENT_WIDTH, currentValidationColor, 0.52f, 0.4f);
    }

    private void syncLayoutFromMenu() {
        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);
        clampScrollOffset(state.visibleSlots());
        syncExpressionState(state);
        syncSlotLayout(state.visibleSlots());
        syncWidgetLayout(state);

        addSlotButton.active = state.configuredSlots() < state.totalSlots();
        removeSlotButton.active = state.configuredSlots() > MultiLevelEmitterRuntimePart.DEFAULT_VISIBLE_SLOT_COUNT;
        scrollUpButton.active = scrollOffset > 0;
        scrollDownButton.active = scrollOffset < maxScrollOffset(state.visibleSlots());
        applyExpressionButton.active =
                !state.expressionLocked() && MultiLevelEmitterScreen.canApplyExpression(currentExpressionValidation);
        rememberServerSnapshot(state);
    }

    private void syncExpressionState(MultiLevelEmitterScreen.RuntimeScreenState state) {
        if (expressionInput == null) {
            return;
        }
        MultiLevelEmitterScreen.ExpressionDraftSyncDecision decision =
                MultiLevelEmitterScreen.resolveExpressionDraftSync(
                        expressionDraftText,
                        expressionDirty,
                        state.appliedExpressionText(),
                        previousConfiguredSlots,
                        state.configuredSlots()
                );
        boolean draftChanged = !Objects.equals(decision.draftText(), expressionInput.getValue());
        if (draftChanged) {
            expressionInput.setValue(decision.draftText());
            expressionDraftText = expressionInput.getValue();
        }
        expressionDirty = decision.dirty();
        if (decision.topologyReset()) {
            helperStatus = SLOT_LAYOUT_CHANGED_STATUS;
            helperStatusColor = COLOR_WARNING;
        }
        currentExpressionValidation = MultiLevelEmitterScreen.validateExpressionDraft(
                expressionInput.getValue(),
                state.configuredSlots(),
                menu::hasMarkedItem
        );
        boolean expressionLocked = state.expressionLocked();
        expressionInput.active = !expressionLocked;
        expressionInput.setEditable(!expressionLocked);
        expressionInput.setTooltip(expressionLocked ? Tooltip.create(state.craftingLockTooltip()) : null);
        if (expressionLocked) {
            expressionInput.setFocused(false);
            clearFocus();
            helperStatus = Component.translatable("gui.chexsonsaeutils.multi_level_emitter.crafting_lock_helper");
            helperStatusColor = COLOR_WARNING;
        } else if (Objects.equals(helperStatus.getString(), CRAFTING_LOCK_HELPER.getString())) {
            helperStatus = PRECEDENCE_HINT;
            helperStatusColor = COLOR_HELPER;
        }
        andButton.active = !expressionLocked;
        andButton.setTooltip(expressionLocked
                ? Tooltip.create(state.craftingLockTooltip())
                : Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.insert_and")));
        orButton.active = !expressionLocked;
        orButton.setTooltip(expressionLocked
                ? Tooltip.create(state.craftingLockTooltip())
                : Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.insert_or")));
        parenthesesButton.active = !expressionLocked;
        parenthesesButton.setTooltip(expressionLocked
                ? Tooltip.create(state.craftingLockTooltip())
                : Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.wrap_selection")));
        formatExpressionButton.active = !expressionLocked;
        formatExpressionButton.setTooltip(expressionLocked
                ? Tooltip.create(state.craftingLockTooltip())
                : Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.normalize_expression_spacing")));
        applyExpressionButton.setTooltip(expressionLocked
                ? Tooltip.create(state.craftingLockTooltip())
                : Tooltip.create(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.save_expression")));
        updateValidationPresentation();
    }

    private void syncSlotLayout(int visibleSlots) {
        List<Slot> configSlots = menu.getSlots(SlotSemantics.CONFIG);
        for (int slotIndex = 0; slotIndex < configSlots.size(); slotIndex++) {
            Slot slot = configSlots.get(slotIndex);
            boolean visible = slotIndex >= scrollOffset && slotIndex < scrollOffset + visibleRows(visibleSlots);
            if (slot instanceof AppEngSlot appEngSlot) {
                appEngSlot.setSlotEnabled(visible && menu.isSlotEnabled(slotIndex));
            }
            if (visible) {
                int row = slotIndex - scrollOffset;
                setSlotPosition(slot, SLOT_X, rowBaseY(row));
            } else {
                setSlotPosition(slot, HIDDEN_SLOT_X, HIDDEN_SLOT_Y);
            }
        }
    }

    private void syncWidgetLayout(MultiLevelEmitterScreen.RuntimeScreenState state) {
        int visibleRows = visibleRows(state.visibleSlots());
        for (int row = 0; row < MAX_RENDERED_ROWS; row++) {
            Button slotReferenceButton = slotReferenceButtons.get(row);
            ThresholdEditBox input = thresholdInputs.get(row);
            Button comparisonButton = comparisonButtons.get(row);
            FuzzyModeButton fuzzyModeButton = fuzzyModeButtons.get(row);
            FittedTextButton craftingModeButton = craftingModeButtons.get(row);
            boolean rowVisible = row < visibleRows;
            if (!rowVisible) {
                slotReferenceButton.visible = false;
                slotReferenceButton.active = false;
                slotReferenceButton.setPosition(HIDDEN_SLOT_X, HIDDEN_SLOT_Y);
                input.visible = false;
                input.setEditable(false);
                input.setPosition(HIDDEN_SLOT_X, HIDDEN_SLOT_Y);
                comparisonButton.visible = false;
                comparisonButton.active = false;
                comparisonButton.setPosition(HIDDEN_SLOT_X, HIDDEN_SLOT_Y);
                fuzzyModeButton.visible = false;
                fuzzyModeButton.active = false;
                fuzzyModeButton.setPosition(HIDDEN_SLOT_X, HIDDEN_SLOT_Y);
                craftingModeButton.visible = false;
                craftingModeButton.active = false;
                craftingModeButton.setPosition(HIDDEN_SLOT_X, HIDDEN_SLOT_Y);
                continue;
            }

            int slotIndex = rowToSlotIndex(row);
            MultiLevelEmitterScreen.SlotView slot = state.slots().get(slotIndex);
            int y = topPos + rowBaseY(row) + ROW_WIDGET_Y_OFFSET;
            boolean enabled = slot.enabled();

            slotReferenceButton.visible = true;
            slotReferenceButton.active = enabled && !state.expressionLocked();
            slotReferenceButton.setMessage(Component.literal("#" + (slotIndex + 1)));
            slotReferenceButton.setTooltip(state.expressionLocked()
                    ? Tooltip.create(state.craftingLockTooltip())
                    : Tooltip.create(Component.literal("#" + (slotIndex + 1))));
            slotReferenceButton.setPosition(leftPos + SLOT_CHIP_X, y);

            input.visible = true;
            input.setPosition(leftPos + THRESHOLD_X, y);
            input.active = enabled && !slot.thresholdLocked();
            input.setEditable(enabled && !slot.thresholdLocked());
            input.setTextColor(COLOR_INPUT_TEXT);
            input.setTextColorUneditable(COLOR_READONLY_INPUT_TEXT);
            if (!enabled) {
                input.setTextColor(COLOR_READONLY_INPUT_TEXT);
                input.setTextColorUneditable(COLOR_READONLY_INPUT_TEXT);
            }
            MultiLevelEmitterScreen.ThresholdSyncDecision thresholdDecision =
                    MultiLevelEmitterScreen.resolveThresholdSync(
                            input.getValue(),
                            input.isFocused() && input.slotIndex == slotIndex,
                            previousServerThresholdFor(slotIndex, slot.threshold()),
                            slot.threshold()
                    );
            input.setSlotIndex(slotIndex);
            if (!Objects.equals(input.getValue(), thresholdDecision.fieldValue())) {
                input.setValue(thresholdDecision.fieldValue());
            }
            if (slot.thresholdLocked()) {
                input.setTooltip(Tooltip.create(state.craftingLockTooltip()));
                if (input.isFocused()) {
                    input.setFocused(false);
                    clearFocus();
                }
            } else {
                input.setTooltip(null);
            }

            comparisonButton.visible = true;
            comparisonButton.active = enabled && !slot.comparisonLocked();
            comparisonButton.setPosition(leftPos + MODE_X, y);
            comparisonButton.setMessage(Component.literal(
                    MultiLevelEmitterScreen.comparisonModeLabel(slot.comparisonMode())
            ));
            if (slot.comparisonLocked()) {
                comparisonButton.setTooltip(Tooltip.create(state.craftingLockTooltip()));
            } else {
                comparisonButton.setTooltip(null);
            }

            fuzzyModeButton.visible = slot.showFuzzyControl() && !slot.showCraftingControl();
            fuzzyModeButton.active = enabled && slot.showFuzzyControl() && !slot.showCraftingControl();
            fuzzyModeButton.setPosition(leftPos + FUZZY_MODE_X + 6, y);
            fuzzyModeButton.setMatchingMode(slot.matchingMode());
            fuzzyModeButton.setMessage(Component.literal(slot.fuzzyShortLabel()));
            fuzzyModeButton.setTooltip(Tooltip.create(slot.fuzzyTooltip()));
            fuzzyModeButton.setTextColorOverride(slot.emphasizeFuzzyMode() ? COLOR_WARNING : -1);

            craftingModeButton.visible = slot.showCraftingControl();
            craftingModeButton.active = enabled && slot.showCraftingControl();
            craftingModeButton.setPosition(leftPos + FUZZY_MODE_X, y);
            craftingModeButton.setMessage(Component.literal(slot.craftingShortLabel()));
            craftingModeButton.setTooltip(Tooltip.create(slot.craftingTooltip()));
            craftingModeButton.setTextColorOverride(slot.emphasizeCraftingMode() ? COLOR_WARNING : -1);
        }
    }

    private void onExpressionDraftChanged(String value) {
        expressionDraftText = value == null ? "" : value;
        expressionDirty = !Objects.equals(expressionDraftText, menu.appliedExpressionText());
        helperStatus = PRECEDENCE_HINT;
        helperStatusColor = COLOR_HELPER;
        currentExpressionValidation = MultiLevelEmitterScreen.validateExpressionDraft(
                expressionDraftText,
                menu.configuredSlotCount(),
                menu::hasMarkedItem
        );
        updateValidationPresentation();
    }

    private void insertOperator(String operator) {
        SelectionRange selection = currentSelection();
        setExpressionDraft(MultiLevelEmitterScreen.insertOperator(
                expressionInput.getValue(),
                selection.start(),
                selection.end(),
                operator
        ));
    }

    private void wrapSelection() {
        SelectionRange selection = currentSelection();
        String wrapped = MultiLevelEmitterScreen.wrapSelectionWithParentheses(
                expressionInput.getValue(),
                selection.start(),
                selection.end()
        );
        int cursor = selection.isCollapsed() ? selection.start() + 1 : Math.min(wrapped.length(), selection.end() + 2);
        setExpressionDraft(wrapped, cursor);
    }

    private void insertSlotReference(int slotNumber) {
        SelectionRange selection = currentSelection();
        setExpressionDraft(MultiLevelEmitterScreen.insertSlotReference(
                expressionInput.getValue(),
                selection.start(),
                selection.end(),
                slotNumber
        ));
    }

    private void applyExpressionDraft() {
        if (!MultiLevelEmitterScreen.canApplyExpression(currentExpressionValidation)) {
            return;
        }
        menu.applyExpression(expressionInput.getValue());
        expressionDirty = false;
        helperStatus = PRECEDENCE_HINT;
        helperStatusColor = COLOR_HELPER;
        currentExpressionValidation = MultiLevelEmitterScreen.validateExpressionDraft(
                expressionInput.getValue(),
                menu.configuredSlotCount(),
                menu::hasMarkedItem
        );
        updateValidationPresentation();
    }

    private void setExpressionDraft(String newDraft) {
        setExpressionDraft(newDraft, newDraft == null ? 0 : newDraft.length());
    }

    private void setExpressionDraft(String newDraft, int cursorPosition) {
        String safeDraft = newDraft == null ? "" : newDraft;
        expressionInput.setValue(safeDraft);
        int clampedCursor = Math.max(0, Math.min(safeDraft.length(), cursorPosition));
        expressionInput.setCursorPosition(clampedCursor);
        expressionInput.setHighlightPos(clampedCursor);
        expressionInput.setFocused(true);
    }

    private FormattedCharSequence formatDraftText(String text, int displayOffset) {
        if (text == null || text.isEmpty()) {
            return FormattedCharSequence.EMPTY;
        }
        List<FormattedCharSequence> segments = new ArrayList<>();
        MultiLevelEmitterExpressionDiagnostic diagnostic = primaryDiagnostic();
        int diagnosticStart = diagnostic == null ? -1 : diagnostic.start();
        int diagnosticEnd = diagnostic == null ? -1 : diagnostic.end();
        int cursor = 0;
        while (cursor < text.length()) {
            int absoluteIndex = displayOffset + cursor;
            if (diagnostic != null
                    && diagnostic.severity() != MultiLevelEmitterExpressionDiagnostic.Severity.VALID
                    && absoluteIndex >= diagnosticStart
                    && absoluteIndex < diagnosticEnd) {
                int end = Math.min(text.length(), diagnosticEnd - displayOffset);
                segments.add(FormattedCharSequence.forward(
                        text.substring(cursor, end),
                        Style.EMPTY.withColor(diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.WARNING
                                ? COLOR_WARNING
                                : COLOR_INVALID)
                ));
                cursor = end;
                continue;
            }

            if (startsWithOperator(text, cursor)) {
                String operator = text.regionMatches(true, cursor, "AND", 0, 3)
                        ? text.substring(cursor, cursor + 3)
                        : text.substring(cursor, cursor + 2);
                segments.add(FormattedCharSequence.forward(operator, Style.EMPTY.withColor(COLOR_OPERATOR)));
                cursor += operator.length();
                continue;
            }

            char current = text.charAt(cursor);
            if (current == '(' || current == ')') {
                segments.add(FormattedCharSequence.forward(Character.toString(current), Style.EMPTY.withColor(COLOR_PAREN)));
                cursor++;
                continue;
            }

            int next = cursor + 1;
            while (next < text.length()
                    && !startsWithOperator(text, next)
                    && text.charAt(next) != '('
                    && text.charAt(next) != ')'
                    && (diagnostic == null
                    || displayOffset + next < diagnosticStart
                    || displayOffset + next >= diagnosticEnd)) {
                next++;
            }
            segments.add(FormattedCharSequence.forward(text.substring(cursor, next), Style.EMPTY));
            cursor = next;
        }
        return FormattedCharSequence.composite(segments);
    }

    private void updateValidationPresentation() {
        MultiLevelEmitterExpressionDiagnostic diagnostic = primaryDiagnostic();
        if (diagnostic == null) {
            currentValidationStatus = VALID_STATUS;
            currentValidationColor = COLOR_VALID;
            return;
        }
        currentValidationColor = switch (diagnostic.severity()) {
            case VALID -> COLOR_VALID;
            case WARNING -> COLOR_WARNING;
            case INVALID -> COLOR_INVALID;
        };
        currentValidationStatus = switch (diagnostic.code()) {
            case "valid_expression" -> VALID_STATUS;
            case "unmarked_slot" -> Component.translatable(
                    "gui.chexsonsaeutils.multi_level_emitter.unmarked_slot_status",
                    diagnostic.slotIndex() + 1
            );
            case "out_of_range_slot" -> Component.translatable(
                    "gui.chexsonsaeutils.multi_level_emitter.out_of_range_slot_status",
                    diagnostic.slotIndex() + 1
            );
            case "mixed_precedence_warning" -> WARNING_STATUS;
            default -> diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.WARNING
                    ? WARNING_STATUS
                    : diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.INVALID
                    ? INVALID_STATUS
                    : VALID_STATUS;
        };
    }

    private MultiLevelEmitterExpressionDiagnostic primaryDiagnostic() {
        return currentExpressionValidation == null ? null : currentExpressionValidation.primaryDiagnostic();
    }

    private SelectionRange currentSelection() {
        int cursor = expressionInput.getCursorPosition();
        int highlight = highlightPosition(expressionInput);
        return new SelectionRange(Math.min(cursor, highlight), Math.max(cursor, highlight));
    }

    private void clampScrollOffset(int visibleSlots) {
        scrollOffset = MultiLevelEmitterScreen.clampScrollOffset(scrollOffset, visibleSlots, MAX_RENDERED_ROWS);
    }

    private int visibleRows(int visibleSlots) {
        return Math.min(MAX_RENDERED_ROWS, Math.max(0, visibleSlots - scrollOffset));
    }

    private int maxScrollOffset(int visibleSlots) {
        return Math.max(0, visibleSlots - MAX_RENDERED_ROWS);
    }

    private int rowToSlotIndex(int row) {
        return scrollOffset + row;
    }

    private int rowBaseY(int row) {
        return ROW_TOP + row * ROW_HEIGHT;
    }

    private void resetLocalUiState() {
        scrollOffset = 0;
        expressionDraftText = menu.appliedExpressionText();
        expressionDirty = false;
        previousConfiguredSlots = menu.configuredSlotCount();
        helperStatus = PRECEDENCE_HINT;
        helperStatusColor = COLOR_HELPER;
        lastServerThresholds.clear();
        clearFocus();
    }

    private long previousServerThresholdFor(int slotIndex, long fallbackThreshold) {
        return slotIndex >= 0 && slotIndex < lastServerThresholds.size()
                ? lastServerThresholds.get(slotIndex)
                : fallbackThreshold;
    }

    private void rememberServerSnapshot(MultiLevelEmitterScreen.RuntimeScreenState state) {
        lastServerThresholds.clear();
        for (MultiLevelEmitterScreen.SlotView slot : state.slots()) {
            lastServerThresholds.add(slot.threshold());
        }
        previousConfiguredSlots = state.configuredSlots();
    }

    private void clearFocus() {
        setFocused(null);
    }

    private boolean isMouseOverConfigPanel(double mouseX, double mouseY) {
        return mouseX >= leftPos + CONFIG_PANEL_X
                && mouseX < leftPos + CONFIG_PANEL_X + CONFIG_PANEL_WIDTH
                && mouseY >= topPos + CONFIG_PANEL_Y
                && mouseY < topPos + CONFIG_PANEL_Y + CONFIG_PANEL_HEIGHT;
    }

    private List<Component> getCompatibleUpgrades() {
        var upgrades = menu.getUpgrades();
        if (upgrades == null) {
            return Collections.emptyList();
        }
        var tooltip = new ArrayList<Component>();
        tooltip.add(GuiText.CompatibleUpgrades.text());
        tooltip.addAll(Upgrades.getTooltipLinesForMachine(upgrades.getUpgradableItem()));
        return tooltip;
    }

    private static boolean startsWithOperator(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return false;
        }
        if (matchesOperator(text, index, "AND")) {
            return true;
        }
        return matchesOperator(text, index, "OR");
    }

    private static boolean matchesOperator(String text, int index, String operator) {
        int end = index + operator.length();
        if (end > text.length() || !text.regionMatches(true, index, operator, 0, operator.length())) {
            return false;
        }
        boolean leftBoundary = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
        boolean rightBoundary = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
        return leftBoundary && rightBoundary;
    }

    private static void drawScaled(
            GuiGraphics guiGraphics,
            Font font,
            Component text,
            int x,
            int y,
            int color,
            float scale
    ) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, text, 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private static void drawFittedText(
            GuiGraphics guiGraphics,
            Font font,
            Component text,
            int x,
            int y,
            int maxWidth,
            int color,
            float preferredScale,
            float minimumScale
    ) {
        if (maxWidth <= 0) {
            return;
        }
        float scale = fittedScale(font, text, maxWidth, preferredScale, minimumScale);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0f);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, text, 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private static void drawCenteredFittedText(
            GuiGraphics guiGraphics,
            Font font,
            Component text,
            int x,
            int y,
            int width,
            int height,
            int color,
            float preferredScale,
            float minimumScale
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = fittedScale(font, text, width, preferredScale, minimumScale);
        int scaledHeight = Math.max(1, Mth.ceil(font.lineHeight * scale));
        int drawY = y + Math.max(0, (height - scaledHeight) / 2);
        guiGraphics.enableScissor(x, y, x + width, y + height);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x + width / 2.0f, drawY, 0.0f);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, text, -font.width(text) / 2, 0, color, false);
        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();
    }

    private static float fittedScale(Font font, Component text, int maxWidth, float preferredScale, float minimumScale) {
        int textWidth = Math.max(1, font.width(text));
        float widthScale = maxWidth / (float) textWidth;
        return Mth.clamp(widthScale, minimumScale, preferredScale);
    }

    private static Icon fuzzyModeIcon(MultiLevelEmitterPart.MatchingMode mode) {
        MultiLevelEmitterPart.MatchingMode effective =
                mode == null ? MultiLevelEmitterPart.MatchingMode.STRICT : mode;
        return switch (effective) {
            case STRICT -> Icon.TOOLBAR_BUTTON_BACKGROUND;
            case IGNORE_ALL -> Icon.FUZZY_IGNORE;
            case PERCENT_99 -> Icon.FUZZY_PERCENT_99;
            case PERCENT_75 -> Icon.FUZZY_PERCENT_75;
            case PERCENT_50 -> Icon.FUZZY_PERCENT_50;
            case PERCENT_25 -> Icon.FUZZY_PERCENT_25;
        };
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            return ObfuscationReflectionHelper.findField(owner, name);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to access " + owner.getSimpleName() + "." + name, exception);
        }
    }

    private static void setSlotPosition(Slot slot, int x, int y) {
        try {
            SLOT_X_FIELD.setInt(slot, x);
            SLOT_Y_FIELD.setInt(slot, y);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to set slot position", exception);
        }
    }

    private static int highlightPosition(EditBox editBox) {
        try {
            return EDIT_BOX_HIGHLIGHT_FIELD.getInt(editBox);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read EditBox.highlightPos", exception);
        }
    }

    private record SelectionRange(int start, int end) {
        private boolean isCollapsed() {
            return start == end;
        }
    }

    private final class FuzzyModeButton extends Button {

        private MultiLevelEmitterPart.MatchingMode matchingMode = MultiLevelEmitterPart.MatchingMode.STRICT;
        private int textColorOverride = -1;

        private FuzzyModeButton(
                int x,
                int y,
                int width,
                int height,
                Component message,
                OnPress onPress,
                Tooltip tooltip
        ) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.setTooltip(tooltip);
        }

        private void setMatchingMode(MultiLevelEmitterPart.MatchingMode matchingMode) {
            this.matchingMode = matchingMode == null ? MultiLevelEmitterPart.MatchingMode.STRICT : matchingMode;
        }

        private void setTextColorOverride(int textColorOverride) {
            this.textColorOverride = textColorOverride;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            if (!visible) {
                return;
            }

            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();

            if (isFocused()) {
                guiGraphics.fill(getX() - 1, getY() - 1, getX() + width + 1, getY(), 0xFFFFFFFF);
                guiGraphics.fill(getX() - 1, getY(), getX(), getY() + height, 0xFFFFFFFF);
                guiGraphics.fill(getX() + width, getY(), getX() + width + 1, getY() + height, 0xFFFFFFFF);
                guiGraphics.fill(getX() - 1, getY() + height, getX() + width + 1, getY() + height + 1, 0xFFFFFFFF);
            }

            Blitter background = Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter();
            if (!active) {
                background.opacity(0.5f);
            }
            background.dest(getX(), getY()).blit(guiGraphics);

            if (matchingMode == MultiLevelEmitterPart.MatchingMode.STRICT) {
                drawCenteredFittedText(
                        guiGraphics,
                        font,
                        getMessage(),
                        getX() + 1,
                        getY() + 1,
                        getWidth() - 2,
                        getHeight() - 2,
                        active
                                ? textColorOverride >= 0 ? textColorOverride : 0xFFFFFF
                                : 0xA0A0A0,
                        0.72f,
                        0.52f
                );
            } else {
                Blitter icon = fuzzyModeIcon(matchingMode).getBlitter();
                if (!active) {
                    icon.opacity(0.5f);
                }
                icon.dest(getX(), getY()).blit(guiGraphics);
            }

            RenderSystem.enableDepthTest();
        }
    }

    private static final class FittedTextButton extends Button {

        private final float preferredScale;
        private final float minimumScale;
        private int textColorOverride = -1;

        private FittedTextButton(
                int x,
                int y,
                int width,
                int height,
                Component message,
                OnPress onPress,
                float preferredScale,
                float minimumScale,
                Tooltip tooltip
        ) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.preferredScale = preferredScale;
            this.minimumScale = minimumScale;
            this.setTooltip(tooltip);
        }

        @Override
        public void renderString(GuiGraphics guiGraphics, Font font, int color) {
            drawCenteredFittedText(
                    guiGraphics,
                    font,
                    getMessage(),
                    getX() + 2,
                    getY(),
                    getWidth() - 4,
                    getHeight(),
                    textColorOverride >= 0 ? textColorOverride : color,
                    preferredScale,
                    minimumScale
            );
        }

        private void setTextColorOverride(int textColorOverride) {
            this.textColorOverride = textColorOverride;
        }
    }

    private final class ThresholdEditBox extends EditBox {

        private int slotIndex;

        private ThresholdEditBox(Font font, int x, int y, int width, int height, int slotIndex) {
            super(font, x, y, width, height, Component.literal("threshold-" + slotIndex));
            this.slotIndex = slotIndex;
        }

        private void setSlotIndex(int slotIndex) {
            this.slotIndex = slotIndex;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitThreshold(true, false);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            boolean focusLost = isFocused() && !focused;
            super.setFocused(focused);
            if (focusLost) {
                commitThreshold(false, true);
            }
        }

        private void commitThreshold(boolean enterPressed, boolean focusLost) {
            if (!menu.isSlotConfigured(slotIndex)) {
                setValue(MultiLevelEmitterScreen.thresholdFieldValue(menu.thresholdForSlot(slotIndex)));
                return;
            }
            long fallback = menu.thresholdForSlot(slotIndex);
            long parsed = MultiLevelEmitterScreen.parseThresholdInput(getValue(), fallback);
            boolean committed = MultiLevelEmitterScreen.commitThresholdFromInput(
                    menu,
                    slotIndex,
                    parsed,
                    Long.MAX_VALUE,
                    enterPressed,
                    focusLost
            );
            if (committed) {
                setValue(MultiLevelEmitterScreen.thresholdFieldValue(menu.thresholdForSlot(slotIndex)));
            }
        }
    }
}
