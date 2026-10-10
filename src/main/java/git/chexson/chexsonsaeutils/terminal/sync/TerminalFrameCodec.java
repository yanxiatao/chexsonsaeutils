package git.chexson.chexsonsaeutils.terminal.sync;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import java.math.BigInteger;
import java.util.ArrayList;
/** 有资源预算的精确帧编码，整个逻辑帧在外层按字节分片，单个巨数也可跨包。 */
public final class TerminalFrameCodec {
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    public static final int CHUNK_BYTES = 24 * 1024;
    public static final int MAX_ENTRIES = 100_000;
    private TerminalFrameCodec() {}
    public static void writeAmount(FriendlyByteBuf buf, StorageAmount amount) {
        if (amount.unbounded()) { buf.writeByte(2); return; }
        if (amount.value().bitLength() <= 63) { buf.writeByte(0); buf.writeVarLong(amount.value().longValueExact()); }
        else {
            if ((long) amount.value().bitLength() > (long) MAX_FRAME_BYTES * 8)
                throw new IllegalArgumentException("数量超出帧预算");
            buf.writeByte(1); var bytes = amount.value().toByteArray();
            if (bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
            buf.writeByteArray(bytes); }
    }
    public static StorageAmount readAmount(FriendlyByteBuf buf) {
        int kind = buf.readUnsignedByte();
        if (kind == 2) return StorageAmount.infinite();
        if (kind == 0) return StorageAmount.finite(BigInteger.valueOf(buf.readVarLong()));
        if (kind != 1) throw new IllegalArgumentException("未知数量编码");
        var bytes = buf.readByteArray(MAX_FRAME_BYTES);
        if (bytes.length == 0 || bytes[0] == 0) throw new IllegalArgumentException("非规范大数量编码");
        var value = new BigInteger(1, bytes);
        if (value.bitLength() <= 63) throw new IllegalArgumentException("小数量误用大数编码");
        return StorageAmount.finite(value);
    }
    public static void write(RegistryFriendlyByteBuf buf, TerminalFrame frame) {
        if (frame.changes().size() > MAX_ENTRIES || frame.removed().size() > MAX_ENTRIES)
            throw new IllegalArgumentException("库存帧条目超出预算");
        buf.writeVarLong(frame.revision()); buf.writeVarLong(frame.base()); buf.writeBoolean(frame.full());
        buf.writeBoolean(frame.active()); buf.writeBoolean(frame.covered()); buf.writeVarInt(frame.changes().size());
        for (var entry : frame.changes()) {
            buf.writeVarInt(entry.id()); AEKey.STREAM_CODEC.encode(buf, entry.key()); writeAmount(buf, entry.amount());
            buf.writeBoolean(entry.craftable()); buf.writeBoolean(entry.unverified() != null);
            if (entry.unverified() != null) buf.writeLong(entry.unverified());
            if (buf.readableBytes() > MAX_FRAME_BYTES) throw new IllegalArgumentException("库存帧超出资源预算");
        }
        buf.writeVarInt(frame.removed().size()); for (var id : frame.removed()) buf.writeVarInt(id);
        if (buf.readableBytes() > MAX_FRAME_BYTES) throw new IllegalArgumentException("库存帧超出资源预算");
    }
    public static TerminalFrame read(RegistryFriendlyByteBuf buf) {
        if (buf.readableBytes() > MAX_FRAME_BYTES) throw new IllegalArgumentException("库存帧超出资源预算");
        long revision = buf.readVarLong(), base = buf.readVarLong();
        if (revision <= 0 || base < 0 || base >= revision) throw new IllegalArgumentException("库存帧版本无效");
        boolean full = buf.readBoolean(), active = buf.readBoolean(), covered = buf.readBoolean();
        int count = boundedCount(buf);
        var entries = new ArrayList<TerminalEntry>(count);
        for (int i = 0; i < count; i++) {
            int id = buf.readVarInt(); if (id < 0) throw new IllegalArgumentException("条目编号无效");
            var key = AEKey.STREAM_CODEC.decode(buf); var amount = readAmount(buf);
            boolean craftable = buf.readBoolean(); Long unverified = buf.readBoolean() ? buf.readLong() : null;
            entries.add(new TerminalEntry(id, key, amount, craftable, unverified));
        }
        int removals = boundedCount(buf); var removed = new ArrayList<Integer>(removals);
        for (int i = 0; i < removals; i++) {
            int id = buf.readVarInt();
            if (id < 0) throw new IllegalArgumentException("删除条目编号无效");
            removed.add(id);
        }
        if (buf.isReadable()) throw new IllegalArgumentException("库存帧尾部多余数据");
        return new TerminalFrame(revision, base, full, active, covered, entries, removed);
    }
    private static int boundedCount(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("库存帧条目超出预算");
        return count;
    }
}
