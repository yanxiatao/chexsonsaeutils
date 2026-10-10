package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 通用终局凭据直接驱动台账，验证绑定时同步通知仍能认回已经登记的记录。 */
final class DeclaredBatchTerminalLedgerImplTest {
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final IPatternDetails PATTERN = new Pattern();

    @Test
    void synchronousSuccessAtBindSeesRegisteredRecordAndDeliversOnlyOnce() {
        DeclaredBatchTerminalLedger ledger = new DeclaredBatchTerminalLedgerImpl();
        var receipt = new Receipt(UUID.randomUUID(), DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        var units = BigInteger.ONE.shiftLeft(80);
        var delivered = new AtomicInteger();
        ledger.noteAdmitted(receipt, PATTERN, BigInteger.valueOf(8L), 10L,
                (pattern, firings) -> { throw unused(); }, Map.of(PRODUCT, units), true,
                (pending, clamped) -> {
                    assertEquals(Map.of(PRODUCT, units), pending);
                    assertTrue(clamped);
                    delivered.incrementAndGet();
                });
        assertEquals(1, delivered.get());
        assertEquals(0, ledger.inflightBatchCount());
        assertEquals(1L, ledger.coverageCounters().inflightRegistered());
        assertEquals(1L, ledger.coverageCounters().terminalSuccess());
        assertEquals(0L, ledger.coverageCounters().terminalUnmatched());
        receipt.finish(DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(1, delivered.get());
        assertEquals(1L, ledger.coverageCounters().terminalUnmatched());
    }

    @Test
    void synchronousNegativeAtBindCallsRollbackWithoutDeliveryForBothOutcomes() {
        for (var outcome : List.of(DeclaredBatchTerminalLedger.Outcome.REJECTED,
                DeclaredBatchTerminalLedger.Outcome.CANCELLED)) {
            DeclaredBatchTerminalLedger ledger = new DeclaredBatchTerminalLedgerImpl();
            var receipt = new Receipt(UUID.randomUUID(), outcome);
            var firings = BigInteger.ONE.shiftLeft(75);
            var rolledBack = new AtomicInteger();
            ledger.noteAdmitted(receipt, PATTERN, firings, 10L, (pattern, amount) -> {
                assertSame(PATTERN, pattern);
                assertEquals(firings, amount);
                rolledBack.incrementAndGet();
                return false;
            }, Map.of(PRODUCT, BigInteger.TEN), false, (pending, clamped) -> { throw unused(); });
            assertEquals(1, rolledBack.get());
            assertEquals(0, ledger.inflightBatchCount());
            assertFalse(ledger.hasUndeliveredDeclaredOutput());
            assertEquals("NONE", ledger.describePendingDelivery());
            assertTrue(ledger.describeUndelivered().contains("#" + firings + "枚/已回退0枚"));
            assertEquals(outcome == DeclaredBatchTerminalLedger.Outcome.CANCELLED ? 1L : 0L,
                    ledger.coverageCounters().terminalCancelled());
            assertEquals(outcome == DeclaredBatchTerminalLedger.Outcome.REJECTED ? 1L : 0L,
                    ledger.coverageCounters().terminalOther());
        }
    }

    @Test
    void genericReceiptWithoutBatchIdentityNeverBindsOrRegisters() {
        DeclaredBatchTerminalLedger ledger = new DeclaredBatchTerminalLedgerImpl();
        var receipt = new Receipt(null, null);
        ledger.noteAdmitted(receipt, PATTERN, BigInteger.ONE, 10L, null);
        assertEquals(0, receipt.bindCalls);
        assertEquals(0, ledger.inflightBatchCount());
        assertEquals(1L, ledger.coverageCounters().silentNoBatchId());
    }

    /** 假回执只实现通用契约；同步终局是注册行为的一部分，没有第三方类型依赖。 */
    private static final class Receipt implements DeclaredBatchReceipt {
        private final @Nullable UUID id;
        private final @Nullable DeclaredBatchTerminalLedger.Outcome synchronousOutcome;
        private DeclaredBatchTerminalCallback callback;
        private int bindCalls;
        Receipt(@Nullable UUID id, @Nullable DeclaredBatchTerminalLedger.Outcome synchronousOutcome) {
            this.id = id;
            this.synchronousOutcome = synchronousOutcome;
        }
        @Override public @Nullable UUID batchId() { return id; }
        @Override public boolean plannedOutputsClamped() { return false; }
        @Override public void bindTerminal(DeclaredBatchTerminalCallback callback, UUID attachedBatchId) {
            assertEquals(id, attachedBatchId);
            bindCalls++;
            this.callback = callback;
            if (synchronousOutcome != null) { finish(synchronousOutcome); }
        }
        void finish(DeclaredBatchTerminalLedger.Outcome outcome) {
            if (callback == null || id == null) { throw unused(); }
            callback.onTerminal(id, outcome);
        }
    }

    private static final class Pattern implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.GLOWSTONE); }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 1L)); }
    }
    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException("测试未配置此终局入口");
    }
}
