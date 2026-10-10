package git.chexson.chexsonsaeutils.crafting.progress;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningProgressTest {
    @Test void requestScopeRestoresPreviousContextAndPublishesActualWork() {
        var outer = new PlanningProgressImpl(); var inner = new PlanningProgressImpl();
        try (var scope = PlanningProgressContext.open(outer)) {
            outer.phase("COMPILE"); outer.advance(23);
            try (var nested = PlanningProgressContext.open(inner)) {
                inner.phase("SOLVE"); inner.advance(1042);
                assertSame(inner, PlanningProgressContext.current());
            }
            assertSame(outer, PlanningProgressContext.current());
        }
        assertNull(PlanningProgressContext.current());
        assertEquals(23, outer.snapshot().steps());
        assertEquals("SOLVE", inner.snapshot().phase());
        assertEquals(1042, inner.snapshot().steps());
        assertTrue(inner.snapshot().elapsedMillis() >= 0);
    }

    @Test void futurePreservesResultFailureAndNeverLeaksProgressIntoNextRequest() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = PlanningProgressFuture.submit(executor, () -> {
                var progress = PlanningProgressContext.current();
                progress.phase("SOLVE"); progress.advance(45);
                return "exact plan";
            });
            assertEquals("exact plan", future.get(5, TimeUnit.SECONDS));
            assertEquals(45, future.planningProgress().snapshot().steps());
            var failed = PlanningProgressFuture.submit(executor, () -> { throw new IllegalStateException("declined"); });
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, failed::get).getCause());
            assertNull(executor.submit(PlanningProgressContext::current).get());
            var next = PlanningProgressFuture.submit(executor, () -> 7);
            assertEquals(7, next.get()); assertEquals(0, next.planningProgress().snapshot().steps());
        }
    }

    @Test void cancellationInterruptsWorkerAndClearsRequestContext() throws Exception {
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = PlanningProgressFuture.submit(executor, () -> {
                PlanningProgressContext.current().phase("SOLVE"); started.countDown();
                new CountDownLatch(1).await(); return 0;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true)); assertTrue(future.isCancelled());
            assertThrows(CancellationException.class, future::get);
            assertNull(executor.submit(PlanningProgressContext::current).get(5, TimeUnit.SECONDS));
        }
    }
}
