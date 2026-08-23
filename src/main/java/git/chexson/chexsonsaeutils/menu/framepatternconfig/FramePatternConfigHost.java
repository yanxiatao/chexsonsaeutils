package git.chexson.chexsonsaeutils.menu.framepatternconfig;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogicHost;

/**
 * 框架样板编码 GUI 的菜单宿主（MenuHost）。
 * <p>
 * 动机：编码 GUI 直接编辑供应器样板槽中的原样板（原地转换，无样板副本），
 * 宿主只需定位到打开它的供应器（BlockPos + 维度 + 样板槽序号）并缓存
 * 会话状态（槽位映射/抽取槽位）。宿主由 {@link FramePatternConfigLocator}
 * 在打开菜单时构造，菜单关闭即丢弃，无需持久化。
 * <p>
 * 供应器定位：延迟到首次使用时（getProvider），BE 被拆后重新定位；
 * 定位失败返回 null，由菜单逻辑 Fail Fast（日志 + 清空状态）。
 */
public class FramePatternConfigHost {

    private final BlockPos pos;
    private final ResourceKey<Level> dimension;
    private final int patternSlotIndex;

    /**
     * 来源供应器类型：true = 定制样板供应器（面板），false = 框架样板供应器（方块）。
     * 动机：编码 GUI 标题需按来源供应器显示，Screen 据此选择布局 json。
     */
    private final boolean fromCustomProvider;

    /** 缓存的供应器宿主引用（延迟定位；isRemoved 后重新定位）。 */
    private FramePatternProviderLogicHost provider;

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
     * @param fromCustomProvider 来源供应器类型（true = 定制样板供应器）
     */
    public FramePatternConfigHost(BlockPos pos, ResourceKey<Level> dimension, int patternSlotIndex,
            boolean fromCustomProvider) {
        this.pos = pos;
        this.dimension = dimension;
        this.patternSlotIndex = patternSlotIndex;
        this.fromCustomProvider = fromCustomProvider;
    }

    /**
     * @return 来源供应器类型（true = 定制样板供应器，标题/布局据此区分）
     */
    public boolean isFromCustomProvider() {
        return fromCustomProvider;
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
     * 定位供应器方块实体（延迟定位 + 缓存；BE 移除后重新定位）。
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
    public FramePatternProviderLogicHost getProvider(Player player) {
        if (this.provider == null || this.provider.getBlockEntity() == null
                || this.provider.getBlockEntity().isRemoved()) {
            Level level = player.level();
            if (!level.isClientSide() && player.getServer() != null) {
                level = player.getServer().getLevel(this.dimension);
            }
            if (level != null && level.getBlockEntity(this.pos) instanceof FramePatternProviderLogicHost be) {
                this.provider = be;
            } else {
                this.provider = null;
            }
        }
        return this.provider;
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