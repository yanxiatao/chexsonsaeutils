package git.chexson.chexsonsaeutils.menu.terminal;
import appeng.api.storage.ITerminalHost;
import appeng.helpers.InventoryAction;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.me.common.*;
import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.terminal.storage.TerminalInventoryAccess;
import git.chexson.chexsonsaeutils.network.terminal.*;
import git.chexson.chexsonsaeutils.terminal.runtime.TerminalSyncSession;
import git.chexson.chexsonsaeutils.terminal.sync.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** 复用原生合成栏与交易逻辑；网络展示使用独立会话和精确帧。 */
public final class BigIntCraftingTerminalMenu extends CraftingTermMenu
        implements TerminalServerTarget, TerminalClientTarget {
    public static final MenuType<BigIntCraftingTerminalMenu> TYPE = MenuTypeBuilder
            .create(BigIntCraftingTerminalMenu::new, ITerminalHost.class)
            .buildUnregistered(ResourceLocation.parse("chexsonsaeutils:bigint_crafting_terminal"));
    private final UUID clientSession = UUID.randomUUID();
    private final TerminalInventoryViewImpl view = new TerminalInventoryViewImpl();
    private final TerminalFrameAssembler assembler = new TerminalFrameAssembler();
    private TerminalSyncSession sync;
    public BigIntCraftingTerminalMenu(int id, Inventory inventory, ITerminalHost host) {
        super(TYPE, id, inventory, host, true);
        // 接收原生列表以满足 JEI/EMI 查询，但它不覆盖自有精确数量视图。
        if (isClientSide()) setClientRepo(new NativeRecipeRepo());
    }
    public TerminalInventoryView view() { return view; }
    public void requestSnapshot() {
        if (isClientSide()) PacketDistributor.sendToServer(new TerminalRequestPayload(containerId, clientSession));
    }
    @Override public void requestTerminalSnapshot(UUID session) {
        if (sync == null || !sync.nonce().equals(session)) {
            if (sync != null) sync.close();
            sync = new TerminalSyncSession(session, false);
        }
        else sync.requestFull();
    }
    @Override public void removed(net.minecraft.world.entity.player.Player player) {
        if (sync != null) { sync.close(); sync = null; }
        super.removed(player);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (isServerSide() && sync != null) sync.tick(this, TerminalInventoryAccess.reader(), chunk ->
                PacketDistributor.sendToPlayer((ServerPlayer) getPlayer(), new TerminalChunkPayload(containerId,
                        chunk.nonce(), chunk.revision(), chunk.index(), chunk.count(), chunk.data())));
    }
    @Override public void receiveTerminalChunk(UUID session, long revision, int index, int count, byte[] data) {
        if (!clientSession.equals(session)) return;
        try {
            var complete = assembler.accept(revision, index, count, data);
            if (complete == null) return;
            var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(complete), getPlayer().registryAccess());
            try { if (!view.apply(TerminalFrameCodec.read(buf))) requestSnapshot(); }
            finally { buf.release(); }
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("精确终端帧无效，请求全量同步", failure);
            assembler.clear(); requestSnapshot();
        }
    }
    public void interact(int id, InventoryAction action) {
        PacketDistributor.sendToServer(new TerminalInteractionPayload(containerId, clientSession, id, action));
    }
    @Override public void interactTerminal(UUID session, int id, InventoryAction action) {
        if (sync == null || !sync.nonce().equals(session) || !sync.active() || !canInteractWithGrid()) return;
        var node = getGridNode();
        if (node == null || !node.isActive() || !sync.ownsGrid(node.getGrid())) return;
        if (id == -1) {
            if (action == InventoryAction.PICKUP_OR_SET_DOWN || action == InventoryAction.SPLIT_OR_PLACE_SINGLE)
                handleNetworkInteraction((ServerPlayer) getPlayer(), null, action);
            return;
        }
        var entry = sync.entry(id);
        if (entry != null && isKeyVisible(entry.key())) handleNetworkInteraction((ServerPlayer) getPlayer(), entry.key(), action);
    }
    private final class NativeRecipeRepo implements IClientRepo {
        private final Map<Long, GridInventoryEntry> entries = new HashMap<>();
        @Override public void handleUpdate(boolean fullUpdate, List<GridInventoryEntry> updates) {
            if (fullUpdate) entries.clear();
            for (var entry : updates) {
                if (!entry.isMeaningful()) entries.remove(entry.getSerial());
                else {
                    var prior = entries.get(entry.getSerial());
                    if (entry.getWhat() != null) entries.put(entry.getSerial(), entry);
                    else if (prior != null) entries.put(entry.getSerial(), new GridInventoryEntry(entry.getSerial(),
                            prior.getWhat(), entry.getStoredAmount(), entry.getRequestableAmount(), entry.isCraftable()));
                }
            }
        }
        @Override public Set<GridInventoryEntry> getAllEntries() { return Set.copyOf(entries.values()); }
        @Override public Collection<GridInventoryEntry> getByIngredient(Ingredient ingredient) {
            return entries.values().stream().filter(e -> e.getWhat() instanceof AEItemKey item
                    && ingredient.test(item.toStack())).toList();
        }
    }
}
