package git.chexson.chexsonsaeutils.network.terminal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import java.util.function.BiConsumer;
/** 原生终端可选接入由装配层绑定，载荷不引用 NeoEco 或客户端类型。 */
public final class NativeTerminalRouter {
    private static BiConsumer<ServerPlayer, UUID> server;
    private static BiConsumer<Player, TerminalChunkPayload> client;
    private NativeTerminalRouter() {}
    public static void bindServer(BiConsumer<ServerPlayer, UUID> callback) { server = callback; }
    public static void bindClient(BiConsumer<Player, TerminalChunkPayload> callback) { client = callback; }
    public static void request(ServerPlayer player, UUID session) { if (server != null) server.accept(player, session); }
    public static void receive(Player player, TerminalChunkPayload chunk) { if (client != null) client.accept(player, chunk); }
}
