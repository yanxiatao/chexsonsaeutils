package git.chexson.chexsonsaeutils.crafting.framepattern;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.EncodedPatternDecoder;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.EncodedPatternItem;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 框架样板物品（EncodedPatternItem 子类）。
 * <p>
 * 动机：AE2 的 EncodedPatternItem 提供样板解码、tooltip、shift 清除等通用行为，
 * 本类只需接入 FrameProcessingPattern 的 decoder 与无效样板 tooltip 策略。
 * 转换 API（convertFromProcessingPattern）供 4b 阶段的 GUI 调用：把处理样板
 * 转换为携带槽位映射的框架样板。
 */
public class FramePatternItem extends EncodedPatternItem<FrameProcessingPattern> {

    public FramePatternItem(Properties properties) {
        super(properties, new EncodedPatternDecoder<FrameProcessingPattern>() {
            @Override
            public FrameProcessingPattern decode(AEItemKey what, Level level) {
                return new FrameProcessingPattern(what);
            }
        }, FrameProcessingPattern::getInvalidPatternTooltip);
    }

    /**
     * 物品注册工厂（stacksTo(1)：样板不可堆叠，与 AE2 样板一致）。
     */
    public static FramePatternItem createItem() {
        return new FramePatternItem(new Item.Properties().stacksTo(1));
    }

    /**
     * 转换 API：把处理样板转换为框架样板。
     * <p>
     * 读取处理样板的 ENCODED_PROCESSING_PATTERN 组件（稀疏输入/输出），
     * 写入新的框架样板物品并附加槽位映射。供 4b 阶段 GUI 在玩家配置
     * 槽位映射后调用。
     *
     * @param processingPattern 处理样板物品（必须携带 ENCODED_PROCESSING_PATTERN 组件）
     * @param slotMapping       与稀疏输入对齐的槽位映射，-1 表示未指定
     * @param extractSlots      强制抽取槽位列表，空数组表示未配置
     * @return 新的框架样板物品
     * @throws IllegalArgumentException 输入不是处理样板时抛出
     */
    public static ItemStack convertFromProcessingPattern(ItemStack processingPattern, int[] slotMapping,
            int[] extractSlots) {
        var encoded = processingPattern.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        if (encoded == null) {
            throw new IllegalArgumentException(
                    "Given item does not encode a processing pattern: " + processingPattern);
        }
        // I3 修复：槽位映射必须与处理样板的稀疏输入对齐（encode 内同样校验，此处先行 fail-fast）
        if (slotMapping.length != encoded.sparseInputs().size()) {
            throw new IllegalArgumentException(
                    "slotMapping length %d does not match sparseInputs size %d"
                            .formatted(slotMapping.length, encoded.sparseInputs().size()));
        }
        var stack = new ItemStack(ChexsonsaeutilsContent.FRAME_PATTERN_ITEM.get());
        FrameProcessingPattern.encode(stack, encoded.sparseInputs(), encoded.sparseOutputs(), slotMapping,
                extractSlots);
        return stack;
    }
}