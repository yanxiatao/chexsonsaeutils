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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningAlgorithmOrderTest {
    @Test
    void defaultsMatchSupportedAlgorithmOrder() {
        // 配置默认顺位与当前算法枚举保持一致。
        assertEquals(
                List.of("GRAPH", "NATIVE"),
                PlanningServerConfig.DEFAULT_ORDER);
        assertEquals(
                List.of("GRAPH", "NATIVE"),
                java.util.Arrays.stream(PlanningAlgorithmId.values()).map(Enum::name).toList());
    }

    @Test
    void acceptsOnlyCompletePermutations() {
        List<String> reordered = List.of(
                "NATIVE",
                "GRAPH");
        assertEquals(reordered, PlanningServerConfig.requireCompleteOrder(reordered));

        assertThrows(IllegalArgumentException.class, () -> PlanningServerConfig.requireCompleteOrder(
                List.of("GRAPH", "GRAPH")));
        assertThrows(IllegalArgumentException.class, () -> PlanningServerConfig.requireCompleteOrder(
                List.of("GRAPH")));
    }

    @Test
    void normalizesDamagedPersistedValuesToDefaults() {
        // 同一档写两遍：长度对得上但去重后不完整 ⇒ 按损坏处理回落默认序。
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, PlanningServerConfig.normalizeOrder(
                List.of("NATIVE", "NATIVE"), PlanningServerConfig.DEFAULT_ORDER));
        // 认不出的串被丢掉之后剩下"不完整"的排列 ⇒ 整串按损坏处理回落默认序。
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, PlanningServerConfig.normalizeOrder(
                List.of("FROZEN_ROUTE", "UNKNOWN", "NATIVE"), PlanningServerConfig.DEFAULT_ORDER));
        // 旧 TOML 里残留的 FAST 档：同样不再是被认识的档位。
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, PlanningServerConfig.normalizeOrder(
                List.of("FROZEN_ROUTE", "FAST", "NATIVE"), PlanningServerConfig.DEFAULT_ORDER));
        // 实机现场那一刻写着的 3 元序（用户实例 config/chexsonsaeutils-server.toml:4）：
        // 退役档字符串认不出 ⇒ 丢掉 ⇒ 剩下的恰好是新枚举的完整排列 ⇒ 静默退化成 DEFAULT 序，
        // 不报错、不留兼容壳。这一条就是"升级后现场配置怎么走"唯一的牙。
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, PlanningServerConfig.normalizeOrder(
                List.of("FROZEN_ROUTE", "GRAPH", "NATIVE"), PlanningServerConfig.DEFAULT_ORDER));

        List<String> valid = List.of(
                "NATIVE",
                "GRAPH");
        // 归一这一腿（trim＋转大写根）：磁盘上写小写也算同一档，去掉它这条就红。
        assertEquals(valid, PlanningServerConfig.normalizeOrder(
                List.of("native", "graph"), PlanningServerConfig.DEFAULT_ORDER));
    }

    @Test
    void snapshotIsImmutableAndReloadAdvancesRevision() {
        List<String> mutable = new ArrayList<>(PlanningServerConfig.DEFAULT_ORDER);
        PlanningAlgorithmOrderService service = new PlanningAlgorithmOrderService(mutable);
        PlanningAlgorithmOrderService.Snapshot first = service.snapshot();
        mutable.clear();

        assertEquals(PlanningServerConfig.DEFAULT_ORDER, first.order());
        assertThrows(UnsupportedOperationException.class,
                () -> first.order().set(0, "NATIVE"));

        List<String> reloaded = List.of(
                "NATIVE",
                "GRAPH");
        PlanningAlgorithmOrderService.Snapshot second = service.reload(reloaded);
        assertEquals(1L, second.revision());
        assertEquals(reloaded, second.order());
        assertEquals(PlanningServerConfig.DEFAULT_ORDER, first.order());
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
                        "NATIVE",
                        "GRAPH"), 0L);
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
