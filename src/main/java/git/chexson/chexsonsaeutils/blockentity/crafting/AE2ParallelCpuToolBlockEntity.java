package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import appeng.api.util.IConfigManager;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.menu.ISubMenu;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class AE2ParallelCpuToolBlockEntity extends AENetworkedBlockEntity implements ITerminalHost {

    private final ParallelCraftingCpuCluster parallelCpuCluster = new ParallelCraftingCpuCluster(this);
    private final IConfigManager configManager = IConfigManager.builder(this::onConfigChanged)
            .registerSetting(Settings.CPU_SELECTION_MODE, CpuSelectionMode.ANY)
            .build();
    private final MEStorage terminalInventory = new SupplierStorage(() -> {
        IGrid grid = getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    });

    public AE2ParallelCpuToolBlockEntity(BlockPos pos, BlockState blockState) {
        super(Chexsonsaeutils.AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY.get(), pos, blockState);
        this.getMainNode().setIdlePowerUsage(0.0);
    }

    @Override
    public void onReady() {
        super.onReady();
        this.getMainNode().setVisualRepresentation(getMainMenuIcon());
        postCpuListChange();
    }

    @Override
    public void setRemoved() {
        postCpuListChange();
        super.setRemoved();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        postCpuListChange();
    }

    public ParallelCraftingCpuConfig.Settings getParallelCpuSettings() {
        return ParallelCraftingCpuConfig.current();
    }

    public ParallelCraftingCpuCluster getParallelCpuCluster() {
        return parallelCpuCluster;
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
    public ILinkStatus getLinkStatus() {
        return ILinkStatus.ofManagedNode(getMainNode());
    }

    @Override
    public IConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        configManager.writeToNBT(data, registries);
        parallelCpuCluster.writeToNBT(data, registries);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        configManager.readFromNBT(data, registries);
        parallelCpuCluster.readFromNBT(data, registries);
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
}
