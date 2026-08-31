package git.chexson.chexsonsaeutils.helpers.mattermassprovider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.settings.TickRates;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.me.helpers.MachineSource;
import git.chexson.chexsonsaeutils.cell.MatterMassStore;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomProcessingPattern;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternDetails;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternItem;
import git.chexson.chexsonsaeutils.events.MatterMassMergeHandler;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogic;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassItem;

/**
 * 物质团供应器核心逻辑（继承 {@link CustomPatternProviderLogic} 复用样板供应基座）。
 * <p>
 * 与定制样板供应器的差异：
 * <ul>
 *   <li>样板槽放入处理样板（含定制/替换感知变体）时自动就地改写为物质团样板
 *       （{@link #convertProcessingPatterns}），上报输出替换为以第一个输出命名的物质团；</li>
 *   <li>无返回栏、无对外推送：{@link #pushPattern} 直接吞掉 CPU 推送的原料，
 *       全部装入对应物质团（外部存储按预分配 UUID）；</li>
 *   <li>产物交付经自有 Ticker：NETWORK 模式 insert 回网格（合成链接自动认领）；
 *       PLAYER 模式交付放置者背包后立即 {@code insertIntoCpus} 上报完成
 *       （离线/包满时阻塞重试）。</li>
 * </ul>
 * isBusy = 存在待交付物质团（串行化本机任务，防止 CPU 连续推送堆积）。
 * 特性门控关闭时：不转换、不吞料、不交付（注册项保留，世界兼容）。
 */
public class MatterMassPatternProviderLogic extends CustomPatternProviderLogic {

    private static final Logger LOG = LoggerFactory.getLogger(MatterMassPatternProviderLogic.class);
    private static final String NBT_PENDING_MASSES = "matterMassPending";

    /**
     * 待交付条目（原料已收下，等待返回网络/玩家/直接退回）。
     * <p>
     * 双 UUID 设计：{@code patternUuid} 为样板预分配 UUID，仅用于构造与上报输出
     * 一致的物质团匹配 key（经 insertIntoCpus 完成合成记账）；{@code contentsUuid}
     * 为本次合成独有，作为该张物质团内容物条目的键——同一样板的多次合成各产物
     * 内容物互相独立，避免共享条目导致的互相抽干/聚合丢失。
     * <p>
     * {@code returnInputs} 非 null 时为 PASS_THROUGH 模式：不建物质团，
     * 待退回网络的原料清单；物质团模式该字段为 null。
     */
    public record PendingMass(UUID patternUuid, @Nullable UUID contentsUuid, Component name,
            @Nullable List<GenericStack> returnInputs) {
    }

    private final MatterMassPatternProviderHost mmHost;
    private final IManagedGridNode mmMainNode;
    private final IActionSource mmActionSource;
    private final List<PendingMass> pendingMasses = new ArrayList<>();
    /** 转换重入保护：写回样板槽会触发库存变更回调再次进入 updatePatterns。 */
    private boolean converting;

    public MatterMassPatternProviderLogic(IManagedGridNode mainNode, MatterMassPatternProviderHost host,
            int patternInventorySize) {
        super(mainNode, host, patternInventorySize);
        this.mmHost = host;
        this.mmMainNode = mainNode;
        this.mmActionSource = new MachineSource(mainNode::getNode);
        // addService 为覆盖语义：用物质团交付 Ticker 替换父类链路的全部 Ticker
        mainNode.addService(IGridTickable.class, new MatterMassTicker());
    }

    @Override
    public void updatePatterns() {
        if (enabled() && !converting) {
            converting = true;
            try {
                convertProcessingPatterns();
            } finally {
                converting = false;
            }
        }
        super.updatePatterns();
    }

