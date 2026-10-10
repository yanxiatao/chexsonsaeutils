package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 装配矩阵单格按需下发的样板本体。
 *
 * <p>全量下发会把一万六千份配方推给客户端，因此只按可见行索取本体，
 * 用途是鼠标悬停时还原 AE2 自己的样板提示；渲染仍用摘要里的展示栈。</p>
 */
public record PatternMatrixRealStack(int slot, ItemStack stack) {

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixRealStack> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixRealStack::slot,
                    ItemStack.OPTIONAL_STREAM_CODEC,
                    PatternMatrixRealStack::stack,
                    PatternMatrixRealStack::new
            );
}
