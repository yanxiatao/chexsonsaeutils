package git.chexson.chexsonsaeutils.crafting.remote;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RemoteLedgerTest {
    @Test void exactWideDemandAndPartialDeliverySurviveRestore() {
        var ledger = new RemoteLedgerImpl<String>(4, 100);
        var demand = BigInteger.TEN.pow(30);
        assertTrue(ledger.enqueue("iron", demand));
        var id = UUID.randomUUID();
        ledger.submitted(id, "iron", 70);
        assertEquals(70, ledger.receive(id, "iron", 90, true));
        assertTrue(ledger.buffered().isEmpty());
        assertEquals(40, ledger.receive(id, "iron", 40, false));
        assertEquals(30, ledger.batch("iron", 100));
        ledger.delivered("iron", 15);
        var restored = RemoteLedgerImpl.restore(4, 100, ledger.pending(), ledger.buffered(), ledger.reservations());
        assertEquals(30, restored.receive(id, "iron", 80, false));
        restored.close(id, false);
        assertEquals(BigInteger.valueOf(55), restored.buffered().get("iron"));
        assertEquals(demand.subtract(BigInteger.valueOf(70)), restored.pending().get("iron"));
        restored.delivered("iron", 55);
        assertTrue(restored.buffered().isEmpty());
        assertEquals(100, restored.batch("iron", 100));
    }
    @Test void completionDoesNotManufactureOutputAndCancelledSourceOnlyRetriesDeficit() {
        var ledger = new RemoteLedgerImpl<String>(2, 100);
        ledger.enqueue("fluid", BigInteger.valueOf(80));
        var id = UUID.randomUUID();
        ledger.submitted(id, "fluid", 80);
        ledger.receive(id, "fluid", 20, false);
        ledger.close(id, true);
        assertEquals(BigInteger.valueOf(20), ledger.buffered().get("fluid"));
        assertEquals(BigInteger.valueOf(60), ledger.pending().get("fluid"));
        assertEquals(0, ledger.receive(id, "fluid", 60, false));
        ledger.cancelPending();
        assertEquals(BigInteger.valueOf(20), ledger.buffered().get("fluid"));
    }
    @Test void keysInFlightCountTowardCapacityAndMismatchedReceiptsCannotStealReservation() {
        var ledger = new RemoteLedgerImpl<String>(1, 10);
        ledger.enqueue("a", BigInteger.TEN);
        var id = UUID.randomUUID();
        ledger.submitted(id, "a", 10);
        assertFalse(ledger.enqueue("b", BigInteger.ONE));
        assertEquals(0, ledger.receive(id, "b", 10, false));
        assertEquals(0, ledger.batch("a", 10));
        assertThrows(IllegalStateException.class, () -> ledger.submitted(id, "a", 1));
        ledger.receive(id, "a", 10, false);
        assertFalse(ledger.enqueue("b", BigInteger.ONE));
        assertThrows(IllegalStateException.class, () -> ledger.delivered("a", 11));
    }
}
