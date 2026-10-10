package git.chexson.chexsonsaeutils.config;

import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningAlgorithmOrderTest {
    @Test
    void exposesStableAlgorithmMetadata() {
        assertEquals("planning_algorithm.chexsonsaeutils.frozen_route",
                PlanningAlgorithmId.FROZEN_ROUTE.translationKey());
        assertEquals("planning_algorithm.chexsonsaeutils.frozen_route.description",
                PlanningAlgorithmId.FROZEN_ROUTE.descriptionKey());
        assertTrue(PlanningAlgorithmId.NATIVE.terminal());
        assertTrue(PlanningAlgorithmId.NATIVE.isTerminal());
        assertFalse(PlanningAlgorithmId.GRAPH.terminal());
    }

    @Test
    void acceptsOnlyCompletePermutations() {
        List<PlanningAlgorithmId> reordered = List.of(
                PlanningAlgorithmId.NATIVE,
                PlanningAlgorithmId.FAST,
                PlanningAlgorithmId.GRAPH,
                PlanningAlgorithmId.FROZEN_ROUTE);
        assertEquals(reordered, PlanningServerConfig.requireCompleteOrder(reordered));

        assertThrows(IllegalArgumentException.class, () -> PlanningServerConfig.requireCompleteOrder(
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.GRAPH,
                        PlanningAlgorithmId.FAST, PlanningAlgorithmId.NATIVE)));
        assertThrows(IllegalArgumentException.class, () -> PlanningServerConfig.requireCompleteOrder(
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.FAST, PlanningAlgorithmId.NATIVE)));
    }

    @Test
    void normalizesDamagedPersistedValuesToDefaults() {
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, PlanningServerConfig.normalizeOrder(
                List.of("GRAPH", "GRAPH", "FAST", "NATIVE"), PlanningServerConfig.DEFAULT_ORDER));
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, PlanningServerConfig.normalizeOrder(
                List.of("FROZEN_ROUTE", "UNKNOWN", "FAST", "NATIVE"), PlanningServerConfig.DEFAULT_ORDER));

        List<PlanningAlgorithmId> valid = List.of(
                PlanningAlgorithmId.NATIVE,
                PlanningAlgorithmId.FAST,
                PlanningAlgorithmId.GRAPH,
                PlanningAlgorithmId.FROZEN_ROUTE);
        assertEquals(valid, PlanningServerConfig.normalizeOrder(
                List.of("native", "fast", "graph", "frozen_route"), PlanningServerConfig.DEFAULT_ORDER));
    }

    @Test
    void snapshotIsImmutableAndReloadAdvancesRevision() {
        List<PlanningAlgorithmId> mutable = new ArrayList<>(PlanningServerConfig.DEFAULT_ORDER);
        PlanningAlgorithmOrderService service = new PlanningAlgorithmOrderService(mutable);
        PlanningAlgorithmOrderService.Snapshot first = service.snapshot();
        mutable.clear();

        assertEquals(PlanningServerConfig.DEFAULT_ORDER, first.order());
        assertThrows(UnsupportedOperationException.class,
                () -> first.order().set(0, PlanningAlgorithmId.NATIVE));

        List<PlanningAlgorithmId> reloaded = List.of(
                PlanningAlgorithmId.NATIVE,
                PlanningAlgorithmId.FAST,
                PlanningAlgorithmId.GRAPH,
                PlanningAlgorithmId.FROZEN_ROUTE);
        PlanningAlgorithmOrderService.Snapshot second = service.reload(reloaded);
        assertEquals(1L, second.revision());
        assertEquals(reloaded, second.order());
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, first.order());
    }

    @Test
    void updateAdvancesRevisionOnce() {
        PlanningAlgorithmOrderService service = new PlanningAlgorithmOrderService(
                PlanningServerConfig.DEFAULT_ORDER);
        List<PlanningAlgorithmId> order = List.of(
                PlanningAlgorithmId.NATIVE,
                PlanningAlgorithmId.FAST,
                PlanningAlgorithmId.GRAPH,
                PlanningAlgorithmId.FROZEN_ROUTE);

        PlanningAlgorithmOrderService.UpdateResult result = service.update(order, 0L);

        assertTrue(result.updated());
        assertEquals(1L, result.snapshot().revision());
        assertEquals(order, service.snapshot().order());
    }

    @Test
    void staleAndNegativeRevisionDoNotWrite() {
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        PlanningAlgorithmOrderService service = new PlanningAlgorithmOrderService(
                PlanningServerConfig.DEFAULT_ORDER,
                order -> writes.incrementAndGet());

        assertEquals(PlanningAlgorithmOrderService.UpdateStatus.INVALID_REVISION,
                service.update(PlanningServerConfig.DEFAULT_ORDER, -1L).status());
        service.reload(PlanningServerConfig.DEFAULT_ORDER);
        assertEquals(PlanningAlgorithmOrderService.UpdateStatus.REVISION_MISMATCH,
                service.update(PlanningServerConfig.DEFAULT_ORDER, 0L).status());
        assertEquals(0, writes.get());
    }

    @Test
    void persistenceFailureDoesNotPublishOrAdvanceRevision() {
        PlanningAlgorithmOrderService service = new PlanningAlgorithmOrderService(
                PlanningServerConfig.DEFAULT_ORDER,
                order -> {
                    throw new IllegalStateException("disk failure");
                });

        var result = service.update(PlanningServerConfig.DEFAULT_ORDER, 0L);

        assertEquals(PlanningAlgorithmOrderService.UpdateStatus.PERSISTENCE_FAILED, result.status());
        assertEquals(0L, service.revision());
    }

    @Test
    void expectedRevisionAllowsExactlyOneConcurrentWriter() throws Exception {
        PlanningAlgorithmOrderService service = new PlanningAlgorithmOrderService(
                PlanningServerConfig.DEFAULT_ORDER);
        int writers = 16;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(writers)) {
            var futures = IntStream.range(0, writers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.update(List.of(
                        PlanningAlgorithmId.NATIVE,
                        PlanningAlgorithmId.FAST,
                        PlanningAlgorithmId.GRAPH,
                        PlanningAlgorithmId.FROZEN_ROUTE), 0L);
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long successes = 0L;
            for (var future : futures) {
                if (future.get(5, TimeUnit.SECONDS).updated()) {
                    successes++;
                }
            }
            assertEquals(1L, successes);
        }
        assertEquals(1L, service.snapshot().revision());

        PlanningAlgorithmOrderService.UpdateResult stale = service.update(
                PlanningServerConfig.DEFAULT_ORDER, 0L);
        assertEquals(PlanningAlgorithmOrderService.UpdateStatus.REVISION_MISMATCH, stale.status());
        assertEquals(service.snapshot(), stale.snapshot());
    }
}
