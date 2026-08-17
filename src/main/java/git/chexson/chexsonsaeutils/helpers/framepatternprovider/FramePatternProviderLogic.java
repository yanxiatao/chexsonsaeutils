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
 * 保留 LGPL-3.0 头（见 THIRD_PARTY_NOTICES.md）。改动点（均以注释标注）：
 * 1. 包名与宿主类型改为本项目（FramePatternProviderLogicHost）。
 * 2. target 解析：删除相邻方块缓存（PatternProviderTargetCache/findAdapter/getActiveSides），
 *    改为私有维度机器（FrameMachineAccess.getMachineItemHandler()，无 side 语义）。
 * 3. pushPattern/sendStacksOut：推送目标固定为私有维度机器，不向周围方块发/收（需求 8）。
 * 4. getTerminalGroup：机器在私有维度，无相邻机器分组，直接用宿主图标。
 * 5. 新增 pullFromMachine()：主动抽取机器输出到返回库存（需求 8 主动抽取按钮）。
 * 6. sendDirection 字段保留仅为旧存档 NBT 兼容，新逻辑无方向语义。
 */

package git.chexson.chexsonsaeutils.helpers.framepatternprovider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.IConfigManager;
import appeng.core.AELog;
import appeng.core.definitions.AEItems;
import appeng.core.localization.PlayerMessages;
import appeng.core.settings.TickRates;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.helpers.patternprovider.UnlockCraftingEvent;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.PlayerInternalInventory;

/**
 * 框架样板供应器的样板供应逻辑（fork 自 AE2 PatternProviderLogic）。
 * <p>
 * 与 AE2 原版的差异（需求 8 隔离语义）：推送目标固定为私有维度机器
 * （{@link FramePatternProviderLogicHost#getMachineItemHandler()}），不向周围方块发/收；
 * 返回库存只收机器输出；额外提供 {@link #pullFromMachine()} 主动抽取机器输出到返回库存。
 * 其余行为（样板解码、阻塞模式、锁定模式、sendList 补发、终端展示）与 AE2 一致。
 * <p>
 * 仅支持 processing 样板（S3）：crafting 样板依赖 ICraftingMachine 推送路径（分子装配机），
 * 该路径已在 fork 时删除，crafting 样板无法推送到私有维度机器，也不会被主动抽取。
 */
public class FramePatternProviderLogic implements InternalInventoryHost, ICraftingProvider {
    private static final Logger LOG = LoggerFactory.getLogger(FramePatternProviderLogic.class);

    public static final String NBT_MEMORY_CARD_PATTERNS = "patterns";
    public static final String NBT_UNLOCK_EVENT = "unlockEvent";
    public static final String NBT_UNLOCK_STACK = "unlockStack";
    public static final String NBT_PRIORITY = "priority";
    public static final String NBT_SEND_LIST = "sendList";
    public static final String NBT_SEND_DIRECTION = "sendDirection";
    public static final String NBT_RETURN_INV = "returnInv";

    private final FramePatternProviderLogicHost host;
    private final IManagedGridNode mainNode;
    private final IActionSource actionSource;
    private final IConfigManager configManager;

    private int priority;

    // Pattern storing logic
    private final AppEngInternalInventory patternInventory;
    private final List<IPatternDetails> patterns = new ArrayList<>();
    /**
     * Keeps track of the inputs of all the patterns. When blocking mode is enabled, if any of these is contained in the
     * target, the pattern won't be pushed. Always contains keys with the secondary component dropped.
     */
    private final Set<AEKey> patternInputs = new HashSet<>();
    // Pattern sending logic
    private final List<GenericStack> sendList = new ArrayList<>();
    /**
     * 保留仅为旧存档 NBT 兼容（原版字段），新逻辑无方向语义（推送目标固定为私有维度机器）。
     */
    private Direction sendDirection;
    // Stack returning logic
    private final PatternProviderReturnInventory returnInv;

    private YesNo redstoneState = YesNo.UNDECIDED;

    @Nullable
    private UnlockCraftingEvent unlockEvent;
    @Nullable
    private GenericStack unlockStack;

    @Nullable
    public FramePatternProviderLogic(IManagedGridNode mainNode, FramePatternProviderLogicHost host) {
        this(mainNode, host, 9);
    }

