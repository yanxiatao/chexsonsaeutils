package git.chexson.chexsonsaeutils.crafting.graphplan.convert;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;

/**
 * 留种核对：确认"要留在网络里的那一颗种"在计划跑完之后仍在网络。
 *
 * <p>留种本身只有一条规则，落在 {@link CraftGraph#retainedSeeds()} 与
 * {@code GraphPlanningState.holdBackRetainedSeeds}——那颗种从一开始就不是可花库存，
 * 所以补它的那一发由求解自己排出来，原料（钻石那一类）在同一次闭式解里跟主链争料。
 *
 * <p>本类因此不再改账：它只把每个催化物的 {@code projected}（计划跑完该键在网络侧的投影）
 * 与要留的份数对一遍，输出判决行。投影不足说明求解没排出这一发，缺料已经记在真正缺的那一步，
 * 这里再补一发就是第二套口径——事后补种本来也不成立：解完的账本把每枚料都排死了。
 */
public final class RetentionApplier {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final long RETAINED_SEED_COUNT = 1L;

    private RetentionApplier() {
    }

    /** 核对每个环催化物的留种量，返回 null（本阶段不再拒规划、不再改账）。 */
    @Nullable
    public static String apply(GraphPlanningState state, CraftGraph graph, long finalAmount) {
        var verdicts = new StringBuilder();
        Map<AEKey, CompressedRing> retainingRings = graph.retainingRingsByCatalyst();
        // 份数读宽账：留种投影按"发数 × 每发流量"算，截断的发数会把投影算少（假 SHORT）
        WideCraftCounter crafts = state.craftsWide();
        for (var entry : retainingRings.entrySet()) {
            AEKey catalyst = entry.getKey();
            CompressedRing ring = entry.getValue();
            if (catalyst == null || ring == null || ring.catalysts() == null) {
                continue;
            }
            long seedAmount = ring.catalysts().get(catalyst);
            if (seedAmount <= 0L) {
                continue;
            }
            long retainedAmount = WideMath.wideMul(RETAINED_SEED_COUNT, seedAmount);
            long projected = PlanFlow.projectedNetworkAmount(crafts, graph,
                    graph.target(), finalAmount, catalyst);
            appendVerdict(verdicts, catalyst, "seed=" + seedAmount + " projected=" + projected
                    + (projected >= retainedAmount ? " kept" : " SHORT")
                    // 判决必须自带账目：哪张样板在产这颗种、哪张在吃，各多少发。
                    // 没有这两串就分不开"任务表没排进翻倍那几发"与"投影算错了环成员的账"。
                    + " " + PlanFlow.describeSeedFlows(crafts, graph, catalyst));
        }
        if (!verdicts.isEmpty()) {
            LOGGER.info("[chexsonsaeutils-retention] {}", verdicts);
        }
        return null;
    }

    private static void appendVerdict(StringBuilder verdicts, AEKey key, String verdict) {
        if (!verdicts.isEmpty()) {
            verdicts.append(", ");
        }
        verdicts.append(key).append(": ").append(verdict);
    }

    private static final class PlanFlow {

        private PlanFlow() {
        }

        /**
         * 判决自带的账目：这颗种在任务表里的生产者（每发产出 × 发数）与消费者
         * （每发吃几份 × 发数），各列前三。
         *
         * <p>只有这两串能分开两种完全不同的故障：任务表压根没排进翻倍那几发（规划少算），
         * 与投影把环成员的账读错了（判决假警）。
         */
        static String describeSeedFlows(WideCraftCounter crafts, CraftGraph graph, AEKey key) {
            var made = new StringBuilder("makes=[");
            var eaten = new StringBuilder("eats=[");
            int madeCount = 0;
            int eatenCount = 0;
            for (IPatternDetails details : crafts.keys()) {
                PlanAmount times = crafts.getAmount(details);
                if (details == null || times.signum() <= 0L) {
                    continue;
                }
                long perOut = 0L;
                if (details.getOutputs() != null) {
                    for (var output : details.getOutputs()) {
                        if (output != null && key.equals(output.what())) {
                            perOut += output.amount();
                        }
                    }
                }
                long perIn = 0L;
                if (details.getInputs() != null) {
                    for (var input : details.getInputs()) {
                        var possible = input == null ? null : input.getPossibleInputs();
                        if (possible == null) {
                            continue;
                        }
                        for (var candidate : possible) {
                            if (candidate != null && key.equals(candidate.what())) {
                                perIn = Math.max(perIn,
                                        candidate.amount() * Math.max(1L, input.getMultiplier()));
                                break;
                            }
                        }
                    }
                }
                if (perOut > 0L && madeCount++ < 3) {
                    if (made.length() > 8) {
                        made.append(", ");
                    }
                    made.append(details.getDefinition()).append('#')
                            .append(details.getClass().getSimpleName())
                            .append(' ').append(perOut).append('x').append(times);
                }
                if (perIn > 0L && eatenCount++ < 3) {
                    if (eaten.length() > 7) {
                        eaten.append(", ");
                    }
                    eaten.append(details.getDefinition()).append('#')
                            .append(details.getClass().getSimpleName())
                            .append(' ').append(perIn).append('x').append(times);
                }
            }
            return made.append(']').append(' ').append(eaten.append(']'))
                    .append(" graphStock=").append(graph.stock().getOrDefault(key, 0L)).toString();
        }

