package git.chexson.chexsonsaeutils.parts.automation;

import appeng.api.config.FuzzyMode;
import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.networking.IGrid;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEItems;
import appeng.parts.automation.StorageLevelEmitterPart;
import appeng.util.ConfigInventory;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompileResult;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompiler;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionFormatter;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionOwnership;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dedicated runtime type for the MultiLevelEmitter feature path.
 */
public class MultiLevelEmitterRuntimePart extends StorageLevelEmitterPart {
    private static final String NBT_CONFIG = "config";
    static final String NBT_CONFIGURED_ITEM_COUNT = "configured_item_count";
    static final String NBT_REPORTING_VALUES = "reportingValues";
    static final String NBT_COMPARISON_MODES = "comparison_modes";
    static final String NBT_LOGIC_RELATIONS = "logic_relations";
    static final String NBT_MATCHING_MODES = "matching_modes";
    static final String NBT_CRAFTING_MODES = "crafting_modes";
    static final String NBT_EXPRESSION_TEXT = "expression_text";
    static final String NBT_EXPRESSION_OWNERSHIP = "expression_ownership";
    public static final int DEFAULT_VISIBLE_SLOT_COUNT = 1;
    private static final Field STORAGE_WATCHER_FIELD = resolveField("storageWatcher");
    private static final Field CRAFTING_WATCHER_FIELD = resolveField("craftingWatcher");
    private static final ThreadLocal<MultiLevelEmitterRuntimePart> PUBLISHED_MENU_RUNTIME = new ThreadLocal<>();

    private ConfigInventory configInventory;
    private int configuredItemCount = DEFAULT_VISIBLE_SLOT_COUNT;
    private Map<Integer, Long> thresholds = new LinkedHashMap<>();
    private List<MultiLevelEmitterPart.ComparisonMode> comparisonModes = List.of();
    private List<MultiLevelEmitterPart.LogicRelation> relations = List.of();
    private List<MultiLevelEmitterPart.MatchingMode> requestedMatchingModes = List.of();
    private List<MultiLevelEmitterPart.MatchingMode> matchingModes = List.of();
    private List<MultiLevelEmitterPart.CraftingMode> requestedCraftingModes = List.of();
    private List<MultiLevelEmitterPart.CraftingMode> craftingModes = List.of();
    private String appliedExpressionText = "";
    private MultiLevelEmitterExpressionOwnership expressionOwnership = MultiLevelEmitterExpressionOwnership.AUTO;
    private MultiLevelEmitterExpressionCompileResult expressionCompileResult;
    private MultiLevelEmitterExpressionPlan compiledExpressionPlan;
    private RedstoneMode redstoneMode = RedstoneMode.HIGH_SIGNAL;
    private boolean suppressConfigInventoryCallback;
    private boolean cardCapabilityStateInitialized;
    private boolean lastKnownFuzzyCardInstalled;
    private boolean lastKnownCraftingCardInstalled;

    public MultiLevelEmitterRuntimePart(IPartItem<?> partItem) {
        super(partItem);
        applyConfigurationState(DEFAULT_VISIBLE_SLOT_COUNT, null, null, null, false);
    }

    @Override
    public boolean onPartActivate(Player player, InteractionHand hand, Vec3 hitPos) {
        if (isClientSide()) {
            return true;
        }
        publishForMenuOpen(this);
        try {
            if (player instanceof ServerPlayer serverPlayer) {
                MultiLevelEmitterMenu.openMenu(serverPlayer, this);
            }
        } finally {
            consumePublishedMenuRuntime();
        }
        return true;
    }

    @Override
    public void readFromNBT(CompoundTag data) {
        super.readFromNBT(data);
        readRuntimeSnapshot(data, false);
    }

    @Override
    public void writeToNBT(CompoundTag data) {
        super.writeToNBT(data);
        if (data == null) {
            return;
        }
        writeRuntimeSnapshot(data);
    }

