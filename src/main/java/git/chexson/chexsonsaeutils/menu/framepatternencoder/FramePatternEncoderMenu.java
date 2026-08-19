package git.chexson.chexsonsaeutils.menu.framepatternencoder;

import java.util.Arrays;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.OutputSlot;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.crafting.framepattern.FrameProcessingPattern;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigConverter;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigConverterImpl;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigHost;
import git.chexson.chexsonsaeutils.network.framepatternencoder.FramePatternEncoderUpdatePayload;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 框架样板编码菜单（advancedae AdvPatternEncoderMenu 的移植改造）。
 * <p>
 * 动机：原 FramePatternConfigMenu 的槽位映射经客户端动作（set_slot_mapping）回传，
 * 交互与 advancedae 的实时编码 UI 不一致；本菜单照 advancedae 改为
 * 「槽位变更 → 服务端 update() → 重新编码输出槽 → 回推最新数据」的实时链路，
 * 并保留抽取槽位（本 mod 强制抽取功能）的客户端动作。
 * <p>
 * 数据流：输入槽（槽 0）放入处理样板或框架样板 → decodeInputAndEncode 解码并
 * 编码输出槽（槽 1）；客户端槽位输入经 {@code FramePatternSlotChangePacket} 到达
 * update()；抽取槽位经 set_extract_slots 客户端动作到达 setExtractSlotsFromClient。
 */
public class FramePatternEncoderMenu extends AEBaseMenu {

