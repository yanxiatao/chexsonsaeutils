package git.chexson.chexsonsaeutils.network.custompatternencoder;

import static appeng.api.stacks.AEKey.writeKey;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import appeng.api.stacks.AEKey;
import appeng.core.network.CustomAppEngPayload;
import appeng.core.network.ServerboundPacket;
import git.chexson.chexsonsaeutils.menu.custompatternencoder.CustomPatternEncoderMenu;

/**
 * 客户端 → 服务端：把某个稀疏输入映射到机器槽位（-1 = 未指定）。
 * <p>
 * 动机：照 advancedae AdvPatternEncoderChangeDirectionPacket 的实时交互链路，
 * 槽位输入控件每次变更立即发包，服务端 update() 重新编码输出槽并回推最新数据。
 */
public record CustomPatternSlotChangePacket(AEKey key, int slot) implements ServerboundPacket {

    public static final StreamCodec<RegistryFriendlyByteBuf, CustomPatternSlotChangePacket> STREAM_CODEC =
            StreamCodec.ofMember(CustomPatternSlotChangePacket::write, CustomPatternSlotChangePacket::decode);

    public static final Type<CustomPatternSlotChangePacket> TYPE =
            CustomAppEngPayload.createType("frame_pattern_slot_change");

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        writeKey(buf, this.key);
        buf.writeInt(this.slot);
    }

    public static CustomPatternSlotChangePacket decode(RegistryFriendlyByteBuf buf) {
        return new CustomPatternSlotChangePacket(AEKey.readKey(buf), buf.readInt());
    }

    @Override
    public void handleOnServer(ServerPlayer player) {
        if (player.containerMenu instanceof CustomPatternEncoderMenu encoderMenu) {
            encoderMenu.update(this.key, this.slot);
        }
    }
}