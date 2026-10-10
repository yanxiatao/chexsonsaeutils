package git.chexson.chexsonsaeutils.client.integration.neoecoae;
import appeng.menu.me.common.MEStorageMenu;
import appeng.client.gui.me.common.MEStorageScreen;
import git.chexson.chexsonsaeutils.network.terminal.*;
import git.chexson.chexsonsaeutils.terminal.sync.*;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
/** 复用 NeoEco 公开缓存主动兼容原生终端，无 mixin 和反射。 */
public final class NeoEcoTerminalClientCompat {
    private static MEStorageMenu owner;
    private static UUID session;
    private static NeoEcoTerminalProjection projection;
    private static TerminalInventoryViewImpl view;
    private static final TerminalFrameAssembler ASSEMBLER = new TerminalFrameAssembler();
    private NeoEcoTerminalClientCompat() {}
    public static void register() {
        NativeTerminalRouter.bindClient((player, chunk) -> receive(chunk));
        NeoForge.EVENT_BUS.addListener(NeoEcoTerminalClientCompat::tick);
        NeoForge.EVENT_BUS.addListener(NeoEcoTerminalClientCompat::beforeRender);
    }
    private static void tick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        var current = player != null && player.containerMenu instanceof MEStorageMenu menu
                && !(menu instanceof TerminalClientTarget) ? menu : null;
        if (current == owner) return;
        owner = current; projection = new NeoEcoTerminalProjectionImpl(); ASSEMBLER.clear(); view = new TerminalInventoryViewImpl(); session = UUID.randomUUID();
        if (owner != null) PacketDistributor.sendToServer(new TerminalRequestPayload(owner.containerId, session));
    }
    private static void receive(TerminalChunkPayload chunk) {
        if (owner == null || owner.containerId != chunk.containerId() || !chunk.session().equals(session)) return;
        try {
            var complete = ASSEMBLER.accept(chunk.revision(), chunk.index(), chunk.count(), chunk.data());
            if (complete == null) return;
            var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(complete), owner.getPlayer().registryAccess());
            try { if (!view.apply(TerminalFrameCodec.read(buf))) request(); }
            finally { buf.release(); }
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("NeoEco 兼容精确帧无效，请求全量", failure);
            ASSEMBLER.clear(); request();
        }
    }
    private static void request() { PacketDistributor.sendToServer(new TerminalRequestPayload(owner.containerId, session)); }
    private static void beforeRender(ScreenEvent.Render.Pre event) {
        if (owner == null || view == null || view.revision() == 0 || !(event.getScreen() instanceof MEStorageScreen<?> screen)
                || screen.getMenu() != owner) return;
        projection.refresh(owner, view);
    }
}