    public FramePatternProviderLogic(IManagedGridNode mainNode, FramePatternProviderLogicHost host,
            int patternInventorySize) {
        this.patternInventory = new AppEngInternalInventory(this, patternInventorySize);
        this.host = host;
        this.mainNode = mainNode
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(IGridTickable.class, new Ticker())
                .addService(ICraftingProvider.class, this);
        this.actionSource = new MachineSource(mainNode::getNode);

        configManager = IConfigManager.builder(this::configChanged)
                .registerSetting(Settings.BLOCKING_MODE, YesNo.NO)
                .registerSetting(Settings.PATTERN_ACCESS_TERMINAL, YesNo.YES)
                .registerSetting(Settings.LOCK_CRAFTING_MODE, LockCraftingMode.NONE)
                .build();

        this.returnInv = new PatternProviderReturnInventory(() -> {
            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
            this.host.saveChanges();
        });
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        this.host.saveChanges();

        ICraftingProvider.requestUpdate(mainNode);
    }

    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        this.configManager.writeToNBT(tag, registries);
        this.patternInventory.writeToNBT(tag, NBT_MEMORY_CARD_PATTERNS, registries);
        tag.putInt(NBT_PRIORITY, this.priority);
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
    }

    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        this.configManager.readFromNBT(tag, registries);
        this.patternInventory.readFromNBT(tag, NBT_MEMORY_CARD_PATTERNS, registries);
        this.priority = tag.getInt(NBT_PRIORITY);

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
            var legacy = new AppEngInternalInventory(patternInventory.size());
            legacy.readFromNBT(tag, "patternInventory", registries);
            for (int i = 0; i < legacy.size(); i++) {
                var stack = legacy.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    patternInventory.setItemDirect(i, stack);
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

    public IConfigManager getConfigManager() {
        return this.configManager;
    }

    public void saveChanges() {
        this.host.saveChanges();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        this.host.saveChanges();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        this.saveChanges();
        this.updatePatterns();
    }

    @Override
    public boolean isClientSide() {
        Level level = this.host.getBlockEntity().getLevel();
        return level == null || level.isClientSide();
    }

    public void updatePatterns() {
        patterns.clear();
        patternInputs.clear();

        for (var stack : this.patternInventory) {
            var details = PatternDetailsHelper.decodePattern(stack, this.host.getBlockEntity().getLevel());

            if (details != null) {
                patterns.add(details);

                for (var iinput : details.getInputs()) {
                    for (var inputCandidate : iinput.getPossibleInputs()) {
                        patternInputs.add(inputCandidate.what().dropSecondary());
                    }
                }
            }
        }

        ICraftingProvider.requestUpdate(mainNode);
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return this.patterns;
    }

    @Override
    public int getPatternPriority() {
        return this.priority;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!sendList.isEmpty() || !this.mainNode.isActive() || !this.patterns.contains(patternDetails)) {
            return false;
        }

        if (getCraftingLockedReason() != LockCraftingMode.NONE) {
            return false;
        }

        // 需求 8 改造：推送目标固定为私有维度机器（无 side 语义，机器本体单库存），
        // 不向周围方块发/收（原版此处遍历 getActiveSides 的相邻机器与适配器）。
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

    public void resetCraftingLock() {
        if (unlockEvent != null) {
            unlockEvent = null;
            unlockStack = null;
            saveChanges();
        }
    }

    private void onPushPatternSuccess(IPatternDetails pattern) {
        resetCraftingLock();

        var lockMode = configManager.getSetting(Settings.LOCK_CRAFTING_MODE);
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
    public LockCraftingMode getCraftingLockedReason() {
        var lockMode = configManager.getSetting(Settings.LOCK_CRAFTING_MODE);
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
    @Nullable
    public GenericStack getUnlockStack() {
        return unlockStack;
    }

    public boolean isBlocking() {
        return this.configManager.getSetting(Settings.BLOCKING_MODE) == YesNo.YES;
    }

    /**
     * 需求 8 改造：解析私有维度机器为推送目标（替代原版 findAdapter 的相邻方块缓存）。
     * <p>
     * 机器为 IItemHandler（物品通道），非物品通道的 AEKey 插入返回 0。
     * 宿主永不返回 null（客户端/机器不可达时返回空实现，0 槽天然空操作），故无需判空（S2）。
     *
     * @return 私有维度机器的推送目标包装
     */
    private PatternProviderTarget getMachineTarget() {
        IItemHandler handler = host.getMachineItemHandler();
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
     * 需求 8 改造：sendList 补发到私有维度机器（原版按 sendDirection 找相邻适配器）。
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
     * 需求 8 主动抽取：把私有维度机器中与样板输出匹配的物品抽取到返回库存。
     * <p>
     * 只抽取 processing 样板的输出（I2 修复）：熔炉等机器的输入/燃料槽与样板输出不匹配，
     * 不会被抽走，避免机器断粮。crafting 样板（supportsPushInputsToExternalInventory 为 false）
     * 无法推送到本机器，跳过其输出。逐槽 simulate 匹配（isSameAs）→ 转 AEItemKey 插入 returnInv
     * → 按实际插入量 MODULATE 抽取（抽取量 = min(机器槽存量, returnInv 可容纳量)）。
     * 抽取成功后唤醒网格 tick 把返回库存注入网络。
     *
     * @return true 表示至少抽取了部分物品
     */
    public boolean pullFromMachine() {
        IItemHandler handler = host.getMachineItemHandler();
        boolean didSomething = false;
        for (var stack : this.patternInventory) {
            var details = PatternDetailsHelper.decodePattern(stack, this.host.getBlockEntity().getLevel());
            if (details == null || !details.supportsPushInputsToExternalInventory()) {
                continue;
            }
            for (var output : details.getOutputs()) {
                if (!(output.what() instanceof AEItemKey outputKey)) {
                    continue;
                }
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack machineStack = handler.getStackInSlot(slot);
                    if (machineStack.isEmpty() || !outputKey.matches(machineStack)) {
                        continue;
                    }
                    ItemStack simulated = handler.extractItem(slot, machineStack.getCount(), true);
                    if (simulated.isEmpty()) {
                        continue;
                    }
                    long inserted = returnInv.insert(outputKey, simulated.getCount(), Actionable.MODULATE, actionSource);
                    if (inserted > 0) {
                        handler.extractItem(slot, (int) inserted, false);
                        didSomething = true;
                    }
                }
            }
        }
        if (didSomething) {
            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
            this.host.saveChanges();
        }
        return didSomething;
    }

    public InternalInventory getPatternInv() {
        return this.patternInventory;
    }

    public void onMainNodeStateChanged() {
        if (this.mainNode.isActive()) {
            this.mainNode.ifPresent((grid, node) -> {
                grid.getTickManager().alertDevice(node);
            });
        }
    }

    public void addDrops(List<ItemStack> drops) {
        for (var stack : this.patternInventory) {
            drops.add(stack);
        }

        for (var stack : this.sendList) {
            stack.what().addDrops(stack.amount(), drops, this.host.getBlockEntity().getLevel(),
                    this.host.getBlockEntity().getBlockPos());
        }

        this.returnInv.addDrops(drops, this.host.getBlockEntity().getLevel(), this.host.getBlockEntity().getBlockPos());
    }

    public void clearContent() {
        this.patternInventory.clear();
        this.sendList.clear();
        this.returnInv.clear();
    }

    public PatternProviderReturnInventory getReturnInv() {
        return this.returnInv;
    }

    public void exportSettings(DataComponentMap.Builder builder) {
        builder.set(AEComponents.EXPORTED_PATTERNS, patternInventory.toItemContainerContents());
    }

    public void importSettings(DataComponentMap input, @Nullable Player player) {
        var patterns = input.getOrDefault(AEComponents.EXPORTED_PATTERNS, ItemContainerContents.EMPTY);

        if (player != null && !player.level().isClientSide) {
            clearPatternInventory(player);

            var desiredPatterns = new AppEngInternalInventory(patternInventory.size());
            desiredPatterns.fromItemContainerContents(patterns);

            // Restore from blank patterns in the player inv
            var playerInv = player.getInventory();
            var blankPatternsAvailable = player.getAbilities().instabuild ? Integer.MAX_VALUE
                    : playerInv.countItem(AEItems.BLANK_PATTERN.asItem());
            var blankPatternsUsed = 0;
            for (int i = 0; i < desiredPatterns.size(); i++) {
                if (desiredPatterns.getStackInSlot(i).isEmpty()) {
                    continue;
                }

                // Don't restore junk
                var pattern = PatternDetailsHelper.decodePattern(desiredPatterns.getStackInSlot(i),
                        host.getBlockEntity().getLevel());
                if (pattern == null) {
                    continue; // Skip junk / broken recipes
                }

                // Keep track of how many blank patterns we need
                ++blankPatternsUsed;
                if (blankPatternsAvailable >= blankPatternsUsed) {
                    if (!patternInventory.addItems(pattern.getDefinition().toStack()).isEmpty()) {
                        AELog.warn("Failed to add pattern to pattern provider");
                        blankPatternsUsed--;
                    }
                }
            }

            // Deduct the used blank patterns
            if (blankPatternsUsed > 0 && !player.getAbilities().instabuild) {
                new PlayerInternalInventory(playerInv)
                        .removeItems(blankPatternsUsed, AEItems.BLANK_PATTERN.stack(), null);
            }

            // Warn about not being able to restore all patterns due to lack of blank patterns
            if (blankPatternsUsed > blankPatternsAvailable) {
                player.sendSystemMessage(
                        PlayerMessages.MissingBlankPatterns.text(blankPatternsUsed - blankPatternsAvailable));
            }
        }
    }

    // Converts all patterns in this provider to blank patterns and give them to the player
    private void clearPatternInventory(Player player) {
        // Just clear it for creative mode players
        if (player.getAbilities().instabuild) {
            for (int i = 0; i < patternInventory.size(); i++) {
                patternInventory.setItemDirect(i, ItemStack.EMPTY);
            }
            return;
        }

        var playerInv = player.getInventory();

        // Clear out any existing patterns and give them to the player
        var blankPatternCount = 0;
        for (int i = 0; i < patternInventory.size(); i++) {
            var pattern = patternInventory.getStackInSlot(i);
            // Auto-Clear encoded patterns to allow them to stack
            if (pattern.is(AEItems.CRAFTING_PATTERN.asItem())
                    || pattern.is(AEItems.PROCESSING_PATTERN.asItem())
                    || pattern.is(AEItems.SMITHING_TABLE_PATTERN.asItem())
                    || pattern.is(AEItems.STONECUTTING_PATTERN.asItem())
                    || pattern.is(AEItems.BLANK_PATTERN.asItem())) {
                blankPatternCount += pattern.getCount();
            } else {
                // Give back any non-blank-patterns individually
                playerInv.placeItemBackInInventory(pattern);
            }
            patternInventory.setItemDirect(i, ItemStack.EMPTY);
        }

        // Place back the removed blank patterns all at once
        if (blankPatternCount > 0) {
            playerInv.placeItemBackInInventory(AEItems.BLANK_PATTERN.stack(blankPatternCount), false);
        }
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
            if (!mainNode.isActive()) {
                return TickRateModulation.SLEEP;
            }
            boolean couldDoWork = doWork();
            return hasWorkToDo() ? couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER
                    : TickRateModulation.SLEEP;
        }
    }

    /**
     * @return Gets the name used to show this pattern provider in the
     *         {@link appeng.menu.implementations.PatternAccessTermMenu}.
     */
    public PatternContainerGroup getTerminalGroup() {
        // 需求 8 改造：机器在私有维度，无相邻机器分组（原版遍历 getActiveSides 的相邻机器），
        // 直接用宿主图标与名称。
        var hostIcon = this.host.getTerminalIcon();
        return new PatternContainerGroup(
                hostIcon,
                hostIcon.getDisplayName(),
                List.of());
    }

    public long getSortValue() {
        final BlockEntity te = this.host.getBlockEntity();
        return te.getBlockPos().getZ() << 24 ^ te.getBlockPos().getX() << 8 ^ te.getBlockPos().getY();
    }

    @Nullable
    public IGrid getGrid() {
        return mainNode.getGrid();
    }

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

    private void configChanged(IConfigManager manager, Setting<?> setting) {
        if (setting == Settings.LOCK_CRAFTING_MODE) {
            resetCraftingLock();
        } else {
            saveChanges();
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