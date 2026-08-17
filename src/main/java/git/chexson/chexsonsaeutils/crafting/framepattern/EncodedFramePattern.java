package git.chexson.chexsonsaeutils.crafting.framepattern;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;

/**
 * 框架样板的编码数据组件（DataComponent）。
 * <p>
 * 动机：处理样板（EncodedProcessingPattern）只有稀疏输入/输出，没有「输入 → 机器槽位」的
 * 强制映射信息。框架样板供应器需要把每个稀疏输入强制写入私有维度机器的指定槽位（突破
 * 堆叠上限），并在完成后从指定槽位强制抽取输出，因此在本组件中额外携带
 * slotMapping（输入 → 槽位映射）与 extractSlots（强制抽取槽位）。
 *
 * @param sparseInputs  稀疏输入列表（与处理样板一致，可含 null 占位）
 * @param sparseOutputs 稀疏输出列表（与处理样板一致，可含 null 占位）
 * @param slotMapping   与 sparseInputs 对齐的槽位映射，-1 表示未指定（走普通插入路径）
 * @param extractSlots  强制抽取槽位列表，空数组表示未配置（走普通输出匹配路径）
 */
public record EncodedFramePattern(
        List<GenericStack> sparseInputs,
        List<GenericStack> sparseOutputs,
        int[] slotMapping,
        int[] extractSlots) {
    public EncodedFramePattern {
        sparseInputs = Collections.unmodifiableList(sparseInputs);
        sparseOutputs = Collections.unmodifiableList(sparseOutputs);
        // S1 修复：int[] 防御性拷贝，防止外部修改破坏组件数据
        slotMapping = Arrays.copyOf(slotMapping, slotMapping.length);
        extractSlots = Arrays.copyOf(extractSlots, extractSlots.length);
    }

    // S1 修复：accessor 返回拷贝，防止调用方修改内部数组
    @Override
    public int[] slotMapping() {
        return Arrays.copyOf(slotMapping, slotMapping.length);
    }

    @Override
    public int[] extractSlots() {
        return Arrays.copyOf(extractSlots, extractSlots.length);
    }

    /**
     * I1 修复：检查稀疏输入/输出是否引用已卸载 mod 的缺失内容（同 AE2
     * EncodedProcessingPattern 实现），引用缺失内容的样板应被拒绝而非静默接受。
     */
    public boolean containsMissingContent() {
        return Stream.concat(sparseInputs.stream(), sparseOutputs.stream())
                .anyMatch(stack -> stack != null && AEItems.MISSING_CONTENT.is(stack.what()));
    }

    private static final Codec<int[]> INT_ARRAY_CODEC = Codec.INT.listOf()
            .xmap(list -> list.stream().mapToInt(Integer::intValue).toArray(),
                    array -> Arrays.stream(array).boxed().toList());

    public static final Codec<EncodedFramePattern> CODEC = RecordCodecBuilder.create(builder -> builder.group(
            GenericStack.FAULT_TOLERANT_NULLABLE_LIST_CODEC.fieldOf("sparseInputs")
                    .forGetter(EncodedFramePattern::sparseInputs),
            GenericStack.FAULT_TOLERANT_NULLABLE_LIST_CODEC.fieldOf("sparseOutputs")
                    .forGetter(EncodedFramePattern::sparseOutputs),
            INT_ARRAY_CODEC.fieldOf("slotMapping").forGetter(EncodedFramePattern::slotMapping),
            INT_ARRAY_CODEC.fieldOf("extractSlots").forGetter(EncodedFramePattern::extractSlots))
            .apply(builder, EncodedFramePattern::new));

    private static final StreamCodec<ByteBuf, int[]> INT_ARRAY_STREAM_CODEC = ByteBufCodecs.INT
            .apply(ByteBufCodecs.<ByteBuf, Integer>list())
            .map(list -> list.stream().mapToInt(Integer::intValue).toArray(),
                    array -> Arrays.stream(array).boxed().toList());

    public static final StreamCodec<RegistryFriendlyByteBuf, EncodedFramePattern> STREAM_CODEC = StreamCodec
            .composite(
                    GenericStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    EncodedFramePattern::sparseInputs,
                    GenericStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    EncodedFramePattern::sparseOutputs,
                    INT_ARRAY_STREAM_CODEC,
                    EncodedFramePattern::slotMapping,
                    INT_ARRAY_STREAM_CODEC,
                    EncodedFramePattern::extractSlots,
                    EncodedFramePattern::new);
}