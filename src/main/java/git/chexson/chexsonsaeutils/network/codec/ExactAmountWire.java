package git.chexson.chexsonsaeutils.network.codec;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import java.math.BigInteger;

/** 精确非负整数的线上表示；保留既有 VarInt 长度加有符号字节数组的格式。 */
public final class ExactAmountWire {
    private ExactAmountWire() {}

    /** 写出完整数量；不以任意固定位数截断合法订单的中间材料需求。 */
    public static void write(FriendlyByteBuf buffer, BigInteger amount) {
        if (amount.signum() < 0) throw new IllegalArgumentException("同步数量不得为负数");
        buffer.writeByteArray(amount.toByteArray());
    }

    /** 数组分配受当前已收到的包体约束，不能按不可信长度前缀预分配超出包体的内存。 */
    public static BigInteger read(FriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length <= 0 || length > buffer.readableBytes()) throw new DecoderException("精确数量长度超出已接收包体");
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        BigInteger amount = new BigInteger(bytes);
        if (amount.signum() < 0) throw new IllegalArgumentException("同步数量不得为负数");
        return amount;
    }

    /** 每个整数至少有一字节长度前缀和一字节数值；按实际包体拒绝伪造巨量计数。 */
    public static int readCount(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > buffer.readableBytes() / 2) throw new DecoderException("同步条目计数超出已接收包体");
        return count;
    }
}
