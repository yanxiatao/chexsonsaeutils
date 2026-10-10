package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 份数声明形态的<b>义务账</b>：宿主只收下一发的原型材料，CPU 替它欠下剩下 (k-1) 份。
 *
 * <p>欠的是<b>发数</b>不是材料数——每发吃哪几个键几枚由交割那一刻的现场量按
 * {@link ParallelBatchExtraction#payDeclaredObligation} 的同一套顺位算出来，
 * 于是分批交割的合计与"k 倍 holder 一次装进去"的提料合计逐键相等（可表示区间内）。
 *
 * <p>销账一律走既有的槽级口径 {@code ParallelCraftingCpuLogic.recordTakenInputs}：
 * 这张表只记"还欠几发"，不建第二套提料账、也不建第二套配额账。
 * "欠着的料把哪几枚池子占住了"（{@link #pendingPoolClaims}）是<b>每次现算的派生读数</b>，
 * 不是这张表里的第二列：它不落 NBT、不需要跟着交割增减，也就没有对不上的可能。
 *
 * <p>批次发数载体仍为 long，材料乘积则保持 BigInteger 真值。
 * 宽域库存直接扣除材料真值，holder 只携带投影；实际扣账读取 exactPaid。
 * 欠账随作业落 NBT（任务条目级 {@value #NBT_ENTRY_OBLIGATION_WIDE}），
 * 续传后照分批继续交割，不会因为换了 jar 就把已声明出去的份数的材料忘掉。
 */
final class BatchObligationLedger {

    /**
     * 任务条目上的<b>宽域欠账</b>键：只在还挂着欠账时挂上（BigInteger 补码字节，与
     * {@code #craftingProgressWide} 同一写法）。缺该键即"这一条任务没有待交割的义务"，
     * 是自然缺省，不是迁移壳。
     */
    static final String NBT_ENTRY_OBLIGATION_WIDE = "#batchObligationWide";

    /** 样板 → 还欠宿主提走材料的发数。 */
    private final Map<IPatternDetails, BigInteger> pending = new LinkedHashMap<>();

    /** 该样板还欠几发：无欠账返回 {@code ZERO}。 */
    BigInteger pendingFirings(@Nullable IPatternDetails details) {
        BigInteger owed = details == null ? null : pending.get(details);
        return owed == null ? BigInteger.ZERO : owed;
    }

    /**
     * 该样板还欠的发数的<b>宽域读数</b>（{@link PlanAmount}）：环种账筛图／资格那一侧与取料点留底
     * 共用的现成读口，读的就是上面 {@link #pendingFirings} 那一格，不新建账、不落 NBT、不写份数。
     */
    PlanAmount pendingFiringsWide(@Nullable IPatternDetails details) {
        return PlanAmount.of(pendingFirings(details));
    }

    boolean hasPending(IPatternDetails details) {
        return pendingFirings(details).signum() > 0;
    }

    boolean hasAnyPending() {
        return !pending.isEmpty();
    }

    /** 挂账：宿主实收 accepted 发，原型材料已交出发，于是欠 {@code accepted - 1} 发。 */
    void incur(IPatternDetails details, BigInteger firings) {
        if (firings.signum() <= 0L) {
            return;
        }
        pending.merge(details, firings, BigInteger::add);
    }

    /**
     * 还挂在欠账上的池料合计（逐键宽域真值）：下一张样板算可申上界时要从池子里遮住它。
     *
     * <p>这是一份<b>派生读数</b>，不是第二本账：每调一次现算，落不到 NBT，也不记"申报那一刻
     * 吃过什么"。算法是<b>顺位排水</b>——沿欠账样板每一槽的候选顺位，把 {@code owed} 发要吃掉
     * 的枚数从<b>同一份</b>宽域池子余量里排出去，排到哪个键就往那个键上记 claim。
     *
     * <p>为什么不能记一发向量再乘发数（上一刀的写法，被复核打出三条 P1）：
     * <ul>
     * <li>一发向量只有 {@code primaryCandidate} 写进 holder 的那<b>一个</b>键，首位候选被吃干后
     * 欠账退到第二个候选，那一截完全遮不住（漏遮 ⇒ 症状逐字回来）；</li>
     * <li>holder 里躺着的是饱和值与凭空量（{@code Long.MAX_VALUE} 哨兵、就地认量的枚数），
     * 乘进 claim 就是把哨兵当量用、又把凭空量算成别人的口粮；</li>
     * <li>一发向量不落盘：存档重载后欠账读得回来（{@value #NBT_ENTRY_OBLIGATION_WIDE}）而向量
     * 缺失 ⇒ claim 归零 ⇒ 这类跑几小时的作业每次重启都退回改前。</li>
     * </ul>
     * 排水三者都不沾：只读样板声明的顺位与池子的宽域真值，续传后重算即可。
     *
     * <p>顺序取 {@code orderedTasks}（{@link RecursiveTaskOrdering} 的确定全序，与本轮推送同序）：
     * 两张样板挤同一族时"谁先吃"有确定答案，既不互相顶掉也不重复顶。挂着欠账的样板一定还在
     * 任务表上（{@code ParallelCraftingCpuLogic} 收工那道判据要欠账清了才出表），所以这一趟
     * 覆盖全部欠账；不在表上的那一截排不出来就是不遮，退化成改前而不会遮错。
     *
     * <p>池子里排不出去的那一截<b>不进 claim</b>：那一截本来就不在池子里，交割时走网络回补，
     * 没有什么要遮。模具槽（{@link SlotAccount#selfRepaying}）整批只占一发起份量、原样退回，
     * 配给表里本来就没有它，排水也只从池子里点一份。
     *
     * @param poolExact       池子在该键上的<b>宽域</b>真值读数（不是主表那份饱和 {@code long}）
     * @param noPoolSupply    明确认定的无限源：该槽无需消费有限池料，因此不进 claim。
     *                        未来计划产量能否装进 long 与现有池料的归属无关。
     */
    WideKeyCounter pendingPoolClaims(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            java.util.function.Function<AEKey, BigInteger> poolExact,
            @Nullable java.util.function.Predicate<AEKey> noPoolSupply
    ) {
        var claims = new WideKeyCounter();
        if (!hasAnyPending() || orderedTasks == null || poolExact == null) {
            return claims;
        }
        // 全部欠账样板共用这一份余量：先按 orderedTasks 排掉的，后面的样板就看不见那一截了
        var remaining = new HashMap<AEKey, BigInteger>();
        for (var task : orderedTasks) {
            IPatternDetails details = task == null ? null : task.getKey();
            BigInteger owed = pendingFirings(details);
            if (details == null || owed.signum() <= 0L || details.getInputs() == null) {
                continue;
            }
            for (IPatternDetails.IInput slot : details.getInputs()) {
                seedRemaining(remaining, slot, poolExact);
                var drained = SlotAccount.drainSlotWide(remaining, slot, owed, noPoolSupply);
                for (var entry : drained.funded().entrySet()) {
                    claims.add(entry.getKey(), entry.getValue());
                }
            }
        }
        return claims;
    }

    /** 只点阅这一格候选表上的键：不为一次排水把整池换算成宽域表。 */
    private static void seedRemaining(Map<AEKey, BigInteger> remaining,
            @Nullable IPatternDetails.IInput slot,
            java.util.function.Function<AEKey, BigInteger> poolExact) {
        GenericStack[] candidates = slot == null ? null : slot.getPossibleInputs();
        if (candidates == null) {
            return;
        }
        for (GenericStack candidate : candidates) {
            AEKey key = candidate == null ? null : candidate.what();
            if (key != null) {
                remaining.computeIfAbsent(key, poolExact);
            }
        }
    }

    /** 交割一批：返回该样板交割后还欠的发数（用于读数）。 */
    BigInteger settle(IPatternDetails details, long paidFirings) {
        return settle(details, BigInteger.valueOf(paidFirings));
    }

    /** 宽域交割用于明确的无限源；有限材料仍须经实际提取才能销账。 */
    BigInteger settle(IPatternDetails details, BigInteger paidFirings) {
        if (details == null || paidFirings.signum() <= 0) {
            return pendingFirings(details);
        }
        BigInteger owed = pendingFirings(details).subtract(paidFirings);
        if (owed.signum() <= 0L) {
            pending.remove(details);
            return BigInteger.ZERO;
        }
        pending.put(details, owed);
        return owed;
    }

    /**
     * 记录一次实际交割尝试并轮转到队尾。
     * 成功、缺料均轮转；因本轮预算耗尽而未尝试的条目保持优先顺序，欠账量不变。
     */
    void noteSettlementAttempt(IPatternDetails details) {
        BigInteger owed = pending.remove(details);
        if (owed != null) {
            pending.put(details, owed);
        }
    }

    /** 当前挂着欠账的样板快照：交割循环会边遍历边销账，所以必须先复制。 */
    List<IPatternDetails> pendingPatterns() {
        return new ArrayList<>(pending.keySet());
    }

    /** 全部欠账合计（饱和读数）：只给日志与探针用，不参与任何判定。 */
    BigInteger totalPending() {
        BigInteger total = BigInteger.ZERO;
        for (BigInteger owed : pending.values()) {
            total = total.add(owed);
        }
        return total;
    }

    /** 把一条任务还欠的发数写进它自己的条目 tag：常态零欠账 ⇒ 一个键都不挂。 */
    static void writeEntry(CompoundTag item, BigInteger firings) {
        if (item == null || firings == null || firings.signum() <= 0L) {
            return;
        }
        item.putByteArray(NBT_ENTRY_OBLIGATION_WIDE, firings.toByteArray());
    }

    /** 从条目 tag 读回欠账：缺键即零欠账。 */
    static BigInteger readEntry(CompoundTag item) {
        if (item == null || !item.contains(NBT_ENTRY_OBLIGATION_WIDE, Tag.TAG_BYTE_ARRAY)) {
            return BigInteger.ZERO;
        }
        byte[] encoded = item.getByteArray(NBT_ENTRY_OBLIGATION_WIDE);
        return encoded.length == 0 ? BigInteger.ZERO : new BigInteger(encoded);
    }
}