        static long projectedNetworkAmount(WideCraftCounter crafts, CraftGraph graph,
                AEKey target, long finalAmount, AEKey key) {
            // long 快路径：单键流量在 long 域内精确时零分配；溢出回落宽路径。
            // 饱和只在单键流量超 9e18 时触发（实机总量 1e12 级），常态逐位一致。
            // 发数本身超界的样板直接整程走宽路径——截断的发数会把投影算少（假 SHORT）。
            long projected = graph.stock().getOrDefault(key, 0L);
            boolean wide = false;
            PlanAmount wideProjected = null;
            for (IPatternDetails details : crafts.keys()) {
                PlanAmount times = crafts.getAmount(details);
                if (details == null || times.signum() <= 0L) {
                    continue;
                }
                var compiled = graph.patternsBySource().get(details);
                if (!wide && times.isFast()) {
                    long narrow = times.fastValue();
                    long next = compiled != null
                            ? accumulateKeyCompiledLong(compiled, narrow, key, projected)
                            : accumulateKeyRawLong(details, narrow, key, projected);
                    if (next != Long.MIN_VALUE) {
                        projected = next;
                        continue;
                    }
                    wide = true;   // 单键流量越界：同一份发数在宽路径上重算
                }
                wide = true;
                wideProjected = wideProjected == null
                        ? PlanAmount.of(projected)
                        : wideProjected;
                wideProjected = compiled != null
                        ? accumulateKeyCompiled(compiled, times, key, wideProjected)
                        : accumulateKeyRaw(details, times, key, wideProjected);
            }
            if (wide) {
                if (key.equals(target)) {
                    wideProjected = wideProjected.subtract(PlanAmount.of(finalAmount));
                }
                return Math.max(0L, Ae2Amounts.toLong(wideProjected));
            }
            if (key.equals(target)) {
                projected -= finalAmount;
            }
            return Math.max(0L, projected);
        }

        /** long 快路径累加；溢出返回 {@link Long#MIN_VALUE} 哨兵。 */
        private static long accumulateKeyCompiledLong(
                git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern compiled, long times,
                AEKey key, long projected) {
            for (var input : compiled.inputs()) {
                if (input.extractCandidates() == null) {
                    if (input.key().equals(key)) {
                        long delta = mul3(input.amountPerCraft(), input.multiplier(), times);
                        if (delta == Long.MIN_VALUE && !(input.amountPerCraft() == 0
                                || input.multiplier() == 0 || times == 0)) {
                            return Long.MIN_VALUE;
                        }
                        projected = subSaturating(projected, delta);
                        if (projected == Long.MIN_VALUE) {
                            return Long.MIN_VALUE;
                        }
                    }
                    continue;
                }
                boolean found = false;
                for (var candidate : input.extractCandidates()) {
                    if (!found && candidate.key().equals(key)) {
                        found = true;
                        long delta = mul3(candidate.amount(), input.multiplier(), times);
                        if (delta == Long.MIN_VALUE && !(candidate.amount() == 0
                                || input.multiplier() == 0 || times == 0)) {
                            return Long.MIN_VALUE;
                        }
                        projected = subSaturating(projected, delta);
                        if (projected == Long.MIN_VALUE) {
                            return Long.MIN_VALUE;
                        }
                    }
                }
            }
            for (var out : compiled.allOutputs()) {
                if (out.what().equals(key)) {
                    long delta = WideMath.wideMul(out.amount(), times);
                    if ((delta == Long.MAX_VALUE || delta == Long.MIN_VALUE) && out.amount() != 0 && times != 0) {
                        return Long.MIN_VALUE;
                    }
                    projected = addSaturating(projected, delta);
                    if (projected == Long.MIN_VALUE) {
                        return Long.MIN_VALUE;
                    }
                }
            }
            return projected;
        }

