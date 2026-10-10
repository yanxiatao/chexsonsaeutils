package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import java.util.Map;
import java.util.Set;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.RetentionApplier;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import org.jetbrains.annotations.Nullable;

public final class GraphPlanPostProcessor {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private GraphPlanPostProcessor() {
    }

    public static SolveResult reconcileMissingWithStorage(
            SolveResult result,
            @Nullable MEStorage storage,
            @Nullable ICraftingService craftingService
    ) {
        if (!result.simulation() || storage == null) {
            return result;
        }
        var state = result.state();
        KeyCounter liveStock = null;
        for (AEKey key : state.missing().keys()) {
            var wideMissing = state.missing().getExactById(state.idOf(key));
            long missing = state.missing().getLongById(state.idOf(key));
            if (missing <= 0L) {
                continue;
            }
            if (liveStock == null) {
                liveStock = readLiveStock(storage);
            }
            if (liveStock.get(key) == Long.MAX_VALUE) {
                deferSaturatedMissingToSubmitLeg(state, key, PlanAmount.of(wideMissing));
                continue;
            }
            long extractable = storage.extract(key, missing,
                    appeng.api.config.Actionable.SIMULATE, IActionSource.empty());
            if (wideMissing.compareTo(WideCeilings.LONG_ACCOUNT) > 0 && extractable == Long.MAX_VALUE) {
                // SIMULATE 的承诺一次最多一个 long：真需求越出 long 时，"答应满 long"既可能来自
                // 注册型无限源，也可能来自一张巨大的有限盘——读侧同形，规划期分不清。
                // 把这份<b>饱和承诺</b>当供给记进 used 就是拿哨兵当量：实机 2026-09-25 11:20 那一跑
                // 提交腿照 2×Long.MAX 的宽域 used 真抽，把有限盘整仓吸进 CPU 池（"乱提取"本体）。
                // 改走延后：需求即供给记进宽域 used，提交腿只认明交不交得出满的一刀，剩下的那一截
                // 由执行侧按批常态续取（不再有任何一步把全量吸进池子）。
                deferSaturatedMissingToSubmitLeg(state, key, PlanAmount.of(wideMissing));
                continue;
            }
            if (wideMissing.compareTo(WideCeilings.LONG_ACCOUNT) > 0 || missing == Long.MAX_VALUE) {
                // 一次性取证：这一键的需求达到或超出 long，"到底能不能一次拿出满 long"就是
                // 无限源与真缺口的分界。两种形状都要记——宽域账（wideMissing > long）与
                // 窄账已饱和（missing == Long.MAX_VALUE，实机 lava／matter_ball 就是这样
                // 顶着天花板进缺料表的）不是一回事，只判前者那一型永远不打这一行。
                // 注意这行只是<b>取证</b>，不是判据：实机已证"网络报满 long"既可能是无限源
                // 也可能是很多张真盘累出来的（mekanism:redstone 曾被误判成无限源，计划敢吃
                // 8.5e37 枚、执行侧永远取不到、全员 noCand=empty 卡死，那两条判别已回退）。
                LOGGER.info("Graph planning shortage probe: key={} wideMissing={} extractable={}",
                        key, wideMissing, extractable);
            }
            if (GraphDiagnostics.active()) {
                GraphDiagnostics.log(
                        "reconcile key=%s missing=%d extractable=%d canEmitFor=%s",
                        key, missing, extractable,
                        craftingService != null && craftingService.canEmitFor(key));
            }
            if (extractable > 0L) {
                state.reduceMissing(key, extractable);
                state.addUsedForReconciliation(key, extractable);
                state.addStackBytes(key, extractable, 1L);
            }
        }
        return state.missing().isEmpty() ? result.withSimulation(false) : result;
    }

    /** 对账用的网络报量一读：读不到就交回空表（认不出任何哨兵，逐键照旧走 extract 对账）。 */
    private static KeyCounter readLiveStock(MEStorage storage) {
        var liveStock = new KeyCounter();
        try {
            storage.getAvailableStacks(liveStock);
            return liveStock;
        } catch (RuntimeException failure) {
            // 读不到报量绝不打断对账：认不出哨兵就是今日口径（照旧问 extract）
            LOGGER.info("Graph planning deferred shortage skipped: network stock unreadable —— {}",
                    failure.toString());
            return new KeyCounter();
        }
    }

