package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HolderBatchProvider;
import git.chexson.chexsonsaeutils.crafting.persistence.ExtremeAssemblerPatternHostSavedData;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchHost;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchOutputBuffer;
import git.chexson.chexsonsaeutils.menu.matrix.DecodedPatternEntryCache;
import git.chexson.chexsonsaeutils.menu.matrix.DirtySlotPatternRefreshScheduler;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixDisplay;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSearch;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSource;
import git.chexson.chexsonsaeutils.menu.matrix.PatternSlotStore;
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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 虚拟合成主机：样板提交时不做真实合成，直接按样板声明把输入转换为产出与容器残留，
 * 缓冲到下一 tick 经网格存储归还（{@link CraftingOutputRouter} 会把匹配 CPU waitingFor
 * 的物品路由给发起作业的 CPU）。
 */
public abstract class AbstractExtremeAssemblerHostBlockEntity extends AENetworkedBlockEntity
        implements PatternContainer, ICraftingProvider, InternalInventoryHost,
        HolderBatchProvider, DeclaredBatchHost, git.chexson.chexsonsaeutils.crafting.hostbatch.ImmediateBatchOutputHost,
        git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoExactBatchHost, PatternMatrixSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_VIRTUAL_PENDING_OUTPUTS = "virtualPendingOutputs";
    private static final String NBT_VIRTUAL_AMOUNT_WIDE = "#virtualAmountWide";

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
    private final VirtualPatternInventoryContainer automationContainer = new VirtualPatternInventoryContainer(this);
    private final VirtualPatternItemHandler automationItemHandler = new VirtualPatternItemHandler(automationContainer);
    private final String patternSlotsSummaryTranslationKey;

    private boolean localOptimizationEnabled = true;
    private final WideKeyCounter pendingVirtualOutputs = new WideKeyCounter();
    private boolean virtualReturnStalled;
    private boolean externalPatternsLoaded;
    private boolean providerRefreshAfterReadyPending;

    protected AbstractExtremeAssemblerHostBlockEntity(
            net.minecraft.world.level.block.entity.BlockEntityType<?> blockEntityType,
            BlockPos pos,
            BlockState blockState,
            String patternSlotsSummaryTranslationKey
    ) {
        super(blockEntityType, pos, blockState);
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
            data.put(NBT_VIRTUAL_PENDING_OUTPUTS, writeVirtualOutputs(pendingVirtualOutputs, registries));
        } else {
            data.remove(NBT_VIRTUAL_PENDING_OUTPUTS);
        }
        saveCustomState(data, registries);
    }

    /** 保留 GenericStack 条目结构，只对超 long 的数量附加精确值。 */
    static ListTag writeVirtualOutputs(WideKeyCounter buffer, HolderLookup.Provider registries) {
        ListTag pendingOutputsTag = new ListTag();
        for (var key : buffer.keys()) {
            BigInteger exact = buffer.getExact(key);
            if (exact.signum() <= 0) {
                continue;
            }
            CompoundTag entry = GenericStack.writeTag(registries,
                    new GenericStack(key, exact.min(WideCeilings.LONG_ACCOUNT).longValueExact()));
            if (exact.compareTo(WideCeilings.LONG_ACCOUNT) > 0) {
                entry.putByteArray(NBT_VIRTUAL_AMOUNT_WIDE, exact.toByteArray());
            }
            pendingOutputsTag.add(entry);
        }
        return pendingOutputsTag;
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        readVirtualOutputs(pendingVirtualOutputs, data.getList(NBT_VIRTUAL_PENDING_OUTPUTS, Tag.TAG_COMPOUND), registries);
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
        if (!localPatternProviderFacade.contains(patternDetails)) {
            return false;
        }
        return acceptVirtualOutputs(supportedPattern, inputHolder);
    }

    /** 与保存同源的读回入口；单批大数在重载后保持精确。 */
    static void readVirtualOutputs(WideKeyCounter buffer, ListTag data, HolderLookup.Provider registries) {
        buffer.clear();
        for (Tag tag : data) {
            if (!(tag instanceof CompoundTag stackTag)) {
                continue;
            }
            GenericStack stack = GenericStack.readTag(registries, stackTag);
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                buffer.add(stack.what(), stackTag.contains(NBT_VIRTUAL_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)
                        ? new BigInteger(stackTag.getByteArray(NBT_VIRTUAL_AMOUNT_WIDE))
                        : BigInteger.valueOf(stack.amount()));
            }
        }
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
        return bufferVirtualOutputs(pattern, inputHolder, executions, false);
    }

    /**
     * 把 {@code executions} 发的产出与回流容器并入待回流缓冲。
     *
     * @param holderIsUnitPrototype {@code true} 表示 {@code inputHolder} 只是<b>一发</b>的原型材料，
     *                              回流容器按「原型量 ÷ 模板量 × 份数」计（份数声明形态）；
     *                              {@code false} 是老的 k 倍 holder，容器量直接从 holder 实量反推
     */
    private boolean bufferVirtualOutputs(
            IMolecularAssemblerSupportedPattern pattern,
            @Nullable KeyCounter[] inputHolder,
            long executions,
            boolean holderIsUnitPrototype
    ) {
        if (executions <= 0L) {
            return false;
        }
        WideKeyCounter buffered = virtualOutputs(pattern, inputHolder,
                BigInteger.valueOf(executions), holderIsUnitPrototype);
        if (buffered.isEmpty()) {
            return false;
        }
        for (var key : buffered.keys()) {
            pendingVirtualOutputs.add(key, buffered.getExact(key));
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
        return localPatternProviderFacade.contains(pattern)
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
        return chexsonsaeutils$pushNeoEcoExactBatch(pattern, inputs, outputs, remainders,
                craftingJobId, BigInteger.valueOf(count));
    }

    @Override
    public boolean chexsonsaeutils$pushNeoEcoExactBatch(IPatternDetails pattern,
            List<GenericStack> inputs, List<GenericStack> outputs, List<GenericStack> remainders,
            java.util.UUID craftingJobId, BigInteger count) {
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
     * 聚合样板（倍率 1、模板量=整批量）天然得 1。无输入样板为 1 份；有输入但不足一发时拒收。
     */
    static long deriveHolderExecutions(
            IPatternDetails pattern,
            @Nullable KeyCounter[] inputHolder
    ) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs.length == 0) {
            return 1L;
        }
        if (inputHolder == null || inputHolder.length < inputs.length) {
            return 0L;
        }
        BigInteger executions = BigInteger.valueOf(Long.MAX_VALUE);
        for (int slot = 0; slot < inputs.length; slot++) {
            KeyCounter holder = inputHolder[slot];
            if (holder == null || holder.isEmpty() || inputs[slot].getMultiplier() <= 0L) {
                return 0L;
            }
            BigInteger templates = BigInteger.ZERO;
            for (var entry : holder) {
                if (entry.getKey() != null && entry.getLongValue() > 0L) {
                    templates = templates.add(BigInteger.valueOf(entry.getLongValue()
                            / templateAmountFor(inputs[slot], entry.getKey())));
                }
            }
            executions = executions.min(templates.divide(BigInteger.valueOf(inputs[slot].getMultiplier())));
        }
        return executions.longValueExact();
    }

    /**
     * 容器残留与 AE2 同款公式（见 CraftingCpuHelper.extractPatternInputs）：每槽内某键的数量
     * 除以该槽模板量即该模板的执行次数，执行次数即残留个数。残留键与产出键相同时按键合并。
     */
    private static void appendVirtualRemainders(
            IPatternDetails pattern,
            @Nullable KeyCounter[] inputHolder,
            WideKeyCounter buffer
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

    /**
     * 份数声明形态的回流容器：{@code inputHolder} 是<b>一发</b>的原型材料，容器件数等于
     * 「真实槽倍率」（一发用掉几份模板）× 声明份数。
     *
     * <p>与 CPU 侧 {@code ParallelBatchExtraction.addDeclaredReserves} 逐槽同值：那里的预期回流
     * 是 {@code multiplier × 份数}，这里 {@code 原型的真实槽倍率 = multiplier}（原型按每发量提取）。
     */
    private static void appendDeclaredVirtualRemainders(
            IPatternDetails pattern,
            @Nullable KeyCounter[] unitPrototype,
            WideKeyCounter buffer,
            BigInteger executions
    ) {
        if (unitPrototype == null) {
            return;
        }
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        int slots = Math.min(inputs.length, unitPrototype.length);
        for (int slot = 0; slot < slots; slot++) {
            KeyCounter holder = unitPrototype[slot];
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
                long perFiring = input.getMultiplier();
                buffer.add(remainderKey, BigInteger.valueOf(perFiring).multiply(executions));
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
                AEItemKey.of(getItemFromBlockEntity()),
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
    public git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms patternMatrixSearchTerms(int slot) {
        var entry = decodedPatternEntryCache.get(slot);
        return git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms.from(
                entry == null ? null : entry.patternDetails());
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
        pendingVirtualOutputs.clear();
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

    /** 份数按 long 反推、产出走虚拟缓冲一次并入，没有直连机那种 int 执行计数上限。 */
    @Override
    public long maxPushExecutions(IPatternDetails pattern) {
        return Long.MAX_VALUE;
    }

    /**
     * 收样判据（与 {@link #pushPattern(IPatternDetails, KeyCounter[])} 同一条准入）：
     * 认得的样板返回它自己，认不得的返回 {@code null}。
     */
    private @Nullable IMolecularAssemblerSupportedPattern acceptedSupportedPattern(IPatternDetails pattern) {
        localPatternProviderFacade.refreshDirtyPatterns();
        if (!getMainNode().isActive()
                || !(pattern instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
            return null;
        }
        return localPatternProviderFacade.contains(pattern)
                ? supportedPattern
                : null;
    }

    @Override
    public boolean supportsDeclaredBatch(IPatternDetails pattern) {
        return acceptedSupportedPattern(pattern) != null;
    }

    /** 虚拟产出及回流容器均使用宽域缓冲，声明发数没有 long 数值上界。 */
    @Override
    public boolean declaredBatchFiringsUnbounded() {
        return true;
    }

    /** 未采用无界声明的调用方仍可读取准入状态；发数限制由宽域能力明确给出。 */
    @Override
    public BigInteger declaredBatchCapacity(IPatternDetails pattern) {
        return acceptedSupportedPattern(pattern) == null
                ? BigInteger.ZERO
                : WideCeilings.LONG_ACCOUNT;
    }

    /** 按实收 BigInteger 发数构造整批精确产出；拒收时不修改缓冲。 */
    @Override
    public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
            BigInteger declaredExecutions) {
        IMolecularAssemblerSupportedPattern supported = acceptedSupportedPattern(pattern);
        if (supported == null || declaredExecutions == null || declaredExecutions.signum() <= 0) {
            return BigInteger.ZERO;
        }
        WideKeyCounter buffered = virtualOutputs(supported, unitPrototype, declaredExecutions, true);
        if (buffered.isEmpty()) {
            return BigInteger.ZERO;
        }
        for (var key : buffered.keys()) {
            pendingVirtualOutputs.add(key, buffered.getExact(key));
        }
        saveChanges();
        return declaredExecutions;
    }

    /** 装配器实际使用的产出构造口；单批数量与同键回流都按 BigInteger 精确累加。 */
    static WideKeyCounter virtualOutputs(IPatternDetails pattern, @Nullable KeyCounter[] holder,
            BigInteger executions, boolean unitPrototype) {
        var buffered = new WideKeyCounter();
        if (executions.signum() <= 0) {
            return buffered;
        }
        for (var output : pattern.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                buffered.add(output.what(), BigInteger.valueOf(output.amount()).multiply(executions));
            }
        }
        if (unitPrototype) {
            appendDeclaredVirtualRemainders(pattern, holder, buffered, executions);
        } else {
            appendVirtualRemainders(pattern, holder, buffered);
        }
        return buffered;
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

    /** CPU 已完成本批登记后才调用；只交付真实缓冲，不在 push 内回调或遍历网络存储。 */
    @Override
    public void deliverBufferedBatchOutputs(DeclaredBatchOutputReceiver receiver) {
        IGrid currentGrid = getGrid();
        if (!getMainNode().isActive() || currentGrid == null || pendingVirtualOutputs.isEmpty()) return;
        CraftingOutputRouter.claimBufferedOutputs(pendingVirtualOutputs, currentGrid, receiver);
        saveChanges();
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
    /** 逐键扣除实际交付量；本轮未发出的宽域余额及拒收分段都留在缓冲。 */
    static void accountVirtualDelivery(WideKeyCounter buffer,
            List<GenericStack> sent, List<GenericStack> remaining) {
        for (var stack : sent) {
            buffer.subtract(stack.what(), stack.amount());
        }
        for (var stack : remaining) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                buffer.add(stack.what(), stack.amount());
            }
        }
    }

    static List<GenericStack> virtualOutputPayload(WideKeyCounter buffer) {
        List<GenericStack> payload = new ArrayList<>(buffer.size());
        for (var key : buffer.keys()) {
            BigInteger amount = buffer.getExact(key);
            if (amount.signum() > 0) {
                payload.add(new GenericStack(key, amount.min(WideCeilings.LONG_ACCOUNT).longValueExact()));
            }
        }
        return payload;
    }

    static BigInteger pendingVirtualOutputAmount(WideKeyCounter buffer) {
        BigInteger total = BigInteger.ZERO;
        for (var key : buffer.keys()) total = total.add(buffer.getExact(key));
        return total;
    }

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
        IGrid currentGrid = getGrid();
        if (currentGrid.getCraftingService() instanceof DeclaredBatchOutputReceiver receiver) {
            CraftingOutputRouter.claimBufferedOutputs(pendingVirtualOutputs, currentGrid, receiver);
            saveChanges();
        }
        CraftingOutputRouter.claimBufferedCpuOutputs(pendingVirtualOutputs, currentGrid);
        saveChanges();
        IStorageService storageService = currentGrid.getStorageService();
        if (storageService == null) {
            return;
        }
        List<GenericStack> payload = virtualOutputPayload(pendingVirtualOutputs);
        var routing = CraftingOutputRouter.routePayload(storageService, actionSource, payload, null);
        List<GenericStack> remaining = routing.remainingPayload();
        accountVirtualDelivery(pendingVirtualOutputs, payload, remaining);
        CraftingOutputRouter.storeUnrequestedBufferedOutputs(pendingVirtualOutputs,
                storageService.getInventory(), actionSource, currentGrid.getCraftingService()::isRequesting);
        boolean stalled = !pendingVirtualOutputs.isEmpty();
        virtualReturnStalled = stalled;
        if (stalled) {
            // 产出回不去的唯一出口日志：网络 insert 未全收（存储满或
            // waitingFor 不匹配 → 剩余滞留缓冲、机器 isBusy 拒收新批）。
            BigInteger stalledAmount = pendingVirtualOutputAmount(pendingVirtualOutputs);
            StringBuilder stalledKeys = new StringBuilder();
            for (var key : pendingVirtualOutputs.keys()) {
                if (stalledKeys.length() > 0) stalledKeys.append(", ");
                stalledKeys.append(key).append(" x ").append(pendingVirtualOutputs.getExact(key));
                if (stalledKeys.length() > 400) {
                    stalledKeys.append("...(more)");
                    break;
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
