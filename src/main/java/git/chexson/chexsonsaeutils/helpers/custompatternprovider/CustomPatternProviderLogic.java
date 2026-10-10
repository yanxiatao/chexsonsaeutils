/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

/*
 * 本文件 fork 自 AE2 19.2.17 的 appeng/helpers/patternprovider/PatternProviderLogic.java，
 * 保留 LGPL-3.0 头（见 THIRD_PARTY_NOTICES.md）。
 * 阶段 2 继承改造（R2 评审确认）：本类改为 extends PatternProviderLogic，宿主 getLogic()
 * 返回类型与 AE2 PatternProviderLogicHost 兼容（阶段 3 GUI 复用铺路）。
 * 父类所有字段/关键方法均为 private，无法直接访问，故本类自建定制状态
 * （sendList/returnInv/patternInputs/unlockEvent/unlockStack/redstoneState/sendDirection/
 * priority/filteredImport/outputCache/energyInjector），并覆写父类同名方法保留 fork 语义；
 * 父类字段（patternInventory/configManager/patterns）经 public 访问器
 * （getPatternInv/getConfigManager/super.updatePatterns）复用。
 * 改动点（均以注释标注）：
 * 1. 包名与宿主类型改为本项目（CustomPatternProviderLogicHost）。
 * 2. target 解析：删除相邻方块缓存（PatternProviderTargetCache/findAdapter/getActiveSides），
 *    改为按宿主 getTargets() 方向解析相邻机器的 IItemHandler（resolveMachineHandler）。
 * 3. pushPattern/sendStacksOut：推送目标固定为解析出的那台机器，不向其它周围方块发/收（需求 8）。
 * 4. getTerminalGroup：不做相邻机器分组遍历，直接用宿主图标。
 * 5. 新增 pullFromMachine()：主动抽取机器输出到返回库存（需求 8 主动抽取按钮）。
 * 6. sendDirection 字段保留仅为旧存档 NBT 兼容，新逻辑无方向语义。
 * 7. 返回库存改用 CustomPatternProviderReturnInventory：带输入过滤，且单格可超过物品堆叠上限
 *    （主动抽取一次搬空机器整槽产出的前提）。
 * 8. 继承改造（阶段 2）：父类 Ticker 由本类构造器 addService 覆盖（ManagedGridNode
 *    addService 为 putInstance 覆盖语义）——父类 Ticker 驱动父类 doWork（sendStacksOut
 *    依赖 sendDirection + 相邻方块适配器，sendDirection 为 null 且 sendList 非空时抛
 *    IllegalStateException），本类推送目标为按方向解析的机器，必须由本类 Ticker 驱动
 *    本类 doWork（灌电 + 机器目标补发）；父类 sendList/returnInv/patternInputs/
 *    unlockEvent/unlockStack/redstoneState/priority 恒空/恒 0 闲置。
 */

package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingWatcherNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.settings.TickRates;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.helpers.patternprovider.UnlockCraftingEvent;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomProcessingPattern;
import git.chexson.chexsonsaeutils.integration.CustomPatternEnergyInjector;
import git.chexson.chexsonsaeutils.integration.appflux.AppFluxEnergyInjectorImpl;
import git.chexson.chexsonsaeutils.integration.extendedae_plus.EapWirelessBridgeHelper;

/**
 * 定制样板供应器的样板供应逻辑（继承 AE2 PatternProviderLogic）。
 * <p>
 * 继承改造（阶段 2，R2 评审确认）：宿主 getLogic() 返回类型与 AE2
 * PatternProviderLogicHost 兼容，为阶段 3 GUI 复用（Menu/Screen 继承
 * PatternProviderMenu/Screen）铺路。父类所有字段/关键方法均为 private，
 * 无法直接访问，故本类自建定制状态（sendList/returnInv/patternInputs/
 * unlockEvent/unlockStack/redstoneState/sendDirection/priority/
 * filteredImport/outputCache/energyInjector），并覆写父类同名方法保留
 * fork 语义；父类字段（patternInventory/configManager/patterns）经
 * public 访问器（getPatternInv/getConfigManager/super.updatePatterns）复用。
 * <p>
 * 与 AE2 原版的差异（需求 8 隔离语义）：推送/抽取目标经 {@link #resolveMachineHandler()}
 * 解析——遍历宿主 {@code getTargets()} 方向取第一个可用相邻机器，全部方向不可用时拒绝
 * 推送与抽取；不像原版那样向周围所有方块广播推送。
 * 返回库存为 {@link CustomPatternProviderReturnInventory}：只收符合输入过滤的机器输出，
 * 单格可超过物品堆叠上限（超过后该格在 GUI 为只读包装栈，点击可取回一栈）；
 * 额外提供 {@link #pullFromMachine()} 主动抽取机器输出到返回库存。
 * 其余行为（样板解码、阻塞模式、锁定模式、sendList 补发、终端展示）与 AE2 一致。
 * <p>
 * 仅支持 processing 样板（S3）：crafting 样板依赖 ICraftingMachine 推送路径（分子装配机），
 * 该路径已在 fork 时删除，crafting 样板无法推送到机器，也不会被主动抽取。
 */
public class CustomPatternProviderLogic extends PatternProviderLogic {
    private static final Logger LOG = LoggerFactory.getLogger(CustomPatternProviderLogic.class);

    public static final String NBT_MEMORY_CARD_PATTERNS = "patterns";
    public static final String NBT_UNLOCK_EVENT = "unlockEvent";
    public static final String NBT_UNLOCK_STACK = "unlockStack";
    public static final String NBT_PRIORITY = "priority";
    public static final String NBT_SEND_LIST = "sendList";
    public static final String NBT_SEND_DIRECTION = "sendDirection";
    public static final String NBT_RETURN_INV = "returnInv";
    /** 需求 6a：输入过滤开关 NBT key。 */
    public static final String NBT_FILTERED_IMPORT = "filteredImport";
    /** 需求 8 toggle：主动抽取开关 NBT key（开启时 Ticker 周期性调用 pullFromMachine）。 */
    public static final String NBT_ACTIVE_EXTRACT = "activeExtract";

    private final CustomPatternProviderLogicHost host;
    private final IManagedGridNode mainNode;
    private final IActionSource actionSource;

    /**
     * 推送优先级（R2：父类 priority private 不可达，自建字段并覆写
     * getPriority/setPriority/getPatternPriority；父类 priority 恒 0 闲置）。
     */
    private int priority;

