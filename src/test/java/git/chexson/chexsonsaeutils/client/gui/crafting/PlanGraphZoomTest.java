package git.chexson.chexsonsaeutils.client.gui.crafting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlanGraphZoomTest {
    @Test void detailsFadeContinuouslyInsteadOfSwitchingAtOldThreshold() {
        assertEquals(0, PlanGraphZoom.routes(0.1)); assertEquals(1, PlanGraphZoom.routes(1));
        assertEquals(0, PlanGraphZoom.icons(0.1)); assertEquals(1, PlanGraphZoom.icons(1.5));
        assertEquals(0, PlanGraphZoom.amounts(0.5)); assertEquals(1, PlanGraphZoom.amounts(1.5));
        double previous = 0;
        for (double zoom = 0.01; zoom < 1.5; zoom += 0.001) {
            double value = PlanGraphZoom.routes(zoom);
            assertTrue(value >= previous); assertTrue(value - previous < 0.01); previous = value;
        }
        assertTrue(Math.abs(PlanGraphZoom.routes(0.651) - PlanGraphZoom.routes(0.649)) < 0.01);
        assertEquals(0, PlanGraphZoom.color(0xffffffff, 0) >>> 24);
        assertEquals(255, PlanGraphZoom.color(0xffffffff, 1) >>> 24);
    }
}
