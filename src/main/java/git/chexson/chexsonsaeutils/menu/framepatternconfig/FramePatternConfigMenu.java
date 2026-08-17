package git.chexson.chexsonsaeutils.menu.framepatternconfig;

import java.util.Arrays;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.GenericStack;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.OutputSlot;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.network.framepatternconfig.FramePatternConfigUpdatePayload;

/**
 * 框架样板配置菜单。
 * <p>
 * 槽位构成：槽 0 = 输入处理样板（ENCODED_PATTERN，仅可放入 AE2 编码样板）、
 * 槽 1 = 输出框架样板（MACHINE_OUTPUT，转换结果落位）+ 玩家背包槽位。
 * <p>
 * 服务端行为：输入槽变更时重新解码稀疏输入并自动编码输出槽
 * （输入变更即重置槽位映射为全 -1）；客户端动作：
 * set_slot_mapping（"index:value"）、set_extract_slots（逗号分隔 CSV）、
 * confirm（用当前映射重新编码）。变长的稀疏输入/映射数据经
 * {@link FramePatternConfigUpdatePayload} 推送到客户端。
 * <p>
 * 打开方式：FramePatternProviderScreen 在配置模式下点击处理样板槽位 →
 * FramePatternProviderMenu.openConfigForSlot → MenuOpener.open
 * （locator 为 {@link FramePatternConfigLocator}）。
 */
public class FramePatternConfigMenu extends AEBaseMenu {

    public static final MenuType<FramePatternConfigMenu> TYPE = MenuTypeBuilder
            .create(FramePatternConfigMenu::new, FramePatternConfigHost.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "frame_pattern_config"));

    /** 宿主库存变更回调（由宿主转发，见 {@link FramePatternConfigHost#setInventoryChangedHandler}）。 */
    public interface InventoryChangedHandler {
        void handleChange(InternalInventory inv, int slot);
    }

    private final FramePatternConfigHost host;
    private final FramePatternConfigConverter converter = new FramePatternConfigConverterImpl();
    private final RestrictedInputSlot inputSlot;
    private final OutputSlot outputSlot;

    /** 机器槽位上限（9x9 = 81 槽，编号 0-80，-1 = 未指定）。 */
    private static final int MAX_MACHINE_SLOT = 80;
    /** 抽取槽位列表长度上限（防止客户端伪造超大数组）。 */
    private static final int MAX_EXTRACT_SLOTS = 64;

    /**
     * 初始更新挂起标志（B2 修复）：构造器内不直接 sendUpdate，因为此时
     * OpenScreenPacket 尚未入队，负载会先于菜单打开包到达客户端而被丢弃；
     * 改为首个 broadcastChanges 周期发送（此时包序保证 OpenScreenPacket 已先到）。
     */
    private boolean pendingInitialUpdate = true;

    /** 服务端缓存：当前输入样板的稀疏输入列表（sendUpdate 时直接使用）。 */
    private List<GenericStack> serverSparseInputs = List.of();

    /** 客户端同步字段：稀疏输入列表、槽位映射、抽取槽位（由更新负载填充）。 */
    private List<GenericStack> sparseInputs = List.of();
    private int[] slotMapping = new int[0];
    private int[] extractSlots = new int[0];

    public FramePatternConfigMenu(int id, Inventory playerInventory, FramePatternConfigHost host) {
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
        registerClientAction("set_slot_mapping", String.class, this::setSlotMappingFromClient);
        registerClientAction("set_extract_slots", String.class, this::setExtractSlotsFromClient);
        registerClientAction("confirm", this::encodeOutput);

        // 初始解码：输入槽已由 locator 放入样板副本（sendUpdate 被 pendingInitialUpdate 挂起）
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
     * 宿主库存变更：槽 0（输入）变更时重新解码并重置映射；
     * 槽 1（输出）被取走时不自动重新编码（玩家点击确认按钮再生成）。
     */
    private void onHostInventoryChanged(InternalInventory inv, int slot) {
        if (inv != this.host.getInventory() || slot != 0) {
            return;
        }
        decodeInputAndEncode();
    }

    /** 重新解码输入样板，重置映射并推送最新数据到客户端。 */
    private void decodeInputAndEncode() {
        var input = this.inputSlot.getItem();
        if (input.isEmpty()) {
            this.host.setSlotMapping(new int[0]);
            this.host.setExtractSlots(new int[0]);
            this.outputSlot.set(ItemStack.EMPTY);
            this.serverSparseInputs = List.of();
            sendUpdate();
            return;
        }
        var sparse = this.converter.decodeSparseInputs(input, getPlayer().level());
        if (sparse == null) {
            // 输入不是处理样板（例如框架样板自身）：清空映射与输出
            this.host.setSlotMapping(new int[0]);
            this.host.setExtractSlots(new int[0]);
            this.outputSlot.set(ItemStack.EMPTY);
            this.serverSparseInputs = List.of();
            sendUpdate();
            return;
        }
        this.serverSparseInputs = sparse;
        // I2 修复：输入样板（内容）变更时旧映射无条件作废——即使新旧样板稀疏输入数
        // 相同，槽位映射也可能不同（A 的槽 2 对应 B 的槽 0），必须重置为全 -1
        var mapping = new int[sparse.size()];
        Arrays.fill(mapping, -1);
        this.host.setSlotMapping(mapping);
        encodeOutput();
        sendUpdate();
    }

    /** 用当前槽位映射与抽取槽位编码输出框架样板；映射非法时清空输出。 */
    private void encodeOutput() {
        var input = this.inputSlot.getItem();
        if (input.isEmpty()) {
            this.outputSlot.set(ItemStack.EMPTY);
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
            PacketDistributor.sendToPlayer(serverPlayer, new FramePatternConfigUpdatePayload(
                    containerId,
                    this.serverSparseInputs,
                    this.host.getSlotMapping(),
                    this.host.getExtractSlots()
            ));
        }
    }

    /** 客户端动作：设置某个稀疏输入的槽位映射（数据格式 "index:value"）。 */
    private void setSlotMappingFromClient(String data) {
        var parts = data.split(":");
        if (parts.length != 2) {
            return;
        }
        try {
            int index = Integer.parseInt(parts[0]);
            int value = Integer.parseInt(parts[1]);
            if (value < -1 || value > MAX_MACHINE_SLOT) {
                // I3 修复：拒绝越界机器槽位
                return;
            }
            var mapping = this.host.getSlotMapping();
            if (index < 0 || index >= mapping.length) {
                return;
            }
            mapping[index] = value;
            encodeOutput();
            // B3 修复：回推最新映射与输出到客户端（防止客户端本地字段过期被回显覆盖）
            sendUpdate();
        } catch (NumberFormatException ignored) {
            // 客户端输入非法：忽略本次修改（fail-fast 由客户端校验拦截，此处仅防御）
        }
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

    /** 客户端入口：设置槽位映射（index 对应稀疏输入序号，value = 机器槽位，-1 = 未指定）。 */
    public void setSlotMapping(int index, int value) {
        sendClientAction("set_slot_mapping", index + ":" + value);
    }

    /** 客户端入口：设置抽取槽位列表。 */
    public void setExtractSlots(int[] slots) {
        sendClientAction("set_extract_slots",
                Arrays.stream(slots).mapToObj(Integer::toString).reduce((a, b) -> a + "," + b).orElse(""));
    }

    /** 客户端入口：确认（用当前映射重新编码输出槽）。 */
    public void confirm() {
        sendClientAction("confirm");
    }
}