    // Pattern sending logic（父类 sendList private 不可达，自建；父类 sendList 恒空闲置）
    private final List<GenericStack> sendList = new ArrayList<>();
    /**
     * 保留仅为旧存档 NBT 兼容（原版字段），新逻辑无方向语义（推送目标按 getTargets() 方向解析）。
     */
    private Direction sendDirection;
    // Stack returning logic（父类 returnInv 无输入过滤与超堆叠上限，自建带过滤实例；
    // 父类 returnInv 恒空闲置）
    private final CustomPatternProviderReturnInventory returnInv;
    /**
     * 需求 6a：输入过滤开关（NBT 持久化，Menu @GuiSync 同步）。
     * 过滤开启时 returnInv 注入网络只放行已配置样板的输出物品（outputCache）。
     */
    private boolean filteredImport;
    /**
     * 需求 8 toggle：主动抽取开关（NBT 持久化，Menu @GuiSync 同步）。
     * 开启时 Ticker 周期性调用 {@link #pullFromMachine()} 把机器输出抽到返回库存。
     */
    private boolean activeExtract;
    /**
     * 需求 6a：已配置样板的输出物品集合（updatePatterns 时重建）。
     * 适配说明：advancedae 的 trackedCrafts（进行中 crafting 请求）语义不适用——
     * 本项目 fork 仅支持 processing 样板（crafting 请求路径已删除），故只保留
     * outputCache 等价语义（放行样板输出物品回网络）。
     */
    private final HashSet<AEKey> outputCache = new HashSet<>();
    /**
     * blocking 模式输入集合（父类 patternInputs private 不可达，自建；updatePatterns 重建）。
     */
    private final Set<AEKey> patternInputs = new HashSet<>();
    /**
     * 主动抽取缓存（{@link #rebuildPullCaches()} 懒重建，{@link #updatePatterns()} 置脏）：
     * 配了抽取槽位的样板槽位并集，pass1 无条件抽取。
     */
    private final Set<Integer> pullExtractSlots = new HashSet<>();
    /** 未配抽取槽位的样板输出并集，pass2 在输入过滤开启时的白名单。 */
    private final Set<AEItemKey> pullOutputKeys = new HashSet<>();
    /** 是否存在未配抽取槽位的可推送样板：false 时 pass2 整体跳过。 */
    private boolean hasLoosePattern;
    /** 抽取缓存失效标记：初值 true 保证首次抽取前必建表。 */
    private boolean pullCachesDirty = true;
    /**
     * 活跃合成追踪（照 advancedae AdvPatternProviderLogic）：经 AE2 官方
     * ICraftingWatcherNode 监听网络合成请求——网络中正在请求合成的样板输出集合。
     * 输入过滤白名单优先级：trackedCrafts 非空时以其为准，空时兜底 outputCache。
     * 不持久化（watcher 随网格重建，请求状态由 AE2 重新回调）。
     */
    private final Set<AEKey> trackedCrafts = new HashSet<>();
    /** 合成监视器句柄（updateWatcher 时由 AE2 注入；updatePatterns 重设关注集合用）。 */
    @Nullable
    private IStackWatcher craftingWatcher;
    /**
     * 需求 7：appflux 感应卡灌电注入器（接口隔离——appflux 引用集中在实现类）。
     * appflux 未加载时为 null，Ticker 跳过灌电。
     */
    @Nullable
    private final CustomPatternEnergyInjector energyInjector;

    private YesNo redstoneState = YesNo.UNDECIDED;

    @Nullable
    private UnlockCraftingEvent unlockEvent;
    @Nullable
    private GenericStack unlockStack;

    @Nullable
    public CustomPatternProviderLogic(IManagedGridNode mainNode, CustomPatternProviderLogicHost host) {
        this(mainNode, host, 9);
    }

    public CustomPatternProviderLogic(IManagedGridNode mainNode, CustomPatternProviderLogicHost host,
            int patternInventorySize) {
        super(mainNode, host, patternInventorySize);
        this.host = host;
        this.mainNode = mainNode;
        this.actionSource = new MachineSource(mainNode::getNode);
        this.returnInv = new CustomPatternProviderReturnInventory(
                () -> {
                    this.mainNode.ifPresent(
                            (grid, node) -> grid.getTickManager().alertDevice(node));
                    this.host.saveChanges();
                },
                // 需求 6a：输入过滤三级逻辑统一由 passesInputFilter 承担
                (slot, what) -> this.passesInputFilter(what),
                // 返回栏单格超堆叠总闸：懒读配置，切换开关不重建库存实例
                ChexsonsaeutilsCompatibilityConfig::customPatternProviderOverstackReturnEnabled);
        // 需求 7：appflux 感应卡灌电注入器（未装 appflux 时为 null）
        this.energyInjector = AppFluxEnergyInjectorImpl.create(host, mainNode, actionSource);
        // R2 硬性要求：覆盖父类构造器注册的 Ticker（ManagedGridNode.addService 为
        // putInstance 覆盖语义）。父类 Ticker 驱动父类 doWork（sendStacksOut 依赖
        // sendDirection + 相邻方块适配器，sendDirection 为 null 且 sendList 非空时
        // 抛 IllegalStateException），本类推送目标为按方向解析的机器，必须由本类 Ticker
        // 驱动本类 doWork（灌电 + 机器目标补发）。
        mainNode.addService(IGridTickable.class, new Ticker());
        // 活跃合成追踪（照 advancedae）：注册 AE2 合成监视器节点服务，
        // 网络合成请求变化时回调 onRequestChange 维护 trackedCrafts
        mainNode.addService(ICraftingWatcherNode.class, new ICraftingWatcherNode() {
            @Override
            public void updateWatcher(IStackWatcher newWatcher) {
                craftingWatcher = newWatcher;
                updatePatterns();
            }

            @Override
            public void onRequestChange(AEKey what) {
                if (trackedCrafts.contains(what)) {
                    trackedCrafts.remove(what);
                } else {
                    trackedCrafts.add(what);
                }
            }

            @Override
            public void onCraftableChange(AEKey what) {
            }
        });
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
        this.host.saveChanges();

        ICraftingProvider.requestUpdate(mainNode);
    }

