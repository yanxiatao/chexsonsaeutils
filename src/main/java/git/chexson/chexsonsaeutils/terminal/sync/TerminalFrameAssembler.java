package git.chexson.chexsonsaeutils.terminal.sync;
import java.io.ByteArrayOutputStream;
/** 顺序可靠通道上的有界帧组装；只缓存一帧，缺片时请求全量而不提交残缺数据。 */
public final class TerminalFrameAssembler {
    private ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private long revision = -1;
    private int next, total;
    public byte[] accept(long frame, int index, int count, byte[] data) {
        if (count <= 0 || count > (TerminalFrameCodec.MAX_FRAME_BYTES / TerminalFrameCodec.CHUNK_BYTES + 1)
                || data.length > TerminalFrameCodec.CHUNK_BYTES) throw new IllegalArgumentException("库存分片超出预算");
        if (index == 0) { bytes = new ByteArrayOutputStream(); revision = frame; next = 0; total = count; }
        if (frame != revision || count != total || index != next || index >= count) throw new IllegalStateException("库存帧缺少分片");
        if (bytes.size() + data.length > TerminalFrameCodec.MAX_FRAME_BYTES) throw new IllegalArgumentException("库存帧过大");
        bytes.writeBytes(data); next++;
        if (next == count) { var result = bytes.toByteArray(); clear(); return result; }
        return null;
    }
    public void clear() { bytes = new ByteArrayOutputStream(); revision = -1; next = 0; total = 0; }
}
