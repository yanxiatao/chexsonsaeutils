package git.chexson.chexsonsaeutils.menu.framepatternencoder;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.MenuTypeBuilder;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.crafting.framepattern.FrameProcessingPattern;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigConverter;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigConverterImpl;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigHost;
import git.chexson.chexsonsaeutils.network.framepatternencoder.FramePatternEncoderUpdatePayload;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 框架样板编码菜单（原地编辑供应器原样板）。
 * <p>
 * 动机：用户要求修改样板时直接自动修改供应器中的对应样板（不复制样板生成
 * 框架样板、不手动取出重新放置）。本菜单不再有输入槽/输出槽（无样板副本），
 * 打开时从供应器样板槽读样板解码显示，修改槽位实时写回供应器原样板——
 * 处理样板原地转换为框架样板（setItemDirect 替换 + updatePatterns 刷新），
 * 关闭即生效。
 * <p>
 * 数据流：客户端槽位输入经 {@code FramePatternSlotChangePacket} 到达 update()，
 * 抽取槽位经 set_extract_slots 客户端动作到达 setExtractSlotsFromClient；
 * 两者都写回供应器样板槽并回推 {@code FramePatternEncoderUpdatePayload} 回显。
 */
public class FramePatternEncoderMenu extends AEBaseMenu {

    private static final Logger LOGGER = LoggerFactory.getLogger(FramePatternEncoderMenu.class);

