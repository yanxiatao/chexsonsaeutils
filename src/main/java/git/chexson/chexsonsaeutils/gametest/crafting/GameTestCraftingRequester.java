package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.helpers.MachineSource;
import com.google.common.collect.ImmutableSet;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GameTestCraftingRequester implements ICraftingRequester {

    private final AENetworkedBlockEntity host;
    private final IActionSource actionSource;
    private final AcceptancePolicy acceptancePolicy;
    private final Map<AEItemKey, Long> acceptedOutputs = new LinkedHashMap<>();
    @Nullable
    private ICraftingLink activeLink;

    public GameTestCraftingRequester(AENetworkedBlockEntity host) {
        this(host, AcceptancePolicy.acceptAll());
    }

    public GameTestCraftingRequester(AENetworkedBlockEntity host, AcceptancePolicy acceptancePolicy) {
        this.host = host;
        this.actionSource = new MachineSource(host);
        this.acceptancePolicy = acceptancePolicy == null ? AcceptancePolicy.acceptAll() : acceptancePolicy;
    }

    public IActionSource getActionSource() {
        return actionSource;
    }

    public void trackLink(@Nullable ICraftingLink link) {
        this.activeLink = link;
    }

    public long countAcceptedOutput(AEItemKey key) {
        return acceptedOutputs.getOrDefault(key, 0L);
    }

    public boolean isJobFinished() {
        return activeLink != null && activeLink.isDone();
    }

    public boolean isJobCanceled() {
        return activeLink != null && activeLink.isCanceled();
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return activeLink == null ? ImmutableSet.of() : ImmutableSet.of(activeLink);
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        long accepted = Math.min(
                Math.max(0L, acceptancePolicy.accept(what, amount, mode)),
                Math.max(0L, amount)
        );
        if (mode == Actionable.MODULATE && what instanceof AEItemKey itemKey && accepted > 0L) {
            acceptedOutputs.merge(itemKey, accepted, Long::sum);
        }
        return accepted;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        this.activeLink = link;
    }

    @Override
    public IGridNode getActionableNode() {
        return host.getMainNode().getNode();
    }

    @FunctionalInterface
    interface AcceptancePolicy {
        long accept(AEKey what, long amount, Actionable mode);

        static AcceptancePolicy acceptAll() {
            return (what, amount, mode) -> Math.max(0L, amount);
        }
    }
}
