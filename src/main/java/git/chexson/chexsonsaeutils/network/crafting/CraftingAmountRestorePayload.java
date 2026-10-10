package git.chexson.chexsonsaeutils.network.crafting;

import git.chexson.chexsonsaeutils.crafting.request.CraftingAmountExpression;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.math.BigInteger;
import java.util.Objects;
import java.util.function.Consumer;

/** 返回数量选择页的精确回填；客户端绑定负责访问屏幕，服务端不加载客户端类。 */
public record CraftingAmountRestorePayload(int containerId, BigInteger amount, boolean missing) implements CustomPacketPayload {
    public static final Type<CraftingAmountRestorePayload> TYPE = new Type<>(ResourceLocation.parse("chexsonsaeutils:craft_amount_restore"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingAmountRestorePayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> { buf.writeVarInt(value.containerId); buf.writeUtf(value.amount.toString(), CraftingAmountExpression.MAX_LENGTH);
                buf.writeBoolean(value.missing); },
            buf -> new CraftingAmountRestorePayload(buf.readVarInt(), new BigInteger(buf.readUtf(CraftingAmountExpression.MAX_LENGTH)), buf.readBoolean()));
    private static Consumer<CraftingAmountRestorePayload> receiver;
    public CraftingAmountRestorePayload {
        if (amount.signum() <= 0 || amount.toString().length() > CraftingAmountExpression.MAX_LENGTH)
            throw new IllegalArgumentException("回填数量非法");
    }
    /** 客户端启动时绑定屏幕入口。 */
    public static void bind(Consumer<CraftingAmountRestorePayload> value) { receiver = Objects.requireNonNull(value); }
    public static void handle(CraftingAmountRestorePayload value, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu.containerId == value.containerId)
                Objects.requireNonNull(receiver, "回填客户端尚未装配").accept(value);
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
