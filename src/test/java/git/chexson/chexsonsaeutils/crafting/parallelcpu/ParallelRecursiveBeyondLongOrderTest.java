package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-D（复核抓到的第二条尾巴）：终点订单量<b>越出 {@code long}</b> 时收工判据要成立。
 *
 * <p>22bbc22 那条交付判据读的订单量出自 {@code ParallelExecutingCraftingJob#finalOutputOrderAmount}，
 * 而它原先只读 long 那两格（契约定版量／{@code GenericStack} 终点量），源头
 * {@code Ae2PlanConverter#toRecursivePlan} 交的是 {@code result.finalAmount()}——一个 {@code long}。
 * 订单越界那一刻这两格恒为 {@link Long#MAX_VALUE} 那道<b>哨兵</b> ⇒ 交回 {@code null}＝弃权 ⇒
 * 主线要的那一档（{@code >long} 订单）压根不会自动收工。
 *
 * <p>真值从这一档唯一还完整的地方取：随计划出门的<b>宽域份数账</b>（{@code WideCraftSnapshot}
 * ＝规划侧 {@code state.craftsWide()}）——终点份数 × 每发终点量，在建表那一刻反推一次。
 * 比较全程在宽域上做（交付账 {@code finalOutputDelivered} 本来就是 {@code WideKeyCounter}），
 * 两侧都不许在比较前窄化。
 *
 * <p>两型分开钉（本仓口径：饱和值是哨兵，不许当量用）：
 * <ul>
 *   <li>哨兵 + 反推不出真值（这份计划里没有产出终点键的样板）⇒ {@code null}＝不判；</li>
 *   <li>哨兵 + 反推出的真值恰好是 {@code Long.MAX_VALUE} ⇒ 是一个数得出来的数，照判。</li>
 * </ul>
 *
 * <h2>FIX-G：反推量只会偏小的那一截</h2>
 * 终点键有一截<b>压根不进任务表</b>——它随计划直接出门（构造那一趟把 {@code plan.emittedItems()}
 * 插进 {@code waitingFor}，不落在 {@code patternTimes()}／宽域份数账里）。上一版的反推产量只把
 * 任务表那几张样板的产出相加，于是漏掉这一截 ⇒ 订单量<b>偏小</b> ⇒ 交付账刚交满"毛量"就判收工，
 * 而订单实际还差那一截。这一刀把出门那一截也并进同一本终点产量（宽域相加，两侧都不在比较前窄化）；
 * 认终点键仍走 {@code AEKey#matches} 那一句，不是终点键的出门量一枚都不算（型一那条臂同时钉住）。
 */
final class ParallelRecursiveBeyondLongOrderTest {

    private static final AEKey PRODUCT = AEItemKey.of(new net.minecraft.world.item.ItemStack(Items.CHEST));
    private static final long LONG_MAX = Long.MAX_VALUE;
    /** 订单量真值：1e19 枚，比 {@code long} 天花板多出 7.77e17 枚。 */
    private static final BigInteger ORDER_WIDE = new BigInteger("10000000000000000000");
    private static final BigInteger TAIL_CHUNK = ORDER_WIDE.subtract(BigInteger.valueOf(LONG_MAX));
    /** FIX-G：终点键里<b>不经任务表</b>、随计划直接出门的那一截（1e12 枚，单笔塞得进 long）。 */
    private static final BigInteger EMITTED_CHUNK = new BigInteger("1000000000000");

    /**
     * 主线那一档：{@code >long} 订单、实物<b>交齐</b> ⇒ 收工判据必须成立。
     *
     * <p>交付分两轮进账（AE2 交付通道单笔 ≤ {@code long}），交付账按宽域累加到 1e19。
     * 未修树上这一条红：订单量交回 {@code null} ⇒ 判据恒假。
     */
    @Test
    void beyondLongOrderClosesOncePhysicallyDeliveredInFull() {
        var producer = new FakePattern(AEItemKey.of(Items.GLOWSTONE), new GenericStack(PRODUCT, 1L));
        var job = newJob(producer, ORDER_WIDE, Map.of(), -1L);

        // 差一口气：先交一轮 long 天花板，还欠 7.77e17 枚
        job.addFinalOutputDelivered(PRODUCT, LONG_MAX);
        assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "只交了 " + LONG_MAX + " 枚、订单是 1e19 枚：差 " + TAIL_CHUNK + " 枚就不许收工");

        // 第二轮把剩下的交齐
        job.addFinalOutputDelivered(PRODUCT, TAIL_CHUNK.longValueExact());
        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "终点订单量 > Long.MAX_VALUE、实物交齐 ⇒ 收工判据必须成立（判据读不出订单量"
                        + "就是把 long 出口那道哨兵当成『没有量』，这一档于是永远走不到 finishJob）："
                        + "交付账=" + job.finalOutputDeliveredAmount().exactValue());

        // 同一把尺的两侧读数：订单量取的是宽域真值，不是饱和哨兵
        assertNotNull(job.finalOutputOrderAmount(), "哨兵这一档必须由宽域份数账反推出订单量");
        assertEquals(ORDER_WIDE, job.finalOutputOrderAmount().exactValue(),
                "订单量必须等于 1e19 枚真值——交回 Long.MAX_VALUE 就是把哨兵当量");
        assertEquals(ORDER_WIDE, job.finalOutputDeliveredAmount().exactValue(),
                "两轮实物交付在宽域交付账上凑齐 1e19 枚");
    }

    /**
     * 两型分开：读数是哨兵时，<b>反推不出真值</b>那一型明确不判；<b>真值恰好是
     * {@code Long.MAX_VALUE}</b> 那一型照判。两型在 long 出口上长得一模一样（都是
     * {@code 9223372036854775807}），分开它们的只有宽域份数账。
     */
    @Test
    void sentinelWithoutDerivableTruthJudgesNothingWhileTrueLongMaxJudges() {
        // 型一：任务表里没有产出终点键的样板 ⇒ 反推不出订单量 ⇒ 不判（交满也不收工）。
        //
        // FIX-G 顺手将这一臂的夹具改成<b>空任务表</b>：上一版把 unrelated×1 发留在表上，
        // {@code isDyeableRecursiveJobComplete} 那条老臂（任务表空 且 waitingFor 空）当场恒假，
        // 这一句 assertFalse 于是被夹具顺带满足——它压根没打在交付臂上。现在表是空的，拦住老臂的
        // 只剩 {@code waitingFor} 上那一截不经任务表直接出门的残量，断言问的才是交付臂本身：
        // 订单量算不出来 ⇒ 交付量凑得再满也不许放行。
        var unrelated = new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L));
        var noTruth = newJob(unrelated, null, Map.of(), -1L, emittedIron(5L));
        assertTrue(noTruth.tasks.isEmpty(), "夹具：任务表是空的，这一句判据只能由交付臂给出");
        assertFalse(noTruth.waitingFor.list.isEmpty(),
                "夹具：不经任务表直接出门的那一截挂在 waitingFor 上，老臂（两格全空）恒假");
        assertNull(noTruth.finalOutputOrderAmount(),
                "反推不出真值就明说不判：不许拿饱和哨兵顶一个'看起来到了'的订单量；"
                        + "出门那一截不是终点键，也不许被算成终点产量");
        noTruth.addFinalOutputDelivered(PRODUCT, LONG_MAX);
        assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(noTruth),
                "订单量算不出来的那一型只能等任务表自己清空，不许被交付量凑齐（空表下这一句打的正是交付臂）");

        // 型二：终点份数＝Long.MAX_VALUE、每发 1 枚 ⇒ 真值就是 9223372036854775807，不是哨兵
        var producer = new FakePattern(AEItemKey.of(Items.REDSTONE_BLOCK), new GenericStack(PRODUCT, 1L));
        var trueMax = newJob(producer, null, Map.of(producer, LONG_MAX), -1L);
        assertNotNull(trueMax.finalOutputOrderAmount(),
                "真值恰好是 Long.MAX_VALUE 的那一型是一个数得出来的数，必须与'反推不出'分开");
        assertEquals(BigInteger.valueOf(LONG_MAX), trueMax.finalOutputOrderAmount().exactValue(),
                "订单量＝终点份数 Long.MAX × 每发 1 枚");
        trueMax.addFinalOutputDelivered(PRODUCT, LONG_MAX);
        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(trueMax),
                "真 Long.MAX 订单交齐就该收工：把这一型也一并弃权，等于把真订单当成哨兵扔掉");
    }

    /**
     * FIX-G：终点键有一截<b>不经任务表</b>、随计划直接出门（构造那趟把它插进 {@code waitingFor}）时，
     * 反推出来的订单量必须含那一截。
     *
     * <p>漏掉它的后果是<b>偏小＝提前收工</b>：交付账刚交满"任务表那一截的毛量"就判成收工，
     * 而订单实际还差直接出门的那一截。这一档只在哨兵那一型上被读到（订单越出 {@code long}，
     * {@code long} 出口交回的是哨兵，唯一还完整的读数就是这份反推产量）。
     *
     * <p>未修树红在 {@code finalOutputOrderAmount} 少 {@code 1e12} 枚、且交满毛量就被判成完成。
     */
    @Test
    void terminalChunkEmittedOutsideTheTaskTableStillCountsIntoTheOrder() {
        var producer = new FakePattern(AEItemKey.of(Items.GLOWSTONE), new GenericStack(PRODUCT, 1L));
        // 订单真值 1e19 枚＝任务表那一截（1e19-1e12 发 × 每发 1 枚）＋直接出门那一截（1e12 枚）
        var job = newJob(producer, ORDER_WIDE.subtract(EMITTED_CHUNK), Map.of(), -1L,
                emittedTerminal(EMITTED_CHUNK.longValueExact()));

        assertNotNull(job.finalOutputOrderAmount(), "哨兵这一档必须由宽域终点量反推订单量");
        assertEquals(ORDER_WIDE, job.finalOutputOrderAmount().exactValue(),
                "订单量必须含直接出门的那一截 1e12 枚——少算它就是偏小，偏小＝提前收工");

        // 交满"不含那一截的毛量"：任务表那一截整笔交齐（单笔 ≤ long，分两轮进账）
        job.addFinalOutputDelivered(PRODUCT, LONG_MAX);
        job.addFinalOutputDelivered(PRODUCT,
                ORDER_WIDE.subtract(EMITTED_CHUNK).subtract(BigInteger.valueOf(LONG_MAX)).longValueExact());
        assertEquals(ORDER_WIDE.subtract(EMITTED_CHUNK), job.finalOutputDeliveredAmount().exactValue(),
                "交付账此刻正好是任务表那一截");
        assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "还差直接出门的那一截 1e12 枚就判收工＝提前收工：订单量偏小是这一条的根因");

        job.addFinalOutputDelivered(PRODUCT, EMITTED_CHUNK.longValueExact());
        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "补上那一截才算交齐（两侧都不许在比较前窄化）");
    }

    /**
     * 建一份带递归契约的计划：终点件 {@code PRODUCT × Long.MAX_VALUE}（{@code >long} 订单在
     * {@code long} 出口上就长成这一格），契约定版量交回 {@code -1}＝没有定版量，退回计划终点量。
     *
     * @param wideFirings 走宽域份数账的发数（{@code >long} 那一档）；给 {@code null} 即不进这份账
     * @param longTimes   AE2 那份代表值字典（塞得进 {@code long} 的发数从这一格来）
     */
    private static ParallelExecutingCraftingJob newJob(IPatternDetails producer,
            BigInteger wideFirings, Map<IPatternDetails, Long> longTimes, long contractAmount) {
        return newJob(producer, wideFirings, longTimes, contractAmount, new KeyCounter());
    }

    /**
     * 同一份夹具，多给一格<b>不经任务表直接出门</b>的量（{@code plan.emittedItems()}：构造那一趟
     * 把它插进 {@code waitingFor}，{@code patternTimes()} 与宽域份数账里都没有它）。
     */
    private static ParallelExecutingCraftingJob newJob(IPatternDetails producer,
            BigInteger wideFirings, Map<IPatternDetails, Long> longTimes, long contractAmount,
            KeyCounter emittedItems) {
        var counter = new WideCraftCounter();
        if (wideFirings != null) {
            counter.add(producer, PlanAmount.of(wideFirings));
        }
        var base = new CraftingPlan(new GenericStack(PRODUCT, LONG_MAX), 0L, true, false,
                new KeyCounter(), emittedItems, new KeyCounter(), longTimes);
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                contractAmount, Map.of(), Map.of(), WideLedgerSnapshot.empty(),
                WideCraftSnapshot.capture(counter));
        return new ParallelExecutingCraftingJob(plan, ignored -> {
        }, null, null);
    }

    /** 出门那一截就是终点键本身：订单量必须把它算进去。 */
    private static KeyCounter emittedTerminal(long amount) {
        var emitted = new KeyCounter();
        emitted.add(PRODUCT, amount);
        return emitted;
    }

    /** 出门那一截是<b>别的</b>键：一枚也不许算进终点产量。 */
    private static KeyCounter emittedIron(long amount) {
        var emitted = new KeyCounter();
        emitted.add(AEItemKey.of(Items.IRON_INGOT), amount);
        return emitted;
    }

    /** 只产一格的样板替身：终点键的产量按 {@code 每发量 × 发数} 算。 */
    private record FakePattern(AEItemKey definition, GenericStack output) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(output);
        }
    }
}
