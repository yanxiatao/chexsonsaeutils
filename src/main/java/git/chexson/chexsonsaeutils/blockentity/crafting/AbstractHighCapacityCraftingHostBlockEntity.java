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
import git.chexson.chexsonsaeutils.crafting.AeCpuIngressRouter;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineAggregatedPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineDelegatingPattern;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachinePlanningProvider;
import git.chexson.chexsonsaeutils.crafting.persistence.HighCapacityPatternHostSavedData;
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
 * 缓冲到下一 tick 经网格存储归还（{@link AeCpuIngressRouter} 会把匹配 CPU waitingFor
 * 的物品路由给发起作业的 CPU）。
 */
public abstract class AbstractHighCapacityCraftingHostBlockEntity extends AENetworkedBlockEntity
        implements PatternContainer, ICraftingProvider, InternalInventoryHost,
        FormalMachinePlanningProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_VIRTUAL_PENDING_OUTPUTS = "virtualPendingOutputs";
    private static final String NBT_ACTIVE_PAGE = "activePage";
    private static final String NBT_LAST_SEARCH_QUERY = "lastSearchQuery";
    private static final String NBT_SEARCH_RESULT_COUNT = "searchResultCount";
    private static final String NBT_HIGHLIGHTED_GLOBAL_SLOT = "highlightedGlobalSlot";
    private static final String NBT_HIGHLIGHTED_PAGE_SLOT_MASK = "highlightedPageSlotMask";
    protected static final int PAGE_SIZE = 27;

    private static int configTotalPatternSlots() {
        return ChexsonsaeutilsCompatibilityConfig.intValue(
                ChexsonsaeutilsCompatibilityConfig.HIGH_CAPACITY_TOTAL_PATTERN_SLOTS);
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
    private final Supplier<ItemStack> representativeItemSupplier;
    private final PatternSearchIndex patternSearchIndex = new PatternSearchIndex();
    private final VirtualPatternInventoryContainer automationContainer = new VirtualPatternInventoryContainer(this);
    private final VirtualPatternItemHandler automationItemHandler = new VirtualPatternItemHandler(automationContainer);
    private final String pageStatusTranslationKey;

    private boolean localOptimizationEnabled = true;
    private final KeyCounter pendingVirtualOutputs = new KeyCounter();
    private boolean virtualReturnStalled;
    private String lastSearchQuery = "";
    private int searchResultCount;
    private int highlightedGlobalSlot = -1;
    private int highlightedPageSlotMask;
    private boolean externalPatternsLoaded;
    private boolean providerRefreshAfterReadyPending;

    protected AbstractHighCapacityCraftingHostBlockEntity(
            net.minecraft.world.level.block.entity.BlockEntityType<?> blockEntityType,
            BlockPos pos,
            BlockState blockState,
            Supplier<ItemStack> representativeItemSupplier,
            String pageStatusTranslationKey
    ) {
        super(blockEntityType, pos, blockState);
        this.representativeItemSupplier = representativeItemSupplier;
        this.pageStatusTranslationKey = pageStatusTranslationKey;
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
        localPatternProviderFacade.refreshDirtyPatterns();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        data.putInt(NBT_ACTIVE_PAGE, pagedPatternInventory.getActivePage());
        data.putString(NBT_LAST_SEARCH_QUERY, lastSearchQuery);
        data.putInt(NBT_SEARCH_RESULT_COUNT, searchResultCount);
        data.putInt(NBT_HIGHLIGHTED_GLOBAL_SLOT, highlightedGlobalSlot);
        data.putInt(NBT_HIGHLIGHTED_PAGE_SLOT_MASK, highlightedPageSlotMask);
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
        pagedPatternInventory.setActivePage(data.contains(NBT_ACTIVE_PAGE) ? data.getInt(NBT_ACTIVE_PAGE) : 0);
        lastSearchQuery = data.contains(NBT_LAST_SEARCH_QUERY) ? data.getString(NBT_LAST_SEARCH_QUERY) : "";
        searchResultCount = data.contains(NBT_SEARCH_RESULT_COUNT) ? Math.max(0, data.getInt(NBT_SEARCH_RESULT_COUNT)) : 0;
        highlightedGlobalSlot = data.contains(NBT_HIGHLIGHTED_GLOBAL_SLOT)
                ? data.getInt(NBT_HIGHLIGHTED_GLOBAL_SLOT)
                : -1;
        highlightedPageSlotMask = data.contains(NBT_HIGHLIGHTED_PAGE_SLOT_MASK)
                ? data.getInt(NBT_HIGHLIGHTED_PAGE_SLOT_MASK)
                : computeHighlightedPageSlotMask(lastSearchQuery);
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
        if (!this.getMainNode().isActive()) {
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
        return acceptVirtualOutputs(supportedPattern, inputHolder);
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
        return acceptVirtualOutputs(aggregatedPattern, inputHolder);
    }

    /**
     * 虚拟合成的核心：按样板声明把产出与容器残留并入缓冲。缓冲为空说明样板无任何正产出，
     * 拒绝推送让 CPU 保留输入另寻提供者。
     */
    private boolean acceptVirtualOutputs(
            IMolecularAssemblerSupportedPattern pattern,
            @Nullable KeyCounter[] inputHolder
    ) {
        KeyCounter buffered = new KeyCounter();
        for (GenericStack output : pattern.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                buffered.add(output.what(), output.amount());
            }
        }
        appendVirtualRemainders(pattern, inputHolder, buffered);
        if (buffered.isEmpty()) {
            return false;
        }
        for (var entry : buffered) {
            pendingVirtualOutputs.add(entry.getKey(), entry.getLongValue());
        }
        saveChanges();
        return true;
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
                            "High capacity crafting machine virtual remainder: amount {} not divisible by template "
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
        return virtualReturnStalled;
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
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int slot = 0; slot < pagedPatternInventory.getTotalSlots(); slot++) {
            ItemStack stack = pagedPatternInventory.getVirtualSlot(slot);
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
        pagedPatternInventory.clear();
        refreshScheduler.clear();
        decodedPatternEntryCache.clear();
        localPatternProviderFacade.clear();
        pendingVirtualOutputs.reset();
        virtualReturnStalled = false;
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

    void recordSearchMetrics(PatternSearchIndex.MatchResult matchResult) {
        if (matchResult == null) {
            return;
        }
    }

    boolean isLocalOptimizationEnabled() {
        return localOptimizationEnabled;
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
        List<GenericStack> remaining = AeCpuIngressRouter
                .routePayload(storageService, actionSource, payload, null)
                .remainingPayload();
        pendingVirtualOutputs.reset();
        boolean stalled = false;
        for (GenericStack stack : remaining) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                pendingVirtualOutputs.add(stack.what(), stack.amount());
                stalled = true;
            }
        }
        virtualReturnStalled = stalled;
        saveChanges();
    }

    private static IPatternDetails unwrapFormalDelegatingPattern(IPatternDetails patternDetails) {
        if (patternDetails instanceof FormalMachineDelegatingPattern delegatingPattern) {
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
        pagedPatternInventory.loadFromExternalSnapshot(HighCapacityPatternHostSavedData.get(serverLevel).snapshotSlots(worldPosition));
        externalPatternsLoaded = true;
        localPatternProviderFacade.markAllDirty();
        providerRefreshAfterReadyPending = true;
        forceProviderRefreshIfReady();
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
}