        /** long 快路径累加（raw 样板路径）；溢出返回 {@link Long#MIN_VALUE} 哨兵。 */
        private static long accumulateKeyRawLong(IPatternDetails details, long times,
                AEKey key, long projected) {
            for (var input : details.getInputs()) {
                if (input == null || input.getPossibleInputs() == null) {
                    continue;
                }
                boolean found = false;
                for (GenericStack possibleInput : input.getPossibleInputs()) {
                    if (possibleInput == null || possibleInput.what() == null) {
                        continue;
                    }
                    if (!found && possibleInput.what().equals(key)) {
                        found = true;
                        long delta = mul3(possibleInput.amount(), input.getMultiplier(), times);
                        if (delta == Long.MIN_VALUE
                                && !(possibleInput.amount() == 0 || input.getMultiplier() == 0 || times == 0)) {
                            return Long.MIN_VALUE;
                        }
                        projected = subSaturating(projected, delta);
                        if (projected == Long.MIN_VALUE) {
                            return Long.MIN_VALUE;
                        }
                    }
                }
            }
            for (GenericStack outputStack : details.getOutputs()) {
                if (outputStack != null && outputStack.what() != null && outputStack.what().equals(key)) {
                    long delta = WideMath.wideMul(outputStack.amount(), times);
                    if ((delta == Long.MAX_VALUE || delta == Long.MIN_VALUE)
                            && outputStack.amount() != 0 && times != 0) {
                        return Long.MIN_VALUE;
                    }
                    projected = addSaturating(projected, delta);
                    if (projected == Long.MIN_VALUE) {
                        return Long.MIN_VALUE;
                    }
                }
            }
            return projected;
        }

        private static long mul3(long a, long b, long c) {
            long ab = WideMath.wideMul(a, b);
            if ((ab == Long.MAX_VALUE || ab == Long.MIN_VALUE) && a != 0 && b != 0) {
                return Long.MIN_VALUE;
            }
            return WideMath.wideMul(ab, c);
        }

        private static long addSaturating(long a, long b) {
            long sum = a + b;
            // 哨兵透传：任一端已是哨兵则整体转宽路径重算
            if (a == Long.MIN_VALUE || b == Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MIN_VALUE : sum;
        }

        private static long subSaturating(long a, long b) {
            if (a == Long.MIN_VALUE || b == Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            long diff = a - b;
            return ((a ^ b) & (a ^ diff)) < 0 ? Long.MIN_VALUE : diff;
        }

        private static PlanAmount accumulateKeyCompiled(
                git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern compiled,
                PlanAmount times, AEKey key, PlanAmount projected) {
            for (var input : compiled.inputs()) {
                if (input.extractCandidates() == null) {
                    if (input.key().equals(key)) {
                        projected = projected.subtract(WideMath.mul(input.amountPerCraft(), input.multiplier())
                                .multiply(times));
                    }
                    continue;
                }
                Set<AEKey> seenKeys = new HashSet<>();
                for (var candidate : input.extractCandidates()) {
                    if (candidate.key().equals(key) && seenKeys.add(candidate.key())) {
                        projected = projected.subtract(WideMath.mul(candidate.amount(), input.multiplier())
                                .multiply(times));
                    }
                }
            }
            for (var out : compiled.allOutputs()) {
                if (out.what().equals(key)) {
                    projected = projected.add(PlanAmount.of(out.amount()).multiply(times));
                }
            }
            return projected;
        }

        private static PlanAmount accumulateKeyRaw(IPatternDetails details, PlanAmount times,
                AEKey key, PlanAmount projected) {
            for (var input : details.getInputs()) {
                if (input == null || input.getPossibleInputs() == null) {
                    continue;
                }
                Set<AEKey> seenKeys = new HashSet<>();
                for (GenericStack possibleInput : input.getPossibleInputs()) {
                    if (possibleInput == null || possibleInput.what() == null) {
                        continue;
                    }
                    if (possibleInput.what().equals(key) && seenKeys.add(possibleInput.what())) {
                        projected = projected.subtract(WideMath.mul(possibleInput.amount(), input.getMultiplier())
                                .multiply(times));
                    }
                }
            }
            for (GenericStack outputStack : details.getOutputs()) {
                if (outputStack != null && outputStack.what() != null && outputStack.what().equals(key)) {
                    projected = projected.add(PlanAmount.of(outputStack.amount()).multiply(times));
                }
            }
            return projected;
        }
    }
}