    @Override
    public int getPatternPriority() {
        return this.priority;
    }

    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        // R2 NBT 顺序铁律：super 必须最前（写父类字段：configManager/patternInventory/
        // priority（恒 0 闲置）/unlockEvent（闲置）/sendList（空）/sendDirection（闲置）/
        // returnInv（空）），子类字段后写覆盖同名 key（顺序颠倒 = 存档静默丢失）。
        super.writeToNBT(tag, registries);
        if (unlockEvent == UnlockCraftingEvent.REDSTONE_POWER) {
            tag.putByte(NBT_UNLOCK_EVENT, (byte) 1);
        } else if (unlockEvent == UnlockCraftingEvent.RESULT) {
            if (unlockStack != null) {
                tag.putByte(NBT_UNLOCK_EVENT, (byte) 2);
                tag.put(NBT_UNLOCK_STACK, GenericStack.writeTag(registries, unlockStack));
            } else {
                LOG.error("Saving pattern provider {}, locked waiting for stack, but stack is null!", host);
            }
        } else if (unlockEvent == UnlockCraftingEvent.REDSTONE_PULSE) {
            tag.putByte(NBT_UNLOCK_EVENT, (byte) 3);
        }

        ListTag sendListTag = new ListTag();
        for (var toSend : sendList) {
            sendListTag.add(GenericStack.writeTag(registries, toSend));
        }
        tag.put(NBT_SEND_LIST, sendListTag);
        if (sendDirection != null) {
            tag.putByte(NBT_SEND_DIRECTION, (byte) sendDirection.get3DDataValue());
        }

        tag.put(NBT_RETURN_INV, this.returnInv.writeToTag(registries));
        tag.putBoolean(NBT_FILTERED_IMPORT, this.filteredImport);
        tag.putBoolean(NBT_ACTIVE_EXTRACT, this.activeExtract);
        tag.putInt(NBT_PRIORITY, this.priority);
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        // R2 NBT 顺序铁律：super 必须最前（读父类字段：configManager/patternInventory/
        // priority（恒 0 闲置）/unlockEvent（闲置）/sendList（空）/sendDirection（闲置）/
        // returnInv（空）），子类字段后读覆盖（顺序颠倒 = 存档静默丢失）。
        super.readFromNBT(tag, registries);

        var unlockEventType = tag.getByte(NBT_UNLOCK_EVENT);
        this.unlockEvent = switch (unlockEventType) {
            case 0 -> null;
            case 1 -> UnlockCraftingEvent.REDSTONE_POWER;
            case 2 -> UnlockCraftingEvent.RESULT;
            case 3 -> UnlockCraftingEvent.REDSTONE_PULSE;
            default -> {
                LOG.error("Unknown unlock event type {} in NBT for pattern provider: {}", unlockEventType, tag);
                yield null;
            }
        };
        if (this.unlockEvent == UnlockCraftingEvent.RESULT) {
            this.unlockStack = GenericStack.readTag(registries, tag.getCompound(NBT_UNLOCK_STACK));
            if (this.unlockStack == null) {
                LOG.error("Could not load unlock stack for pattern provider from NBT: {}", tag);
            }
        } else {
            this.unlockStack = null;
        }

        var sendListTag = tag.getList("sendList", Tag.TAG_COMPOUND);
        for (int i = 0; i < sendListTag.size(); ++i) {
            var stack = GenericStack.readTag(registries, sendListTag.getCompound(i));
            if (stack != null) {
                this.addToSendList(stack.what(), stack.amount());
            }
        }
        if (tag.contains("sendDirection")) {
            sendDirection = Direction.from3DDataValue(tag.getByte("sendDirection"));
        }

