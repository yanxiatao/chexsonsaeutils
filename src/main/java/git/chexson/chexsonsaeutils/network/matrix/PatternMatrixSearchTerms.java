package git.chexson.chexsonsaeutils.network.matrix;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 不含数量或完整样板的搜索摘要，保留翻译组件以供客户端按当前语言搜索。 */
public record PatternMatrixSearchTerms(List<Component> inputs, List<Component> outputs) {
    public static final PatternMatrixSearchTerms EMPTY = new PatternMatrixSearchTerms(List.of(), List.of());
    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixSearchTerms> STREAM_CODEC =
            StreamCodec.composite(ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    PatternMatrixSearchTerms::inputs,
                    ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    PatternMatrixSearchTerms::outputs, PatternMatrixSearchTerms::new);

    public PatternMatrixSearchTerms {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    /** 搜索全部可选输入、副产物及键标识，重复候选只保留一份。 */
    public static PatternMatrixSearchTerms from(IPatternDetails pattern) {
        if (pattern == null) return EMPTY;
        Set<Component> inputs = new LinkedHashSet<>();
        Set<Component> outputs = new LinkedHashSet<>();
        for (var input : pattern.getInputs()) {
            for (GenericStack stack : input.getPossibleInputs()) add(inputs, stack);
        }
        for (GenericStack stack : pattern.getOutputs()) add(outputs, stack);
        return new PatternMatrixSearchTerms(List.copyOf(inputs), List.copyOf(outputs));
    }

    private static void add(Set<Component> terms, GenericStack stack) {
        if (stack == null || stack.what() == null) return;
        terms.add(stack.what().getDisplayName());
        terms.add(Component.literal(stack.what().toString()));
    }
}
