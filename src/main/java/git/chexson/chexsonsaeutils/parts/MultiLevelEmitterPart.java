package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.FuzzyMode;
import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingWatcherNode;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
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
    
    // 配置槽（支持无限个物品，初始 6 个基础槽位 + 可选扩展）
    private final ConfigInventory config = ConfigInventory.configTypes(256, this::configureWatchers);
    
    // 使用Map动态管理阈值，确保阈值与物品正确关联
    private final java.util.Map<Integer, Long> thresholds = new java.util.HashMap<>();
    
    // 升级槽（最多 2 个升级：模糊卡和合成卡）
    private final IUpgradeInventory upgrades;
    
    // 逻辑关系列表（物品种类数 - 1 个逻辑关系）
    private final List<LogicRelation> logicRelations = new ArrayList<>();
    
    // 比较模式列表（每个物品一个比较模式）
    private final List<ComparisonMode> comparisonModes = new ArrayList<>();
    
    // 标志：配置物品数量是否已从 NBT 读取
    private boolean configuredItemCountLoadedFromNBT = false;
    
    private IStackWatcher storageWatcher;
    private IStackWatcher craftingWatcher;
    private long lastUpdateTick = -1L;

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
                long currentTick = TickHandler.instance().getCurrentTick();
                if (currentTick != lastUpdateTick) {
                    lastUpdateTick = currentTick;
                    // 直接调用 updateState() 来刷新红石信号
                    // 不需要调用 updateReportingValue()，因为 isLevelEmitterOn() 会直接从存储中获取物品数量
                    updateState();
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
                this.craftingWatcher.setWatchAll(true);
            }
        } else {
            if (this.storageWatcher != null) {
                if (isUpgradedWith(AEItems.FUZZY_CARD)) {
                    this.storageWatcher.setWatchAll(true);
                } else {
                    // 性能优化：只监控已配置的槽位中的物品（强制按顺序配置）
                    for (int i = 0; i < itemCount; i++) {
                        AEKey key = config.getKey(i);
                        if (key != null) {
                            this.storageWatcher.add(key);
                        }
                    }
                }
            }
        }
        
        // 直接调用 updateState() 来刷新红石信号
        // 不需要调用 updateReportingValue()，因为 isLevelEmitterOn() 会直接从存储中获取物品数量
        updateState();
    }
    
    private void updateReportingValue(IGrid grid) {
        var stacks = grid.getStorageService().getCachedInventory();
        
        // 性能优化：只遍历已配置的槽位（强制按顺序配置）
        int itemCount = getConfiguredItemCount();
        
        if (isUpgradedWith(AEItems.FUZZY_CARD)) {
            this.lastReportedValue = 0;
            var fzMode = this.getConfigManager().getSetting(Settings.FUZZY_MODE);
            for (int i = 0; i < itemCount; i++) {
                AEKey key = config.getKey(i);
                if (key != null) {
                    var fuzzyList = stacks.findFuzzy(key, fzMode);
                    for (var st : fuzzyList) {
                        this.lastReportedValue += st.getLongValue();
                    }
                }
            }
        } else {
            this.lastReportedValue = 0;
            for (int i = 0; i < itemCount; i++) {
                AEKey key = config.getKey(i);
                if (key != null) {
                    this.lastReportedValue += stacks.get(key);
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
            return grid.getCraftingService().isRequestingAny();
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
            return Set.of();
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
        int itemCount = getConfiguredItemCount();
        if (itemCount == 0) {
            return false;
        }
        
        // 尝试获取 grid 和 storage，不检查 main node 是否激活
        IGridNode node = this.getMainNode().getNode();
        if (node == null || !node.isActive()) {
            return false;
        }
        
        IGrid grid = node.getGrid();
        if (grid == null) {
            return false;
        }
        
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) {
            return false;
        }
        
        // 计算每个配置物品的数量
        List<Long> currentCounts = new ArrayList<>(itemCount);
        List<Long> thresholds = new ArrayList<>(itemCount);
        List<ComparisonMode> modes = new ArrayList<>(itemCount);
        
        // 确保比较模式列表大小正确
        while (comparisonModes.size() < itemCount) {
            comparisonModes.add(ComparisonMode.GREATER_OR_EQUAL);
        }
        
        for (int i = 0; i < itemCount; i++) {
            AEKey key = config.getKey(i);
            if (key != null) {
                long count = storage.getAvailableStacks().get(key);
                long threshold = getReportingValueForSlot(i);
                ComparisonMode mode = comparisonModes.get(i);
                
                currentCounts.add(count);
                thresholds.add(threshold);
                modes.add(mode);
            }
        }
        
        // 根据逻辑关系判断是否激活
        boolean result;
        if (logicRelations.isEmpty()) {
            // 默认使用 AND 逻辑
            result = checkAndLogic(currentCounts, thresholds, modes);
        } else {
            result = checkLogicRelations(currentCounts, thresholds, modes);
        }
        
        // 根据红石模式取反
        RedstoneMode rsMode = getConfigManager().getSetting(Settings.REDSTONE_EMITTER);
        if (rsMode == RedstoneMode.HIGH_SIGNAL) {
            return result;
        } else {
            return !result;
        }
    }
    
    private boolean checkAndLogic(List<Long> currentCounts, List<Long> thresholds, List<ComparisonMode> modes) {
        for (int i = 0; i < currentCounts.size(); i++) {
            boolean condition = checkCondition(currentCounts.get(i), thresholds.get(i), modes.get(i));
            if (!condition) {
                return false;
            }
        }
        return true;
    }

    private boolean checkCondition(long currentCount, long threshold, ComparisonMode mode) {
        return switch (mode) {
            case GREATER_OR_EQUAL -> currentCount >= threshold;
            case LESS_THAN -> currentCount < threshold;
        };
    }
    
    private boolean checkLogicRelations(List<Long> currentCounts, List<Long> thresholds, List<ComparisonMode> modes) {
        // 性能优化：确保逻辑关系数量匹配（逻辑关系数量 = 物品数量 - 1）
        int itemCount = currentCounts.size();
        if (itemCount == 0) {
            return false;
        }
        
        if (itemCount == 1) {
            // 只有一个物品，直接返回结果
            return checkCondition(currentCounts.get(0), thresholds.get(0), modes.get(0));
        }
        
        // 确保逻辑关系列表大小正确（物品数量 - 1）
        while (logicRelations.size() < itemCount - 1) {
            logicRelations.add(LogicRelation.AND);
        }
        
        List<Boolean> conditions = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            boolean condition = checkCondition(currentCounts.get(i), thresholds.get(i), modes.get(i));
            conditions.add(condition);
        }
        
        // 根据逻辑关系列表计算最终结果
        boolean result = conditions.get(0);
        for (int i = 0; i < itemCount - 1; i++) {
            LogicRelation relation = logicRelations.get(i);
            boolean nextCondition = conditions.get(i + 1);
            if (relation == LogicRelation.AND) {
                result = result && nextCondition;
            } else {
                result = result || nextCondition;
            }
        }
        
        return result;
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
            return thresholds.getOrDefault(slot, 0L);
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
            long oldValue = thresholds.getOrDefault(slot, 0L);
            thresholds.put(slot, value);
            
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
        
        // 读取逻辑关系
        logicRelations.clear();
        logicRelations.addAll(MultiLevelEmitterUtils.readLogicRelationsFromNBT(data, NBT_LOGIC_RELATIONS));
        
        // 读取比较模式
        comparisonModes.clear();
        comparisonModes.addAll(MultiLevelEmitterUtils.readComparisonModesFromNBT(data, "comparison_modes"));
        
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
        configuredItemCountLoadedFromNBT = true;
        
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
        
        // 写入阈值数据
        var thresholdTag = new net.minecraft.nbt.CompoundTag();
        for (java.util.Map.Entry<Integer, Long> entry : thresholds.entrySet()) {
            thresholdTag.putLong(String.valueOf(entry.getKey()), entry.getValue());
        }
        data.put("thresholds", thresholdTag);
        

        
        // 写入逻辑关系
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(logicRelations, data, NBT_LOGIC_RELATIONS);
        
        // 写入比较模式
        MultiLevelEmitterUtils.writeComparisonModesToNBT(comparisonModes, data, "comparison_modes");
    }
    
    public ConfigInventory getConfig() {
        return config;
    }
    
    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }
    
    public List<LogicRelation> getLogicRelations() {
        return new ArrayList<>(logicRelations);
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

    public boolean isConfiguredItemCountLoadedFromNBT() {
        return configuredItemCountLoadedFromNBT;
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