    @Override
    public void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
        CompoundTag snapshot = new CompoundTag();
        writeRuntimeSnapshot(snapshot);
        data.writeNbt(snapshot);
    }

    @Override
    public boolean readFromStream(FriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        CompoundTag snapshot = data.readNbt();
        if (snapshot != null) {
            readRuntimeSnapshot(snapshot, false);
            return true;
        }
        return changed;
    }

    public void applyConfiguration(
            int configuredItemCount,
            Map<Integer, Long> persistedThresholds,
            List<MultiLevelEmitterPart.ComparisonMode> persistedComparisons,
            List<MultiLevelEmitterPart.LogicRelation> persistedRelations
    ) {
        applyConfigurationState(
                configuredItemCount,
                persistedThresholds,
                persistedComparisons,
                persistedRelations,
                true
        );
    }

    public void updateConfiguredItemCountFromUi(int configuredItemCount) {
        applyConfiguration(configuredItemCount, thresholds, comparisonModes, relations);
    }

    public void applyExpressionFromUi(String rawExpression) {
        expressionOwnership = MultiLevelEmitterExpressionOwnership.CUSTOM;
        recompileExpression(rawExpression, MultiLevelEmitterExpressionOwnership.CUSTOM);
        refreshRuntimeState(true);
    }

    public void updateThresholdFromUi(int slotIndex, long threshold) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return;
        }
        Map<Integer, Long> updatedThresholds = new LinkedHashMap<>(thresholds);
        updatedThresholds.put(slotIndex, threshold);
        applyConfiguration(configuredItemCount, updatedThresholds, comparisonModes, relations);
    }

    public void cycleComparisonModeFromUi(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return;
        }
        List<MultiLevelEmitterPart.ComparisonMode> updatedModes = new ArrayList<>(comparisonModes);
        MultiLevelEmitterPart.ComparisonMode current = updatedModes.get(slotIndex);
        updatedModes.set(slotIndex, MultiLevelEmitterMenu.nextComparisonMode(current));
        applyConfiguration(configuredItemCount, thresholds, updatedModes, relations);
    }

    public void cycleMatchingModeFromUi(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return;
        }
        List<MultiLevelEmitterPart.MatchingMode> updatedModes = new ArrayList<>(requestedMatchingModes);
        MultiLevelEmitterPart.MatchingMode current = updatedModes.get(slotIndex);
        updatedModes.set(slotIndex, MultiLevelEmitterPart.nextMatchingMode(current));
        applyConfigurationState(
                configuredItemCount,
                thresholds,
                comparisonModes,
                relations,
                updatedModes,
                requestedCraftingModes,
                appliedExpressionText,
                expressionOwnership,
                true
        );
    }

    public void cycleCraftingModeFromUi(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return;
        }
        List<MultiLevelEmitterPart.CraftingMode> updatedModes = new ArrayList<>(requestedCraftingModes);
        MultiLevelEmitterPart.CraftingMode current = updatedModes.get(slotIndex);
        updatedModes.set(slotIndex, MultiLevelEmitterPart.nextCraftingMode(current));
        applyConfigurationState(
                configuredItemCount,
                thresholds,
                comparisonModes,
                relations,
                requestedMatchingModes,
                updatedModes,
                appliedExpressionText,
                expressionOwnership,
                true
        );
    }

    @Override
    public ConfigInventory getConfig() {
        return ensureConfigInventory();
    }

    @Override
    protected void configureWatchers() {
        reconcileCardModes();
        IStackWatcher storageWatcher = getWatcher(STORAGE_WATCHER_FIELD);
        if (storageWatcher != null) {
            storageWatcher.reset();
            if (hasMarkedNonStrictStorageSlot()) {
                storageWatcher.setWatchAll(true);
            } else {
                for (AEKey key : storageCountedKeys()) {
                    storageWatcher.add(key);
                }
            }
        }

        IStackWatcher craftingWatcher = getWatcher(CRAFTING_WATCHER_FIELD);
        if (craftingWatcher != null) {
            craftingWatcher.reset();
            for (AEKey key : requestStateCraftingKeys()) {
                craftingWatcher.add(key);
            }
        }

        requestCraftingProviderUpdate();
        refreshRuntimeState(false);
    }

    @Override
    public Set<AEKey> getEmitableItems() {
        if (!hasCraftingCardInstalled()) {
            return Set.of();
        }
        return Set.copyOf(emitToCraftKeys());
    }

    @Override
    protected boolean isLevelEmitterOn() {
        if (isClientSide()) {
            return super.isLevelEmitterOn();
        }

        if (!getMainNode().isActive()) {
            return false;
        }

        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return false;
        }

        return evaluateConfiguredOutput(grid, true);
    }

    public void setRedstoneMode(RedstoneMode redstoneMode) {
        this.redstoneMode = redstoneMode == null ? RedstoneMode.HIGH_SIGNAL : redstoneMode;
    }

    public static void publishForMenuOpen(MultiLevelEmitterRuntimePart runtimePart) {
        if (runtimePart == null) {
            PUBLISHED_MENU_RUNTIME.remove();
            return;
        }
        PUBLISHED_MENU_RUNTIME.set(runtimePart);
    }

    public static MultiLevelEmitterRuntimePart consumePublishedMenuRuntime() {
        MultiLevelEmitterRuntimePart runtimePart = PUBLISHED_MENU_RUNTIME.get();
        PUBLISHED_MENU_RUNTIME.remove();
        return runtimePart;
    }

    public boolean evaluateConfiguredOutput(List<Long> observedValues, boolean networkActive) {
        List<Long> normalizedObservedValues =
                MultiLevelEmitterUtils.normalizeObservedValuesForSlotCount(observedValues, configuredItemCount);
        List<MultiLevelEmitterPart.SlotEvaluation> slotResults =
                MultiLevelEmitterPart.evaluateSlotComparisonsWithParticipation(
                        normalizedObservedValues,
                        thresholds,
                        comparisonModes
                );
        MultiLevelEmitterPart.AggregationResult evaluationResult;
        if (compiledExpressionPlan != null) {
            evaluationResult = compiledExpressionPlan.evaluateParticipating(slotResults);
        } else if (expressionIsInvalid()) {
            evaluationResult = new MultiLevelEmitterPart.AggregationResult(normalizedObservedValues.size(), false);
        } else {
            evaluationResult = MultiLevelEmitterPart.evaluateFinalResultWithParticipation(slotResults, relations);
        }
        return MultiLevelEmitterPart.resolveEmitterState(
                networkActive,
                normalizedObservedValues.size(),
                evaluationResult,
                currentRedstoneMode()
        );
    }

    public boolean evaluateConfiguredOutput(IGrid grid, boolean networkActive) {
        List<MultiLevelEmitterPart.SlotEvaluation> slotResults = readSlotEvaluations(grid);
        if (compiledExpressionPlan != null) {
            return MultiLevelEmitterPart.resolveEmitterState(
                    networkActive,
                    configuredItemCount,
                    compiledExpressionPlan.evaluateParticipating(slotResults),
                    currentRedstoneMode()
            );
        }
        if (expressionIsInvalid()) {
            return MultiLevelEmitterPart.resolveEmitterState(
                    networkActive,
                    configuredItemCount,
                    false,
                    currentRedstoneMode()
            );
        }
        return MultiLevelEmitterPart.resolveEmitterState(
                networkActive,
                configuredItemCount,
                MultiLevelEmitterPart.evaluateFinalResultWithParticipation(slotResults, relations),
                currentRedstoneMode()
        );
    }

    private List<MultiLevelEmitterPart.SlotEvaluation> readSlotEvaluations(IGrid grid) {
        KeyCounter inventory = grid.getStorageService().getCachedInventory();
        ICraftingService craftingService = grid.getCraftingService();
        List<MultiLevelEmitterPart.SlotEvaluation> slotResults = new ArrayList<>(configuredItemCount);
        for (int slot = 0; slot < configuredItemCount; slot++) {
            AEKey key = ensureConfigInventory().getKey(slot);
            MultiLevelEmitterPart.CraftingMode craftingMode = craftingModeForSlot(slot);
            if (craftingMode == MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                    || craftingMode == MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT) {
                if (key == null) {
                    slotResults.add(MultiLevelEmitterPart.SlotEvaluation.inactive());
                    continue;
                }
                slotResults.add(MultiLevelEmitterPart.SlotEvaluation.participating(
                        craftingService != null && craftingService.isRequesting(key)
                ));
                continue;
            }

            long amount = 0L;
            if (key != null) {
                MultiLevelEmitterPart.MatchingMode matchingMode = matchingModeForSlot(slot);
                amount = matchingMode == MultiLevelEmitterPart.MatchingMode.STRICT
                        ? inventory.get(key)
                        : readFuzzyAmount(inventory, key, toFuzzyMode(matchingMode));
            }
            long threshold = thresholds.getOrDefault(slot, 1L);
            MultiLevelEmitterPart.ComparisonMode comparisonMode = slot < comparisonModes.size()
                    ? comparisonModes.get(slot)
                    : MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL;
            slotResults.add(MultiLevelEmitterPart.SlotEvaluation.participating(
                    MultiLevelEmitterPart.evaluateComparison(amount, threshold, comparisonMode)
            ));
        }
        return List.copyOf(slotResults);
    }

    public int configuredItemCount() {
        return configuredItemCount;
    }

    public int markedItemCount() {
        int count = 0;
        ConfigInventory config = ensureConfigInventory();
        for (int slot = 0; slot < configuredItemCount; slot++) {
            if (config.getKey(slot) != null) {
                count++;
            }
        }
        return count;
    }

    public boolean hasConfiguredItem(int slotIndex) {
        return slotIndex >= 0
                && slotIndex < configuredItemCount
                && ensureConfigInventory().getKey(slotIndex) != null;
    }

    public ItemStack configuredItemStack(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = ensureConfigInventory().createMenuWrapper().getStackInSlot(slotIndex);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public Map<Integer, Long> thresholds() {
        return new LinkedHashMap<>(thresholds);
    }

    public List<MultiLevelEmitterPart.ComparisonMode> comparisonModes() {
        return List.copyOf(comparisonModes);
    }

    public List<MultiLevelEmitterPart.LogicRelation> relations() {
        return List.copyOf(relations);
    }

    public boolean hasFuzzyCardInstalled() {
        try {
            return isUpgradedWith(AEItems.FUZZY_CARD);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasCraftingCardInstalled() {
        try {
            return isUpgradedWith(AEItems.CRAFTING_CARD);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public MultiLevelEmitterPart.MatchingMode matchingModeForSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return MultiLevelEmitterPart.MatchingMode.STRICT;
        }
        return slotIndex < matchingModes.size()
                ? matchingModes.get(slotIndex)
                : MultiLevelEmitterPart.MatchingMode.STRICT;
    }

    public MultiLevelEmitterPart.CraftingMode craftingModeForSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return MultiLevelEmitterPart.CraftingMode.NONE;
        }
        return slotIndex < craftingModes.size()
                ? craftingModes.get(slotIndex)
                : MultiLevelEmitterPart.CraftingMode.NONE;
    }

    public boolean hasDuplicateEmitToCraftTarget(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredItemCount) {
            return false;
        }
        if (craftingModeForSlot(slotIndex) != MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT) {
            return false;
        }
        AEKey targetKey = ensureConfigInventory().getKey(slotIndex);
        if (targetKey == null) {
            return false;
        }
        for (int candidate = 0; candidate < configuredItemCount; candidate++) {
            if (candidate == slotIndex) {
                continue;
            }
            if (craftingModeForSlot(candidate) != MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT) {
                continue;
            }
            if (Objects.equals(targetKey, ensureConfigInventory().getKey(candidate))) {
                return true;
            }
        }
        return false;
    }

    public String appliedExpressionText() {
        return appliedExpressionText;
    }

    public MultiLevelEmitterExpressionOwnership expressionOwnership() {
        return expressionOwnership;
    }

    public boolean expressionIsInvalid() {
        return expressionCompileResult != null && expressionCompileResult.isInvalid();
    }

    private ConfigInventory ensureConfigInventory() {
        if (configInventory == null) {
            configInventory = ConfigInventory.configTypes(
                    MultiLevelEmitterMenu.SLOT_CAPACITY,
                    this::onConfigInventoryChanged
            );
        }
        return configInventory;
    }

    private void trimConfigInventoryToConfiguredSlots(int configuredSlots) {
        ConfigInventory config = ensureConfigInventory();
        boolean previous = suppressConfigInventoryCallback;
        suppressConfigInventoryCallback = true;
        config.beginBatch();
        try {
            for (int slot = configuredSlots; slot < config.size(); slot++) {
                if (config.getStack(slot) != null) {
                    config.setStack(slot, null);
                }
            }
        } finally {
            config.endBatch();
            suppressConfigInventoryCallback = previous;
        }
    }

    private void applyConfigurationState(
            int configuredItemCount,
            Map<Integer, Long> persistedThresholds,
            List<MultiLevelEmitterPart.ComparisonMode> persistedComparisons,
            List<MultiLevelEmitterPart.LogicRelation> persistedRelations,
            List<MultiLevelEmitterPart.MatchingMode> persistedMatchingModes,
            List<MultiLevelEmitterPart.CraftingMode> persistedCraftingModes,
            String persistedExpressionText,
            MultiLevelEmitterExpressionOwnership persistedOwnership,
            boolean refreshRuntimeState
    ) {
        int previousSlotCount = this.configuredItemCount;
        int normalizedSlotCount = Math.max(
                DEFAULT_VISIBLE_SLOT_COUNT,
                Math.min(MultiLevelEmitterMenu.SLOT_CAPACITY, configuredItemCount)
        );
        this.configuredItemCount = normalizedSlotCount;
        this.thresholds = new LinkedHashMap<>(
                MultiLevelEmitterPart.normalizeThresholdsForSlotCount(persistedThresholds, normalizedSlotCount)
        );
        this.comparisonModes = List.copyOf(
                MultiLevelEmitterPart.normalizeComparisonModesForSlotCount(persistedComparisons, normalizedSlotCount)
        );
        this.relations = List.copyOf(
                MultiLevelEmitterPart.normalizeRelationsForSlotCount(persistedRelations, normalizedSlotCount)
        );
        boolean fuzzyCardInstalled = hasFuzzyCardInstalled();
        boolean craftingCardInstalled = hasCraftingCardInstalled();
        this.requestedMatchingModes = List.copyOf(reconcileRequestedMatchingModes(
                persistedMatchingModes,
                normalizedSlotCount,
                fuzzyCardInstalled
        ));
        this.requestedCraftingModes = List.copyOf(reconcileRequestedCraftingModes(
                persistedCraftingModes,
                normalizedSlotCount,
                craftingCardInstalled
        ));
        this.matchingModes = List.copyOf(
                MultiLevelEmitterPart.normalizeMatchingModesForSlotCount(
                        requestedMatchingModes,
                        normalizedSlotCount,
                        fuzzyCardInstalled
                )
        );
        this.craftingModes = List.copyOf(
                MultiLevelEmitterPart.normalizeCraftingModesForSlotCount(
                        requestedCraftingModes,
                        normalizedSlotCount,
                        craftingCardInstalled
                )
        );
        this.cardCapabilityStateInitialized = true;
        this.lastKnownFuzzyCardInstalled = fuzzyCardInstalled;
        this.lastKnownCraftingCardInstalled = craftingCardInstalled;
        trimConfigInventoryToConfiguredSlots(normalizedSlotCount);
        synchronizeExpressionState(previousSlotCount, persistedExpressionText, persistedOwnership);
        if (refreshRuntimeState) {
            refreshRuntimeState(true);
        }
    }

    private void applyConfigurationState(
            int configuredItemCount,
            Map<Integer, Long> persistedThresholds,
            List<MultiLevelEmitterPart.ComparisonMode> persistedComparisons,
            List<MultiLevelEmitterPart.LogicRelation> persistedRelations,
            boolean refreshRuntimeState
    ) {
        applyConfigurationState(
                configuredItemCount,
                persistedThresholds,
                persistedComparisons,
                persistedRelations,
                requestedMatchingModes,
                requestedCraftingModes,
                appliedExpressionText,
                expressionOwnership,
                refreshRuntimeState
        );
    }

    private void reconcileCardModes() {
        applyConfigurationState(
                configuredItemCount,
                thresholds,
                comparisonModes,
                relations,
                requestedMatchingModes,
                requestedCraftingModes,
                appliedExpressionText,
                expressionOwnership,
                false
        );
    }

    private void writeRuntimeSnapshot(CompoundTag data) {
        data.putInt(NBT_CONFIGURED_ITEM_COUNT, configuredItemCount);
        ensureConfigInventory().writeToChildTag(data, NBT_CONFIG);
        MultiLevelEmitterPart.writeThresholdsToNbt(thresholds, data, NBT_REPORTING_VALUES);
        MultiLevelEmitterUtils.writeComparisonModesToNBT(comparisonModes, data, NBT_COMPARISON_MODES);
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(relations, data, NBT_LOGIC_RELATIONS);
        MultiLevelEmitterUtils.writeMatchingModesToNBT(requestedMatchingModes, data, NBT_MATCHING_MODES);
        MultiLevelEmitterUtils.writeCraftingModesToNBT(requestedCraftingModes, data, NBT_CRAFTING_MODES);
        data.putString(NBT_EXPRESSION_TEXT, appliedExpressionText);
        data.putString(NBT_EXPRESSION_OWNERSHIP, expressionOwnership.name());
    }

    private void readRuntimeSnapshot(CompoundTag data, boolean refreshRuntimeState) {
        if (data == null) {
            boolean previous = suppressConfigInventoryCallback;
            suppressConfigInventoryCallback = true;
            ensureConfigInventory().clear();
            suppressConfigInventoryCallback = previous;
            applyConfigurationState(
                    DEFAULT_VISIBLE_SLOT_COUNT,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    MultiLevelEmitterExpressionOwnership.AUTO,
                    refreshRuntimeState
            );
            return;
        }
        boolean previous = suppressConfigInventoryCallback;
        suppressConfigInventoryCallback = true;
        if (data.contains(NBT_CONFIG)) {
            ensureConfigInventory().readFromChildTag(data, NBT_CONFIG);
        } else {
            ensureConfigInventory().clear();
        }
        suppressConfigInventoryCallback = previous;
        applyConfigurationState(
                Math.max(DEFAULT_VISIBLE_SLOT_COUNT, data.getInt(NBT_CONFIGURED_ITEM_COUNT)),
                MultiLevelEmitterPart.readThresholdsFromNbt(data, NBT_REPORTING_VALUES),
                MultiLevelEmitterUtils.readComparisonModesFromNBT(data, NBT_COMPARISON_MODES),
                MultiLevelEmitterUtils.readLogicRelationsFromNBT(data, NBT_LOGIC_RELATIONS),
                MultiLevelEmitterUtils.readMatchingModesFromNBT(data, NBT_MATCHING_MODES),
                MultiLevelEmitterUtils.readCraftingModesFromNBT(data, NBT_CRAFTING_MODES),
                readPersistedExpressionText(data),
                readPersistedExpressionOwnership(data),
                refreshRuntimeState
        );
    }

    private void synchronizeExpressionState(
            int previousSlotCount,
            String persistedExpressionText,
            MultiLevelEmitterExpressionOwnership persistedOwnership
    ) {
        MultiLevelEmitterExpressionOwnership normalizedOwnership =
                persistedOwnership == null ? MultiLevelEmitterExpressionOwnership.AUTO : persistedOwnership;
        boolean slotCountChanged = previousSlotCount != configuredItemCount;
        boolean missingPersistedExpression = persistedExpressionText == null;
        if (missingPersistedExpression || normalizedOwnership == MultiLevelEmitterExpressionOwnership.AUTO) {
            expressionOwnership = MultiLevelEmitterExpressionOwnership.AUTO;
            recompileExpression(
                    MultiLevelEmitterExpressionFormatter.defaultExpressionForSlots(configuredItemCount),
                    MultiLevelEmitterExpressionOwnership.AUTO
            );
            return;
        }
        if (!slotCountChanged || normalizedOwnership == MultiLevelEmitterExpressionOwnership.CUSTOM) {
            recompileExpression(persistedExpressionText, MultiLevelEmitterExpressionOwnership.CUSTOM);
        }
    }

    private void recompileExpression(String rawExpression, MultiLevelEmitterExpressionOwnership ownership) {
        appliedExpressionText = rawExpression == null
                ? MultiLevelEmitterExpressionFormatter.defaultExpressionForSlots(configuredItemCount)
                : rawExpression;
        expressionOwnership = ownership == null ? MultiLevelEmitterExpressionOwnership.AUTO : ownership;
        expressionCompileResult = MultiLevelEmitterExpressionCompiler.compile(
                appliedExpressionText,
                configuredItemCount,
                this::hasConfiguredItem
        );
        compiledExpressionPlan = expressionCompileResult == null || expressionCompileResult.isInvalid()
                ? null
                : expressionCompileResult.plan();
    }

    private static String readPersistedExpressionText(CompoundTag data) {
        if (data == null || !data.contains(NBT_EXPRESSION_TEXT)) {
            return null;
        }
        return data.getString(NBT_EXPRESSION_TEXT);
    }

    private static MultiLevelEmitterExpressionOwnership readPersistedExpressionOwnership(CompoundTag data) {
        if (data == null || !data.contains(NBT_EXPRESSION_OWNERSHIP)) {
            return MultiLevelEmitterExpressionOwnership.AUTO;
        }
        String raw = data.getString(NBT_EXPRESSION_OWNERSHIP);
        if (raw == null || raw.isBlank()) {
            return MultiLevelEmitterExpressionOwnership.AUTO;
        }
        try {
            return MultiLevelEmitterExpressionOwnership.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return MultiLevelEmitterExpressionOwnership.AUTO;
        }
    }

    private void onConfigInventoryChanged() {
        if (suppressConfigInventoryCallback) {
            return;
        }
        refreshRuntimeState(true);
    }

    private void refreshRuntimeState(boolean reconfigureWatchers) {
        if (!isClientSide()) {
            if (reconfigureWatchers) {
                configureWatchers();
                return;
            }
            updateState();
        }
        markRuntimeStateDirty();
    }

    private List<AEKey> storageCountedKeys() {
        List<AEKey> keys = new ArrayList<>(configuredItemCount);
        for (int slot = 0; slot < configuredItemCount; slot++) {
            if (!isStorageCountingSlot(slot)) {
                continue;
            }
            AEKey key = ensureConfigInventory().getKey(slot);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private List<AEKey> emitWhileCraftingKeys() {
        List<AEKey> keys = new ArrayList<>(configuredItemCount);
        for (int slot = 0; slot < configuredItemCount; slot++) {
            MultiLevelEmitterPart.CraftingMode craftingMode = craftingModeForSlot(slot);
            if (craftingMode != MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                    && craftingMode != MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT) {
                continue;
            }
            AEKey key = ensureConfigInventory().getKey(slot);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private List<AEKey> requestStateCraftingKeys() {
        return emitWhileCraftingKeys();
    }

    private Set<AEKey> emitToCraftKeys() {
        Set<AEKey> keys = new LinkedHashSet<>(configuredItemCount);
        for (int slot = 0; slot < configuredItemCount; slot++) {
            if (craftingModeForSlot(slot) != MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT) {
                continue;
            }
            AEKey key = ensureConfigInventory().getKey(slot);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private List<Long> readObservedValues(IGrid grid) {
        KeyCounter inventory = grid.getStorageService().getCachedInventory();
        List<Long> observedValues = new ArrayList<>(configuredItemCount);
        for (int slot = 0; slot < configuredItemCount; slot++) {
            AEKey key = ensureConfigInventory().getKey(slot);
            if (key == null) {
                observedValues.add(0L);
                continue;
            }

            MultiLevelEmitterPart.MatchingMode matchingMode = matchingModeForSlot(slot);
            long amount = matchingMode == MultiLevelEmitterPart.MatchingMode.STRICT
                    ? inventory.get(key)
                    : readFuzzyAmount(inventory, key, toFuzzyMode(matchingMode));
            observedValues.add(amount);
        }
        return observedValues;
    }

    private boolean hasMarkedNonStrictStorageSlot() {
        ConfigInventory config = ensureConfigInventory();
        for (int slot = 0; slot < configuredItemCount; slot++) {
            if (isStorageCountingSlot(slot)
                    && config.getKey(slot) != null
                    && matchingModeForSlot(slot) != MultiLevelEmitterPart.MatchingMode.STRICT) {
                return true;
            }
        }
        return false;
    }

    private boolean isStorageCountingSlot(int slot) {
        return craftingModeForSlot(slot) == MultiLevelEmitterPart.CraftingMode.NONE;
    }

    private List<MultiLevelEmitterPart.MatchingMode> reconcileRequestedMatchingModes(
            List<MultiLevelEmitterPart.MatchingMode> persistedMatchingModes,
            int normalizedSlotCount,
            boolean fuzzyCardInstalled
    ) {
        List<MultiLevelEmitterPart.MatchingMode> requestedModes =
                new ArrayList<>(MultiLevelEmitterPart.normalizeRequestedMatchingModesForSlotCount(
                        persistedMatchingModes,
                        normalizedSlotCount
                ));
        if (cardCapabilityStateInitialized && lastKnownFuzzyCardInstalled && !fuzzyCardInstalled) {
            for (int slot = 0; slot < requestedModes.size(); slot++) {
                requestedModes.set(slot, MultiLevelEmitterPart.MatchingMode.STRICT);
            }
        }
        return requestedModes;
    }

    private List<MultiLevelEmitterPart.CraftingMode> reconcileRequestedCraftingModes(
            List<MultiLevelEmitterPart.CraftingMode> persistedCraftingModes,
            int normalizedSlotCount,
            boolean craftingCardInstalled
    ) {
        List<MultiLevelEmitterPart.CraftingMode> requestedModes =
                new ArrayList<>(MultiLevelEmitterPart.normalizeRequestedCraftingModesForSlotCount(
                        persistedCraftingModes,
                        normalizedSlotCount
                ));
        if (cardCapabilityStateInitialized && lastKnownCraftingCardInstalled && !craftingCardInstalled) {
            for (int slot = 0; slot < requestedModes.size(); slot++) {
                requestedModes.set(slot, MultiLevelEmitterPart.CraftingMode.NONE);
            }
            return requestedModes;
        }
        if (cardCapabilityStateInitialized && !lastKnownCraftingCardInstalled && craftingCardInstalled) {
            ConfigInventory config = ensureConfigInventory();
            for (int slot = 0; slot < requestedModes.size(); slot++) {
                if (requestedModes.get(slot) == MultiLevelEmitterPart.CraftingMode.NONE && config.getKey(slot) != null) {
                    requestedModes.set(slot, MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING);
                }
            }
        }
        return requestedModes;
    }

    private static long readFuzzyAmount(KeyCounter inventory, AEKey key, FuzzyMode fuzzyMode) {
        long amount = 0L;
        for (var entry : inventory.findFuzzy(key, fuzzyMode)) {
            amount += entry.getLongValue();
        }
        return amount;
    }

    private static FuzzyMode toFuzzyMode(MultiLevelEmitterPart.MatchingMode matchingMode) {
        return switch (matchingMode == null ? MultiLevelEmitterPart.MatchingMode.STRICT : matchingMode) {
            case STRICT -> throw new IllegalArgumentException("STRICT does not map to an AE2 fuzzy mode");
            case IGNORE_ALL -> FuzzyMode.IGNORE_ALL;
            case PERCENT_99 -> FuzzyMode.PERCENT_99;
            case PERCENT_75 -> FuzzyMode.PERCENT_75;
            case PERCENT_50 -> FuzzyMode.PERCENT_50;
            case PERCENT_25 -> FuzzyMode.PERCENT_25;
        };
    }

    private RedstoneMode currentRedstoneMode() {
        try {
            if (getConfigManager().hasSetting(Settings.REDSTONE_EMITTER)) {
                return getConfigManager().getSetting(Settings.REDSTONE_EMITTER);
            }
        } catch (RuntimeException ignored) {
            return redstoneMode;
        }
        return redstoneMode;
    }

    private void requestCraftingProviderUpdate() {
        try {
            if (getMainNode() == null || getMainNode().getNode() == null || getMainNode().getNode().getGrid() == null) {
                return;
            }
            ICraftingProvider.requestUpdate(getMainNode());
        } catch (RuntimeException ignored) {
        }
    }

    private IStackWatcher getWatcher(Field field) {
        try {
            return (IStackWatcher) field.get(this);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read AE2 watcher field", exception);
        }
    }

    private static Field resolveField(String fieldName) {
        try {
            Field field = StorageLevelEmitterPart.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve StorageLevelEmitterPart." + fieldName, exception);
        }
    }

    private void markRuntimeStateDirty() {
        if (getHost() != null) {
            getHost().markForSave();
            getHost().markForUpdate();
        }
    }
}