    /**
     * 报量恰为哨兵 {@code Long.MAX_VALUE} 的缺口键走第三条路。
     *
     * <p>不 mint：neoecoae／extendedae_plus 的<b>有限</b>盘顶到 long 天花板也报这个数，
     * "报了多少"不是无限判据，认中即随计划出门的凭空造料一次都不许发生。也不留进
     * 规划期缺料表：KubeJS／ExtendedAE 那型<b>真无限源</b>在读侧与有限盘完全同形
     * （实机 2026-09-25 02:34，上一单把宿主读数顶到哨兵之后，1e10 的单因 lava／matter_ball
     * 留在缺料表里整单判死、开始键夹灰）。它的网络供给在 long 域里不可表达，规划期
     * 判不了"够不够"——判决延后给提交腿 {@code ParallelCraftingCpuLogic#tryExtractInitialItems}：
     * 那里对越出 long 的需求<b>只喂一刀</b>（{@code ≤long} 那一口），只认明这一刀给不给得起——
     * 一口给不满按真值报缺；给得起满口就放行提交，剩下那一截由执行侧
     * {@code refillTaskInputsFromNetwork} / {@code payDeclaredBatchObligations} 按批常态续取。
     *
     * <p>记账按"需求即供给"：missing 剔多少、used 就补多少（全程宽域精确值，不写任何
     * {@code Long.MAX_VALUE} 假数）。两本账都在守恒式的供给侧，同增同减，闸门与毛账
     * 不再把这一键报成 hole/miss；配给表（{@code buildMaterialPlan}）读同一份 used，
     * 提交腿照份额只喂一刀、剩下的由执行侧按批续取。取证行照打，"延后"必须说得出口，
     * 不许看起来像被吞了。
     */
    private static void deferSaturatedMissingToSubmitLeg(GraphPlanningState state, AEKey key,
            PlanAmount wideMissing) {
        LOGGER.info("Graph planning deferred shortage: network key={} reported={} simulated=未问"
                + " accept=deferred-to-submit wideMissing={} —— 哨兵报量在 long 域里判不了够不够，"
                + "判决延后给提交腿只喂一刀认明给不给得起", key, Long.MAX_VALUE, wideMissing);
        state.reduceMissingWide(key, wideMissing);
        state.addUsedForReconciliationWide(key, wideMissing);
        state.addStackBytesWide(key, 1L, wideMissing);
    }

    @Nullable
    public static String applyRetention(SolveResult result, CraftGraph graph, long requestedAmount) {
        return RetentionApplier.apply(result.state(), graph, requestedAmount);
    }

    @Nullable
    public static String conversionDecline(SolveResult result) {
        var state = result.state();
        if (!state.hasNegativeExitValues()) {
            return null;
        }
        // 点名来源：负数出在哪一本账上要一眼看得见（份数账为负就是上游算批出了符号错误）
        var negativeCrafts = state.negativeCraftDetails();
        return negativeCrafts == null
                ? "negative plan values"
                : "negative plan values: crafts[" + negativeCrafts + "]";
    }

