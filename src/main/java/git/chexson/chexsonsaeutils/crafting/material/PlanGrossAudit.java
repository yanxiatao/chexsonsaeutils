package git.chexson.chexsonsaeutils.crafting.material;

import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;

/**
 * 计划逐键毛账探针：供给 = 任务表自产 + {@code usedItems}；需求 = 每槽按执行侧同一条
 * 顺位排水（{@link SlotAccount#drainSlotWide}）从<b>共用</b>供给池里实际付掉的料，
 * 退到哪个键就记在哪个键上，盖不住的部分记回首位候选。
 *
 * <p>排水按 definition 定序（{@code patternTimes} 是身份哈希图，不定序则同一张表每次报的数不同）。
 * 逐槽各挑最能盖住的候选等于把同一份供给许诺两次，缺口就此被抹平。
 *
 * <p>口径与真实执行顺序无关，因此可以在规划链每一段、第三方重写点、CPU 提交边界
 * 各记一次同一份账并直接对量级。
 *
 * <h2>⑤ 数域：这一本账读哪一本，读数就按哪一本的原样印出来</h2>
 * 探针过去把 {@code need}／{@code supply} 各存一格 {@code long} 并向上饱和（{@code saturatedAdd}），
 * 于是<b>两张各顶满天花板的账先各自撞顶、再相减</b>——{@code need=MAX ∧ supply=MAX} 读起来就是
 * {@code 配平}，{@code gross=[ok keys=N]} 与"真缺料"在<b>读数上同形</b>；顶不住的那一侧还会把
 * {@code 9223372036854775807} 当量印出去。本仓口径是毛账<b>只作诊断分档、不参与放行判定</b>，
 * 而它被 {@code GraphPlanningSession} 打进 {@code INFO} 供每一轮排障读——<b>读数不许撒谎</b>。
 *
 * <p>所以这里只改"读哪一本账的数域"：份数走 {@link WideCraftCounter} 的精确值、
 * 来路走计划对象自带的 {@link WideLedgerSnapshot}（{@link WideAmountPlanCarrier} 现成读口，
 * 不新建账本、不加旁路镜像），逐键累计与排水一律落在 {@link BigInteger} 上——
 * 真值越界就印真值（BigInteger 原文），读不到宽账而 {@code long} 恰好顶格时印
 * {@value #SENTINEL_MARK}（本仓既有的顶格标记写法，见 {@code per100r} 的 {@code pwr=-1}），
 * 绝不再把天花板当量。那一行同时自报用的是哪把尺：{@code view=wide}／{@code view=narrow}。
 *
 * <p>判据本体一个字没动：不加拒绝分支、不加闸门、不加夹子；毛账仍只作分档。
 */
public final class PlanGrossAudit {

    /** 越出 {@code long} 的顶格读数：印成可辨认的标记（同 {@code per100r} 的 {@code pwr=-1} 写法）。 */
    private static final String SENTINEL_MARK = "-1";

    /**
     * {@code byKey} 每键存 {@code {总需求, 总供给}}（{@link BigInteger} 精确值，不再向上饱和）；
     * {@code consumers} 记需求出自哪张样板；{@code spare} 记排空失败那一刻该槽各候选键在池子里
     * 还剩多少——全零才是真供不应求。{@code firings} 是同一把尺的发数合计。
     *
     * <p>{@code wideRuler} 只说一件事：这一本账<b>读得到宽域真值吗</b>——
     * {@code true}＝份数／来路来自 {@link WideCraftCounter} 或计划对象自带的宽域账；
     * {@code false}＝只能读到 AE2 那份 {@code long}（{@code patternTimes} 与 {@code KeyCounter}），
     * 饱和值与真值在读数上同形，那一行会如实标 {@code view=narrow}。
     */
    public record Balances(Map<AEKey, BigInteger[]> byKey, Map<AEKey, Map<String, BigInteger>> consumers,
            Map<AEKey, Map<AEKey, BigInteger>> spare, BigInteger firings, boolean wideRuler) {
    }

    private PlanGrossAudit() {
    }

    public static Balances measure(ICraftingPlan plan) {
        return measureTasks(tasksOf(plan.patternTimes(), WideAmountPlanCarrier.resolveWideCrafts(plan)),
                plan.usedItems(), WideAmountPlanCarrier.resolveWideLedgers(plan), readsWideLedgers(plan));
    }

