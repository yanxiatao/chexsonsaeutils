package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #72 第二刀：物理池换宽域实现后的<b>分轮回池交付</b>与<b>精确读数接线</b>用例。
 *
 * <p>钉四件事：
 * <ul>
 *   <li>溢出的键按轮退网：一轮每键最多交 {@code min(精确值, Long.MAX)}，逐轮只按网络
 *       <b>实收</b>的 {@code long} 扣账，余量留在宽域台账，同一键跨轮直到清零（跨轮可续）；</li>
 *   <li>拒收/失败一分不扣：台账与主表哨兵行原样留下，绝不允许"按申报扣"；</li>
 *   <li>{@code long} 域内的键与旧的"主表逐键 setValue"退法逐位一致（换实现不改常态行为）；</li>
 *   <li>状态栏聚合（{@code getAllItems}）与停滞日志 {@code inv=} 段：读数走精确读口，
 *       交去 AE2 前逐键饱和——跨源相加绝不回绕出负数。</li>
 * </ul>
 *
 * <p>夹具沿用本包既有口径（空计划 + 空 link + {@code WideCraftingInventory}），不起第二套。
 */
final class ParallelWidePoolRoundStoreTest {

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    // ---- 分轮回池交付 ----

    /** 同一键精确值 3×MAX+7：按轮各交不超过 long，四轮交完、条目整条撤走，合计逐枚一致。 */
    @Test
    void overflowedPoolKeyConvergesAcrossRoundsOfAtMostLongMax() {
        WideCraftingInventory pool = newPool();
        for (int batch = 0; batch < 3; batch++) {
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        }
        pool.insert(GLASS, 7L, Actionable.MODULATE);
        BigInteger exact = LONG_MAX.multiply(BigInteger.valueOf(3L)).add(BigInteger.valueOf(7L));
        assertEquals(exact, pool.amount(GLASS));

        var offers = new ArrayList<Long>();
        BigInteger deliveredTotal = BigInteger.ZERO;
        for (int round = 0; round < 4; round++) {
            BigInteger before = totalExact(pool);
            ParallelCraftingCpuLogic.storeWideInventory(pool, (what, offer) -> {
                offers.add(offer);
                return offer; // 网络全收
            });
            BigInteger delivered = before.subtract(totalExact(pool));
            assertTrue(delivered.signum() > 0, "每一轮都必须真实推进，绝不原地打转");
            assertTrue(delivered.compareTo(LONG_MAX) <= 0, "一轮每键最多交一个 long");
            deliveredTotal = deliveredTotal.add(delivered);
        }
        assertEquals(exact, deliveredTotal, "四轮合计恰等于精确值：一枚不多一枚不少");
        assertEquals(List.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 7L), offers);
        assertTrue(pool.list.isEmpty(), "清零即整条撤走，不留 0 值条目");
        assertFalse(pool.hasOverflow(), "交付完成后侧表不残留");
    }

    /** 网络整轮拒收：台账一分不扣，主表哨兵行与侧表原样，下一轮继续。 */
    @Test
    void refusedRoundDebtsNothingAndKeepsTheWideLedgerIntact() {
        WideCraftingInventory pool = newPool();
        pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        BigInteger exact = LONG_MAX.multiply(BigInteger.TWO);

        ParallelCraftingCpuLogic.storeWideInventory(pool, (what, offer) -> 0L);

        assertEquals(exact, pool.amount(GLASS), "拒收一轮，台账一分不扣");
        assertEquals(Long.MAX_VALUE, pool.list.get(GLASS), "溢出键对基类身份读数仍是饱和哨兵");
        assertTrue(pool.hasOverflow());
    }

    /** 申报交出 MAX、网络只认 9 枚：扣账只按实收的 9，绝不跟着申报走。 */
    @Test
    void partialAcceptanceDebitsByReceiptNotByOffer() {
        WideCraftingInventory pool = newPool();
        pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        pool.insert(GLASS, 9L, Actionable.MODULATE);
        BigInteger before = pool.amount(GLASS);
        assertEquals(LONG_MAX.multiply(BigInteger.TWO).add(BigInteger.valueOf(9L)), before);

        ParallelCraftingCpuLogic.storeWideInventory(pool, (what, offer) -> {
            assertEquals(Long.MAX_VALUE, offer, "溢出键本轮的可交量应夹在 long 天花板");
            return 9L;
        });

        assertEquals(LONG_MAX.multiply(BigInteger.TWO), pool.amount(GLASS),
                "2×MAX+9 只按实收扣 9 枚：扣账恒等于实收，不是申报数");
    }

    /** {@code long} 域内的键：分轮交付与旧的"主表逐键 setValue"退法逐位一致。 */
    @Test
    void inRangeQuantitiesMatchTheLegacyMainTableLoopRoundByRound() {
        WideCraftingInventory wide = newPool();
        ListCraftingInventory legacy = new ListCraftingInventory(ignored -> {
        });
        wide.insert(GLASS, 250L, Actionable.MODULATE);
        wide.insert(IRON, 100L, Actionable.MODULATE);
        legacy.list.add(GLASS, 250L);
        legacy.list.add(IRON, 100L);

        for (int round = 0; round < 6; round++) {
            ParallelCraftingCpuLogic.storeWideInventory(wide, acceptAtMost(100L));
            for (var entry : legacy.list) {
                long inserted = Math.min(entry.getLongValue(), 100L);
                entry.setValue(entry.getLongValue() - inserted);
            }
            legacy.list.removeZeros();
            assertSameMainTable(wide.list, legacy.list, "第 " + round + " 轮后主表必须逐键一致");
        }
        assertTrue(wide.list.isEmpty() && legacy.list.isEmpty(), "两种退法最终都收敛到空池");
    }

    // ---- 精确读数接线 ----

    /**
     * 台账 #87：宽域直写／直取口与基类那两条 {@code long} 口<b>记进同一本</b>进出账。
     *
     * <p>一次批量、四个出口，手算表（全部打在 GLASS 这一格上，期望值写死、不由被测产出复算）：
     * <ol>
     *   <li>{@code insertExact(100, MODULATE)}：100 塞得进 {@code long} ⇒ 实现内部退回
     *       {@code insert(long)} ⇒ 由那一条覆写记 <b>一次</b> ⇒ {@code in=100}。
     *       宽域覆写若也跟着记，这里就是 200（双计）。</li>
     *   <li>{@code extractExact(60, MODULATE)}：这条口恒走侧表那张嘴、<b>不回调</b>
     *       {@code extract(long)} ⇒ 只能由宽域覆写记下 ⇒ {@code out=60}。
     *       改前这一格恒 {@code 0}（退网交付整段隐身）。</li>
     *   <li>{@code insert(25, MODULATE)} 基类 {@code long} 口 ⇒ {@code in=125}。</li>
     *   <li>{@code extract(10, MODULATE)} 基类 {@code long} 口 ⇒ {@code out=70}。</li>
     *   <li>两次 {@code SIMULATE}（{@code insertExact(7)}／{@code extractExact(9)}）
     *       一律不进账 ⇒ {@code in}/{@code out} 停在 125／70。</li>
     * </ol>
     * ⇒ {@code in − out = 125 − 70 = 55 = 100 − 60 + 25 − 10} ＝池子这一格的<b>净变化</b>。
     */
    @Test
    void everyWideAndLongMouthBooksIntoTheSameInOutLedger() {
        var ledger = new ParallelPoolLedger();
        var pool = ParallelCraftingCpuLogic.newLedgerBackedPool(ledger, ignored -> {
        });

        ledger.path("submit");
        pool.insertExact(GLASS, BigInteger.valueOf(100L), Actionable.MODULATE);
        assertEquals(100L, ledger.in(GLASS),
                "≤long 那一支只许记一次：宽域覆写跟着记就成 200（双计）");

        ledger.path("ecoPush");
        assertEquals(BigInteger.valueOf(60L),
                pool.extractExact(GLASS, BigInteger.valueOf(60L), Actionable.MODULATE));
        assertEquals(60L, ledger.out(GLASS),
                "退网走 extractExact：改前这张嘴不记账，out{} 里恒 0");

        ledger.path("submit");
        pool.insert(GLASS, 25L, Actionable.MODULATE);
        pool.extract(GLASS, 10L, Actionable.MODULATE);
        assertEquals(125L, ledger.in(GLASS), "基类 long 口与宽域口同一本");
        assertEquals(70L, ledger.out(GLASS), "基类 long 口与宽域口同一本");

        pool.insertExact(GLASS, BigInteger.valueOf(7L), Actionable.SIMULATE);
        pool.extractExact(GLASS, BigInteger.valueOf(9L), Actionable.SIMULATE);
        assertEquals(125L, ledger.in(GLASS), "SIMULATE 一律不进流水");
        assertEquals(70L, ledger.out(GLASS), "SIMULATE 一律不进流水");

        assertEquals(BigInteger.valueOf(55L), pool.amount(GLASS), "池子现量手算：100−60+25−10");
        assertEquals(55L, ledger.in(GLASS) - ledger.out(GLASS),
                "一次批量的对账：in − out ＝ 池子净变化");
    }

    /**
     * 台账 #87 的越界那一档：同一笔进出，{@code long} 域内的格对得齐，越界的两格<b>不再隐身</b>。
     *
     * <p>手算表：{@code insertExact(IRON, 2×Long.MAX)}（续传读回的形状）→ 越界那一支不回调
     * {@code insert(long)}，只能由宽域覆写记下 → {@code in=Long.MAX_VALUE}（这本账是 {@code long}
     * 读数账 ⇒ 报小不许回绕）。随后分轮退网 {@code extractExact(IRON, Long.MAX, MODULATE)} 交出
     * 一个天花板 → {@code out=Long.MAX_VALUE}。改前这两格都是 {@code 0}，池子里躺着 2×MAX 枚而
     * {@code in} 的 {@code resume} 那一格与 {@code out} 那一格一个字都不印——照 {@code debug.log}
     * 定性就会把"池子被人抽走一批"读成"没人伸手"。
     */
    @Test
    void wideMouthsBeyondLongNoLongerVanishFromThePoolReadout() {
        var ledger = new ParallelPoolLedger();
        var pool = ParallelCraftingCpuLogic.newLedgerBackedPool(ledger, ignored -> {
        });

        ledger.path("resume");
        pool.insertExact(IRON, LONG_MAX.multiply(BigInteger.TWO), Actionable.MODULATE);
        assertEquals(LONG_MAX.multiply(BigInteger.TWO), pool.amount(IRON), "池子记住的是精确值");
        assertEquals(Long.MAX_VALUE, ledger.in(IRON),
                "越界那一截过去在 in{} 里隐身（改前恒 0）：现在报一个天花板，报小不许回绕");
        assertTrue(ledger.describeKey(IRON).contains("resume=" + ParallelPoolLedger.SATURATED_READOUT),
                "带路径点名的读口也要看见这一笔（达顶那一格按标记印、不把饱和哨兵当量印）："
                        + ledger.describeKey(IRON));

        ledger.restore(ledger.path("store"));
        pool.extractExact(IRON, LONG_MAX, Actionable.MODULATE);
        assertEquals(Long.MAX_VALUE, ledger.out(IRON),
                "分轮退网那一笔过去整段不进 out{}（改前恒 0）");
        assertEquals(Long.MAX_VALUE, ledger.in(IRON), "入账侧读数不因此改动");
    }

    /**
     * 台账 #226 的那条饱和合并换共享档后的读数钉子：同一条路径上两个键各记 {@code Long.MAX_VALUE}，
     * {@code describePaths()} 的合计必须停在顶上（印 9223372036854775807），不许回绕成 {@code -2}。
     * 换的是只夹上界那一档——负方向如实相加，本读口 {@code noteOut} 按非正早退走不到负向，
     * 那一半的算术契约由 {@code bigint/ClampedMathTest} 钉。
     */
    @Test
    void pathTotalReadoutPinsTheCeilingInsteadOfWrappingAcrossTwoKeys() {
        var ledger = new ParallelPoolLedger();
        ledger.path("batch");
        ledger.noteOut(GLASS, Long.MAX_VALUE);
        ledger.noteOut(IRON, Long.MAX_VALUE);

        String described = ledger.describePaths();

        assertTrue(described.contains("batch=" + Long.MAX_VALUE + "("),
                "两格各一个天花板，合计只许夹在顶上：" + described);
        assertFalse(described.contains("=-2"),
                "回绕会把越顶的合计读成负数：" + described);
    }

    /** 停滞日志 inv= 段打精确真值；不再是主表那个饱和哨兵。 */
    @Test
    void stallSnapshotRendersTheTrueAmountWhereTheSentinelUsedToPrintMax() {
        WideCraftingInventory pool = newPool();
        for (int batch = 0; batch < 3; batch++) {
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        }
        BigInteger exact = LONG_MAX.multiply(BigInteger.valueOf(3L));

        String exactView = ParallelCraftingCpuLogic.describeInventoryExact(
                ParallelCraftingCpuLogic.snapshotPoolExact(pool));
        assertTrue(exactView.contains(exact.toString()),
                "inv= 段必须打出 3×Long.MAX 的精确十进制，而不是饱和哨兵：" + exactView);
        assertTrue(exactView.startsWith("kinds=1"), "形状与老 describeInventory 同一条：" + exactView);
    }

    /** 状态栏聚合：三源叠到越界时按饱和交出，绝不回绕；域内合计与旧写法逐位一致。 */
    @Test
    void menuAggregationSaturatesAcrossSourcesInsteadOfWrapping() {
        WideCraftingInventory pool = newPool();
        pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        pool.insert(IRON, 5L, Actionable.MODULATE);

        var job = newJob();
        var expected = new KeyCounter();
        expected.add(GLASS, Long.MAX_VALUE);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        // 未发任务还挂着一整发 MAX：MAX(池) + 2×MAX(缓冲) + MAX(任务) 若裸加必回绕
        var pattern = new OutputPattern(GLASS, Long.MAX_VALUE);
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = 1L;
        job.tasks.put(pattern, progress);
        job.waitingFor.insert(IRON, 7L, Actionable.MODULATE);

        var out = new KeyCounter();
        ParallelCraftingCpuLogic.getAllItems(pool, job, out);

        assertEquals(Long.MAX_VALUE, out.get(GLASS), "越界合计钉在天花板：报小不报大，永不回绕");
        assertTrue(out.get(GLASS) > 0L);
        assertEquals(12L, out.get(IRON), "域内合计与旧 addAll 写法逐位一致");
    }

    /** 无作业（收尾后残池）：聚合就是池子的饱和读数。 */
    @Test
    void menuAggregationWithoutJobReportsThePoolSaturated() {
        WideCraftingInventory pool = newPool();
        pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
        pool.insert(GLASS, 1L, Actionable.MODULATE);

        var out = new KeyCounter();
        ParallelCraftingCpuLogic.getAllItems(pool, null, out);
        assertEquals(Long.MAX_VALUE, out.get(GLASS));
    }

    // ---- 夹具 ----

    private static WideCraftingInventory newPool() {
        return new WideCraftingInventory(ignored -> {
        });
    }

    private static ParallelExecutingCraftingJob newJob() {
        return new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
    }

    private static ParallelCraftingCpuLogic.WidePoolInserter acceptAtMost(long perKeyPerRound) {
        return (what, offer) -> Math.min(offer, perKeyPerRound);
    }

    private static BigInteger totalExact(WideCraftingInventory pool) {
        BigInteger total = BigInteger.ZERO;
        for (var entry : pool.list) {
            total = total.add(pool.amount(entry.getKey()));
        }
        return total;
    }

    private static void assertSameMainTable(KeyCounter left, KeyCounter right, String message) {
        var keys = new HashSet<AEKey>();
        for (var entry : left) {
            keys.add(entry.getKey());
        }
        for (var entry : right) {
            keys.add(entry.getKey());
        }
        for (AEKey key : keys) {
            assertEquals(left.get(key), right.get(key), message + " 键 " + key + " 主表读数不一致");
        }
        assertEquals(left.size(), right.size(), message);
    }

    private static ICraftingPlan emptyPlan() {
        return new ICraftingPlan() {
            private final KeyCounter empty = new KeyCounter();

            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return empty;
            }

            @Override
            public KeyCounter emittedItems() {
                return empty;
            }

            @Override
            public KeyCounter missingItems() {
                return empty;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return Map.of();
            }
        };
    }

    /** 只挂一个产出键的样板：状态栏聚合按 outputs × 份数取值。 */
    private record OutputPattern(AEKey product, long perFiring) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(Items.FURNACE);
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(product, perFiring));
        }
    }
}