    /**
     * 仅上报物质团样板：转换后合法槽位均为物质团样板，此过滤兜底拦截
     * 未转换的合成型样板等，防止本机上报无法执行的配方。
     */
    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return super.getAvailablePatterns().stream()
                .filter(pattern -> pattern instanceof MatterMassPatternDetails)
                .toList();
    }

    /**
     * 吞料入团：CPU 推送的原料写入本次合成独有的内容物条目（新 contentsUuid），
     * 物质团入待交付队列。返回 true 即原料被消耗（进入物质团内容物）。
     */
    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!enabled() || isClientSide()) {
            return false;
        }
        if (!(patternDetails instanceof MatterMassPatternDetails massPattern)) {
            return false;
        }
        if (!pendingMasses.isEmpty()) {
            return false;
        }
        var contents = new ArrayList<GenericStack>();
        for (var counter : inputHolder) {
            for (var entry : counter) {
                if (entry.getLongValue() > 0) {
                    contents.add(new GenericStack(entry.getKey(), entry.getLongValue()));
                }
            }
        }
        if (contents.isEmpty()) {
            return false;
        }
        if (mmHost.getReturnMode() == ReturnMode.PASS_THROUGH) {
            // 不产物质团：原料待退回网络，合成以样板上报输出记账完成
            pendingMasses.add(new PendingMass(massPattern.getMassUuid(), null,
                    massPattern.firstOutputName(), contents));
        } else {
            var contentsUuid = UUID.randomUUID();
            MatterMassStore.global().append(contentsUuid, contents);
            pendingMasses.add(new PendingMass(massPattern.getMassUuid(), contentsUuid,
                    massPattern.firstOutputName(), null));
        }
        saveChanges();
        alertTick();
        return true;
    }

    @Override
    public boolean isBusy() {
        return !pendingMasses.isEmpty();
    }

    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag, registries);
        var list = new ListTag();
        for (var pending : pendingMasses) {
            var entry = new CompoundTag();
            entry.putUUID("patternUuid", pending.patternUuid());
            if (pending.contentsUuid() != null) {
                entry.putUUID("contentsUuid", pending.contentsUuid());
            }
            entry.putString("name", Component.Serializer.toJson(pending.name(), registries));
            if (pending.returnInputs() != null) {
                var inputsTag = new ListTag();
                var ops = registries.createSerializationContext(NbtOps.INSTANCE);
                for (var in : pending.returnInputs()) {
                    inputsTag.add(GenericStack.CODEC.encodeStart(ops, in).getOrThrow());
                }
                entry.put("returnInputs", inputsTag);
            }
            list.add(entry);
        }
        tag.put(NBT_PENDING_MASSES, list);
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.readFromNBT(tag, registries);
        pendingMasses.clear();
        var list = tag.getList(NBT_PENDING_MASSES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            var name = Component.Serializer.fromJson(entry.getString("name"), registries);
            // 旧存档（单 uuid 字段）兼容：patternUuid/contentsUuid 回落到同一值
            var patternUuid = entry.hasUUID("patternUuid") ? entry.getUUID("patternUuid")
                    : entry.getUUID("uuid");
            var contentsUuid = entry.hasUUID("contentsUuid") ? entry.getUUID("contentsUuid")
                    : (entry.contains("returnInputs") ? null : entry.getUUID("uuid"));
            List<GenericStack> returnInputs = null;
            if (entry.contains("returnInputs", Tag.TAG_LIST)) {
                returnInputs = new ArrayList<>();
                var ops = registries.createSerializationContext(NbtOps.INSTANCE);
                var inputsTag = entry.getList("returnInputs", Tag.TAG_COMPOUND);
                for (int j = 0; j < inputsTag.size(); j++) {
                    GenericStack.CODEC.parse(ops, inputsTag.getCompound(j)).result().ifPresent(returnInputs::add);
                }
            }
            pendingMasses.add(new PendingMass(patternUuid, contentsUuid,
                    name != null ? name : Component.literal("Matter Mass"), returnInputs));
        }
    }

    /** 机器被拆时待交付条目随掉落返还（原料不丢）。 */
    @Override
    public void addDrops(List<ItemStack> drops) {
        super.addDrops(drops);
        for (var pending : pendingMasses) {
            if (pending.returnInputs() != null) {
                for (var in : pending.returnInputs()) {
                    if (in.what() instanceof AEItemKey itemKey) {
                        long amount = in.amount();
                        while (amount > 0) {
                            int n = (int) Math.min(amount, itemKey.getMaxStackSize());
                            drops.add(itemKey.toStack(n));
                            amount -= n;
                        }
                    }
                }
            } else if (pending.contentsUuid() != null) {
                drops.add(MatterMassItem.createStack(pending.name(), pending.contentsUuid()));
            }
        }
        pendingMasses.clear();
    }

    /**
     * 样板槽自动转换：非物质团样板的处理样板（含 AEProcessingPattern 子类
     * 与定制样板）就地改写为物质团样板；原始稀疏输入/输出快照固化进组件
     * （替换规则等语义在转换瞬间定格），新分配物质团 UUID。
     */
    private void convertProcessingPatterns() {
        var blockEntity = mmHost.getBlockEntity();
        var level = blockEntity != null ? blockEntity.getLevel() : null;
        if (level == null) {
            return;
        }
        var inv = getPatternInv();
        for (int slot = 0; slot < inv.size(); slot++) {
            var stack = inv.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getItem() instanceof MatterMassPatternItem) {
                continue;
            }
            IPatternDetails details;
            try {
                details = PatternDetailsHelper.decodePattern(stack, level);
            } catch (RuntimeException e) {
                continue;
            }
            List<GenericStack> inputs = null;
            List<GenericStack> outputs = null;
            if (details instanceof AEProcessingPattern processingPattern) {
                inputs = processingPattern.getSparseInputs();
                outputs = processingPattern.getSparseOutputs();
            } else if (details instanceof CustomProcessingPattern customPattern) {
                inputs = customPattern.getSparseInputs();
                outputs = customPattern.getSparseOutputs();
            }
            if (inputs == null || outputs == null) {
                continue;
            }
            try {
                inv.setItemDirect(slot,
                        MatterMassPatternItem.createPatternStack(inputs, outputs, UUID.randomUUID()));
            } catch (RuntimeException e) {
                LOG.warn("物质团样板转换失败，保留原样板：slot={}", slot, e);
            }
        }
    }

    private void alertTick() {
        mmMainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    private boolean enabled() {
        return ChexsonsaeutilsCompatibilityConfig.boolValue(
                ChexsonsaeutilsCompatibilityConfig.MATTER_MASS_PATTERN_PROVIDER_ENABLED);
    }

    private class MatterMassTicker implements IGridTickable {
        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(TickRates.Interface, pendingMasses.isEmpty());
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!enabled() || pendingMasses.isEmpty()) {
                return TickRateModulation.IDLE;
            }
            var grid = node.getGrid();
            if (grid == null) {
                return TickRateModulation.SLOWER;
            }
            boolean progressed = deliverNext(grid);
            if (pendingMasses.isEmpty()) {
                saveChanges();
                return TickRateModulation.IDLE;
            }
            return progressed ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
        }
    }

    /** 交付队首条目；@return 本次是否有成功交付/出队 */
    private boolean deliverNext(IGrid grid) {
        var pending = pendingMasses.get(0);
        // 合成匹配 key：样板 UUID（与上报输出一致），用于 insertIntoCpus 记账完成
        var matchingKey = AEItemKey.of(MatterMassItem.createStack(pending.name(), pending.patternUuid()));
        if (matchingKey == null) {
            pendingMasses.remove(0);
            return true;
        }
        if (pending.returnInputs() != null) {
            return deliverInputsToNetwork(grid, pending, matchingKey);
        }
        // 实体物质团：内容物 UUID（每张团内容物独立）
        var stack = MatterMassItem.createStack(pending.name(), pending.contentsUuid());
        var massKey = AEItemKey.of(stack);
        if (massKey == null) {
            pendingMasses.remove(0);
            return true;
        }
        if (mmHost.getReturnMode() == ReturnMode.PLAYER) {
            return deliverToPlayer(grid, stack, matchingKey);
        }
        // AE 模式：实体团插入网络存储（玩家可取出），匹配 key 另行记账完成合成
        var storage = grid.getStorageService().getInventory();
        var inserted = storage.insert(massKey, 1, Actionable.MODULATE, mmActionSource);
        if (inserted <= 0) {
            return false;
        }
        if (grid.getCraftingService() instanceof appeng.me.service.CraftingService craftingService) {
            craftingService.insertIntoCpus(matchingKey, 1, Actionable.MODULATE);
        }
        pendingMasses.remove(0);
        return true;
    }

    /**
     * PASS_THROUGH 交付：把待退回原料先整体模拟（任一装不下则阻塞重试，不丢料），
     * 全部可容纳后实插回网络存储，随后以样板上报输出 insertIntoCpus 记账，
     * 保证该产出的合成正常算作完成。
     */
    private boolean deliverInputsToNetwork(IGrid grid, PendingMass pending, AEItemKey matchingKey) {
        var storage = grid.getStorageService().getInventory();
        for (var in : pending.returnInputs()) {
            if (storage.insert(in.what(), in.amount(), Actionable.SIMULATE, mmActionSource) < in.amount()) {
                return false;
            }
        }
        for (var in : pending.returnInputs()) {
            storage.insert(in.what(), in.amount(), Actionable.MODULATE, mmActionSource);
        }
        if (grid.getCraftingService() instanceof appeng.me.service.CraftingService craftingService) {
            craftingService.insertIntoCpus(matchingKey, 1, Actionable.MODULATE);
        }
        pendingMasses.remove(0);
        return true;
    }

    /**
     * 玩家模式交付：在线且背包装得下 → 入包后立即上报合成完成
     * （insertIntoCpus 走 finalOutput 链接记账，不产生幻影排放）；
     * 离线或包满 → 阻塞重试。
     */
    private boolean deliverToPlayer(IGrid grid, ItemStack stack, AEItemKey matchingKey) {
        var owner = mmHost.getOwnerUuid();
        if (owner == null) {
            return false;
        }
        var blockEntity = mmHost.getBlockEntity();
        if (blockEntity == null || !(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
            return false;
        }
        var player = serverLevel.getServer().getPlayerList().getPlayer(owner);
        if (player == null) {
            return false;
        }
        if (!inventoryHasSpace(player, stack)) {
            return false;
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        if (grid.getCraftingService() instanceof appeng.me.service.CraftingService craftingService) {
            craftingService.insertIntoCpus(matchingKey, 1, Actionable.MODULATE);
        }
        pendingMasses.remove(0);
        MatterMassMergeHandler.tryMerge(player);
        return true;
    }

    /** 背包（主背包+副手）是否有空间容纳该栈（阻塞检查用，不改动物品）。 */
    private static boolean inventoryHasSpace(net.minecraft.world.entity.player.Player player, ItemStack stack) {
        var inventory = player.getInventory();
        for (var slot : inventory.items) {
            if (slot.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(slot, stack)
                    && slot.getCount() + stack.getCount() <= slot.getMaxStackSize()) {
                return true;
            }
        }
        for (var slot : inventory.offhand) {
            if (slot.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(slot, stack)
                    && slot.getCount() + stack.getCount() <= slot.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }
}
