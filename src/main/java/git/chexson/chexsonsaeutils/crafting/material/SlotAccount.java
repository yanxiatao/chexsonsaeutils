package git.chexson.chexsonsaeutils.crafting.material;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;

/**
 * 一个槽在执行侧到底要哪个键、要多少——全仓库唯一的口径。
 *
 * <p>毛账探针（{@link PlanGrossAudit}）、提交配平、守恒闸门与执行侧占料账本
 * （{@code ParallelPlanReservation}）原先各自实现过同一组判据，同一张任务表因此
 * 报出过四个不同的缺口数字，缺口到底真不真没人能判。这里只留一份实现：
 * <ul>
 * <li>{@link #firstCandidate}：执行侧顺位取料的第一候选（跳过空槽与零量候选）；</li>
 * <li>{@link #unitsPerFiring}：一发吃掉的单位数＝槽倍率 × 该候选模板量；</li>
 * <li>{@link #chargedFirings}：模具类槽（原样退回）整批只占一发起步量；</li>
 * <li>{@link #hasAlternative}：这一槽除某个键外还有没有别的可吃——决定它该不该被夹住；</li>
 * <li>{@link #isValidCandidate}：一枚候选算不算数（有键、每发件数为正）——顺位遍历、计费与
 * 规划侧那一枚（{@code GraphInput.Candidate}）读的都是这一句；</li>
 * <li>{@link #hasFamilyEntry}：这本配给账里有没有<b>这一族</b>的条目——闸门补封套前的那一问与
 * 执行侧"有没有配额口径"的那一问共用。</li>
 * </ul>
 *
 * <p>顺位排水只有一份实现（{@link #drain}），对外给两个算术域：{@link #drainSlot} 是
 * {@code long} 形状，{@link #drainSlotWide} 是不夹天花板的宽域形状。同一把尺是硬要求：
 * 在别处再写一套顺位，就会有两张表对同一格报出不同的键。
 *
 * <p>“可退料”的判据不写死数据源：调用方把自己看得见的供给（计划产出、网络库存、
 * 图上的同族变体）以函数形式传入，本类只做顺位与算量。
 */
public final class SlotAccount {

    private SlotAccount() {
    }

