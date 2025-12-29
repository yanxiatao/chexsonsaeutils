package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.Settings;
import appeng.api.stacks.GenericStack;
import appeng.api.util.IConfigManager;
import appeng.core.definitions.AEItems;
import appeng.hooks.ticking.TickHandler;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.IOptionalSlotHost;
import appeng.util.ConfigInventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Level Emitter 配置菜单
 * 继承自 UpgradeableMenu 以支持配置功能
 */
public class MultiLevelEmitterMenu extends UpgradeableMenu<MultiLevelEmitterPart> 
    implements IOptionalSlotHost {
    
    private static final String ACTION_SET_REPORTING_VALUE = "setReportingValue";
    private static final String ACTION_SET_LOGIC_RELATION = "setLogicRelation";
    
    private static final DeferredRegister<MenuType<?>> MENU_TYPES = 
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, "chexsonsaeutils");
    
    public static final RegistryObject<MenuType<MultiLevelEmitterMenu>> MENU_TYPE = 
        MENU_TYPES.register("multi_level_emitter", 
            () -> MenuTypeBuilder.create(
                MultiLevelEmitterMenu::new, 
                MultiLevelEmitterPart.class
            ).withInitialData((host, buffer) -> {
                // 同步配置槽
                ConfigInventory config = host.getConfig();
                buffer.writeVarInt(config.size());
                for (int i = 0; i < config.size(); i++) {
                    GenericStack.writeBuffer(config.getStack(i), buffer);
                }
                
                // 同步配置物品数量（直接从配置槽位中计算，避免数据同步时序问题）
                int configuredItemCount = 0;
                for (int i = 0; i < config.size(); i++) {
                    if (config.getKey(i) != null) {
                        configuredItemCount++;
                    } else {
                        // 遇到第一个空槽位，停止计数
                        break;
                    }
                }
                buffer.writeVarInt(configuredItemCount);
                
                // 同步已配置槽位的阈值（只同步已配置的槽位）
                for (int i = 0; i < configuredItemCount; i++) {
                    buffer.writeVarLong(host.getReportingValueForSlot(i));
                }
                
                // 同步逻辑关系
                List<MultiLevelEmitterPart.LogicRelation> relations = host.getLogicRelations();
                buffer.writeVarInt(relations.size());
                for (MultiLevelEmitterPart.LogicRelation relation : relations) {
                    buffer.writeEnum(relation);
                }
                
                // 同步比较模式
                List<MultiLevelEmitterPart.ComparisonMode> modes = host.getComparisonModes();
                buffer.writeVarInt(modes.size());
                for (MultiLevelEmitterPart.ComparisonMode mode : modes) {
                    buffer.writeEnum(mode);
                }
            }, (host, menu, buffer) -> {
                // 读取配置槽
                int configSize = buffer.readVarInt();
                ConfigInventory config = host.getConfig();
                for (int i = 0; i < configSize && i < config.size(); i++) {
                    config.setStack(i, GenericStack.readBuffer(buffer));
                }
                
                // 读取配置物品数量
                menu.configuredItemCount = buffer.readVarInt();
                
                // 读取已配置槽位的阈值（只读取已配置的槽位）
                for (int i = 0; i < menu.configuredItemCount; i++) {
                    menu.thresholds.put(i, buffer.readVarLong());
                }
                
                // 读取逻辑关系
                int relationCount = buffer.readVarInt();
                menu.logicRelations.clear();
                for (int i = 0; i < relationCount; i++) {
                    menu.logicRelations.add(buffer.readEnum(MultiLevelEmitterPart.LogicRelation.class));
                }
                
                // 读取比较模式
                int modeCount = buffer.readVarInt();
                menu.comparisonModes.clear();
                for (int i = 0; i < modeCount; i++) {
                    menu.comparisonModes.add(buffer.readEnum(MultiLevelEmitterPart.ComparisonMode.class));
                }
                
                // 标记数据已同步
                menu.dataSynced = true;
                
                // 在数据同步后，重新计算配置物品数量（确保使用的是最新的配置槽位数据）
                int recalculatedCount = 0;
                for (int i = 0; i < config.size(); i++) {
                    if (config.getKey(i) != null) {
                        recalculatedCount++;
                    } else {
                        // 遇到第一个空槽位，停止计数
                        break;
                    }
                }
                if (recalculatedCount != menu.configuredItemCount) {
                    menu.configuredItemCount = recalculatedCount;
                    // 触发数据同步到客户端
                    menu.broadcastChanges();
                }
            }).build("multi_level_emitter"));
    
    // 阈值映射（每个配置槽一个阈值，支持动态数量）
    final java.util.Map<Integer, Long> thresholds = new java.util.HashMap<>();
    
    // 逻辑关系列表
    private final List<MultiLevelEmitterPart.LogicRelation> logicRelations = new ArrayList<>();
    
    // 比较模式列表
    private final List<MultiLevelEmitterPart.ComparisonMode> comparisonModes = new ArrayList<>();
    
    // 配置物品数量（通过 withInitialData 同步，同时使用 @GuiSync 用于动态更新）
    @GuiSync(7)
    public int configuredItemCount = 0;
    
    // 数据同步标志（用于 Screen 检测数据是否已同步）
    public boolean dataSynced = false;
    
    // 强制刷新标志（用于 Screen 检测是否需要强制刷新）
    @GuiSync(8)
    public boolean forceRefresh = false;

    // 阈值更新延迟处理器
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
        r -> new Thread(r, "MultiLevelEmitter-ThresholdUpdater")
    );


    public MultiLevelEmitterMenu(MenuType<MultiLevelEmitterMenu> menuType, int id, Inventory playerInventory, MultiLevelEmitterPart part) {
        super(menuType, id, playerInventory, part);
        
        // 注册客户端操作（为每个槽位注册独立的操作）
        for (int i = 0; i < 256; i++) {
            final int slot = i;
            final String actionName = ACTION_SET_REPORTING_VALUE + "_" + i;
            registerClientAction(actionName, Long.class, value -> {
                if (isClientSide()) {
                    long oldValue = this.thresholds.getOrDefault(slot, 0L);
                    if (value != oldValue) {
                        this.thresholds.put(slot, value);
                        sendClientAction(actionName, value);
                    }
                } else {
                    // 立即更新阈值，确保用户在GUI中修改的值立即保存
                    getHost().setReportingValueForSlot(slot, value);
                    this.thresholds.put(slot, value);
                    // 确保Part被标记为需要保存
                    getHost().getHost().markForSave();
                    // 立即同步到客户端
                    broadcastChanges();
                }
            });
        }
        
        // 注册逻辑关系客户端操作（为每个逻辑关系注册独立的操作）
        for (int i = 0; i < 255; i++) {
            final int index = i;
            final String actionName = ACTION_SET_LOGIC_RELATION + "_" + i;
            registerClientAction(actionName, MultiLevelEmitterPart.LogicRelation.class, relation -> {
                if (isClientSide()) {
                    // 更新客户端逻辑关系
                    while (logicRelations.size() <= index) {
                        logicRelations.add(MultiLevelEmitterPart.LogicRelation.AND);
                    }
                    logicRelations.set(index, relation);
                    sendClientAction(actionName, relation);
                } else {
                    getHost().setLogicRelation(index, relation);
                }
            });
        }
        
        // 注册比较模式客户端操作（为每个槽位注册独立的操作）
        for (int i = 0; i < 256; i++) {
            final int index = i;
            final String actionName = "setComparisonMode_" + i;
            registerClientAction(actionName, MultiLevelEmitterPart.ComparisonMode.class, mode -> {
                if (isClientSide()) {
                    // 更新客户端比较模式
                    while (comparisonModes.size() <= index) {
                        comparisonModes.add(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL);
                    }
                    comparisonModes.set(index, mode);
                    sendClientAction(actionName, mode);
                } else {
                    getHost().setComparisonMode(index, mode);
                }
            });
        }
        
        // 在服务端，延迟刷新数据，确保配置槽位的数据被正确加载后再计算配置物品数量
        if (!isClientSide()) {
            // 延迟 1 tick 后刷新数据
            TickHandler.instance().addCallable(null, () -> {
                // 重新计算配置物品数量
                int recalculatedCount = getHost().getConfiguredItemCount();
                
                // 如果重新计算的值与当前值不同，则更新并同步到客户端
                if (recalculatedCount != configuredItemCount) {
                    configuredItemCount = recalculatedCount;
                    broadcastChanges();
                }
            });
        }
    }
    
    @Override
    public void removed(net.minecraft.world.entity.player.@NotNull Player player) {
        super.removed(player);
        
        // 关闭调度器，防止内存泄漏
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    protected void setupConfig() {
        var inv = getHost().getConfig().createMenuWrapper();
        
        // 添加 6 个基础配置槽
        for (int i = 0; i < 6; i++) {
            this.addSlot(new FakeSlot(inv, i), SlotSemantics.CONFIG);
        }
        
        // 添加可选配置槽（无限个）
        for (int i = 6; i < inv.size(); i++) {
            this.addSlot(new appeng.menu.slot.OptionalFakeSlot(inv, this, i, i - 6), SlotSemantics.CONFIG);
        }
    }
    
    @Override
    protected void setupUpgrades() {
        setupUpgrades(this.getHost().getUpgrades());
    }
    
    /**
     * 计算配置槽中实际配置的物品数量
     * @param config 配置槽
     * @return 已配置的物品数量
     */
    private int calculateConfiguredItemCount(ConfigInventory config) {
        return MultiLevelEmitterUtils.calculateConfiguredItemCount(config);
    }
    
    @Override
    public void onSlotChange(Slot s) {
        super.onSlotChange(s);
        
        if (isClientSide()) {
            return;
        }
        
        // 强制按顺序配置：当配置槽变化时，自动清理不合法的配置
        var config = getHost().getConfig();
        
        // 先计算移动前的物品数量（oldItemCount）
        int oldItemCount = calculateConfiguredItemCount(config);
        

        
        // 保存当前阈值数据到临时变量，以便在槽位移动后恢复
        java.util.Map<Integer, Long> tempThresholds = new java.util.HashMap<>(thresholds);
        
        // 自动整理配置：如果有空槽位，将后面的槽位依次向前移动
        int writeIndex = 0;
        for (int readIndex = 0; readIndex < config.size(); readIndex++) {
            var stack = config.getStack(readIndex);
    
            if (stack != null && stack.what() != null) {
                if (readIndex != writeIndex) {
            
                    // 先移动阈值数据，再移动物品配置
                    // 将源阈值移动到目标位置，确保阈值与物品保持关联
                    tempThresholds.put(writeIndex, tempThresholds.getOrDefault(readIndex, 0L));
                    // 物品和阈值都移动到新位置
                    config.setStack(writeIndex, stack);
                    config.setStack(readIndex, null);
                    
                    // 移动比较模式（确保列表大小足够）
                    while (comparisonModes.size() <= Math.max(readIndex, writeIndex)) {
                        comparisonModes.add(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL);
                    }
                    comparisonModes.set(writeIndex, comparisonModes.get(readIndex));
                }
                writeIndex++;
            }
        }
        
        // 计算移动后的配置物品数量
        int newItemCount = writeIndex;
        

        
        // 重要：不要清除用户可能已设置的阈值，只对已配置的物品槽进行操作
        // 保持用户可能已设置的阈值不变
        // 只保留从0到newItemCount-1的阈值，其他保持不变
        java.util.Map<Integer, Long> newThresholds = new java.util.HashMap<>();
        for (int i = 0; i < newItemCount; i++) {
            newThresholds.put(i, tempThresholds.getOrDefault(i, 0L));
        }
        // 保留其他已存在的阈值
        for (java.util.Map.Entry<Integer, Long> entry : tempThresholds.entrySet()) {
            if (entry.getKey() >= newItemCount) {
                newThresholds.put(entry.getKey(), entry.getValue());
            }
        }
        tempThresholds.clear();
        tempThresholds.putAll(newThresholds);
        
        // 清理超出连续配置范围+1的槽位
        // 保留 newItemCount + 1 个槽位（最后一个空槽位用于反复配置）
        for (int i = newItemCount + 1; i < config.size(); i++) {
            var stack = config.getStack(i);
            if (stack != null && stack.what() != null) {
                config.setStack(i, null);
            }
        }
        
        // 清除超出范围的逻辑关系和阈值数据
        // 逻辑关系数量 = 物品数量 - 1
        int expectedLogicRelationCount = Math.max(0, newItemCount - 1);
        while (logicRelations.size() > expectedLogicRelationCount) {
            logicRelations.remove(logicRelations.size() - 1);
        }
        
        // 清除超出范围的比较模式数据
        while (comparisonModes.size() > newItemCount) {
            comparisonModes.remove(comparisonModes.size() - 1);
        }
        
        // 同步到 Part - 确保阈值数据正确保存到Part

        getHost().setLogicRelations(new ArrayList<>(logicRelations));
        getHost().setComparisonModes(new ArrayList<>(comparisonModes));
        
        // 逐个同步阈值到Part，确保数据持久化
        // 同步已配置物品的阈值（这些已经过移动操作调整）
        for (int i = 0; i < newItemCount; i++) {
            // 使用临时阈值映射（已经过移动操作调整），确保阈值与物品的正确关联
            long thresholdValue = tempThresholds.getOrDefault(i, 0L);
    
            getHost().setReportingValueForSlot(i, thresholdValue);
        }
        
        // 保持其他阈值不变（用户可能为未来添加的物品预设了阈值）
        // 获取当前Part中的其他阈值，避免将它们重置为0
        for (java.util.Map.Entry<Integer, Long> entry : tempThresholds.entrySet()) {
            int index = entry.getKey();
            if (index >= newItemCount) {
                long currentPartValue = getHost().getReportingValueForSlot(index);
                long tempValue = entry.getValue();
                if (tempValue != currentPartValue) {
                    // 只有当值确实改变了，才更新（避免不必要的保存操作）
                    getHost().setReportingValueForSlot(index, tempValue);
                }
            }
        }
        
        // 更新菜单中的阈值映射
        thresholds.clear();
        thresholds.putAll(tempThresholds);
        
        // 确保Part被标记为需要保存，以解决阈值无法保留的问题
        getHost().getHost().markForSave();
        
        // 更新配置物品数量
        configuredItemCount = newItemCount;
        
        // 如果物品数量减少，设置强制刷新标志
        if (newItemCount < oldItemCount) {
    
            forceRefresh = true;
            // 同步到客户端，确保GUI更新
            broadcastChanges();
        }
        
        // 刷新红石信号
        getHost().refreshRedstoneSignal();
        
        // 立即同步到客户端
        broadcastChanges();
    }
    
    @Override
    protected void loadSettingsFromHost(IConfigManager cm) {
        this.setCraftingMode(cm.getSetting(Settings.CRAFT_VIA_REDSTONE));
        if (cm.hasSetting(Settings.FUZZY_MODE)) {
            this.setFuzzyMode(cm.getSetting(Settings.FUZZY_MODE));
        }
        this.setRedStoneMode(cm.getSetting(Settings.REDSTONE_EMITTER));
    }
    
    @Override
    public boolean isSlotEnabled(int idx) {
        // 可选配置槽位始终启用（支持无限个槽位）
        // 基础槽位：0-5 始终启用
        // 可选槽位：6+ 始终启用
        return idx >= 0;
    }
    
    /**
     * 设置阈值
     */
    public void setReportingValue(long value, int slot) {
        if (isClientSide()) {
            long oldValue = this.thresholds.getOrDefault(slot, 0L);
            if (value != oldValue) {
                this.thresholds.put(slot, value);
                sendClientAction(ACTION_SET_REPORTING_VALUE + "_" + slot, value);
            }
        } else {
            // 服务端：立即更新 Part，不再使用延迟机制
            // 立即更新阈值，确保用户在GUI中修改的值立即保存
            getHost().setReportingValueForSlot(slot, value);
            this.thresholds.put(slot, value);
            // 确保Part被标记为需要保存
            getHost().getHost().markForSave();
            // 立即同步到客户端
            broadcastChanges();
        }
    }
    
    /**
     * 获取阈值
     */
    public long getThreshold(int slot) {
        boolean isClient = isClientSide();

        return isClient ? thresholds.getOrDefault(slot, 0L) : getHost().getReportingValueForSlot(slot);
    }
    
    /**
     * 获取逻辑关系
     */
    public List<MultiLevelEmitterPart.LogicRelation> getLogicRelations() {
        if (isClientSide()) {
            return new ArrayList<>(this.logicRelations);
        } else {
            return getHost().getLogicRelations();
        }
    }

    /**
     * 获取指定槽位的比较模式
     */
    public MultiLevelEmitterPart.ComparisonMode getComparisonMode(int index) {
        if (isClientSide()) {
            while (comparisonModes.size() <= index) {
                comparisonModes.add(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL);
            }
            return comparisonModes.get(index);
        } else {
            return getHost().getComparisonMode(index);
        }
    }
    
    /**
     * 设置指定槽位的比较模式
     */
    public void setComparisonMode(int index, MultiLevelEmitterPart.ComparisonMode mode) {
        if (isClientSide()) {
            while (comparisonModes.size() <= index) {
                comparisonModes.add(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL);
            }
            comparisonModes.set(index, mode);
            sendClientAction("setComparisonMode_" + index, mode);
        } else {
            getHost().setComparisonMode(index, mode);
        }
    }
    
    /**
     * 设置逻辑关系
     */
    public void setLogicRelation(int index, MultiLevelEmitterPart.LogicRelation relation) {
        if (isClientSide()) {
            // 更新客户端缓存的逻辑关系
            while (logicRelations.size() <= index) {
                logicRelations.add(MultiLevelEmitterPart.LogicRelation.AND);
            }
            MultiLevelEmitterPart.LogicRelation current = logicRelations.get(index);
            if (current != relation) {
                logicRelations.set(index, relation);
                sendClientAction("setLogicRelation_" + index, relation);
            }
        } else {
            getHost().setLogicRelation(index, relation);
        }
    }
    
    /**
     * 是否支持模糊搜索
     */
    public boolean supportsFuzzySearch() {
        return getHost().getConfigManager().hasSetting(Settings.FUZZY_MODE) && hasUpgrade(AEItems.FUZZY_CARD);
    }

    public static DeferredRegister<MenuType<?>> getMenuTypes() {
        return MENU_TYPES;
    }

}
