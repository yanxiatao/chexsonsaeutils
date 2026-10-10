package git.chexson.chexsonsaeutils.network.crafting;

import git.chexson.chexsonsaeutils.crafting.request.CraftingAmountExpression;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.math.BigInteger;

/** 下单数量已经转换为最小单位；服务端不接受客户端的目标键或表达式。 */
public record WideCraftRequestPayload(int containerId, BigInteger amount, boolean missing, boolean autoStart,
        boolean finalTotal) implements CustomPacketPayload {
    public static final Type<WideCraftRequestPayload> TYPE = new Type<>(ResourceLocation.parse("chexsonsaeutils:wide_craft_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WideCraftRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> { buf.writeVarInt(value.containerId); buf.writeUtf(value.amount.toString(), CraftingAmountExpression.MAX_LENGTH);
                buf.writeBoolean(value.missing); buf.writeBoolean(value.autoStart); buf.writeBoolean(value.finalTotal); },
            buf -> new WideCraftRequestPayload(buf.readVarInt(), new BigInteger(buf.readUtf(CraftingAmountExpression.MAX_LENGTH)),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
    public WideCraftRequestPayload {
        if (amount.signum() <= 0 || amount.toString().length() > CraftingAmountExpression.MAX_LENGTH)
            throw new IllegalArgumentException("下单数量必须为预算内正整数");
    }
    private static BiConsumer<WideCraftRequestPayload, Player> receiver;
    /** common setup 绑定服务端处理入口，载荷不反向依赖菜单实现。 */
    public static void bind(BiConsumer<WideCraftRequestPayload, Player> value) { receiver = Objects.requireNonNull(value); }
    public static void handle(WideCraftRequestPayload value, IPayloadContext context) {
        context.enqueueWork(() -> Objects.requireNonNull(receiver, "下单服务端尚未装配").accept(value, context.player()));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