        this.returnInv.readFromTag(tag.getList("returnInv", Tag.TAG_COMPOUND), registries);
        this.filteredImport = tag.getBoolean(NBT_FILTERED_IMPORT);
        this.activeExtract = tag.getBoolean(NBT_ACTIVE_EXTRACT);
        this.priority = tag.getInt(NBT_PRIORITY);
    }

    /**
     * @return 输入过滤开关（需求 6a）：开启时 returnInv 注入网络只放行已配置样板的输出物品
     */
    public boolean isFilteredImport() {
        return filteredImport;
    }

    /**
     * 设置输入过滤开关（需求 6a），服务端权威（Menu client action 调用）。
     *
     * @param filteredImport 新状态
     */
    public void setFilteredImport(boolean filteredImport) {
        if (this.filteredImport == filteredImport) {
            return;
        }
        this.filteredImport = filteredImport;
        this.host.saveChanges();
    }

    /**
     * 外部输入过滤判定（照 advancedae AdvPatternProviderReturnInventory 三级逻辑）：
     * 过滤关闭放行任意输入；开启时优先以 trackedCrafts（网络正在请求合成的样板输出，
     * 即"活跃样板"的动态集合）为白名单，trackedCrafts 为空（无进行中合成）时兜底
     * 放行样板输出（outputCache）。
     * <p>
     * 供宿主对外部 capability 访问（管道/漏斗等主动 IO）做插入过滤；
     * Logic 自身的推送/抽取走内部路径，不经此判定。
     *
     * @param key 待判定物品
     * @return true 表示允许插入
     */
    public boolean passesInputFilter(AEKey key) {
        if (!this.filteredImport) {
            return true;
        }
        if (!this.trackedCrafts.isEmpty()) {
            return this.trackedCrafts.contains(key);
        }
        return this.outputCache.contains(key);
    }

    /**
     * @return 主动抽取开关（需求 8 toggle，服务端权威，Menu @GuiSync 同步）
     */
    public boolean isActiveExtract() {
        return activeExtract;
    }

    /**
     * 设置主动抽取开关（需求 8 toggle），服务端权威（Menu client action 调用）。
     * 开启后 Ticker 最快每 tick 调用一次 {@link #pullFromMachine()}（URGENT 维持，
     * 机器无产出时 SLOWER 休眠降频，间隔由 AE2 封顶）。
     * <p>
     * 开启时主动唤醒网格 tick：Ticker 空闲时返回 SLEEP（网格不调用 tickingRequest），
     * 不唤醒则主动抽取永不生效（照 returnInv 变化唤醒模式）。
     *
     * @param activeExtract 新状态
     */
    public void setActiveExtract(boolean activeExtract) {
        if (this.activeExtract == activeExtract) {
            return;
        }
        this.activeExtract = activeExtract;
        this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
        this.host.saveChanges();
    }

    /**
     * 阶段 2 旧存档兼容：把旧 NBT key（patternInventory/returnInventory，AppEngInternalInventory
     * 物品格式）迁移到新 key（patterns/returnInv，本逻辑的库存格式）。
     * <p>
     * 新存档不含旧 key，本方法直接跳过；旧存档不含新 key，readFromNBT 后库存为空，由本方法填充。
     *
     * @param tag        完整方块 NBT（含旧 key）
     * @param registries 注册表提供者
     */
    public void migrateLegacyInventory(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("patternInventory")) {
            var legacy = new AppEngInternalInventory(getPatternInv().size());
            legacy.readFromNBT(tag, "patternInventory", registries);
            for (int i = 0; i < legacy.size(); i++) {
                var stack = legacy.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    getPatternInv().setItemDirect(i, stack);
                }
            }
        }
        if (tag.contains("returnInventory")) {
            var legacy = new AppEngInternalInventory(returnInv.size());
            legacy.readFromNBT(tag, "returnInventory", registries);
            for (int i = 0; i < legacy.size(); i++) {
                var stack = legacy.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    var key = AEItemKey.of(stack);
                    if (key != null) {
                        returnInv.insert(key, stack.getCount(), Actionable.MODULATE, actionSource);
                    }
                }
            }
        }
    }

    @Override
    public void updatePatterns() {
        // R2 patterns 单一数据源：super 先填父类 patterns（getAvailablePatterns 复用父类，
        // pushPattern 的 contains 校验与 getAvailablePatterns 同源），子类再自建
        // patternInputs/outputCache（父类 patternInputs private 不可达）。
        super.updatePatterns();
        patternInputs.clear();
        outputCache.clear();
        // 主动抽取缓存：样板集变化即失效，由 pullFromMachine 下次抽取前懒重建
        this.pullCachesDirty = true;
        // 活跃合成追踪（照 advancedae）：样板集变化时重设关注集合——关注所有样板输出，
        // 网络合成请求变化经 onRequestChange 维护 trackedCrafts
        if (craftingWatcher != null) {
            craftingWatcher.reset();
        }

        for (var stack : getPatternInv()) {
            var details = PatternDetailsHelper.decodePattern(stack, this.host.getBlockEntity().getLevel());

            if (details != null) {
                // 需求 6a：收集样板输出物品（输入过滤放行集合）
                for (var output : details.getOutputs()) {
                    if (craftingWatcher != null) {
                        craftingWatcher.add(output.what());
                    }
                    outputCache.add(output.what());
                }

                for (var iinput : details.getInputs()) {
                    for (var inputCandidate : iinput.getPossibleInputs()) {
                        patternInputs.add(inputCandidate.what().dropSecondary());
                    }
                }
            }
        }
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!sendList.isEmpty() || !this.mainNode.isActive()
                || !getAvailablePatterns().contains(patternDetails)) {
            return false;
        }

        if (getCraftingLockedReason() != LockCraftingMode.NONE) {
            return false;
        }

        // 需求 4a：定制样板走强制槽位写入路径（不经过 target 的普通插入模拟）
        if (patternDetails instanceof CustomProcessingPattern customPattern) {
            return pushCustomPattern(customPattern, inputHolder);
        }

        // 机器目标解析见 getMachineTarget()：遍历 getTargets() 方向取第一个可用机器；
        // 无可用方向时拒绝推送（不向周围其它方块发/收，原版此处遍历
        // getActiveSides 的相邻机器与适配器）。
        var target = getMachineTarget();
        if (target == null) {
            return false;
        }

        // If no dedicated crafting machine could be found, and the pattern does not support
        // generic external inventories, stop here.
        if (!patternDetails.supportsPushInputsToExternalInventory()) {
            return false;
        }

        if (this.isBlocking() && target.containsPatternInput(this.patternInputs)) {
            return false;
        }

        if (this.adapterAcceptsAll(target, inputHolder)) {
            patternDetails.pushInputsToExternalInventory(inputHolder, (what, amount) -> {
                var inserted = target.insert(what, amount, Actionable.MODULATE);
                if (inserted < amount) {
                    this.addToSendList(what, amount - inserted);
                }
            });
            onPushPatternSuccess(patternDetails);
            this.sendStacksOut();
            return true;
        }

        return false;
    }

    /**
     * 需求 4a：定制样板的强制槽位推送。
     * <p>
     * 与普通路径（adapterAcceptsAll + pushInputsToExternalInventory）不同：
     * 每个稀疏输入按 slotMapping 强制写入机器指定槽位（setStackInSlot 合并），
     * 未指定槽位（-1 或越界）走 insertItemStacked 普通插入。两种容量模式：
     * <ul>
     * <li>严格模式（overflowStacks=false，默认）：受槽有效容量上限约束
     * min(getSlotLimit, maxStackSize)，超限整体拒绝以避免 vanilla 容器 setItem
     * limitSize 截断吞料；预检失败整体拒绝，不产生部分写入。</li>
     * <li>突破模式（overflowStacks=true）：跳过槽容量预检，写入后读回实际存量，
     * 差额退回 sendList 排队重试——支持超限的机器真突破；vanilla 容器填满为止
     * +剩余排队重试。两种模式均不吞料。</li>
     * </ul>
     * 异物占用与 int 溢出校验在两种模式下都保留。
     * blocking 模式语义与普通路径一致：机器内已有样板输入时拒绝。
     *
     * @return true 表示推送成功
     */
    private boolean pushCustomPattern(CustomProcessingPattern pattern, KeyCounter[] inputHolder) {
        IItemHandler handler = resolveMachineHandler();
        if (handler == null) {
            return false;
        }
        boolean modifiable = handler instanceof IItemHandlerModifiable;
        if (!modifiable) {
            // S3 修复：非 modifiable 机器无法强制写入，指定槽位静默降级为普通插入——输出日志
            LOG.warn(
                    "Machine handler {} is not IItemHandlerModifiable; frame pattern slot mapping degrades to regular insertion",
                    handler.getClass().getName());
        }
        var sparseInputs = pattern.getSparseInputs();
        var slotMapping = pattern.getSlotMapping();

        // blocking 模式：机器内已有样板输入时拒绝（与普通路径 containsPatternInput 等价）
        if (this.isBlocking()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    AEItemKey key = AEItemKey.of(stack);
                    if (key != null && this.patternInputs.contains(key.dropSecondary())) {
                        return false;
                    }
                }
            }
        }

        var allInputs = new KeyCounter();
        for (var counter : inputHolder) {
            allInputs.addAll(counter);
        }

        // 预检：输入足量 + 指定槽位未被异物占用（合并不覆盖）
        for (int i = 0; i < sparseInputs.size(); i++) {
            var sparseInput = sparseInputs.get(i);
            if (sparseInput == null) {
                continue;
            }
            if (!(sparseInput.what() instanceof AEItemKey itemKey)) {
                return false; // 强制槽位写入仅支持物品输入（与普通路径对非物品返回 0 一致）
            }
            if (allInputs.get(itemKey) < sparseInput.amount()) {
                return false;
            }
            int slot = i < slotMapping.length ? slotMapping[i] : -1;
            if (modifiable && slot >= 0 && slot < handler.getSlots()) {
                var existing = handler.getStackInSlot(slot);
                if (!existing.isEmpty() && !itemKey.matches(existing)) {
                    return false;
                }
                // S2 修复：合并后总量用 long 计算，超 int 上限时拒绝（grow 参数溢出会静默截断）
                if ((long) existing.getCount() + sparseInput.amount() > Integer.MAX_VALUE) {
                    return false;
                }
                // S4 修复（严格模式）：槽容量上限校验——vanilla 容器（箱子等 Container 方块）的
                // setItem 会对超限栈执行 limitSize 截断，强制合并超出槽上限的部分会被静默吞掉
                // （网络已扣料、任务已推进 → 材料凭空消失）。此处按 NeoForge 插入约定取
                // min(getSlotLimit, maxStackSize) 为有效上限，不足量整体拒绝推送，
                // 由 AE2 阻塞等待机器腾出空间（正确流控，而非部分写入）。
                // 突破模式跳过此校验：写入后读回实际存量、差额退回排队重试，同样不吞料。
                // 空槽也要校验（probeStack 用 toStack 构造只为读 maxStackSize）；
                // 若 existing 本身已是历史超限栈（count > limit），校验必然拒绝 → 推送永久阻塞，
                // 这是可接受的 Fail Fast（暴露异常状态优于静默吞料）。
                if (!pattern.isOverflowStacksAllowed()) {
                    var probeStack = existing.isEmpty()
                            ? itemKey.toStack((int) Math.min(sparseInput.amount(), Integer.MAX_VALUE))
                            : existing;
                    int effectiveLimit = Math.min(handler.getSlotLimit(slot), probeStack.getMaxStackSize());
                    if ((long) existing.getCount() + sparseInput.amount() > effectiveLimit) {
                        return false;
                    }
                }
            }
        }

        // 写入：指定槽位 setStackInSlot 合并（仅 modifiable handler），未指定槽位普通插入
        for (int i = 0; i < sparseInputs.size(); i++) {
            var sparseInput = sparseInputs.get(i);
            if (sparseInput == null) {
                continue;
            }
            var itemKey = (AEItemKey) sparseInput.what();
            long amount = sparseInput.amount();
            int slot = i < slotMapping.length ? slotMapping[i] : -1;
            if (modifiable && slot >= 0 && slot < handler.getSlots()) {
                var existing = handler.getStackInSlot(slot);
                long before = existing.getCount();
                // 空槽位合并修复：existing.copy() 对空栈产生 AIR 物品栈（item=Items.AIR），
                // grow 后写入 AIR x N——网络已扣原料、箱子槽位看似为空、任务挂起等输出。
                // 空槽位必须用 itemKey.toStack 创建真实物品栈；非空槽位才走 copy+grow 合并。
                ItemStack merged = existing.isEmpty() ? itemKey.toStack((int) amount) : existing.copy();
                if (!existing.isEmpty()) {
                    merged.grow((int) amount); // 预检已保证合并后不超 int 上限
                }
                ((IItemHandlerModifiable) handler).setStackInSlot(slot, merged);
                // 突破模式：读回实际存量计算真实写入量，差额退回 sendList 排队重试
                // （vanilla 容器 setItem 会 limitSize 截断超限部分；支持超限的机器则全量写入）
                if (pattern.isOverflowStacksAllowed()) {
                    long written = handler.getStackInSlot(slot).getCount() - before;
                    if (written < amount) {
                        this.addToSendList(itemKey, amount - written);
                    }
                }
            } else {
                var remaining = ItemHandlerHelper.insertItemStacked(handler,
                        itemKey.toStack((int) Math.min(amount, Integer.MAX_VALUE)), false);
                if (!remaining.isEmpty()) {
                    this.addToSendList(itemKey, remaining.getCount());
                }
            }
            allInputs.remove(itemKey, amount);
        }

        onPushPatternSuccess(pattern);
        this.sendStacksOut();
        return true;
    }

    @Override
    public void resetCraftingLock() {
        if (unlockEvent != null) {
            unlockEvent = null;
            unlockStack = null;
            saveChanges();
        }
    }

    private void onPushPatternSuccess(IPatternDetails pattern) {
        resetCraftingLock();

        var lockMode = getConfigManager().getSetting(Settings.LOCK_CRAFTING_MODE);
        switch (lockMode) {
            case LOCK_UNTIL_PULSE -> {
                if (getRedstoneState()) {
                    // Already have signal, wait for no signal before switching to REDSTONE_POWER
                    unlockEvent = UnlockCraftingEvent.REDSTONE_PULSE;
                } else {
                    // No signal, wait for signal
                    unlockEvent = UnlockCraftingEvent.REDSTONE_POWER;
                }
                redstoneState = YesNo.UNDECIDED; // Check redstone state again next update
                saveChanges();
            }
            case LOCK_UNTIL_RESULT -> {
                unlockEvent = UnlockCraftingEvent.RESULT;
                unlockStack = pattern.getPrimaryOutput();
                saveChanges();
            }
        }
    }

    /**
     * Gets if the crafting lock is in effect and why.
     *
     * @return null if the lock isn't in effect
     */
    @Override
    public LockCraftingMode getCraftingLockedReason() {
        var lockMode = getConfigManager().getSetting(Settings.LOCK_CRAFTING_MODE);
        if (lockMode == LockCraftingMode.LOCK_WHILE_LOW && !getRedstoneState()) {
            // Crafting locked by redstone signal
            return LockCraftingMode.LOCK_WHILE_LOW;
        } else if (lockMode == LockCraftingMode.LOCK_WHILE_HIGH && getRedstoneState()) {
            return LockCraftingMode.LOCK_WHILE_HIGH;
        } else if (unlockEvent != null) {
            // Crafting locked by waiting for unlock event
            switch (unlockEvent) {
                case REDSTONE_POWER, REDSTONE_PULSE -> {
                    return LockCraftingMode.LOCK_UNTIL_PULSE;
                }
                case RESULT -> {
                    return LockCraftingMode.LOCK_UNTIL_RESULT;
                }
            }
        }
        return LockCraftingMode.NONE;
    }

    /**
     * @return Null if {@linkplain #getCraftingLockedReason()} is not {@link LockCraftingMode#LOCK_UNTIL_RESULT}.
     */
    @Override
    @Nullable
    public GenericStack getUnlockStack() {
        return unlockStack;
    }

    /**
     * 解析机器物品 handler：遍历 {@link CustomPatternProviderLogicHost#getTargets()} 方向，
     * 调用 {@link CustomPatternProviderLogicHost#getMachineItemHandler(Direction)}
     * 取第一个可用 handler（null = 该方向无机器，跳过）。
     * <p>
     * 全部方向不可用时返回 null，调用方拒绝推送/抽取（fail-closed）。
     *
     * @return 机器物品 handler；无可用方向时返回 null
     */
    @Nullable
    private IItemHandler resolveMachineHandler() {
        for (var direction : host.getTargets()) {
            var handler = host.getMachineItemHandler(direction);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /**
     * 需求 8 改造：解析相邻机器为推送目标（替代原版 findAdapter 的相邻方块缓存）。
     * <p>
     * 机器为 IItemHandler（物品通道），非物品通道的 AEKey 插入返回 0。
     * handler 来自 {@link #resolveMachineHandler()}：全部方向不可用时返回 null，
     * 调用方据此拒绝推送/补发。
     *
     * @return 目标机器的推送包装；无可用方向时返回 null
     */
    private PatternProviderTarget getMachineTarget() {
        IItemHandler handler = resolveMachineHandler();
        if (handler == null) {
            return null;
        }
        return new PatternProviderTarget() {
            @Override
            public long insert(AEKey what, long amount, Actionable type) {
                if (!(what instanceof AEItemKey itemKey)) {
                    return 0;
                }
                ItemStack stack = itemKey.toStack((int) Math.min(amount, Integer.MAX_VALUE));
                ItemStack remaining = ItemHandlerHelper.insertItemStacked(handler, stack, type == Actionable.SIMULATE);
                return stack.getCount() - remaining.getCount();
            }

            @Override
            public boolean containsPatternInput(Set<AEKey> patternInputs) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (!stack.isEmpty()) {
                        AEItemKey key = AEItemKey.of(stack);
                        if (key != null && patternInputs.contains(key.dropSecondary())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    private boolean adapterAcceptsAll(PatternProviderTarget target, KeyCounter[] inputHolder) {
        for (var inputList : inputHolder) {
            for (var input : inputList) {
                var inserted = target.insert(input.getKey(), input.getLongValue(), Actionable.SIMULATE);
                if (inserted == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void addToSendList(AEKey what, long amount) {
        if (amount > 0) {
            this.sendList.add(new GenericStack(what, amount));

            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
        }
    }

    /**
     * 需求 8 改造：sendList 补发到机器（原版按 sendDirection 找相邻适配器）。
     * 机器目标解析见 {@link #getMachineTarget()}（按 getTargets() 方向解析的机器）。
     * sendDirection 字段保留仅为旧存档 NBT 兼容，此处不再依赖。
     */
    private boolean sendStacksOut() {
        if (sendList.isEmpty()) {
            return false;
        }

        var target = getMachineTarget();
        if (target == null) {
            return false;
        }

        boolean didSomething = false;

        for (var it = sendList.listIterator(); it.hasNext();) {
            var stack = it.next();
            var what = stack.what();
            long amount = stack.amount();

            var inserted = target.insert(what, amount, Actionable.MODULATE);
            if (inserted >= amount) {
                it.remove();
                didSomething = true;
            } else if (inserted > 0) {
                it.set(new GenericStack(what, amount - inserted));
                didSomething = true;
            }
        }

        if (sendList.isEmpty()) {
            sendDirection = null;
        }

        return didSomething;
    }

    @Override
    public boolean isBusy() {
        return !sendList.isEmpty();
    }

    private boolean hasWorkToDo() {
        return !sendList.isEmpty() || !returnInv.isEmpty();
    }

    private boolean doWork() {
        // Note: bitwise OR to avoid short-circuiting.
        return returnInv.injectIntoNetwork(
                mainNode.getGrid().getStorageService().getInventory(), actionSource, this::onStackReturnedToNetwork)
                | sendStacksOut();
    }

    /**
     * 需求 8 主动抽取：把机器中与样板输出匹配的物品抽取到返回库存。
     * <p>
     * 机器目标解析见 {@link #resolveMachineHandler()}：遍历 {@code getTargets()} 方向取第一个
     * 可用 handler（该方向无机器时跳过）。两趟单扫：
     * <ul>
     * <li>pass 1：配置了抽取槽位（extractSlots）的样板槽位并集，无条件抽取、不受输入过滤开关
     * 影响（显式槽位配置语义优先）。</li>
     * <li>pass 2：存在未配抽取槽位的样板时单次扫描机器全部槽位。开关语义不变——输入过滤
     * 开启=只抽样板输出（{@link #pullOutputKeys} 白名单），关闭=抽取机器内所有物品。</li>
     * </ul>
     * 单槽一次抽完（{@code extractItem(slot, 槽内全部数量)}），配合返回栏单格超堆叠
     * （{@link CustomPatternProviderReturnInventory}）即可一轮搬空机器产出；返回栏装不下时
     * 余量回滚进机器槽，不丢料。
     * <p>
     * 只抽取 processing 样板的输出（I2 修复）：熔炉等机器的输入/燃料槽与样板输出不匹配，
     * 不会被抽走，避免机器断粮。crafting 样板（supportsPushInputsToExternalInventory 为 false）
     * 无法推送到本机器，跳过其输出。I2 修复：先实际抽取再按返回量插入 returnInv
     * （插入量 = min(机器实际抽取量, returnInv 可容纳量)），插入不足时剩余回滚到机器槽位，
     * 避免自定义 handler 实际抽取量 &lt; 模拟量导致物品复制。
     * 抽取成功后唤醒网格 tick 把返回库存注入网络。
     *
     * @return true 表示至少抽取了部分物品
     */
    public boolean pullFromMachine() {
        IItemHandler handler = resolveMachineHandler();
        if (handler == null) {
            return false;
        }
        if (this.pullCachesDirty) {
            this.rebuildPullCaches();
        }
        boolean didSomething = false;
        // pass 1：显式抽取槽位（需求 4a）——不受输入过滤开关影响
        for (int slot : this.pullExtractSlots) {
            if (slot >= 0 && slot < handler.getSlots()) {
                didSomething |= this.pullOneSlot(handler, slot);
            }
        }
        // pass 2：单次全机扫描（原实现按样板逐个重扫机器全部槽位 = 样板数 x 槽数）
        if (this.hasLoosePattern) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack machineStack = handler.getStackInSlot(slot);
                if (machineStack.isEmpty()) {
                    continue;
                }
                if (this.filteredImport) {
                    // AEItemKey 的 equals 与 matches 同为 item + 组件全等，集合判定等价
                    var key = AEItemKey.of(machineStack);
                    if (key == null || !this.pullOutputKeys.contains(key)) {
                        continue;
                    }
                }
                didSomething |= this.pullOneSlot(handler, slot);
            }
        }
        if (didSomething) {
            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
            this.host.saveChanges();
        }
        return didSomething;
    }

    /**
     * 抽取机器指定槽位的全部内容到返回库存。
     * <p>
     * I2 修复顺序：先实际抽取，再按真实抽取量插入 returnInv；插入不足时把余量回滚进机器槽，
     * 机器拒收回收时再进 sendList 补发——全程不复制、不吞料。
     *
     * @param handler 机器物品 handler
     * @param slot    机器槽位序号（调用方保证在界内）
     * @return true 表示至少抽到了 1 个
     */
    private boolean pullOneSlot(IItemHandler handler, int slot) {
        ItemStack machineStack = handler.getStackInSlot(slot);
        if (machineStack.isEmpty()) {
            return false;
        }
        ItemStack extracted = handler.extractItem(slot, machineStack.getCount(), false);
        if (extracted.isEmpty()) {
            return false;
        }
        var key = AEItemKey.of(extracted);
        if (key == null) {
            // 非物品栈无法进入 returnInv：回滚到机器槽位
            handler.insertItem(slot, extracted, false);
            return false;
        }
        long inserted = returnInv.insert(key, extracted.getCount(), Actionable.MODULATE, actionSource);
        if (inserted < extracted.getCount()) {
            // returnInv 装不下：剩余回滚到机器槽位
            var remainder = extracted.copy();
            remainder.shrink((int) inserted);
            var notInserted = handler.insertItem(slot, remainder, false);
            if (!notInserted.isEmpty()) {
                // 机器拒绝回收：进入 sendList 补发（兜底防物品丢失）
                var notInsertedKey = AEItemKey.of(notInserted);
                if (notInsertedKey != null) {
                    this.addToSendList(notInsertedKey, notInserted.getCount());
                }
            }
        }
        return inserted > 0;
    }

    /**
     * 重建主动抽取缓存（由 {@link #pullFromMachine()} 在脏标记下懒调用）。
     * <p>
     * 动机：原实现在每次抽取时把全部样板重新解码一遍，并对每个样板重扫机器全部槽位；
     * 样板上限为页数 x 36（默认 288），主动抽取每 tick 一次时解码开销与槽数成正比。
     */
    private void rebuildPullCaches() {
        this.pullCachesDirty = false;
        this.pullExtractSlots.clear();
        this.pullOutputKeys.clear();
        this.hasLoosePattern = false;

        for (var stack : getPatternInv()) {
            var details = PatternDetailsHelper.decodePattern(stack, this.host.getBlockEntity().getLevel());
            // 只登记可推送到机器的 processing 样板：crafting 样板无法推送，其输出也不应被抽走
            if (details == null || !details.supportsPushInputsToExternalInventory()) {
                continue;
            }
            if (details instanceof CustomProcessingPattern customPattern
                    && customPattern.getExtractSlots().length > 0) {
                for (int slot : customPattern.getExtractSlots()) {
                    this.pullExtractSlots.add(slot);
                }
            } else {
                this.hasLoosePattern = true;
                for (var output : details.getOutputs()) {
                    if (output.what() instanceof AEItemKey itemKey) {
                        this.pullOutputKeys.add(itemKey);
                    }
                }
            }
        }
    }

    @Override
    public void addDrops(List<ItemStack> drops) {
        for (var stack : getPatternInv()) {
            drops.add(stack);
        }

        for (var stack : this.sendList) {
            stack.what().addDrops(stack.amount(), drops, this.host.getBlockEntity().getLevel(),
                    this.host.getBlockEntity().getBlockPos());
        }

        this.returnInv.addDrops(drops, this.host.getBlockEntity().getLevel(), this.host.getBlockEntity().getBlockPos());

        // ExtendedAE_Plus 的 mixin 以 TAIL 注入父类 PatternProviderLogic.addDrops（频道卡库存
        // compatUpgrades 掉落）；子类覆写后注入不再触发，必须显式调 super 维持注入链。
        // 安全：父类 PatternProviderLogic 自身无闲置库存（本类全部库存已在上方掉落），
        // super 调用不会产生重复掉落。
        super.addDrops(drops);
    }

    @Override
    public void clearContent() {
        getPatternInv().clear();
        this.sendList.clear();
        this.returnInv.clear();

        // 同上：ExtendedAE_Plus mixin TAIL 注入父类 clearContent（频道卡库存清理），
        // 显式调 super 维持注入链，否则方块清空时频道卡库存残留。
        super.clearContent();
    }

    @Override
    public CustomPatternProviderReturnInventory getReturnInv() {
        return this.returnInv;
    }

    private void onStackReturnedToNetwork(GenericStack genericStack) {
        if (unlockEvent != UnlockCraftingEvent.RESULT) {
            return; // If we're not waiting for the result, we don't care
        }

        if (unlockStack == null) {
            // Actually an error state...
            LOG.error("pattern provider was waiting for RESULT, but no result was set");
            unlockEvent = null;
        } else if (unlockStack.what().equals(genericStack.what())) {
            var remainingAmount = unlockStack.amount() - genericStack.amount();
            if (remainingAmount <= 0) {
                unlockEvent = null;
                unlockStack = null;
            } else {
                unlockStack = new GenericStack(unlockStack.what(), remainingAmount);
            }
        }
    }

    private class Ticker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(TickRates.Interface, !hasWorkToDo());
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            // EAP 桥接 HEAD（镜像 PatternProviderLogicTickerMixin.eap$tickHead）：
            // EAP 的 mixin 只注入父类 PatternProviderLogic$Ticker，本类 Ticker 是新建
            // 内部类（不继承父类 Ticker），频道卡无线链接的延迟初始化必须在此镜像调用。
            // 与 addDrops/clearContent 末尾调 super 维持注入链的模式一致。
            // 类加载隔离（崩溃修复）：tickingRequest 是每 tick 热路径，不得直接引用
            // EAP 接口类——JVM JIT 编译该方法时解析常量池类引用，EAP 未加载时接口类
            // 不存在，ModLoaded 短路只防解释执行，JIT 编译期解析仍抛
            // NoClassDefFoundError。桥接委托给 EapWirelessBridgeHelper（仅引用本项目
            // 类，恒存在），辅助类只在 EAP 加载时被加载执行。客户端跳过（镜像 EAP
            // 守卫：node 为 null 时按服务端处理）。
            if (!(node != null && node.getLevel() != null && node.getLevel().isClientSide)
                    && ModList.get().isLoaded("extendedae_plus")) {
                EapWirelessBridgeHelper.handleDelayedInit(CustomPatternProviderLogic.this);
            }
            if (!mainNode.isActive()) {
                return TickRateModulation.SLEEP;
            }
            // 需求 7：appflux 感应卡灌电——每 tick 检测感应卡并灌电（上限由 AFConfig 控制）。
            // "不对其它面供电"已天然满足：capability 透传让外部访问任何面都等于访问私有
            // 维度机器，灌电直接走网格 → 机器，不经过供应器自身能量存储。
            if (energyInjector != null && energyInjector.isInstalled()
                    && ChexsonsaeutilsCompatibilityConfig.boolValue(
                    ChexsonsaeutilsCompatibilityConfig.CUSTOM_PATTERN_PROVIDER_ENABLED)) {
                energyInjector.injectEnergy(Integer.MAX_VALUE);
            }
            // 需求 8 toggle：主动抽取——每次被调用立即执行（最快每 tick 一次）。
            // 频率策略：抽到物品（didSomething）时 pullFromMachine 内部已 alertDevice 且
            // 下方返回 URGENT，维持每 tick 最快抽取；机器无产出时返回 SLOWER 休眠降频
            // （AE2 TickManager 间隔翻倍封顶约 128 tick≈6.4 秒，即休眠间隔上限），
            // 休眠期间每次被调用仍会尝试抽取，一旦抽到立即回到每 tick 节奏。
            boolean featureEnabled = ChexsonsaeutilsCompatibilityConfig.boolValue(
                    ChexsonsaeutilsCompatibilityConfig.CUSTOM_PATTERN_PROVIDER_ENABLED);
            var pulled = featureEnabled && activeExtract && pullFromMachine();
            boolean couldDoWork = featureEnabled && doWork();
            TickRateModulation result;
            if (hasWorkToDo()) {
                result = couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
            } else if (pulled) {
                // 刚抽到物品：维持每 tick 最快抽取节奏
                result = TickRateModulation.URGENT;
            } else if (activeExtract) {
                // 主动抽取开启但机器无产出：允许休眠降频（间隔由 AE2 封顶，不会无限拉长）
                result = TickRateModulation.SLOWER;
            } else {
                result = TickRateModulation.SLEEP;
            }
            // EAP 桥接 TAIL（镜像 PatternProviderLogicTickerMixin.eap$tickTail）：
            // 更新无线链接状态；有频道卡（eap$shouldKeepTicking）且本 tick 将 SLEEP 时
            // 改 SLOWER 保活——否则设备进入 SLEEP 后网格不再调用 tickingRequest，
            // 无线状态永不刷新。同样经 EapWirelessBridgeHelper 类加载隔离
            // （见 HEAD 注释：热路径字节码不得引用 EAP 接口类）。
            if (!(node != null && node.getLevel() != null && node.getLevel().isClientSide)
                    && ModList.get().isLoaded("extendedae_plus")) {
                if (EapWirelessBridgeHelper.updateWirelessLink(CustomPatternProviderLogic.this)
                        && result == TickRateModulation.SLEEP) {
                    result = TickRateModulation.SLOWER;
                }
            }
            return result;
        }
    }

    /**
     * @return Gets the name used to show this pattern provider in the
     *         {@link appeng.menu.implementations.PatternAccessTermMenu}.
     */
    @Override
    public PatternContainerGroup getTerminalGroup() {
        // 需求 8 改造：不做相邻机器分组遍历（原版遍历 getActiveSides 的相邻机器），
        // 直接用宿主图标与名称。
        var hostIcon = this.host.getTerminalIcon();
        return new PatternContainerGroup(
                hostIcon,
                hostIcon.getDisplayName(),
                List.of());
    }

    @Override
    public void updateRedstoneState() {
        // If we're waiting for a pulse, update immediately
        if (unlockEvent == UnlockCraftingEvent.REDSTONE_POWER && getRedstoneState()) {
            unlockEvent = null; // Unlocked!
            saveChanges();
        } else if (unlockEvent == UnlockCraftingEvent.REDSTONE_PULSE && !getRedstoneState()) {
            unlockEvent = UnlockCraftingEvent.REDSTONE_POWER; // Wait for re-power
            redstoneState = YesNo.UNDECIDED; // Need to re-check signal on next update
            saveChanges();
        } else {
            // Otherwise, just reset back to undecided
            redstoneState = YesNo.UNDECIDED;
        }
    }

    private boolean getRedstoneState() {
        if (redstoneState == YesNo.UNDECIDED) {
            var be = this.host.getBlockEntity();
            redstoneState = be.getLevel().hasNeighborSignal(be.getBlockPos())
                    ? YesNo.YES
                    : YesNo.NO;
        }
        return redstoneState == YesNo.YES;
    }
}