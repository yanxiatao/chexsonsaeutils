package git.chexson.chexsonsaeutils.crafting.mattermass;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
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
 * 物质团样板的编码数据组件（DataComponent）。
 * <p>
 * 动机：物质团供应器把处理样板就地改写为物质团样板——上报给网格的输出替换为
 * 物质团（名字取原始第一个输出），但编码终端重新编码时必须还原为原始处理样板，
 * 因此组件同时保存原始稀疏输入/输出快照。{@code massUuid} 在转换瞬间预分配并
 * 写入物质团物品组件，保证样板上报 key 与合成产物 key 恒一致（CPU 回流匹配）。
 * <p>
 * 注意：物质团样板不携带 {@code AEComponents.ENCODED_PROCESSING_PATTERN}，
 * 避免被 replacement 解码器或原生解码器误识别。
 *
 * @param sparseInputs  原始稀疏输入快照（转换时固化，含替换规则展开结果）
 * @param sparseOutputs 原始稀疏输出快照（重新编码还原用）
 * @param massUuid      该样板对应物质团的预分配 UUID
 */
public record EncodedMatterMassPattern(
        List<GenericStack> sparseInputs,
        List<GenericStack> sparseOutputs,
        UUID massUuid) {

    public EncodedMatterMassPattern {
        sparseInputs = Collections.unmodifiableList(sparseInputs);
        sparseOutputs = Collections.unmodifiableList(sparseOutputs);
    }

    /** 同 EncodedCustomPattern：引用已卸载 mod 内容的样板视为无效。 */
    public boolean containsMissingContent() {
        return Stream.concat(sparseInputs.stream(), sparseOutputs.stream())
                .anyMatch(stack -> stack != null && AEItems.MISSING_CONTENT.is(stack.what()));
    }

    public static final Codec<EncodedMatterMassPattern> CODEC = RecordCodecBuilder.create(builder -> builder.group(
            GenericStack.FAULT_TOLERANT_NULLABLE_LIST_CODEC.fieldOf("sparseInputs")
                    .forGetter(EncodedMatterMassPattern::sparseInputs),
            GenericStack.FAULT_TOLERANT_NULLABLE_LIST_CODEC.fieldOf("sparseOutputs")
                    .forGetter(EncodedMatterMassPattern::sparseOutputs),
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("massUuid")
                    .forGetter(EncodedMatterMassPattern::massUuid))
            .apply(builder, EncodedMatterMassPattern::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, EncodedMatterMassPattern> STREAM_CODEC = StreamCodec
            .composite(
                    GenericStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    EncodedMatterMassPattern::sparseInputs,
                    GenericStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    EncodedMatterMassPattern::sparseOutputs,
                    ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
                    EncodedMatterMassPattern::massUuid,
                    EncodedMatterMassPattern::new);
}
