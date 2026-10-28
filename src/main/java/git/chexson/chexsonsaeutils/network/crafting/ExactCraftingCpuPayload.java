package git.chexson.chexsonsaeutils.network.crafting;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** 精确 CPU 显示全量帧，使用菜单和 CPU 序号隔离切换前的旧读数。 */
public record ExactCraftingCpuPayload(int containerId, int cpuSerial, CraftingCpuAmounts amounts)
        implements CustomPacketPayload {
    public static final Type<ExactCraftingCpuPayload> TYPE = new Type<>(ResourceLocation.parse("chexsonsaeutils:exact_cpu"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ExactCraftingCpuPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeVarInt(value.containerId); buf.writeVarInt(value.cpuSerial);
                write(buf, value.amounts.stored()); write(buf, value.amounts.active()); write(buf, value.amounts.pending());
            }, buf -> new ExactCraftingCpuPayload(buf.readVarInt(), buf.readVarInt(),
                    new CraftingCpuAmounts(read(buf), read(buf), read(buf))));
    private static BiConsumer<net.minecraft.world.inventory.AbstractContainerMenu, ExactCraftingCpuPayload> receiver;
    /** 可选客户端装配绑定 SDK；网络契约不引用第三方类型。 */
    public static void bind(BiConsumer<net.minecraft.world.inventory.AbstractContainerMenu, ExactCraftingCpuPayload> target) {
        receiver = java.util.Objects.requireNonNull(target);
    }
    public static void handle(ExactCraftingCpuPayload value, IPayloadContext context) {
        context.enqueueWork(() -> {
            var menu = context.player().containerMenu;
            if (menu.containerId == value.containerId && receiver != null) receiver.accept(menu, value);
        });
    }
    private static void write(RegistryFriendlyByteBuf buf, Map<AEKey, BigInteger> map) {
        buf.writeVarInt(map.size());
        map.forEach((key, amount) -> { AEKey.writeKey(buf, key); git.chexson.chexsonsaeutils.network.codec.ExactAmountWire.write(buf, amount); });
    }
    private static Map<AEKey, BigInteger> read(RegistryFriendlyByteBuf buf) {
        int count = git.chexson.chexsonsaeutils.network.codec.ExactAmountWire.readCount(buf);
        var map = new HashMap<AEKey, BigInteger>();
        for (int i = 0; i < count; i++) {
            var key = AEKey.readKey(buf);
            var value = git.chexson.chexsonsaeutils.network.codec.ExactAmountWire.read(buf);
            if (key == null || value.signum() < 0 || map.put(key, value) != null)
                throw new IllegalArgumentException("CPU 精确条目无效");
        }
        return map;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
