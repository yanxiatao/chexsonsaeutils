package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bigint 通道那一张<b>计划</b>回执槽的用例：它交回的只能是预期，做差那把尺必须把"欠了几枚"留着。
 *
 * <p>两件事各一组断言：
 * <ul>
 *   <li>{@link UselessBigIntegerReceipt#plannedAsWideCounter()} 交回的是
 *       {@code pattern.getOutputs() × 份数} 那份<b>自报计划</b>；它不进网络、也不证明入网，
 *       只用于把"不会再有实物回 CPU 池子"的那一截从实物预期里摘出去；</li>
 *   <li>{@link UselessBigIntegerReceipt#selfReportDelta} 的差表<b>两侧方向都留</b>——
 *       欠（{@code 自报 < 声明}）与多都得出表，否则"少交付"在这条口上根本印不出来。</li>
 * </ul>
 *
 * <p>本类刻意只碰 AE2 类型与 {@link BigInteger}：没装 useless_mod 的离线测试树里照样加载得了，
 * 所以这把尺不许长回 {@code UselessBigIntegerApiBridge}（那边 import 了 useless 的类型）。
 */
final class UselessBigIntegerReceiptTest {

    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey BYPRODUCT = AEItemKey.of(Items.CLOCK);

    @Test
    void realOutputClaimKeepsBigIntegerAndIgnoresPlannedCounts() {
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        var id = java.util.UUID.randomUUID();
        receipt.noteBatchId(id);
        BigInteger actual = BigInteger.TEN.pow(40).add(BigInteger.valueOf(17));
        receipt.addPlannedOutput(PRODUCT, actual.multiply(BigInteger.TEN));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        receipt.bindOutputReceiver((grid, what, available) -> {
            calls.incrementAndGet();
            return what.equals(PRODUCT) ? available : BigInteger.ZERO;
        });
        var available = Map.<AEKey, BigInteger>of(PRODUCT, actual, BYPRODUCT, BigInteger.valueOf(20L));
        assertEquals(Map.of(), receipt.claimOutputs(java.util.UUID.randomUUID(), new ClaimGrid(), available));
        assertEquals(0, calls.get());
        assertEquals(Map.of(PRODUCT, actual), receipt.claimOutputs(id, new ClaimGrid(), available));
        assertEquals(2, calls.get());
    }

    @Test
    void terminalReleasesActualOutputReceiverEvenWithoutTerminalLedger() {
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        var id = java.util.UUID.randomUUID();
        receipt.noteBatchId(id);
        receipt.bindOutputReceiver((grid, what, available) -> available);
        receipt.deliverTerminal(id,
                git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.CANCELLED);
        assertEquals(Map.of(), receipt.claimOutputs(id, new ClaimGrid(), Map.of(PRODUCT, BigInteger.TEN)));
    }

    /** 只作为网络身份；实物认领不得访问网络服务或进行存储插入。 */
    private static final class ClaimGrid implements appeng.api.networking.IGrid {
        private UnsupportedOperationException unused() {
            return new UnsupportedOperationException("实物直送无需网络服务");
        }
        public <C extends appeng.api.networking.IGridService> C getService(Class<C> type) { throw unused(); }
        public <T extends appeng.api.networking.events.GridEvent> T postEvent(T event) { throw unused(); }
        public Iterable<Class<?>> getMachineClasses() { throw unused(); }
        public Iterable<appeng.api.networking.IGridNode> getMachineNodes(Class<?> type) { throw unused(); }
        public <T> java.util.Set<T> getMachines(Class<T> type) { throw unused(); }
        public <T> java.util.Set<T> getActiveMachines(Class<T> type) { throw unused(); }
        public Iterable<appeng.api.networking.IGridNode> getNodes() { throw unused(); }
        public boolean isEmpty() { throw unused(); }
        public appeng.api.networking.IGridNode getPivot() { throw unused(); }
        public int size() { throw unused(); }
        public void export(com.google.gson.stream.JsonWriter writer) { throw unused(); }
    }

    /** 计划回执保留逐键精确量：没收到任何计划即 {@code null}（＝全额挂实物预期）。 */
    @Test
    void plannedCounterIsTheSelfReportedPlanNotAnInsertReceipt() {
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        assertNull(receipt.plannedAsWideCounter(), "一条计划都没交回来＝null，调用方退回实物挂预期");

        receipt.addPlannedOutput(PRODUCT, BigInteger.valueOf(5_000L));
        receipt.addPlannedOutput(PRODUCT, BigInteger.valueOf(2_500L));
        WideKeyCounter planned = receipt.plannedAsWideCounter();
        assertEquals(BigInteger.valueOf(7_500L), planned.getExact(PRODUCT), "同键复加：那份读数就是整批的计划量");
        assertEquals(1, planned.size(), "只报回来的那一键");
    }

    /** 计划量超 long 时仍保留整批，不混同交付分段粒度。 */
    @Test
    void plannedKeyBeyondLongRetainsTheWholeBatch() {
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        receipt.addPlannedOutput(PRODUCT, BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
                receipt.plannedAsWideCounter().getExact(PRODUCT),
                "保留整批数量，不能以交付分段大小替代");
    }

    /** 欠的那一截必须出表：自报清单比声明少两枚，差表里就得有这 −2。 */
    @Test
    void shortfallAgainstTheDeclarationStaysVisibleInTheDelta() {
        var delta = UselessBigIntegerReceipt.selfReportDelta(
                totals(PRODUCT, 100L), totals(PRODUCT, 98L));
        assertEquals(BigInteger.valueOf(-2L), delta.get(PRODUCT),
                "少交付那一截在差表里是负的，不许被吞成空表");
        assertTrue(UselessBigIntegerReceipt.describeSelfReportDelta(delta).contains("欠2"),
                "读数要把方向说出来：只印一个负数，读日志的人会当它是零头。读数="
                        + UselessBigIntegerReceipt.describeSelfReportDelta(delta));
    }

    /** 声明里有、自报清单里整个没有这一键：同样算欠，不许读成"对平"。 */
    @Test
    void declaredKeyMissingFromTheSelfReportReadsAsShortfallNotAsBalanced() {
        var delta = UselessBigIntegerReceipt.selfReportDelta(
                totals(PRODUCT, 100L), totals(BYPRODUCT, 30L));
        assertEquals(BigInteger.valueOf(-100L), delta.get(PRODUCT), "自报侧缺这一键＝整笔欠");
        assertEquals(BigInteger.valueOf(30L), delta.get(BYPRODUCT), "自报侧多出来那一键＝键形差（动态产物换了真键）");
        assertTrue(delta.size() == 2, "两截都要点名，缺一格就说不清是谁对不上");
    }

    /** 两侧逐键相等才是空表，且那句读数不许再声称"交付"。 */
    @Test
    void equalSelfReportReadsAsBalancedWithoutClaimingDelivery() {
        var planned = new LinkedHashMap<AEKey, BigInteger>();
        planned.put(PRODUCT, BigInteger.valueOf(1_000L));
        planned.put(BYPRODUCT, BigInteger.valueOf(7L));
        var selfReported = new LinkedHashMap<AEKey, BigInteger>();
        selfReported.put(BYPRODUCT, BigInteger.valueOf(7L));
        selfReported.put(PRODUCT, BigInteger.valueOf(1_000L));

        assertTrue(UselessBigIntegerReceipt.selfReportDelta(planned, selfReported).isEmpty(),
                "逐键相等＝空表，与键的先后无关");
        var described = UselessBigIntegerReceipt.describeSelfReportDelta(
                UselessBigIntegerReceipt.selfReportDelta(planned, selfReported));
        assertTrue(described.contains("自报清单"), "对平的主体是自报清单，不是交付：" + described);
        assertTrue(described.contains("非") || described.contains("不证明"),
                "这一行必须自带'看不出落库'的免责：" + described);
        assertTrue(!described.contains("已按实际入网量交付"),
                "不许再出现那副把计划量说成已交付的口径：" + described);
    }

    /** 绑定前终局按原契约丢弃，不缓冲、不在随后绑定时重放。 */
    @Test
    void terminalBeforeBindIsDroppedAndNeverReplayed() {
        var receipt = new UselessBigIntegerReceipt("test-machine");
        var id = java.util.UUID.randomUUID();
        receipt.noteBatchId(id);
        var before = UselessBigIntegerReceipt.terminalDroppedNoLedger();
        receipt.deliverTerminal(id,
                git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(before + 1L, UselessBigIntegerReceipt.terminalDroppedNoLedger());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        receipt.bindTerminal((batch, outcome) -> calls.incrementAndGet(), id);
        assertEquals(0, calls.get());
        receipt.deliverTerminal(id,
                git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(1, calls.get());
    }

    /** 绑定后错误批次标识只计丢弃，不改变台账或交割量。 */
    @Test
    void wrongTerminalIdentityCannotSettleBoundLedger() {
        var receipt = new UselessBigIntegerReceipt("test-machine");
        var id = java.util.UUID.randomUUID();
        receipt.noteBatchId(id);
        var ledger = new git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedgerImpl();
        var pattern = new appeng.api.crafting.IPatternDetails() {
            public AEItemKey getDefinition() { return AEItemKey.of(net.minecraft.world.item.Items.GLOWSTONE); }
            public IInput[] getInputs() { return new IInput[0]; }
            public java.util.List<appeng.api.stacks.GenericStack> getOutputs() { return java.util.List.of(); }
        };
        ledger.noteAdmitted(receipt, pattern, BigInteger.ONE, 10L, null);
        var before = UselessBigIntegerReceipt.terminalDroppedIdMismatch();
        receipt.deliverTerminal(java.util.UUID.randomUUID(),
                git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(before + 1L, UselessBigIntegerReceipt.terminalDroppedIdMismatch());
        assertEquals(1, ledger.inflightBatchCount());
        assertEquals(0L, ledger.coverageCounters().terminalSuccess());
    }

    private static Map<AEKey, BigInteger> totals(AEKey key, long amount) {
        var totals = new LinkedHashMap<AEKey, BigInteger>();
        totals.put(key, BigInteger.valueOf(amount));
        return totals;
    }
}
