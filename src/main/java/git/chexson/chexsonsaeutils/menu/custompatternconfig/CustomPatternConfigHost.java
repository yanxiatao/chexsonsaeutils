package git.chexson.chexsonsaeutils.menu.custompatternconfig;

import org.jetbrains.annotations.Nullable;

import appeng.api.parts.IPartHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;

/**
 * 定制样板编码 GUI 的菜单宿主（MenuHost）。
 * <p>
 * 动机：编码 GUI 直接编辑供应器样板槽中的原样板（原地转换，无样板副本），
 * 宿主只需定位到打开它的供应器（BlockPos + 维度 + 样板槽序号）并缓存
 * 会话状态（槽位映射/抽取槽位）。宿主由 {@link CustomPatternConfigLocator}
 * 在打开菜单时构造，菜单关闭即丢弃，无需持久化。
 * <p>
 * 供应器定位：每次使用时解析当前位置与面板方向，移除方块或面板后立即失效；
 * 定位失败返回 null，由菜单逻辑 Fail Fast（日志 + 清空状态）。
 */
public class CustomPatternConfigHost {

    private final BlockPos pos;
    private final ResourceKey<Level> dimension;
    private final int patternSlotIndex;
    @Nullable private final Direction partSide;

    /** 会话状态：槽位映射（与稀疏输入对齐，-1 = 未指定）与抽取槽位列表。 */
    private int[] slotMapping = new int[0];
    private int[] extractSlots = new int[0];

    /**
     * 会话状态：编码会话的「突破堆叠上限」开关，随样板写回组件
     * （true：指定槽位推送不受容器槽容量限制，超出部分排队等待写入）。
     */
    private boolean overflowStacks = false;

    /**
     * @param pos              供应器方块位置
     * @param dimension        供应器所在维度
     * @param patternSlotIndex 供应器样板槽序号（patternInventory 内索引）
     */
    public CustomPatternConfigHost(BlockPos pos, ResourceKey<Level> dimension, int patternSlotIndex) {
        this(pos, dimension, patternSlotIndex, null);
    }

    public CustomPatternConfigHost(BlockPos pos, ResourceKey<Level> dimension, int patternSlotIndex,
            @Nullable Direction partSide) {
        this.pos = pos;
        this.dimension = dimension;
        this.patternSlotIndex = patternSlotIndex;
        this.partSide = partSide;
    }

    @Nullable
    public Direction getPartSide() {
        return partSide;
    }

    /**
     * @return 供应器方块位置（日志/诊断用）
     */
    public BlockPos getPos() {
        return pos;
    }

    /**
     * @return 供应器样板槽序号（patternInventory 内索引）
     */
    public int getPatternSlotIndex() {
        return patternSlotIndex;
    }

    /**
     * 定位供应器方块或面板；每次解析当前位置，避免线缆未移除时仍引用已拆下的面板。
     * <p>
     * 维度处理：locator 携带供应器所在维度（dimension），但玩家可能从其他维度
     * 远程打开本菜单（AE2 终端/无线终端），此时玩家所在维度 ≠ 供应器维度，
     * 用玩家维度定位必然失败（provider missing → 全灰）。服务端按 dimension
     * 取 ServerLevel 定位；客户端只有玩家所在维度（菜单渲染副本，数据由负载
     * 驱动，定位失败无影响）。
     *
     * @param player 打开菜单的玩家（服务端/客户端均可）
     * @return 供应器 BE；方块缺失或类型不符时返回 null（调用方 Fail Fast）
     */
    @Nullable
    public CustomPatternProviderLogicHost getProvider(Player player) {
        Level level = player.level();
        if (!level.isClientSide() && player.getServer() != null) {
            level = player.getServer().getLevel(this.dimension);
        }
        if (level == null || !level.dimension().equals(this.dimension) || !level.hasChunkAt(this.pos)) {
            return null;
        }
        var blockEntity = level.getBlockEntity(this.pos);
        if (this.partSide != null) {
            return blockEntity instanceof IPartHost partHost ? resolvePartProvider(partHost, this.partSide) : null;
        }
        return blockEntity instanceof CustomPatternProviderLogicHost provider ? provider : null;
    }

    @Nullable
    static CustomPatternProviderLogicHost resolvePartProvider(IPartHost partHost, Direction side) {
        var part = partHost.getPart(side);
        return part instanceof CustomPatternProviderLogicHost provider ? provider : null;
    }

    public int[] getSlotMapping() {
        return slotMapping;
    }

    public void setSlotMapping(int[] slotMapping) {
        // clone：防御组件数组引用污染（调用方可能传入样板组件内的数组引用，
        // 直接存引用会让后续修改意外改动组件数据）
        this.slotMapping = slotMapping.clone();
    }

    public int[] getExtractSlots() {
        return extractSlots;
    }

    public void setExtractSlots(int[] extractSlots) {
        this.extractSlots = extractSlots;
    }

    /**
     * @return 编码会话的「突破堆叠上限」开关（随样板写回组件）
     */
    public boolean getOverflowStacks() {
        return overflowStacks;
    }

    /**
     * @param overflowStacks 编码会话的「突破堆叠上限」开关（随样板写回组件）
     */
    public void setOverflowStacks(boolean overflowStacks) {
        this.overflowStacks = overflowStacks;
    }
}