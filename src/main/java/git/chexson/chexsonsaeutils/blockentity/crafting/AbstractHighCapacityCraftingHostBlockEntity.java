package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.menu.AutoCraftingMenu;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import git.chexson.chexsonsaeutils.crafting.AeCpuIngressRouter;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.NativeSourceCpuHandle;
import git.chexson.chexsonsaeutils.crafting.ParallelActiveCpuHandle;
import git.chexson.chexsonsaeutils.crafting.SourceCpuHandle;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineAggregatedPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineDelegatingPattern;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachinePlanningProvider;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingLane;
import git.chexson.chexsonsaeutils.crafting.persistence.HighCapacityPatternHostSavedData;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public abstract class AbstractHighCapacityCraftingHostBlockEntity extends AENetworkedBlockEntity
        implements PatternContainer, IUpgradeableObject, ICraftingProvider, InternalInventoryHost,
        FormalMachinePlanningProvider {

    protected static boolean supportsCompletionTemplate(@Nullable IMolecularAssemblerSupportedPattern pattern) {
        if (!(pattern instanceof AECraftingPattern aeCraftingPattern)) {
            return false;
        }
        return !aeCraftingPattern.canSubstitute() && !aeCraftingPattern.canSubstituteFluids();
    }

    @Nullable
    public static CompletionTemplate probeStableCompletionTemplate(
            @Nullable Level level,
            @Nullable IMolecularAssemblerSupportedPattern pattern,
            @Nullable CompiledTask compiledTask
    ) {
        if (level == null || pattern == null || compiledTask == null || !supportsCompletionTemplate(pattern)) {
            return null;
        }
        SingleExecutionResult first = executeSingleCompletion(level, pattern, compiledTask);
        if (first == null) {
            return null;
        }
        SingleExecutionResult second = executeSingleCompletion(level, pattern, compiledTask);
        if (second == null || !first.matches(second)) {
            return null;
        }
        return new CompletionTemplate(first.primary(), first.remainders());
    }

    @Nullable
    private static SingleExecutionResult executeSingleCompletion(
            Level level,
            IMolecularAssemblerSupportedPattern pattern,
            CompiledTask compiledTask
    ) {
        TransientCraftingContainer craftingContainer = new TransientCraftingContainer(new AutoCraftingMenu(), 3, 3);
        ItemStack[] craftingGrid = compiledTask.getCraftingGridCopies();
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            craftingContainer.setItem(slot, craftingGrid[slot]);
        }
        var positionedInput = craftingContainer.asPositionedCraftInput();
        var craftingInput = positionedInput.input();
        ItemStack output = pattern.assemble(craftingInput, level);
        if (output.isEmpty()) {
            return null;
        }
        GenericStack primary = normalizePrimaryOutput(pattern, output);
        if (primary == null) {
            return null;
        }
        Map<AEItemKey, Long> remainderTotals = new LinkedHashMap<>();
        for (ItemStack remainder : pattern.getRemainingItems(craftingInput)) {
            if (remainder.isEmpty()) {
                continue;
            }
            GenericStack genericRemainder = GenericStack.fromItemStack(remainder.copy());
            if (genericRemainder == null || !(genericRemainder.what() instanceof AEItemKey itemKey)) {
                return null;
            }
            remainderTotals.merge(itemKey, genericRemainder.amount(), Long::sum);
        }
        return new SingleExecutionResult(primary, Map.copyOf(remainderTotals));
    }

    public record CompletionTemplate(GenericStack primary, Map<AEItemKey, Long> remainders) {
    }

    @Nullable
    protected static GenericStack normalizePrimaryOutput(
            @Nullable IMolecularAssemblerSupportedPattern pattern,
            @Nullable ItemStack output
    ) {
        if (pattern == null || output == null || output.isEmpty()) {
            return null;
        }
        GenericStack assembled = GenericStack.fromItemStack(output.copy());
        if (assembled == null) {
            return null;
        }
        GenericStack primaryOutput = pattern.getPrimaryOutput();
        if (primaryOutput == null || primaryOutput.what() == null || assembled.what() == null) {
            return assembled;
        }
        if (primaryOutput.what().getType() != assembled.what().getType()) {
            return assembled;
        }
        return new GenericStack(primaryOutput.what(), assembled.amount());
    }

    private record SingleExecutionResult(GenericStack primary, Map<AEItemKey, Long> remainders) {
        private boolean matches(SingleExecutionResult other) {
            if (other == null || primary == null || other.primary == null) {
                return false;
            }
            return primary.what().equals(other.primary.what())
                    && primary.amount() == other.primary.amount()
                    && remainders.equals(other.remainders);
        }
    }

    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_EXECUTION_QUEUE = "executionQueue";
    private static final String NBT_BASE_TICKS = "baseTicks";
    private static final String NBT_BATCH_MODE = "batchMode";
    private static final String NBT_WAITING_AE_RESULT = "waitingAeResult";
    private static final String NBT_PENDING_COMPLETION = "pendingCompletion";
    private static final String NBT_PENDING_COMPLETION_QUEUE = "pendingCompletionQueue";
    private static final String NBT_WAITING_AE_RETRY_DELAY = "waitingAeRetryDelay";
    private static final String NBT_WAITING_AE_RETRY_TICK = "waitingAeRetryTick";
    private static final String NBT_ACTIVE_PAGE = "activePage";
    private static final String NBT_LAST_SEARCH_QUERY = "lastSearchQuery";
    private static final String NBT_SEARCH_RESULT_COUNT = "searchResultCount";
    private static final String NBT_HIGHLIGHTED_GLOBAL_SLOT = "highlightedGlobalSlot";
    private static final String NBT_HIGHLIGHTED_PAGE_SLOT_MASK = "highlightedPageSlotMask";
    protected static final int PAGE_SIZE = 27;
    private static final int UPGRADE_SLOTS = 5;

    private static int configTotalPatternSlots() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_TOTAL_PATTERN_SLOTS);
    }

    private static int configDefaultBaseTicks() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_DEFAULT_BASE_TICKS);
    }

    private static int configLocalExecutionQueueCapacity() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_LOCAL_EXECUTION_QUEUE_CAPACITY);
    }

    private static long configTickSoftBudgetNanos() {
        return ChexsonsaeutilsCompatibilityConfig.longValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_TICK_SOFT_BUDGET_NANOS);
    }

    private static long configTickHardBudgetNanos() {
        return ChexsonsaeutilsCompatibilityConfig.longValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_TICK_HARD_BUDGET_NANOS);
    }

    private static long configTickAbsoluteBudgetNanos() {
        return ChexsonsaeutilsCompatibilityConfig.longValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_TICK_ABSOLUTE_BUDGET_NANOS);
    }

    private static int configCompletionProgressSaveInterval() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_COMPLETION_PROGRESS_SAVE_INTERVAL);
    }

    private static int configQueueProgressSaveInterval() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_QUEUE_PROGRESS_SAVE_INTERVAL);
    }

    protected final MachineSource actionSource = new MachineSource(this);
    protected final DirtySlotPatternRefreshScheduler refreshScheduler =
            new DirtySlotPatternRefreshScheduler(configTotalPatternSlots());
    protected final DecodedPatternEntryCache decodedPatternEntryCache =
            new DecodedPatternEntryCache(configTotalPatternSlots());
    protected final PagedPatternInventory pagedPatternInventory =
            new PagedPatternInventory(this, refreshScheduler, configTotalPatternSlots(), PAGE_SIZE);
    protected final LocalPatternProviderFacade localPatternProviderFacade =
            new LocalPatternProviderFacade(this, pagedPatternInventory, refreshScheduler, decodedPatternEntryCache);
    protected final LocalExecutionQueue localExecutionQueue = new LocalExecutionQueue(configLocalExecutionQueueCapacity());
    private final Supplier<ItemStack> representativeItemSupplier;
    private final IUpgradeInventory upgrades;
    private final TransientCraftingContainer craftingContainer =
            new TransientCraftingContainer(new AutoCraftingMenu(), 3, 3);
    private final PatternSearchIndex patternSearchIndex = new PatternSearchIndex();
    private final VirtualPatternInventoryContainer automationContainer = new VirtualPatternInventoryContainer(this);
    private final VirtualPatternItemHandler automationItemHandler = new VirtualPatternItemHandler(automationContainer);
    private final String pageStatusTranslationKey;

    private int baseOperationTicks = configDefaultBaseTicks();
    private boolean localOptimizationEnabled = true;
    private long jobsSubmitted;
    private long jobsCompleted;
    @Nullable
    private PendingAeReturn pendingAeReturn;
    private final ArrayDeque<PendingCompletionWork> pendingCompletionQueue = new ArrayDeque<>();
    private int waitingAeRetryDelayTicks = 1;
    private int waitingAeRetryTickCountdown;
    private final BatchExecutionMode initialBatchExecutionMode;
    private final boolean formalMachineDispatchHost;
    private BatchExecutionMode batchExecutionMode;
    private String lastSearchQuery = "";
    private int searchResultCount;
    private int highlightedGlobalSlot = -1;
    private int highlightedPageSlotMask;
    private boolean externalPatternsLoaded;
    private boolean providerRefreshAfterReadyPending;
    private long lastFastPathFallbackCount;
    private int lastEffectiveLaneCount = 1;
    private int lastCompletionBudget = 1;
    private int lastDispatchBudget = 1;
    private int lastCompletionSliceBudget = 1;
    private int lastSoftBudget = 1;
    private int lastHardBudget = 1;
    private int lastObservedLargestBatch = 1;
    private boolean cpuWaitingReturnStoppedThisTick;
    private int unsavedQueueMutationCount;
    private FormalMachineTickBudgetSnapshot lastTickBudgetSnapshot =
            new FormalMachineTickBudgetSnapshot(
                    configTickSoftBudgetNanos(),
                    configTickHardBudgetNanos(),
                    configTickAbsoluteBudgetNanos(),
                    0L,
                    false
            );
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
        this.representativeItemSupplier = representativeItemSupplier;
        this.pageStatusTranslationKey = pageStatusTranslationKey;
        this.initialBatchExecutionMode = initialBatchExecutionMode == null
                ? BatchExecutionMode.OFF
                : initialBatchExecutionMode;
        this.formalMachineDispatchHost = formalMachineDispatchHost;
        this.batchExecutionMode = this.initialBatchExecutionMode;
        this.upgrades = UpgradeInventories.forMachine(
                () -> representativeItemSupplier.get().getItem(),
                UPGRADE_SLOTS,
                this::saveChanges
        );
        this.getMainNode()
                .setIdlePowerUsage(0.0)
                .addService(ICraftingProvider.class, this);
    }

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

    @Override
    public void onReady() {
        super.onReady();
        loadExternalPatternsIfNeeded();
        localPatternProviderFacade.markAllDirty();
        recalculateBudgetModel();
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        localPatternProviderFacade.refreshDirtyPatterns();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        upgrades.writeToNBT(data, NBT_UPGRADES, registries);
        localExecutionQueue.writeToTag(data, NBT_EXECUTION_QUEUE, registries);
        data.putInt(NBT_BASE_TICKS, baseOperationTicks);
        data.putString(NBT_BATCH_MODE, batchExecutionMode.name());
        data.putInt(NBT_ACTIVE_PAGE, pagedPatternInventory.getActivePage());
        data.putString(NBT_LAST_SEARCH_QUERY, lastSearchQuery);
        data.putInt(NBT_SEARCH_RESULT_COUNT, searchResultCount);
        data.putInt(NBT_HIGHLIGHTED_GLOBAL_SLOT, highlightedGlobalSlot);
        data.putInt(NBT_HIGHLIGHTED_PAGE_SLOT_MASK, highlightedPageSlotMask);
        if (!pendingCompletionQueue.isEmpty()) {
            ListTag pendingCompletionTag = new ListTag();
            for (PendingCompletionWork pendingCompletionWork : pendingCompletionQueue) {
                pendingCompletionTag.add(pendingCompletionWork.writeToTag(registries));
            }
            data.put(NBT_PENDING_COMPLETION_QUEUE, pendingCompletionTag);
        } else {
            data.remove(NBT_PENDING_COMPLETION_QUEUE);
            data.remove(NBT_PENDING_COMPLETION);
        }
        if (pendingAeReturn != null) {
            data.put(NBT_WAITING_AE_RESULT, pendingAeReturn.writeToTag(registries));
            data.putInt(NBT_WAITING_AE_RETRY_DELAY, waitingAeRetryDelayTicks);
            data.putInt(NBT_WAITING_AE_RETRY_TICK, waitingAeRetryTickCountdown);
        } else {
            data.remove(NBT_WAITING_AE_RESULT);
        }
        saveCustomState(data, registries);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        upgrades.readFromNBT(data, NBT_UPGRADES, registries);
        localExecutionQueue.readFromTag(data, NBT_EXECUTION_QUEUE, registries);
        baseOperationTicks = data.contains(NBT_BASE_TICKS) ? Math.max(1, data.getInt(NBT_BASE_TICKS)) : configDefaultBaseTicks();
        batchExecutionMode = data.contains(NBT_BATCH_MODE)
                ? BatchExecutionMode.valueOf(data.getString(NBT_BATCH_MODE))
                : BatchExecutionMode.OFF;
        pendingCompletionQueue.clear();
        if (data.contains(NBT_PENDING_COMPLETION_QUEUE, Tag.TAG_LIST)) {
            ListTag pendingCompletionTag = data.getList(NBT_PENDING_COMPLETION_QUEUE, Tag.TAG_COMPOUND);
            for (Tag tag : pendingCompletionTag) {
                if (tag instanceof CompoundTag compoundTag) {
                    PendingCompletionWork pendingCompletionWork = PendingCompletionWork.readFromTag(compoundTag, registries);
                    if (pendingCompletionWork != null) {
                        pendingCompletionQueue.addLast(pendingCompletionWork);
                    }
                }
            }
        } else {
            PendingCompletionWork legacyPendingCompletion =
                    PendingCompletionWork.readFromTag(data.getCompound(NBT_PENDING_COMPLETION), registries);
            if (legacyPendingCompletion != null) {
                pendingCompletionQueue.addLast(legacyPendingCompletion);
            }
        }
        pendingAeReturn = PendingAeReturn.readFromTag(data.getCompound(NBT_WAITING_AE_RESULT), registries);
        waitingAeRetryDelayTicks = data.contains(NBT_WAITING_AE_RETRY_DELAY)
                ? Math.max(1, Math.min(20, data.getInt(NBT_WAITING_AE_RETRY_DELAY)))
                : 1;
        waitingAeRetryTickCountdown = data.contains(NBT_WAITING_AE_RETRY_TICK)
                ? Math.max(0, data.getInt(NBT_WAITING_AE_RETRY_TICK))
                : 0;
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
        recalculateBudgetModel();
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        loadCustomState(data, registries);
        updateCompletionBacklogPeaks();
    }

    protected void saveCustomState(CompoundTag data, HolderLookup.Provider registries) {
    }

    protected void loadCustomState(CompoundTag data, HolderLookup.Provider registries) {
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inventory) {
        saveChanges();
    }

    @Override
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

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        localPatternProviderFacade.refreshDirtyPatterns();
        return localPatternProviderFacade.getAvailablePatterns();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (!this.getMainNode().isActive() || pendingAeReturn != null) {
            return false;
        }
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
            return false;
        }
        if (supportedPattern instanceof FormalMachineAggregatedPattern aggregatedPattern) {
            return pushAggregatedPattern(aggregatedPattern, inputHolder);
        }
        if (!localPatternProviderFacade.contains(unwrapFormalDelegatingPattern(patternDetails))) {
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

    private boolean pushAggregatedPattern(
            FormalMachineAggregatedPattern aggregatedPattern,
            KeyCounter[] inputHolder
    ) {
        if (aggregatedPattern == null
                || inputHolder == null
                || !aggregatedPattern.hostLocator().matches(this)) {
            return false;
        }
        CompiledTask compiledTask = CompiledTask.compile(
                aggregatedPattern,
                inputHolder,
                aggregatedPattern.totalTicks(),
                1
        );
        if (compiledTask == null) {
            return false;
        }
        attachAggregatedCompletionTemplate(aggregatedPattern, compiledTask);
        return offerPrecompiledTask(compiledTask);
    }

    protected boolean offerPrecompiledTask(CompiledTask compiledTask) {
        if (compiledTask == null) {
            return false;
        }
        IMolecularAssemblerSupportedPattern pattern = compiledTask.resolvePattern(getLevel());
        if (pattern != null) {
            maybeAttachCompletionTemplate(pattern, compiledTask);
        }
        if (!localExecutionQueue.offer(compiledTask)) {
            return false;
        }
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        jobsSubmitted += compiledTask.getExecutionCount();
        markQueueMutationForSave(localExecutionQueue.totalTaskCount() <= 1);
        return true;
    }

    @Override
    public boolean isBusy() {
        if (pendingAeReturn != null) {
            return true;
        }
        if (isCompletionBacklogHardPressured()) {
            return true;
        }
        if (batchExecutionMode == BatchExecutionMode.SAME_PATTERN_DRAIN) {
            return false;
        }
        return localExecutionQueue.isAtCapacity();
    }

    @Override
    public Set<AEKey> getEmitableItems() {
        return Set.of();
    }

    @Override
    public appeng.api.networking.IGrid getGrid() {
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

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

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
        pendingCompletionQueue.clear();
        pendingAeReturn = null;
        lastSearchQuery = "";
        searchResultCount = 0;
        highlightedGlobalSlot = -1;
        highlightedPageSlotMask = 0;
        persistAllPatterns();
        clearCustomContent();
    }

    protected void clearCustomContent() {
    }

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

    protected int getVisibleOutputBufferSlotsUsedForSnapshot() {
        return 0;
    }

    public int getQueuedTaskCount() {
        return localExecutionQueue.queuedTaskCount();
    }

    public int getRunningTaskCount() {
        return localExecutionQueue.runningTaskCount();
    }

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

    public void setLocalOptimizationEnabledForTest(boolean enabled) {
        localOptimizationEnabled = enabled;
        saveChanges();
    }

    boolean isLocalOptimizationEnabled() {
        return localOptimizationEnabled;
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

    void recordLocalOptimizationHit() {
    }

    void recordDecodeCall() {
    }

    void recordDecodeCacheHit() {
    }

    void recordDirtyRefreshScan(int slotCount) {
    }

    void recordProviderUpdate() {
    }

    void recordSearchMetrics(PatternSearchIndex.MatchResult matchResult) {
        if (matchResult == null) {
            return;
        }
    }

    public boolean hasInWorldNodeHostCapabilityForTest() {
        return true;
    }

    public void forceProviderRefreshForTest() {
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    public void recordDeterministicPlanningHitForTest(long wallClockNanos) {
    }

    public void recordDeterministicPlanningFallbackForTest() {
    }

    public FormalMachineTickBudgetSnapshot getTickBudgetSnapshotForTest() {
        return lastTickBudgetSnapshot;
    }

    public void recordBulkExtractionResult(int actualAdditionalExecutions) {
        if (actualAdditionalExecutions <= 0) {
            return;
        }
    }

    public void recordBulkExtractionFallback() {
    }

    public void recordTemplatedDispatchHitForTest() {
    }

    public void clearPatternsForTest() {
        pagedPatternInventory.clear();
        persistAllPatterns();
        decodedPatternEntryCache.clear();
        localPatternProviderFacade.markAllDirty();
        localPatternProviderFacade.refreshDirtyPatterns();
        requestProviderUpdateIfLocalPatternsChanged();
        saveChanges();
    }

    public void clearPatternsDeferredRefreshForTest() {
        refreshScheduler.clear();
        pagedPatternInventory.clearWithoutDirtyMarks();
        persistAllPatterns();
        decodedPatternEntryCache.clear();
        localPatternProviderFacade.clear();
        saveChanges();
    }

    public void setPatternInSlotForTest(int slot, ItemStack stack) {
        setPatternAt(slot, stack);
        localPatternProviderFacade.refreshDirtyPatterns();
        requestProviderUpdateIfLocalPatternsChanged();
        saveChanges();
    }

    public void setPatternInSlotDeferredRefreshForTest(int slot, ItemStack stack) {
        setPatternAt(slot, stack);
        saveChanges();
    }

    public ItemStack getPatternAt(int slot) {
        return pagedPatternInventory.getVirtualSlot(slot).copy();
    }

    public void setPatternAt(int slot, ItemStack stack) {
        pagedPatternInventory.setVirtualSlot(slot, stack);
        persistPatternSlot(slot);
        onPatternSlotChanged(slot);
    }

    public void clearPatternsForAutomation() {
        pagedPatternInventory.clear();
        localPatternProviderFacade.clear();
        decodedPatternEntryCache.clear();
        persistAllPatterns();
        lastSearchQuery = "";
        searchResultCount = 0;
        highlightedGlobalSlot = -1;
        requestProviderUpdateIfReady();
    }

    public int fillCraftingPatternsForTest(int startSlot, List<ItemStack> patterns) {
        int inserted = 0;
        if (patterns.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < patterns.size(); index++) {
            int targetSlot = startSlot + index;
            if (targetSlot < 0 || targetSlot >= pagedPatternInventory.getTotalSlots()) {
                break;
            }
            setPatternAt(targetSlot, patterns.get(index));
            inserted++;
        }
        localPatternProviderFacade.refreshDirtyPatterns();
        saveChanges();
        return inserted;
    }

    public int fillCraftingPatternsForTest(int startSlot, int count, ItemStack[] craftingGrid) {
        Level currentLevel = getLevel();
        if (currentLevel == null || count <= 0) {
            return 0;
        }
        ItemStack pattern = encodeCraftingPatternForTest(currentLevel, craftingGrid);
        if (pattern.isEmpty()) {
            return 0;
        }
        List<ItemStack> patterns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            patterns.add(pattern.copy());
        }
        return fillCraftingPatternsForTest(startSlot, patterns);
    }

    public int fillCraftingPatternsRoundRobinForTest(List<ItemStack> patterns, int slotCount) {
        if (patterns.isEmpty() || slotCount <= 0) {
            return 0;
        }
        int cappedSlotCount = Math.min(slotCount, pagedPatternInventory.getTotalSlots());
        List<ItemStack> roundRobin = new ArrayList<>(cappedSlotCount);
        for (int slot = 0; slot < cappedSlotCount; slot++) {
            ItemStack source = patterns.get(slot % patterns.size());
            roundRobin.add(source.copy());
        }
        return fillCraftingPatternsForTest(0, roundRobin);
    }

    public int submitFirstAvailablePatternForTest(int count) {
        localPatternProviderFacade.refreshDirtyPatterns();
        int submitted = 0;
        for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
            if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
                continue;
            }
            while (submitted < count && submitCompiledTaskForTest(supportedPattern)) {
                submitted++;
            }
            if (submitted >= count) {
                break;
            }
        }
        if (submitted > 0) {
            flushQueuedMutationsToDisk();
        }
        return submitted;
    }

    public int submitAvailablePatternsRoundRobinForTest(int totalJobs, int uniquePatternBudget) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (totalJobs <= 0 || uniquePatternBudget <= 0) {
            return 0;
        }
        List<IMolecularAssemblerSupportedPattern> supportedPatterns = new ArrayList<>();
        LinkedHashSet<AEItemKey> uniqueDefinitions = new LinkedHashSet<>();
        for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
            if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
                continue;
            }
            AEItemKey definition = supportedPattern.getDefinition();
            if (!uniqueDefinitions.add(definition)) {
                continue;
            }
            supportedPatterns.add(supportedPattern);
            if (supportedPatterns.size() >= uniquePatternBudget) {
                break;
            }
        }
        if (supportedPatterns.isEmpty()) {
            return 0;
        }
        int submitted = 0;
        LinkedHashSet<AEItemKey> submittedDefinitions = new LinkedHashSet<>();
        while (submitted < totalJobs) {
            boolean progressed = false;
            for (IMolecularAssemblerSupportedPattern supportedPattern : supportedPatterns) {
                if (submitted >= totalJobs) {
                    break;
                }
                if (!submitCompiledTaskForTest(supportedPattern, 1)) {
                    continue;
                }
                submitted += 1;
                submittedDefinitions.add(supportedPattern.getDefinition());
                progressed = true;
            }
            if (!progressed) {
                break;
            }
        }
        updateExecutionPeaks();
        if (submitted > 0) {
            flushQueuedMutationsToDisk();
        }
        return submitted;
    }

    public int submitPatternsByOutputSequenceForTest(List<AEItemKey> outputs) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (outputs == null || outputs.isEmpty()) {
            return 0;
        }
        Map<AEItemKey, IMolecularAssemblerSupportedPattern> patternsByOutput = new LinkedHashMap<>();
        for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
            if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
                continue;
            }
            GenericStack primaryOutput = patternDetails.getPrimaryOutput();
            if (primaryOutput == null || !(primaryOutput.what() instanceof AEItemKey outputKey)) {
                continue;
            }
            patternsByOutput.putIfAbsent(outputKey, supportedPattern);
        }
        int submitted = 0;
        LinkedHashSet<AEItemKey> submittedDefinitions = new LinkedHashSet<>();
        for (AEItemKey output : outputs) {
            IMolecularAssemblerSupportedPattern supportedPattern = patternsByOutput.get(output);
            if (supportedPattern == null || !submitCompiledTaskForTest(supportedPattern, 1)) {
                break;
            }
            submitted++;
            submittedDefinitions.add(supportedPattern.getDefinition());
        }
        updateExecutionPeaks();
        if (submitted > 0) {
            saveChanges();
        }
        return submitted;
    }

    public int submitFirstAvailablePatternWithExecutionCountForTest(int executionCount) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (executionCount <= 0) {
            return 0;
        }
        for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
            if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
                continue;
            }
            if (submitCompiledTaskForTest(supportedPattern, executionCount)) {
                flushQueuedMutationsToDisk();
                return executionCount;
            }
            break;
        }
        return 0;
    }

    public int submitPatternsByDefinitionSequenceForTest(List<ItemStack> definitions) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (definitions == null || definitions.isEmpty()) {
            return 0;
        }
        List<IMolecularAssemblerSupportedPattern> patternsByDefinition = new ArrayList<>();
        for (ItemStack definition : definitions) {
            if (definition == null || definition.isEmpty()) {
                continue;
            }
            IMolecularAssemblerSupportedPattern matched = null;
            for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
                if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
                    continue;
                }
                ItemStack candidateDefinition = supportedPattern.getDefinition().toStack();
                if (ItemStack.isSameItemSameComponents(candidateDefinition, definition)) {
                    matched = supportedPattern;
                    break;
                }
            }
            if (matched != null) {
                patternsByDefinition.add(matched);
            }
        }
        int submitted = 0;
        LinkedHashSet<AEItemKey> submittedDefinitions = new LinkedHashSet<>();
        for (IMolecularAssemblerSupportedPattern supportedPattern : patternsByDefinition) {
            if (!submitCompiledTaskForTest(supportedPattern, 1)) {
                break;
            }
            submitted++;
            submittedDefinitions.add(supportedPattern.getDefinition());
        }
        updateExecutionPeaks();
        if (submitted > 0) {
            flushQueuedMutationsToDisk();
        }
        return submitted;
    }

    public int submitPatternByDefinitionWithExecutionCountForTest(ItemStack definition, int executionCount) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (definition == null || definition.isEmpty() || executionCount <= 0) {
            return 0;
        }
        for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
            if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(supportedPattern.getDefinition().toStack(), definition)) {
                continue;
            }
            if (submitCompiledTaskForTest(supportedPattern, executionCount)) {
                flushQueuedMutationsToDisk();
                return executionCount;
            }
            return 0;
        }
        return 0;
    }

    public int refreshAvailablePatternsForTest() {
        return localPatternProviderFacade.getAvailablePatterns().size();
    }

    public int countUniquePatternDefinitionsForTest() {
        LinkedHashSet<AEItemKey> uniqueDefinitions = new LinkedHashSet<>();
        for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
            uniqueDefinitions.add(patternDetails.getDefinition());
        }
        return uniqueDefinitions.size();
    }

    public int countAvailablePatternsForOutputForTest(AEItemKey output) {
        int count = 0;
        for (IPatternDetails patternDetails : localPatternProviderFacade.getAvailablePatterns()) {
            GenericStack primaryOutput = patternDetails.getPrimaryOutput();
            if (primaryOutput != null && output.equals(primaryOutput.what())) {
                count++;
                continue;
            }
            for (GenericStack genericStack : patternDetails.getOutputs()) {
                if (genericStack != null && output.equals(genericStack.what())) {
                    count++;
                    break;
                }
            }
        }
        return count;
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

    public BatchExecutionMode getBatchExecutionModeForTest() {
        return batchExecutionMode;
    }

    public void setBatchExecutionModeForTest(BatchExecutionMode batchExecutionMode) {
        this.batchExecutionMode = batchExecutionMode == null ? BatchExecutionMode.OFF : batchExecutionMode;
        saveChanges();
    }

    public void completeTask(CompiledTask compiledTask) {
        if (compiledTask == null) {
            return;
        }
        if (pendingAeReturn != null) {
            throw new IllegalStateException("Completion handoff is blocked while another completion payload is pending");
        }
        @Nullable IMolecularAssemblerSupportedPattern pattern = compiledTask.resolvePattern(getLevel());
        if (pattern == null) {
            compiledTask.markFailed();
            saveChanges();
            return;
        }
        maybeAttachCompletionTemplate(pattern, compiledTask);
        pendingCompletionQueue.addLast(new PendingCompletionWork(compiledTask));
        updateCompletionBacklogPeaks();
        markQueueMutationForSave(false);
    }

    private boolean processPendingCompletionWork(long hardDeadlineNanos) {
        PendingCompletionWork pendingCompletionWork = pendingCompletionQueue.peekFirst();
        if (pendingCompletionWork == null || pendingAeReturn != null) {
            return false;
        }
        if (isDeadlineReached(hardDeadlineNanos)) {
            return false;
        }
        @Nullable IMolecularAssemblerSupportedPattern pattern =
                pendingCompletionWork.compiledTask().resolvePattern(getLevel());
        if (pattern == null) {
            pendingCompletionWork.compiledTask().markFailed();
            pendingCompletionQueue.removeFirst();
            saveChanges();
            return true;
        }
        int requestedExecutions = pendingCompletionWork.hasTemplate()
                ? pendingCompletionWork.remainingExecutions()
                : Math.max(1, pendingCompletionWork.remainingExecutions());
        int sliceExecutions = currentBudgetModel.claimCompletionSliceExecutions(requestedExecutions);
        if (sliceExecutions <= 0) {
            return false;
        }

        boolean preAttachedTemplate = pendingCompletionWork.hasTemplate()
                && pendingCompletionWork.completedExecutions() == 0;
        if (pendingCompletionWork.compiledTask().supportsTemplatedCompletion()
                && !pendingCompletionWork.hasTemplate()
                && !tryInitializeTemplatedCompletion(pattern, pendingCompletionWork)) {
            pendingCompletionWork.compiledTask().setSupportsTemplatedCompletion(false);
        }

        CompletionSliceResult sliceResult = pendingCompletionWork.hasTemplate()
                ? applyTemplatedCompletionSlice(pendingCompletionWork, sliceExecutions)
                : applyIncrementalCompletionSlice(pattern, pendingCompletionWork, sliceExecutions, hardDeadlineNanos);
        if (sliceResult == null) {
            pendingCompletionWork.compiledTask().markFailed();
            pendingCompletionQueue.removeFirst();
            saveChanges();
            return true;
        }
        int processedExecutions = sliceResult.processedExecutions();
        if (processedExecutions == 0) {
            return false;
        }
        if (preAttachedTemplate && pendingCompletionWork.hasTemplate()) {
        }

        if (pendingCompletionWork.completionRoute() == TaskCompletionRoute.CPU_WAITING) {
            PendingAeReturn slicePendingReturn = createSlicePendingReturn(pendingCompletionWork, sliceResult);
            if (slicePendingReturn != null) {
                PendingAeReturn remainingPending = tryDeliverPendingReturn(slicePendingReturn, hardDeadlineNanos);
                if (remainingPending != null) {
                    pendingAeReturn = remainingPending;
                    waitingAeRetryDelayTicks = 1;
                    waitingAeRetryTickCountdown = 1;
                } else {
                    finishCompletedReturn(slicePendingReturn);
                }
            }
        } else {
            pendingCompletionWork.appendPrimary(sliceResult.primary());
            pendingCompletionWork.appendRemainders(sliceResult.remainders());
        }

        pendingCompletionWork.advanceExecutions(processedExecutions);
        pendingCompletionWork.markSliceProcessed(processedExecutions);

        if (pendingAeReturn != null) {
            saveChanges();
            return true;
        }

        if (pendingCompletionWork.completionRoute() == TaskCompletionRoute.CPU_WAITING
                && pendingCompletionWork.isComplete()) {
            CompiledTask completedTask = pendingCompletionWork.compiledTask();
            pendingCompletionQueue.removeFirst();
            completedTask.markComplete();
            saveChanges();
            return true;
        }

        if (pendingCompletionWork.isComplete()) {
            PendingAeReturn nextPending = finalizePendingCompletionReturn(pendingCompletionWork);
            CompiledTask completedTask = pendingCompletionWork.compiledTask();
            pendingCompletionQueue.removeFirst();
            if (nextPending == null) {
                completedTask.markFailed();
                saveChanges();
                return true;
            }
            if (isDeadlineReached(hardDeadlineNanos)) {
                pendingAeReturn = nextPending;
                waitingAeRetryDelayTicks = 1;
                waitingAeRetryTickCountdown = 0;
                saveChanges();
                return true;
            }
            PendingAeReturn remainingPending = tryDeliverPendingReturn(nextPending, hardDeadlineNanos);
            if (remainingPending != null) {
                pendingAeReturn = remainingPending;
                waitingAeRetryDelayTicks = 1;
                waitingAeRetryTickCountdown = 1;
                saveChanges();
                return true;
            }
            finishCompletedReturn(nextPending);
            completedTask.markComplete();
            saveChanges();
            return true;
        }

        if (pendingCompletionWork.unsavedSliceCounter() >= configCompletionProgressSaveInterval()) {
            pendingCompletionWork.resetUnsavedSliceCounter();
            saveChanges();
        }

        if (pendingCompletionQueue.size() > 1) {
            pendingCompletionQueue.removeFirst();
            pendingCompletionQueue.addLast(pendingCompletionWork);
        }
        return true;
    }

    private void processPendingCompletionBacklog(long hardDeadlineNanos) {
        int slicesThisTick = pendingCompletionQueue.size();
        while (pendingAeReturn == null
                && !pendingCompletionQueue.isEmpty()
                && slicesThisTick-- > 0
                && !isDeadlineReached(hardDeadlineNanos)) {
            if (!processPendingCompletionWork(hardDeadlineNanos)) {
                break;
            }
        }
    }

    @Nullable
    private PendingAeReturn finalizePendingCompletionReturn(PendingCompletionWork pendingCompletion) {
        if (pendingCompletion == null || !pendingCompletion.isComplete()) {
            return null;
        }
        GenericStack primary = pendingCompletion.aggregatedPrimary();
        if (primary == null) {
            return null;
        }
        List<GenericStack> remainderStacks = new ArrayList<>(pendingCompletion.aggregatedRemainders().size());
        for (Map.Entry<AEItemKey, Long> entry : pendingCompletion.aggregatedRemainders().entrySet()) {
            if (entry.getValue() > 0) {
                remainderStacks.add(new GenericStack(entry.getKey(), entry.getValue()));
            }
        }
        return new PendingAeReturn(
                primary,
                List.copyOf(remainderStacks),
                pendingCompletion.totalExecutions(),
                pendingCompletion.completionRoute(),
                pendingCompletion.compiledTask().getSourceCraftingId()
        );
    }

    private boolean tryInitializeTemplatedCompletion(
            IMolecularAssemblerSupportedPattern pattern,
            PendingCompletionWork pendingCompletion
    ) {
        if (pendingCompletion.compiledTask().hasCompletionTemplate()) {
            pendingCompletion.setTemplate(
                    pendingCompletion.compiledTask().getCompletionTemplatePrimary(),
                    pendingCompletion.compiledTask().getCompletionTemplateRemainders()
            );
            return true;
        }
        SingleExecutionResult first = executeSingleCompletion(pattern, pendingCompletion.compiledTask());
        if (first == null) {
            return false;
        }
        SingleExecutionResult second = executeSingleCompletion(pattern, pendingCompletion.compiledTask());
        if (second == null || !first.matches(second)) {
            return false;
        }
        pendingCompletion.setTemplate(first.primary(), first.remainders());
        pendingCompletion.compiledTask().setCompletionTemplate(first.primary(), first.remainders());
        return true;
    }

    private @Nullable CompletionSliceResult applyTemplatedCompletionSlice(
            PendingCompletionWork pendingCompletion,
            int sliceExecutions
    ) {
        GenericStack templatePrimary = pendingCompletion.templatePrimary();
        if (templatePrimary == null || sliceExecutions <= 0) {
            return null;
        }
        GenericStack scaledPrimary = new GenericStack(
                templatePrimary.what(),
                Math.multiplyExact(templatePrimary.amount(), (long) sliceExecutions)
        );
        Map<AEItemKey, Long> scaledRemainders = new LinkedHashMap<>();
        for (Map.Entry<AEItemKey, Long> entry : pendingCompletion.templateRemainders().entrySet()) {
            long scaledAmount = Math.multiplyExact(entry.getValue(), (long) sliceExecutions);
            if (scaledAmount > 0) {
                scaledRemainders.put(entry.getKey(), scaledAmount);
            }
        }
        if (sliceExecutions > 1) {
        }
        return new CompletionSliceResult(sliceExecutions, scaledPrimary, Map.copyOf(scaledRemainders));
    }

    private @Nullable CompletionSliceResult applyIncrementalCompletionSlice(
            IMolecularAssemblerSupportedPattern pattern,
            PendingCompletionWork pendingCompletion,
            int sliceExecutions,
            long hardDeadlineNanos
    ) {
        int processedExecutions = 0;
        GenericStack aggregatedPrimary = null;
        Map<AEItemKey, Long> aggregatedRemainders = new LinkedHashMap<>();
        for (int iteration = 0; iteration < sliceExecutions; iteration++) {
            if (isDeadlineReached(hardDeadlineNanos)) {
                break;
            }
            SingleExecutionResult result = executeSingleCompletion(pattern, pendingCompletion.compiledTask());
            if (result == null) {
                return null;
            }
            if (aggregatedPrimary == null) {
                aggregatedPrimary = result.primary();
            } else if (aggregatedPrimary.what().equals(result.primary().what())) {
                aggregatedPrimary = new GenericStack(
                        aggregatedPrimary.what(),
                        safeAdd(aggregatedPrimary.amount(), result.primary().amount())
                );
            } else {
                return null;
            }
            for (Map.Entry<AEItemKey, Long> entry : result.remainders().entrySet()) {
                aggregatedRemainders.merge(entry.getKey(), entry.getValue(), AbstractHighCapacityCraftingHostBlockEntity::safeAdd);
            }
            processedExecutions++;
        }
        if (processedExecutions <= 0 || aggregatedPrimary == null) {
            return new CompletionSliceResult(0, null, Map.of());
        }
        return new CompletionSliceResult(processedExecutions, aggregatedPrimary, Map.copyOf(aggregatedRemainders));
    }

    private @Nullable PendingAeReturn createSlicePendingReturn(
            PendingCompletionWork pendingCompletionWork,
            CompletionSliceResult sliceResult
    ) {
        if (sliceResult.primary() == null || sliceResult.processedExecutions() <= 0) {
            return null;
        }
        List<GenericStack> remainderStacks = new ArrayList<>(sliceResult.remainders().size());
        for (Map.Entry<AEItemKey, Long> entry : sliceResult.remainders().entrySet()) {
            if (entry.getValue() > 0L) {
                remainderStacks.add(new GenericStack(entry.getKey(), entry.getValue()));
            }
        }
        PendingAeReturn pendingReturn = new PendingAeReturn(
                sliceResult.primary(),
                List.copyOf(remainderStacks),
                sliceResult.processedExecutions(),
                pendingCompletionWork.completionRoute(),
                pendingCompletionWork.compiledTask().getSourceCraftingId()
        );
        return pendingReturn;
    }

    @Nullable
    private SingleExecutionResult executeSingleCompletion(
            IMolecularAssemblerSupportedPattern pattern,
            CompiledTask compiledTask
    ) {
        ItemStack[] craftingGrid = compiledTask.getCraftingGridCopies();
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            craftingContainer.setItem(slot, craftingGrid[slot]);
        }
        var positionedInput = craftingContainer.asPositionedCraftInput();
        var craftingInput = positionedInput.input();
        ItemStack output = pattern.assemble(craftingInput, getLevel());
        if (output.isEmpty()) {
            return null;
        }
        GenericStack primary = normalizePrimaryOutput(pattern, output);
        if (primary == null) {
            return null;
        }
        Map<AEItemKey, Long> remainderTotals = new LinkedHashMap<>();
        for (ItemStack remainder : pattern.getRemainingItems(craftingInput)) {
            if (remainder.isEmpty()) {
                continue;
            }
            GenericStack genericRemainder = GenericStack.fromItemStack(remainder.copy());
            if (genericRemainder == null || !(genericRemainder.what() instanceof AEItemKey itemKey)) {
                return null;
            }
            remainderTotals.merge(itemKey, genericRemainder.amount(), Long::sum);
        }
        return new SingleExecutionResult(primary, remainderTotals);
    }

    private void finishCompletedReturn(PendingAeReturn completedReturn) {
        jobsCompleted += completedReturn.logicalExecutionCount();
        if (completedReturn.logicalExecutionCount() > 1) {
        }
    }

    private void tryFlushPendingAeReturn(long hardDeadlineNanos) {
        if (pendingAeReturn == null) {
            return;
        }
        if (!this.getMainNode().isActive() || getGrid() == null) {
            return;
        }
        if (waitingAeRetryTickCountdown > 0) {
            waitingAeRetryTickCountdown--;
            return;
        }
        long payloadBefore = countPayloadAmount(pendingAeReturn.pendingPayload());
        PendingAeReturn remainingPending = tryDeliverPendingReturn(pendingAeReturn, hardDeadlineNanos);
        if (remainingPending == null) {
            finishCompletedReturn(pendingAeReturn);
            pendingAeReturn = null;
            waitingAeRetryDelayTicks = 1;
            waitingAeRetryTickCountdown = 0;
            saveChanges();
            return;
        }
        pendingAeReturn = remainingPending;
        long payloadAfter = countPayloadAmount(remainingPending.pendingPayload());
        waitingAeRetryDelayTicks = payloadAfter < payloadBefore ? 1 : Math.min(20, waitingAeRetryDelayTicks * 2);
        waitingAeRetryTickCountdown = waitingAeRetryDelayTicks;
        saveChanges();
    }

    @Nullable
    private PendingAeReturn tryDeliverPendingReturn(PendingAeReturn pending, long hardDeadlineNanos) {
        List<GenericStack> payload = pending.completionRoute() == TaskCompletionRoute.CPU_WAITING
                ? orderCpuWaitingPayload(pending)
                : List.copyOf(pending.pendingPayload());
        if (payload.isEmpty()) {
            return null;
        }
        PayloadSlice payloadSlice = takePendingPayload(payload);
        cpuWaitingReturnStoppedThisTick = false;
        List<GenericStack> slicePayload = pending.completionRoute() == TaskCompletionRoute.CPU_WAITING
                ? routePayloadThroughCpu(pending, payloadSlice.slice(), hardDeadlineNanos)
                : payloadSlice.slice();
        if (slicePayload.isEmpty()) {
            return payloadSlice.remainder().isEmpty() ? null : pending.withPendingPayload(payloadSlice.remainder());
        }
        if (pending.completionRoute() == TaskCompletionRoute.CPU_WAITING && cpuWaitingReturnStoppedThisTick) {
            return pending.withPendingPayload(mergePayload(slicePayload, payloadSlice.remainder()));
        }
        if (pending.completionRoute() == TaskCompletionRoute.CPU_WAITING) {
            return pending.withPendingPayload(mergePayload(slicePayload, payloadSlice.remainder()));
        }
        List<GenericStack> remainingSlicePayload = routePayloadIntoAeNetwork(
                slicePayload,
                null,
                pending.sourceCraftingId()
        );
        if (remainingSlicePayload.isEmpty()) {
            return payloadSlice.remainder().isEmpty() ? null : pending.withPendingPayload(payloadSlice.remainder());
        }
        List<GenericStack> nextPayload =
                new ArrayList<>(remainingSlicePayload.size() + payloadSlice.remainder().size());
        nextPayload.addAll(remainingSlicePayload);
        nextPayload.addAll(payloadSlice.remainder());
        return pending.withPendingPayload(nextPayload);
    }

    private PayloadSlice takePendingPayload(List<GenericStack> payload) {
        if (payload.isEmpty()) {
            return new PayloadSlice(List.of(), List.of());
        }
        List<GenericStack> slice = new ArrayList<>(payload.size());
        for (GenericStack genericStack : payload) {
            if (genericStack == null || genericStack.amount() <= 0L) {
                continue;
            }
            slice.add(genericStack);
        }
        return new PayloadSlice(List.copyOf(slice), List.of());
    }

    private static long countPayloadAmount(List<GenericStack> payload) {
        long total = 0L;
        for (GenericStack stack : payload) {
            if (stack != null && stack.amount() > 0L) {
                if (total > Long.MAX_VALUE - stack.amount()) {
                    return Long.MAX_VALUE;
                }
                total += stack.amount();
            }
        }
        return total;
    }

    private static List<GenericStack> mergePayload(List<GenericStack> first, List<GenericStack> second) {
        List<GenericStack> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }

    private static String describeInputHolder(@Nullable KeyCounter[] inputHolder) {
        if (inputHolder == null) {
            return "null";
        }
        List<List<GenericStack>> snapshot = new ArrayList<>(inputHolder.length);
        for (KeyCounter slot : inputHolder) {
            snapshot.add(slot == null ? List.of() : flattenInputHolder(new KeyCounter[]{slot}));
        }
        return Arrays.toString(snapshot.toArray());
    }

    private record PayloadSlice(List<GenericStack> slice, List<GenericStack> remainder) {
    }

    private static IPatternDetails unwrapFormalDelegatingPattern(IPatternDetails patternDetails) {
        if (patternDetails instanceof FormalMachineDelegatingPattern delegatingPattern) {
            return delegatingPattern.basePattern();
        }
        return patternDetails;
    }

    private static List<GenericStack> flattenInputHolder(@Nullable KeyCounter[] inputHolder) {
        if (inputHolder == null || inputHolder.length == 0) {
            return List.of();
        }
        List<GenericStack> flattened = new ArrayList<>();
        for (KeyCounter slot : inputHolder) {
            if (slot == null) {
                continue;
            }
            for (var entry : slot) {
                if (entry.getKey() != null && entry.getLongValue() > 0L) {
                    flattened.add(new GenericStack(entry.getKey(), entry.getLongValue()));
                }
            }
        }
        return List.copyOf(flattened);
    }

    private List<GenericStack> orderCpuWaitingPayload(PendingAeReturn pending) {
        List<GenericStack> payload = pending.pendingPayload();
        if (payload.isEmpty() || pending.primaryResult() == null) {
            return List.copyOf(payload);
        }
        List<GenericStack> ordered = new ArrayList<>(payload.size());
        List<GenericStack> primaryStacks = new ArrayList<>();
        long primaryAmount = pending.primaryResult().amount();
        AEKey primaryKey = pending.primaryResult().what();
        for (GenericStack stack : payload) {
            if (stack == null || stack.amount() <= 0L) {
                continue;
            }
            if (primaryKey != null && primaryKey.equals(stack.what())) {
                primaryStacks.add(stack);
            } else {
                ordered.add(stack);
            }
        }
        primaryStacks.sort((left, right) -> Boolean.compare(
                right.amount() == primaryAmount,
                left.amount() == primaryAmount
        ));
        primaryStacks.addAll(ordered);
        ordered = primaryStacks;
        return List.copyOf(ordered);
    }

    private List<GenericStack> routePayloadThroughCpu(
            PendingAeReturn pending,
            List<GenericStack> payload,
            long hardDeadlineNanos
    ) {
        if (payload.isEmpty()) {
            return List.of();
        }
        SourceCpuHandle sourceCpu = findSourceCpuHandle(pending.sourceCraftingId());
        if (sourceCpu == null || !sourceCpu.isActive()) {
            return payload;
        }
        List<GenericStack> remainingPayload = new ArrayList<>(payload.size());
        for (GenericStack genericStack : payload) {
            if (genericStack == null || genericStack.what() == null || genericStack.amount() <= 0L) {
                continue;
            }
            if (isDeadlineReached(hardDeadlineNanos)) {
                cpuWaitingReturnStoppedThisTick = true;
                appendRemainingCpuWaitingPayload(payload, genericStack, remainingPayload, true);
                break;
            }
            AeCpuIngressRouter.StackRoutingResult routingResult = AeCpuIngressRouter.routeStackIntoSourceCpu(
                    actionSource,
                    genericStack,
                    sourceCpu
            );
            if (routingResult.remainingAmount() > 0L && routingResult.key() != null) {
                remainingPayload.add(new GenericStack(routingResult.key(), routingResult.remainingAmount()));
            }
            if (isDeadlineReached(hardDeadlineNanos)) {
                cpuWaitingReturnStoppedThisTick = true;
                appendRemainingCpuWaitingPayload(payload, genericStack, remainingPayload, false);
                break;
            }
        }
        return List.copyOf(remainingPayload);
    }

    private @Nullable SourceCpuHandle findSourceCpuHandle(UUID craftingId) {
        ICraftingService craftingService = getMainNode().getGrid().getCraftingService();
        if (!(craftingService instanceof CraftingService service)) {
            return null;
        }
        for (var candidate : service.getCpus()) {
            if (candidate instanceof CraftingCPUCluster cluster) {
                if (isMatchingNativeCpu(cluster, craftingId)) {
                    return new NativeSourceCpuHandle(cluster, craftingId);
                }
            }
            if (candidate instanceof ParallelCraftingCPU parallelCpu) {
                if (!parallelCpu.isActiveVirtualCpu()) {
                    continue;
                }
                ParallelCraftingCpuCluster cluster = parallelCpu.cluster();
                ParallelCraftingLane lane = cluster.findLaneByCraftingId(craftingId);
                if (lane != null && parallelCpu == cluster.findActiveCpuByCraftingId(craftingId)) {
                    return new ParallelActiveCpuHandle(cluster, craftingId);
                }
            }
        }
        return null;
    }

    private static boolean isMatchingNativeCpu(CraftingCPUCluster cpu, UUID craftingId) {
        ExecutingCraftingJob job = ((CraftingCpuLogicAccessor) cpu.craftingLogic).getJob();
        if (job == null) {
            return false;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        return accessor.getLink() != null && craftingId.equals(accessor.getLink().getCraftingID());
    }

    private void appendRemainingCpuWaitingPayload(
            List<GenericStack> payload,
            GenericStack processedStack,
            List<GenericStack> remainingPayload,
            boolean includeProcessedStack
    ) {
        boolean append = false;
        for (GenericStack candidate : payload) {
            if (candidate == processedStack) {
                append = true;
                if (!includeProcessedStack) {
                    continue;
                }
            }
            if (append && candidate != null && candidate.amount() > 0L) {
                remainingPayload.add(candidate);
            }
        }
    }

    private List<GenericStack> routePayloadIntoAeNetwork(
            List<GenericStack> payload,
            @Nullable SourceCpuHandle sourceCpu,
            @Nullable UUID sourceCraftingId
    ) {
        if (payload.isEmpty()) {
            return List.of();
        }
        CraftingService craftingService = getGridCraftingService();
        IStorageService storageService = getGridStorageService();
        if (craftingService == null && storageService == null) {
            return payload;
        }
        AeCpuIngressRouter.RoutingResult routingResult = AeCpuIngressRouter.routePayload(
                storageService,
                actionSource,
                payload,
                sourceCpu
        );
        recordIngressRoutingResult(routingResult, sourceCraftingId);
        return routingResult.remainingPayload();
    }

    private void recordIngressRoutingResult(
            AeCpuIngressRouter.RoutingResult routingResult,
            @Nullable UUID sourceCraftingId
    ) {
        if (routingResult == null) {
            return;
        }
    }

    @Nullable
    private CraftingService getGridCraftingService() {
        return getGrid() != null && getGrid().getCraftingService() instanceof CraftingService craftingService
                ? craftingService
                : null;
    }

    @Nullable
    private IStorageService getGridStorageService() {
        return getGrid() == null ? null : getGrid().getStorageService();
    }

    @Nullable
    public static ItemStack encodeCraftingPatternForTest(Level level, ItemStack[] craftingGrid) {
        if (craftingGrid.length != 9) {
            throw new IllegalArgumentException("craftingGrid must have exactly 9 slots");
        }

        NonNullList<ItemStack> padded = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int i = 0; i < craftingGrid.length; i++) {
            padded.set(i, craftingGrid[i] == null ? ItemStack.EMPTY : craftingGrid[i].copy());
        }
        CraftingInput recipeInput = CraftingInput.of(3, 3, padded);
        RecipeHolder<CraftingRecipe> recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, recipeInput, level)
                .orElse(null);
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = recipe.value().assemble(recipeInput, level.registryAccess());
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack[] encodedInputs = new ItemStack[9];
        for (int i = 0; i < craftingGrid.length; i++) {
            encodedInputs[i] = craftingGrid[i] == null ? ItemStack.EMPTY : craftingGrid[i].copy();
        }
        return PatternDetailsHelper.encodeCraftingPattern(recipe, encodedInputs, result, false, false);
    }

    private boolean submitCompiledTaskForTest(IMolecularAssemblerSupportedPattern pattern, int executionCount) {
        KeyCounter[] inputHolders = new KeyCounter[pattern.getInputs().length];
        for (int index = 0; index < pattern.getInputs().length; index++) {
            inputHolders[index] = new KeyCounter();
            for (GenericStack possibleInput : pattern.getInputs()[index].getPossibleInputs()) {
                if (possibleInput != null) {
                    inputHolders[index].add(
                            possibleInput.what(),
                            possibleInput.amount() * pattern.getInputs()[index].getMultiplier()
                    );
                    break;
                }
            }
        }
        return offerCompiledTask(pattern.getDefinition().toStack(), pattern, inputHolders, Math.max(1, executionCount));
    }

    private boolean submitCompiledTaskForTest(IMolecularAssemblerSupportedPattern pattern) {
        return submitCompiledTaskForTest(pattern, 1);
    }

    void updateExecutionPeaks() {
        LinkedHashSet<AEItemKey> uniqueDefinitions = new LinkedHashSet<>();
        for (CompiledTask task : localExecutionQueue.getActiveTasks()) {
            if (!task.getPatternDefinition().isEmpty()) {
                uniqueDefinitions.add(AEItemKey.of(task.getPatternDefinition()));
            }
        }
        for (PendingCompletionWork pendingCompletionWork : pendingCompletionQueue) {
            if (!pendingCompletionWork.compiledTask().getPatternDefinition().isEmpty()) {
                uniqueDefinitions.add(AEItemKey.of(pendingCompletionWork.compiledTask().getPatternDefinition()));
            }
        }
        updateCompletionBacklogPeaks();
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
        recordSearchMetrics(matchResult);
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
        recordSearchMetrics(matchResult);
        for (int globalSlot : matchResult.matches()) {
            if (globalSlot >= pageStart && globalSlot < pageEnd) {
                mask |= 1 << (globalSlot - pageStart);
            }
        }
        return mask;
    }

    public HighCapacityCraftingMachineStatus getMachineStatus() {
        if (!getMainNode().isActive() || getGrid() == null) {
            return HighCapacityCraftingMachineStatus.NETWORK_OFFLINE;
        }
        if (pendingAeReturn != null) {
            return HighCapacityCraftingMachineStatus.WAITING_AE_STORAGE;
        }
        if (!pendingCompletionQueue.isEmpty() || !localExecutionQueue.isIdle()) {
            return HighCapacityCraftingMachineStatus.RUNNING;
        }
        return HighCapacityCraftingMachineStatus.IDLE;
    }

    public boolean isWaitingAeReturn() {
        return pendingAeReturn != null;
    }

    public boolean isPendingCompletionWork() {
        return !pendingCompletionQueue.isEmpty();
    }

    public boolean hasPendingCompletionBacklog() {
        return !pendingCompletionQueue.isEmpty();
    }

    public long getPendingAeReturnCount() {
        return pendingAeReturn == null ? 0 : 1;
    }

    public int getPendingLogicalExecutionCountForTest() {
        if (pendingAeReturn != null) {
            return pendingAeReturn.logicalExecutionCount();
        }
        return getPendingCompletionLogicalExecutionCountInternal();
    }

    public int getPendingCompletionTaskCountForTest() {
        return getPendingCompletionTaskCountInternal();
    }

    public int getPendingCompletionLogicalExecutionsForTest() {
        return getPendingCompletionLogicalExecutionCountInternal();
    }

    public IItemHandler getAutomationItemHandler() {
        return automationItemHandler;
    }

    public void gotoPage(int pageIndex) {
        setActivePage(pageIndex);
    }

    protected void serverTick() {
        if (!ChexsonsaeutilsCompatibilityConfig.boolValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_CRAFTING_MACHINE_ENABLED)) {
            return;
        }
        long tickStartedAt = System.nanoTime();
        long hardDeadlineNanos = safeAdd(tickStartedAt, configTickHardBudgetNanos());
        loadExternalPatternsIfNeeded();
        localPatternProviderFacade.refreshDirtyPatterns();
        requestProviderUpdateIfLocalPatternsChanged();
        forceProviderRefreshIfReady();
        recalculateBudgetModel();
        localExecutionQueue.reconfigureActiveLanes(currentBudgetModel);
        if (pendingAeReturn != null) {
            tryFlushPendingAeReturn(hardDeadlineNanos);
            updateExecutionPeaks();
            updateBudgetDeltas();
            finishTickBudget(tickStartedAt);
            return;
        }
        localExecutionQueue.tick(this);
        if (!pendingCompletionQueue.isEmpty() && pendingAeReturn == null && !isTickBudgetHardStopped(tickStartedAt)) {
            processPendingCompletionBacklog(hardDeadlineNanos);
        }
        updateExecutionPeaks();
        updateBudgetDeltas();
        finishTickBudget(tickStartedAt);
    }

    private boolean isTickBudgetHardStopped(long tickStartedAt) {
        long elapsed = Math.max(0L, System.nanoTime() - tickStartedAt);
        if (elapsed < configTickHardBudgetNanos()) {
            return false;
        }
        lastTickBudgetSnapshot = new FormalMachineTickBudgetSnapshot(
                configTickSoftBudgetNanos(),
                configTickHardBudgetNanos(),
                configTickAbsoluteBudgetNanos(),
                elapsed,
                true
        );
        return true;
    }

    private void finishTickBudget(long tickStartedAt) {
        long elapsed = Math.max(0L, System.nanoTime() - tickStartedAt);
        boolean hardStop = elapsed >= configTickHardBudgetNanos();
        lastTickBudgetSnapshot = new FormalMachineTickBudgetSnapshot(
                configTickSoftBudgetNanos(),
                configTickHardBudgetNanos(),
                configTickAbsoluteBudgetNanos(),
                elapsed,
                hardStop
        );
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + Math.max(0L, right);
    }

    private static int saturatedIntAdd(int left, int right) {
        long value = (long) Math.max(0, left) + Math.max(0, right);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int saturatedLongToInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long safeLongMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

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

    private void loadExternalPatternsIfNeeded() {
        if (externalPatternsLoaded || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        pagedPatternInventory.loadFromExternalSnapshot(HighCapacityPatternHostSavedData.get(serverLevel).snapshotSlots(worldPosition));
        externalPatternsLoaded = true;
        localPatternProviderFacade.markAllDirty();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    private static boolean isDeadlineReached(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0L;
    }

    private static boolean isDeadlinePastAbsoluteBudget(long hardDeadlineNanos) {
        long absoluteDeadlineNanos = safeAdd(
                hardDeadlineNanos,
                Math.max(0L, configTickAbsoluteBudgetNanos() - configTickHardBudgetNanos())
        );
        return System.nanoTime() - absoluteDeadlineNanos >= 0L;
    }

    private void persistPatternSlot(int slot) {
        if (!(level instanceof ServerLevel serverLevel) || slot < 0 || slot >= pagedPatternInventory.getTotalSlots()) {
            return;
        }
        HighCapacityPatternHostSavedData.get(serverLevel).setSlot(worldPosition, slot, pagedPatternInventory.getVirtualSlot(slot));
    }

    private void persistAllPatterns() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        HighCapacityPatternHostSavedData savedData = HighCapacityPatternHostSavedData.get(serverLevel);
        savedData.removeHost(worldPosition);
        for (int slot = 0; slot < pagedPatternInventory.getTotalSlots(); slot++) {
            ItemStack stack = pagedPatternInventory.getVirtualSlot(slot);
            if (!stack.isEmpty()) {
                savedData.setSlot(worldPosition, slot, stack);
            }
        }
    }
    private void persistCleanup() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        HighCapacityPatternHostSavedData.get(serverLevel).removeHost(worldPosition);
    }

    private void markQueueMutationForSave(boolean forceSave) {
        setChanged();
        if (forceSave) {
            flushQueuedMutationsToDisk();
            return;
        }
        if (unsavedQueueMutationCount >= configQueueProgressSaveInterval()) {
            flushQueuedMutationsToDisk();
        }
    }

    private void flushQueuedMutationsToDisk() {
        unsavedQueueMutationCount = 0;
        saveChanges();
    }

    private int findMaxExecutionCountAcrossQueue() {
        int max = 0;
        for (CompiledTask task : localExecutionQueue.getAllTasks()) {
            max = Math.max(max, task.getExecutionCount());
        }
        for (PendingCompletionWork pendingCompletionWork : pendingCompletionQueue) {
            max = Math.max(max, pendingCompletionWork.compiledTask().getExecutionCount());
        }
        return max;
    }

    private int getOutstandingLogicalExecutionsForSnapshot() {
        int count = localExecutionQueue.countOutstandingLogicalExecutions();
        for (PendingCompletionWork pendingCompletionWork : pendingCompletionQueue) {
            count = saturatedIntAdd(count, pendingCompletionWork.remainingExecutions());
        }
        return count;
    }

    public DynamicExecutionBudgetModel getCurrentBudgetModel() {
        return currentBudgetModel;
    }

    public int getInstalledSpeedCardCount() {
        return getInstalledUpgrades(AEItems.SPEED_CARD);
    }

    public int getDistinctBatchKeyCount() {
        return localExecutionQueue.countDistinctBatchKeys();
    }

    private int getPendingCompletionTaskCountInternal() {
        return pendingCompletionQueue.size();
    }

    private int getPendingCompletionLogicalExecutionCountInternal() {
        int count = 0;
        for (PendingCompletionWork pendingCompletionWork : pendingCompletionQueue) {
            count = saturatedIntAdd(count, pendingCompletionWork.remainingExecutions());
        }
        return count;
    }

    private void updateCompletionBacklogPeaks() {
    }

    private double getCompletionBacklogPressureRatio() {
        return getPendingCompletionLogicalExecutionCountInternal()
                / (double) Math.max(1, currentBudgetModel.completionSliceBudget());
    }

    private boolean isCompletionBacklogHardPressured() {
        if (pendingAeReturn != null) {
            return true;
        }
        return getCompletionBacklogPressureRatio() >= Math.max(8.0D, getPreferredLaneFloor() * 4.0D);
    }

    public int getWaitingPenaltyForBudget() {
        return 0;
    }

    public int getPreferredLaneFloor() {
        return Math.max(1, computeLaneCount(getInstalledSpeedCardCount()));
    }

    private void recalculateBudgetModel() {
        int previousLaneCount = lastEffectiveLaneCount;
        currentBudgetModel = DynamicExecutionBudgetModel.fromHost(this);
        lastCompletionBudget = currentBudgetModel.completionTarget();
        lastDispatchBudget = currentBudgetModel.dispatchTarget();
        lastCompletionSliceBudget = currentBudgetModel.completionSliceBudget();
        lastSoftBudget = currentBudgetModel.softBudget();
        lastHardBudget = currentBudgetModel.hardBudget();
        lastEffectiveLaneCount = currentBudgetModel.laneActivationTarget();
        if (lastEffectiveLaneCount > previousLaneCount) {
        } else if (lastEffectiveLaneCount < previousLaneCount) {
        }
    }

    private void updateBudgetDeltas() {
        lastObservedLargestBatch = Math.max(lastObservedLargestBatch, findMaxExecutionCountAcrossQueue());
    }

    private void maybeAttachCompletionTemplate(
            @Nullable IMolecularAssemblerSupportedPattern pattern,
            @Nullable CompiledTask compiledTask
    ) {
        if (compiledTask == null) {
            return;
        }
        if (pattern instanceof FormalMachineAggregatedPattern aggregatedPattern) {
            attachAggregatedCompletionTemplate(aggregatedPattern, compiledTask);
            return;
        }
        if (compiledTask.hasCompletionTemplate()) {
            compiledTask.setSupportsTemplatedCompletion(true);
            return;
        }
        if (!supportsCompletionTemplate(pattern)) {
            compiledTask.setSupportsTemplatedCompletion(false);
            return;
        }
        compiledTask.setSupportsTemplatedCompletion(true);
        CompletionTemplate template = probeStableCompletionTemplate(getLevel(), pattern, compiledTask);
        if (template != null) {
            compiledTask.setCompletionTemplate(template.primary(), template.remainders());
        }
    }

    private void attachAggregatedCompletionTemplate(
            FormalMachineAggregatedPattern aggregatedPattern,
            CompiledTask compiledTask
    ) {
        if (aggregatedPattern == null || compiledTask == null || aggregatedPattern.aggregatedOutputs().isEmpty()) {
            return;
        }
        GenericStack primaryOutput = aggregatedPattern.aggregatedOutputs().getFirst();
        if (primaryOutput == null || primaryOutput.what() == null || primaryOutput.amount() <= 0L) {
            compiledTask.setSupportsTemplatedCompletion(false);
            return;
        }
        Map<AEItemKey, Long> remainderTotals = new LinkedHashMap<>();
        for (GenericStack remainder : aggregatedPattern.aggregatedRemainders()) {
            if (remainder != null && remainder.what() instanceof AEItemKey itemKey && remainder.amount() > 0L) {
                remainderTotals.merge(itemKey, remainder.amount(), AbstractHighCapacityCraftingHostBlockEntity::safeAdd);
            }
        }
        compiledTask.setCompletionTemplate(primaryOutput, remainderTotals);
        compiledTask.setSupportsTemplatedCompletion(true);
    }

    private record CompletionSliceResult(
            int processedExecutions,
            @Nullable GenericStack primary,
            Map<AEItemKey, Long> remainders
    ) {
    }

}
