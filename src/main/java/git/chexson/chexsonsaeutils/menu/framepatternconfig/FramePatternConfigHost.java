package git.chexson.chexsonsaeutils.menu.framepatternconfig;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;

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

    /** 缓存的供应器 BE 引用（延迟定位；isRemoved 后重新定位）。 */
    private FramePatternProviderBlockEntity provider;

    /** 会话状态：槽位映射（与稀疏输入对齐，-1 = 未指定）与抽取槽位列表。 */
    private int[] slotMapping = new int[0];
    private int[] extractSlots = new int[0];

    /**
     * @param pos              供应器方块位置
     * @param dimension        供应器所在维度
     * @param patternSlotIndex 供应器样板槽序号（patternInventory 内索引）
     */
    public FramePatternConfigHost(BlockPos pos, ResourceKey<Level> dimension, int patternSlotIndex) {
        this.pos = pos;
        this.dimension = dimension;
        this.patternSlotIndex = patternSlotIndex;
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
     *
     * @param level 定位用世界（服务端/客户端均可，客户端仅用于菜单渲染副本）
     * @return 供应器 BE；方块缺失或类型不符时返回 null（调用方 Fail Fast）
     */
    @Nullable
    public FramePatternProviderBlockEntity getProvider(Level level) {
        if (this.provider == null || this.provider.isRemoved()) {
            if (level.getBlockEntity(this.pos) instanceof FramePatternProviderBlockEntity be) {
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
}