    /** 规划链中段还没有计划实例：按当时的任务表 + usedItems 量同一份账（这份字典是 AE2 的 long 域）。 */
    public static Balances measure(@Nullable Map<IPatternDetails, Long> patternTimes,
            @Nullable KeyCounter usedItems) {
        return measureTasks(tasksOf(patternTimes, WideCraftSnapshot.empty()), usedItems, null, false);
    }

    /**
     * 宽域份数版：规划链中段的任务表读精确发数（{@link WideCraftCounter}），
     * 判据口径与 {@code long} 版逐字相同——单键流量同样按精确值累计，
     * 不再在天花板上做减法（{@code view=wide}）。
     */
    public static Balances measure(@Nullable WideCraftCounter crafts, @Nullable KeyCounter usedItems) {
        var tasks = new ArrayList<Map.Entry<IPatternDetails, PlanAmount>>();
        if (crafts != null) {
            for (var source : crafts.keys()) {
                if (source == null) {
                    continue;
                }
                PlanAmount times = crafts.getAmount(source);
                if (times.signum() <= 0) {
                    continue;
                }
                tasks.add(Map.entry(source, times));
            }
        }
        return measureTasks(tasks, usedItems, null, true);
    }

    /**
     * 任务表的发数：{@code patternTimes()} 是 AE2 那份<b>饱和代表值</b>，
     * 宽域份数快照里有这一张就用真值，否则代表值就是真值（{@link WideCraftSnapshot} 类注释的读法）。
     */
    private static List<Map.Entry<IPatternDetails, PlanAmount>> tasksOf(
            @Nullable Map<IPatternDetails, Long> patternTimes, WideCraftSnapshot wideCrafts) {
        var tasks = new ArrayList<Map.Entry<IPatternDetails, PlanAmount>>();
        if (patternTimes == null) {
            return tasks;
        }
        for (var entry : patternTimes.entrySet()) {
            Long value = entry.getValue();
            long times = value == null ? 0L : Math.max(0L, value);
            if (entry.getKey() == null || times <= 0L) {
                continue;
            }
            BigInteger exact = wideCrafts.get(entry.getKey());
            tasks.add(Map.entry(entry.getKey(), exact != null ? PlanAmount.of(exact) : PlanAmount.of(times)));
        }
        return tasks;
    }

    /** 这一趟读得到宽域真值吗：计划对象自带账载体（含被递归契约壳套着的那一层）就算读得到。 */
    private static boolean readsWideLedgers(@Nullable ICraftingPlan plan) {
        if (plan instanceof WideAmountPlanCarrier) {
            return true;
        }
        if (plan instanceof RecursivePlanContract contract) {
            ICraftingPlan delegate = contract.chexsonsaeutils$getDelegate();
            return delegate != null && delegate != plan && delegate instanceof WideAmountPlanCarrier;
        }
        return false;
    }

