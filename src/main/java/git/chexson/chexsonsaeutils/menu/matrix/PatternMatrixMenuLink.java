package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntriesPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixMatchesPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStacksPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixServerTarget;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixTarget;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixVisibleRequestPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单侧的装配矩阵胶水：服务端按批下发摘要、按需回真栈、按位图回搜索结果；
 * 客户端只维护一份 `PatternMatrixClientView`。
 *
 * <p>两台机器的菜单共用这个类，机器差异全部收敛在 {@link PatternMatrixSource} 的
 * 展示栈/状态码/搜索口径里。</p>
 */
public final class PatternMatrixMenuLink implements PatternMatrixTarget, PatternMatrixServerTarget {

    /** 单个真栈包的条数上限：样板本体带着完整配方，必须分包才撑不爆连接。 */
    private static final int REAL_STACKS_PER_PACKET = 64;

    private final AEBaseMenu menu;
    private final PatternMatrixSource source;
    private final PatternMatrixClientView view = new PatternMatrixClientView();
    private final PatternMatrixServerSync sync = new PatternMatrixServerSync();
    private int announcedTotalSlots = -1;

    public PatternMatrixMenuLink(AEBaseMenu menu, PatternMatrixSource source) {
        this.menu = menu;
        this.source = source;
    }

    // ---------- 服务端 ----------

    /** 由菜单的 broadcastChanges 每 tick 调用，分批推送变化格子。 */
    public void serverBroadcastChanges() {
        if (menu.isClientSide()) {
            return;
        }
        announceTotalSlots();
        sync.broadcastChanges(source, this::sendEntries);
    }

    /**
     * 单独宣告总槽数：空机器一条变化都扫不出来，客户端必须靠这帧知道容量才能画出放样用的空行。
     */
    private void announceTotalSlots() {
        int totalSlots = source.patternMatrixTotalSlots();
        if (totalSlots == announcedTotalSlots) {
            return;
        }
        announcedTotalSlots = totalSlots;
        sendToOwner(new PatternMatrixEntriesPayload(menu.containerId, totalSlots, List.of()));
    }

    /** 菜单的 {@code doAction} 按 extraId 分流到这里，落实后立即回显，避免客户端等到下一 tick 才看到格子变化。 */
    public void handleMatrixClick(InventoryAction action, int globalSlot, ServerPlayer player) {
        if (menu.isClientSide() || player.containerMenu != menu) {
            return;
        }
        PatternMatrixInteraction.doAction(
                source,
                action,
                globalSlot,
                player,
                () -> player.containerMenu.getCarried(),
                player.containerMenu::setCarried
        );
        serverBroadcastChanges();
    }

    @Override
    public void handleMatrixSearch(String query, ServerPlayer player) {
        if (menu.isClientSide() || player.containerMenu != menu) {
            return;
        }
        if (query == null || query.isBlank()) {
            sendToOwner(new PatternMatrixMatchesPayload(menu.containerId, 0, List.of()));
            return;
        }
        long[] bits = new long[PatternMatrixSearch.bitsetSize(source.patternMatrixTotalSlots())];
        int count = source.patternMatrixSearchMatches(query, bits);
        sendToOwner(PatternMatrixMatchesPayload.of(menu.containerId, count, bits));
    }

    @Override
    public void handleMatrixVisibleRequest(int[] globalSlots, ServerPlayer player) {
        if (menu.isClientSide() || player.containerMenu != menu) {
            return;
        }
        List<PatternMatrixRealStack> stacks = collectRealStacks(globalSlots);
        for (int from = 0; from < stacks.size(); from += REAL_STACKS_PER_PACKET) {
            int to = Math.min(stacks.size(), from + REAL_STACKS_PER_PACKET);
            sendToOwner(new PatternMatrixRealStacksPayload(
                    menu.containerId,
                    List.copyOf(stacks.subList(from, to))
            ));
        }
    }

    private List<PatternMatrixRealStack> collectRealStacks(int[] globalSlots) {
        int size = source.patternMatrixTotalSlots();
        List<PatternMatrixRealStack> stacks = new ArrayList<>(globalSlots.length);
        for (int slot : globalSlots) {
            if (slot < 0 || slot >= size) {
                continue;
            }
            ItemStack stored = source.patternMatrixInventory().getStackInSlot(slot);
            if (!stored.isEmpty()) {
                stacks.add(new PatternMatrixRealStack(slot, stored.copy()));
            }
        }
        return stacks;
    }

    private void sendEntries(List<PatternMatrixEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sendToOwner(new PatternMatrixEntriesPayload(
                menu.containerId,
                source.patternMatrixTotalSlots(),
                entries
        ));
    }

    private void sendToOwner(CustomPacketPayload payload) {
        if (menu.getPlayer() instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, payload);
        }
    }

    // ---------- 客户端 ----------

    @Override
    public boolean matchesContainerId(int containerId) {
        return menu.containerId == containerId;
    }

    @Override
    public void applyMatrixEntries(int totalSlots, List<PatternMatrixEntry> entries) {
        view.resize(totalSlots);
        view.apply(entries);
    }

    @Override
    public void applyMatrixMatches(long[] matchBits, int matchCount) {
        view.applyMatches(matchBits, matchCount);
    }

    @Override
    public void applyMatrixRealStacks(List<PatternMatrixRealStack> realStacks) {
        view.applyRealStacks(realStacks);
    }

    @Override
    public PatternMatrixClientView matrixView() {
        return view;
    }

    /** 界面索取可见行的样板本体。 */
    public void requestVisibleRealStacks(int[] globalSlots) {
        if (!menu.isClientSide() || globalSlots.length == 0) {
            return;
        }
        List<Integer> slots = new ArrayList<>(globalSlots.length);
        for (int slot : globalSlots) {
            slots.add(slot);
        }
        PacketDistributor.sendToServer(new PatternMatrixVisibleRequestPayload(menu.containerId, slots));
    }

    /** 界面把搜索词交给服务端，命中结果按位图回包。 */
    public void requestSearch(String query) {
        if (!menu.isClientSide()) {
            return;
        }
        PacketDistributor.sendToServer(PatternMatrixSearchPayload.of(menu.containerId, query));
    }
}
