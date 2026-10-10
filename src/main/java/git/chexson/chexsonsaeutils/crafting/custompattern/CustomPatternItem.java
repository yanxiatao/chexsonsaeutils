package git.chexson.chexsonsaeutils.crafting.custompattern;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import appeng.api.crafting.EncodedPatternDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.AppEng;
import appeng.core.localization.GuiText;
import appeng.crafting.pattern.EncodedPatternItem;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 定制样板物品（EncodedPatternItem 子类）。
 * <p>
 * 动机：AE2 的 EncodedPatternItem 提供样板解码、tooltip、shift 清除等通用行为，
 * 本类只需接入 CustomProcessingPattern 的 decoder 与无效样板 tooltip 策略。
 * 转换 API（convertFromProcessingPattern）供 4b 阶段的 GUI 调用：把处理样板
 * 转换为携带槽位映射的定制样板。
 */
public class CustomPatternItem extends EncodedPatternItem<CustomProcessingPattern> {

    public CustomPatternItem(Properties properties) {
        super(properties, new EncodedPatternDecoder<CustomProcessingPattern>() {
            @Override
            public CustomProcessingPattern decode(AEItemKey what, Level level) {
                return new CustomProcessingPattern(what);
            }
        }, CustomProcessingPattern::getInvalidPatternTooltip);
    }

    /**
     * 物品注册工厂（stacksTo(1)：样板不可堆叠，与 AE2 样板一致）。
     */
    public static CustomPatternItem createItem() {
        return new CustomPatternItem(new Item.Properties().stacksTo(1));
    }

    /**
     * 转换 API：把处理样板转换为定制样板。
     * <p>
     * 读取处理样板的 ENCODED_PROCESSING_PATTERN 组件（稀疏输入/输出），
     * 写入新的定制样板物品并附加槽位映射。供 4b 阶段 GUI 在玩家配置
     * 槽位映射后调用。
     *
     * @param processingPattern 处理样板物品（必须携带 ENCODED_PROCESSING_PATTERN 组件）
     * @param slotMapping       与稀疏输入对齐的槽位映射，-1 表示未指定
     * @param extractSlots      强制抽取槽位列表，空数组表示未配置
     * @param overflowStacks    是否允许指定槽位推送突破堆叠上限（随样板写入组件）
     * @return 新的定制样板物品
     * @throws IllegalArgumentException 输入不是处理样板时抛出
     */
    public static ItemStack convertFromProcessingPattern(ItemStack processingPattern, int[] slotMapping,
            int[] extractSlots, boolean overflowStacks) {
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
        var stack = new ItemStack(ChexsonsaeutilsContent.CUSTOM_PATTERN_ITEM.get());
        CustomProcessingPattern.encode(stack, encoded.sparseInputs(), encoded.sparseOutputs(), slotMapping,
                extractSlots, overflowStacks);
        return stack;
    }

    /**
     * 样板 tooltip 自绘（照 advancedae MixinEncodedPatternItem 的接管模式，本体直接覆写）。
     * <p>
     * 动机：定制样板的输入行需附加指定机器槽位 id（如"物品 (0)"），并显示主动抽取
     * 槽位配置行——AE2 原版 appendHoverText 无此信息。非定制样板/客户端无关卡/
     * 解码失败时回退父类原逻辑。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var what = AEItemKey.of(stack);
        var clientLevel = AppEng.instance().getClientLevel();
        if (what == null || clientLevel == null) {
            super.appendHoverText(stack, context, lines, flags);
            return;
        }
        try {
            var details = PatternDetailsHelper.decodePattern(stack, clientLevel);
            if (!(details instanceof CustomProcessingPattern customPattern)) {
                super.appendHoverText(stack, context, lines, flags);
                return;
            }
            var tooltip = details.getTooltip(clientLevel, flags);

            // 输出行："Produces: 物品 x64"（照 AE2 原版格式）
            var label = Component.empty()
                    .append(tooltip.getOutputMethod())
                    .append(": ")
                    .withStyle(ChatFormatting.GRAY);
            var and = Component.literal(" ")
                    .append(GuiText.And.text())
                    .append(" ")
                    .withStyle(ChatFormatting.GRAY);
            var with = GuiText.With.text().copy().append(": ").withStyle(ChatFormatting.GRAY);

            boolean first = true;
            for (var output : tooltip.getOutputs()) {
                lines.add(Component.empty().append(first ? label : and).append(getTooltipEntryLine(output)));
                first = false;
            }

            // 输入行："With: 物品 x64 (0)"——括号内为指定机器槽位 id（未指定则无后缀），
            // 同种物品映射多个槽位时以逗号列出（照 advancedae 高级样板的方向标注模式）
            first = true;
            for (var input : tooltip.getInputs()) {
                lines.add(Component.empty()
                        .append(first ? with : and)
                        .append(getTooltipEntryLine(input))
                        .append(buildSlotSuffix(customPattern, input.what())));
                first = false;
            }

            // 主动抽取槽位配置行：仅在样板配置了抽取槽位时显示
            if (customPattern.getExtractSlots().length > 0) {
                lines.add(Component.empty()
                        .append(Component.translatable(
                                "gui.chexsonsaeutils.custom_pattern.tooltip.active_extract")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(joinSlots(customPattern.getExtractSlots()))));
            }
        } catch (Exception e) {
            // 解码失败（引用缺失内容/组件损坏）：回退父类原逻辑展示基础信息
            super.appendHoverText(stack, context, lines, flags);
        }
    }

    /**
     * 按输入 key 反查其指定的机器槽位集合（同 key 多个稀疏输入时全部收集，
     * 未指定槽位 -1 跳过）。
     *
     * @return 形如 " (0)" / " (0,3)" 的后缀组件；无指定槽位时返回空组件
     */
    private static Component buildSlotSuffix(CustomProcessingPattern pattern, AEKey key) {
        var sparseInputs = pattern.getSparseInputs();
        var slotMapping = pattern.getSlotMapping();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sparseInputs.size(); i++) {
            var input = sparseInputs.get(i);
            if (input == null || !input.what().equals(key)) {
                continue;
            }
            int slot = i < slotMapping.length ? slotMapping[i] : -1;
            if (slot < 0) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(slot);
        }
        return sb.isEmpty() ? Component.empty() : Component.literal(" (" + sb + ")");
    }

    /**
     * @return 槽位数组的逗号分隔文本（tooltip 抽取槽位行用）
     */
    private static String joinSlots(int[] slots) {
        StringBuilder sb = new StringBuilder();
        for (int slot : slots) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(slot);
        }
        return sb.toString();
    }
}
