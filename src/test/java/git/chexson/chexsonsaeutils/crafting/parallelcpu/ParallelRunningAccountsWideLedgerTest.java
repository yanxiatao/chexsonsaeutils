package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行 CPU 三条<b>流水账</b>（报称产出 {@code accountAnnounced} / 实收回池 {@code accountReturned}
 * / 提走用料 {@code accountTaken}）的宽域账用例：账本身能记住越出 {@code long} 的累计量。
 *
 * <p>这三本账是<b>跨批复加</b>的：单笔恒 {@code ≤ long}（推送粒度），但合计没有上界——
 * {@code accountAnnounced} 与产出缓冲 {@code waitingFor} 吃的是同一条申报流
 * （{@code ParallelCraftingCpuLogic#reserveOne} 一处写两本），上一刀已经坐实那条流能超
 * {@code long}。老口径用 AE2 {@link KeyCounter} 裸加，越界即回绕：轻则报出一个既不是真值也
 * 不是天花板的数，重则<b>回绕成负</b>，"报称多、回池少即产出漏了"这条判据被直接读反。
 *
 * <p>四条底线各一个用例：
 * <ul>
 *   <li>越界累计逐位精确、不回绕、不变负；</li>
 *   <li>回绕修复前后<b>同一组数</b>的差分：老 {@link KeyCounter} 给负读数，宽域账给精确值；</li>
 *   <li>对外 {@code long} 读数（停滞日志那三项）仍是饱和 {@code Long.MAX_VALUE}，口径一字不变；</li>
 *   <li>申报流一次划分仍成立：报称账 = 等实物 + 预期入网，不重复计也不漏计。</li>
 * </ul>
 *
 * <p>夹具沿用本包既有口径（空计划 + 空 link），不起第二套。
 */
final class ParallelRunningAccountsWideLedgerTest {

    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** 三批各挂 {@code Long.MAX_VALUE} 枚：报称账精确到 3×，对外 long 读数钉在天花板。 */
    @Test
    void announcedAccountRemembersTheExactTotalAcrossBatchesBeyondLong() {
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, Long.MAX_VALUE);

        for (int batch = 1; batch <= 3; batch++) {
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        }

        var exactTotal = LONG_MAX.multiply(BigInteger.valueOf(3L));
        assertEquals(exactTotal, job.accountAnnounced.getExact(PRODUCT),
                "报称产出流水账跨批复加逐位精确：这才是账本本身装得下 >long 的意思");
        assertTrue(job.accountAnnounced.hasOverflow(), "越界那一截确实进了溢出侧表");
        assertEquals(Long.MAX_VALUE, job.accountAnnounced.getLong(PRODUCT),
                "对外 long 读数（停滞日志的 ann=）仍是饱和天花板：口径与本刀之前一字不变");
        assertNotEquals(exactTotal.longValue(), job.accountAnnounced.getLong(PRODUCT),
                "精确值不在对外 long 读口上——那条口只报饱和");

        var legacy = new KeyCounter();
        for (int batch = 1; batch <= 3; batch++) {
            legacy.add(PRODUCT, Long.MAX_VALUE);
        }
        assertEquals(Long.MAX_VALUE - 2L, legacy.get(PRODUCT),
                "老口径：3×Long.MAX 裸加回绕成 MAX-2，既不是真值也不是天花板");
        assertEquals(Long.MAX_VALUE, job.accountAnnounced.getLong(PRODUCT),
                "新口径至少被钉在 long 天花板下：判「够不够」只会低估、不会假通过");
    }

    /** 同一组数（3 × 4e18 枚）的差分：老 {@link KeyCounter} 回绕成负数，宽域账给精确值。 */
    @Test
    void oldLongLedgerWrappedNegativeOnTheSameStreamWhileWideLedgerStaysExact() {
        long perBatch = 4_000_000_000_000_000_000L;
        var exactTotal = BigInteger.valueOf(perBatch).multiply(BigInteger.valueOf(3L));
        assertTrue(exactTotal.compareTo(LONG_MAX) > 0,
                "本用例确实越界：1.2e19 枚 > 9.22e18，正是老口径记不住的那一档");

        var legacy = new KeyCounter();
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, perBatch);
        for (int batch = 1; batch <= 3; batch++) {
            legacy.add(PRODUCT, perBatch);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        }

        assertTrue(legacy.get(PRODUCT) < 0L,
                "旧口径会算出负数的那组数：回绕后的读数把「报称产出」读成了倒欠");
        assertEquals(-6_446_744_073_709_551_616L, legacy.get(PRODUCT),
                "回绕值可复算：1.2e19 - 2^64");
        assertEquals(exactTotal, job.accountAnnounced.getExact(PRODUCT),
                "新口径同一组数给精确值，不回绕、不变负");
        assertTrue(job.accountAnnounced.getLong(PRODUCT) > 0L, "对外读数也不再是负数");
        assertEquals(Long.MAX_VALUE, job.accountAnnounced.getLong(PRODUCT), "对外读数按饱和收口");
    }

    /** 实收回池账跟着实物回池一笔笔加：越界后仍精确，"报称 − 回池"那一截读得出来。 */
    @Test
    void returnedAccountTracksPhysicalBatchReturnsWithoutWrapping() {
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, Long.MAX_VALUE);
        for (int batch = 1; batch <= 3; batch++) {
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        }
        assertEquals(LONG_MAX.multiply(BigInteger.valueOf(3L)),
                WideCraftingInventory.amount(job.waitingFor, PRODUCT));

        // 两批实物回池：与 insertInternal 同一条"先 SIMULATE 夹住、再 MODULATE 抽走并记账"
        returnOnePhysicalBatch(job, Long.MAX_VALUE);
        returnOnePhysicalBatch(job, Long.MAX_VALUE);

        assertEquals(LONG_MAX.multiply(BigInteger.TWO), job.accountReturned.getExact(PRODUCT),
                "实收回池账逐位精确：ret= 的读数不该是回绕出来的数");
        assertEquals(Long.MAX_VALUE, job.accountReturned.getLong(PRODUCT),
                "对外 long 读数按饱和收口，日志里 ret 与 ann 同为天花板");
        assertTrue(job.accountReturned.getLong(PRODUCT) >= 0L, "绝不回绕成负");
        assertEquals(LONG_MAX, job.accountAnnounced.getExact(PRODUCT)
                        .subtract(job.accountReturned.getExact(PRODUCT)),
                "判据「报称多、回池少」在宽域账上仍算得出：还差 1×Long.MAX 枚没回来");
        assertEquals(LONG_MAX, WideCraftingInventory.amount(job.waitingFor, PRODUCT),
                "账与缓冲同步下降：销一笔少一笔，不双计");
    }

    /** 申报流一次划分：报称账 = 等实物 + 预期入网，越界量级下合计仍不多也不少。 */
    @Test
    void announcedAccountStillEqualsPhysicalPlusNetworkAcrossWideBatches() {
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, Long.MAX_VALUE);
        var plannedToNetwork = new KeyCounter();
        plannedToNetwork.add(PRODUCT, 4_000_000_000_000_000_000L);
        var declaredTotal = BigInteger.ZERO;

        for (int batch = 1; batch <= 3; batch++) {
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), plannedToNetwork);
            declaredTotal = declaredTotal.add(LONG_MAX);
        }

        assertEquals(declaredTotal, job.accountAnnounced.getExact(PRODUCT),
                "报称账按全额记，与产物走哪条路无关");
        assertEquals(declaredTotal,
                WideCraftingInventory.amount(job.waitingFor, PRODUCT)
                        .add(job.expectedToNetwork(PRODUCT).exactValue()),
                "等实物 + 预期入网 = 申报总量：三本账在越界量级下仍对得齐"
                        + "（预期入网那一截读的是宿主自报的计划量，不是入网回执）");
    }

    /**
     * 回池一笔：镜像 {@code ParallelCraftingCpuLogic#insertInternal} 的动作形状——
     * 先用 SIMULATE 读出产出的预期上限夹住本批，再 MODULATE 抽走并记 {@code accountReturned}。
     */
    private static void returnOnePhysicalBatch(ParallelExecutingCraftingJob job, long requested) {
        AEKey what = PRODUCT;
        long clamped = Math.min(requested, job.waitingFor.extract(what, requested,
                Actionable.SIMULATE));
        if (clamped <= 0L) {
            return;
        }
        job.waitingFor.extract(what, clamped, Actionable.MODULATE);
        job.accountReturned.add(what, clamped);
    }

    private static ParallelExecutingCraftingJob newJob() {
        return new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
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
}
