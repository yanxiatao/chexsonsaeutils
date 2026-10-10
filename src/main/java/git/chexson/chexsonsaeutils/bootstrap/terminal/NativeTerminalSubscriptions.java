package git.chexson.chexsonsaeutils.bootstrap.terminal;
import appeng.menu.me.common.MEStorageMenu;
import git.chexson.chexsonsaeutils.network.terminal.*;
import git.chexson.chexsonsaeutils.terminal.runtime.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
/** 原生终端主动同步订阅，关菜单释放引用。 */
public final class NativeTerminalSubscriptions {
    private static final Map<ServerPlayer, Subscription> SUBSCRIPTIONS = new HashMap<>();
    private NativeTerminalSubscriptions() {}
    public static void register() {
        NativeTerminalRouter.bindServer(NativeTerminalSubscriptions::request);
        NeoForge.EVENT_BUS.addListener(NativeTerminalSubscriptions::tick);
        NeoForge.EVENT_BUS.addListener(NativeTerminalSubscriptions::stopped);
    }
    private static void request(ServerPlayer player, UUID session) {
        if (!(player.containerMenu instanceof MEStorageMenu menu) || menu instanceof TerminalServerTarget) return;
        var previous = SUBSCRIPTIONS.get(player);
        if (previous != null && previous.menu == menu && previous.session.nonce().equals(session)) previous.session.requestFull();
        else {
            if (previous != null) previous.session.close();
            SUBSCRIPTIONS.put(player, new Subscription(menu, new TerminalSyncSession(session, false)));
        }
    }
    private static void tick(ServerTickEvent.Post event) {
        var iterator = SUBSCRIPTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var player = entry.getKey(); var subscription = entry.getValue();
            if (player.hasDisconnected() || player.containerMenu != subscription.menu) { subscription.session.close(); iterator.remove(); continue; }
            subscription.session.tick(subscription.menu, TerminalStorageAssembly.reader(), chunk ->
                    PacketDistributor.sendToPlayer(player, new TerminalChunkPayload(subscription.menu.containerId,
                            chunk.nonce(), chunk.revision(), chunk.index(), chunk.count(), chunk.data())));
        }
    }
    private static void stopped(ServerStoppedEvent event) { SUBSCRIPTIONS.values().forEach(s -> s.session.close()); SUBSCRIPTIONS.clear(); TerminalSnapshotCache.clear(); }
    private record Subscription(MEStorageMenu menu, TerminalSyncSession session) {}
}
