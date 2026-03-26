package git.chexson.chexsonsaeutils.menu.implementations;

import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompileResult;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompiler;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionFormatter;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionOwnership;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MultiLevelEmitterScreen {

    private static final String REGISTRATION_KEY = MultiLevelEmitterItem.SCREEN_BINDING_KEY;
    private static final String FUZZY_MODE_KEY = "gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode";
    private static final String CRAFTING_MODE_KEY = "gui.chexsonsaeutils.multi_level_emitter.crafting_mode";
    private static final String CRAFTING_LOCK_HELPER_KEY =
            "gui.chexsonsaeutils.multi_level_emitter.crafting_lock_helper";
    private static final String CRAFTING_LOCK_TOOLTIP_KEY =
            "gui.chexsonsaeutils.multi_level_emitter.crafting_lock_tooltip";
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("(?i)\\b(?:AND|OR)\\b");
    private static final Pattern SLOT_REFERENCE_PATTERN = Pattern.compile("#\\d+");
    private static final AtomicReference<MenuType<MultiLevelEmitterMenu.RuntimeMenu>> menuType = new AtomicReference<>();
    private static final AtomicReference<ThresholdCommitHandler> thresholdCommitHandler =
            new AtomicReference<>(MultiLevelEmitterScreen::commitThresholdToMenu);
    private static final AtomicBoolean CLIENT_BINDINGS_REGISTERED = new AtomicBoolean(false);

    private MultiLevelEmitterScreen() {
    }

    public static String registrationKey() {
        return REGISTRATION_KEY;
    }

    @FunctionalInterface
    public interface ThresholdCommitHandler {
        void commit(MultiLevelEmitterMenu.RuntimeMenu menu, int slotIndex, long threshold, long maxValue);
    }

    public record SlotView(
            int slotIndex,
            boolean enabled,
            boolean configured,
            boolean marked,
            long threshold,
            boolean thresholdLocked,
            MultiLevelEmitterPart.ComparisonMode comparisonMode,
            boolean comparisonLocked,
            MultiLevelEmitterPart.MatchingMode matchingMode,
            MultiLevelEmitterPart.CraftingMode craftingMode,
            boolean showFuzzyControl,
            boolean emphasizeFuzzyMode,
            String fuzzyShortLabel,
            Component fuzzyTooltip,
            boolean showCraftingControl,
            boolean emphasizeCraftingMode,
            String craftingShortLabel,
            Component craftingTooltip,
            boolean duplicateEmitToCraftTarget
    ) {
    }

    public record RuntimeScreenState(
            int configuredSlots,
            int markedSlots,
            int visibleSlots,
            int totalSlots,
            String appliedExpressionText,
            MultiLevelEmitterExpressionOwnership expressionOwnership,
            boolean expressionInvalid,
            boolean expressionLocked,
            Component craftingLockTooltip,
            List<SlotView> slots
    ) {
    }

    public record ThresholdSyncDecision(String fieldValue, boolean preserveLocalDraft) {
    }

    public record ExpressionDraftSyncDecision(String draftText, boolean dirty, boolean topologyReset) {
    }

    public static void registerClientBindings() {
        CLIENT_BINDINGS_REGISTERED.set(menuType.get() != null && thresholdCommitHandler.get() != null);
    }

    public static void registerClientBindings(
            MenuType<MultiLevelEmitterMenu.RuntimeMenu> menuType,
            ThresholdCommitHandler thresholdCommitHandler
    ) {
        MultiLevelEmitterScreen.menuType.set(Objects.requireNonNull(menuType, "menuType"));
        MultiLevelEmitterScreen.thresholdCommitHandler.set(
                Objects.requireNonNull(thresholdCommitHandler, "thresholdCommitHandler")
        );
        registerClientBindings();
    }

    public static boolean hasClientBindingsRegistered() {
        return CLIENT_BINDINGS_REGISTERED.get();
    }

    public static boolean shouldCommitThresholdOnInput(boolean enterPressed, boolean focusLost) {
        return enterPressed || focusLost;
    }

    public static boolean commitThresholdFromInput(
            MultiLevelEmitterMenu.RuntimeMenu menu,
            int slotIndex,
            long rawThreshold,
            long maxValue,
            boolean enterPressed,
            boolean focusLost
    ) {
        if (menu == null || !shouldCommitThresholdOnInput(enterPressed, focusLost)) {
            return false;
        }
        ThresholdCommitHandler commitHandler = thresholdCommitHandler.get();
        if (commitHandler == null) {
            return false;
        }
        long normalized = normalizeThresholdForCommit(rawThreshold, maxValue);
        commitHandler.commit(menu, slotIndex, normalized, maxValue);
        return true;
    }

    public static void applyConfiguredSlotCount(MultiLevelEmitterMenu.RuntimeMenu menu, int configuredSlots) {
        if (menu != null) {
            menu.setConfiguredSlotCount(clampConfiguredSlots(configuredSlots, menu.totalSlotCapacity()));
        }
    }

    public static void toggleComparisonMode(MultiLevelEmitterMenu.RuntimeMenu menu, int slotIndex) {
        if (menu != null) {
            menu.cycleComparisonMode(slotIndex);
        }
    }

    public static void toggleMatchingMode(MultiLevelEmitterMenu.RuntimeMenu menu, int slotIndex) {
        if (menu != null) {
            menu.cycleMatchingMode(slotIndex);
        }
    }

    public static void toggleCraftingMode(MultiLevelEmitterMenu.RuntimeMenu menu, int slotIndex) {
        if (menu != null) {
            menu.cycleCraftingMode(slotIndex);
        }
    }

    public static long normalizeThresholdForCommit(long rawThreshold, long maxValue) {
        return MultiLevelEmitterMenu.sanitizeAndClampThreshold(rawThreshold, maxValue);
    }

    public static int clampConfiguredSlots(int configuredSlots, int capacity) {
        return Math.max(MultiLevelEmitterRuntimePart.DEFAULT_VISIBLE_SLOT_COUNT, Math.min(Math.max(1, capacity), configuredSlots));
    }

    public static int nextConfiguredSlotCount(int currentConfiguredSlots, int delta, int capacity) {
        return clampConfiguredSlots(currentConfiguredSlots + delta, capacity);
    }

    public static long preserveThresholdOnMatchingModeToggle(long currentThreshold) {
        return currentThreshold;
    }

    public static long parseThresholdInput(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static String thresholdFieldValue(long threshold) {
        return Long.toString(Math.max(0L, threshold));
    }

    public static ThresholdSyncDecision resolveThresholdSync(
            String localFieldValue,
            boolean editingThisField,
            long previousServerThreshold,
            long latestServerThreshold
    ) {
        boolean preserveLocalDraft = editingThisField && previousServerThreshold == latestServerThreshold;
        String resolvedFieldValue = preserveLocalDraft
                ? localFieldValue == null ? "" : localFieldValue
                : thresholdFieldValue(latestServerThreshold);
        return new ThresholdSyncDecision(resolvedFieldValue, preserveLocalDraft);
    }

    public static ExpressionDraftSyncDecision resolveExpressionDraftSync(
            String localDraftText,
            boolean draftDirty,
            String appliedExpressionText,
            int previousConfiguredSlots,
            int latestConfiguredSlots
    ) {
        String authoritativeDraftText = appliedExpressionText == null ? "" : appliedExpressionText;
        boolean topologyChanged = previousConfiguredSlots != latestConfiguredSlots;
        boolean topologyReset = topologyChanged && draftDirty;
        if (topologyChanged) {
            return new ExpressionDraftSyncDecision(authoritativeDraftText, false, topologyReset);
        }
        if (draftDirty) {
            return new ExpressionDraftSyncDecision(localDraftText == null ? "" : localDraftText, true, false);
        }
        return new ExpressionDraftSyncDecision(authoritativeDraftText, false, false);
    }

    public static int clampScrollOffset(int currentScrollOffset, int visibleSlots, int maxRenderedRows) {
        int normalizedVisibleSlots = Math.max(0, visibleSlots);
        int normalizedRenderedRows = Math.max(1, maxRenderedRows);
        int maxScrollOffset = Math.max(0, normalizedVisibleSlots - normalizedRenderedRows);
        return Math.max(0, Math.min(currentScrollOffset, maxScrollOffset));
    }

    public static String comparisonModeLabel(MultiLevelEmitterPart.ComparisonMode mode) {
        MultiLevelEmitterPart.ComparisonMode effective =
                mode == null ? MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL : mode;
        return switch (effective) {
            case GREATER_OR_EQUAL -> ">=";
            case LESS_THAN -> "<";
            case EQUAL -> "=";
            case NOT_EQUAL -> "!=";
        };
    }

    public static String fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode mode) {
        MultiLevelEmitterPart.MatchingMode effective =
                mode == null ? MultiLevelEmitterPart.MatchingMode.STRICT : mode;
        return switch (effective) {
            case STRICT -> "STR";
            case IGNORE_ALL -> "*";
            case PERCENT_99 -> "99%";
            case PERCENT_75 -> "75%";
            case PERCENT_50 -> "50%";
            case PERCENT_25 -> "25%";
        };
    }

    public static Component fuzzyTooltip(MultiLevelEmitterPart.MatchingMode mode) {
        return Component.translatable(FUZZY_MODE_KEY)
                .append("\n")
                .append(Component.translatable(fuzzyModeDetailKey(mode)));
    }

    public static String craftingShortLabel(MultiLevelEmitterPart.CraftingMode mode) {
        MultiLevelEmitterPart.CraftingMode effective =
                mode == null ? MultiLevelEmitterPart.CraftingMode.NONE : mode;
        return switch (effective) {
            case NONE -> "OFF";
            case EMIT_WHILE_CRAFTING -> "REQ";
            case EMIT_TO_CRAFT -> "SUP";
        };
    }

    public static Component craftingTooltip(
            MultiLevelEmitterPart.CraftingMode mode,
            boolean marked,
            boolean duplicateEmitToCraftTarget
    ) {
        Component tooltip = Component.translatable(CRAFTING_MODE_KEY, craftingModeDetail(mode));
        if (!marked) {
            tooltip = tooltip.copy()
                    .append("\n")
                    .append(Component.translatable(CRAFTING_MODE_KEY + ".unmarked_hint"));
        }
        if (duplicateEmitToCraftTarget) {
            tooltip = tooltip.copy()
                    .append("\n")
                    .append(Component.translatable(CRAFTING_MODE_KEY + ".duplicate_emit_to_craft"));
        }
        return tooltip;
    }

    public static MultiLevelEmitterExpressionCompileResult validateExpressionDraft(
            String rawText,
            int configuredSlots,
            IntPredicate markedSlotPredicate
    ) {
        return MultiLevelEmitterExpressionCompiler.compile(rawText, configuredSlots, markedSlotPredicate);
    }

    public static String insertOperator(String rawText, int selectionStart, int selectionEnd, String operator) {
        String safeRawText = rawText == null ? "" : rawText;
        String normalizedOperator = normalizeOperator(operator);
        TextRange selection = normalizeSelection(safeRawText, selectionStart, selectionEnd);
        TextRange replacementRange = selection.isEmpty()
                ? findTokenAt(safeRawText, selection.start(), OPERATOR_PATTERN).orElse(selection)
                : findCoveredToken(safeRawText, selection, OPERATOR_PATTERN).orElse(selection);
        return replaceRange(safeRawText, replacementRange.start(), replacementRange.end(), normalizedOperator, true, true);
    }

    public static String wrapSelectionWithParentheses(String rawText, int selectionStart, int selectionEnd) {
        String safeRawText = rawText == null ? "" : rawText;
        TextRange selection = normalizeSelection(safeRawText, selectionStart, selectionEnd);
        if (selection.isEmpty()) {
            return safeRawText.substring(0, selection.start()) + "()" + safeRawText.substring(selection.end());
        }
        return safeRawText.substring(0, selection.start())
                + "("
                + safeRawText.substring(selection.start(), selection.end())
                + ")"
                + safeRawText.substring(selection.end());
    }

    public static String insertSlotReference(String rawText, int selectionStart, int selectionEnd, int slotNumber) {
        String safeRawText = rawText == null ? "" : rawText;
        String replacement = "#" + Math.max(1, slotNumber);
        TextRange selection = normalizeSelection(safeRawText, selectionStart, selectionEnd);
        TextRange replacementRange = selection.isEmpty()
                ? findTokenAt(safeRawText, selection.start(), SLOT_REFERENCE_PATTERN).orElse(selection)
                : findCoveredToken(safeRawText, selection, SLOT_REFERENCE_PATTERN).orElse(selection);
        return replaceRange(safeRawText, replacementRange.start(), replacementRange.end(), replacement, false, false);
    }

    public static String formatDraftExpression(String rawText) {
        return MultiLevelEmitterExpressionFormatter.format(rawText);
    }

    public static boolean canApplyExpression(MultiLevelEmitterExpressionCompileResult compileResult) {
        return compileResult != null && !compileResult.isInvalid();
    }

    public static RuntimeScreenState snapshotState(MultiLevelEmitterMenu.RuntimeMenu menu) {
        if (menu == null) {
            return new RuntimeScreenState(
                    0,
                    0,
                    0,
                    0,
                    "",
                    MultiLevelEmitterExpressionOwnership.AUTO,
                    false,
                    false,
                    Component.empty(),
                    List.of()
            );
        }
        int configuredSlots = menu.configuredSlotCount();
        int markedSlots = menu.markedSlotCount();
        int visibleSlots = menu.visibleSlotCount();
        int totalSlots = menu.totalSlotCapacity();
        boolean craftingCardInstalled = menu.hasCraftingCardInstalled();
        Component craftingLockTooltip = craftingLockTooltip();
        List<SlotView> slots = new ArrayList<>(visibleSlots);
        for (int slotIndex = 0; slotIndex < visibleSlots; slotIndex++) {
            MultiLevelEmitterPart.MatchingMode matchingMode = menu.matchingModeForSlot(slotIndex);
            boolean marked = menu.hasMarkedItem(slotIndex);
            MultiLevelEmitterPart.CraftingMode craftingMode = menu.craftingModeForSlot(slotIndex);
            boolean duplicateEmitToCraftTarget = menu.duplicateEmitToCraftTarget(slotIndex);
            slots.add(new SlotView(
                    slotIndex,
                    menu.isSlotEnabled(slotIndex),
                    menu.isSlotConfigured(slotIndex),
                    marked,
                    menu.thresholdForSlot(slotIndex),
                    craftingCardInstalled && craftingMode != MultiLevelEmitterPart.CraftingMode.NONE,
                    menu.comparisonModeForSlot(slotIndex),
                    craftingCardInstalled && craftingMode != MultiLevelEmitterPart.CraftingMode.NONE,
                    matchingMode,
                    craftingMode,
                    menu.hasFuzzyCardInstalled(),
                    matchingMode != MultiLevelEmitterPart.MatchingMode.STRICT,
                    fuzzyShortLabel(matchingMode),
                    fuzzyTooltip(matchingMode),
                    craftingCardInstalled,
                    craftingCardInstalled && craftingMode != MultiLevelEmitterPart.CraftingMode.NONE,
                    craftingShortLabel(craftingMode),
                    craftingTooltip(craftingMode, marked, duplicateEmitToCraftTarget),
                    duplicateEmitToCraftTarget
            ));
        }
        return new RuntimeScreenState(
                configuredSlots,
                markedSlots,
                visibleSlots,
                totalSlots,
                menu.appliedExpressionText(),
                menu.expressionOwnership(),
                menu.expressionIsInvalid(),
                false,
                craftingLockTooltip,
                List.copyOf(slots)
        );
    }

    private static void commitThresholdToMenu(
            MultiLevelEmitterMenu.RuntimeMenu menu,
            int slotIndex,
            long threshold,
            long maxValue
    ) {
        menu.commitThreshold(slotIndex, threshold, maxValue);
    }

    public static String configuredOverTotalLabel(int configured, int total) {
        int safeConfigured = Math.max(0, configured);
        int safeTotal = Math.max(0, total);
        return safeConfigured + "/" + safeTotal;
    }

    private static String fuzzyModeDetailKey(MultiLevelEmitterPart.MatchingMode mode) {
        MultiLevelEmitterPart.MatchingMode effective =
                mode == null ? MultiLevelEmitterPart.MatchingMode.STRICT : mode;
        return switch (effective) {
            case STRICT -> FUZZY_MODE_KEY + ".strict";
            case IGNORE_ALL -> FUZZY_MODE_KEY + ".ignore_all";
            case PERCENT_99 -> FUZZY_MODE_KEY + ".percent_99";
            case PERCENT_75 -> FUZZY_MODE_KEY + ".percent_75";
            case PERCENT_50 -> FUZZY_MODE_KEY + ".percent_50";
            case PERCENT_25 -> FUZZY_MODE_KEY + ".percent_25";
        };
    }

    private static Component craftingModeDetail(MultiLevelEmitterPart.CraftingMode mode) {
        MultiLevelEmitterPart.CraftingMode effective =
                mode == null ? MultiLevelEmitterPart.CraftingMode.NONE : mode;
        return switch (effective) {
            case NONE -> Component.translatable(CRAFTING_MODE_KEY + ".disabled");
            case EMIT_WHILE_CRAFTING -> Component.translatable(CRAFTING_MODE_KEY + ".while_crafting");
            case EMIT_TO_CRAFT -> Component.translatable(CRAFTING_MODE_KEY + ".to_craft");
        };
    }

    public static Component craftingLockHelper() {
        return Component.translatable(CRAFTING_LOCK_HELPER_KEY);
    }

    public static Component craftingLockTooltip() {
        return Component.translatable(CRAFTING_LOCK_TOOLTIP_KEY);
    }

    private static String normalizeOperator(String operator) {
        String safeOperator = operator == null ? "" : operator.trim().toUpperCase();
        if (!"AND".equals(safeOperator) && !"OR".equals(safeOperator)) {
            throw new IllegalArgumentException("Unsupported operator: " + operator);
        }
        return safeOperator;
    }

    private static TextRange normalizeSelection(String rawText, int selectionStart, int selectionEnd) {
        int safeLength = rawText.length();
        int start = Math.max(0, Math.min(safeLength, Math.min(selectionStart, selectionEnd)));
        int end = Math.max(0, Math.min(safeLength, Math.max(selectionStart, selectionEnd)));
        return new TextRange(start, end);
    }

    private static String replaceRange(
            String rawText,
            int start,
            int end,
            String replacement,
            boolean padWithSpaces,
            boolean trimAdjacentWhitespace
    ) {
        int leftTrimmedStart = start;
        int rightTrimmedEnd = end;
        if (trimAdjacentWhitespace) {
            while (leftTrimmedStart > 0 && Character.isWhitespace(rawText.charAt(leftTrimmedStart - 1))) {
                leftTrimmedStart--;
            }
            while (rightTrimmedEnd < rawText.length() && Character.isWhitespace(rawText.charAt(rightTrimmedEnd))) {
                rightTrimmedEnd++;
            }
        }
        String prefix = rawText.substring(0, leftTrimmedStart);
        String suffix = rawText.substring(rightTrimmedEnd);
        StringBuilder rewritten = new StringBuilder(rawText.length() + replacement.length() + 2);
        rewritten.append(prefix);
        if (padWithSpaces && needsLeadingSpace(prefix)) {
            rewritten.append(' ');
        }
        rewritten.append(replacement);
        if (padWithSpaces && needsTrailingSpace(suffix)) {
            rewritten.append(' ');
        }
        rewritten.append(suffix);
        return rewritten.toString();
    }

    private static boolean needsLeadingSpace(String prefix) {
        return !prefix.isEmpty() && !Character.isWhitespace(prefix.charAt(prefix.length() - 1)) && prefix.charAt(prefix.length() - 1) != '(';
    }

    private static boolean needsTrailingSpace(String suffix) {
        return !suffix.isEmpty() && !Character.isWhitespace(suffix.charAt(0)) && suffix.charAt(0) != ')';
    }

    private static java.util.Optional<TextRange> findTokenAt(String rawText, int caret, Pattern pattern) {
        Matcher matcher = pattern.matcher(rawText);
        while (matcher.find()) {
            if (caret >= matcher.start() && caret <= matcher.end()) {
                return java.util.Optional.of(new TextRange(matcher.start(), matcher.end()));
            }
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<TextRange> findCoveredToken(String rawText, TextRange selection, Pattern pattern) {
        Matcher matcher = pattern.matcher(rawText);
        while (matcher.find()) {
            if (selection.start() <= matcher.start() && selection.end() >= matcher.end()) {
                return java.util.Optional.of(new TextRange(matcher.start(), matcher.end()));
            }
        }
        return java.util.Optional.empty();
    }

    private record TextRange(int start, int end) {
        private boolean isEmpty() {
            return start == end;
        }
    }
}
