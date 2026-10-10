package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RingSeedLedger;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** 测试建账入口及断言快照；副本只用于断言，reservation 始终持实际材料账。 */
final class MaterialAllocationFixtures {
    private MaterialAllocationFixtures() { }

    static void assign(ParallelExecutingCraftingJob job, IPatternDetails pattern, WideKeyCounter amount) {
        job.materialAllocation.adopt(Map.of(pattern, amount), Map.of());
    }

    static Map<IPatternDetails, WideKeyCounter> assignedSnapshot(ParallelExecutingCraftingJob job) {
        return snapshot(job, 0);
    }

    static Map<IPatternDetails, WideKeyCounter> envelopeSnapshot(ParallelExecutingCraftingJob job) {
        return snapshot(job, 1);
    }

    static Map<IPatternDetails, WideKeyCounter> eatenSnapshot(ParallelExecutingCraftingJob job) {
        return snapshot(job, 2);
    }

    private static Map<IPatternDetails, WideKeyCounter> snapshot(ParallelExecutingCraftingJob job, int book) {
        var result = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        for (var row : job.materialRows()) {
            var amounts = switch (book) {
                case 0 -> row.assigned();
                case 1 -> row.envelopes();
                case 2 -> row.eaten();
                default -> throw new IllegalArgumentException("未知测试账本：" + book);
            };
            if (!amounts.isEmpty()) {
                var counter = new WideKeyCounter();
                for (var amount : amounts) {
                    counter.setExact(amount.key(), amount.amount().exactValue());
                }
                result.put(row.pattern(), counter);
            }
        }
        return result;
    }

    static MaterialAllocation allocation(Map<IPatternDetails, WideKeyCounter> assigned,
            Map<IPatternDetails, WideKeyCounter> eaten) {
        MaterialAllocation allocation = new MaterialAllocationImpl();
        allocation.adopt(assigned, Map.of());
        eaten.forEach((pattern, amounts) -> {
            for (AEKey key : amounts.keys()) {
                allocation.recordConsumption(pattern, key, amounts.getExact(key));
            }
        });
        return allocation;
    }

    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool, Map<IPatternDetails, WideKeyCounter> assigned,
            Map<IPatternDetails, WideKeyCounter> eaten) {
        return ParallelPlanReservation.build(tasks, pool, allocation(assigned, eaten));
    }

    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool, Map<IPatternDetails, WideKeyCounter> assigned,
            Map<IPatternDetails, WideKeyCounter> eaten, Set<AEKey> unbounded) {
        return ParallelPlanReservation.build(tasks, pool, allocation(assigned, eaten), unbounded);
    }

    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool, Map<IPatternDetails, WideKeyCounter> assigned,
            Map<IPatternDetails, WideKeyCounter> eaten, Set<AEKey> unbounded, Map<Object, RingSeedLedger.SeedDemand> seeds) {
        return ParallelPlanReservation.build(tasks, pool, allocation(assigned, eaten), unbounded, seeds);
    }

    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool, Map<IPatternDetails, WideKeyCounter> assigned,
            Map<IPatternDetails, WideKeyCounter> eaten, Set<AEKey> unbounded, Map<Object, RingSeedLedger.SeedDemand> seeds, BatchObligationLedger obligations) {
        return ParallelPlanReservation.build(tasks, pool, allocation(assigned, eaten), unbounded, seeds, obligations);
    }
}