    public static ICraftingPlan convert(SolveResult result, CraftGraph graph) {
        Ae2Amounts.logWideSummary();
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log("graph.hostBatch disabled: per-pattern tasks + runtime multi-push");
        }
        // 超 long 的显示旁路账已由 Ae2PlanConverter 在构造计划时带走
        // （RecursiveCraftingPlan / WideAmountCraftingPlan 两个载体），这里不再登记。
        return Ae2PlanConverter.toPlan(result, graph);
    }

    /**
     * 末级算法（NATIVE）也没辙时的「材料不足」回执：把守恒闸门逐键算出的缺口（宽域真值）装进
     * 一份<b>不含任何样板</b>的 {@code simulation=true} 计划。
     *
     * <p>为什么不复用那份被拒的账本走 {@link Ae2PlanConverter}：实机 2026-09-22 22:36 的
     * {@code ae2:creative_storage_cell x 1000000000} 证明了这条路是空的——被判不可行的账本是从
     * 超支的库存里出账的，出口值天然带负数，{@code hasNegativeExitValues} 那道守卫一挡，玩家仍然
     * 只看到 {@code IllegalStateException: Terminal planning algorithm declined}。
     * 报缺料若必须依赖一份"本来就不成立"的账，这条出口就永远可能交不出东西。
     *
     * <p>因此这里只交事实：需求 {@code finalOutput} 一分不砍、缺口逐键按宽域记进
     * {@code missingItems}（{@code >long} 那一截由 {@link WideLedgerSnapshot} 随计划出门，
     * 确认屏按真值显示），样板表为空——"这份订单做不出来"正是判决本身。
     * {@code simulation=true} 让它进不了提交链，不存在"假计划被吞下去执行"的口子。
     *
     * <p>判决不带缺口账（预算耗尽、图 overflow、编译失败、模糊槽排水）时照旧交回 {@code null}，
     * 调用方行为一字不变。这条出口不放宽任何判据：闸门该拒照旧拒。
     */
    @Nullable
    public static ICraftingPlan shortagePlan(@Nullable PlanningAttemptResult attempt, CraftGraph graph) {
        var declined = attempt == null ? null : attempt.declinedLedger();
        var shortage = declined == null ? null : declined.shortage();
        if (shortage == null || shortage.isEmpty()) {
            LOGGER.info("Graph planning shortage report idle: target={} reason={} 判决没带逐键缺口账",
                    graph.target(), attempt == null ? "null" : attempt.declineReason());
            // ⑧ idle 档补一行<b>同一把尺</b>（{@link #describeHoles}，同一个出口，逐字同形状）。
            // 不造 shortage、不加判据：idle 前提就是"判决没带逐键缺口账"⇒ shortage 空 ⇒
            // 这把尺此刻只会报 keys=0 shown=0 []——它<b>点名不了</b>实机那一枚（那枚压根没进 shortage，
            // 正是 ⑦ 那两道"不记需求"的静默口让它从没被记进 demand）。这一行的价值是让
            // "尺没跑"（没这行）与"尺跑了、覆盖 0 枚"（keys=0）两种状态分得开，不再一个字都不出。
            LOGGER.info("Graph planning shortage holes idle: target={} {}", graph.target(),
                    describeHoles(Map.of(), graph, declined == null ? null : declined.state()));
            return null;
        }
        var missingWide = new WideKeyCounter();
        var missing = new KeyCounter();
        for (var entry : shortage.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            missingWide.add(entry.getKey(), entry.getValue());
            missing.add(entry.getKey(), Ae2Amounts.toLong(entry.getValue()));
        }
        LOGGER.info("Graph planning shortage report: target={} amount={} simulation=true shortage={}",
                graph.target(), declined.finalAmount(), shortage);
        LOGGER.info("Graph planning shortage holes: target={} {}",
                graph.target(), describeHoles(shortage, graph, declined.state()));
        var base = new CraftingPlan(new GenericStack(graph.target(), declined.finalAmount()),
                0L, true, false, new KeyCounter(), new KeyCounter(), missing, Map.of());
        return new WideAmountCraftingPlan(base, WideLedgerSnapshot.capture(missingWide, null, null),
                null, Set.of());
    }

    /**
     * 缺口键的一层生产者溯源。
     *
     * <p>要分开的两件事只在现场才分得开：缺口键<b>图上根本没有生产者</b> ⇒ 真缺料，回执本身就是
     * 答案；<b>有生产者却没排进这一趟计划</b>（{@code times=0}）⇒ 规划不闭合，该修的是求解侧，
     * 拿"材料不足"打发玩家就是假结论。实机 2026-09-23 的 {@code iron_ingot short=2.07e20}
     * 落在哪一侧，判决行分不出来——它同时写着 {@code missing=0}，而玩家知道铁是可以从铁精华炼出来的。
     *
     * <p>每个生产者顺带列出它自己的前三格输入：这一格的输入若也在缺口表里，洞就还在更上游一层。
     */
    private static String describeHoles(Map<AEKey, PlanAmount> shortage, CraftGraph graph,
            @Nullable GraphPlanningState state) {
        var rows = new java.util.ArrayList<>(shortage.entrySet());
        rows.sort((left, right) -> right.getValue().exactValue()
                .compareTo(left.getValue().exactValue()));
        var producersByOutput = graph.producersByOutput();
        // 覆盖面自报：keys=这一把尺<b>真有几枚缺口键</b>（全量），shown=下面逐枚点名的（≤3）。
        // 没有这两个数，读日志的人会把"只点了前 3 枚"当成全量——shortage 有 68 枚也只摆 3 枚。
        int keysTotal = shortage.size();
        int keysShown = Math.min(3, rows.size());
        var described = new StringBuilder("[");
        for (int index = 0; index < Math.min(3, rows.size()); index++) {
            AEKey key = rows.get(index).getKey();
            var producers = producersByOutput.getOrDefault(key, java.util.List.of());
            if (index > 0) {
                described.append(' ');
            }
            described.append(key).append(" short=").append(rows.get(index).getValue().exactValue())
                    .append(" producers=").append(producers.size());
            int listed = 0;
            for (var producer : producers) {
                if (listed >= 2) {
                    break;
                }
                if (listed > 0) {
                    described.append(',');
                }
                // 判决可以不带账本（SolveResult.state 可空）：这行是玩家唯一能分清"真缺料"与
                // "有路没排进计划"的出口，不能被一份空账本打断。
                var times = state == null ? null : state.craftsWide().getAmount(producer.source());
                described.append("[").append(producer.source().getDefinition())
                        .append('#').append(producer.source().getClass().getSimpleName())
                        .append(" times=").append(times == null ? "?" : Ae2Amounts.toLong(times))
                        .append(" in=").append(describeInputs(producer, shortage, graph))
                        .append(']');
                listed++;
            }
        }
        return "keys=" + keysTotal + " shown=" + keysShown + " " + described.append(']');
    }

    /** 生产者自己的前三格输入：在缺口表里就报 short，否则报图上这份库存读数。 */
    private static String describeInputs(GraphPattern producer, Map<AEKey, PlanAmount> shortage,
            CraftGraph graph) {
        var described = new StringBuilder("[");
        int listed = 0;
        for (var input : producer.inputs()) {
            if (input == null || input.key() == null) {
                continue;
            }
            if (listed > 0) {
                described.append(',');
            }
            var gap = shortage.get(input.key());
            described.append(input.key()).append('=')
                    .append(gap == null ? "stock" + graph.stock().getOrDefault(input.key(), 0L)
                            : "short" + gap.exactValue());
            if (++listed >= 3) {
                described.append("...");
                break;
            }
        }
        return described.append(']').toString();
    }
}
