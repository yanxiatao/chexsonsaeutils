package git.chexson.chexsonsaeutils.crafting.custompattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.crafting.PatternDetailsTooltip;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 框架样板的样板详情实现（IPatternDetails）。
 * <p>
 * 动机：框架样板供应器需要把稀疏输入按 slotMapping 强制写入私有维度机器的指定槽位，
 * 并在完成后按 extractSlots 强制抽取输出。本类在 AEProcessingPattern 的稀疏输入/输出
 * 语义之上，额外暴露槽位映射与抽取槽位，供 CustomPatternProviderLogic 的
 * pushCustomPattern / pullFromMachine 使用。
 * <p>
 * 注意：AE2 的 AEPatternHelper 是 package-private，无法直接复用其 condenseStacks，
 * 此处自实现等价逻辑（LinkedHashMap 保序合并）。
 */
public class CustomProcessingPattern implements IPatternDetails {
    public static final int MAX_INPUT_SLOTS = 9 * 9;
    public static final int MAX_OUTPUT_SLOTS = 3 * 9;

    private final AEItemKey definition;
    private final List<GenericStack> sparseInputs, sparseOutputs;
    private final int[] slotMapping;
    private final int[] extractSlots;
    private final boolean overflowStacks;
    private final Input[] inputs;
    private final List<GenericStack> condensedOutputs;

    public CustomProcessingPattern(AEItemKey definition) {
        this.definition = definition;

        var encodedPattern = definition.get(ChexsonsaeutilsContent.ENCODED_CUSTOM_PATTERN.get());
        if (encodedPattern == null) {
            throw new IllegalArgumentException("Given item does not encode a frame pattern: " + definition);
        } else if (encodedPattern.containsMissingContent()) {
            // I1 修复：同 AEProcessingPattern，引用已卸载 mod 物品的样板视为无效
            throw new IllegalArgumentException("Pattern references missing content");
        }

        this.sparseInputs = encodedPattern.sparseInputs();
        this.sparseOutputs = encodedPattern.sparseOutputs();
        this.slotMapping = encodedPattern.slotMapping();
        this.extractSlots = encodedPattern.extractSlots();
        this.overflowStacks = encodedPattern.overflowStacks();
        var condensedInputs = condenseStacks(sparseInputs);
        this.inputs = new Input[condensedInputs.size()];
        for (int i = 0; i < inputs.length; ++i) {
            inputs[i] = new Input(condensedInputs.get(i));
        }

        // Ordering is preserved by condenseStacks
        this.condensedOutputs = condenseStacks(sparseOutputs);
    }

    /**
     * 把框架样板数据写入物品的 ENCODED_CUSTOM_PATTERN 组件。
     *
     * @param stack        目标物品（必须是 CustomPatternItem）
     * @param sparseInputs 稀疏输入列表（至少一个非 null）
     * @param sparseOutputs 稀疏输出列表（第一个必须非 null）
     * @param slotMapping  与 sparseInputs 对齐的槽位映射，-1 表示未指定
     * @param extractSlots 强制抽取槽位列表，空数组表示未配置
     * @param overflowStacks 是否允许指定槽位推送突破堆叠上限（无上下文的调用场景传 false）
     */
    public static void encode(ItemStack stack, List<GenericStack> sparseInputs, List<GenericStack> sparseOutputs,
            int[] slotMapping, int[] extractSlots, boolean overflowStacks) {
        if (sparseInputs.stream().noneMatch(Objects::nonNull)) {
            throw new IllegalArgumentException("At least one input must be non-null.");
        }
        Objects.requireNonNull(sparseOutputs.get(0),
                "The first (primary) output must be non-null.");
        // I3 修复：槽位映射必须与稀疏输入对齐，不符时 fail-fast 而非静默降级
        if (slotMapping.length != sparseInputs.size()) {
            throw new IllegalArgumentException(
                    "slotMapping length %d does not match sparseInputs size %d"
                            .formatted(slotMapping.length, sparseInputs.size()));
        }

        stack.set(ChexsonsaeutilsContent.ENCODED_CUSTOM_PATTERN.get(), new EncodedCustomPattern(
                sparseInputs, sparseOutputs, slotMapping, extractSlots, overflowStacks));
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass() == getClass()
                && ((CustomProcessingPattern) obj).definition.equals(definition);
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return condensedOutputs;
    }

    public List<GenericStack> getSparseInputs() {
        return sparseInputs;
    }

