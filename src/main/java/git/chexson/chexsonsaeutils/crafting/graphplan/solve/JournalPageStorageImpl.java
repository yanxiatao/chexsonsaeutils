package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * 低内存求解的临时回滚页栈。数值按二进制保存，样板/键身份只在小型字典中保留。
 * 不使用对象序列化；弹出的页可被新分支覆盖，文件随 attempt 结束删除。
 */
final class JournalPageStorageImpl implements JournalPageStorage {
    private final Path path;
    private final RandomAccessFile file;
    private final LongArrayList offsets = new LongArrayList();
    private final IdentityHashMap<Object, Integer> referenceIds = new IdentityHashMap<>();
    private final ArrayList<Object> references = new ArrayList<>();
    private long end;

    JournalPageStorageImpl() {
        try {
            path = Files.createTempFile("chexsonsaeutils-plan-", ".journal");
            try {
                file = new RandomAccessFile(path.toFile(), "rw");
            } catch (IOException failure) {
                Files.deleteIfExists(path);
                throw failure;
            }
        } catch (IOException failure) {
            throw failed("创建", failure);
        }
    }

    private int referenceId(Object ref) {
        if (ref == null) return -1;
        Integer existing = referenceIds.get(ref);
        if (existing != null) return existing;
        int id = references.size();
        references.add(ref);
        referenceIds.put(ref, id);
        return id;
    }

    @Override public void pushRange(int[] kinds, int[] keyIds, Object[] refs, long[] deltas,
            double[] doubles, Object[] values, Slice slice) {
        try {
            file.seek(end);
            var out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(file.getChannel()), 65536));
            out.writeInt(slice.rowCount());
            for (int i = slice.rowStart(); i < slice.rowStart() + slice.rowCount(); i++) {
                out.writeInt(kinds[i]); out.writeInt(keyIds[i]);
                out.writeInt(referenceId(refs[i])); out.writeLong(deltas[i]);
            }
            out.writeInt(slice.doubleCount());
            for (int i = slice.doubleStart(); i < slice.doubleStart() + slice.doubleCount(); i++) out.writeDouble(doubles[i]);
            out.writeInt(slice.valueCount());
            for (int i = slice.valueStart(); i < slice.valueStart() + slice.valueCount(); i++) writeValue(out, values[i]);
            out.flush();
            offsets.add(end);
            end = file.getFilePointer();
        } catch (IOException failure) {
            throw failed("写入", failure);
        }
    }

    @Override public Tops pop(int[] kinds, int[] keyIds, Object[] refs, long[] deltas,
            double[] doubles, Object[] values) {
        if (offsets.isEmpty()) throw new IllegalStateException("No journal page to restore");
        long offset = offsets.removeLong(offsets.size() - 1);
        try {
            file.seek(offset);
            var in = new DataInputStream(new BufferedInputStream(Channels.newInputStream(file.getChannel()), 65536));
            if (in.readInt() != kinds.length) throw new IOException("Journal page size mismatch");
            for (int i = 0; i < kinds.length; i++) {
                kinds[i] = in.readInt(); keyIds[i] = in.readInt();
                int id = in.readInt(); refs[i] = id < 0 ? null : references.get(id);
                deltas[i] = in.readLong();
            }
            int doubleCount = in.readInt();
            for (int i = 0; i < doubleCount; i++) doubles[i] = in.readDouble();
            int valueCount = in.readInt();
            for (int i = 0; i < valueCount; i++) values[i] = readValue(in);
            end = offset;
            return new Tops(doubleCount, valueCount);
        } catch (IOException failure) {
            throw failed("读取", failure);
        }
    }

    private static void writeValue(DataOutput out, Object value) throws IOException {
        if (value == null) { out.writeByte(0); return; }
        BigInteger integer;
        if (value instanceof BigInteger number) {
            out.writeByte(1); integer = number;
        } else if (value instanceof BigDecimal number) {
            out.writeByte(2); out.writeInt(number.scale()); integer = number.unscaledValue();
        } else throw new IOException("Unsupported journal value: " + value.getClass().getName());
        byte[] bytes = integer.toByteArray();
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static Object readValue(DataInput in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == 0) return null;
        if (type != 1 && type != 2) throw new IOException("Unknown journal value type: " + type);
        int scale = type == 2 ? in.readInt() : 0;
        int length = in.readInt();
        if (length <= 0) throw new IOException("Invalid journal integer length: " + length);
        byte[] bytes = new byte[length]; in.readFully(bytes);
        BigInteger number = new BigInteger(bytes);
        return type == 1 ? number : new BigDecimal(number, scale);
    }

    @Override public int pageCount() { return offsets.size(); }
    @Override public Path path() { return path; }

    @Override public void close() {
        IOException failure = null;
        try { file.close(); } catch (IOException exception) { failure = exception; }
        try { Files.deleteIfExists(path); } catch (IOException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        }
        references.clear(); referenceIds.clear(); offsets.clear();
        if (failure != null) throw failed("删除", failure);
    }

    private static UncheckedIOException failed(String operation, IOException failure) {
        com.mojang.logging.LogUtils.getLogger().error("低内存规划日志{}失败", operation, failure);
        return new UncheckedIOException("Planning journal " + operation + " failed", failure);
    }
}
