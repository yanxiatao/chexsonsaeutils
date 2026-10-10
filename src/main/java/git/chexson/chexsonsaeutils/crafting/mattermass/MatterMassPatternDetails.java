package git.chexson.chexsonsaeutils.crafting.mattermass;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.crafting.PatternDetailsTooltip;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassItem;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 物质团样板详情（IPatternDetails）。
 * <p>
 * 上报给合成网格的输出被替换为单个物质团（名字取原始第一个输出的显示名），
 * 输入沿用原始稀疏输入。{@link #supportsPushInputsToExternalInventory()} 恒 false：
 * 物质团样板误入普通样板供应器时无法推送，任务停滞但不损失原料。
 * <p>
 * 编码终端兼容：{@link #getAEProcessingPattern} 用原始快照还原等价处理样板，
 * 供 PatternEncodingLogicMatterMassMixin 回填输入/输出格，重新编码产物即原始处理样板。
 */
public class MatterMassPatternDetails implements IPatternDetails {

    private final AEItemKey definition;
    private final List<GenericStack> sparseInputs;
    private final List<GenericStack> sparseOutputs;
    private final UUID massUuid;
    private final List<GenericStack> outputs;
    private IInput[] inputs;

    public MatterMassPatternDetails(AEItemKey definition) {
        this.definition = definition;

        var encoded = definition.get(ChexsonsaeutilsContent.ENCODED_MATTER_MASS_PATTERN.get());
        if (encoded == null) {
            throw new IllegalArgumentException("Given item does not encode a matter mass pattern: " + definition);
        } else if (encoded.containsMissingContent()) {
            throw new IllegalArgumentException("Pattern references missing content");
        }

        this.sparseInputs = encoded.sparseInputs();
        this.sparseOutputs = encoded.sparseOutputs();
        this.massUuid = encoded.massUuid();

        var massStack = MatterMassItem.createStack(firstOutputName(), this.massUuid);
        var massKey = AEItemKey.of(massStack);
        if (massKey == null) {
            throw new IllegalStateException("Failed to build matter mass key for pattern " + definition);
        }
        this.outputs = List.of(new GenericStack(massKey, 1));
    }

    /**
     * 把物质团样板数据写入物品组件（转换时调用）。
     *
     * @param stack         目标物品（必须是 MatterMassPatternItem）
     * @param sparseInputs  原始稀疏输入（至少一个非 null）
     * @param sparseOutputs 原始稀疏输出（第一个必须非 null）
     * @param massUuid      预分配的物质团 UUID
     */
    public static void encode(ItemStack stack, List<GenericStack> sparseInputs, List<GenericStack> sparseOutputs,
            UUID massUuid) {
        if (sparseInputs.stream().noneMatch(Objects::nonNull)) {
            throw new IllegalArgumentException("At least one input must be non-null.");
        }
        Objects.requireNonNull(sparseOutputs.get(0),
                "The first (primary) output must be non-null.");
        stack.set(ChexsonsaeutilsContent.ENCODED_MATTER_MASS_PATTERN.get(),
                new EncodedMatterMassPattern(sparseInputs, sparseOutputs, massUuid));
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass() == getClass()
                && ((MatterMassPatternDetails) obj).definition.equals(definition);
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        if (inputs == null) {
            // 委托合成的原生处理样板构建输入数组（输入语义与处理样板完全一致）
            var delegate = buildDelegatePattern(this.sparseInputs, this.outputs);
            this.inputs = delegate != null ? delegate.getInputs() : new IInput[0];
        }
        return inputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    @Override
    public boolean supportsPushInputsToExternalInventory() {
        return false;
    }

    public List<GenericStack> getSparseInputs() {
        return sparseInputs;
    }

    public List<GenericStack> getSparseOutputs() {
        return sparseOutputs;
    }

    public UUID getMassUuid() {
        return massUuid;
    }

    /** 物质团显示名：原始第一个输出的显示名（转换时确定，随组件快照持久）。 */
    public net.minecraft.network.chat.Component firstOutputName() {
        for (var output : sparseOutputs) {
            if (output != null) {
                return output.what().getDisplayName();
            }
        }
        return net.minecraft.network.chat.Component.literal("Matter Mass");
    }

    /**
     * 还原等价的原生处理样板详情（原始输入/输出快照），供编码终端回填后
     * 重新编码为标准处理样板。
     */
    @Nullable
    public AEProcessingPattern getAEProcessingPattern(Level level) {
        var stack = PatternDetailsHelper.encodeProcessingPattern(this.sparseInputs, this.sparseOutputs);
        if (stack == null) {
            return null;
        }
        var pattern = PatternDetailsHelper.decodePattern(stack, level);
        return pattern instanceof AEProcessingPattern aePattern ? aePattern : null;
    }

    /** 用给定稀疏输入/输出合成原生处理样板（内部复用其输入数组/推送语义）。 */
    @Nullable
    private static AEProcessingPattern buildDelegatePattern(List<GenericStack> sparseInputs,
            List<GenericStack> sparseOutputs) {
        var stack = PatternDetailsHelper.encodeProcessingPattern(sparseInputs, sparseOutputs);
        if (stack == null) {
            return null;
        }
        var key = AEItemKey.of(stack);
        if (key == null) {
            return null;
        }
        try {
            return new AEProcessingPattern(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static PatternDetailsTooltip getInvalidPatternTooltip(ItemStack stack, Level level,
            @Nullable Exception cause, TooltipFlag flags) {
        var tooltip = new PatternDetailsTooltip(PatternDetailsTooltip.OUTPUT_TEXT_PRODUCES);
        var encoded = stack.get(ChexsonsaeutilsContent.ENCODED_MATTER_MASS_PATTERN.get());
        if (encoded != null) {
            encoded.sparseInputs().stream().filter(Objects::nonNull).forEach(tooltip::addInput);
            encoded.sparseOutputs().stream().filter(Objects::nonNull).forEach(tooltip::addOutput);
        }
        return tooltip;
    }
}
