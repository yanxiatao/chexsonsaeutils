package git.chexson.chexsonsaeutils.crafting.remote;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.*;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteRoutesTest {
    private static final AEKey KEY = AEItemKey.of(Items.IRON_INGOT);
    @Test void sameOutputRoutesByPriorityAndLoadWithoutCrossingReceiverBoundary() {
        var receiver = FakeGrid.builder().build();
        var otherReceiver = FakeGrid.builder().build();
        var first = new Endpoint(receiver, 10, true);
        var second = new Endpoint(receiver, 10, true);
        var foreign = new Endpoint(otherReceiver, 100, true);
        var offline = new Endpoint(receiver, 1000, false);
        var devices = List.of(first, second, foreign, offline);
        devices.forEach(RemoteRoutes::register);
        try {
            assertTrue(RemoteRoutes.enqueue(receiver, KEY, BigInteger.TEN));
            assertTrue(RemoteRoutes.enqueue(receiver, KEY, BigInteger.ONE));
            assertEquals(BigInteger.valueOf(11), first.accepted.add(second.accepted));
            assertTrue(first.accepted.signum() > 0 && second.accepted.signum() > 0);
            assertEquals(BigInteger.ZERO, foreign.accepted);
            assertEquals(BigInteger.ZERO, offline.accepted);
            assertEquals(3, RemoteRoutes.sources(receiver, KEY).size());
            first.reject = true; second.reject = true;
            assertFalse(RemoteRoutes.enqueue(receiver, KEY, BigInteger.ONE));
            assertEquals(BigInteger.ZERO, foreign.accepted);
        } finally { devices.forEach(RemoteRoutes::unregister); }
    }
    @Test void successfulUnsubmittedRerouteMovesOnlyThatDeficitAndKeepsAlreadyReceivedItems() {
        var old = new RemoteLedgerImpl<String>(2, 100);
        var next = new RemoteLedgerImpl<String>(2, 100);
        old.enqueue("target", BigInteger.valueOf(50));
        var id = UUID.randomUUID(); old.submitted(id, "target", 20); old.receive(id, "target", 10, false);
        assertTrue(next.enqueue("target", BigInteger.valueOf(30)));
        old.movePending("target", BigInteger.valueOf(30));
        assertTrue(old.pending().isEmpty());
        assertEquals(BigInteger.TEN, old.buffered().get("target"));
        assertEquals(10, old.reservations().get(id).remaining());
        assertEquals(BigInteger.valueOf(30), next.pending().get("target"));
        assertThrows(IllegalStateException.class, () -> old.movePending("target", BigInteger.ONE));
    }
    @Test void reroutingSkipsRejectingCandidateAndTriesRemainingSources() {
        var receiver = FakeGrid.builder().build();
        var original = new Endpoint(receiver, 100, true);
        var refusing = new Endpoint(receiver, 50, true); refusing.reject = true;
        var available = new Endpoint(receiver, 10, true);
        var devices = List.of(original, refusing, available); devices.forEach(RemoteRoutes::register);
        try {
            assertTrue(RemoteRoutes.enqueueAlternative(receiver, KEY, original.identity(), BigInteger.TEN));
            assertEquals(BigInteger.ZERO, original.accepted);
            assertEquals(BigInteger.ZERO, refusing.accepted);
            assertEquals(BigInteger.TEN, available.accepted);
            available.reject = true;
            assertFalse(RemoteRoutes.enqueueAlternative(receiver, KEY, original.identity(), BigInteger.ONE));
        } finally { devices.forEach(RemoteRoutes::unregister); }
    }
    private static final class Endpoint implements RemoteEndpoint {
        final UUID id = UUID.randomUUID(); final IGrid receiver; final IGrid source = FakeGrid.builder().build();
        final int priority; final boolean ready; boolean reject; BigInteger accepted = BigInteger.ZERO;
        Endpoint(IGrid receiver, int priority, boolean ready) { this.receiver = receiver; this.priority = priority; this.ready = ready; }
        @Override public UUID identity() { return id; }
        @Override public String label() { return id.toString(); }
        @Override public IGrid sourceGrid() { return source; }
        @Override public IGrid receiverGrid() { return receiver; }
        @Override public Set<AEKey> outputs() { return Set.of(KEY); }
        @Override public boolean ready() { return ready; }
        @Override public int priority() { return priority; }
        @Override public BigInteger load() { return accepted; }
        @Override public boolean accept(AEKey key, BigInteger amount) {
            if (reject || !ready || !outputs().contains(key)) return false;
            accepted = accepted.add(amount); return true;
        }
        @Override public ICraftingPlan preview(AEKey key, BigInteger amount, KeyCounter stock) {
            return RemotePlanning.plan(null, source.getCraftingService(), key, amount, source, stock);
        }
        @Override public String status() { return ready ? "就绪" : "离线"; }
        @Override public Level world() { return null; }
    }
}
