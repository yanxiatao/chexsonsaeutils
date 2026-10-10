package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账 #217：剩余发数 {@code > long} 的样板，在<b>产出贷方账</b>里等于不存在。
 *
 * <p>执行侧那一格的原文（实机 2026-10-03 10:59）：同一行里并排着
 * {@code left=2.26814709091078720820e20}（配额账的宽域真值）与 {@code toMake=0}，紧跟
 * {@code src=NONE}——"这份任务表里没有一张样板的输出含这一枚"（{@code src=} 读 {@code job.tasks}，
 * 说不到图上有没有生产路线，那一层要读规划侧 {@code describeHoles} 的 {@code producers=}）。
 * 病在 {@code ParallelPlanReservation.representableFirings}
 * 对 {@code > long} 交回 {@code 0}，而那一趟遍历把"锁不锁"与"产不产"绑成了同一句：
 * {@code firings <= 0} 即整条 {@code continue} ⇒ 这张样板计划产的那一截<b>整个不进贷方账</b>。
 * 不进贷方账之后，下游 {@code reservedForExclusiveConsumers} 的
 * {@code releaseable = 产出 − 别人认领} 读成 0 ⇒ 这一键对每位消费者扣到底。
 *
 * <p>同族既有定版同一条法（#106／#100／#52／#98）：<b>内部精确宽域，饱和只在 AE2 边界出口；
 * 饱和值是哨兵不是量</b>。本文件四臂钉的就是这一条分家：
 * <ul>
 *   <li>存在性臂（主臂）：{@code > long} 发数的样板那笔产出照记，且交回宽域真值十位数；</li>
 *   <li>锁与量分家臂：撤锁那一型（"窄化读数撞上天花板 ⇒ 不下悲观锁"）仍不许让量消失；</li>
 *   <li>正向臂：正常（{@code <= long}）订单的占料锁、{@code usableFor} 读数逐字不变；</li>
 *   <li>窄值退回臂：发数装得进 {@code long} 时走的就是原来那条窄路，一次宽域升档都不许多。</li>
 * </ul>
 *
 * <p>本刀<b>不</b>碰 {@code ParallelExecutingCraftingJob#accountPlannedEmit}（停滞行
 * {@code planEmit=} 那本快照账体，台账 #217 批次二b 第 4 条；该账体已由任务 #219 条目⑥从
 * AE2 {@code KeyCounter} 换成 {@code WideKeyCounter}，钉它的是
 * {@code ParallelSubmitArrivalQuotaTest}），
 * 也<b>不</b>碰 {@code crafting/graphplan/**} 与 {@code ParallelBatchExtraction} 的算式。
 */
final class ParallelWideProductionCreditTest {

    /** 主臂那一格的剩余发数真值：1e19 枚，比 {@code long} 天花板还多出 7.77e17 枚。 */
    private static final BigInteger FIRINGS_WIDE = new BigInteger("10000000000000000000");
    /** 复加顶格那一型的每笔量：两笔之和正好比天花板多一枚。 */
    private static final long HALF_MAX_PLUS_ONE = Long.MAX_VALUE / 2L + 1L;
    /** 贷方账里那一枚中间键（实机形态：{@code mekanism:redstone} 那一格）。 */
    private static final AEKey MIDDLE = AEItemKey.of(new ItemStack(Items.NETHERITE_SCRAP));
    /** 被占料锁盯着的那一枚。 */
    private static final AEKey ORE = AEItemKey.of(new ItemStack(Items.RAW_IRON));
    /** 退路上那一枚：它的量决定伸手那一格改不改得了道。 */
    private static final AEKey COPPER = AEItemKey.of(new ItemStack(Items.RAW_COPPER));
    /** 各家样板自己的产物（不参与这一族读数）。 */
    private static final AEKey PRODUCT = AEItemKey.of(new ItemStack(Items.IRON_BLOCK));
    /** 谁也吃不上的那一枚，只用来给样板填一条不相关的输入。 */
    private static final AEKey FILLER = AEItemKey.of(new ItemStack(Items.DIRT));

    private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
    private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

    /**
     * <b>存在性臂（主臂，HEAD 必红）</b>：某样板剩余发数 {@code > long}（1e19 发）、每发产 1 枚
     * {@code MIDDLE} ⇒ 这一笔产出必须在贷方账上，而且交回的是<b>宽域真值十位数</b>。
     *
     * <p>HEAD 红在这里：那一趟拿 {@code representableFirings}（越界交回 0）当这一行<b>存不存在</b>
     * 的判据，整条 {@code continue} ⇒ 贷方量读成 0、该键根本不在账上（红件逐字
     * {@code expected: <10000000000000000000> but was: <0>}）——正是实机同一行里
     * {@code toMake=0} 与 {@code left=2.27e20} 同时成立的那一型。
     *
     * <p>只红这一条的变异：量那一趟的发数出口退回 {@code representableFirings}（越界即整条跳过），
     * 或<b>单笔</b>顶格那一支挨着名单补一句 {@code remove}。分家臂走的是<b>复加</b>顶格那一支
     * 且发数装得进 long，正向臂与窄值臂压根不顶格——两把变异都碰不到别的臂。
     */
    @Test
    void beyondLongFiringsPatternStillBooksItsPlannedOutputInWideTruth() {
        var producer = pattern(Items.BLAST_FURNACE, FILLER, 1L, MIDDLE, 1L);
        var job = newJob();
        job.tasks.put(producer, wideProgress(FIRINGS_WIDE));

        var reservation = MaterialAllocationFixtures.build(
                job.tasks.entrySet(), Map.of(ORE, 10L), NO_PLAN, NO_TAKEN);

        PlanAmount credit = reservation.pendingProductionExact(MIDDLE);
        assertEquals("10000000000000000000", credit.exactValue().toString(),
                "剩余发数 > long 的那张样板，计划产出必须按宽域真值进贷方账——"
                        + "拿“能不能塞进 long”当存在性判据就是把这笔产出整个撤掉");
        assertEquals(FIRINGS_WIDE, credit.exactValue(),
                "贷方账读数＝发数真值 × 每发 1 枚，一个数都不许多也不许少");
        assertFalse(credit.isFast(),
                "这一笔本来就越出 long：账上摆的是真值，不是 0，也不是那道天花板");
    }

    /**
     * <b>锁与量分家臂</b>：撤锁那一型（贷方量顶到 {@code long} 天花板 ⇒
     * {@code productionHasNoTrustworthyReading} 为真）仍<b>不许</b>让量消失。
     *
     * <p>夹具取的是 HEAD 里"每笔都装得进 {@code long}、合计装不进"那一型：两张样板各产
     * {@code Long.MAX/2 + 1} 枚 {@code MIDDLE}——单笔都表得出来，合计 {@code
     * 9223372036854775808} 正好比天花板多一枚。HEAD 在这一支上写的是 {@code dropAsUntrustworthy}
     * ＝{@code map.remove} 挨着 {@code untrusted.add}：名单那一半是对的（少一道悲观锁），
     * {@code remove} 那一半就是把"锁算式跑不出可信数"读成"这一键没有产出"，于是
     * {@code releaseable} 记成 0、整份占料锁扣到底——实机 {@code toMake=0} 那一格就是这么来的。
     *
     * <p>只红这一条的变异：复加顶格那一支把"撤锁"写成"撤量"（挨着名单补一句 {@code remove}）。
     * 主臂那一键走的是<b>单笔</b>顶格那一支（一笔就 {@code 1e19} 枚），两型各在一处，这把变异
     * 碰不到它；本用例第一句（锁）与最后一句（锁放开后池子原样交回）在变异下照旧绿，
     * 只有量那一句当场红。
     */
    @Test
    void revokingThePessimisticLockKeepsEveryUnitOfTheCredit() {
        var producerA = patternWithFirstCandidate(Items.GRINDSTONE, FILLER, 1L,
                MIDDLE, HALF_MAX_PLUS_ONE);
        var producerB = patternWithFirstCandidate(Items.SMOKER, FILLER, 1L,
                MIDDLE, HALF_MAX_PLUS_ONE);
        var locker = patternWithFirstCandidate(Items.FURNACE, MIDDLE, 100L, PRODUCT, 1L);
        var job = newJob();
        job.tasks.put(producerA, progress(1L));
        job.tasks.put(producerB, progress(1L));
        job.tasks.put(locker, progress(1L));

        var reservation = MaterialAllocationFixtures.build(
                job.tasks.entrySet(), Map.of(MIDDLE, 500L), NO_PLAN, NO_TAKEN);

        assertTrue(reservation.productionHasNoTrustworthyReading(MIDDLE),
                "前提（锁那一腿）：合计顶穿 long ⇒ 这一键在 long 域锁算式里跑不出可信读数 ⇒ 不下悲观锁");
        assertEquals("9223372036854775808", reservation.pendingProductionExact(MIDDLE)
                        .exactValue().toString(),
                "撤锁不许顺带撤量：两笔各 (MAX+1)/2 复加出来的那一枚越界量照旧在账上，"
                        + "一笔都不因为进了名单而消失");
        assertFalse(reservation.pendingProductionExact(MIDDLE).isFast(),
                "合计越出 long 的就是真值本身，不是天花板读数，也不是被撤掉的 0");

        var asker = patternWithFirstCandidate(Items.CARTOGRAPHY_TABLE, MIDDLE, 1L, PRODUCT, 1L);
        assertEquals(500L, reservation.usableFor(MIDDLE, 500L, asker, 1L,
                        asker.getInputs()[0], true),
                "锁确实放开了：池子里那 500 枚原样交回，不是被一整份兑不了的债权扣光");
    }

    /**
     * <b>正向臂</b>：正常（每笔都装得进 {@code long}）订单的一个读数都不动。
     *
     * <p>这一格钉的是"分家不是放开"：贷方 {@code 60} 枚而别人按第一候选认领 {@code 100} 枚 ⇒
     * 能兑现的只有 {@code 0} 枚，剩下那 {@code 100} 枚占料锁照旧扣到底——{@code usableFor}
     * 交回 {@code 400} 而不是池子现量。这一型在 HEAD 与改后必须是同一个数。
     *
     * <p>只红这一条的变异：把顶格判据放宽成"任何正量都算不可信"（{@code >= 0} 之类的写法）——
     * 于是这一键也进了名单、整道锁被放开，第一句（名单为空）与最后一句（照扣 100 枚）同时红。
     * 把宽域账当成"放开夹子"的理由，本用例拦的就是这一步；别的臂没有靠这道锁取数的断言。
     */
    @Test
    void normalOrdersKeepTheirReservationAndCreditReadingsByteForByte() {
        var locker = patternWithFirstCandidate(Items.FURNACE, ORE, 100L, PRODUCT, 1L);
        var oreProducer = patternWithFirstCandidate(Items.SMOKER, FILLER, 1L, ORE, 60L);
        var copperProducer = patternWithFirstCandidate(Items.GRINDSTONE, FILLER, 1L, COPPER, 5L);
        var job = newJob();
        job.tasks.put(locker, progress(1L));
        job.tasks.put(oreProducer, progress(1L));
        job.tasks.put(copperProducer, progress(1L));

        var reservation = MaterialAllocationFixtures.build(
                job.tasks.entrySet(), Map.of(ORE, 500L), NO_PLAN, NO_TAKEN);

        assertEquals(0, reservation.untrustedProductionKeyCount(),
                "正常订单里一个键都不该进“锁不可信”的名单：越界判据不许顺手放宽");
        assertFalse(reservation.productionHasNoTrustworthyReading(ORE),
                "贷方 60 枚装得进 long，就是可信口径：这一键的占料锁照下");
        assertEquals("60", reservation.pendingProductionExact(ORE).exactValue().toString(),
                "窄值那一档的贷方读数与 HEAD 逐字相同");
        assertEquals("5", reservation.pendingProductionExact(COPPER).exactValue().toString(),
                "退路上那一枚的贷方读数同样一字未动");

        var reroutable = new FakeInput(new GenericStack[]{new GenericStack(ORE, 1L),
                new GenericStack(COPPER, 1L)}, 1L, null);
        var consumer = new FakePattern(AEItemKey.of(new ItemStack(Items.CARTOGRAPHY_TABLE)),
                new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                new IPatternDetails.IInput[]{reroutable});
        assertEquals(400L, reservation.usableFor(ORE, 500L, consumer, 1L, reroutable, false),
                "占料锁逐字不变：能兑现的产出只有 60 枚，抵不上别人认领的 100 枚 ⇒ 那 100 枚照扣");
    }

    /**
     * <b>窄值退回臂</b>：{@code representableFirings} 走窄支时与 HEAD 同一条路、同一个读数，
     * 一次宽域升档都不许多；而锁那一腿撤下一格的锁，也不许带走这张样板的量。
     *
     * <p>夹具是 {@code 1e13} 发（正好装得进 {@code long} ⇒ 发数走的正是 HEAD 那条窄支）× 每发
     * {@code 1e6} 枚（占料那一格装不下，合计 {@code 1e19} 枚）⇒ 老口径那一格 {@code units ==
     * Long.MAX_VALUE} 即 {@code continue} 的豁免一字未动：这一格既不进占料锁也不进认领。
     * 同一张样板每发产 1 枚 × {@code 1e13} 发＝{@code 1e13} 枚照旧进贷方账，而且落在
     * {@code PlanAmount} 的 {@code long} 快路径上——规范化不变式是 {@code wide == null 当且仅当
     * 值落在 long 域}，所以 {@code isFast()} 恒真就是"这一趟一条 BigInteger 都没多"的字节水位自证。
     *
     * <p>只红这一条的变异：把"撤锁"写成"撤量"挪到锁那一腿——{@code units == Long.MAX_VALUE}
     * 那一格不再跳过，而是把那道饱和读数当占料记进锁账。第一句当场红（池子里 500 枚被一堵兑不了
     * 的墙扣光、{@code usable=0}），就是实机 {@code locked=[minecraft:lava
     * stored=9223372036854775807 usable=0] noCand=3154} 那一跑。其余三臂的夹具里没有会饱和的格子
     * （正向臂每格 1～100 枚、分家臂 1 发、主臂整张被窄尺跳过），这一把碰不到它们。
     */
    @Test
    void narrowFiringsTakeTheNarrowPathAndUnscalableSlotKeepsItsCredit() {
        var saturatingSlot = patternWithFirstCandidate(Items.FURNACE, ORE, 1_000_000L, MIDDLE, 1L);
        var job = newJob();
        job.tasks.put(saturatingSlot, progress(10_000_000_000_000L));

        var reservation = MaterialAllocationFixtures.build(
                job.tasks.entrySet(), Map.of(ORE, 500L), NO_PLAN, NO_TAKEN);

        var asker = patternWithFirstCandidate(Items.CARTOGRAPHY_TABLE, ORE, 1L, PRODUCT, 1L);
        assertEquals(500L, reservation.usableFor(ORE, 500L, asker, 1L, asker.getInputs()[0], true),
                "窄支那一格的豁免一字未动：锁主自己要分几万轮才吃下的那一截，不配把 500 枚判成"
                        + "别人取不动——把饱和读数记进占料账就是把锁乘成一堵墙");

        PlanAmount credit = reservation.pendingProductionExact(MIDDLE);
        assertEquals("10000000000000", credit.exactValue().toString(),
                "每发 1 枚 × 1e13 发＝1e13 枚：与换域前同一条乘算、同一个数");
        assertTrue(credit.isFast(),
                "字节水位：这一趟一次宽域升档都不许多——分家换的是出口，"
                        + "不是把 long 快路径换成 BigInteger");
    }

    private static ParallelExecutingCraftingJob newJob() {
        ICraftingPlan plan = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        return new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    /** 越出 {@code long} 的任务进度：走真实写入口（{@code addFirings}），饱和镜像自动成哨兵。 */
    private static ParallelExecutingCraftingJob.TaskProgress wideProgress(BigInteger firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(firings));
        return progress;
    }

    /** 一张"吃 {@code input} 一枚、产 {@code output} 若干枚"的单槽样板。 */
    private static FakePattern pattern(Item definition, AEKey input, long inputUnits,
            AEKey output, long outputUnits
    ) {
        return patternWithFirstCandidate(definition, input, inputUnits, output, outputUnits);
    }

    /** 一张只有一个候选的单槽样板（退料键为 null ⇒ 不是模具槽）。 */
    private static FakePattern patternWithFirstCandidate(Item definition, AEKey input, long inputUnits,
            AEKey output, long outputUnits
    ) {
        return new FakePattern(AEItemKey.of(new ItemStack(definition)),
                new GenericStack[]{new GenericStack(output, outputUnits)},
                new IPatternDetails.IInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(input, inputUnits)}, 1L, null)});
    }

    private record FakePattern(AEItemKey definition, GenericStack[] outputs,
            IPatternDetails.IInput[] slots) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(outputs);
        }
    }

    private record FakeInput(GenericStack[] candidates, long multiplier,
            AEKey remainder) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            for (GenericStack candidate : candidates) {
                if (candidate != null && candidate.what().equals(input)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainder;
        }
    }
}
