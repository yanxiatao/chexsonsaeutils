package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.ManagedGridNode;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public abstract class AbstractHighCapacityCraftingHostBlockEntity extends AEBaseBlockEntity
        implements IGridConnectedBlockEntity, PatternContainer, ICraftingProvider, InternalInventoryHost {

    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_EXECUTION_QUEUE = "executionQueue";
    private static final String NBT_BASE_TICKS = "baseTicks";
    private static final String NBT_BATCH_MODE = "batchMode";
    private static final String NBT_ACTIVE_PAGE = "activePage";
    private static final String NBT_LAST_SEARCH_QUERY = "lastSearchQuery";
    private static final String NBT_SEARCH_RESULT_COUNT = "searchResultCount";
    private static final String NBT_HIGHLIGHTED_GLOBAL_SLOT = "highlightedGlobalSlot";
    private static final String NBT_HIGHLIGHTED_PAGE_SLOT_MASK = "highlightedPageSlotMask";
    protected static final int TOTAL_PATTERN_SLOTS = 16_384;
    protected static final int PAGE_SIZE = 27;
    private static final int UPGRADE_SLOTS = 5;
    private static final int DEFAULT_BASE_TICKS = 20;
    private static final int LOCAL_EXECUTION_QUEUE_CAPACITY = 1_024;

    private final ManagedGridNode mainNode;
    protected final MachineSource actionSource = new MachineSource(this);
    protected final DirtySlotPatternRefreshScheduler refreshScheduler =
            new DirtySlotPatternRefreshScheduler(TOTAL_PATTERN_SLOTS);
    protected final DecodedPatternEntryCache decodedPatternEntryCache =
            new DecodedPatternEntryCache(TOTAL_PATTERN_SLOTS);
    protected final PagedPatternInventory pagedPatternInventory =
            new PagedPatternInventory(this, refreshScheduler, TOTAL_PATTERN_SLOTS, PAGE_SIZE);
    protected final LocalPatternProviderFacade localPatternProviderFacade =
            new LocalPatternProviderFacade(this, pagedPatternInventory, refreshScheduler, decodedPatternEntryCache);
    protected final LocalExecutionQueue localExecutionQueue = new LocalExecutionQueue(LOCAL_EXECUTION_QUEUE_CAPACITY);
    private final Supplier<ItemStack> representativeItemSupplier;
    private final IUpgradeInventory upgrades;
    private final PatternSearchIndex patternSearchIndex = new PatternSearchIndex();
    private final VirtualPatternInventoryContainer automationContainer = new VirtualPatternInventoryContainer(this);
    private final VirtualPatternItemHandler automationItemHandler = new VirtualPatternItemHandler(automationContainer);
    private final String pageStatusTranslationKey;

    private int baseOperationTicks = DEFAULT_BASE_TICKS;
    private long jobsSubmitted;
    private long jobsCompleted;

    // TODO Sprint 2: pendingCompletionQueue, pendingAeReturn, completion templates
    // TODO Sprint 3: formal machine dispatch, formalMachineDispatchHost field

    @SuppressWarnings("FieldCanBeLocal")
    private final boolean formalMachineDispatchHost;
    private BatchExecutionMode batchExecutionMode;
    private String lastSearchQuery = "";
    private int searchResultCount;
    private int highlightedGlobalSlot = -1;
    private int highlightedPageSlotMask;
    private boolean externalPatternsLoaded;
    private boolean providerRefreshAfterReadyPending;
    private int lastEffectiveLaneCount = 1;
    private int lastCompletionBudget = 1;
    private int lastDispatchBudget = 1;
    private int lastCompletionSliceBudget = 1;
    private int lastSoftBudget = 1;
    private int lastHardBudget = 1;
    private DynamicExecutionBudgetModel currentBudgetModel =
            new DynamicExecutionBudgetModel(1, 1, 1, 1, 1, 1, 1, 0, 0, 0, false);

    protected AbstractHighCapacityCraftingHostBlockEntity(
            net.minecraft.world.level.block.entity.BlockEntityType<?> blockEntityType,
            BlockPos pos,
            BlockState blockState,
            Supplier<ItemStack> representativeItemSupplier,
            String pageStatusTranslationKey,
            BatchExecutionMode initialBatchExecutionMode,
            boolean formalMachineDispatchHost
    ) {
        super(blockEntityType, pos, blockState);
        this.mainNode = new ManagedGridNode(this, new Listener())
                .setIdlePowerUsage(0.0)
                .setInWorldNode(true)
                .setFlags(GridFlags.REQUIRE_CHANNEL);
        this.representativeItemSupplier = representativeItemSupplier;
        this.pageStatusTranslationKey = pageStatusTranslationKey;
        this.formalMachineDispatchHost = formalMachineDispatchHost;
        this.batchExecutionMode = initialBatchExecutionMode == null
                ? BatchExecutionMode.OFF
                : initialBatchExecutionMode;
        this.upgrades = UpgradeInventories.forMachine(
                representativeItemSupplier.get().getItem(),
                UPGRADE_SLOTS,
                this::saveChanges
        );
        this.mainNode.addService(ICraftingProvider.class, this);
    }

    // ──────────────────────────────────────────────
    //  IGridConnectedBlockEntity
    // ──────────────────────────────────────────────

    @Override
    public ManagedGridNode getMainNode() {
        return mainNode;
    }

    @Override
    public Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        return EnumSet.allOf(Direction.class);
    }

    @Override
    public void onReady() {
        super.onReady();
        this.mainNode.create(getLevel(), getBlockPos());
        loadExternalPatternsIfNeeded();
        localPatternProviderFacade.markAllDirty();
        recalculateBudgetModel();
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        localPatternProviderFacade.refreshDirtyPatterns();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    @Override
    public void setRemoved() {
        this.mainNode.destroy();
        super.setRemoved();
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        this.mainNode.loadFromNBT(data);
        upgrades.readFromNBT(data, NBT_UPGRADES);
        localExecutionQueue.readFromTag(data, NBT_EXECUTION_QUEUE);
        baseOperationTicks = data.contains(NBT_BASE_TICKS) ? Math.max(1, data.getInt(NBT_BASE_TICKS)) : DEFAULT_BASE_TICKS;
        batchExecutionMode = data.contains(NBT_BATCH_MODE)
                ? BatchExecutionMode.valueOf(data.getString(NBT_BATCH_MODE))
                : BatchExecutionMode.OFF;
        pagedPatternInventory.setActivePage(data.contains(NBT_ACTIVE_PAGE) ? data.getInt(NBT_ACTIVE_PAGE) : 0);
        lastSearchQuery = data.contains(NBT_LAST_SEARCH_QUERY) ? data.getString(NBT_LAST_SEARCH_QUERY) : "";
        searchResultCount = data.contains(NBT_SEARCH_RESULT_COUNT) ? Math.max(0, data.getInt(NBT_SEARCH_RESULT_COUNT)) : 0;
        highlightedGlobalSlot = data.contains(NBT_HIGHLIGHTED_GLOBAL_SLOT)
                ? data.getInt(NBT_HIGHLIGHTED_GLOBAL_SLOT)
                : -1;
        highlightedPageSlotMask = data.contains(NBT_HIGHLIGHTED_PAGE_SLOT_MASK)
                ? data.getInt(NBT_HIGHLIGHTED_PAGE_SLOT_MASK)
                : computeHighlightedPageSlotMask(lastSearchQuery);
        externalPatternsLoaded = false;
        refreshScheduler.clear();
        decodedPatternEntryCache.clear();
        localPatternProviderFacade.clear();
        localPatternProviderFacade.markAllDirty();
        loadPatternSlotsFromTag(data);
        recalculateBudgetModel();
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        this.mainNode.saveToNBT(data);
        upgrades.writeToNBT(data, NBT_UPGRADES);
        localExecutionQueue.writeToTag(data, NBT_EXECUTION_QUEUE);
        data.putInt(NBT_BASE_TICKS, baseOperationTicks);
        data.putString(NBT_BATCH_MODE, batchExecutionMode.name());
        data.putInt(NBT_ACTIVE_PAGE, pagedPatternInventory.getActivePage());
        data.putString(NBT_LAST_SEARCH_QUERY, lastSearchQuery);
        data.putInt(NBT_SEARCH_RESULT_COUNT, searchResultCount);
        data.putInt(NBT_HIGHLIGHTED_GLOBAL_SLOT, highlightedGlobalSlot);
        data.putInt(NBT_HIGHLIGHTED_PAGE_SLOT_MASK, highlightedPageSlotMask);
        savePatternSlotsToTag(data);
    }

    public void saveChangedInventory(AppEngInternalInventory inventory) {
        saveChanges();
    }

    public void onChangeInventory(AppEngInternalInventory inventory, int slot) {
        if (inventory == pagedPatternInventory.getActivePageInventory()) {
            pagedPatternInventory.onActivePageSlotChanged(slot);
            int globalSlot = pagedPatternInventory.toGlobalSlotIndex(slot);
            persistPatternSlot(globalSlot);
            onPatternSlotChanged(globalSlot);
        }
        saveChanges();
    }

    protected void onPatternSlotChanged(int globalSlot) {
        if (globalSlot == highlightedGlobalSlot) {
            highlightedGlobalSlot = -1;
        }
        highlightedPageSlotMask = computeHighlightedPageSlotMask(lastSearchQuery);
    }

    // ──────────────────────────────────────────────
    //  ICraftingProvider
    // ──────────────────────────────────────────────

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        localPatternProviderFacade.refreshDirtyPatterns();
        return localPatternProviderFacade.getAvailablePatterns();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (!this.getMainNode().isActive()) {
            return false;
        }
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
            return false;
        }
        // TODO Sprint 3: FormalMachineAggregatedPattern dispatch
        if (!localPatternProviderFacade.contains(patternDetails)) {
            return false;
        }
        return offerCompiledTask(patternDetails.getDefinition().toStack(), supportedPattern, inputHolder, 1);
    }

    private boolean offerCompiledTask(
            ItemStack definitionStack,
            IMolecularAssemblerSupportedPattern supportedPattern,
            KeyCounter[] inputHolder,
            int executionCount
    ) {
        CompiledTask compiledTask = compileProviderTask(supportedPattern, inputHolder, executionCount);
        if (compiledTask == null) {
            return false;
        }
        return offerPrecompiledTask(compiledTask);
    }

    @Nullable
    private CompiledTask compileProviderTask(
            IMolecularAssemblerSupportedPattern supportedPattern,
            KeyCounter[] inputHolder,
            int executionCount
    ) {
        return CompiledTask.compile(
                supportedPattern,
                inputHolder,
                getCurrentOperationTicksForExecution(),
                executionCount
        );
    }

    protected boolean offerPrecompiledTask(CompiledTask compiledTask) {
        if (compiledTask == null) {
            return false;
        }
        if (!localExecutionQueue.offer(compiledTask)) {
            return false;
        }
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        jobsSubmitted += compiledTask.getExecutionCount();
        saveChanges();
        return true;
    }

    @Override
    public boolean isBusy() {
        // TODO Sprint 2: check pendingCompletionQueue, pendingAeReturn
        if (batchExecutionMode == BatchExecutionMode.SAME_PATTERN_DRAIN) {
            return false;
        }
        return localExecutionQueue.isAtCapacity();
    }

    @Override
    public Set<AEKey> getEmitableItems() {
        return Set.of();
    }

    // ──────────────────────────────────────────────
    //  PatternContainer
    // ──────────────────────────────────────────────

    @Override
    public IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return pagedPatternInventory.getActivePageInventory();
    }

    @Override
    public long getTerminalSortOrder() {
        return pagedPatternInventory.getActivePage();
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(
                AEItemKey.of(representativeItemSupplier.get()),
                getName(),
                List.of(Component.translatable(
                        pageStatusTranslationKey,
                        pagedPatternInventory.getActivePage() + 1,
                        pagedPatternInventory.getPageCount()
                ))
        );
    }

    // ──────────────────────────────────────────────
    //  Upgrades (self-managed, no IUpgradeableObject)
    // ──────────────────────────────────────────────

    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    public int getInstalledUpgrades(net.minecraft.world.level.ItemLike item) {
        return upgrades.getInstalledUpgrades(item);
    }

    // ──────────────────────────────────────────────
    //  Drops / Clear
    // ──────────────────────────────────────────────

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int slot = 0; slot < pagedPatternInventory.getTotalSlots(); slot++) {
            ItemStack stack = pagedPatternInventory.getVirtualSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
        for (ItemStack upgrade : upgrades) {
            if (!upgrade.isEmpty()) {
                drops.add(upgrade.copy());
            }
        }
        addCustomDrops(level, pos, drops);
    }

    protected void addCustomDrops(Level level, BlockPos pos, List<ItemStack> drops) {
    }

    @Override
    public void clearContent() {
        super.clearContent();
        pagedPatternInventory.clear();
        upgrades.clear();
        localExecutionQueue.clear();
        refreshScheduler.clear();
        decodedPatternEntryCache.clear();
        localPatternProviderFacade.clear();
        lastSearchQuery = "";
        searchResultCount = 0;
        highlightedGlobalSlot = -1;
        highlightedPageSlotMask = 0;
        persistAllPatterns();
        clearCustomContent();
    }

    protected void clearCustomContent() {
    }

    // ──────────────────────────────────────────────
    //  Stub methods (called by sub-components)
    // ──────────────────────────────────────────────

    // TODO: implement in Sprint 2/3
    public boolean isLocalOptimizationEnabled() {
        return true;
    }

    // TODO: implement in Sprint 2/3
    public void recordDirtyRefreshScan(int count) {
    }

    // TODO: implement in Sprint 2/3
    public void recordProviderUpdate() {
    }

    // TODO: implement in Sprint 2/3
    public void recordDecodeCacheHit() {
    }

    // TODO: implement in Sprint 2/3
    public void recordLocalOptimizationHit() {
    }

    // TODO: implement in Sprint 2/3
    public void recordDecodeCall() {
    }

    // TODO: implement in Sprint 2/3
    public void clearPatternsForAutomation() {
    }

    // ──────────────────────────────────────────────
    //  Page navigation
    // ──────────────────────────────────────────────

    public void setActivePage(int pageIndex) {
        pagedPatternInventory.setActivePage(pageIndex);
        highlightedPageSlotMask = computeHighlightedPageSlotMask(lastSearchQuery);
        saveChanges();
    }

    public void nextPage() {
        setActivePage(pagedPatternInventory.getActivePage() + 1);
    }

    public void previousPage() {
        setActivePage(pagedPatternInventory.getActivePage() - 1);
    }

    public void gotoPage(int pageIndex) {
        setActivePage(pageIndex);
    }

    public int getPageIndex() {
        return pagedPatternInventory.getActivePage();
    }

    public int getPageCount() {
        return pagedPatternInventory.getPageCount();
    }

    public int getTotalPatternSlots() {
        return pagedPatternInventory.getTotalSlots();
    }

    public int getVisiblePatternSlots() {
        return pagedPatternInventory.getPageSize();
    }

    public int getActivePatternSlots() {
        return pagedPatternInventory.countActivePageNonEmptySlots();
    }

    public int getTotalNonEmptyPatternSlots() {
        return pagedPatternInventory.countAllNonEmptySlots();
    }

    public int getDecodedPatternCount() {
        return localPatternProviderFacade.activePatternCount();
    }

    // ──────────────────────────────────────────────
    //  Queue stats
    // ──────────────────────────────────────────────

    public int getQueuedTaskCount() {
        return localExecutionQueue.queuedTaskCount();
    }

    public int getRunningTaskCount() {
        return localExecutionQueue.runningTaskCount();
    }

    public int getDistinctBatchKeyCount() {
        return localExecutionQueue.countDistinctBatchKeys();
    }

    // ──────────────────────────────────────────────
    //  Speed / ticks
    // ──────────────────────────────────────────────

    public int getCurrentOperationTicks() {
        return computeCurrentOperationTicks();
    }

    private int getCurrentOperationTicksForExecution() {
        return computeCurrentOperationTicks();
    }

    private int computeCurrentOperationTicks() {
        int speedDivisor = 1 + getInstalledUpgrades(AEItems.SPEED_CARD);
        int operationTicks = baseOperationTicks / Math.max(1, speedDivisor);
        return Math.max(1, operationTicks);
    }

    public int getBaseOperationTicks() {
        return baseOperationTicks;
    }

    public void setBaseOperationTicksForTest(int ticks) {
        baseOperationTicks = Math.max(1, ticks);
        saveChanges();
    }

    // ──────────────────────────────────────────────
    //  Budget model
    // ──────────────────────────────────────────────

    public DynamicExecutionBudgetModel getCurrentBudgetModel() {
        return currentBudgetModel;
    }

    public int getInstalledSpeedCardCount() {
        return getInstalledUpgrades(AEItems.SPEED_CARD);
    }

    public int getPreferredLaneFloor() {
        return Math.max(1, computeLaneCount(getInstalledSpeedCardCount()));
    }

    public int getWaitingPenaltyForBudget() {
        return 0;
    }

    public boolean isWaitingAeReturn() {
        // TODO Sprint 2: check pendingAeReturn
        return false;
    }

    public int getLaneCount() {
        return Math.max(1, lastEffectiveLaneCount);
    }

    public int getPerTickWorkUnits() {
        return 1;
    }

    protected int computeLaneCount(int speedCards) {
        return Math.max(1, 1 + Math.min(3, Math.max(0, speedCards)));
    }

    protected int getMaxLaneCount() {
        return 4;
    }

    public int getCompletionBudgetPerTick() {
        return Math.max(1, lastCompletionBudget);
    }

    // ──────────────────────────────────────────────
    //  Complete task — MC 1.20.1 assembly
    // ──────────────────────────────────────────────

    public void completeTask(CompiledTask compiledTask) {
        if (compiledTask == null) {
            return;
        }
        // TODO Sprint 2: pendingAeReturn check, CPU-waiting route
        Level level = getLevel();
        if (level == null) {
            compiledTask.markFailed();
            saveChanges();
            return;
        }
        IMolecularAssemblerSupportedPattern pattern = compiledTask.resolvePattern(level);
        if (pattern == null) {
            compiledTask.markFailed();
            saveChanges();
            return;
        }
        // MC 1.20.1: assemble uses Container, not CraftingInput
        // TODO Sprint 2: completion template optimization
        CraftingContainer craftingContainer = createCraftingContainer(compiledTask);
        ItemStack output = pattern.assemble(craftingContainer, level);
        if (output.isEmpty()) {
            compiledTask.markFailed();
            saveChanges();
            return;
        }
        GenericStack outputStack = GenericStack.fromItemStack(output.copy());
        if (outputStack != null && outputStack.amount() > 0) {
            injectIntoNetwork(outputStack);
        }
        NonNullList<ItemStack> remainders = pattern.getRemainingItems(craftingContainer);
        for (ItemStack remainder : remainders) {
            if (!remainder.isEmpty()) {
                GenericStack remainderStack = GenericStack.fromItemStack(remainder.copy());
                if (remainderStack != null && remainderStack.amount() > 0) {
                    injectIntoNetwork(remainderStack);
                }
            }
        }
        compiledTask.markComplete();
        jobsCompleted += compiledTask.getExecutionCount();
        saveChanges();
    }

    private CraftingContainer createCraftingContainer(CompiledTask compiledTask) {
        // ponytail: minimal menu, only setItem/getItem used by assembly
        AbstractContainerMenu dummyMenu = new AbstractContainerMenu(null, 0) {
            @Override
            public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }
            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };
        TransientCraftingContainer container = new TransientCraftingContainer(dummyMenu, 3, 3);
        ItemStack[] grid = compiledTask.getCraftingGridCopies();
        for (int i = 0; i < grid.length && i < 9; i++) {
            container.setItem(i, grid[i]);
        }
        return container;
    }

    private void injectIntoNetwork(GenericStack stack) {
        IGrid grid = getGrid();
        if (grid == null) {
            return;
        }
        IStorageService storage = grid.getStorageService();
        if (storage == null) {
            return;
        }
        var inv = storage.getInventory();
        if (inv == null) {
            return;
        }
        inv.insert(stack.what(), stack.amount(), Actionable.MODULATE, actionSource);
    }

    // ──────────────────────────────────────────────
    //  Server tick
    // ──────────────────────────────────────────────

    public static void serverTick(
            Level level,
            BlockPos pos,
            BlockState state,
            AbstractHighCapacityCraftingHostBlockEntity blockEntity
    ) {
        if (!level.isClientSide()) {
            blockEntity.serverTick();
        }
    }

    protected void serverTick() {
        loadExternalPatternsIfNeeded();
        localPatternProviderFacade.refreshDirtyPatterns();
        requestProviderUpdateIfLocalPatternsChanged();
        forceProviderRefreshIfReady();
        recalculateBudgetModel();
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        // TODO Sprint 2: pendingCompletion processing, pendingAeReturn flush
        localExecutionQueue.tick(this);
        updateExecutionPeaks();
    }

    // ──────────────────────────────────────────────
    //  Pattern persistence (NBT-based, no SavedData)
    // ──────────────────────────────────────────────

    // ponytail: persist all pattern slots directly in the block entity NBT.
    // Upgrade path: HighCapacityPatternHostSavedData (Sprint 3) for large inventories.
    private static final String NBT_PATTERN_SLOTS = "patternSlots";

    private void persistPatternSlot(int slot) {
        saveChanges();
    }

    private void persistAllPatterns() {
        saveChanges();
    }

    private void persistCleanup() {
    }

    // ──────────────────────────────────────────────
    //  External patterns (NBT stub)
    // ──────────────────────────────────────────────

    protected void savePatternSlotsToTag(CompoundTag data) {
        ListTag slotsTag = new ListTag();
        for (int i = 0; i < pagedPatternInventory.getTotalSlots(); i++) {
            ItemStack stack = pagedPatternInventory.getVirtualSlot(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt("slot", i);
                slotTag.put("stack", stack.save(new CompoundTag()));
                slotsTag.add(slotTag);
            }
        }
        data.put(NBT_PATTERN_SLOTS, slotsTag);
    }

    protected void loadPatternSlotsFromTag(CompoundTag data) {
        if (!data.contains(NBT_PATTERN_SLOTS, Tag.TAG_LIST)) {
            return;
        }
        ListTag slotsTag = data.getList(NBT_PATTERN_SLOTS, Tag.TAG_COMPOUND);
        for (Tag tag : slotsTag) {
            if (!(tag instanceof CompoundTag slotTag)) {
                continue;
            }
            int slot = slotTag.getInt("slot");
            ItemStack stack = ItemStack.of(slotTag.getCompound("stack"));
            if (slot >= 0 && slot < pagedPatternInventory.getTotalSlots()) {
                pagedPatternInventory.setVirtualSlot(slot, stack);
            }
        }
    }

    private void loadExternalPatternsIfNeeded() {
        // ponytail: external persistence not in Sprint 1; patterns stored in block NBT.
        if (externalPatternsLoaded) {
            return;
        }
        externalPatternsLoaded = true;
        localPatternProviderFacade.markAllDirty();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    // ──────────────────────────────────────────────
    //  Provider update helpers
    // ──────────────────────────────────────────────

    private void forceProviderRefreshIfReady() {
        if (!providerRefreshAfterReadyPending || getMainNode().getNode() == null || getGrid() == null) {
            return;
        }
        localPatternProviderFacade.markAllDirty();
        localPatternProviderFacade.refreshDirtyPatterns();
        localPatternProviderFacade.consumeProviderVisibleSetUpdatePending();
        ICraftingProvider.requestUpdate(getMainNode());
        providerRefreshAfterReadyPending = false;
    }

    private void requestProviderUpdateIfLocalPatternsChanged() {
        if (localPatternProviderFacade.consumeProviderVisibleSetUpdatePending()) {
            requestProviderUpdateIfReady();
        }
    }

    private void requestProviderUpdateIfReady() {
        if (getMainNode().getNode() == null || getGrid() == null) {
            providerRefreshAfterReadyPending = true;
            return;
        }
        ICraftingProvider.requestUpdate(getMainNode());
    }

    public void onBlockRemovedFromWorld() {
        persistCleanup();
    }

    // ──────────────────────────────────────────────
    //  Pattern access
    // ──────────────────────────────────────────────

    public ItemStack getPatternAt(int slot) {
        return pagedPatternInventory.getVirtualSlot(slot).copy();
    }

    public void setPatternAt(int slot, ItemStack stack) {
        pagedPatternInventory.setVirtualSlot(slot, stack);
        persistPatternSlot(slot);
        onPatternSlotChanged(slot);
    }

    public boolean isSupportedEncodedPattern(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Level level = getLevel();
        if (level == null) {
            return false;
        }
        return PatternDetailsHelper.decodePattern(stack, level) instanceof IMolecularAssemblerSupportedPattern;
    }

    // ──────────────────────────────────────────────
    //  Budget recalculation
    // ──────────────────────────────────────────────

    private void recalculateBudgetModel() {
        int previousLaneCount = lastEffectiveLaneCount;
        currentBudgetModel = DynamicExecutionBudgetModel.fromHost(this);
        lastCompletionBudget = currentBudgetModel.completionTarget();
        lastDispatchBudget = currentBudgetModel.dispatchTarget();
        lastCompletionSliceBudget = currentBudgetModel.completionSliceBudget();
        lastSoftBudget = currentBudgetModel.softBudget();
        lastHardBudget = currentBudgetModel.hardBudget();
        lastEffectiveLaneCount = currentBudgetModel.laneActivationTarget();
    }

    void updateExecutionPeaks() {
        // TODO Sprint 2: include pendingCompletionQueue
    }

    // ──────────────────────────────────────────────
    //  Search / highlight
    // ──────────────────────────────────────────────

    public int searchAndHighlightFirst(String rawQuery) {
        return searchAndHighlight(rawQuery, false);
    }

    public int searchAndHighlightNext(String rawQuery) {
        return searchAndHighlight(rawQuery, true);
    }

    private int searchAndHighlight(String rawQuery, boolean rotateFromCurrent) {
        String nextQuery = rawQuery == null ? "" : rawQuery;
        boolean sameQuery = nextQuery.equals(lastSearchQuery);
        lastSearchQuery = nextQuery;
        PatternSearchIndex.MatchResult matchResult = patternSearchIndex.findAllMatchesWithStats(
                pagedPatternInventory,
                decodedPatternEntryCache,
                lastSearchQuery,
                getLevel()
        );
        List<Integer> matches = matchResult.matches();
        searchResultCount = matches.size();
        if (matches.isEmpty()) {
            highlightedGlobalSlot = -1;
            highlightedPageSlotMask = 0;
            saveChanges();
            return -1;
        }
        int match = selectSearchMatch(matches, sameQuery && rotateFromCurrent);
        highlightedGlobalSlot = match;
        pagedPatternInventory.setActivePage(match / pagedPatternInventory.getPageSize());
        highlightedPageSlotMask = computeHighlightedPageSlotMask(lastSearchQuery);
        saveChanges();
        return match;
    }

    public void clearSearchState() {
        lastSearchQuery = "";
        searchResultCount = 0;
        highlightedGlobalSlot = -1;
        highlightedPageSlotMask = 0;
        saveChanges();
    }

    public String getLastSearchQuery() {
        return lastSearchQuery;
    }

    public int getSearchResultCount() {
        return searchResultCount;
    }

    public int getHighlightedGlobalSlot() {
        return highlightedGlobalSlot;
    }

    public int getHighlightedPageSlotMask() {
        return highlightedPageSlotMask;
    }

    public int getHighlightedPageSlot() {
        if (highlightedGlobalSlot < 0) {
            return -1;
        }
        int page = highlightedGlobalSlot / pagedPatternInventory.getPageSize();
        if (page != pagedPatternInventory.getActivePage()) {
            return -1;
        }
        return highlightedGlobalSlot % pagedPatternInventory.getPageSize();
    }

    private int selectSearchMatch(List<Integer> matches, boolean rotateFromCurrent) {
        if (matches.isEmpty()) {
            return -1;
        }
        if (!rotateFromCurrent || highlightedGlobalSlot < 0) {
            return matches.get(0);
        }
        for (int match : matches) {
            if (match > highlightedGlobalSlot) {
                return match;
            }
        }
        return matches.get(0);
    }

    private int computeHighlightedPageSlotMask(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return 0;
        }
        Level level = getLevel();
        if (level == null) {
            return 0;
        }
        int pageSize = pagedPatternInventory.getPageSize();
        int pageStart = pagedPatternInventory.getActivePage() * pageSize;
        int pageEnd = Math.min(pagedPatternInventory.getTotalSlots(), pageStart + pageSize);
        int mask = 0;
        PatternSearchIndex.MatchResult matchResult = patternSearchIndex.findAllMatchesWithStats(
                pagedPatternInventory,
                decodedPatternEntryCache,
                rawQuery,
                level
        );
        for (int globalSlot : matchResult.matches()) {
            if (globalSlot >= pageStart && globalSlot < pageEnd) {
                mask |= 1 << (globalSlot - pageStart);
            }
        }
        return mask;
    }

    // ──────────────────────────────────────────────
    //  Status
    // ──────────────────────────────────────────────

    public HighCapacityCraftingMachineStatus getMachineStatus() {
        if (!getMainNode().isActive() || getGrid() == null) {
            return HighCapacityCraftingMachineStatus.NETWORK_OFFLINE;
        }
        // TODO Sprint 2: check pendingAeReturn
        if (!localExecutionQueue.isIdle()) {
            return HighCapacityCraftingMachineStatus.RUNNING;
        }
        return HighCapacityCraftingMachineStatus.IDLE;
    }

    public boolean hasPendingCompletionBacklog() {
        return false;
    }

    public IItemHandler getAutomationItemHandler() {
        return automationItemHandler;
    }

    public BatchExecutionMode getBatchExecutionModeForTest() {
        return batchExecutionMode;
    }

    public void setBatchExecutionModeForTest(BatchExecutionMode batchExecutionMode) {
        this.batchExecutionMode = batchExecutionMode == null ? BatchExecutionMode.OFF : batchExecutionMode;
        saveChanges();
    }

    public void setSpeedCardsForTest(int count) {
        int desired = Math.max(0, Math.min(count, upgrades.size()));
        for (int slot = 0; slot < upgrades.size(); slot++) {
            upgrades.setItemDirect(slot, slot < desired ? AEItems.SPEED_CARD.stack() : ItemStack.EMPTY);
        }
        recalculateBudgetModel();
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        saveChanges();
    }

    // ──────────────────────────────────────────────
    //  Grid node listener
    // ──────────────────────────────────────────────

    private static class Listener implements IGridNodeListener<AbstractHighCapacityCraftingHostBlockEntity> {
        @Override
        public void onSaveChanges(AbstractHighCapacityCraftingHostBlockEntity node, IGridNode gridNode) {
            node.saveChanges();
        }
    }
}