    public static final MenuType<FramePatternEncoderMenu> TYPE = MenuTypeBuilder
            .create(FramePatternEncoderMenu::new, FramePatternConfigHost.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "frame_pattern_encoder"));

    private final FramePatternConfigHost host;
    private final FramePatternConfigConverter converter = new FramePatternConfigConverterImpl();

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

    /** 服务端缓存：当前样板的稀疏输入列表（sendUpdate 时直接使用）。 */
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
        registerClientAction("set_extract_slots", String.class, this::setExtractSlotsFromClient);

        // 初始解码：从供应器样板槽读样板显示。注意此处 decodeInputAndEncode 内的
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
     * 从供应器样板槽读当前样板并解码显示（不写回）。
     * 处理样板 → 全 -1 映射显示；框架样板 → 显示组件内现有映射/抽取槽位；
     * 其他/空 → 空配置。
     */
    private void decodeInputAndEncode() {
        var stack = getProviderPatternStack();
        if (stack == null || stack.isEmpty()) {
            resetState();
            return;
        }
        var details = PatternDetailsHelper.decodePattern(stack, getPlayer().level());
        if (details instanceof AEProcessingPattern processingPattern) {
            this.serverSparseInputs = processingPattern.getSparseInputs();
            // 打开时仅显示全 -1（处理样板尚无映射），不写回——首次修改时由 update 转换
            var mapping = new int[this.serverSparseInputs.size()];
            Arrays.fill(mapping, -1);
            this.host.setSlotMapping(mapping);
            sendUpdate();
        } else if (details instanceof FrameProcessingPattern framePattern) {
            // 框架样板：显示组件内已有配置（原地编辑的当前状态）
            this.serverSparseInputs = framePattern.getSparseInputs();
            this.host.setSlotMapping(framePattern.getSlotMapping());
            this.host.setExtractSlots(framePattern.getExtractSlots());
            sendUpdate();
        } else {
            resetState();
        }
    }

    /** 清空会话状态并推送空配置到客户端。 */
    private void resetState() {
        this.host.setSlotMapping(new int[0]);
        this.host.setExtractSlots(new int[0]);
        this.serverSparseInputs = List.of();
        sendUpdate();
    }

    /**
     * 读供应器样板槽中的当前样板。
     *
     * @return 样板物品；供应器缺失（方块被拆）或槽位越界时返回 null（Fail Fast）
     */
    private ItemStack getProviderPatternStack() {
        var provider = this.host.getProvider(getPlayer().level());
        if (provider == null) {
            // Fail Fast：供应器方块缺失（被拆/卸载），无法继续编辑
            LOGGER.error("Frame pattern encoder: provider block entity missing at {}",
                    this.host.getPos());
            return null;
        }
        var inv = provider.getLogic().getPatternInv();
        if (this.host.getPatternSlotIndex() < 0 || this.host.getPatternSlotIndex() >= inv.size()) {
            LOGGER.error("Frame pattern encoder: pattern slot index {} out of range (size {})",
                    this.host.getPatternSlotIndex(), inv.size());
            return null;
        }
        return inv.getStackInSlot(this.host.getPatternSlotIndex());
    }

    /**
     * 写回供应器样板槽：处理样板 → 框架样板原地转换（setItemDirect 替换），
     * 框架样板 → 按当前映射/抽取槽位重编码组件；随后触发供应器刷新
     * （updatePatterns 重建输出物品集合，让网格感知样板变化）。
     */
    private void writeBack() {
        var provider = this.host.getProvider(getPlayer().level());
        if (provider == null) {
            // Fail Fast：供应器方块缺失，无法写回
            LOGGER.error("Frame pattern encoder: provider block entity missing, cannot write back");
            return;
        }
        var inv = provider.getLogic().getPatternInv();
        var index = this.host.getPatternSlotIndex();
        var stack = inv.getStackInSlot(index);
        if (stack.isEmpty()) {
            resetState();
            return;
        }
        var details = PatternDetailsHelper.decodePattern(stack, getPlayer().level());
        ItemStack newStack;
        if (details instanceof AEProcessingPattern) {
            // 处理样板 → 框架样板：convertFromProcessingPattern 生成携带
            // ENCODED_FRAME_PATTERN 组件的 FramePatternItem（原地转换，无副本）
            try {
                newStack = this.converter.encodeFramePattern(
                        stack, this.host.getSlotMapping(), this.host.getExtractSlots());
            } catch (IllegalArgumentException e) {
                // 映射长度与稀疏输入不符（理论上不会发生，防御性清理）
                resetState();
                return;
            }
        } else if (details instanceof FrameProcessingPattern framePattern) {
            // 框架样板：按当前映射/抽取槽位重编码组件
            newStack = new ItemStack(ChexsonsaeutilsContent.FRAME_PATTERN_ITEM.get());
            try {
                FrameProcessingPattern.encode(newStack, framePattern.getSparseInputs(),
                        framePattern.getSparseOutputs(), this.host.getSlotMapping(),
                        this.host.getExtractSlots());
            } catch (IllegalArgumentException e) {
                // 映射长度与稀疏输入不符（组件损坏场景，防御性清理）
                resetState();
                return;
            }
        } else {
            resetState();
            return;
        }
        inv.setItemDirect(index, newStack);
        // 直接改库存不会自动触发供应器刷新，手动重建输出物品集合（服务端线程安全：
        // 菜单与供应器同在服务端，updatePatterns 由本菜单调用）
        provider.getLogic().updatePatterns();
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
     * 服务端入口（FramePatternSlotChangePacket 到达）：把某个稀疏输入映射到机器槽位，
     * 写回供应器原样板并回推客户端。
     */
    public void update(AEKey key, int slot) {
        if (slot < -1 || slot > MAX_MACHINE_SLOT) {
            // I3 修复：拒绝越界机器槽位
            return;
        }
        var stack = getProviderPatternStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        var details = PatternDetailsHelper.decodePattern(stack, getPlayer().level());
        int[] mapping;
        List<GenericStack> sparse;
        if (details instanceof AEProcessingPattern processingPattern) {
            // 首次修改：处理样板尚无映射，从全 -1 起步（本次修改触发原地转换）
            sparse = processingPattern.getSparseInputs();
            mapping = new int[sparse.size()];
            Arrays.fill(mapping, -1);
        } else if (details instanceof FrameProcessingPattern framePattern) {
            sparse = framePattern.getSparseInputs();
            // clone：不直接修改输出槽物品组件内的数组引用
            mapping = framePattern.getSlotMapping().clone();
            // 组件损坏防御：映射长度与稀疏输入数不符时忽略本次修改（否则循环越界 AIOOBE）
            if (mapping.length != sparse.size()) {
                return;
            }
        } else {
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
        writeBack();
        // B3 修复：回推最新映射与输出到客户端（防止客户端本地字段过期被回显覆盖）
        sendUpdate();
    }

    /** 客户端动作：设置抽取槽位列表（逗号分隔 CSV），写回供应器原样板。 */
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
            writeBack();
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