    public List<GenericStack> getSparseOutputs() {
        return sparseOutputs;
    }

    /**
     * @return 与 sparseInputs 对齐的槽位映射，-1 表示未指定（走普通插入路径）
     */
    public int[] getSlotMapping() {
        return slotMapping;
    }

    /**
     * @return 强制抽取槽位列表，空数组表示未配置（走普通输出匹配路径）
     */
    public int[] getExtractSlots() {
        return extractSlots;
    }

    /**
     * 转换为等价的 AE2 原生处理样板详情（照 advancedae AdvProcessingPattern.getAEProcessingPattern）。
     * <p>
     * 动机：AE2 原生编码终端的样板回填逻辑（PatternEncodingLogic.loadEncodedPattern）
     * 纯 instanceof 四分支只认 AE 原生四类样板，框架样板放入不会被解析。配套 mixin
     * 拦截后经本方法得到等价 AEProcessingPattern，再调用原生 loadProcessingPattern
     * 完成输入/输出格回填。
     *
     * @param level 客户端/服务端关卡（解码用）
     * @return 等价的原生处理样板详情；稀疏输入/输出无效时返回 null
     */
    @Nullable
    public AEProcessingPattern getAEProcessingPattern(Level level) {
        var stack = PatternDetailsHelper.encodeProcessingPattern(this.getSparseInputs(), this.getSparseOutputs());
        if (stack == null) {
            return null;
        }
        var pattern = PatternDetailsHelper.decodePattern(stack, level);
        return pattern instanceof AEProcessingPattern aePattern ? aePattern : null;
    }

    /**
     * @return 是否允许指定槽位推送突破堆叠上限（true：写入后读回实际存量、差额退回排队重试）
     */
    public boolean isOverflowStacksAllowed() {
        return overflowStacks;
    }

    @Override
    public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink inputSink) {
        if (sparseInputs.size() == inputs.length) {
            // No compression -> no need to reorder
            IPatternDetails.super.pushInputsToExternalInventory(inputHolder, inputSink);
            return;
        }

        var allInputs = new KeyCounter();
        for (var counter : inputHolder) {
            allInputs.addAll(counter);
        }

        // Push according to sparse input order
        for (var sparseInput : sparseInputs) {
            if (sparseInput == null) {
                continue;
            }

            var key = sparseInput.what();
            var amount = sparseInput.amount();
            long available = allInputs.get(key);

            if (available < amount) {
                throw new RuntimeException("Expected at least %d of %s when pushing pattern, but only %d available"
                        .formatted(amount, key, available));
            }

            inputSink.pushInput(key, amount);
            allInputs.remove(key, amount);
        }
    }

    public static PatternDetailsTooltip getInvalidPatternTooltip(ItemStack stack, Level level,
            @Nullable Exception cause, TooltipFlag flags) {
        var tooltip = new PatternDetailsTooltip(PatternDetailsTooltip.OUTPUT_TEXT_PRODUCES);

        var encodedPattern = stack.get(ChexsonsaeutilsContent.ENCODED_CUSTOM_PATTERN.get());
        if (encodedPattern != null) {
            encodedPattern.sparseInputs().stream().filter(Objects::nonNull).forEach(tooltip::addInput);
            encodedPattern.sparseOutputs().stream().filter(Objects::nonNull).forEach(tooltip::addOutput);
        }

        return tooltip;
    }

    /**
     * 合并稀疏输入：去 null、同 key 求和、保持首次出现顺序。
     * <p>
     * 与 AE2 AEPatternHelper.condenseStacks 等价（该类 package-private 无法复用）。
     */
    private static List<GenericStack> condenseStacks(List<GenericStack> sparseInput) {
        // Use a linked map to preserve ordering.
        var map = new LinkedHashMap<AEKey, Long>();

        for (var input : sparseInput) {
            if (input != null) {
                map.merge(input.what(), input.amount(), Long::sum);
            }
        }

        if (map.isEmpty()) {
            throw new IllegalStateException("No pattern here!");
        }

        List<GenericStack> out = new ArrayList<>(map.size());
        for (var entry : map.entrySet()) {
            out.add(new GenericStack(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private static class Input implements IInput {
        private final GenericStack[] template;
        private final long multiplier;

        private Input(GenericStack stack) {
            this.template = new GenericStack[] { new GenericStack(stack.what(), 1) };
            this.multiplier = stack.amount();
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return template;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return input.matches(template[0]);
        }

        @Nullable
        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}