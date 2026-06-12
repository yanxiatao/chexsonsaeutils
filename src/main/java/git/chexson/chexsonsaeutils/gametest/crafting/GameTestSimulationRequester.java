package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.helpers.MachineSource;

public final class GameTestSimulationRequester implements ICraftingSimulationRequester {

    private final AENetworkedBlockEntity host;
    private final IActionSource actionSource;

    public GameTestSimulationRequester(AENetworkedBlockEntity host) {
        this.host = host;
        this.actionSource = new MachineSource(host);
    }

    @Override
    public IActionSource getActionSource() {
        return actionSource;
    }

    @Override
    public IGridNode getGridNode() {
        return host.getMainNode().getNode();
    }
}
