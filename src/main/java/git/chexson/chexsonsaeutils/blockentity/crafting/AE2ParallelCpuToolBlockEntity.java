package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import appeng.api.util.IConfigManager;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.menu.ISubMenu;
import appeng.util.ConfigManager;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class AE2ParallelCpuToolBlockEntity extends AENetworkBlockEntity implements ITerminalHost {

    private final ConfigManager configManager;
    private final MEStorage terminalInventory = new SupplierStorage(() -> {
        IGrid grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    });
    private final ParallelCraftingCpuCluster parallelCpuCluster = new ParallelCraftingCpuCluster(this);

    public AE2ParallelCpuToolBlockEntity(BlockPos pos, BlockState blockState) {
        super(Chexsonsaeutils.AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY.get(), pos, blockState);
        this.configManager = new ConfigManager(this::onConfigChanged);
        this.configManager.registerSetting(Settings.CPU_SELECTION_MODE, CpuSelectionMode.ANY);
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return GridHelper.createManagedNode(this, new Listener())
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.0);
    }

    @Override
    public void onReady() {
        super.onReady();
        postCpuListChange();
    }

    @Override
    public void setRemoved() {
        postCpuListChange();
        super.setRemoved();
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        this.configManager.readFromNBT(data);
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        this.configManager.writeToNBT(data);
    }

    public ParallelCraftingCpuCluster getParallelCpuCluster() {
        return parallelCpuCluster;
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
        if (getMainNode().getNode() != null && getMainNode().getNode().getGrid() != null) {
            getMainNode().getNode().getGrid().postEvent(new GridCraftingCpuChange(getMainNode().getNode()));
        }
    }

    private static class Listener implements IGridNodeListener<AE2ParallelCpuToolBlockEntity> {
        @Override
        public void onSaveChanges(AE2ParallelCpuToolBlockEntity node, IGridNode gridNode) {
            node.saveChanges();
        }
        @Override
        public void onStateChanged(AE2ParallelCpuToolBlockEntity node, IGridNode gridNode, State state) {
            node.postCpuListChange();
        }
    }
}
