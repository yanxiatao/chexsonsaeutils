package git.chexson.chexsonsaeutils.menu.custompatternconfig;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternItem;

/**
 * 框架样板配置转换器实现（CustomPatternConfigConverter 接口实现）。
 * <p>
 * 动机：承接接口定义的两个行为——解码输入样板（仅接受 AE2 处理样板，
 * 其他编码样板返回 null 触发 UI 清空）与调用
 * {@link CustomPatternItem#convertFromProcessingPattern} 生成框架样板。
 */
public class CustomPatternConfigConverterImpl implements CustomPatternConfigConverter {

    @Override
    public List<GenericStack> decodeSparseInputs(ItemStack input, Level level) {
        var details = PatternDetailsHelper.decodePattern(input, level);
        if (!(details instanceof AEProcessingPattern processingPattern)) {
            return null;
        }
        return processingPattern.getSparseInputs();
    }

    @Override
    public ItemStack encodeCustomPattern(ItemStack input, int[] slotMapping, int[] extractSlots,
            boolean overflowStacks) {
        return CustomPatternItem.convertFromProcessingPattern(input, slotMapping, extractSlots, overflowStacks);
    }
}