    private static Balances measureTasks(List<Map.Entry<IPatternDetails, PlanAmount>> tasks,
            @Nullable KeyCounter usedItems, @Nullable WideLedgerSnapshot wideLedgers, boolean wideRuler) {
        var byKey = new HashMap<AEKey, BigInteger[]>();
        var consumers = new HashMap<AEKey, Map<String, BigInteger>>();
        var spare = new HashMap<AEKey, Map<AEKey, BigInteger>>();
        BigInteger firings = BigInteger.ZERO;
        for (var task : tasks) {
            firings = firings.add(task.getValue().exactValue());
            accumulateSupply(byKey, task.getKey(), task.getValue());
        }
        if (usedItems != null) {
            for (var entry : usedItems) {
                var key = entry.getKey();
                if (key == null) {
                    continue;
                }
                // 来路那一截读计划对象自带的宽域账（AE2 的 KeyCounter 会把 >long 顶成天花板），
                // 没有宽账就读 long——顶格与真值同形时由 view=narrow 如实交代。
                BigInteger wideTruth = wideLedgers == null
                        ? null : wideLedgers.get(WideAmountKind.USED, key);
                BigInteger amount = wideTruth != null ? wideTruth
                        : BigInteger.valueOf(Math.max(0L, entry.getLongValue()));
                if (amount.signum() > 0L) {
                    slot(byKey, key, 1).add(amount);
                }
            }
        }
        // 需求必须在全表供给就位后再排：先跑的任务能把后跑任务的料吃空，
        // 逐槽各挑各的候选等于把同一份供给许诺两次。
        var pool = supplyPool(byKey);
        tasks.sort(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey().getDefinition())));
        for (var task : tasks) {
            accumulateDemand(byKey, consumers, spare, pool, task.getKey(), task.getValue());
        }
        return new Balances(byKey, consumers, spare, firings, wideRuler);
    }

    private static Map<AEKey, BigInteger> supplyPool(Map<AEKey, BigInteger[]> byKey) {
        var pool = new HashMap<AEKey, BigInteger>();
        for (var entry : byKey.entrySet()) {
            if (entry.getValue()[1].signum() > 0L) {
                pool.put(entry.getKey(), entry.getValue()[1]);
            }
        }
        return pool;
    }

    /** 一步取账并打印：调用点只需要字符串证据时用这个。 */
    public static String gross(ICraftingPlan plan) {
        var balances = measure(plan);
        return describe(balances, plan.usedItems(), plan.emittedItems(), plan.missingItems(),
                WideAmountPlanCarrier.resolveWideLedgers(plan));
    }

    /** 缺口键（按缺口量降序）：调用方拿它去闸门侧做逐槽差分。 */
    public static List<AEKey> holeKeys(Balances balances) {
        var ranked = holes(balances);
        var keys = new ArrayList<AEKey>(ranked.size());
        for (var entry : ranked) {
            keys.add(entry.getKey());
        }
        return keys;
    }

    /** 逐键毛账缺口（顺位排水后仍付不出的量，精确值）：只作诊断分档，不参与放行判定。 */
    public static List<Map.Entry<AEKey, BigInteger>> holes(Balances balances) {
        return unbalanced(balances);
    }

    /** 供不应求的键（含前三名消费者）；全配平时只报 ok。读数用的是窄域那本账。 */
    public static String describe(Balances balances, @Nullable KeyCounter usedItems,
            @Nullable KeyCounter emittedItems, @Nullable KeyCounter missingItems) {
        return describe(balances, usedItems, emittedItems, missingItems, null);
    }

    /**
     * 供不应求的键（含前三名消费者）；全配平时只报 ok。
     *
     * <p>{@code wideLedgers} 是计划对象自带的宽域账（{@link WideAmountPlanCarrier#resolveWideLedgers}）：
     * 读得到的键就印 BigInteger 真值，读不到而 {@code long} 恰顶格时印 {@value #SENTINEL_MARK}——
     * 缺口键的读数不许再出现"恰好 {@code Long.MAX_VALUE} 被当成量"的形态。
     */
    public static String describe(Balances balances, @Nullable KeyCounter usedItems,
            @Nullable KeyCounter emittedItems, @Nullable KeyCounter missingItems,
            @Nullable WideLedgerSnapshot wideLedgers) {
        var ranked = unbalanced(balances);
        String view = balances.wideRuler() ? "wide" : "narrow";
        if (ranked.isEmpty()) {
            return "gross=[ok keys=" + balances.byKey().size() + " firings=" + balances.firings()
                    + " view=" + view + "]";
        }
        var described = new StringBuilder("gross=[firings=").append(balances.firings())
                .append(" view=").append(view)
                .append(" unbalanced=").append(ranked.size()).append('/').append(balances.byKey().size());
        for (var entry : ranked.subList(0, Math.min(8, ranked.size()))) {
            AEKey key = entry.getKey();
            BigInteger[] totals = balances.byKey().get(key);
            described.append(' ').append(key)
                    .append(" hole=").append(entry.getValue())
                    .append(" need=").append(totals[0])
                    .append(" supply=").append(totals[1])
                    .append(" emit=").append(amountCell(wideLedgers, WideAmountKind.EMIT, key, emittedItems))
                    .append(" used=").append(amountCell(wideLedgers, WideAmountKind.USED, key, usedItems))
                    .append(" miss=").append(amountCell(wideLedgers, WideAmountKind.MISSING, key, missingItems))
                    .append(" want=").append(describeConsumers(balances.consumers(), key))
                    .append(" spare=").append(describeSpare(balances.spare(), key));
        }
        return described.append(']').toString();
    }

    /** 重写前后的逐键差值（需求与供给各自变化）；完全一致返回空串。 */
    public static String diff(Balances before, Balances after) {
        var keys = new ArrayList<AEKey>();
        for (AEKey key : before.byKey().keySet()) {
            keys.add(key);
        }
        for (AEKey key : after.byKey().keySet()) {
            if (!before.byKey().containsKey(key)) {
                keys.add(key);
            }
        }
        var described = new StringBuilder();
        for (AEKey key : keys) {
            BigInteger[] left = before.byKey().getOrDefault(key, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
            BigInteger[] right = after.byKey().getOrDefault(key, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
            if (left[0].equals(right[0]) && left[1].equals(right[1])) {
                continue;
            }
            if (described.length() > 0) {
                described.append(", ");
            }
            described.append(key).append(" need=").append(left[0]).append('-').append(right[0])
                    .append(" supply=").append(left[1]).append('-').append(right[1]);
        }
        if (described.length() == 0 && before.firings().equals(after.firings())) {
            return "";
        }
        return "[firings=" + before.firings() + '-' + after.firings() + ' ' + described + ']';
    }

    private static List<Map.Entry<AEKey, BigInteger>> unbalanced(Balances balances) {
        var ranked = new ArrayList<Map.Entry<AEKey, BigInteger>>();
        for (var entry : balances.byKey().entrySet()) {
            if (entry.getValue()[0].compareTo(entry.getValue()[1]) > 0) {
                ranked.add(Map.entry(entry.getKey(), entry.getValue()[0].subtract(entry.getValue()[1])));
            }
        }
        ranked.sort(Map.Entry.<AEKey, BigInteger>comparingByValue().reversed());
        return ranked;
    }

    private static void accumulateSupply(Map<AEKey, BigInteger[]> byKey, IPatternDetails details,
            PlanAmount times) {
        var outputs = details.getOutputs();
        if (outputs == null) {
            return;
        }
        for (GenericStack output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0L) {
                continue;
            }
            // 每发产量 × 发数在宽域里相乘再累计：一次乘算撞顶就把后面所有真值永远锁在天花板上
            slot(byKey, output.what(), 1).add(PlanAmount.of(output.amount()).multiply(times).exactValue());
        }
    }

    private static void accumulateDemand(Map<AEKey, BigInteger[]> byKey, Map<AEKey, Map<String, BigInteger>> consumers,
            Map<AEKey, Map<AEKey, BigInteger>> spare, Map<AEKey, BigInteger> pool,
            IPatternDetails details, PlanAmount times) {
        var slots = details.getInputs();
        if (slots == null) {
            return;
        }
        // 顺位、跳过空槽与零量候选、自偿还槽只取一份起步量、榨干靠前候选才退下一个——
        // 四条判据与执行侧／提交配平同一份实现（SlotAccount.drain），只是算术域换到宽域：
        // 饱和乘出来的假数一旦进账，两张各顶格的账相减就永远是 0。
        BigInteger billableTimes = times.exactValue();
        for (IPatternDetails.IInput slot : slots) {
            if (SlotAccount.firstCandidate(slot) == null) {
                continue;
            }
            // 与执行侧、提交配平同一条顺位排水：退到哪个键就记在哪个键上，
            // 只按第一候选收费会让退料吃到的键从账上隐身（实机形态：certus 缺 459248 而 unbalanced 里没有它）。
            var drained = SlotAccount.drainSlotWide(pool, slot, billableTimes, null);
            for (var funded : drained.funded().entrySet()) {
                bill(byKey, consumers, details, slot, funded.getKey(), funded.getValue(), times);
            }
            GenericStack reported = drained.reported();
            if (drained.unfundedFirings().signum() > 0L && reported != null) {
                // 模具类槽整批只占一发起步量：缺口也只按起步量记，否则探针需求天然虚高。
                bill(byKey, consumers, details, slot, reported.what(),
                        SlotAccount.chargedUnitsWide(slot, reported, drained.unfundedFirings()), times);
                noteSpare(spare, reported.what(), slot, pool);
            }
        }
    }

    /** 排不出料的那一刻，这一槽的退路上还剩多少：全零才是真缺，非零是顺序被吃空。 */
    private static void noteSpare(Map<AEKey, Map<AEKey, BigInteger>> spare, AEKey reported,
            IPatternDetails.IInput slot, Map<AEKey, BigInteger> pool) {
        GenericStack[] candidates = slot.getPossibleInputs();
        if (candidates == null) {
            return;
        }
        var leftovers = spare.computeIfAbsent(reported, ignored -> new LinkedHashMap<>());
        for (GenericStack candidate : candidates) {
            if (candidate == null || candidate.what() == null) {
                continue;
            }
            BigInteger onHand = pool.get(candidate.what());
            leftovers.merge(candidate.what(), onHand == null ? BigInteger.ZERO : onHand, BigInteger::add);
        }
    }

    private static void bill(Map<AEKey, BigInteger[]> byKey, Map<AEKey, Map<String, BigInteger>> consumers,
            IPatternDetails details, IPatternDetails.IInput slot, AEKey key, BigInteger amount,
            PlanAmount times) {
        if (amount == null || amount.signum() <= 0L) {
            return;
        }
        slot(byKey, key, 0).add(amount);
        consumers.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                .merge(details.getDefinition() + "#" + details.getClass().getSimpleName()
                        + " unit=" + SlotAccount.unitsPerFiring(slot, SlotAccount.firstCandidate(slot))
                        + "x" + times, amount, BigInteger::add);
    }

    /**
     * 一格数量读数：宽账读得到真值就印 BigInteger 原文；读不到而 AE2 那份 {@code long} 恰是天花板
     * （{@code 9223372036854775807}）时印 {@value #SENTINEL_MARK}——顶格哨兵不是量，
     * 与"真值恰好等于天花板"在 {@code long} 出口分不开，就一律不作量印出去。
     */
    private static String amountCell(@Nullable WideLedgerSnapshot wideLedgers, WideAmountKind kind,
            AEKey key, @Nullable KeyCounter narrow) {
        BigInteger wideTruth = wideLedgers == null ? null : wideLedgers.get(kind, key);
        if (wideTruth != null) {
            return wideTruth.toString();
        }
        long reading = narrow == null ? 0L : Math.max(0L, narrow.get(key));
        return reading == Long.MAX_VALUE ? SENTINEL_MARK : Long.toString(reading);
    }

    /** 退路上还剩多少：只列非零余量，全空即 {@code all-empty}（真缺料，不是顺序噪声）。 */
    private static String describeSpare(Map<AEKey, Map<AEKey, BigInteger>> spare, AEKey key) {
        var leftovers = spare.get(key);
        if (leftovers == null || leftovers.isEmpty()) {
            return "[]";
        }
        var ranked = new ArrayList<Map.Entry<AEKey, BigInteger>>();
        for (var entry : leftovers.entrySet()) {
            if (entry.getValue().signum() > 0L) {
                ranked.add(entry);
            }
        }
        if (ranked.isEmpty()) {
            return "[all-empty]";
        }
        ranked.sort(Map.Entry.<AEKey, BigInteger>comparingByValue().reversed());
        var described = new StringBuilder("[");
        for (var entry : ranked.subList(0, Math.min(3, ranked.size()))) {
            if (described.length() > 1) {
                described.append(", ");
            }
            described.append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (ranked.size() > 3) {
            described.append(" ...(+").append(ranked.size() - 3).append(')');
        }
        return described.append(']').toString();
    }

    private static String describeConsumers(Map<AEKey, Map<String, BigInteger>> consumers, AEKey key) {
        var shares = consumers.get(key);
        if (shares == null || shares.isEmpty()) {
            return "[]";
        }
        var ranked = new ArrayList<>(shares.entrySet());
        ranked.sort(Map.Entry.<String, BigInteger>comparingByValue().reversed());
        var described = new StringBuilder("[");
        for (var entry : ranked.subList(0, Math.min(3, ranked.size()))) {
            if (described.length() > 1) {
                described.append(", ");
            }
            described.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return described.append(']').toString();
    }

    private static BigIntegerHolder slot(Map<AEKey, BigInteger[]> byKey, AEKey key, int index) {
        return new BigIntegerHolder(byKey.computeIfAbsent(key,
                ignored -> new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO}), index);
    }

    /** 指向 {@code byKey} 里某个键的 {需求|供给} 槽位，避免调用点重复下标。 */
    private record BigIntegerHolder(BigInteger[] totals, int index) {
        void add(BigInteger amount) {
            totals[index] = totals[index].add(amount);
        }
    }
}
