package git.chexson.chexsonsaeutils.parts.playersimulation;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
import static git.chexson.chexsonsaeutils.parts.playersimulation.SimulationSettings.Selection.*;

class SimulationScheduleTest {
    @Test void emptyStockMeansEmptyHand() {
        for (var mode : SimulationSettings.Selection.values())
            assertEquals(-1, SimulationSchedule.select(mode, 63, -1, i -> false, new Random(1)));
    }
    @Test void sequentialKeepsSlotUntilExhaustedThenWraps() {
        assertEquals(2, SimulationSchedule.select(SEQUENTIAL, 5, 2, i -> i == 2 || i == 4, new Random(1)));
        assertEquals(4, SimulationSchedule.select(SEQUENTIAL, 5, 2, i -> i == 4, new Random(1)));
        assertEquals(0, SimulationSchedule.select(SEQUENTIAL, 5, 4, i -> i == 0, new Random(1)));
    }
    @Test void roundRobinSkipsUnavailableAndWraps() {
        assertEquals(4, SimulationSchedule.select(ROUND_ROBIN, 5, 2, i -> i == 2 || i == 4, new Random(1)));
        assertEquals(2, SimulationSchedule.select(ROUND_ROBIN, 5, 4, i -> i == 2 || i == 4, new Random(1)));
        assertEquals(0, SimulationSchedule.select(ROUND_ROBIN, 5, -1, i -> true, new Random(1)));
    }
    @Test void randomOnlyChoosesStockedSlotsAndVisitsAll() {
        var random = new Random(42);
        var seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < 1000; i++) {
            int slot = SimulationSchedule.select(RANDOM, 63, 10, n -> n == 2 || n == 12 || n == 62, random);
            assertTrue(slot == 2 || slot == 12 || slot == 62);
            seen.add(slot);
        }
        assertEquals(java.util.Set.of(2, 12, 62), seen);
    }
    @Test void accelerationHasHardOneTickFloor() {
        assertArrayEquals(new int[]{20,10,5,2,1}, java.util.stream.IntStream.rangeClosed(0,4).map(SimulationSchedule::interval).toArray());
        assertThrows(IllegalArgumentException.class, () -> SimulationSchedule.interval(5));
    }
}
