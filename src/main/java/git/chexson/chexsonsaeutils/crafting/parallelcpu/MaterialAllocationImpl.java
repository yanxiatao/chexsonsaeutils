package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 材料归属账的唯一可写实现；任务排水、无限源判定与日志渲染不在本类执行。 */
final class MaterialAllocationImpl implements MaterialAllocation {

    /** 身份配给账；相等但不同实例的样板拥有独立份额。 */
    private final Map<IPatternDetails, WideKeyCounter> assigned = new IdentityHashMap<>();
    /** 身份封套许可账；只用于从配给中扣除非实记需求。 */
    private final Map<IPatternDetails, WideKeyCounter> envelopes = new IdentityHashMap<>();
    /** 身份真实消费账；成功推送后复加，reservation 下次读立即可见。 */
    private final Map<IPatternDetails, WideKeyCounter> eaten = new IdentityHashMap<>();
    /** 配给或孤立封套身份首次登记序，只用于任务之外且没有相等任务顺位的尾项。 */
    private final List<IPatternDetails> registeredPatterns = new ArrayList<>();
    /** 实际建账来源，诊断读取不触发 adopt/mint；不负责生命周期保护。 */
    private Source source = Source.NONE;

    @Override
    public boolean adopt(Map<IPatternDetails, WideKeyCounter> planned,
            Map<IPatternDetails, WideKeyCounter> plannedEnvelopes) {
        if (planned.isEmpty()) {
            return false;
        }
        for (var entry : planned.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            WideKeyCounter amounts = entry.getValue();
            if (pattern == null || amounts == null || amounts.isEmpty()) {
                continue;
            }
            registerPattern(pattern);
            assigned.put(pattern, amounts.copy());
        }
        for (var entry : plannedEnvelopes.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                registerPattern(entry.getKey());
                envelopes.put(entry.getKey(), entry.getValue().copy());
            }
        }
        if (assigned.isEmpty()) {
            return false;
        }
        source = Source.PLAN;
        return true;
    }

    @Override
    public void registerSubmitPattern(IPatternDetails pattern) {
        assigned.computeIfAbsent(pattern, ignored -> {
            registerPattern(pattern);
            return new WideKeyCounter();
        });
        source = Source.SUBMIT;
    }

    @Override
    public void mintShare(IPatternDetails pattern, AEKey key, BigInteger amount) {
        WideKeyCounter amounts = assigned.computeIfAbsent(pattern, ignored -> {
            registerPattern(pattern);
            return new WideKeyCounter();
        });
        if (amount.signum() <= 0) {
            if (!amounts.containsKey(key)) {
                amounts.set(key, 0L);
            }
        } else {
            amounts.add(key, amount);
        }
        source = Source.SUBMIT;
    }

    @Override
    public void recordConsumption(IPatternDetails pattern, AEKey key, long amount) {
        if (pattern == null || key == null || amount <= 0L) {
            return;
        }
        registerPattern(pattern);
        eaten.computeIfAbsent(pattern, ignored -> new WideKeyCounter()).add(key, amount);
    }

    @Override
    public void recordConsumption(IPatternDetails pattern, AEKey key, BigInteger amount) {
        if (pattern == null || key == null || amount == null || amount.signum() <= 0) {
            return;
        }
        registerPattern(pattern);
        eaten.computeIfAbsent(pattern, ignored -> new WideKeyCounter()).add(key, amount);
    }

    @Override
    public void narrowArrival(Iterable<IPatternDetails> executionOrder, AEKey key,
            @Nullable PlanAmount arrived, @Nullable PlanAmount outsideArrivals,
            PlanAmount plannedEmit) {
        if (assigned.isEmpty()) {
            return;
        }
        PlanAmount pool = (arrived == null ? PlanAmount.ZERO : arrived.max(PlanAmount.ZERO))
                .add(outsideArrivals == null ? PlanAmount.ZERO : outsideArrivals.max(PlanAmount.ZERO))
                .add(plannedEmit);
        Object family = key.getPrimaryKey();
        for (IPatternDetails pattern : orderedPatterns(executionOrder)) {
            WideKeyCounter amounts = assigned.get(pattern);
            if (amounts == null || amounts.isEmpty()) {
                continue;
            }
            List<AEKey> variants = familyKeys(amounts, family);
            if (variants.isEmpty()) {
                continue;
            }
            PlanAmount demand = familyAmount(amounts, key);
            PlanAmount allowance = pool.min(demand);
            pool = pool.subtract(allowance);
            if (allowance.compareTo(demand) >= 0) {
                continue;
            }
            PlanAmount left = allowance;
            for (AEKey variant : variants) {
                PlanAmount share = amounts.getAmount(variant);
                PlanAmount given = left.min(share);
                if (given.compareTo(share) < 0) {
                    amounts.setExact(variant, given.exactValue());
                }
                left = left.subtract(given);
            }
        }
    }

    @Override
    public Source source() {
        return source;
    }

    @Override
    public boolean hasAllocation() {
        return !assigned.isEmpty();
    }

    @Override
    public int allocatedPatternCount() {
        return assigned.size();
    }

    @Override
    public boolean hasPatternQuota(@Nullable IPatternDetails pattern) {
        WideKeyCounter amounts = assigned.get(pattern);
        return amounts != null && !amounts.isEmpty();
    }

    @Override
    public boolean hasQuotaBasis(@Nullable IPatternDetails pattern, @Nullable AEKey key) {
        if (pattern == null || key == null) {
            return false;
        }
        WideKeyCounter amounts = assigned.get(pattern);
        if (amounts == null) {
            return false;
        }
        Object family = key.getPrimaryKey();
        for (AEKey candidate : amounts.keys()) {
            if (candidate != null && family.equals(candidate.getPrimaryKey())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PlanAmount assignedAmount(IPatternDetails pattern, AEKey key) {
        return exactAmount(assigned.get(pattern), key);
    }

    @Override
    public PlanAmount envelopeAmount(IPatternDetails pattern, AEKey key) {
        return exactAmount(envelopes.get(pattern), key);
    }

    @Override
    public PlanAmount eatenAmount(IPatternDetails pattern, AEKey key) {
        return exactAmount(eaten.get(pattern), key);
    }

    @Override
    public PlanAmount assignedFamilyAmount(IPatternDetails pattern, AEKey key) {
        return familyAmount(assigned.get(pattern), key);
    }

    @Override
    public PlanAmount eatenFamilyAmount(IPatternDetails pattern, AEKey key) {
        return familyAmount(eaten.get(pattern), key);
    }

    @Override
    public PlanAmount rawFamilyBalance(IPatternDetails pattern, AEKey key) {
        return assignedFamilyAmount(pattern, key).subtract(eatenFamilyAmount(pattern, key));
    }

    @Override
    public @Nullable PlanAmount remainingFamilyQuota(IPatternDetails pattern, AEKey key) {
        if (!hasPatternQuota(pattern)) {
            return null;
        }
        PlanAmount mine = assignedFamilyAmount(pattern, key);
        PlanAmount took = eatenFamilyAmount(pattern, key);
        return took.signum() <= 0 ? mine : mine.subtract(took).max(PlanAmount.ZERO);
    }

    @Override
    public List<Row> rows(Iterable<IPatternDetails> executionOrder) {
        List<Row> result = new ArrayList<>();
        for (IPatternDetails pattern : orderedPatterns(executionOrder)) {
            result.add(new Row(pattern, keyAmounts(assigned.get(pattern)),
                    keyAmounts(envelopes.get(pattern)), keyAmounts(eaten.get(pattern))));
        }
        return List.copyOf(result);
    }

    /** 新配给身份入册；存在性仅用 identity 查表，不把 equals 身份并账。 */
    private void registerPattern(IPatternDetails pattern) {
        if (!assigned.containsKey(pattern) && !envelopes.containsKey(pattern) && !eaten.containsKey(pattern)) {
            registeredPatterns.add(pattern);
        }
    }

    /** 任务身份按执行序，额外配给身份按 equals 任务顺位，未在任务表的尾项保持登记序。 */
    private List<IPatternDetails> orderedPatterns(Iterable<IPatternDetails> executionOrder) {
        List<IPatternDetails> ordered = new ArrayList<>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        Map<IPatternDetails, Integer> rankByPattern = new HashMap<>();
        int rank = 0;
        for (IPatternDetails pattern : executionOrder) {
            if (pattern != null) {
                rankByPattern.putIfAbsent(pattern, rank);
                if (seen.add(pattern)) {
                    ordered.add(pattern);
                }
            }
            rank++;
        }
        List<IPatternDetails> tail = new ArrayList<>();
        for (IPatternDetails pattern : registeredPatterns) {
            if (seen.add(pattern)) {
                tail.add(pattern);
            }
        }
        int unranked = rank;
        tail.sort(Comparator.comparingInt(pattern -> rankByPattern.getOrDefault(pattern, unranked)));
        ordered.addAll(tail);
        return ordered;
    }

    /** 精确金额缺席时读零，与 WideKeyCounter 的既有查表语义一致。 */
    private static PlanAmount exactAmount(@Nullable WideKeyCounter amounts, AEKey key) {
        return amounts == null ? PlanAmount.ZERO : amounts.getAmount(key);
    }

    /** 正金额按族精确复加；非正条目仍可由 hasQuotaBasis 判断在册，但不参与族金额。 */
    private static PlanAmount familyAmount(@Nullable WideKeyCounter amounts, @Nullable AEKey key) {
        if (amounts == null || key == null || amounts.isEmpty()) {
            return PlanAmount.ZERO;
        }
        Object family = key.getPrimaryKey();
        PlanAmount total = PlanAmount.ZERO;
        for (AEKey candidate : amounts.keys()) {
            if (candidate != null && family.equals(candidate.getPrimaryKey())) {
                PlanAmount value = amounts.getAmount(candidate);
                if (value.signum() > 0) {
                    total = total.add(value);
                }
            }
        }
        return total;
    }

    /** 按账本键登记序筛出一个族的全部在册键，零值条目保留。 */
    private static List<AEKey> familyKeys(WideKeyCounter amounts, Object family) {
        List<AEKey> variants = new ArrayList<>();
        for (AEKey variant : amounts.keys()) {
            if (variant != null && family.equals(variant.getPrimaryKey())) {
                variants.add(variant);
            }
        }
        return variants;
    }

    /** 冷路径金额快照；只复制不可变 key/amount 行，不交出底层可写 counter。 */
    private static List<KeyAmount> keyAmounts(@Nullable WideKeyCounter amounts) {
        if (amounts == null || amounts.isEmpty()) {
            return List.of();
        }
        List<KeyAmount> result = new ArrayList<>();
        for (AEKey key : amounts.keys()) {
            result.add(new KeyAmount(key, amounts.getAmount(key)));
        }
        return List.copyOf(result);
    }
}
