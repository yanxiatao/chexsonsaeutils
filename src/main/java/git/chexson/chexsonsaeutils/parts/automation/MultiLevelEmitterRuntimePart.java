package git.chexson.chexsonsaeutils.parts.automation;

import appeng.api.config.FuzzyMode;
import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.networking.IGrid;
import appeng.api.networking.IStackWatcher;
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
import java.util.List;
import java.util.Map;

/**
 * Dedicated runtime type for the MultiLevelEmitter feature path.
 */
public class MultiLevelEmitterRuntimePart extends StorageLevelEmitterPart {
    private static final String NBT_CONFIG = "config";
    static final String NBT_CONFIGURED_ITEM_COUNT = "configured_item_count";
    static final String NBT_REPORTING_VALUES = "reportingValues";
    static final String NBT_COMPARISON_MODES = "comparison_modes";
    static final String NBT_LOGIC_RELATIONS = "logic_relations";
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
    private String appliedExpressionText = "";
    private MultiLevelEmitterExpressionOwnership expressionOwnership = MultiLevelEmitterExpressionOwnership.AUTO;
    private MultiLevelEmitterExpressionCompileResult expressionCompileResult;
    private MultiLevelEmitterExpressionPlan compiledExpressionPlan;
    private RedstoneMode redstoneMode = RedstoneMode.HIGH_SIGNAL;
    private boolean suppressConfigInventoryCallback;

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

    @Override
    public ConfigInventory getConfig() {
        return ensureConfigInventory();
    }

    @Override
    protected void configureWatchers() {
        IStackWatcher storageWatcher = getWatcher(STORAGE_WATCHER_FIELD);
        if (storageWatcher != null) {
            storageWatcher.reset();
            for (AEKey key : configuredKeys()) {
                storageWatcher.add(key);
            }
        }

        IStackWatcher craftingWatcher = getWatcher(CRAFTING_WATCHER_FIELD);
        if (craftingWatcher != null) {
            craftingWatcher.reset();
        }

        refreshRuntimeState(false);
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

        return evaluateConfiguredOutput(readObservedValues(grid), true);
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
        List<Boolean> slotResults = MultiLevelEmitterPart.evaluateSlotComparisons(
                normalizedObservedValues,
                thresholds,
                comparisonModes
        );
        boolean evaluationResult;
        if (compiledExpressionPlan != null) {
            evaluationResult = compiledExpressionPlan.evaluate(slotResults);
        } else if (expressionIsInvalid()) {
            evaluationResult = false;
        } else {
            evaluationResult = MultiLevelEmitterPart.evaluateFinalResult(slotResults, relations);
        }
        return MultiLevelEmitterPart.resolveEmitterState(
                networkActive,
                normalizedObservedValues.size(),
                evaluationResult,
                currentRedstoneMode()
        );
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
                appliedExpressionText,
                expressionOwnership,
                refreshRuntimeState
        );
    }

    private void writeRuntimeSnapshot(CompoundTag data) {
        data.putInt(NBT_CONFIGURED_ITEM_COUNT, configuredItemCount);
        ensureConfigInventory().writeToChildTag(data, NBT_CONFIG);
        MultiLevelEmitterPart.writeThresholdsToNbt(thresholds, data, NBT_REPORTING_VALUES);
        MultiLevelEmitterUtils.writeComparisonModesToNBT(comparisonModes, data, NBT_COMPARISON_MODES);
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(relations, data, NBT_LOGIC_RELATIONS);
        data.putString(NBT_EXPRESSION_TEXT, appliedExpressionText);
        data.putString(NBT_EXPRESSION_OWNERSHIP, expressionOwnership.name());
    }

    private void readRuntimeSnapshot(CompoundTag data, boolean refreshRuntimeState) {
        if (data == null) {
            boolean previous = suppressConfigInventoryCallback;
            suppressConfigInventoryCallback = true;
            ensureConfigInventory().clear();
            suppressConfigInventoryCallback = previous;
            applyConfigurationState(DEFAULT_VISIBLE_SLOT_COUNT, null, null, null, refreshRuntimeState);
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

    private List<AEKey> configuredKeys() {
        List<AEKey> keys = new ArrayList<>(configuredItemCount);
        for (int slot = 0; slot < configuredItemCount; slot++) {
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
        boolean fuzzyEnabled = supportsFuzzyMatching();
        FuzzyMode fuzzyMode = fuzzyEnabled
                ? getConfigManager().getSetting(Settings.FUZZY_MODE)
                : FuzzyMode.IGNORE_ALL;
        for (int slot = 0; slot < configuredItemCount; slot++) {
            AEKey key = ensureConfigInventory().getKey(slot);
            if (key == null) {
                observedValues.add(0L);
                continue;
            }

            long amount = 0L;
            if (fuzzyEnabled) {
                for (var entry : inventory.findFuzzy(key, fuzzyMode)) {
                    amount += entry.getLongValue();
                }
            } else {
                amount = inventory.get(key);
            }
            observedValues.add(amount);
        }
        return observedValues;
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

    private boolean supportsFuzzyMatching() {
        try {
            return isUpgradedWith(AEItems.FUZZY_CARD) && getConfigManager().hasSetting(Settings.FUZZY_MODE);
        } catch (RuntimeException ignored) {
            return false;
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
