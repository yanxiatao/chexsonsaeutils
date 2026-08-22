package git.chexson.chexsonsaeutils.menu.framepatternconfig;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.stacks.GenericStack;

/**
 * 框架样板配置转换器（接口）。
 * <p>
 * 动机：配置 GUI 的核心行为是「处理样板 → 框架样板」的转换，该逻辑与
 * 菜单/屏幕解耦，便于独立测试与复用。实现见 {@link FramePatternConfigConverterImpl}。
 */
public interface FramePatternConfigConverter {

    /**
     * 解码输入处理样板的稀疏输入列表。
     *
     * @param input 输入槽中的处理样板
     * @param level 解码所需的世界（注册表访问）
     * @return 稀疏输入列表；输入不是处理样板时返回 null
     */
    List<GenericStack> decodeSparseInputs(ItemStack input, Level level);

    /**
     * 按槽位映射与抽取槽位生成框架样板。
     *
     * @param input          输入处理样板
     * @param slotMapping    每个稀疏输入对应的机器槽位（-1 = 未指定）
     * @param extractSlots   抽取槽位列表
     * @param overflowStacks 是否允许指定槽位推送突破堆叠上限（随样板写入组件）
     * @return 框架样板物品
     * @throws IllegalArgumentException slotMapping 长度与稀疏输入数不符时
     */
    ItemStack encodeFramePattern(ItemStack input, int[] slotMapping, int[] extractSlots, boolean overflowStacks);
}