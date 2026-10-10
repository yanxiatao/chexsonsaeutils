package git.chexson.chexsonsaeutils.crafting.plan;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;

/**
 * 计划名单一行（B-1 → ⑩）的<b>唯一一处组装口</b>：两档（{@code stage=exit}/{@code stage=build}）
 * 都走这里，于是同一把尺——同一个 {@link PatternIdentity#patternIdentityTag 稳定 id}、
 * 同一份 {@code ,times=} 原文、同一个 {@code ->in|->DROP(zeroFirings)}、同一个 cap、同一套
 * {@code shown/total} 语义。两处只差那一张 {@code plan} 与那一格 {@code stage=}，
 * 一比 {@code stage=exit total=26} 对 {@code stage=build total=19}，少掉的那几张当场点名。
 *
 * <p>名单宇宙与份数取自 {@link #universeAndTimes}（就是建表那一句真算 executions 的来源，
 * 不在别处另算一份"看起来一样的数"）。纯读数：{@code shown}/{@code total}/{@code zeroFirings}
 * 不参与任何判据。
 *
 * <p><b>只进 debug.log，不进 latest.log</b>（{@code LOGGER.debug}，作者读的现场文件是
 * PrismLauncher 实例的 {@code debug.log}，DEBUG 档在那儿开着；{@code latest.log} 里读不到它，
 * 不是没打）。
 */
public final class PatternRosterReadout {

    /** 名单截断封顶：两档共用同一个数，覆盖面自报口径才一致。 */
    private static final int ROSTER_CAP = 64;

    private PatternRosterReadout() {
    }

    /**
     * 名单宇宙＝{@code AE2 代表值字典 ∪ 自有宽域份数账}（键序：先代表值字典、再宽域账独有那批），
     * 每枚的份数＝宽域真值优先、代表值兜底。返回保序 {@link LinkedHashMap}——建表与规划出口两处
     * 调它拿<b>同一份</b>派生，建表照这份数排任务，读数照这份数点名，绝不各算一份。
     */
    public static LinkedHashMap<IPatternDetails, PlanAmount> universeAndTimes(ICraftingPlan plan) {
        WideCraftSnapshot wideCrafts = WideAmountPlanCarrier.resolveWideCrafts(plan);
        Map<IPatternDetails, Long> representativeTimes = plan.patternTimes();
        Set<IPatternDetails> taskKeys = new LinkedHashSet<>(representativeTimes.keySet());
        taskKeys.addAll(wideCrafts.entries().keySet());
        var ordered = new LinkedHashMap<IPatternDetails, PlanAmount>();
        for (IPatternDetails pattern : taskKeys) {
            if (pattern == null) {
                continue;
            }
            BigInteger exactTimes = wideCrafts.get(pattern);
            Long representative = representativeTimes.get(pattern);
            PlanAmount executions = exactTimes != null
                    ? PlanAmount.of(exactTimes)
                    : PlanAmount.of(Math.max(0L, representative == null ? 0L : representative));
            ordered.put(pattern, executions);
        }
        return ordered;
    }

    /**
     * 组一行（去抬头）：{@code stage=<stage> shown=<a>/total=<b> zeroFirings=<c> list=[...] }。
     * {@code total}＝真有几张、{@code shown}＝截断后点出的（≤cap）、{@code zeroFirings}＝被整张丢掉
     * 的张数。自家子字段边界用 {@code ,}、候选分隔用 {@code /}、外来空白在格出口折成 {@code _}。
     */
    public static String formatLine(String stage, LinkedHashMap<IPatternDetails, PlanAmount> orderedTimes) {
        List<String> cells = new java.util.ArrayList<>();
        long total = 0L;
        long zeroFirings = 0L;
        for (var entry : orderedTimes.entrySet()) {
            var pattern = entry.getKey();
            if (pattern == null) {
                continue;
            }
            PlanAmount executions = entry.getValue();
            total++;
            boolean dropped = executions.signum() <= 0;
            if (dropped) {
                zeroFirings++;
            }
            if (cells.size() < ROSTER_CAP) {
                cells.add(rosterToken(pattern, executions, dropped));
            }
        }
        return "stage=" + stage
                + " shown=" + cells.size() + "/total=" + total
                + " zeroFirings=" + zeroFirings
                + " list=" + foldRosterWhitespace(String.join("/", cells));
    }

    /**
     * 名单里一张样板的一格：<b>纯读数</b>。
     * {@code definition#稳定id,times=份数原文,->in} 或 {@code ...->DROP(zeroFirings)}。稳定 id 走
     * {@link PatternIdentity#patternIdentityTag}——与 stall 行、{@code noCandWhy=}、
     * {@code quotaBlind=}、{@code blocked=} 那几处同一把尺，两档不许出现两处不同的 id 形状。
     */
    private static String rosterToken(IPatternDetails pattern, PlanAmount executions, boolean dropped) {
        return pattern.getDefinition() + PatternIdentity.patternIdentityTag(pattern)
                + ",times=" + executions
                + ",->" + (dropped ? "DROP(zeroFirings)" : "in");
    }

    /** 名单一行的格出口折点：只剩别人家的空白（样板头 {@code AEItemKey#toString} 的组件段），折成 {@code _}。 */
    private static String foldRosterWhitespace(String raw) {
        return raw.replace(' ', '_');
    }
}
