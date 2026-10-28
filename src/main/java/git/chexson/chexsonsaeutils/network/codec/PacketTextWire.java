package git.chexson.chexsonsaeutils.network.codec;

import net.minecraft.network.FriendlyByteBuf;

/** 复用原生 UTF-8 线格式，字符预算取实际内容或已接收包体；大包仍由 NeoForge 分片。 */
public final class PacketTextWire {
    private PacketTextWire() {}
    private static final int MAX_UTF_CHARACTERS = Integer.MAX_VALUE / 3;

    /** 按文本本身长度编码，移除默认 32767 字符对服务端大明细的限制。 */
    public static void write(FriendlyByteBuf buffer, String value) {
        if (value.length() > MAX_UTF_CHARACTERS) throw new IllegalArgumentException("同步文本超出 UTF-8 编码容量");
        buffer.writeUtf(value, value.length());
    }

    /** 字符数不会多于实际包体的字节数，原生 codec 继续校验长度前缀和 UTF-8。 */
    public static String read(FriendlyByteBuf buffer) {
        return buffer.readUtf(Math.min(buffer.readableBytes(), MAX_UTF_CHARACTERS));
    }
}
