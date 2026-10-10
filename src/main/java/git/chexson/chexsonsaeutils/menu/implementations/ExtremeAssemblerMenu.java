package git.chexson.chexsonsaeutils.menu.implementations;

import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.MenuTypeBuilder;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.ExtremeAssemblerBlockEntity;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixInteraction;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixMenuLink;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixRealStack;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixServerTarget;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixTarget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

public class ExtremeAssemblerMenu extends AEBaseMenu
        implements PatternMatrixTarget, PatternMatrixServerTarget {

    public static final MenuType<ExtremeAssemblerMenu> TYPE = MenuTypeBuilder
            .create(ExtremeAssemblerMenu::new, ExtremeAssemblerBlockEntity.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":extreme_assembler")
            ));

    private final ExtremeAssemblerBlockEntity host;
    private final PatternMatrixMenuLink matrix;

    public ExtremeAssemblerMenu(int id, Inventory playerInventory, ExtremeAssemblerBlockEntity host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        this.matrix = new PatternMatrixMenuLink(this, host);
        createPlayerInventorySlots(playerInventory);
    }

    public ExtremeAssemblerBlockEntity getHost() {
        return host;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            matrix.serverBroadcastChanges();
        }
        super.broadcastChanges();
    }

    // ---------- 客户端视图 ----------

    @Override
    public boolean matchesContainerId(int containerId) {
        return matrix.matchesContainerId(containerId);
    }

    @Override
    public void applyMatrixEntries(int totalSlots, List<PatternMatrixEntry> entries) {
        matrix.applyMatrixEntries(totalSlots, entries);
    }

    @Override
    public void applyMatrixMatches(long[] matchBits, int matchCount) {
        matrix.applyMatrixMatches(matchBits, matchCount);
    }

    @Override
    public void applyMatrixRealStacks(List<PatternMatrixRealStack> realStacks) {
        matrix.applyMatrixRealStacks(realStacks);
    }

    @Override
    public PatternMatrixClientView matrixView() {
        return matrix.matrixView();
    }

    /** 界面与矩阵面板共用的同步胶水。 */
    public PatternMatrixMenuLink matrixLink() {
        return matrix;
    }

    // ---------- 服务端请求 ----------

    @Override
    public void doAction(ServerPlayer player, InventoryAction action, int slot, long id) {
        if (id == PatternMatrixInteraction.MATRIX_PATTERN_ID) {
            matrix.handleMatrixClick(action, slot, player);
            return;
        }
        super.doAction(player, action, slot, id);
    }

    @Override
    public void handleMatrixSearch(String query, ServerPlayer player) {
        matrix.handleMatrixSearch(query, player);
    }

    @Override
    public void handleMatrixVisibleRequest(int[] globalSlots, ServerPlayer player) {
        matrix.handleMatrixVisibleRequest(globalSlots, player);
    }

    /** 背包 shift 点击样板：直接写进全量样板库存的第一个可用槽。 */
    @Override
    protected int transferStackToMenu(ItemStack input) {
        return PatternMatrixInteraction.shiftInsertIntoMatrix(host, input);
    }
}
