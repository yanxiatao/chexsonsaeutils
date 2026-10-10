package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 跨页保存真实状态、任意嵌套回滚与分支覆写，含百位整数和精确字节。 */
class PagedPlanJournalTest {
    private static final AEItemKey KEY = AEItemKey.of(Items.PAPER);
    private static final PlanAmount WIDE = PlanAmount.of(BigInteger.TEN.pow(100));
    private static final IPatternDetails PATTERN = new IPatternDetails() {
        @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.BARREL); }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(KEY, 1)); }
    };
    private static final RingId RING = RingId.of(KEY, List.of());

    private static final class Fixture implements AutoCloseable {
        final WideKeyCounter[] counters = new WideKeyCounter[7];
        final WideCraftCounter crafts = new WideCraftCounter();
        final ByteAccount bytes = new ByteAccount();
        final Set<RingId> failed = new HashSet<>(), repaid = new HashSet<>(), started = new HashSet<>();
        final PlanJournal journal;
        final int key;
        Fixture(int pageEntries) { this(new PlanJournal.Arena(pageEntries)); }
        Fixture(PlanJournal.Arena arena) {
            var index = new KeyIndex(16);
            for (int i = 0; i < counters.length; i++) counters[i] = new WideKeyCounter(index);
            key = counters[0].idOf(KEY);
            journal = new PlanJournal(counters, crafts, failed, repaid, started, bytes, arena);
        }
        void mutate(int step) {
            journal.counterSet(PlanJournal.UNMODIFIED, key, step % 3 == 0 ? 0 : step);
            journal.counterAddDelta(PlanJournal.WORKING, key, WIDE);
            journal.counterSetExact(PlanJournal.USED, key, WIDE.multiply(PlanAmount.of(step + 1L)));
            journal.counterAddDelta(PlanJournal.MISSING, key, step % 2 == 0 ? Long.MIN_VALUE : 3L);
            journal.craftsMerge(PATTERN, step % 2 == 0 ? WIDE : PlanAmount.of(3L));
            journal.stackBytes(KEY, 3L, WIDE);
            journal.stackBytes(KEY, 7L, 11L);
            journal.plainBytes(1.25);
            if (step == 0) { journal.ringFailed(RING); journal.ringSeedRepaid(RING); journal.ringStarted(RING); }
        }
        void sameAs(Fixture other) {
            for (int i = 0; i < counters.length; i++) {
                assertEquals(other.counters[i].getExact(KEY), counters[i].getExact(KEY));
                assertEquals(other.counters[i].containsKey(KEY), counters[i].containsKey(KEY));
            }
            assertEquals(other.crafts.getAmount(PATTERN), crafts.getAmount(PATTERN));
            assertEquals(other.bytes.exactValue(), bytes.exactValue());
            assertEquals(Double.doubleToRawLongBits(other.bytes.ae2Bytes()), Double.doubleToRawLongBits(bytes.ae2Bytes()));
            assertEquals(other.failed, failed); assertEquals(other.repaid, repaid);
            assertEquals(other.started, started);
        }
        @Override public void close() { journal.releaseRollback(); }
    }

    @Test void nestedRollbackAndRebranchAreIdenticalToMemoryJournal() {
        try (var paged = new Fixture(16); var memory = new Fixture(0)) {
            for (int i = 0; i < 5; i++) { paged.mutate(i); memory.mutate(i); }
            int inner = paged.journal.mark();
            for (int i = 5; i < 25; i++) { paged.mutate(i); memory.mutate(i); }
            paged.sameAs(memory);
            assertTrue(paged.journal.spilledPages() > 0);
            var file = paged.journal.spillPath(); assertTrue(Files.exists(file));
            paged.journal.undoTo(inner); memory.journal.undoTo(inner); paged.sameAs(memory);
            for (int i = 30; i < 45; i++) { paged.mutate(i); memory.mutate(i); }
            paged.sameAs(memory);
            paged.journal.undoTo(0); memory.journal.undoTo(0); paged.sameAs(memory);
            assertFalse(paged.counters[0].containsKey(KEY), "缺位和零缓存标记须区分");
            paged.journal.releaseRollback(); assertFalse(Files.exists(file));
        }
    }

    @Test void residentArraysStayBoundedWhenHistoryHasHundredThousandEntries() {
        try (var paged = new Fixture(1024)) {
            for (int i = 0; i < 100_000; i++) paged.journal.counterAddDelta(PlanJournal.WORKING, paged.key, 1L);
            assertEquals(100_000L, paged.counters[PlanJournal.WORKING].getLong(KEY));
            assertEquals(1024, paged.journal.residentCapacity());
            assertEquals(97, paged.journal.spilledPages());
            paged.journal.undoTo(50_000);
            assertEquals(50_000L, paged.counters[PlanJournal.WORKING].getLong(KEY));
            for (int i = 0; i < 70_000; i++) paged.journal.counterAddDelta(PlanJournal.WORKING, paged.key, 2L);
            assertEquals(190_000L, paged.counters[PlanJournal.WORKING].getLong(KEY));
            assertEquals(1024, paged.journal.residentCapacity());
            paged.journal.undoTo(0);
            assertFalse(paged.counters[PlanJournal.WORKING].containsKey(KEY));
        }
    }

    @Test void exactPageBoundaryCanBeReusedWithoutLosingOldValues() {
        try (var paged = new Fixture(16)) {
            for (int i = 0; i < 16; i++) paged.journal.counterSetExact(PlanJournal.USED, paged.key, WIDE.add(PlanAmount.of(i)));
            int mark = paged.journal.mark();
            for (int i = 0; i < 40; i++) paged.journal.counterAddDelta(PlanJournal.USED, paged.key, 1L);
            paged.journal.undoTo(mark);
            assertEquals(WIDE.add(PlanAmount.of(15)), paged.counters[PlanJournal.USED].getAmountById(paged.key));
            for (int i = 0; i < 40; i++) paged.journal.counterAddDelta(PlanJournal.USED, paged.key, 2L);
            paged.journal.undoTo(0);
            assertFalse(paged.counters[PlanJournal.USED].containsKey(KEY));
        }
    }

    @Test void leavingFailedAttemptDeletesTemporaryJournalAndReleasesReferences() {
        var paged = new Fixture(16);
        for (int i = 0; i < 10; i++) paged.mutate(i);
        var file = paged.journal.spillPath(); assertTrue(Files.exists(file));
        assertThrows(IllegalStateException.class, () -> {
            try (paged) { throw new IllegalStateException("simulated branch failure"); }
        });
        assertFalse(Files.exists(file));
        assertEquals(0, paged.journal.residentCapacity());
        assertEquals(0, paged.journal.spilledPages());
    }
    @Test void automaticMigrationPreservesEarlierMarksAndWideRareColumns() {
        var low = new java.util.concurrent.atomic.AtomicBoolean(false);
        git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(
                        low.get() ? 1024L : Long.MAX_VALUE, 256L);
        try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure)); var memory = new Fixture(0)) {
            for (int i = 0; i < 20; i++) { adaptive.mutate(i); memory.mutate(i); }
            int inner = adaptive.journal.mark();
            for (int i = 20; i < 40; i++) { adaptive.mutate(i); memory.mutate(i); }
            int before = adaptive.journal.mark();
            assertEquals(0, adaptive.journal.spilledPages());
            low.set(true); adaptive.journal.checkMemoryPressure();
            assertEquals(before, adaptive.journal.mark(), "迁移不能修改原有检查点编号");
            assertEquals(16, adaptive.journal.residentCapacity());
            assertTrue(adaptive.journal.spilledPages() > 0);
            var file = adaptive.journal.spillPath(); assertTrue(Files.exists(file));
            adaptive.sameAs(memory);
            low.set(false); adaptive.journal.checkMemoryPressure();
            assertEquals(16, adaptive.journal.residentCapacity(), "压力恢复后本次求解仍保持分页，避免来回切换");
            for (int i = 40; i < 70; i++) { adaptive.mutate(i); memory.mutate(i); }
            adaptive.sameAs(memory);
            adaptive.journal.undoTo(inner); memory.journal.undoTo(inner); adaptive.sameAs(memory);
            for (int i = 90; i < 110; i++) { adaptive.mutate(i); memory.mutate(i); }
            adaptive.sameAs(memory);
            adaptive.journal.undoTo(0); memory.journal.undoTo(0); adaptive.sameAs(memory);
            adaptive.journal.releaseRollback(); assertFalse(Files.exists(file));
        }
    }

    @Test void expandingArrayTriggersMigrationBeforeHeadroomIsExhausted() {
        git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(1_000_000L, 980_000L);
        try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure))) {
            // 1024 初始条目仍未侵入预留，但扩容所需的第二份数组会侵入。
            for (int i = 0; i < 512; i++) adaptive.journal.counterAddDelta(PlanJournal.WORKING, adaptive.key, 1L);
            assertEquals(0, adaptive.journal.spilledPages());
            for (int i = 512; i < 1100; i++) adaptive.journal.counterAddDelta(PlanJournal.WORKING, adaptive.key, 1L);
            assertEquals(16, adaptive.journal.residentCapacity());
            assertEquals(1100L, adaptive.counters[PlanJournal.WORKING].getLong(KEY));
            adaptive.journal.undoTo(0); assertFalse(adaptive.counters[PlanJournal.WORKING].containsKey(KEY));
        }
    }

    @Test void healthyHeadroomKeepsInMemoryFastPath() {
        git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(Long.MAX_VALUE, 256L);
        try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure))) {
            for (int i = 0; i < 2500; i++) adaptive.journal.counterAddDelta(PlanJournal.WORKING, adaptive.key, 1L);
            adaptive.journal.checkMemoryPressure();
            assertEquals(4096, adaptive.journal.residentCapacity());
            assertEquals(0, adaptive.journal.spilledPages()); assertNull(adaptive.journal.spillPath());
            adaptive.journal.undoTo(0); assertFalse(adaptive.counters[PlanJournal.WORKING].containsKey(KEY));
        }
    }

    @Test void migrationOfShortTailWaitsUntilFirstPageIsFull() {
        var low = new java.util.concurrent.atomic.AtomicBoolean(false);
        git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(low.get() ? 0L : Long.MAX_VALUE, 256L);
        try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure))) {
            for (int i = 0; i < 7; i++) adaptive.journal.counterSetExact(PlanJournal.USED, adaptive.key, WIDE.add(PlanAmount.of(i)));
            int mark = adaptive.journal.mark();
            low.set(true); adaptive.journal.checkMemoryPressure();
            assertEquals(16, adaptive.journal.residentCapacity()); assertNull(adaptive.journal.spillPath());
            for (int i = 0; i < 50; i++) adaptive.journal.counterAddDelta(PlanJournal.USED, adaptive.key, 1L);
            adaptive.journal.undoTo(mark);
            assertEquals(WIDE.add(PlanAmount.of(6)), adaptive.counters[PlanJournal.USED].getAmountById(adaptive.key));
            adaptive.journal.undoTo(0); assertFalse(adaptive.counters[PlanJournal.USED].containsKey(KEY));
        }
    }

}
