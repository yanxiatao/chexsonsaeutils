package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.FuzzyMode;
import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingWatcherNode;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.networking.IStackWatcher;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.core.definitions.AEItems;
import appeng.helpers.IConfigInvHost;
import appeng.hooks.ticking.TickHandler;
import appeng.items.parts.PartItem;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.automation.AbstractLevelEmitterPart;
import appeng.parts.PartModel;
import appeng.util.ConfigInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Multi-Level Emitter Part - 实现多物品监控和逻辑关系判断
 * 基于 AbstractLevelEmitterPart 实现，类似 StorageLevelEmitterPart
 */
public class MultiLevelEmitterPart extends AbstractLevelEmitterPart
        implements IConfigInvHost, ICraftingProvider, IUpgradeableObject {

    private static final String NBT_LOGIC_RELATIONS = "logic_relations";
    private static final String NBT_CONFIGURED_ITEM_COUNT = "configured_item_count";
    
    // 多物品发信器模型路径（使用 @PartModels 注解让 AE2 自动注册）
    @PartModels
    private static final ResourceLocation MODEL_BASE = ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "part/multi_level_emitter_base");
    
    @PartModels
    private static final ResourceLocation MODEL_STATUS_ON = ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "part/multi_level_emitter_status_on");
    
    @PartModels
    private static final ResourceLocation MODEL_STATUS_OFF = ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "part/multi_level_emitter_status_off");
    
    // 定义模型组合（根据状态返回）
    private static final IPartModel MODEL_ON = new PartModel(MODEL_BASE, MODEL_STATUS_ON);
    private static final IPartModel MODEL_OFF = new PartModel(MODEL_BASE, MODEL_STATUS_OFF);

    // 配置槽（支持动态数量的物品）
    private final ConfigInventory config = ConfigInventory.configTypes(64, this::configureWatchers); // 初始大小64，可根据需要调整
    
    // 每个槽位的lastReportedValue（实际数量），对应每个配置物品的数量
    private final java.util.Map<Integer, Long> lastReportedValues = new java.util.HashMap<>();
    
    // 每个槽位的reportingValue（阈值），对应每个配置物品的阈值
    private final java.util.Map<Integer, Long> reportingValues = new java.util.HashMap<>();
    
    // 本地客户端状态，用于处理客户端显示
    private boolean localClientSideOn = false;


    // 升级槽（最多 2 个升级：模糊卡和合成卡）
    private final IUpgradeInventory upgrades;
    
    // 逻辑关系列表（物品种类数 - 1 个逻辑关系）
    private final List<LogicRelation> logicRelations = new ArrayList<>();
    
    // 比较模式列表（每个物品一个比较模式）
    private final List<ComparisonMode> comparisonModes = new ArrayList<>();


    private IStackWatcher storageWatcher;
    private IStackWatcher craftingWatcher;
    private long lastUpdateTick = -1L;

    @Override
    public void writeVisualStateToNBT(CompoundTag data) {
        super.writeVisualStateToNBT(data);

        data.putBoolean("on", isLevelEmitterOn());
    }
    
    @Override
    public void readVisualStateFromNBT(CompoundTag data) {
        super.readVisualStateFromNBT(data);

        this.localClientSideOn = data.getBoolean("on");
    }

    public MultiLevelEmitterPart(PartItem<?> partItem) {
        super(partItem);
        
        // Level emitters do not require a channel to function
        getMainNode().setFlags();
        
        // 创建升级库存，使用方法引用
        this.upgrades = UpgradeInventories.forMachine(partItem.asItem(), 2, this::upgradesChanged);
        
        // 注册配置管理器设置
        getConfigManager().registerSetting(Settings.CRAFT_VIA_REDSTONE, YesNo.NO);
        getConfigManager().registerSetting(Settings.FUZZY_MODE, FuzzyMode.IGNORE_ALL);
        
        // 添加存储监听服务
        IStorageWatcherNode stackWatcherNode = new IStorageWatcherNode() {
            @Override
            public void updateWatcher(IStackWatcher newWatcher) {
                storageWatcher = newWatcher;
                configureWatchers();
            }

            @Override
            public void onStackChange(AEKey what, long amount) {
                // 如果没有安装模糊卡，检查是否是配置的物品发生了变化
                if (!isUpgradedWith(AEItems.FUZZY_CARD)) {
                    // 检查变化的物品是否在配置槽位中
                    int itemCount = getConfiguredItemCount();
                    boolean isConfiguredItem = false;
                    int slotIndex = -1;
                    
                    for (int i = 0; i < itemCount; i++) {
                        AEKey configuredKey = config.getKey(i);
                        if (configuredKey != null && what.equals(configuredKey)) {
                            isConfiguredItem = true;
                            slotIndex = i;
                            break;
                        }
                    }
                    
                    if (isConfiguredItem && slotIndex >= 0) {
                        // 直接更新对应的lastReportedValue
                        lastReportedValues.put(slotIndex, amount);
                        updateState();
                        return;
                    }
                }
                
                // 如果是模糊卡升级或变化的不是配置物品，则重新扫描整个网络
                long currentTick = TickHandler.instance().getCurrentTick();
                if (currentTick != lastUpdateTick) {
                    lastUpdateTick = currentTick;
                    IGrid grid = getMainNode().getNode().getGrid();
                    if (grid != null) {
                        updateReportingValue(grid);
                    }
                }
            }
        };
        getMainNode().addService(IStorageWatcherNode.class, stackWatcherNode);
        
        ICraftingWatcherNode craftingWatcherNode = new ICraftingWatcherNode() {
            @Override
            public void updateWatcher(IStackWatcher newWatcher) {
                craftingWatcher = newWatcher;
                configureWatchers();
            }

            @Override
            public void onRequestChange(AEKey what) {
                updateState();
            }

            @Override
            public void onCraftableChange(AEKey what) {
            }
        };
        getMainNode().addService(ICraftingWatcherNode.class, craftingWatcherNode);
        getMainNode().addService(ICraftingProvider.class, this);
    }
    
    @Override
    protected int getUpgradeSlots() {
        return 2; // 只支持模糊卡和合成卡
    }
    
    @Override
    public void upgradesChanged() {
        this.configureWatchers();
    }
    
    @Override
    public IPartModel getStaticModels() {
        if (this.isActive() && this.isPowered()) {
            return this.isLevelEmitterOn() ? MODEL_ON : MODEL_OFF;
        } else if (this.isPowered()) {
            return this.isLevelEmitterOn() ? MODEL_ON : MODEL_OFF;
        } else {
            return MODEL_OFF;
        }
    }
    
    @Override
    protected void configureWatchers() {
        int itemCount = getConfiguredItemCount();
        
        if (this.storageWatcher != null) {
            this.storageWatcher.reset();
        }

        if (this.craftingWatcher != null) {
            this.craftingWatcher.reset();
        }

        ICraftingProvider.requestUpdate(getMainNode());

        if (isUpgradedWith(AEItems.CRAFTING_CARD)) {
            if (this.craftingWatcher != null) {
                if (itemCount == 0) this.craftingWatcher.setWatchAll(true);
                else {
                    for (int i = 0; i < itemCount; i++){
                        AEKey key = config.getKey(i);
                        if (key != null) {
                            this.craftingWatcher.add(key);
                        }
                    }
                }
            }
        } else {
            if (this.storageWatcher != null) {
                if (isUpgradedWith(AEItems.FUZZY_CARD) || itemCount == 0) {
                    this.storageWatcher.setWatchAll(true);
                } else {
                    for (int i = 0; i < itemCount; i++) {
                        AEKey key = config.getKey(i);
                        if (key != null) {
                            this.storageWatcher.add(key);
                        }
                    }
                }
            }
            getMainNode().ifPresent(this::updateReportingValue);
        }

    }
    
    private void updateReportingValue(IGrid grid) {
        var stacks = grid.getStorageService().getCachedInventory();
        
        // 性能优化：只遍历已配置的槽位（强制按顺序配置）
        int itemCount = getConfiguredItemCount();
        
        // 清空之前的报告值
        lastReportedValues.clear();
        
        if (isUpgradedWith(AEItems.FUZZY_CARD)) {
            var fzMode = this.getConfigManager().getSetting(Settings.FUZZY_MODE);
            for (int i = 0; i < itemCount; i++) {
                AEKey key = config.getKey(i);
                if (key != null) {
                    var fuzzyList = stacks.findFuzzy(key, fzMode);
                    long totalValue = 0;
                    for (var st : fuzzyList) {
                        totalValue += st.getLongValue();
                    }
                    lastReportedValues.put(i, totalValue);

                }
            }
        } else {
            for (int i = 0; i < itemCount; i++) {
                AEKey key = config.getKey(i);
                if (key != null) {
                    lastReportedValues.put(i, stacks.get(key));
                }
            }
        }
        
        updateState();
    }

    @Override
    protected boolean hasDirectOutput() {
        return isUpgradedWith(AEItems.CRAFTING_CARD);
    }
    
    @Override
    protected boolean getDirectOutput() {
        var grid = this.getMainNode().getGrid();
        if (grid != null) {
            // 性能优化：只检查已配置的物品是否被请求
            int itemCount = getConfiguredItemCount();
            if (itemCount > 0) {
                for (int i = 0; i < itemCount; i++) {
                    AEKey key = config.getKey(i);
                    if (key != null && grid.getCraftingService().isRequesting(key)) {
                        return true;
                    }
                }
            }
            else {
                return grid.getCraftingService().isRequestingAny();
            }

            // 如果没有任何配置物品被请求，检查是否有任何物品被请求（当没有配置物品时）
        }
        return false;
    }
    
    @Override
    public List<appeng.api.crafting.IPatternDetails> getAvailablePatterns() {
        return List.of();
    }
    
    @Override
    public boolean pushPattern(appeng.api.crafting.IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return false;
    }
    
    @Override
    public boolean isBusy() {
        return true;
    }
    
    @Override
    public Set<AEKey> getEmitableItems() {
            if (isUpgradedWith(AEItems.CRAFTING_CARD)
                    && getConfigManager().getSetting(Settings.CRAFT_VIA_REDSTONE) == YesNo.YES) {
                // 性能优化：只遍历已配置的槽位（强制按顺序配置）
                int itemCount = getConfiguredItemCount();
                if (itemCount > 0) {
                    java.util.Set<AEKey> result = new java.util.HashSet<>();
                    for (int i = 0; i < itemCount; i++) {
                        AEKey key = config.getKey(i);
                        if (key != null) {
                            result.add(key);
                        }
                    }
                    return result;
                }
            }
            return Set.of();
        }
    
    @Override
    protected void onReportingValueChanged() {
        getMainNode().ifPresent(this::updateReportingValue);
    }
    
    @Override
    public boolean onPartActivate(Player player, InteractionHand hand, Vec3 pos) {
        if (!isClientSide()) {
            MenuOpener.open(MultiLevelEmitterMenu.MENU_TYPE.get(), player, MenuLocators.forPart(this));
        }
        return true;
    }
    
    @Override
    protected boolean isLevelEmitterOn() {
        if (isClientSide()) {
            // 对于客户端，返回本地客户端状态
            return localClientSideOn;
        }

        if (!this.getMainNode().isActive()) {
            return false;
        }

        if (hasDirectOutput()) {
            return getDirectOutput();
        }

        // 实现多物品逻辑判断
        int itemCount = getConfiguredItemCount();


        // 计算每个配置物品的数量和阈值比较结果
        List<Boolean> results = new ArrayList<>(itemCount);

        for (int i = 0; i < itemCount; i++) {
            AEKey key = config.getKey(i);
            if (key != null) {
                ComparisonMode mode = getComparisonMode(i);

                // 比较实际数量与阈值
                boolean conditionMet;
                if (Objects.requireNonNull(mode) == ComparisonMode.LESS_THAN) {
                    conditionMet = lastReportedValues.get(i) < reportingValues.get(i);
                } else {
                    conditionMet = lastReportedValues.get(i) >= reportingValues.get(i);
                }
                
                results.add(conditionMet);
            } else {
                // 如果槽位没有配置物品，则视为不满足条件
                results.add(false);
            }
        }

        // 根据逻辑关系组合结果
        boolean finalResult = isFinalResult(results);

        // 根据红石模式取反
        RedstoneMode rsMode = getConfigManager().getSetting(Settings.REDSTONE_EMITTER);
        if (rsMode == RedstoneMode.HIGH_SIGNAL) {
            return finalResult;
        } else {
            return !finalResult;
        }
    }

    private boolean isFinalResult(List<Boolean> results) {
        boolean finalResult;
        if (results.isEmpty()) {
            finalResult = false;
        } else if (results.size() == 1) {
            finalResult = results.get(0);
        } else {
            // 构建逻辑关系进行组合判断
            finalResult = results.get(0);
            for (int i = 0; i < results.size() - 1; i++) {
                LogicRelation relation = i < getLogicRelations().size() ? getLogicRelations().get(i) : LogicRelation.AND;

                boolean nextResult = results.get(i + 1);

                if (relation == LogicRelation.AND) {
                    finalResult = finalResult && nextResult;
                } else { // OR
                    finalResult = finalResult || nextResult;
                }
            }
        }
        return finalResult;
    }

    public int getConfiguredItemCount() {
        return MultiLevelEmitterUtils.calculateConfiguredItemCount(config);
    }
    
    /**
     * 获取配置槽位对象
     * @return 配置槽位对象
     */
    
    public long getReportingValueForSlot(int slot) {
        if (slot >= 0) {
            return reportingValues.getOrDefault(slot, 0L);
        }
        return 0;
    }
    
    /**
     * 刷新红石信号（公共方法，供 Menu 调用）
     */
    public void refreshRedstoneSignal() {
        updateState();
    }
    
    /**
     * 设置指定槽位的阈值
     */
    public void setReportingValueForSlot(int slot, long value) {
        if (slot >= 0) {
            long oldValue = reportingValues.getOrDefault(slot,0L);
            reportingValues.put(slot, value);
            
            // 如果阈值确实发生了变化，则标记需要保存
            if (oldValue != value) {
                // 确保Part被标记为需要保存，以解决阈值无法保留的问题
                getHost().markForSave();
                // 直接调用 updateState() 来刷新红石信号
                updateState();
                
                // 同时确保网络节点被标记为需要保存，防止快速操作时数据丢失
                getMainNode().ifPresent((grid, node) -> updateState());
            }
        }
    }
    
    @Override
    public void readFromNBT(CompoundTag data) {
        super.readFromNBT(data);
        config.readFromChildTag(data, "config");
        
        // 读取配置物品数量（用于验证）
        if (data.contains(NBT_CONFIGURED_ITEM_COUNT)) {
            data.getInt(NBT_CONFIGURED_ITEM_COUNT);
        }
/*
        // 读取阈值数组
        thresholds.clear(); // 清空现有阈值
        if (data.contains("thresholds", Tag.TAG_COMPOUND)) {
            var thresholdTag = data.getCompound("thresholds");
            for (String key : thresholdTag.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    long value = thresholdTag.getLong(key);
                    thresholds.put(slot, value);
                } catch (NumberFormatException e) {
                    // 忽略无效的键
                    com.mojang.logging.LogUtils.getLogger().warn("Invalid threshold key: {}", key);
                }
            }

        }
        */
        // 读取逻辑关系
        logicRelations.clear();
        logicRelations.addAll(MultiLevelEmitterUtils.readLogicRelationsFromNBT(data, NBT_LOGIC_RELATIONS));
        
        // 读取比较模式
        comparisonModes.clear();
        comparisonModes.addAll(MultiLevelEmitterUtils.readComparisonModesFromNBT(data, "comparison_modes"));
        
        // 读取lastReportedValues（如果存在）
        lastReportedValues.clear();
        if (data.contains("lastReportedValues", Tag.TAG_COMPOUND)) {
            var reportedValuesTag = data.getCompound("lastReportedValues");
            for (String key : reportedValuesTag.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    long value = reportedValuesTag.getLong(key);
                    lastReportedValues.put(slot, value);
                } catch (NumberFormatException e) {
                    // 忽略无效的键
                    com.mojang.logging.LogUtils.getLogger().warn("Invalid lastReportedValue key: {}", key);
                }
            }
        }
        
        // 读取reportingValues（如果存在）
        reportingValues.clear();
        if (data.contains("reportingValues", Tag.TAG_COMPOUND)) {
            var reportingValuesTag = data.getCompound("reportingValues");
            for (String key : reportingValuesTag.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    long value = reportingValuesTag.getLong(key);
                    reportingValues.put(slot, value);
                } catch (NumberFormatException e) {
                    // 忽略无效的键
                    com.mojang.logging.LogUtils.getLogger().warn("Invalid reportingValue key: {}", key);
                }
            }
        }
        
        // 在读取 NBT 后，重新计算配置物品数量并更新配置监控器
        // 这确保了即使 NBT 中保存的值不正确，也能从配置槽位中重新计算
        int actualItemCount = getConfiguredItemCount();
        

        
        // 清理超出实际配置数量的逻辑关系和比较模式
        // 逻辑关系数量 = 物品数量 - 1
        int expectedLogicRelationCount = Math.max(0, actualItemCount - 1);
        while (logicRelations.size() > expectedLogicRelationCount) {
            logicRelations.remove(logicRelations.size() - 1);
        }
        while (logicRelations.size() < expectedLogicRelationCount) {
            logicRelations.add(LogicRelation.AND);
        }
        
        // 比较模式数量 = 物品数量
        while (comparisonModes.size() > actualItemCount) {
            comparisonModes.remove(comparisonModes.size() - 1);
        }
        while (comparisonModes.size() < actualItemCount) {
            comparisonModes.add(ComparisonMode.GREATER_OR_EQUAL);
        }
        
        if (actualItemCount > 0) {
            configureWatchers();
        }
        
        // 标记配置物品数量已从 NBT 读取

        // 通知配置已加载，触发重新计算配置物品数量并同步到客户端
        onConfigLoaded();
    }
    
    /**
     * 配置加载完成后的回调
     * 重新计算配置物品数量并同步到客户端
     */
    private void onConfigLoaded() {
        // 重新计算配置物品数量
        getConfiguredItemCount();

        // 标记需要更新，这样下一次打开 GUI 时会使用正确的值
        getHost().markForUpdate();
        
        // 强制保存更改，确保配置数据被正确保存
        getHost().markForSave();
        
        // 刷新红石信号
        updateState();
    }
    
    @Override
    public void writeToNBT(CompoundTag data) {
        super.writeToNBT(data);
        config.writeToChildTag(data, "config");
        
        // 写入配置物品数量
        data.putInt(NBT_CONFIGURED_ITEM_COUNT, getConfiguredItemCount());

        

        
        // 写入逻辑关系
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(logicRelations, data, NBT_LOGIC_RELATIONS);
        
        // 写入比较模式
        MultiLevelEmitterUtils.writeComparisonModesToNBT(comparisonModes, data, "comparison_modes");
        
        // 写入lastReportedValues
        var reportedValuesTag = new net.minecraft.nbt.CompoundTag();
        for (java.util.Map.Entry<Integer, Long> entry : lastReportedValues.entrySet()) {
            reportedValuesTag.putLong(String.valueOf(entry.getKey()), entry.getValue());
        }
        data.put("lastReportedValues", reportedValuesTag);
        
        // 写入reportingValues
        var reportingValuesTag = new net.minecraft.nbt.CompoundTag();
        for (java.util.Map.Entry<Integer, Long> entry : reportingValues.entrySet()) {
            reportingValuesTag.putLong(String.valueOf(entry.getKey()), entry.getValue());
        }
        data.put("reportingValues", reportingValuesTag);
    }
    
    public ConfigInventory getConfig() {
        return config;
    }
    
    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }
    
    public List<LogicRelation> getLogicRelations() {
        return logicRelations;
    }

    public void setLogicRelations(List<LogicRelation> relations) {
        logicRelations.clear();
        logicRelations.addAll(relations);
        this.getHost().markForUpdate();
        this.getHost().markForSave();
        // 直接调用 updateState() 来刷新红石信号
        updateState();
    }
    
    public void setLogicRelation(int index, LogicRelation relation) {
        while (logicRelations.size() <= index) {
            logicRelations.add(LogicRelation.AND);
        }
        logicRelations.set(index, relation);
        this.getHost().markForUpdate();
        this.getHost().markForSave();
        // 直接调用 updateState() 来刷新红石信号
        updateState();
    }
    
    public List<ComparisonMode> getComparisonModes() {
        return new ArrayList<>(comparisonModes);
    }
    
    public void setComparisonModes(List<ComparisonMode> modes) {
        comparisonModes.clear();
        comparisonModes.addAll(modes);
        this.getHost().markForUpdate();
        this.getHost().markForSave();
        // 直接调用 updateState() 来刷新红石信号
        updateState();
    }
    
    public ComparisonMode getComparisonMode(int index) {
        while (comparisonModes.size() <= index) {
            comparisonModes.add(ComparisonMode.GREATER_OR_EQUAL);
        }
        return comparisonModes.get(index);
    }
    
    public void setComparisonMode(int index, ComparisonMode mode) {
        while (comparisonModes.size() <= index) {
            comparisonModes.add(ComparisonMode.GREATER_OR_EQUAL);
        }
        comparisonModes.set(index, mode);
        this.getHost().markForUpdate();
        this.getHost().markForSave();
        // 直接调用 updateState() 来刷新红石信号
        updateState();
    }

    /**
     * 逻辑关系枚举
     */
    public enum LogicRelation {
        AND,  // 与逻辑
        OR    // 或逻辑
    }
    
    /**
     * 比较模式枚举
     */
    public enum ComparisonMode {
        GREATER_OR_EQUAL,  // 大于等于
        LESS_THAN          // 小于
    }
}
