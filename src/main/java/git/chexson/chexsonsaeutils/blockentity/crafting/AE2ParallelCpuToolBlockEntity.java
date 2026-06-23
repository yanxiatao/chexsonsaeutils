package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import appeng.api.util.IConfigManager;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.me.ManagedGridNode;
import appeng.menu.ISubMenu;
import appeng.api.orientation.BlockOrientation;
import appeng.util.ConfigManager;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Set;

public class AE2ParallelCpuToolBlockEntity extends AEBaseBlockEntity
        implements IGridConnectedBlockEntity, ITerminalHost {

    private final ManagedGridNode mainNode;
    private final ConfigManager configManager;
    private final MEStorage terminalInventory = new SupplierStorage(() -> {
        IGrid grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    });

    public AE2ParallelCpuToolBlockEntity(BlockPos pos, BlockState blockState) {
        super(Chexsonsaeutils.AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY.get(), pos, blockState);
        this.mainNode = new ManagedGridNode(this, new Listener())
                .setIdlePowerUsage(0.0)
                .setInWorldNode(true)
                .setFlags(GridFlags.REQUIRE_CHANNEL);
        this.configManager = new ConfigManager(this::onConfigChanged);
        this.configManager.registerSetting(Settings.CPU_SELECTION_MODE, CpuSelectionMode.ANY);
    }

    @Override
    public void onReady() {
        super.onReady();
        this.mainNode.create(getLevel(), getBlockPos());
    }

    @Override
    public void setRemoved() {
        this.mainNode.destroy();
        super.setRemoved();
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        this.mainNode.loadFromNBT(data);
        this.configManager.readFromNBT(data);
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        this.mainNode.saveToNBT(data);
        this.configManager.writeToNBT(data);
    }

    @Override
    public ManagedGridNode getMainNode() {
        return mainNode;
    }

    @Override
    public void setOwner(Player player) {
        mainNode.setOwningPlayer(player);
    }

    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        return EnumSet.allOf(Direction.class);
    }

    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    @Override
    public void saveChanges() {
        super.saveChanges();
    }

    // IGridConnectedBlockEntity default methods: getGridNode(), getGridNode(Direction)
    // are implemented as default methods in the interface and inherited automatically.

    // ponytail: cluster will be set during Sprint 2 grid registration
    private ParallelCraftingCpuCluster parallelCpuCluster;

    public ParallelCraftingCpuCluster getParallelCpuCluster() {
        return parallelCpuCluster;
    }

    public void setParallelCpuCluster(ParallelCraftingCpuCluster cluster) {
        this.parallelCpuCluster = cluster;
    }

    public ParallelCraftingCpuConfig.Settings getParallelCpuSettings() {
        return ParallelCraftingCpuConfig.current();
    }

    public IGrid getGrid() {
        return getMainNode().getGrid();
    }

    public boolean isParallelCpuProviderActive() {
        return getMainNode().isReady()
                && getMainNode().getNode() != null
                && getGrid() != null;
    }

    public boolean canProcessParallelCpuJobs() {
        return isParallelCpuProviderActive() && getMainNode().isActive();
    }

    public CpuSelectionMode getSelectionMode() {
        return configManager.getSetting(Settings.CPU_SELECTION_MODE);
    }

    @Override
    public MEStorage getInventory() {
        return terminalInventory;
    }

    @Override
    public IConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        player.closeContainer();
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return Chexsonsaeutils.AE2_PARALLEL_CPU_TOOL_ITEM.get().getDefaultInstance();
    }

    public void refreshParallelCpuProvider() {
        postCpuListChange();
    }

    private void onConfigChanged(IConfigManager manager, Setting<?> setting) {
        if (setting == Settings.CPU_SELECTION_MODE) {
            postCpuListChange();
        }
        saveChanges();
    }

    private void postCpuListChange() {
        IGridNode node = mainNode.getNode();
        if (node != null && node.getGrid() != null) {
            node.getGrid().postEvent(new GridCraftingCpuChange(node));
        }
    }

    private static class Listener implements IGridNodeListener<AE2ParallelCpuToolBlockEntity> {
        @Override
        public void onSaveChanges(AE2ParallelCpuToolBlockEntity node, IGridNode gridNode) {
            node.saveChanges();
        }
    }
}
