package git.chexson.chexsonsaeutils.crafting.executionwitness;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AggregateSolver 产出的精确输入分配快照。
 *
 * <p>全部数量都是压缩 run，不按 firing 展开；结构只含键、整数与有序列表，便于后续直接映射到 NBT。
 */
public record PlanningInputWitness(
        boolean complete,
        Map<AEItemKey, Long> expectedCrafts,
        List<PatternInputRuns> patternInputs,
        List<ExactKeyOwnership> ownership) {

    public PlanningInputWitness {
        expectedCrafts = copyCrafts(expectedCrafts);
        patternInputs = List.copyOf(Objects.requireNonNull(patternInputs, "patternInputs"));
        ownership = List.copyOf(Objects.requireNonNull(ownership, "ownership"));
        var definitions = new java.util.HashSet<AEItemKey>();
        for (var pattern : patternInputs) {
            if (!definitions.add(pattern.patternDefinition())) {
                throw new IllegalArgumentException("duplicate pattern definition");
            }
        }
        var exactKeys = new java.util.HashSet<AEKey>();
        for (var entry : ownership) {
            if (!exactKeys.add(entry.exactKey())) {
                throw new IllegalArgumentException("duplicate ownership key");
            }
        }
    }

    private static Map<AEItemKey, Long> copyCrafts(Map<AEItemKey, Long> source) {
        Objects.requireNonNull(source, "expectedCrafts");
        var copy = new LinkedHashMap<AEItemKey, Long>();
        source.forEach((key, amount) -> {
            Objects.requireNonNull(key, "pattern definition");
            if (amount == null || amount <= 0L) {
                throw new IllegalArgumentException("craft amount must be positive");
            }
            copy.put(key, amount);
        });
        return Map.copyOf(copy);
    }

    /** 一个 pattern definition 下按输入槽保存的真实 exact key 分配。 */
    public record PatternInputRuns(AEItemKey patternDefinition, int inputSlotCount, List<InputRun> runs) {
        public PatternInputRuns {
            Objects.requireNonNull(patternDefinition, "patternDefinition");
            if (inputSlotCount <= 0) {
                throw new IllegalArgumentException("inputSlotCount must be positive");
            }
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
            for (var run : runs) {
                if (run.inputSlot() >= inputSlotCount) {
                    throw new IllegalArgumentException("input slot outside pattern range");
                }
            }
        }
    }

    /** 某输入槽连续分配到同一 exact key、同一模板量的模板单位 run。 */
    public record InputRun(int inputSlot, AEKey exactKey, long templateAmount, long templateUnitCount) {
        public InputRun {
            Objects.requireNonNull(exactKey, "exactKey");
            if (inputSlot < 0) {
                throw new IllegalArgumentException("inputSlot must not be negative");
            }
            requirePositive(templateAmount, "templateAmount");
            requirePositive(templateUnitCount, "templateUnitCount");
        }
    }

    /** 一个 exact key 的有序 pattern 所有权 run。 */
    public record ExactKeyOwnership(AEKey exactKey, List<OwnershipRun> runs) {
        public ExactKeyOwnership {
            Objects.requireNonNull(exactKey, "exactKey");
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        }
    }

    /** 所有权保留槽与模板量，执行侧无需猜测单位换算。 */
    public record OwnershipRun(AEItemKey patternDefinition, int inputSlot, long templateAmount,
            long templateUnitCount) {
        public OwnershipRun {
            Objects.requireNonNull(patternDefinition, "patternDefinition");
            if (inputSlot < 0) {
                throw new IllegalArgumentException("inputSlot must not be negative");
            }
            requirePositive(templateAmount, "templateAmount");
            requirePositive(templateUnitCount, "templateUnitCount");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
