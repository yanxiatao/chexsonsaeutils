package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DelegatingPatternDetails;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchProvider;
import git.chexson.chexsonsaeutils.crafting.persistence.ExtremeAssemblerPatternHostSavedData;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchHost;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchOutputBuffer;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixDisplay;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSearch;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 虚拟合成主机：样板提交时不做真实合成，直接按样板声明把输入转换为产出与容器残留，
 * 缓冲到下一 tick 经网格存储归还（{@link CraftingOutputRouter} 会把匹配 CPU waitingFor
 * 的物品路由给发起作业的 CPU）。
 */
public abstract class AbstractExtremeAssemblerHostBlockEntity extends AENetworkedBlockEntity
        implements PatternContainer, ICraftingProvider, InternalInventoryHost,
        HostBatchProvider, NeoEcoBatchHost, PatternMatrixSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_VIRTUAL_PENDING_OUTPUTS = "virtualPendingOutputs";

    private static int configTotalPatternSlots() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.EXTREME_ASSEMBLER_TOTAL_PATTERN_SLOTS);
    }

    protected final MachineSource actionSource = new MachineSource(this);
    protected final DirtySlotPatternRefreshScheduler refreshScheduler =
            new DirtySlotPatternRefreshScheduler(configTotalPatternSlots());
    protected final DecodedPatternEntryCache decodedPatternEntryCache =
            new DecodedPatternEntryCache(configTotalPatternSlots());
    protected final PatternSlotStore patternStore =
            new PatternSlotStore(this, refreshScheduler, configTotalPatternSlots(), this::persistPatternSlot);
    protected final LocalPatternProviderFacade localPatternProviderFacade =
            new LocalPatternProviderFacade(this, patternStore, refreshScheduler, decodedPatternEntryCache);
    private final Supplier<ItemStack> representativeItemSupplier;
    private final VirtualPatternInventoryContainer automationContainer = new VirtualPatternInventoryContainer(this);
    private final VirtualPatternItemHandler automationItemHandler = new VirtualPatternItemHandler(automationContainer);
    private final String patternSlotsSummaryTranslationKey;

    private boolean localOptimizationEnabled = true;
    private final KeyCounter pendingVirtualOutputs = new KeyCounter();
    private boolean virtualReturnStalled;
    private boolean externalPatternsLoaded;
    private boolean providerRefreshAfterReadyPending;

    protected AbstractExtremeAssemblerHostBlockEntity(
            net.minecraft.world.level.block.entity.BlockEntityType<?> blockEntityType,
            BlockPos pos,
            BlockState blockState,
            Supplier<ItemStack> representativeItemSupplier,
            String patternSlotsSummaryTranslationKey
    ) {
        super(blockEntityType, pos, blockState);
        this.representativeItemSupplier = representativeItemSupplier;
        this.patternSlotsSummaryTranslationKey = patternSlotsSummaryTranslationKey;
        this.getMainNode()
                .setIdlePowerUsage(0.0)
                .addService(ICraftingProvider.class, this);
    }

    public static void serverTick(
            Level level,
            BlockPos pos,
            BlockState state,
            AbstractExtremeAssemblerHostBlockEntity blockEntity
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
        localPatternProviderFacade.refreshDirtyPatterns();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        if (!pendingVirtualOutputs.isEmpty()) {
            ListTag pendingOutputsTag = new ListTag();
            for (var entry : pendingVirtualOutputs) {
                if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                    continue;
                }
                pendingOutputsTag.add(GenericStack.writeTag(
                        registries,
                        new GenericStack(entry.getKey(), entry.getLongValue())
                ));
            }
            data.put(NBT_VIRTUAL_PENDING_OUTPUTS, pendingOutputsTag);
        } else {
            data.remove(NBT_VIRTUAL_PENDING_OUTPUTS);
        }
        saveCustomState(data, registries);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        pendingVirtualOutputs.reset();
        if (data.contains(NBT_VIRTUAL_PENDING_OUTPUTS, Tag.TAG_LIST)) {
            for (Tag tag : data.getList(NBT_VIRTUAL_PENDING_OUTPUTS, Tag.TAG_COMPOUND)) {
                if (!(tag instanceof CompoundTag stackTag)) {
                    continue;
                }
                GenericStack stack = GenericStack.readTag(registries, stackTag);
                if (stack != null && stack.what() != null && stack.amount() > 0L) {
                    pendingVirtualOutputs.add(stack.what(), stack.amount());
                }
            }
        }
        virtualReturnStalled = false;
        externalPatternsLoaded = false;
        refreshScheduler.clear();
        decodedPatternEntryCache.clear();
        localPatternProviderFacade.clear();
        localPatternProviderFacade.markAllDirty();
        loadCustomState(data, registries);
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
        if (inventory == patternStore.inventory()) {
            patternStore.onSlotWritten(slot);
        }
        saveChanges();
    }

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
        if (supportedPattern instanceof HostBatchPattern aggregatedPattern) {
            return pushAggregatedPattern(aggregatedPattern, inputHolder);
        }
        if (!localPatternProviderFacade.contains(unwrapFormalDelegatingPattern(patternDetails))) {
            return false;
        }
        return acceptVirtualOutputs(supportedPattern, inputHolder);
    }

    private boolean pushAggregatedPattern(
            HostBatchPattern aggregatedPattern,
            KeyCounter[] inputHolder
    ) {
        if (aggregatedPattern == null
                || inputHolder == null
                || !aggregatedPattern.hostLocator().matches(this)) {
            return false;
        }
        return acceptVirtualOutputs(aggregatedPattern, inputHolder);
    }

    /**
     * 虚拟合成的核心：按样板声明把产出与容器残留并入缓冲。执行份数从
     * holder 实际量反推（多份推送一次进账 k 份产出），缓冲为空说明样板无
     * 任何正产出，拒绝推送让 CPU 保留输入另寻提供者。
     */
    private boolean acceptVirtualOutputs(
            IMolecularAssemblerSupportedPattern pattern,
            @Nullable KeyCounter[] inputHolder
    ) {
        long executions = deriveHolderExecutions(pattern, inputHolder);
        if (executions <= 0L) {
            return false;
        }
        KeyCounter buffered = new KeyCounter();
        for (GenericStack output : pattern.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                buffered.add(output.what(), saturatingMultiply(output.amount(), executions));
            }
        }
        appendVirtualRemainders(pattern, inputHolder, buffered);
        if (buffered.isEmpty()) {
            return false;
        }
        for (var entry : buffered) {
            pendingVirtualOutputs.add(entry.getKey(), entry.getLongValue());
        }
        // 不在 pushPattern 内即时 flush：CPU 侧 reserveExpectedWaiting 在
        // pushPattern 返回之后才登记 waitingFor，push 内同步 insert 会让
        // CraftingServiceStorage 的劫持查不到本次预期（时序上早了一步），
        // 产出落进普通网络回不到发起 CPU——表现为任务永缺料、料凭空
        // 「丢失」。产出统一走 serverTick 的 flush：block entity tick 排在
        // grid tick（CPU 执行 + reserve）之后，依赖链产出下一 CPU tick
        // 即可提取，同 tick 级联收益保持。
        saveChanges();
        return true;
    }

    @Override
    public long chexsonsaeutils$getNeoEcoBatchCapacity(IPatternDetails pattern) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (!getMainNode().isActive()
                || !(pattern instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
            return 0L;
        }
        if (supportedPattern instanceof HostBatchPattern aggregatedPattern) {
            return aggregatedPattern.hostLocator().matches(this) ? Long.MAX_VALUE : 0L;
        }
        return localPatternProviderFacade.contains(unwrapFormalDelegatingPattern(pattern))
                ? Long.MAX_VALUE
                : 0L;
    }

    @Override
    public boolean chexsonsaeutils$pushNeoEcoBatch(
            IPatternDetails pattern,
            List<GenericStack> inputs,
            List<GenericStack> outputs,
            List<GenericStack> remainders,
            java.util.UUID craftingJobId,
            long count
    ) {
        if (chexsonsaeutils$getNeoEcoBatchCapacity(pattern) <= 0L
                || !NeoEcoBatchOutputBuffer.append(pendingVirtualOutputs, outputs, remainders, count)) {
            return false;
        }
        saveChanges();
        return true;
    }

    /**
     * 从 holder 实际量反推执行份数（neoecoae 式）：每槽
     * Σ(数量÷模板量)÷倍率，全槽取最小。单份推送得 1；k 份多推得 k；
     * 聚合样板（倍率 1、模板量=整批量）天然得 1。holder 缺位按 1 份
     * （兼容无输入样板与旧路径）。
     */
    private static long deriveHolderExecutions(
            IPatternDetails pattern,
            @Nullable KeyCounter[] inputHolder
    ) {
        if (inputHolder == null) {
            return 1L;
        }
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        int slots = Math.min(inputs.length, inputHolder.length);
        long executions = Long.MAX_VALUE;
        for (int slot = 0; slot < slots; slot++) {
            KeyCounter holder = inputHolder[slot];
            if (holder == null || holder.isEmpty()) {
                continue;
            }
            long multiplier = Math.max(1L, inputs[slot].getMultiplier());
            long templates = 0L;
            for (var entry : holder) {
                AEKey templateKey = entry.getKey();
                long amount = entry.getLongValue();
                if (templateKey == null || amount <= 0L) {
                    continue;
                }
                templates += amount / templateAmountFor(inputs[slot], templateKey);
            }
            executions = Math.min(executions, templates / multiplier);
        }
        return executions == Long.MAX_VALUE ? 1L : Math.max(1L, executions);
    }

    private static long saturatingMultiply(long amount, long executions) {
        if (amount <= 0L || executions <= 0L) {
            return 0L;
        }
        if (amount > Long.MAX_VALUE / executions) {
            return Long.MAX_VALUE;
        }
        return amount * executions;
    }

    /**
     * 容器残留与 AE2 同款公式（见 CraftingCpuHelper.extractPatternInputs）：每槽内某键的数量
     * 除以该槽模板量即该模板的执行次数，执行次数即残留个数。残留键与产出键相同时按键合并。
     */
    private static void appendVirtualRemainders(
            IPatternDetails pattern,
            @Nullable KeyCounter[] inputHolder,
            KeyCounter buffer
    ) {
        if (inputHolder == null) {
            return;
        }
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        int slots = Math.min(inputs.length, inputHolder.length);
        for (int slot = 0; slot < slots; slot++) {
            KeyCounter holder = inputHolder[slot];
            if (holder == null || holder.isEmpty()) {
                continue;
            }
            IPatternDetails.IInput input = inputs[slot];
            for (var entry : holder) {
                AEKey templateKey = entry.getKey();
                long amount = entry.getLongValue();
                if (templateKey == null || amount <= 0L) {
                    continue;
                }
                AEKey remainderKey = input.getRemainingKey(templateKey);
                if (remainderKey == null) {
                    continue;
                }
                long templateAmount = templateAmountFor(input, templateKey);
                if (amount % templateAmount != 0L) {
                    LOGGER.warn(
                            "Extreme assembler virtual remainder: amount {} not divisible by template "
                                    + "amount {} for key {} in pattern {}",
                            amount,
                            templateAmount,
                            templateKey,
                            pattern.getDefinition()
                    );
                }
                buffer.add(remainderKey, amount / templateAmount);
            }
        }
    }

    private static long templateAmountFor(IPatternDetails.IInput input, AEKey key) {
        for (GenericStack possible : input.getPossibleInputs()) {
            if (possible != null && possible.what() != null && key.matches(possible)) {
                return Math.max(1L, possible.amount());
            }
        }
        return 1L;
    }

    @Override
    public boolean isBusy() {
        // 虚拟执行永不拒收：flush 滞留（网络满/暂无接收者）只代表产出还
        // 在缓冲里等回流，不代表机器没有产能——此前 true 会让 provider
        // 退避把整台合成机的所有样板打入指数退避（最长 2^20 tick），
        // 多推与普通推送全灭，整机表现为「不并行」。
        return false;
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
        return patternStore.inventory();
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(
                AEItemKey.of(representativeItemSupplier.get()),
                getName(),
                List.of(Component.translatable(
                        patternSlotsSummaryTranslationKey,
                        patternStore.countNonEmptySlots(),
                        patternStore.getTotalSlots()
                ))
        );
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
        return 0;
    }

    @Override
    public int patternMatrixReasonCode(int slot) {
        return 0;
    }

    @Override
    public long patternMatrixDetailVersion() {
        return 0L;
    }

    @Override
    public boolean patternMatrixMayPlace(ItemStack stack) {
        return isSupportedEncodedPattern(stack);
    }

    @Override
    public int patternMatrixSearchMatches(String query, long[] matchBits) {
        return PatternMatrixSearch.findMatches(
                patternStore, decodedPatternEntryCache, getLevel(), query, matchBits);
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int slot = 0; slot < patternStore.getTotalSlots(); slot++) {
            ItemStack stack = patternStore.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
        addCustomDrops(level, pos, drops);
    }

    protected void addCustomDrops(Level level, BlockPos pos, List<ItemStack> drops) {
    }

    @Override
    public void clearContent() {
        super.clearContent();
        patternStore.clearAll();
        refreshScheduler.clear();
        decodedPatternEntryCache.clear();
        localPatternProviderFacade.clear();
        pendingVirtualOutputs.reset();
        virtualReturnStalled = false;
        persistAllPatterns();
        clearCustomContent();
    }

    protected void clearCustomContent() {
    }

    public int getTotalPatternSlots() {
        return patternStore.getTotalSlots();
    }

    public int getUsedPatternSlots() {
        return patternStore.countNonEmptySlots();
    }

    public int getDecodedPatternCount() {
        return localPatternProviderFacade.activePatternCount();
    }

    @Override
    public int getCurrentOperationTicks() {
        return 1;
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

    boolean isLocalOptimizationEnabled() {
        return localOptimizationEnabled;
    }

    public ItemStack getPatternAt(int slot) {
        return patternStore.getStackInSlot(slot).copy();
    }

    public void setPatternAt(int slot, ItemStack stack) {
        patternStore.setStackInSlot(slot, stack);
    }

    public void clearPatternsForAutomation() {
        patternStore.clearAll();
        localPatternProviderFacade.clear();
        decodedPatternEntryCache.clear();
        persistAllPatterns();
        requestProviderUpdateIfReady();
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

    public IItemHandler getAutomationItemHandler() {
        return automationItemHandler;
    }

    protected void serverTick() {
        if (!ChexsonsaeutilsCompatibilityConfig.boolValue(
                ChexsonsaeutilsCompatibilityConfig.EXTREME_ASSEMBLER_ENABLED)) {
            return;
        }
        loadExternalPatternsIfNeeded();
        localPatternProviderFacade.refreshDirtyPatterns();
        requestProviderUpdateIfLocalPatternsChanged();
        forceProviderRefreshIfReady();
        flushPendingVirtualOutputs();
    }

    /**
     * 把缓冲的虚拟产出经网格存储归还。CraftingServiceStorage 会优先把匹配某 CPU
     * waitingFor 的物品路由给该 CPU（AE2 官方完成路径）；剩余部分回写缓冲下 tick 重试。
     */
    private void flushPendingVirtualOutputs() {
        if (pendingVirtualOutputs.isEmpty()) {
            if (virtualReturnStalled) {
                virtualReturnStalled = false;
            }
            return;
        }
        if (!getMainNode().isActive() || getGrid() == null) {
            return;
        }
        IStorageService storageService = getGrid().getStorageService();
        if (storageService == null) {
            return;
        }
        List<GenericStack> payload = new ArrayList<>(pendingVirtualOutputs.size());
        for (var entry : pendingVirtualOutputs) {
            if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                continue;
            }
            payload.add(new GenericStack(entry.getKey(), entry.getLongValue()));
        }
        var routing = CraftingOutputRouter
                .routePayload(storageService, actionSource, payload, null);
        List<GenericStack> remaining = routing.remainingPayload();
        pendingVirtualOutputs.reset();
        boolean stalled = false;
        for (GenericStack stack : remaining) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                pendingVirtualOutputs.add(stack.what(), stack.amount());
                stalled = true;
            }
        }
        virtualReturnStalled = stalled;
        if (stalled) {
            // 产出回不去的唯一出口日志：网络 insert 未全收（存储满或
            // waitingFor 不匹配 → 剩余滞留缓冲、机器 isBusy 拒收新批）。
            long stalledAmount = 0L;
            StringBuilder stalledKeys = new StringBuilder();
            for (GenericStack stack : remaining) {
                if (stack != null && stack.what() != null && stack.amount() > 0L) {
                    stalledAmount = stalledAmount >= Long.MAX_VALUE - stack.amount()
                            ? Long.MAX_VALUE
                            : stalledAmount + stack.amount();
                    if (stalledKeys.length() > 0) {
                        stalledKeys.append(", ");
                    }
                    stalledKeys.append(stack.what()).append(" x ").append(stack.amount());
                    if (stalledKeys.length() > 400) {
                        stalledKeys.append("...(more)");
                        break;
                    }
                }
            }
            LOGGER.warn(
                    "[chexsonsaeutils-virtual] output flush stalled @ {}: pendingAmount={} first=[{}]",
                    worldPosition,
                    stalledAmount,
                    stalledKeys
            );
        }
        saveChanges();
    }

    private static IPatternDetails unwrapFormalDelegatingPattern(IPatternDetails patternDetails) {
        if (patternDetails instanceof DelegatingPatternDetails delegatingPattern) {
            return delegatingPattern.basePattern();
        }
        return patternDetails;
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
        patternStore.loadFromExternalSnapshot(
                ExtremeAssemblerPatternHostSavedData.get(serverLevel).snapshotSlots(worldPosition));
        externalPatternsLoaded = true;
        localPatternProviderFacade.markAllDirty();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    private void persistPatternSlot(int slot) {
        if (!(level instanceof ServerLevel serverLevel) || slot < 0 || slot >= patternStore.getTotalSlots()) {
            return;
        }
        ExtremeAssemblerPatternHostSavedData.get(serverLevel)
                .setSlot(worldPosition, slot, patternStore.getStackInSlot(slot));
    }

    private void persistAllPatterns() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        ExtremeAssemblerPatternHostSavedData savedData = ExtremeAssemblerPatternHostSavedData.get(serverLevel);
        savedData.removeHost(worldPosition);
        for (int slot = 0; slot < patternStore.getTotalSlots(); slot++) {
            ItemStack stack = patternStore.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                savedData.setSlot(worldPosition, slot, stack);
            }
        }
    }

    private void persistCleanup() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        ExtremeAssemblerPatternHostSavedData.get(serverLevel).removeHost(worldPosition);
    }
}
