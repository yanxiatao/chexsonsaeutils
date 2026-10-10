package git.chexson.chexsonsaeutils.network.planning;

import git.chexson.chexsonsaeutils.config.PlanningServerConfig;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanningOrderProtocolTest {
    @Test
    void roundTripsStableEnumNames() {
        List<String> encoded = PlanningOrderProtocol.encode(PlanningServerConfig.DEFAULT_ORDER);

        List<PlanningAlgorithmId> decoded = PlanningOrderProtocol.decode(encoded);

        assertEquals(PlanningServerConfig.DEFAULT_ORDER, decoded);
        assertEquals("FROZEN_ROUTE", encoded.getFirst());
    }

    @Test
    void rejectsWrongCountDuplicateAndUnknownIds() {
        assertThrows(IllegalArgumentException.class, () -> PlanningOrderProtocol.decode(
                List.of("FROZEN_ROUTE", "GRAPH", "FAST")));
        assertThrows(IllegalArgumentException.class, () -> PlanningOrderProtocol.decode(
                List.of("FROZEN_ROUTE", "GRAPH", "GRAPH", "NATIVE")));
        assertThrows(IllegalArgumentException.class, () -> PlanningOrderProtocol.decode(
                List.of("FROZEN_ROUTE", "GRAPH", "FAST", "UNKNOWN")));
    }

    @Test
    void mapsServiceStatusWithoutOrdinalProtocol() {
        assertEquals(PlanningOrderStatePayload.Result.UPDATED,
                PlanningOrderUpdatePayload.mapResult(
                        git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService.UpdateStatus.UPDATED));
        assertEquals(PlanningAlgorithmId.FAST,
                PlanningOrderProtocol.decode(
                        PlanningOrderProtocol.encode(PlanningServerConfig.DEFAULT_ORDER)).get(2));
    }
}
