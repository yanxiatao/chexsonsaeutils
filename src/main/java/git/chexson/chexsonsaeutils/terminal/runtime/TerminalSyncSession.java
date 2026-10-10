package git.chexson.chexsonsaeutils.terminal.runtime;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.menu.me.common.MEStorageMenu;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import git.chexson.chexsonsaeutils.terminal.sync.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.Consumer;
/** 单菜单增量基线与有界分片发送；底层网格采集由共享缓存完成。 */
public final class TerminalSyncSession {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private final UUID nonce;
    private final boolean itemsOnly;
    private Map<AEKey, TerminalEntry> previous = Map.of();
    private Map<Integer, TerminalEntry> byId = Map.of();
    private final Map<AEKey, Integer> ids = new HashMap<>();
    private int nextId;
    private IGrid grid;
    private long revision, nextTick;
    private boolean full = true, active, covered, connectedPreviously;
    private boolean failed;
    private byte[] pending;
    private int offset;
    public TerminalSyncSession(UUID nonce) { this(nonce, true); }
    public TerminalSyncSession(UUID nonce, boolean itemsOnly) { this.nonce = nonce; this.itemsOnly = itemsOnly; }
    public UUID nonce() { return nonce; }
    public void requestFull() { full = true; pending = null; nextTick = 0; }
    public TerminalEntry entry(int id) {
        return byId.get(id);
    }
    public boolean active() { return active && !failed; }
    public boolean ownsGrid(IGrid current) { return grid == current && active(); }
    public void tick(MEStorageMenu menu, TerminalInventoryReader reader, Consumer<Chunk> sink) {
        var player = (ServerPlayer) menu.getPlayer();
        var node = menu.getGridNode();
        boolean connected = node != null && node.isOnline() && menu.getLinkStatus().connected();
        var currentGrid = connected ? node.getGrid() : null;
        if (currentGrid != grid) {
            if (grid != null) TerminalSnapshotCache.release(grid);
            grid = currentGrid;
            ids.clear(); byId = Map.of(); active = false;
            if (grid != null) TerminalSnapshotCache.acquire(grid);
            requestFull();
        }
        if (connected != connectedPreviously) { connectedPreviously = connected; requestFull(); }
        long tick = player.level().getGameTime();
        if (pending == null && tick >= nextTick) {
            nextTick = tick + 5;
            try {
                var snapshot = connected ? TerminalSnapshotCache.read(grid, player.server, reader)
                        : new TerminalInventorySnapshot(Map.of(), Map.of(), Set.of(), false);
                var keys = new HashSet<>(snapshot.amounts().keySet());
                keys.addAll(snapshot.unverified().keySet()); keys.addAll(snapshot.craftables());
                if (keys.size() > TerminalFrameCodec.MAX_ENTRIES) throw new IllegalArgumentException("终端库存条目超出预算");
                var updated = new HashMap<AEKey, TerminalEntry>();
                for (var key : keys) {
                    if ((itemsOnly && !(key instanceof AEItemKey)) || !menu.isKeyVisible(key)) continue;
                    int id = ids.computeIfAbsent(key, ignored -> { int assigned = nextId; nextId = Math.incrementExact(nextId); return assigned; });
                    updated.put(key, new TerminalEntry(id, key, snapshot.amounts().getOrDefault(key, StorageAmount.ZERO),
                            snapshot.craftables().contains(key), snapshot.unverified().get(key)));
                }
                var changes = updated.values().stream().filter(e -> full || !e.equals(previous.get(e.key()))).toList();
                var removed = previous.values().stream().filter(e -> !updated.containsKey(e.key())).map(TerminalEntry::id).toList();
                if (full || !changes.isEmpty() || !removed.isEmpty() || active != connected || covered != snapshot.covered()) {
                    var frame = new TerminalFrame(revision + 1, revision, full, connected, snapshot.covered(), changes, removed);
                    var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(256, TerminalFrameCodec.MAX_FRAME_BYTES), player.registryAccess());
                    try { TerminalFrameCodec.write(buf, frame); pending = new byte[buf.readableBytes()]; buf.readBytes(pending); }
                    finally { buf.release(); }
                    revision++; offset = 0; previous = Map.copyOf(updated); full = false;
                    var index = new HashMap<Integer, TerminalEntry>(); updated.values().forEach(e -> index.put(e.id(), e));
                    byId = Map.copyOf(index); ids.keySet().retainAll(updated.keySet());
                    active = connected; covered = snapshot.covered();
                }
                failed = false;
            } catch (RuntimeException failure) {
                if (!failed) LOGGER.error("精确终端采集失败: menu={}, session={}", menu.containerId, nonce, failure);
                failed = true; active = false; previous = Map.of(); byId = Map.of(); ids.clear(); full = true;
                var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(256, TerminalFrameCodec.MAX_FRAME_BYTES), player.registryAccess());
                try { TerminalFrameCodec.write(buf, new TerminalFrame(++revision, 0, true, false, false, List.of(), List.of()));
                    pending = new byte[buf.readableBytes()]; buf.readBytes(pending); offset = 0; }
                finally { buf.release(); }
            }
        }
        if (pending != null) {
            int index = offset / TerminalFrameCodec.CHUNK_BYTES;
            int count = (pending.length + TerminalFrameCodec.CHUNK_BYTES - 1) / TerminalFrameCodec.CHUNK_BYTES;
            int end = Math.min(pending.length, offset + TerminalFrameCodec.CHUNK_BYTES);
            sink.accept(new Chunk(nonce, revision, index, count, Arrays.copyOfRange(pending, offset, end)));
            offset = end; if (offset == pending.length) pending = null;
        }
    }
    /** 关闭菜单或替换订阅时释放共享快照。 */
    public void close() {
        if (grid != null) { TerminalSnapshotCache.release(grid); grid = null; }
        previous = Map.of(); byId = Map.of(); ids.clear(); pending = null; active = false;
    }
    public record Chunk(UUID nonce, long revision, int index, int count, byte[] data) {}
}
