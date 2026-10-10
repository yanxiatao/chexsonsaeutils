package git.chexson.chexsonsaeutils.terminal.runtime;
import appeng.api.networking.IGrid;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import net.minecraft.server.MinecraftServer;
import java.util.WeakHashMap;
/** 同一网格每刷新周期共享一次采集，弱键不延长网格生命期。 */
public final class TerminalSnapshotCache {
    private static final WeakHashMap<IGrid, Cached> CACHE = new WeakHashMap<>();
    private static final WeakHashMap<IGrid, Integer> USERS = new WeakHashMap<>();
    public static void acquire(IGrid grid) { USERS.merge(grid, 1, Integer::sum); }
    public static void release(IGrid grid) {
        var count = USERS.get(grid);
        if (count == null) throw new IllegalStateException("未注册的精确终端订阅");
        if (count == 1) { USERS.remove(grid); CACHE.remove(grid); } else USERS.put(grid, count - 1);
    }
    private TerminalSnapshotCache() {}
    public static TerminalInventorySnapshot read(IGrid grid, MinecraftServer server, TerminalInventoryReader reader) {
        long tick = server.overworld().getGameTime() / 5;
        var cached = CACHE.get(grid);
        if (cached == null || tick != cached.tick) {
            cached = new Cached(tick, reader.read(grid, server)); CACHE.put(grid, cached);
        }
        return cached.snapshot;
    }
    public static void clear() { CACHE.clear(); USERS.clear(); }
    private record Cached(long tick, TerminalInventorySnapshot snapshot) {}
}
