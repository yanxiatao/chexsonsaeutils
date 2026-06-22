package git.chexson.chexsonsaeutils.blockentity.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.me.service.CraftingService;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.blockentity.crafting.DecodedPatternEntryCache;
import git.chexson.chexsonsaeutils.blockentity.crafting.DirtySlotPatternRefreshScheduler;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPatternInventory;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPatternProvider;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackSupport;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeIndexBuilder;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeIndex;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeIndexCache;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeReloadTracker;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeUserConfigStore;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportReasonCode;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportStatus;
import git.chexson.chexsonsaeutils.crafting.directprocessing.PatternCompatibility;
import git.chexson.chexsonsaeutils.crafting.directprocessing.PatternCompatibilityCache;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineAggregatedPattern;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachinePlanningProvider;
import git.chexson.chexsonsaeutils.crafting.directprocessing.PendingOutputBatch;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingCompiledTask;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingExecutionBudget;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingExecutionQueue;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingLatencyOrigin;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingTaskCompletionHost;
import git.chexson.chexsonsaeutils.crafting.AeCpuIngressRouter;
import git.chexson.chexsonsaeutils.crafting.SourceCpuHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class AEDirectProcessingMachineBlockEntity extends AENetworkedBlockEntity
        implements ICraftingProvider, IUpgradeableObject, PatternContainer, InternalInventoryHost,
        ProcessingTaskCompletionHost, FormalMachinePlanningProvider {

    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_MACHINE_BINDING = "machineBinding";
    private static final String NBT_PATTERN_SLOTS = "patternSlots";
    private static final String NBT_SLOT = "slot";
    private static final String NBT_STACK = "stack";
    private static final String NBT_EXECUTION_QUEUE = "executionQueue";
    private static final String NBT_PENDING_OUTPUT = "pendingOutput";
    private static final String NBT_DISCOVERY_EPOCH = "discoveryEpoch";
    private static final String NBT_PENDING_OUTPUT_RETRY_DELAY = "pendingOutputRetryDelay";
    private static final String NBT_PENDING_OUTPUT_RETRY_BACKOFF = "pendingOutputRetryBackoff";
    private static final String NBT_BATCH_PAYLOAD = "payload";
    private static final String NBT_BATCH_SOURCE_CRAFTING_ID = "sourceCraftingId";
    private static final int TOTAL_PATTERN_SLOTS = 16_384;
    private static final int PAGE_SIZE = 27;
    private static final int UPGRADE_SLOTS = 5;
    private static final int DEFAULT_OPERATION_TICKS = 20;
    private static final int MAX_QUEUE_TASKS = 256;
    private static final int MAX_PENDING_OUTPUT_BATCHES = 1_024;
    private static final int MAX_PENDING_OUTPUT_RETRY_DELAY_TICKS = 20;
    private static final int DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK = 64;

    private final MachineSource actionSource = new MachineSource(this);
    private final IUpgradeInventory upgrades;
    private final AppEngInternalInventory machineBindingInventory = new AppEngInternalInventory(this, 1, 1);
    private final DirtySlotPatternRefreshScheduler refreshScheduler =
            new DirtySlotPatternRefreshScheduler(TOTAL_PATTERN_SLOTS);
    private final DecodedPatternEntryCache decodedPatternEntryCache =
            new DecodedPatternEntryCache(TOTAL_PATTERN_SLOTS);
    private final DirectProcessingPatternInventory patternInventory =
            new DirectProcessingPatternInventory(this, refreshScheduler, TOTAL_PATTERN_SLOTS, PAGE_SIZE);
    private final DirectProcessingPatternProvider patternProvider = new DirectProcessingPatternProvider();
    private final PatternCompatibilityCache compatibilityCache = new PatternCompatibilityCache();
    private final ProcessingExecutionQueue executionQueue = new ProcessingExecutionQueue(MAX_QUEUE_TASKS);
    private final DirectProcessingItemHandler automationItemHandler = new DirectProcessingItemHandler(this);
    private final Map<Integer, IPatternDetails> supportedPatternsBySlot = new LinkedHashMap<>();
    private final Map<Integer, PatternCompatibility> patternCompatibilityBySlot = new LinkedHashMap<>();
    private final Map<Integer, AEItemKey> patternDefinitionsBySlot = new LinkedHashMap<>();
    private final Map<AEItemKey, Set<Integer>> slotsByPatternDefinition = new LinkedHashMap<>();

    private MachineRecipeIndexBuilder discoveryService = MachineRecipeIndexBuilder.fromConfig();
    private MachineRecipeIndex recipeIndex = MachineRecipeIndex.empty();
    private ProcessingExecutionBudget budgetController = createConfiguredBudgetController();
    private long discoveryEpoch;
    private long recipeManagerEpoch;
    private long observedConfigMappingEpoch = Long.MIN_VALUE;
    private long observedRecipeReloadEpoch = Long.MIN_VALUE;
    private String observedBudgetProfile = "";
    private boolean observedGenericDiscoveryEnabled;
    private long recipeDiscoveryCount;
    private long recipeFullScanCount;
    private long dirtyRefreshScanCount;
    private long pushPatternCacheLookupCount;
    private long pushPatternAcceptedCount;
    private long pushPatternRejectedCount;
    private long completedTaskCount;
    private long completedLogicalExecutionCount;
    private long pendingOutputRetryCount;
    private long outputReturnBudgetRejectedCount;
    private long pushToAeReturnLatencyTicksTotal;
    private long pushToAeReturnLatencyTicksMax;
    private long pushToAeReturnLatencySampleCount;
    private long dirtyRefreshWallNanosMax;
    private long pushPatternCacheLookupNanosMax;
    private final ArrayDeque<PendingOutputBatch> pendingOutputBatches = new ArrayDeque<>();
    private int pendingOutputRetryDelayTicks;
    private int pendingOutputRetryBackoffTicks;
    private boolean machineRecipeIndexRefreshPending;

    public AEDirectProcessingMachineBlockEntity(BlockPos pos, BlockState blockState) {
        super(Chexsonsaeutils.AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY.get(), pos, blockState);
        this.upgrades = UpgradeInventories.forMachine(
                () -> Chexsonsaeutils.AE_DIRECT_PROCESSING_MACHINE_ITEM.get(),
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
            AEDirectProcessingMachineBlockEntity blockEntity
    ) {
        if (!level.isClientSide()) {
            blockEntity.serverTick();
        }
    }

    @Override
    public void onReady() {
        super.onReady();
        observedConfigMappingEpoch = MachineRecipeConfigMappingRegistry.instance().epoch();
        observedRecipeReloadEpoch = MachineRecipeReloadTracker.recipeReloadEpoch();
        observedGenericDiscoveryEnabled = currentGenericDiscoveryEnabled();
        markMachineRecipeIndexDirty();
        refreshMachineRecipeIndexIfReady();
        markAllPatternsDirty();
        refreshDirtyPatterns();
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        upgrades.writeToNBT(data, NBT_UPGRADES, registries);
        data.put(NBT_MACHINE_BINDING, getMachineBindingStack().saveOptional(registries));
        data.putLong(NBT_DISCOVERY_EPOCH, discoveryEpoch);
        data.putInt(NBT_PENDING_OUTPUT_RETRY_DELAY, pendingOutputRetryDelayTicks);
        data.putInt(NBT_PENDING_OUTPUT_RETRY_BACKOFF, pendingOutputRetryBackoffTicks);
        executionQueue.writeToTag(data, NBT_EXECUTION_QUEUE, registries);
        if (!pendingOutputBatches.isEmpty()) {
            data.put(NBT_PENDING_OUTPUT, writePendingOutputBatches(registries));
        }
        writePatternSlots(data, registries);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        upgrades.readFromNBT(data, NBT_UPGRADES, registries);
        machineBindingInventory.setItemDirect(
                0,
                data.contains(NBT_MACHINE_BINDING)
                        ? ItemStack.parseOptional(registries, data.getCompound(NBT_MACHINE_BINDING))
                        : ItemStack.EMPTY
        );
        discoveryEpoch = data.contains(NBT_DISCOVERY_EPOCH) ? data.getLong(NBT_DISCOVERY_EPOCH) : 0L;
        pendingOutputRetryDelayTicks = data.contains(NBT_PENDING_OUTPUT_RETRY_DELAY)
                ? data.getInt(NBT_PENDING_OUTPUT_RETRY_DELAY)
                : 0;
        pendingOutputRetryBackoffTicks = data.contains(NBT_PENDING_OUTPUT_RETRY_BACKOFF)
                ? data.getInt(NBT_PENDING_OUTPUT_RETRY_BACKOFF)
                : 0;
        executionQueue.readFromTag(data, NBT_EXECUTION_QUEUE, registries);
        pendingOutputBatches.clear();
        if (data.contains(NBT_PENDING_OUTPUT)) {
            readPendingOutputBatches(registries, data.getList(NBT_PENDING_OUTPUT, Tag.TAG_COMPOUND));
        }
        readPatternSlots(data, registries);
        clearPatternExposureCaches();
        observedConfigMappingEpoch = MachineRecipeConfigMappingRegistry.instance().epoch();
        observedRecipeReloadEpoch = MachineRecipeReloadTracker.recipeReloadEpoch();
        observedGenericDiscoveryEnabled = currentGenericDiscoveryEnabled();
        discoveryService = MachineRecipeIndexBuilder.fromConfig();
        markMachineRecipeIndexDirty();
        markAllPatternsDirty();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inventory) {
        saveChanges();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inventory, int slot) {
        if (inventory == machineBindingInventory) {
            onMachineBindingChanged();
            return;
        }
        if (inventory == patternInventory.getActivePageInventory()) {
            patternInventory.onActivePageSlotChanged(slot);
            saveChanges();
        }
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patternProvider.availablePatterns();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        long startedAtNanos = System.nanoTime();
        pushPatternCacheLookupCount++;

        if (patternDetails instanceof FormalMachineAggregatedPattern aggregatedPattern) {
            recordPushPatternCacheLookupNanos(startedAtNanos);
            return pushAggregatedPattern(aggregatedPattern, inputHolder);
        }

        PatternCompatibility compatibility = patternDetails == null
                ? null
                : compatibilityCache.get(patternDetails.getDefinition(), recipeIndex.version());
        recordPushPatternCacheLookupNanos(startedAtNanos);
        if (compatibility == null || !compatibility.supported()) {
            pushPatternRejectedCount++;
            return false;
        }
        if (!this.getMainNode().isActive()
                || pendingOutputRetryDelayTicks > 0
                || pendingOutputBatches.size() >= MAX_PENDING_OUTPUT_BATCHES
                || executionQueue.totalTaskCount() >= MAX_QUEUE_TASKS) {
            pushPatternRejectedCount++;
            return false;
        }
        ProcessingCompiledTask task = ProcessingCompiledTask.compile(
                compatibility.pattern(),
                compatibility.signature(),
                inputHolder,
                getCurrentOperationTicks()
        );
        Level currentLevel = getLevel();
        long acceptedTick = currentLevel == null ? -1L : currentLevel.getGameTime();
        if (task == null || !executionQueue.offer(task, budgetController, acceptedTick)) {
            pushPatternRejectedCount++;
            return false;
        }
        pushPatternAcceptedCount++;
        saveChanges();
        return true;
    }

    private boolean pushAggregatedPattern(
            FormalMachineAggregatedPattern aggregatedPattern,
            KeyCounter[] inputHolder
    ) {
        if (aggregatedPattern == null
                || inputHolder == null
                || !aggregatedPattern.hostLocator().matches(this)) {
            pushPatternRejectedCount++;
            return false;
        }
        if (!this.getMainNode().isActive()
                || pendingOutputRetryDelayTicks > 0
                || pendingOutputBatches.size() >= MAX_PENDING_OUTPUT_BATCHES
                || executionQueue.totalTaskCount() >= MAX_QUEUE_TASKS) {
            pushPatternRejectedCount++;
            return false;
        }

        ProcessingCompiledTask task = ProcessingCompiledTask.compileAggregated(
                aggregatedPattern,
                inputHolder,
                aggregatedPattern.totalTicks()
        );
        Level currentLevel = getLevel();
        long acceptedTick = currentLevel == null ? -1L : currentLevel.getGameTime();
        if (task == null || !executionQueue.offer(task, budgetController, acceptedTick)) {
            pushPatternRejectedCount++;
            return false;
        }
        pushPatternAcceptedCount++;
        saveChanges();
        return true;
    }

    @Override
    public boolean isBusy() {
        return pendingOutputRetryDelayTicks > 0
                || pendingOutputBatches.size() >= MAX_PENDING_OUTPUT_BATCHES
                || executionQueue.totalTaskCount() >= MAX_QUEUE_TASKS;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(
                appeng.api.stacks.AEItemKey.of(Chexsonsaeutils.AE_DIRECT_PROCESSING_MACHINE_ITEM.get()),
                getName(),
                List.of()
        );
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return patternInventory.getActivePageInventory();
    }

    public InternalInventory getMachineBindingInventory() {
        return machineBindingInventory;
    }

    @Override
    public appeng.api.networking.IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public void completeProcessingTask(ProcessingCompiledTask task) {
        completeProcessingTask(task, null);
    }

    @Override
    public void completeProcessingTask(ProcessingCompiledTask task, @Nullable ProcessingLatencyOrigin latencyOrigin) {
        completedTaskCount++;
        if (task != null) {
            completedLogicalExecutionCount = saturatingAdd(completedLogicalExecutionCount, task.executionCount());
        }
        enqueuePendingOutput(
                task.buildOutputPayload(),
                latencyOrigin,
                task == null ? null : task.sourceCraftingId()
        );
        tryFlushPendingOutput();
        saveChanges();
    }

    @Override
    public boolean isWaitingForOutputReturn() {
        return !pendingOutputBatches.isEmpty();
    }

    @Override
    public int getProcessingWorkUnitsPerTick() {
        return Math.max(1, 1 + getInstalledUpgrades(AEItems.SPEED_CARD));
    }

    public IItemHandler getAutomationItemHandler() {
        return automationItemHandler;
    }

    @Override
    public void clearContent() {
        machineBindingInventory.setItemDirect(0, ItemStack.EMPTY);
        for (int slot = 0; slot < patternInventory.getTotalSlots(); slot++) {
            patternInventory.setVirtualSlot(slot, ItemStack.EMPTY);
        }
        executionQueue.clear();
        pendingOutputBatches.clear();
        clearPatternExposureCaches();
        pendingOutputRetryDelayTicks = 0;
        pendingOutputRetryBackoffTicks = 0;
    }

    public ItemStack getMachineBindingStack() {
        return machineBindingInventory.getStackInSlot(0);
    }

    public void setMachineBindingStack(ItemStack stack) {
        machineBindingInventory.setItemDirect(0, stack == null ? ItemStack.EMPTY : stack.copyWithCount(1));
        onMachineBindingChanged();
    }

    public boolean isSupportedBindingMachine(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    public boolean isProcessingPattern(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(AEComponents.ENCODED_PROCESSING_PATTERN);
    }

    public int getTotalPatternSlots() {
        return patternInventory.getTotalSlots();
    }

    public int getVisiblePatternSlots() {
        return patternInventory.getPageSize();
    }

    public int getPageIndex() {
        return patternInventory.getActivePage();
    }

    public int getPageCount() {
        return patternInventory.getPageCount();
    }

    public void nextPage() {
        setActivePage(patternInventory.getActivePage() + 1);
    }

    public void previousPage() {
        setActivePage(patternInventory.getActivePage() - 1);
    }

    public void setActivePage(int pageIndex) {
        patternInventory.setActivePage(pageIndex);
        saveChanges();
    }

    public int toGlobalPatternSlotIndex(int pageSlotIndex) {
        return patternInventory.getActivePage() * patternInventory.getPageSize() + pageSlotIndex;
    }

    public ItemStack getPatternAt(int slot) {
        return patternInventory.getVirtualSlot(slot);
    }

    public void setPatternAt(int slot, ItemStack stack) {
        patternInventory.setVirtualSlot(slot, stack == null ? ItemStack.EMPTY : stack.copyWithCount(1));
        saveChanges();
    }

    public int getActivePatternCount() {
        return supportedPatternsBySlot.size();
    }

    public String getDetectedRecipeTypeSummaryForMenu() {
        List<ResourceLocation> recipeTypeIds = recipeIndex.recipeTypeIds();
        if (recipeTypeIds.isEmpty()) {
            return "-";
        }
        StringBuilder summary = new StringBuilder();
        int limit = Math.min(2, recipeTypeIds.size());
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                summary.append(", ");
            }
            summary.append(recipeTypeIds.get(index));
        }
        if (recipeTypeIds.size() > limit) {
            summary.append(" +").append(recipeTypeIds.size() - limit);
        }
        String keyTypeSummary = recipeIndex.keyTypeSummary();
        if (!"-".equals(keyTypeSummary)) {
            summary.append(" [").append(keyTypeSummary).append(']');
        }
        return summary.toString();
    }

    public String getVisiblePatternStatusSnapshotForMenu() {
        int visibleSlots = Math.max(1, patternInventory.getPageSize());
        int firstSlot = patternInventory.getActivePage() * visibleSlots;
        StringBuilder snapshot = new StringBuilder(visibleSlots * 6);
        for (int index = 0; index < visibleSlots; index++) {
            if (index > 0) {
                snapshot.append(';');
            }
            PatternCompatibility compatibility = patternCompatibilityBySlot.get(firstSlot + index);
            MachineSupportStatus status = compatibility == null
                    ? MachineSupportStatus.UNSUPPORTED_UNREADABLE
                    : compatibility.status();
            MachineSupportReasonCode reason = compatibility == null
                    ? MachineSupportReasonCode.PATTERN_DECODE_FAILED
                    : compatibility.reasonCode();
            snapshot.append(status.ordinal()).append(':').append(reason.ordinal());
        }
        return snapshot.toString();
    }

    @Nullable
    public MachineIdentity getMachineIdentityForMenu() {
        return MachineIdentity.fromBindingStack(getMachineBindingStack());
    }

    public boolean importUserConfigMappingForMenu(MachineRecipeConfigImportRequest request) {
        MachineIdentity currentIdentity = getMachineIdentityForMenu();
        Level currentLevel = getLevel();
        if (currentIdentity == null
                || currentLevel == null
                || request == null
                || request.recipeTypeIds().isEmpty()
                || !matchesCurrentBinding(currentIdentity, request)) {
            return false;
        }
        MachineRecipeConfigImportRequest appliedRequest = discoveryService.validateImportRequest(
                currentLevel,
                currentIdentity,
                request
        );
        if (appliedRequest == null || appliedRequest.recipeTypeIds().isEmpty()) {
            return false;
        }
        MachineRecipeUserConfigStore.instance().upsertMappingAndApply(appliedRequest, currentLevel.registryAccess());
        observedConfigMappingEpoch = MachineRecipeConfigMappingRegistry.instance().epoch();
        discoveryEpoch++;
        invalidatePatternExposureForIndexChange();
        saveChanges();
        return true;
    }

    public MachineSupportStatus getPatternStatus(int slot) {
        PatternCompatibility compatibility = patternCompatibilityBySlot.get(slot);
        return compatibility == null ? MachineSupportStatus.UNSUPPORTED_UNREADABLE : compatibility.status();
    }

    public int countVisiblePatternStatusForMenu(MachineSupportStatus status) {
        if (status == null) {
            return 0;
        }
        int count = 0;
        int firstSlot = patternInventory.getActivePage() * patternInventory.getPageSize();
        int endSlot = Math.min(patternInventory.getTotalSlots(), firstSlot + patternInventory.getPageSize());
        for (int slot = firstSlot; slot < endSlot; slot++) {
            if (!patternInventory.getVirtualSlot(slot).isEmpty() && getPatternStatus(slot) == status) {
                count++;
            }
        }
        return count;
    }

    public int getQueuedTaskCountForMenu() {
        return executionQueue.queuedTaskCount();
    }

    public int getRunningTaskCountForMenu() {
        return executionQueue.runningTaskCount();
    }

    public void invalidateDiscoveryForRecipeReload() {
        discoveryEpoch++;
        recipeManagerEpoch++;
        invalidatePatternExposureForIndexChange();
        saveChanges();
    }

    private void onMachineBindingChanged() {
        discoveryEpoch++;
        invalidatePatternExposureForIndexChange();
        saveChanges();
    }

    private void markMachineRecipeIndexDirty() {
        machineRecipeIndexRefreshPending = true;
    }

    private void invalidatePatternExposureForIndexChange() {
        markMachineRecipeIndexDirty();
        clearPatternExposureCaches();
        markAllPatternsDirty();
        requestCraftingProviderUpdate();
    }

    private void clearPatternExposureCaches() {
        compatibilityCache.clear();
        supportedPatternsBySlot.clear();
        patternCompatibilityBySlot.clear();
        patternDefinitionsBySlot.clear();
        slotsByPatternDefinition.clear();
        patternProvider.clear();
    }

    private void refreshMachineRecipeIndexIfReady() {
        if (!machineRecipeIndexRefreshPending || executionQueue.isBusy() || !pendingOutputBatches.isEmpty()) {
            return;
        }
        if (!budgetController.hasTimeBudget(System.nanoTime())) {
            return;
        }
        rebuildMachineRecipeIndex();
        machineRecipeIndexRefreshPending = false;
    }

    private void rebuildMachineRecipeIndex() {
        Level currentLevel = getLevel();
        MachineIdentity identity = MachineIdentity.fromBindingStack(getMachineBindingStack());
        if (currentLevel == null || identity == null) {
            recipeIndex = MachineRecipeIndex.empty();
            return;
        }
        recipeDiscoveryCount++;
        MachineRecipeIndexCache.BuildResult result = MachineRecipeIndexCache.instance().getOrBuild(
                currentLevel,
                identity,
                recipeManagerEpoch,
                MachineRecipeConfigMappingRegistry.instance().epoch(),
                discoveryEpoch,
                discoveryService::buildIndexTemplate
        );
        recipeIndex = result.index();
        if (!result.cacheHit()) {
            recipeFullScanCount++;
        }
    }

    private void markAllPatternsDirty() {
        refreshScheduler.markRangeDirty(0, patternInventory.getTotalSlots());
    }

    private void refreshDirtyPatterns() {
        if (!refreshScheduler.hasPendingWork() || machineRecipeIndexRefreshPending) {
            return;
        }
        long startedAtNanos = System.nanoTime();
        Level currentLevel = getLevel();
        if (currentLevel == null) {
            return;
        }
        List<Integer> dirtySlots = refreshScheduler.drainDirtySlots(DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK);
        dirtyRefreshScanCount += dirtySlots.size();
        boolean changed = false;
        for (int slot : dirtySlots) {
            changed |= refreshSinglePatternSlot(currentLevel, slot);
        }
        if (changed) {
            if (patternProvider.replaceFromSupportedSlots(supportedPatternsBySlot)) {
                requestCraftingProviderUpdate();
            }
        }
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        dirtyRefreshWallNanosMax = Math.max(dirtyRefreshWallNanosMax, elapsedNanos);
    }

    private boolean refreshSinglePatternSlot(Level currentLevel, int slot) {
        ItemStack stack = patternInventory.getVirtualSlot(slot);
        if (stack.isEmpty()) {
            decodedPatternEntryCache.invalidate(slot);
            return replacePatternExposureForSlot(slot, null, null, null);
        }
        if (decodedPatternEntryCache.matches(slot, stack)) {
            @Nullable DecodedPatternEntryCache.Entry entry = decodedPatternEntryCache.get(slot);
            if (entry != null) {
                return updateCompatibility(slot, entry.patternDetails());
            }
        }
        IPatternDetails decodedPattern = PatternDetailsHelper.decodePattern(stack, currentLevel);
        decodedPatternEntryCache.put(slot, stack, decodedPattern);
        return updateCompatibility(slot, decodedPattern);
    }

    private boolean updateCompatibility(int slot, @Nullable IPatternDetails decodedPattern) {
        if (decodedPattern == null || !isProcessingPattern(decodedPattern.getDefinition().toStack())) {
            return replacePatternExposureForSlot(
                    slot,
                    decodedPattern == null ? null : decodedPattern.getDefinition(),
                    null,
                    PatternCompatibility.unsupported(MachineSupportReasonCode.PATTERN_DECODE_FAILED)
            );
        }
        PatternCompatibility compatibility = discoveryService.compileCompatibility(getLevel(), recipeIndex, decodedPattern);
        return replacePatternExposureForSlot(
                slot,
                decodedPattern.getDefinition(),
                compatibility.supported() ? decodedPattern : null,
                compatibility
        );
    }

    private void serverTick() {
        long startedAtNanos = System.nanoTime();
        try {
            budgetController.resetForTick(startedAtNanos);
            refreshConfigMappingsIfChanged();
            refreshMachineRecipeIndexIfReady();
            refreshDirtyPatterns();
            if (!pendingOutputBatches.isEmpty()) {
                if (pendingOutputRetryDelayTicks > 0) {
                    pendingOutputRetryDelayTicks--;
                    return;
                }
                pendingOutputRetryCount++;
                tryFlushPendingOutput();
            }
            executionQueue.tick(this, getProcessingLaneCount(), budgetController);
            tryFlushPendingOutput();
        } finally {
        }
    }

    private void refreshConfigMappingsIfChanged() {
        long currentConfigMappingEpoch = MachineRecipeConfigMappingRegistry.instance().epoch();
        if (observedConfigMappingEpoch != currentConfigMappingEpoch) {
            observedConfigMappingEpoch = currentConfigMappingEpoch;
            discoveryEpoch++;
            invalidatePatternExposureForIndexChange();
            saveChanges();
        }
        long currentRecipeReloadEpoch = MachineRecipeReloadTracker.recipeReloadEpoch();
        if (observedRecipeReloadEpoch != currentRecipeReloadEpoch) {
            observedRecipeReloadEpoch = currentRecipeReloadEpoch;
            discoveryEpoch++;
            recipeManagerEpoch++;
            invalidatePatternExposureForIndexChange();
            saveChanges();
        }
        refreshBudgetProfileIfChanged();
        refreshDiscoveryConfigIfChanged();
    }

    private void refreshBudgetProfileIfChanged() {
        String currentBudgetProfile = currentBudgetProfileName();
        if (currentBudgetProfile.equals(observedBudgetProfile)) {
            return;
        }
        observedBudgetProfile = currentBudgetProfile;
        budgetController = createConfiguredBudgetController();
    }

    private static ProcessingExecutionBudget createConfiguredBudgetController() {
        return ProcessingExecutionBudget.forProfile(currentBudgetProfileName());
    }

    private static String currentBudgetProfileName() {
        return ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_BUDGET_PROFILE.get();
    }

    private void refreshDiscoveryConfigIfChanged() {
        boolean currentGenericDiscoveryEnabled = currentGenericDiscoveryEnabled();
        if (observedGenericDiscoveryEnabled == currentGenericDiscoveryEnabled) {
            return;
        }
        observedGenericDiscoveryEnabled = currentGenericDiscoveryEnabled;
        discoveryService = MachineRecipeIndexBuilder.fromConfig();
        discoveryEpoch++;
        invalidatePatternExposureForIndexChange();
        saveChanges();
    }

    private static boolean currentGenericDiscoveryEnabled() {
        return ChexsonsaeutilsCompatibilityConfig
                .AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED
                .get();
    }

    private static boolean matchesCurrentBinding(
            MachineIdentity currentIdentity,
            MachineRecipeConfigImportRequest request
    ) {
        return Objects.equals(currentIdentity.machineItemId(), request.machineItemId())
                && Objects.equals(currentIdentity.blockId(), request.machineBlockId());
    }

    private boolean replacePatternExposureForSlot(
            int slot,
            @Nullable AEItemKey patternDefinition,
            @Nullable IPatternDetails supportedPattern,
            @Nullable PatternCompatibility compatibility
    ) {
        IPatternDetails previousSupportedPattern = supportedPatternsBySlot.get(slot);
        AEItemKey previousDefinition = patternDefinitionsBySlot.get(slot);
        boolean sameDefinition = Objects.equals(previousDefinition, patternDefinition);
        if (!sameDefinition) {
            unlinkPatternDefinition(slot, previousDefinition);
        }
        if (compatibility == null) {
            patternCompatibilityBySlot.remove(slot);
        } else {
            patternCompatibilityBySlot.put(slot, compatibility);
        }
        if (supportedPattern == null || compatibility == null || !compatibility.supported()) {
            supportedPatternsBySlot.remove(slot);
        } else {
            supportedPatternsBySlot.put(slot, supportedPattern);
        }
        if (patternDefinition == null) {
            patternDefinitionsBySlot.remove(slot);
        } else {
            patternDefinitionsBySlot.put(slot, patternDefinition);
            slotsByPatternDefinition
                    .computeIfAbsent(patternDefinition, ignored -> new LinkedHashSet<>())
                    .add(slot);
            recacheCompatibilityForDefinition(patternDefinition);
        }
        IPatternDetails currentSupportedPattern = supportedPatternsBySlot.get(slot);
        return !Objects.equals(
                previousSupportedPattern == null ? null : previousSupportedPattern.getDefinition(),
                currentSupportedPattern == null ? null : currentSupportedPattern.getDefinition()
        );
    }

    private void unlinkPatternDefinition(int slot, @Nullable AEItemKey patternDefinition) {
        patternDefinitionsBySlot.remove(slot);
        if (patternDefinition == null) {
            return;
        }
        Set<Integer> slots = slotsByPatternDefinition.get(patternDefinition);
        if (slots == null) {
            compatibilityCache.remove(patternDefinition, recipeIndex.version());
            return;
        }
        slots.remove(slot);
        if (slots.isEmpty()) {
            slotsByPatternDefinition.remove(patternDefinition);
            compatibilityCache.remove(patternDefinition, recipeIndex.version());
            return;
        }
        recacheCompatibilityForDefinition(patternDefinition);
    }

    private void recacheCompatibilityForDefinition(AEItemKey patternDefinition) {
        PatternCompatibility compatibility = selectCompatibilityForDefinition(
                slotsByPatternDefinition.get(patternDefinition),
                patternCompatibilityBySlot
        );
        if (compatibility == null) {
            compatibilityCache.remove(patternDefinition, recipeIndex.version());
            return;
        }
        compatibilityCache.put(patternDefinition, recipeIndex.version(), compatibility);
    }

    static @Nullable PatternCompatibility selectCompatibilityForDefinition(
            @Nullable Set<Integer> slots,
            Map<Integer, PatternCompatibility> compatibilityBySlot
    ) {
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        PatternCompatibility fallback = null;
        for (Integer slot : slots) {
            PatternCompatibility compatibility = compatibilityBySlot.get(slot);
            if (compatibility == null) {
                continue;
            }
            if (compatibility.supported()) {
                return compatibility;
            }
            if (fallback == null) {
                fallback = compatibility;
            }
        }
        return fallback;
    }

    @Override
    public int getCurrentOperationTicks() {
        int speedCards = getInstalledUpgrades(AEItems.SPEED_CARD);
        return Math.max(1, DEFAULT_OPERATION_TICKS >> Math.min(4, speedCards));
    }

    private int getProcessingLaneCount() {
        return Math.max(1, 1 + getInstalledUpgrades(AEItems.SPEED_CARD));
    }

    private void tryFlushPendingOutput() {
        if (pendingOutputBatches.isEmpty()) {
            return;
        }
        long startedAtNanos = System.nanoTime();
        try {
            while (!pendingOutputBatches.isEmpty() && budgetController.hasTimeBudget(System.nanoTime())) {
                PendingOutputBatch batch = pendingOutputBatches.peekFirst();
                if (batch == null || batch.isEmpty()) {
                    pendingOutputBatches.removeFirst();
                    continue;
                }
                CraftingService craftingService = getMainNode().getGrid() != null
                        && getMainNode().getGrid().getCraftingService() instanceof CraftingService service
                        ? service
                        : null;
                IStorageService storageService = getMainNode().getGrid() == null
                        ? null
                        : getMainNode().getGrid().getStorageService();
                List<GenericStack> rejectedSlice = AeCpuIngressRouter.routePayload(storageService, actionSource, batch.payload(), null).remainingPayload();
                List<GenericStack> remainingPayload = DirectProcessingStackSupport.normalizeStacks(rejectedSlice);
                if (!remainingPayload.isEmpty()) {
                    pendingOutputBatches.removeFirst();
                    pendingOutputBatches.addFirst(batch.withPayload(remainingPayload));
                    markPendingOutputReturnRejected();
                    return;
                }
                pendingOutputBatches.removeFirst();
                recordPushToAeReturnLatency(batch.latencyOrigin());
            }
            pendingOutputRetryDelayTicks = 0;
            pendingOutputRetryBackoffTicks = 0;
        } finally {
        }
    }

    private void markPendingOutputReturnRejected() {
        outputReturnBudgetRejectedCount++;
        pendingOutputRetryBackoffTicks = nextPendingOutputRetryDelay(pendingOutputRetryBackoffTicks);
        pendingOutputRetryDelayTicks = pendingOutputRetryBackoffTicks;
    }

    private static int nextPendingOutputRetryDelay(int currentDelay) {
        if (currentDelay <= 0) {
            return 1;
        }
        return Math.min(MAX_PENDING_OUTPUT_RETRY_DELAY_TICKS, currentDelay * 2);
    }

    private void enqueuePendingOutput(
            List<GenericStack> payload,
            @Nullable ProcessingLatencyOrigin latencyOrigin,
            @Nullable UUID sourceCraftingId
    ) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        PendingOutputBatch tail = pendingOutputBatches.peekLast();
        if (tail != null && Objects.equals(tail.sourceCraftingId(), sourceCraftingId)) {
            List<GenericStack> mergedPayload = tryMergePayloads(tail.payload(), payload);
            if (!mergedPayload.isEmpty()) {
                pendingOutputBatches.removeLast();
                pendingOutputBatches.addLast(new PendingOutputBatch(
                        mergedPayload,
                        mergeLatencyOrigins(tail.latencyOrigin(), latencyOrigin),
                        sourceCraftingId
                ));
                return;
            }
        }
        pendingOutputBatches.addLast(new PendingOutputBatch(payload, latencyOrigin, sourceCraftingId));
    }

    private static List<GenericStack> tryMergePayloads(List<GenericStack> left, List<GenericStack> right) {
        if (left == null || left.isEmpty()) {
            return DirectProcessingStackSupport.normalizeStacks(right);
        }
        if (right == null || right.isEmpty()) {
            return DirectProcessingStackSupport.normalizeStacks(left);
        }
        List<GenericStack> merged = new ArrayList<>(left.size() + right.size());
        merged.addAll(left);
        merged.addAll(right);
        return DirectProcessingStackSupport.normalizeStacks(merged);
    }

    @Nullable
    private static ProcessingLatencyOrigin mergeLatencyOrigins(
            @Nullable ProcessingLatencyOrigin left,
            @Nullable ProcessingLatencyOrigin right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.merge(right);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private void recordPushPatternCacheLookupNanos(long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        pushPatternCacheLookupNanosMax = Math.max(pushPatternCacheLookupNanosMax, elapsedNanos);
    }

    private void recordPushToAeReturnLatency(@Nullable ProcessingLatencyOrigin latencyOrigin) {
        if (latencyOrigin == null || latencyOrigin.acceptedPushCount() <= 0) {
            return;
        }
        Level currentLevel = getLevel();
        if (currentLevel == null) {
            return;
        }
        long latencyTicks = latencyOrigin.averageLatencyTicks(currentLevel.getGameTime());
        pushToAeReturnLatencyTicksTotal = saturatingAdd(pushToAeReturnLatencyTicksTotal, latencyTicks);
        pushToAeReturnLatencyTicksMax = Math.max(pushToAeReturnLatencyTicksMax, latencyTicks);
        pushToAeReturnLatencySampleCount = saturatingAdd(
                pushToAeReturnLatencySampleCount,
                latencyOrigin.acceptedPushCount()
        );
    }

    private void requestCraftingProviderUpdate() {
        if (getMainNode().getNode() != null) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    private void writePatternSlots(CompoundTag data, HolderLookup.Provider registries) {
        ListTag slots = new ListTag();
        for (int slot = 0; slot < patternInventory.getTotalSlots(); slot++) {
            ItemStack stack = patternInventory.getVirtualSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt(NBT_SLOT, slot);
            slotTag.put(NBT_STACK, stack.saveOptional(registries));
            slots.add(slotTag);
        }
        data.put(NBT_PATTERN_SLOTS, slots);
    }

    private void readPatternSlots(CompoundTag data, HolderLookup.Provider registries) {
        for (int slot = 0; slot < patternInventory.getTotalSlots(); slot++) {
            patternInventory.setVirtualSlot(slot, ItemStack.EMPTY);
        }
        ListTag slots = data.getList(NBT_PATTERN_SLOTS, Tag.TAG_COMPOUND);
        for (Tag entry : slots) {
            if (entry instanceof CompoundTag slotTag) {
                int slot = slotTag.getInt(NBT_SLOT);
                ItemStack stack = ItemStack.parseOptional(registries, slotTag.getCompound(NBT_STACK));
                patternInventory.setVirtualSlot(slot, stack);
            }
        }
    }

    private static ListTag writeStacks(HolderLookup.Provider registries, List<GenericStack> stacks) {
        ListTag tag = new ListTag();
        for (GenericStack stack : stacks) {
            if (stack != null) {
                tag.add(GenericStack.writeTag(registries, stack));
            }
        }
        return tag;
    }

    private static List<GenericStack> readStacks(HolderLookup.Provider registries, ListTag tag) {
        List<GenericStack> stacks = new ArrayList<>();
        for (Tag entry : tag) {
            if (entry instanceof CompoundTag stackTag) {
                GenericStack stack = GenericStack.readTag(registries, stackTag);
                if (stack != null) {
                    stacks.add(stack);
                }
            }
        }
        return List.copyOf(stacks);
    }

    private ListTag writePendingOutputBatches(HolderLookup.Provider registries) {
        ListTag tag = new ListTag();
        for (PendingOutputBatch batch : pendingOutputBatches) {
            if (batch == null || batch.isEmpty()) {
                continue;
            }
            CompoundTag batchTag = new CompoundTag();
            batchTag.put(NBT_BATCH_PAYLOAD, writeStacks(registries, batch.payload()));
            if (batch.sourceCraftingId() != null) {
                batchTag.putUUID(NBT_BATCH_SOURCE_CRAFTING_ID, batch.sourceCraftingId());
            }
            tag.add(batchTag);
        }
        return tag;
    }

    private void readPendingOutputBatches(HolderLookup.Provider registries, ListTag tag) {
        for (Tag entry : tag) {
            if (entry instanceof CompoundTag batchTag && batchTag.contains(NBT_BATCH_PAYLOAD)) {
                List<GenericStack> payload = readStacks(
                        registries,
                        batchTag.getList(NBT_BATCH_PAYLOAD, Tag.TAG_COMPOUND)
                );
                UUID sourceCraftingId = batchTag.hasUUID(NBT_BATCH_SOURCE_CRAFTING_ID)
                        ? batchTag.getUUID(NBT_BATCH_SOURCE_CRAFTING_ID)
                        : null;
                if (!payload.isEmpty()) {
                    pendingOutputBatches.addLast(new PendingOutputBatch(payload, null, sourceCraftingId));
                }
                continue;
            }
            if (entry instanceof CompoundTag stackTag) {
                GenericStack stack = GenericStack.readTag(registries, stackTag);
                if (stack != null) {
                    pendingOutputBatches.addLast(new PendingOutputBatch(List.of(stack), null, null));
                }
            }
        }
    }
}