    /** 执行侧按候选顺位真正会取的第一份料；没有可用候选时返回 null。 */
    public static @Nullable GenericStack firstCandidate(@Nullable IPatternDetails.IInput slot) {
        GenericStack[] candidates = slot == null ? null : slot.getPossibleInputs();
        if (candidates == null) {
            return null;
        }
        for (GenericStack candidate : candidates) {
            if (isValidCandidate(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 这一枚候选算不算数（有键、且每发件数为正）：顺位遍历与计费共用同一把尺。 */
    public static boolean isValidCandidate(@Nullable GenericStack candidate) {
        return candidate != null && isValidCandidate(candidate.what(), candidate.amount());
    }

    /**
     * 同上，只看键与每发件数：规划侧那一枚（{@code GraphInput.Candidate}）与执行侧那一枚
     * （{@code GenericStack}）读的是同一句判据，不在两处各写一遍。
     */
    public static boolean isValidCandidate(@Nullable AEKey key, long amountPerFiring) {
        return key != null && amountPerFiring > 0L;
    }

    /**
     * 执行侧顺位真伸手的那一枚：落点键＋<b>伸到它的那条声明自己的</b>每发件数。
     *
     * <p>件数是这一格配给与排水的分母——它必须是执行侧 {@code usableTemplates} 递出的
     * {@code InputTemplate(fuzz, stack.amount())} 那一份（{@code stack} 是声明项、不是模糊成员），
     * 而不是候选宇宙里最省的那一份：首位那份先伸手、不去重，配给表按它配才与排产一致
     * （台账 #194/#203/#219 的裁判口径＝执行侧伸手口径）。
     */
    public record Reach(AEKey key, long amountPerFiring) {
    }

    /**
     * 全仓库唯一的一份"取料点真伸手那一枚"选枚尺：外层声明顺位、内层同族展开，第一个被
     * {@code accepts} 收下的落点即胜出，件数恒取<b>伸到它的那条声明</b>自己的 {@code amount}——
     * 同一条声明被后面更省的份数覆写不是本原语的职责（那是 {@code 7d7fc89} 现量到的闸门错案）。
     *
     * <p>{@code GraphCompiler#resolveReachCandidate}（换 node key 与每发件数）取第一枚，
     * {@code MaterialClosureGate} 的 {@code rankedCandidates}/{@code payableReachCandidate} 整份当
     * 候选宇宙——两侧共调这一处，任一侧都不许再写一遍这圈循环。
     *
     * <p>{@code classMembers} 交回 {@code null}＝单成员族（声明的这一枚自己就是唯一落点）；
     * 同族里没有 {@code accepts} 收下的键时兜一次声明自己（与执行侧"先展模糊模板、后补声明键"
     * 同序；{@code payableUniverseCandidate} 那条"精确键在前"的既有档因此也走得进来）。
     *
     * <p>落点顺序＝声明顺位 × 各族成员顺序：它是 {@code LinkedHashMap} 保序的，与 {@link Drain}
     * 的 {@code funded} 遍历序同一把尺（下游配给账的键序读它，不许哈希序）。
     */
    public static List<Reach> reachUniverse(@Nullable GenericStack[] possible,
            Function<AEKey, @Nullable ? extends Iterable<AEKey>> classMembers,
            Predicate<AEKey> accepts) {
        if (possible == null) {
            return List.of();
        }
        var seen = new LinkedHashMap<AEKey, Reach>();
        for (GenericStack candidate : possible) {
            if (!isValidCandidate(candidate)) {
                continue;
            }
            AEKey declared = candidate.what();
            var members = classMembers.apply(declared);
            boolean reached = false;
            if (members != null) {
                for (AEKey member : members) {
                    if (member != null && accepts.test(member)) {
                        seen.putIfAbsent(member, new Reach(member, candidate.amount()));
                        reached = true;
                    }
                }
            }
            if (!reached && accepts.test(declared)) {
                seen.putIfAbsent(declared, new Reach(declared, candidate.amount()));
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** 一发吃掉的单位数；槽倍率或候选量非法时返回 0（调用方按不计费处理）。 */
    public static long unitsPerFiring(@Nullable IPatternDetails.IInput slot,
            @Nullable GenericStack candidate) {
        if (slot == null || !isValidCandidate(candidate)) {
            return 0L;
        }
        return saturatingMultiply(slot.getMultiplier(), candidate.amount());
    }

    /** 该候选投料后原样退回（模具/样板不消耗）：整批只需要一份起步量。 */
    public static boolean selfRepaying(@Nullable IPatternDetails.IInput slot,
            @Nullable GenericStack candidate) {
        return candidate != null && selfRepaying(slot, candidate.what());
    }

    /** 同上，只知道键的版本：排水记配给、执行侧销账都按这个判据把模具槽排除在外。 */
    public static boolean selfRepaying(@Nullable IPatternDetails.IInput slot, @Nullable AEKey key) {
        if (slot == null || key == null) {
            return false;
        }
        return key.equals(slot.getRemainingKey(key));
    }

    /** 计费发数：自偿还槽整批算一发，其余按实际发数。 */
    public static long chargedFirings(IPatternDetails.IInput slot, GenericStack candidate,
            long firings) {
        return selfRepaying(slot, candidate) ? 1L : firings;
    }

    /** 这一槽为这些发数该收的料：每发单位数 × 计费发数。 */
    public static long chargedUnits(@Nullable IPatternDetails.IInput slot,
            @Nullable GenericStack candidate, long firings) {
        return saturatingMultiply(unitsPerFiring(slot, candidate),
                slot == null || candidate == null
                        ? 0L : chargedFirings(slot, candidate, firings));
    }

    /**
     * 同一条 {@link #chargedUnits} 的<b>宽域</b>形状：发数是 {@link BigInteger} 真值时，
     * 每发件数 × 计费发数不在 {@code long} 天花板上做——饱和乘出来的假数一旦进配给账，
     * 下游拿它做减法就是把哨兵当量。自偿还槽仍整批只收一份起步量，口径与窄域一字不差。
     */
    public static BigInteger chargedUnitsWide(@Nullable IPatternDetails.IInput slot,
            @Nullable GenericStack candidate, @Nullable BigInteger firings) {
        if (slot == null || candidate == null || firings == null) {
            return BigInteger.ZERO;
        }
        return unitsPerFiringExact(slot, candidate)
                .multiply(selfRepaying(slot, candidate) ? BigInteger.ONE : firings);
    }

    /**
     * 顺位排水的结果：没盖住的发数，第一个真正看得见的候选（报缺口用它，而非最后退到的那个），
     * 以及每个候选键实际付掉的料——退料退到谁头上，账就该记在谁头上。
     *
     * <p>{@code funded} 的<b>遍历序</b>＝这一槽的<b>候选顺位</b>（实现见 {@link #drain} 那条）。
     * 它不许是 {@link HashMap}：下游 {@code ParallelCraftingCpuLogic#mintSubmitAllocation} 按这份序
     * 登记配给账的键位，摊派那一路（{@code ParallelExecutingCraftingJob#narrowVariants}）再按登记序
     * 逐变体扣份额 ⇒ 哈希序会把"同族哪个变体分到几枚"这一档数字随会话改掉。
     */
    public record Drain(long unfundedFirings, @Nullable GenericStack reported,
            Map<AEKey, Long> funded) {
    }

    /** 同上，份数与枚数都不夹在 {@code long} 上：见 {@link #drainSlotWide}。 */
    public record WideDrain(BigInteger unfundedFirings, @Nullable GenericStack reported,
            Map<AEKey, BigInteger> funded) {
    }

    /**
     * 按执行侧同一条顺位规则把这一槽从<b>共享</b>余量池里排掉：先榨干靠前的候选，
     * 撑不住才退下一个。
     *
     * <p>池子必须共用一份：每槽各自挑"哪个候选最能盖住"会把同一份富余量重复许诺，
     * 缺口就此被抹平成 ok。自偿还槽只从池子里取那份起步量，剩下的又回到池子里。
     *
     * <p>本体在 {@link #drain}：这里只是 {@code long} 形状的读数入口。
     */
    public static Drain drainSlot(Map<AEKey, Long> pool, @Nullable IPatternDetails.IInput slot,
            long firings) {
        var drained = drain(longPool(pool), slot, BigInteger.valueOf(firings), null);
        Map<AEKey, Long> funded = null;
        for (var entry : drained.funded().entrySet()) {
            // 逐笔照搬宽域那份的候选顺位（LinkedHashMap 保序），不在这里换成哈希序
            funded = funded == null ? new LinkedHashMap<>() : funded;
            funded.merge(entry.getKey(), narrow(entry.getValue()), Math::addExact);
        }
        return new Drain(narrow(drained.unfundedFirings()), drained.reported(),
                funded == null ? Map.of() : funded);
    }

    /**
     * {@link #drainSlot} 的<b>宽域</b>入口：发数与枚数都不夹在 {@code long} 上。
     *
     * <p>申报批欠着的料动辄 {@code 1e10} 发 × 一发 {@code >long} 枚，两份天花板读数相乘就是
     * 把哨兵当量用。排水本身没有逐发循环——每发件数 × 发数一次 BigInteger 相乘，
     * 再和该键的余量做一次除法。
     *
     * <p><b>与窄域入口同一份实现</b>（{@link #drain}）：顺位、跳过空槽与零量候选、自偿还槽只取
     * 一份起步量、"榨干靠前候选才退下一个"四条判据一律同尺。两把尺在同一个仓库里一定分叉，
     * 所以这里只留一把，只换算术域。
     *
     * @param pool            跨槽跨样板<b>共用一份</b>的宽域余量表（就地扣减）
     * @param firings         要排掉的发数（宽域真值，不是饱和镜像）
     * @param unboundedSource 该键是否已被账上认成<b>无限源</b>：命中即"这一格不吃池子里的料"，
     *                        排水到此为止、不进 claim；{@code null} 即调用方没有这个口径
     */
    public static WideDrain drainSlotWide(Map<AEKey, BigInteger> pool,
            @Nullable IPatternDetails.IInput slot, @Nullable BigInteger firings,
            @Nullable java.util.function.Predicate<AEKey> unboundedSource) {
        return drain(bigPool(pool), slot, firings, unboundedSource);
    }

    /** 排水唯一的一份实现：只按候选键点阅余量表，从不整表换算。 */
    private static WideDrain drain(Remaining pool, @Nullable IPatternDetails.IInput slot,
            @Nullable BigInteger firings, @Nullable java.util.function.Predicate<AEKey> unbounded) {
        BigInteger unfunded = firings == null ? BigInteger.ZERO : firings;
        GenericStack[] candidates = slot == null ? null : slot.getPossibleInputs();
        if (candidates == null || candidates.length == 0 || unfunded.signum() <= 0L
                || slot.getMultiplier() <= 0L) {
            return new WideDrain(unfunded.max(BigInteger.ZERO), null, Map.of());
        }
        GenericStack reported = null;
        Map<AEKey, BigInteger> funded = null;
        for (GenericStack candidate : candidates) {
            if (unfunded.signum() <= 0L) {
                break;
            }
            if (!isValidCandidate(candidate)) {
                continue;
            }
            if (reported == null) {
                reported = candidate;
            }
            AEKey key = candidate.what();
            if (unbounded != null && unbounded.test(key)) {
                // 这一格由无限源供给：池子里那一截一枚都不动，也不该被算成谁的口粮
                return new WideDrain(BigInteger.ZERO, reported, funded == null ? Map.of() : funded);
            }
            BigInteger unit = unitsPerFiringExact(slot, candidate);
            if (unit.signum() <= 0L) {
                continue;
            }
            boolean selfRepays = selfRepaying(slot, candidate);
            BigInteger onHand = pool.get(key);
            BigInteger fundable = (selfRepays ? BigInteger.ONE : unfunded).min(unfunded)
                    .min(onHand.divide(unit));
            if (fundable.signum() <= 0L) {
                continue;
            }
            BigInteger taken = unit.multiply(fundable);
            pool.put(key, onHand.subtract(taken));
            // 按这一槽的候选顺位登记（不是哈希序）：这份序就是下游配给账的键位序，
            // 摊派逐变体扣份额读的是它——见 Drain/WideDrain 那条。
            funded = funded == null ? new LinkedHashMap<>() : funded;
            funded.merge(key, taken, BigInteger::add);
            unfunded = unfunded.subtract(fundable);
            if (selfRepays) {
                return new WideDrain(BigInteger.ZERO, reported, funded);
            }
        }
        return new WideDrain(unfunded, reported, funded == null ? Map.of() : funded);
    }

    /** 排水只按候选键点阅余量表：两种余量（窄域 {@code long} 与宽域 {@code BigInteger}）各自适配。 */
    private interface Remaining {
        BigInteger get(AEKey key);

        void put(AEKey key, BigInteger value);
    }

    private static Remaining longPool(Map<AEKey, Long> pool) {
        return new Remaining() {
            @Override
            public BigInteger get(AEKey key) {
                Long value = pool.get(key);
                return value == null || value <= 0L ? BigInteger.ZERO : BigInteger.valueOf(value);
            }

            @Override
            public void put(AEKey key, BigInteger value) {
                pool.put(key, narrow(value));
            }
        };
    }

    private static Remaining bigPool(Map<AEKey, BigInteger> pool) {
        return new Remaining() {
            @Override
            public BigInteger get(AEKey key) {
                BigInteger value = pool.get(key);
                return value == null || value.signum() <= 0L ? BigInteger.ZERO : value;
            }

            @Override
            public void put(AEKey key, BigInteger value) {
                pool.put(key, value);
            }
        };
    }

    /** 排掉的料与没盖住的发数都恒在 {@code long} 之内（不超过喂进来的余量与份数），窄化不回绕。 */
    private static long narrow(BigInteger exact) {
        if (exact == null || exact.signum() <= 0L) {
            return 0L;
        }
        return exact.min(WideCeilings.LONG_ACCOUNT).longValue();
    }

    /** 一发吃掉的<b>精确</b>单位数：槽倍率 × 候选模板量，不在这里夹 {@code long}。 */
    public static BigInteger unitsPerFiringExact(@Nullable IPatternDetails.IInput slot,
            @Nullable GenericStack candidate) {
        if (slot == null || !isValidCandidate(candidate)) {
            return BigInteger.ZERO;
        }
        return unitsPerFiringExact(slot.getMultiplier(), candidate.amount());
    }

    /**
     * 同一把尺的原语形状：槽倍率 × 该枚候选的每发件数。任一非正即交回 {@code ZERO}
     * （调用方按"不计费/不 bill"处理），两步都不留饱和中间值。
     */
    public static BigInteger unitsPerFiringExact(long multiplier, long amountPerFiring) {
        if (multiplier <= 0L || amountPerFiring <= 0L) {
            return BigInteger.ZERO;
        }
        return BigInteger.valueOf(multiplier).multiply(BigInteger.valueOf(amountPerFiring));
    }

    /**
     * 这一槽除 {@code excluded} 之外，还有没有别的候选在 {@code deliverable} 眼里有量。
     *
     * <p>判"这一格能不能不吃这个键"用的是<b>候选表</b>：退路还没落到池子（计划排在后面才产）
     * 时，顺位靠前的键照样不能吃别人唯一能用的那份。
     */
    public static boolean hasAlternative(@Nullable IPatternDetails.IInput slot, AEKey excluded,
            java.util.function.ToLongFunction<AEKey> deliverable) {
        GenericStack[] candidates = slot == null ? null : slot.getPossibleInputs();
        if (candidates == null || excluded == null) {
            return false;
        }
        for (GenericStack candidate : candidates) {
            if (!isValidCandidate(candidate)) {
                continue;
            }
            if (excluded.equals(candidate.what())) {
                continue;
            }
            if (deliverable.applyAsLong(candidate.what()) > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这本配给账里是否已有<b>这一族</b>（物品本体族）的任一条目——含 0 值：只答"在不在册"，
     * 不答"还有多少"。规划侧补封套前的那一问与执行侧"有没有配额口径"的那一问是同一句。
     */
    public static boolean hasFamilyEntry(WideKeyCounter amounts, AEKey key) {
        var family = key.getPrimaryKey();
        for (AEKey candidate : amounts.keys()) {
            if (candidate != null && family.equals(candidate.getPrimaryKey())) {
                return true;
            }
        }
        return false;
    }

    public static long saturatingMultiply(long amount, long times) {
        if (amount <= 0L || times <= 0L) {
            return 0L;
        }
        return amount > Long.MAX_VALUE / times ? Long.MAX_VALUE : amount * times;
    }
}