    public static final MenuType<FramePatternEncoderMenu> TYPE = MenuTypeBuilder
            .create(FramePatternEncoderMenu::new, FramePatternConfigHost.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "frame_pattern_encoder"));

    /** 宿主库存变更回调（由宿主转发，见 {@link FramePatternConfigHost#setInventoryChangedHandler}）。 */
    private final FramePatternConfigHost host;
    private final FramePatternConfigConverter converter = new FramePatternConfigConverterImpl();
    private final RestrictedInputSlot inputSlot;
    private final OutputSlot outputSlot;

    /** 机器槽位上限（9x9 = 81 槽，编号 0-80，-1 = 未指定）。 */
    private static final int MAX_MACHINE_SLOT = 80;
    /** 抽取槽位列表长度上限（防止客户端伪造超大数组）。 */
    private static final int MAX_EXTRACT_SLOTS = 64;

    /**
     * 初始更新挂起标志：构造器内不直接 sendUpdate，因为此时 OpenScreenPacket
     * 尚未入队，负载会先于菜单打开包到达客户端而被丢弃；由 Screen.init 的
     * onUpdateRequested 或首个 broadcastChanges 周期发送（此时包序已保证）。
     */
    private boolean pendingInitialUpdate = true;

    /** 服务端缓存：当前输入样板的稀疏输入列表（sendUpdate 时直接使用）。 */
    private List<GenericStack> serverSparseInputs = List.of();

    /** 客户端同步字段：稀疏输入列表、槽位映射、抽取槽位（由更新负载填充）。 */
    private List<GenericStack> sparseInputs = List.of();
    private int[] slotMapping = new int[0];
    private int[] extractSlots = new int[0];

    public FramePatternEncoderMenu(int id, Inventory playerInventory, FramePatternConfigHost host) {
        // AEBaseMenu 要求宿主为 BlockEntity/IPart/ItemMenuHost，此菜单宿主为瞬态对象，
        // 传 null 绕过校验（本菜单不使用 IActionHost 功能）。
        super(TYPE, id, playerInventory, null);
        this.host = host;
        this.createPlayerInventorySlots(playerInventory);
        this.addSlot(this.inputSlot = new RestrictedInputSlot(
                RestrictedInputSlot.PlacableItemType.ENCODED_PATTERN, host.getInventory(), 0
        ), SlotSemantics.ENCODED_PATTERN);
        this.addSlot(this.outputSlot = new OutputSlot(host.getInventory(), 1, null), SlotSemantics.MACHINE_OUTPUT);

        this.host.setInventoryChangedHandler(this::onHostInventoryChanged);
        registerClientAction("set_extract_slots", String.class, this::setExtractSlotsFromClient);

        // 初始解码：输入槽已由 locator 放入样板副本。注意此处 decodeInputAndEncode 内的
        // sendUpdate 负载必被客户端丢弃——OpenScreenPacket 尚未入队，负载先到且 containerId
        // 校验失败；由 Screen.init 的 onUpdateRequested 或首个 broadcastChanges 周期兜底重发
        // （此时包序已保证 OpenScreenPacket 先到）。
        decodeInputAndEncode();
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (this.pendingInitialUpdate) {
            this.pendingInitialUpdate = false;
            sendUpdate();
        }
    }

    /**
     * 客户端请求立即同步（Screen.init 调用）：此时 OpenScreenPacket 已先到达，
     * 发送的负载不会被 containerId 校验丢弃。pendingInitialUpdate 标志保证
     * resize 等重复 init 不会重新解码（避免重置玩家已配置的映射）。
     */
    public void onUpdateRequested() {
        if (this.pendingInitialUpdate) {
            this.pendingInitialUpdate = false;
            decodeInputAndEncode();
        }
    }

    /**
     * 宿主库存变更：槽 0（输入）变更时重新解码并重置映射；
     * 槽 1（输出）被取走时清空输入与映射（照 advancedae onChangeInventory）。
     */
    private void onHostInventoryChanged(InternalInventory inv, int slot) {
        if (inv != this.host.getInventory()) {
            return;
        }
        if (slot == 0) {
            decodeInputAndEncode();
        } else if (slot == 1 && !this.outputSlot.hasItem()) {
            this.host.setSlotMapping(new int[0]);
            this.host.setExtractSlots(new int[0]);
            this.inputSlot.set(ItemStack.EMPTY);
            this.serverSparseInputs = List.of();
            sendUpdate();
        }
    }

    /**
     * 重新解码输入样板并编码输出槽，推送最新数据到客户端。
     * 处理样板 → 全 -1 映射 + 转换输出；框架样板 → 输出 = 输入副本 + 组件内映射；
     * 其他/空 → 清空状态。
     */
    private void decodeInputAndEncode() {
        var input = this.inputSlot.getItem();
        if (input.isEmpty()) {
            resetState();
            return;
        }
        var details = PatternDetailsHelper.decodePattern(input, getPlayer().level());
        if (details instanceof AEProcessingPattern processingPattern) {
            this.serverSparseInputs = processingPattern.getSparseInputs();
            // I2 修复：输入样板（内容）变更时旧映射无条件作废——即使新旧样板稀疏输入数
            // 相同，槽位映射也可能不同（A 的槽 2 对应 B 的槽 0），必须重置为全 -1
            var mapping = new int[this.serverSparseInputs.size()];
            Arrays.fill(mapping, -1);
            this.host.setSlotMapping(mapping);
            encodeOutput();
            sendUpdate();
        } else if (details instanceof FrameProcessingPattern framePattern) {
            // 框架样板：输出 = 输入副本，映射/抽取槽位沿用组件内已有配置
            this.outputSlot.set(input.copy());
            this.serverSparseInputs = framePattern.getSparseInputs();
            this.host.setSlotMapping(framePattern.getSlotMapping());
            this.host.setExtractSlots(framePattern.getExtractSlots());
            sendUpdate();
        } else {
            resetState();
        }
    }

    /** 清空映射/抽取槽位/输出槽并推送空状态到客户端。 */
    private void resetState() {
        this.host.setSlotMapping(new int[0]);
        this.host.setExtractSlots(new int[0]);
        this.outputSlot.set(ItemStack.EMPTY);
        this.serverSparseInputs = List.of();
        sendUpdate();
    }

    /** 用当前槽位映射与抽取槽位编码输出框架样板；映射非法时清空输出。 */
    private void encodeOutput() {
        var input = this.inputSlot.getItem();
        if (input.isEmpty()) {
            this.outputSlot.set(ItemStack.EMPTY);
            return;
        }
        var details = PatternDetailsHelper.decodePattern(input, getPlayer().level());
        if (details instanceof FrameProcessingPattern framePattern) {
            // 框架样板输入分支：convertFromProcessingPattern 要求输入带
            // ENCODED_PROCESSING_PATTERN 组件（FramePatternItem.java:54-57），框架样板只有
            // ENCODED_FRAME_PATTERN 组件会抛 IllegalArgumentException；照 advancedae update()
            // 从输出槽读数据重编码的语义，此处从输入组件读稀疏输入/输出与当前映射，
            // 用 FrameProcessingPattern.encode 重编码输出槽（映射/抽取槽位实时生效）。
            var stack = new ItemStack(ChexsonsaeutilsContent.FRAME_PATTERN_ITEM.get());
            try {
                FrameProcessingPattern.encode(stack, framePattern.getSparseInputs(),
                        framePattern.getSparseOutputs(), this.host.getSlotMapping(),
                        this.host.getExtractSlots());
                this.outputSlot.set(stack);
            } catch (IllegalArgumentException e) {
                // 映射长度与稀疏输入不符（组件损坏场景，防御性清理）
                this.outputSlot.set(ItemStack.EMPTY);
            }
            return;
        }
        try {
            this.outputSlot.set(this.converter.encodeFramePattern(
                    input, this.host.getSlotMapping(), this.host.getExtractSlots()
            ));
        } catch (IllegalArgumentException e) {
            // 映射长度与稀疏输入不符（理论上不会发生，防御性清理）
            this.outputSlot.set(ItemStack.EMPTY);
        }
    }

    /** 推送最新稀疏输入/映射/抽取槽位到客户端。 */
    private void sendUpdate() {
        if (getPlayer() instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new FramePatternEncoderUpdatePayload(
                    containerId,
                    this.serverSparseInputs,
                    this.host.getSlotMapping(),
                    this.host.getExtractSlots()
            ));
        }
    }

    /**
     * 服务端入口（FramePatternSlotChangePacket 到达）：把某个稀疏输入映射到机器槽位。
     * 从输出槽框架样板读取当前映射，更新后重新编码输出并回推客户端。
     */
    public void update(AEKey key, int slot) {
        if (slot < -1 || slot > MAX_MACHINE_SLOT) {
            // I3 修复：拒绝越界机器槽位
            return;
        }
        if (!this.outputSlot.hasItem()) {
            encodeOutput();
        }
        var output = this.outputSlot.getItem();
        if (output.isEmpty()) {
            return;
        }
        var details = PatternDetailsHelper.decodePattern(output, getPlayer().level());
        if (!(details instanceof FrameProcessingPattern framePattern)) {
            return;
        }
        // clone：不直接修改输出槽物品组件内的数组引用
        var mapping = framePattern.getSlotMapping().clone();
        var sparse = framePattern.getSparseInputs();
        // 组件损坏防御：映射长度与稀疏输入数不符时忽略本次修改（否则循环越界 AIOOBE）
        if (mapping.length != sparse.size()) {
            return;
        }
        // key 匹配依赖 AE2 编码时 condense 去重：处理样板稀疏输入无重复 key，
        // 首个匹配即唯一对应（FrameProcessingPattern 的稀疏输入继承自处理样板）
        for (int i = 0; i < sparse.size(); i++) {
            var input = sparse.get(i);
            if (input != null && input.what().equals(key)) {
                mapping[i] = slot;
                break;
            }
        }
        this.host.setSlotMapping(mapping);
        encodeOutput();
        // B3 修复：回推最新映射与输出到客户端（防止客户端本地字段过期被回显覆盖）
        sendUpdate();
    }

    /** 客户端动作：设置抽取槽位列表（逗号分隔 CSV）。 */
    private void setExtractSlotsFromClient(String csv) {
        try {
            int[] slots;
            if (csv.isBlank()) {
                slots = new int[0];
            } else {
                var parts = csv.split(",");
                if (parts.length > MAX_EXTRACT_SLOTS) {
                    // I3 修复：拒绝超长列表
                    return;
                }
                slots = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    int slot = Integer.parseInt(parts[i].trim());
                    if (slot < 0 || slot > MAX_MACHINE_SLOT) {
                        // I3 修复：拒绝越界机器槽位
                        return;
                    }
                    slots[i] = slot;
                }
            }
            this.host.setExtractSlots(slots);
            encodeOutput();
            // B3 修复：回推最新抽取槽位与输出到客户端
            sendUpdate();
        } catch (NumberFormatException ignored) {
            // 非法 CSV：忽略本次修改（确认编码时沿用上次合法值）
        }
    }

    /**
     * 客户端数据入口：更新负载到达时写入同步字段（Screen 在 updateBeforeRender 读取）。
     */
    public void updateFromServer(List<GenericStack> sparseInputs, int[] slotMapping, int[] extractSlots) {
        this.sparseInputs = sparseInputs != null ? sparseInputs : List.of();
        this.slotMapping = slotMapping != null ? slotMapping : new int[0];
        this.extractSlots = extractSlots != null ? extractSlots : new int[0];
    }

    public List<GenericStack> getSparseInputs() {
        return sparseInputs;
    }

    public int[] getSlotMapping() {
        return slotMapping;
    }

    public int[] getExtractSlots() {
        return extractSlots;
    }

    /** 客户端入口：设置抽取槽位列表。 */
    public void setExtractSlots(int[] slots) {
        sendClientAction("set_extract_slots",
                Arrays.stream(slots).mapToObj(Integer::toString).reduce((a, b) -> a + "," + b).orElse(""));
    }
}