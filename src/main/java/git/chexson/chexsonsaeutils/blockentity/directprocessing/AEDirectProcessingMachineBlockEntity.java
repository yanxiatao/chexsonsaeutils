package git.chexson.chexsonsaeutils.blockentity.directprocessing;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.block.AEBaseEntityBlock;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.me.service.CraftingService;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import git.chexson.chexsonsaeutils.bigint.ClampedMath;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.menu.matrix.DecodedPatternEntryCache;
import git.chexson.chexsonsaeutils.menu.matrix.DirtySlotPatternRefreshScheduler;
import git.chexson.chexsonsaeutils.menu.matrix.PatternSlotStore;
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
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchProvider;
import git.chexson.chexsonsaeutils.crafting.directprocessing.PendingOutputBatch;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingTask;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingExecutionBudget;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingExecutionQueue;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingLatencyOrigin;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingTaskCompletionHost;
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.SourceCpuHandle;
import git.chexson.chexsonsaeutils.crafting.SourceCpuResolver;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchHost;
import git.chexson.chexsonsaeutils.integration.directprocessing.DirectProcessingAssembly;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixDisplay;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSearch;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class AEDirectProcessingMachineBlockEntity extends AENetworkedBlockEntity
        implements ICraftingProvider, PatternContainer, InternalInventoryHost,
        ProcessingTaskCompletionHost, HostBatchProvider, NeoEcoBatchHost, PatternMatrixSource {

    /** 标记与实体样板分库存；机器页提供四行九列。 */
    public static final int MACHINE_BINDING_SLOTS = 36;
    private static final String NBT_MACHINE_BINDINGS = "machineBindings";
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
    /** 固定满速：等价于旧版插满 5 张速度卡（一拍加工完、六路并发）。 */
    private static final int FULL_SPEED_OPERATION_TICKS = 1;
    private static final int FULL_SPEED_CONCURRENCY = 6;

    private static int configTotalPatternSlots() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_TOTAL_PATTERN_SLOTS);
    }

    private static int configMaxQueueTasks() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_MAX_QUEUE_TASKS);
    }

    private static int configMaxPendingOutputBatches() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_BATCHES);
    }

    private static int configMaxPendingOutputRetryDelayTicks() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_RETRY_DELAY_TICKS);
    }

    private static int configDirtyPatternRefreshBudgetPerTick() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK);
    }

    private final MachineSource actionSource = new MachineSource(this);
    private final AppEngInternalInventory machineBindingInventory = new AppEngInternalInventory(this, MACHINE_BINDING_SLOTS, 1);
    private final DirtySlotPatternRefreshScheduler refreshScheduler =
            new DirtySlotPatternRefreshScheduler(configTotalPatternSlots());
    private final DecodedPatternEntryCache decodedPatternEntryCache =
            new DecodedPatternEntryCache(configTotalPatternSlots());
    // 样板随方块 NBT 整体落盘，无需逐槽回写，只保留脏槽标记
    private final PatternSlotStore patternStore = new PatternSlotStore(
            this, refreshScheduler, configTotalPatternSlots(), slot -> {
    });
    private final DirectProcessingPatternProvider patternProvider = new DirectProcessingPatternProvider();
    private final PatternCompatibilityCache compatibilityCache = new PatternCompatibilityCache();
    private final ProcessingExecutionQueue executionQueue = new ProcessingExecutionQueue(configMaxQueueTasks());
    private final DirectProcessingItemHandler automationItemHandler = new DirectProcessingItemHandler(this);
    private final Map<Integer, IPatternDetails> supportedPatternsBySlot = new LinkedHashMap<>();
    private final Map<Integer, PatternCompatibility> patternCompatibilityBySlot = new LinkedHashMap<>();
    private final Map<Integer, AEItemKey> patternDefinitionsBySlot = new LinkedHashMap<>();
    private final Map<AEItemKey, Set<Integer>> slotsByPatternDefinition = new LinkedHashMap<>();

    private MachineRecipeIndexBuilder discoveryService = DirectProcessingAssembly.createMachineRecipeIndexBuilder();
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
        super(((AEBaseEntityBlock<?>) blockState.getBlock()).getBlockEntityType(), pos, blockState);
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
        data.put(NBT_MACHINE_BINDING, getMachineBindingStack().saveOptional(registries));
        ListTag bindings = new ListTag();
        for (int slot = 1; slot < MACHINE_BINDING_SLOTS; slot++) {
            ItemStack stack = machineBindingInventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt(NBT_SLOT, slot);
            entry.put(NBT_STACK, stack.saveOptional(registries));
            bindings.add(entry);
        }
        data.put(NBT_MACHINE_BINDINGS, bindings);
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
        machineBindingInventory.setItemDirect(
                0,
                data.contains(NBT_MACHINE_BINDING)
                        ? ItemStack.parseOptional(registries, data.getCompound(NBT_MACHINE_BINDING))
                        : ItemStack.EMPTY
        );
        for (int slot = 1; slot < MACHINE_BINDING_SLOTS; slot++) {
            machineBindingInventory.setItemDirect(slot, ItemStack.EMPTY);
        }
        for (Tag tag : data.getList(NBT_MACHINE_BINDINGS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tag;
            int slot = entry.getInt(NBT_SLOT);
            if (slot <= 0 || slot >= MACHINE_BINDING_SLOTS) {
                throw new IllegalArgumentException("无效直连处理机标记槽: " + slot);
            }
            machineBindingInventory.setItemDirect(slot, ItemStack.parseOptional(registries, entry.getCompound(NBT_STACK)));
        }
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
        discoveryService = DirectProcessingAssembly.createMachineRecipeIndexBuilder();
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
        if (inventory == patternStore.inventory()) {
            patternStore.onSlotWritten(slot);
        }
        saveChanges();
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patternProvider.availablePatterns();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        long startedAtNanos = System.nanoTime();
        pushPatternCacheLookupCount++;

        if (patternDetails instanceof HostBatchPattern aggregatedPattern) {
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
                || pendingOutputBatches.size() >= configMaxPendingOutputBatches()
                || executionQueue.totalTaskCount() >= configMaxQueueTasks()) {
            pushPatternRejectedCount++;
            return false;
        }
        ProcessingTask task = ProcessingTask.compile(
                compatibility,
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

    @Override
    public long maxPushExecutions(IPatternDetails pattern) {
        // 执行计数与整批工时都已换 long，本机不再按 int 记份数，于是回报上限与
        // 声明批走同一条容量口径。
        return chexsonsaeutils$getNeoEcoBatchCapacity(pattern);
    }

    @Override
    public long chexsonsaeutils$getNeoEcoBatchCapacity(IPatternDetails pattern) {
        PatternCompatibility compatibility = pattern == null
                ? null
                : compatibilityCache.get(pattern.getDefinition(), recipeIndex.version());
        if (compatibility == null || !compatibility.supported()
                || compatibility.signature() == null
                || !getMainNode().isActive()
                || pendingOutputRetryDelayTicks > 0
                || pendingOutputBatches.size() >= configMaxPendingOutputBatches()
                || executionQueue.remainingCapacity() <= 0) {
            return 0L;
        }
        // 一次 push 只占一个任务槽，份数本身没有队列上界；真能夹住份数的是
        // 「整批工时 = 份数 × 每发 tick」与「产出 = 份数 × 每发量」这两处 long 乘法，
        // 越界即 compileNeoEcoBatch 拒收，所以容量按它们反推。
        // 这里原来挂的是 Math.min(Integer.MAX_VALUE, ...)：当年执行计数与工时都是 int，
        // 份数一过 2.1e9 乘法就溢出成负数，要么整批拒收要么饱和提速，于是干脆用 int
        // 上限兜住。两处都换 long 后，兜底改成 long 上界反推——仍然拒收真装不下的份数，
        // 但不再在 2.1e9 处凭空夹断（状态屏上那份恒为 2.1T 的读数就是这个夹子）。
        long tickCapacity = Long.MAX_VALUE / (long) Math.max(1, getCurrentOperationTicks());
        long payloadCapacity = Long.MAX_VALUE;
        for (GenericStack output : compatibility.pattern().getOutputs()) {
            if (output != null && output.amount() > 0L) {
                payloadCapacity = Math.min(payloadCapacity, Long.MAX_VALUE / output.amount());
            }
        }
        return Math.min(tickCapacity, payloadCapacity);
    }

    @Override
    public boolean chexsonsaeutils$pushNeoEcoBatch(
            IPatternDetails pattern,
            List<GenericStack> inputs,
            List<GenericStack> outputs,
            List<GenericStack> remainders,
            UUID craftingJobId,
            long count
    ) {
        if (chexsonsaeutils$getNeoEcoBatchCapacity(pattern) <= 0L) {
            return false;
        }
        PatternCompatibility compatibility = compatibilityCache.get(
                pattern.getDefinition(),
                recipeIndex.version()
        );
        ProcessingTask task = null;
        for (var signature : compatibility.signatures()) {
            task = ProcessingTask.compileNeoEcoBatch(compatibility.pattern(), signature,
                    inputs, outputs, remainders, count, getCurrentOperationTicks(), craftingJobId);
            if (task != null) break;
        }
        Level currentLevel = getLevel();
        long acceptedTick = currentLevel == null ? -1L : currentLevel.getGameTime();
        if (task == null || !executionQueue.offer(task, budgetController, acceptedTick)) {
            return false;
        }
        pushPatternAcceptedCount++;
        saveChanges();
        return true;
    }

    private boolean pushAggregatedPattern(
            HostBatchPattern aggregatedPattern,
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
                || pendingOutputBatches.size() >= configMaxPendingOutputBatches()
                || executionQueue.totalTaskCount() >= configMaxQueueTasks()) {
            pushPatternRejectedCount++;
            return false;
        }

        ProcessingTask task = ProcessingTask.compileAggregated(
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
                || pendingOutputBatches.size() >= configMaxPendingOutputBatches()
                || executionQueue.totalTaskCount() >= configMaxQueueTasks();
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(
                appeng.api.stacks.AEItemKey.of(getItemFromBlockEntity()),
                getName(),
                List.of(Component.translatable(
                        "block.chexsonsaeutils.ae_direct_processing_machine.pattern_slots_summary",
                        patternStore.countNonEmptySlots(),
                        patternStore.getTotalSlots()
                ))
        );
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return patternStore.inventory();
    }

    public InternalInventory getMachineBindingInventory() {
        return machineBindingInventory;
    }

    @Override
    public appeng.api.networking.IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public void completeProcessingTask(ProcessingTask task) {
        completeProcessingTask(task, null);
    }

    @Override
    public void completeProcessingTask(ProcessingTask task, @Nullable ProcessingLatencyOrigin latencyOrigin) {
        completedTaskCount++;
        if (task != null) {
            completedLogicalExecutionCount = ClampedMath.add(completedLogicalExecutionCount, task.executionCount());
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
        return FULL_SPEED_CONCURRENCY;
    }

    public IItemHandler getAutomationItemHandler() {
        return automationItemHandler;
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int slot = 0; slot < MACHINE_BINDING_SLOTS; slot++) {
            ItemStack bindingStack = machineBindingInventory.getStackInSlot(slot);
            if (!bindingStack.isEmpty()) drops.add(bindingStack.copy());
        }
        for (int slot = 0; slot < patternStore.getTotalSlots(); slot++) {
            ItemStack stack = patternStore.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
        for (PendingOutputBatch batch : pendingOutputBatches) {
            if (batch == null || batch.isEmpty()) continue;
            for (GenericStack stack : batch.payload()) {
                if (stack != null && stack.what() != null) {
                    stack.what().addDrops(stack.amount(), drops, level, pos);
                }
            }
        }
    }

    @Override
    public void clearContent() {
        for (int slot = 0; slot < MACHINE_BINDING_SLOTS; slot++) {
            machineBindingInventory.setItemDirect(slot, ItemStack.EMPTY);
        }
        patternStore.clearAll();
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

    /** 自动化写入标记槽后立即使合并索引失效。 */
    public void setMachineBindingAt(int slot, ItemStack stack) {
        machineBindingInventory.setItemDirect(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        onMachineBindingChanged();
    }

    public boolean isSupportedBindingMachine(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var rules = level != null && level.isClientSide()
                ? git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRulesRegistry.clientCurrent()
                : git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRulesRegistry.current();
        return (stack.getItem() instanceof BlockItem
                || rules.isCustomMarker(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())))
                && rules.allowsMachine(MachineIdentity.fromBindingStack(stack));
    }

    public boolean isProcessingPattern(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(AEComponents.ENCODED_PROCESSING_PATTERN);
    }

    public int getTotalPatternSlots() {
        return patternStore.getTotalSlots();
    }

    public int getUsedPatternSlots() {
        return patternStore.countNonEmptySlots();
    }

    public ItemStack getPatternAt(int slot) {
        return patternStore.getStackInSlot(slot);
    }

    public void setPatternAt(int slot, ItemStack stack) {
        patternStore.setStackInSlot(slot, stack);
    }

    @Override
    public InternalInventory patternMatrixInventory() {
        return patternStore.inventory();
    }

    @Override
    public int patternMatrixTotalSlots() {
        return patternStore.getTotalSlots();
    }

    @Override
    public ItemStack patternMatrixDisplayStack(int slot) {
        return PatternMatrixDisplay.displayStackFor(
                decodedPatternEntryCache,
                slot,
                patternStore.getStackInSlot(slot),
                getLevel()
        );
    }

    @Override
    public int patternMatrixStatusCode(int slot) {
        return compatibilityOf(slot).status().ordinal();
    }

    @Override
    public int patternMatrixReasonCode(int slot) {
        return compatibilityOf(slot).reasonCode().ordinal();
    }

    @Override
    public long patternMatrixDetailVersion() {
        return discoveryEpoch;
    }

    @Override
    public boolean patternMatrixMayPlace(ItemStack stack) {
        return isProcessingPattern(stack);
    }

    @Override
    public int patternMatrixSearchMatches(String query, long[] matchBits) {
        return PatternMatrixSearch.findMatches(
                patternStore, decodedPatternEntryCache, getLevel(), query, matchBits);
    }

    @Override
    public PatternMatrixSearchTerms patternMatrixSearchTerms(int slot) {
        var entry = decodedPatternEntryCache.get(slot);
        return PatternMatrixSearchTerms.from(
                entry == null ? null : entry.patternDetails());
    }

    private PatternCompatibility compatibilityOf(int slot) {
        PatternCompatibility compatibility = patternCompatibilityBySlot.get(slot);
        return compatibility == null
                ? PatternCompatibility.unsupported(MachineSupportStatus.PENDING_REFRESH, MachineSupportReasonCode.NONE)
                : compatibility;
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

    @Nullable
    public MachineIdentity getMachineIdentityForMenu() {
        return MachineIdentity.fromBindingStack(getMachineBindingStack());
    }

    /** 所有标记对应的唯一机器身份；同种机器重复存入不会重复扫描配方。 */
    public List<MachineIdentity> getMachineIdentitiesForMenu() {
        Set<MachineIdentity> identities = new LinkedHashSet<>();
        for (int slot = 0; slot < MACHINE_BINDING_SLOTS; slot++) {
            ItemStack binding = machineBindingInventory.getStackInSlot(slot);
            if (!isSupportedBindingMachine(binding)) continue;
            MachineIdentity identity = MachineIdentity.fromBindingStack(binding);
            if (identity != null) identities.add(identity);
        }
        return List.copyOf(identities);
    }

    public boolean importUserConfigMappingForMenu(MachineRecipeConfigImportRequest request) {
        MachineIdentity currentIdentity = getMachineIdentitiesForMenu().stream()
                .filter(identity -> request != null && matchesCurrentBinding(identity, request))
                .findFirst().orElse(null);
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
        try {
            if (!MachineRecipeUserConfigStore.instance().upsertMappingAndApply(
                    appliedRequest, currentLevel.registryAccess())) return true;
        } catch (RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().error("直连处理机配方导入失败", exception);
            return false;
        }
        observedConfigMappingEpoch = MachineRecipeConfigMappingRegistry.instance().epoch();
        discoveryEpoch++;
        invalidatePatternExposureForIndexChange();
        saveChanges();
        return true;
    }

    public MachineSupportStatus getPatternStatus(int slot) {
        return compatibilityOf(slot).status();
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
        if (currentLevel == null) {
            recipeIndex = MachineRecipeIndex.empty();
            return;
        }
        List<MachineRecipeIndex> indices = new ArrayList<>();
        for (MachineIdentity identity : getMachineIdentitiesForMenu()) {
            recipeDiscoveryCount++;
            MachineRecipeIndexCache.BuildResult result = MachineRecipeIndexCache.instance().getOrBuild(
                    currentLevel, identity, recipeManagerEpoch, MachineRecipeConfigMappingRegistry.instance().epoch(),
                    discoveryEpoch, discoveryService::buildIndexTemplate);
            indices.add(result.index());
            if (!result.cacheHit()) recipeFullScanCount++;
        }
        recipeIndex = MachineRecipeIndex.merge(indices, discoveryEpoch);
    }

    private void markAllPatternsDirty() {
        refreshScheduler.markRangeDirty(0, patternStore.getTotalSlots());
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
        List<Integer> dirtySlots = refreshScheduler.drainDirtySlots(configDirtyPatternRefreshBudgetPerTick());
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
        ItemStack stack = patternStore.getStackInSlot(slot);
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
                compatibility.supported() ? compatibility.pattern() : null,
                compatibility
        );
    }

    private void serverTick() {
        if (!ChexsonsaeutilsCompatibilityConfig.boolValue(
                ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_ENABLED)) {
            return;
        }
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
            feedActivePipelineTasks();
            executionQueue.tick(this, getProcessingLaneCount(), budgetController);
            tryFlushPendingOutput();
        } finally {
        }
    }

    /**
     * Phase 5 分步供料：活动任务当前步的外部输入不足时，从网络两阶段抽取
     * （SIMULATE 全量校验 → MODULATE 落地）——不足即挂起本 tick，绝不半供给。
     * 链内中间品由任务缓冲自持，不经网络。
     */
    private void feedActivePipelineTasks() {
        appeng.api.networking.IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        var storageService = grid.getStorageService();
        if (storageService == null) {
            return;
        }
        var inventory = storageService.getInventory();
        executionQueue.forEachActiveTask(task -> {
            if (!task.isPipelined() || task.currentStepFed()) {
                return;
            }
            Map<AEKey, Long> needs = task.currentStepNeeds();
            if (needs.isEmpty()) {
                return;
            }
            for (Map.Entry<AEKey, Long> need : needs.entrySet()) {
                if (inventory.extract(need.getKey(), need.getValue(), Actionable.SIMULATE, actionSource)
                        < need.getValue()) {
                    return;
                }
            }
            Map<AEKey, Long> fed = new LinkedHashMap<>();
            for (Map.Entry<AEKey, Long> need : needs.entrySet()) {
                long extracted = inventory.extract(need.getKey(), need.getValue(), Actionable.MODULATE, actionSource);
                if (extracted <= 0L) {
                    return;
                }
                fed.put(need.getKey(), extracted);
            }
            task.feedExternalInputs(fed);
        });
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
        discoveryService = DirectProcessingAssembly.createMachineRecipeIndexBuilder();
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
        return FULL_SPEED_OPERATION_TICKS;
    }

    private int getProcessingLaneCount() {
        return FULL_SPEED_CONCURRENCY;
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
                // Phase 6：接上 AeReturn 预算科目——回网冲刷按 payload 量领取，
                // 与时间预算正交，产出洪峰不再挤占 push 侧的 tick 预算。
                long payloadAmount = 0L;
                for (GenericStack stack : batch.payload()) {
                    if (stack != null && stack.amount() > 0L) {
                        payloadAmount += stack.amount();
                    }
                }
                if (!budgetController.tryClaimAeReturn(Math.max(1L, payloadAmount))) {
                    break;
                }
                CraftingService craftingService = getMainNode().getGrid() != null
                        && getMainNode().getGrid().getCraftingService() instanceof CraftingService service
                        ? service
                        : null;
                SourceCpuHandle sourceCpu = craftingService instanceof SourceCpuResolver resolver
                        && batch.sourceCraftingId() != null
                        ? resolver.chexsonsaeutils$findSourceCpu(batch.sourceCraftingId())
                        : null;
                IStorageService storageService = getMainNode().getGrid() == null
                        ? null
                        : getMainNode().getGrid().getStorageService();
                List<GenericStack> rejectedSlice = CraftingOutputRouter.routePayload(
                        storageService,
                        actionSource,
                        batch.payload(),
                        sourceCpu
                ).remainingPayload();
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
        return Math.min(configMaxPendingOutputRetryDelayTicks(), currentDelay * 2);
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
        pushToAeReturnLatencyTicksTotal = ClampedMath.add(pushToAeReturnLatencyTicksTotal, latencyTicks);
        pushToAeReturnLatencyTicksMax = Math.max(pushToAeReturnLatencyTicksMax, latencyTicks);
        pushToAeReturnLatencySampleCount = ClampedMath.add(
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
        for (int slot = 0; slot < patternStore.getTotalSlots(); slot++) {
            ItemStack stack = patternStore.getStackInSlot(slot);
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
        Map<Integer, ItemStack> restored = new HashMap<>();
        ListTag slots = data.getList(NBT_PATTERN_SLOTS, Tag.TAG_COMPOUND);
        for (Tag entry : slots) {
            if (entry instanceof CompoundTag slotTag) {
                ItemStack stack = ItemStack.parseOptional(registries, slotTag.getCompound(NBT_STACK));
                if (!stack.isEmpty()) {
                    restored.put(slotTag.getInt(NBT_SLOT), stack);
                }
            }
        }
        patternStore.loadFromExternalSnapshot(restored);
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
