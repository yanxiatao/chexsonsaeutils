package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPatternFlows;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;

/**
 * Phase 2d B4 —— 后置守恒闸门（照 neoecoae {@code ECOPlanMaterialValidator}
 * 的立场）：solve 接受结果前逐键校验 {@code supply >= demand}，不符即
 * DECLINE 回落树路径。闭式捷径里的符号错误（A0 那类倒扣、环宏口径漂移、
 * SCC 与净流量口径不一致）都会在出门前被拦住，绝不让不可能的计划进
 * {@code CraftingPlan}。
 *
 * <p>只在 SUCCESS 时跑，不参与搜索，成本 O(patternTimes × 槽数)。
 * 账目逐 mode 复刻 {@code LinearPlanner.fire} 的口径，宽域精确累计：
 * <ul>
 * <li>supply = used（requiredExtract 峰值 ≥ 真实提取，方向保守）+ emitted
 * + ringExtractions（环催化物种子）+ Σ crafts 的产出/余料归还贡献；</li>
 * <li>demand = Σ crafts 的输入消耗 + finalOutput + missing（模拟轮缺口）。</li>
 * </ul>
 *
 * <p>模糊（多候选）槽的<b>逐键归属</b>由顺位决定，但"这一格每发真伸手的那一枚"不是没有答案——
 * 它就是执行侧 {@code ParallelBatchExtraction#usableTemplates} 顺位 ∩ {@code isValid} 的那一枚，
 * 规划侧对应物是 {@link #payableReachCandidate}（#194/#199 落地的计费尺）。任务 #212 起，
 * 这一枚键的<b>每发实吃</b>（槽倍率 × 每发件数 × 宽域发数）记进守恒判据遍历的同一本 demand
 * （{@link #chargeReachSlotDemand}）：有生产路线就被排产、
 * 没有就经 {@code shortageSink} 逐枚报缺——"从来没被要求去排产、也没被要求去报缺"的那声不吭
 * 就此关闭。任务 #214 把这一条搬到<b>自产侧</b>：图上这一枚有生产路线时宽窄一律记账
 * （{@link #chargesReachDemand}），"存量 0 而生产者还没被排上"那一档不再自锁——
 * 实机 {@code 01:46} 的 {@code mekanism:enriched_redstone x 3763234236668680780}
 * 正是塞得进 {@code long} 的一格。"越出 long 才记"只在<b>图上没有生产路线</b>的那一族留着，
 * 管的是封套契约（许可上界不许当需求），不是量级；两半的理由各见 {@link #chargesReachDemand} 抬头。
 * 整槽的跨候选归属仍按执行侧同一条顺位规则复算（{@link #validateFuzzyDrain}：
 * AE2 的一发本就在 {@code prepareSingleFiring} 的份数循环里跨候选续取）：整槽需求折成模板份数，
 * 先从靠前的候选键扣，扣不完才退下一个，全都扣不完的那一截只留在表上、不再拒单（A3 已按裁决摘除：
 * 258 矩阵尺现量到真管线造不出这一格，它只在手搓人造形状上响）。旧的"族内各键富余之和盖住
 * 最小模板量"是数学上最弱的必要条件，它把一个键的富余借给排在它前面的槽、且各槽各自复算，
 * 放行的表在实机上会以尾段某键 {@code net=0} 的形态缺料（{@code [chexsonsaeutils-stall] holes=}）。
 *
 * <p>判据粒度必须与执行侧一致：闸门 DECLINE 在末级算法也规划不出时是终局
 * （作业直接起不来），任何"按更严的假设收紧"都会把可执行的表拒成不可用。
 *
 * <p>产出侧的 CONSUMED_RETURNING 闭式修正与 {@code fire} 逐位一致：
 * 自喂键计 {@code produced×times − consumed×(times−1)}，同键多输出栈只
 * 修正第一个；A0 净增长门保证该式非负。环宏成员不退出校验域——按
 * {@code crafts} 里的交付发射数全量计供需，环内键由环内产出抵消，
 * 催化物种子另由 ringExtractions 侧供给；成员交付数与宏净流量在分数
 * scale 下的差额就此落进 demand，即"执行侧要吃、计划没出钱"的那份料。
 */
public final class MaterialClosureGate {

    private MaterialClosureGate() {
    }

    /**
     * @return null 表示守恒闭合；否则返回违规键描述，调用方 DECLINE。
     */
    public static @Nullable String validate(GraphPlanningState state, CraftGraph graph, long finalAmount) {
        return validate(state, graph, PlanAmount.of(finalAmount), null, null, null);
    }

    /**
     * 同 {@link #validate(GraphPlanningState, CraftGraph, long)}，另外把<b>逐键真缺口</b>
     * （{@code demand − supply}，宽域精确值）如实写进 {@code shortageSink}。
     *
     * <p>为什么要有这只口袋：DECLINE 只说明"这份账不可行"，不说明"还差多少"。而末级算法
     * 也没辙时玩家要看到的正是后者——缺口该多大报多大，{@code >long} 的那一截在这条出口
     * 上不能被 long 域截掉。闸门本身照旧拒绝，本方法不放宽任何判据。
     *
     * @param shortageSink 逐键缺口收集器；{@code null}（默认）一个字都不记
     */
    public static @Nullable String validate(GraphPlanningState state, CraftGraph graph, long finalAmount,
            @Nullable Map<AEKey, PlanAmount> shortageSink) {
        return validate(state, graph, PlanAmount.of(finalAmount), null, null, shortageSink);
    }

    /** 宽域根需求参与同一份守恒对账，不允许先饱和再判缺口。 */
    public static @Nullable String validate(GraphPlanningState state, CraftGraph graph, PlanAmount finalAmount,
            @Nullable Map<AEKey, PlanAmount> shortageSink) {
        return validate(state, graph, finalAmount, null, null, shortageSink);
    }

        // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#buildmaterialplan-封套另记一本与两本账 —— 台账 196/219
    public static MaterialPlanBill buildMaterialPlan(
            GraphPlanningState state, CraftGraph graph, long finalAmount) {
        return buildMaterialPlan(state, graph, PlanAmount.of(finalAmount));
    }

    /** 生成宽域根订单的完整配给账。 */
    public static MaterialPlanBill buildMaterialPlan(
            GraphPlanningState state, CraftGraph graph, PlanAmount finalAmount) {
        var plan = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
        // 封套账本（按样板 → 键 → 量，与下发的表同一形状）：只累计 billReachKeySlot 补记的那
        // 几笔（许可上界，不是排水实记）。表照旧把它们写进 materialPlan（下发内容逐字不变），
        // 但两处读数要分家：执行侧的 hole= 要剔掉这一本，配额夹反而只能读整本。否则首位族
        // 规划供给为 0（常态）时封套被当成实取，缺口读数就报出一格规模相关的假洞。
        var envelopes = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
        // 这一趟 validate 的存在理由是它顺位排水并把每格记在哪一枚、记几枚写进这两本账；
        // A5（判决非空即交白卷）已按裁决摘除——交回的字符串不再决定表的去留。
        validate(state, graph, finalAmount, plan, envelopes, null);
        // 账本本体可变，"不许下游改这张表"由出口那份深拷贝保证（{@code RecursiveCraftingPlan}
        // 的契约读法逐张 copy()）——老口径这里的 unmodifiableMap 视图本就是丢弃的无副作用调用。
        return new MaterialPlanBill(plan, envelopes);
    }

    /**
     * 规划出口的那一份配给账：两本账出自<b>同一趟</b>闸门排水，因此必然同宇宙、同顺位。
     *
     * <p>{@code assigned} 是<b>下发</b>的整张表（排水实记 + 模糊槽顺位伸手候选的封套），执行侧拿它
     * 当配给上界（配额夹读这一本，含封套）。{@code envelopes} 是其中<b>封套那一截</b>，
     * 按样板 → 键 → 量，与 {@code assigned} 同一形状：它是"若向这一族伸手至多整格"的许可上界，
     * 不是计划真取走的量，所以凡是"这一族还缺多少料"的读数（执行侧 {@code hole=} 的 {@code need} 分子）
     * 都要先减掉它。
     *
     * <p>两本都不进 NBT（规划期一次性产物，随计划对象在内存里出门）；都不许降级成
     * {@code Map<AEKey, Long>}——这本账是被减数，截短就等于把配到的那一截抹掉。
     */
    public record MaterialPlanBill(
            Map<IPatternDetails, WideKeyCounter> assigned,
            Map<IPatternDetails, WideKeyCounter> envelopes) {
    }

    private static @Nullable String validate(GraphPlanningState state, CraftGraph graph, PlanAmount finalAmount,
            @Nullable java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> materialPlan,
            @Nullable Map<IPatternDetails, WideKeyCounter> envelopes,
            @Nullable Map<AEKey, PlanAmount> shortageSink) {
        var patternsBySource = graph.patternsBySource();
        // 环宏成员在收缩后不再挂在 patternsBySource 上（宏顶替了它的生产者身份），
        // 逐样板的 GraphPattern 对它不可得。改按宏口径入账：成员条目退出逐样板
        // 累计，宏以"实际交付的发射向量的净流量"计入供需——那正是 LinearPlanner
        // 与补种写进沙盒的同一份数字，环内键由净流量互相抵消，环外缺口就此留在域内。
        var ringMembers = collectRingMembers(graph);
        var supply = new WideKeyCounter();
        var demand = new WideKeyCounter();
        // 份数一律读宽账：饱和视图把超界发数截成 Long.MAX_VALUE，供需两侧就都不是计划里那份数。
        // 遍历走注册槽号序：供需两只账本的键登记序由"先摊开哪张样板"决定，而判决
        // （{@code demand.keys()} 里第一个不平的键）点的就是这个登记序里的第一个——留着
        // {@code keys()} 的哈希序，同一份失衡账两次会话会点到不同的那一种料上（台账 #143 T2）。
        var craftTimes = state.craftsWide();
        for (IPatternDetails source : craftTimes.keysInRegistrationOrder()) {
            if (source == null) {
                continue;
            }
            PlanAmount times = craftTimes.getAmount(source);
            if (ringMembers.contains(source)) {
                // 环成员退出逐样板供需（宏按净流量入账），但它的取料份数照样要进配给表：
                // 不进表就等于这张样板没有配额，硬封顶对它形同虚设
                // （实机 over=[template ate=63 share=0, netherrack, diamond]）。
                billRawSlots(source, times, materialPlan);
                continue;
            }
            // 不在图上的样板：不 bill、不判（与 collectFuzzySlots 的 null 守卫同一口径）——
            // 这张样板整本不进配给表后，ParallelPlanReservation#hasQuotaBasis 交回 false ⇒
            // quotaExact 交"没有配额口径"＝不夹，降级的是配额口径，不是崩溃。
            var pattern = patternsBySource.get(source);
            if (pattern == null) {
                continue;
            }
            accumulate(pattern, times, supply, demand);
            billPlannedSlots(pattern, times, materialPlan);
        }
        addRingNetFlows(graph, craftTimes, supply, demand);
        addToSupply(supply, state.used());
        addToSupply(supply, state.emitted());
        addToSupply(supply, state.ringExtractions());
        demand.add(graph.target(), finalAmount);
        // missing 是「需求未被供给覆盖」的缺口记录，属供给侧平衡项：
        // supply + missing >= demand 才是模拟轮的守恒式。
        // 必须读<b>宽域</b>那本账（state.missing()）：AE2 的 KeyCounter 每键只到 long 上限，
        // 拿 narrow 视图对账会把"两个站点各记满一天花板"的真实缺口截成一个 long，
        // 于是守恒式看着不平、整条规划被拒（实机 ae2:creative_storage_cell x 1000000 的 lava）。
        addToSupply(supply, state.missing());
        // 模糊槽"每格顺位真伸手的那一枚 × 每发件数 × 发数"进需求宇宙（任务 #212，见方法抬头）。
        // 记账必须在逐键核对之前：守恒判据与 shortageSink 因此天然覆盖这一族，两条出路
        // （有生产路线⇒排产、没有⇒既有缺口口袋报缺）全部沿用既有机制，不在这里另立判据。
        var fuzzySlots = collectFuzzySlots(state, graph, ringMembers, supply);
        chargeReachSlotDemand(state, graph, fuzzySlots, supply, demand);
        // 供给原账在这一刻定稿：此后本趟再没有一条腿写 {@code supply}（{@code validateFuzzyDrain}
        // 只读它）。下游配给表读的就是这一本判过的账——谁想加供给腿只能加在闸门这一处，
        // 不许在出口另拼第二套宇宙（任务 #219 条目①定的就是这一条）。
        // 逐键核对：第一处不平就是判决（原文一字未动），但缺口要逐键记全——末级算法也没辙时
        // 玩家看到的"还差多少"就来自这只口袋，只报第一个键会把其余该补的料藏进异常里。
        String imbalance = null;
        for (var key : demand.keys()) {
            PlanAmount have = supply.getAmount(key);
            PlanAmount want = demand.getAmount(key);
            if (have.compareTo(want) >= 0) {
                continue;
            }
            if (shortageSink != null) {
                shortageSink.put(key, want.subtract(have));
            }
            if (imbalance == null) {
                imbalance = "material closure violated: " + key
                        + " supply=" + have
                        + " demand=" + want
                        + " short=" + want.subtract(have)
                        + " missing=" + state.missing().getAmount(key)
                        // used= 交宽域真值：这一腿与 missing 同源同账，饱和视图把溢出键
                        // 钉成天花板就是让读日志的人拿哨兵当量对账（台账 #192 同一口径）。
                        + " used=" + state.used().getAmount(key)
                        // producers= 暂留：narrowProducibleReachDemandWithoutScheduledProducer 用例
                        // 钉它"有路线却没排产"那一型，与报缺料出口（shortageSink）同批摘，本刀不动那条用例。
                        + " producers=" + graph.producersByOutput()
                                .getOrDefault(key, java.util.List.of()).size();
            }
        }
        if (imbalance != null) {
            return imbalance;
        }
        // A3（顺位排水扣不完即拒单）已按裁决摘除：排水照旧决定"每格记在哪一枚、记几枚"进配给表，
        // 但扣不满的那一截不再由闸门拦下。258 矩阵尺现量过：真管线七档（FakeGrid→compile→solve）
        // 造不出这一格，它只在手搓人造形状上响；纵深在编译期件数口径与三把尺上，不在运行时否决。
        validateFuzzyDrain(fuzzySlots, supply, demand, materialPlan, envelopes);
        return null;
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#validatefuzzydrain-顺位排水按模板份数复算 —— 台账 26/27
    /**
     * 顺位排水：把每个模糊槽的整批发射数按 {@code ranked} 顺位逐候选记进配给表（含跨槽共用的
     * {@code slack} 一本账）。
     *
     * <p>这一趟<b>只出表、不判</b>——A3 摘除后扣不满的那一截既不拒单也不报话术，照旧留在表上由
     * 执行侧那一格现形（#194 那一型的 {@code got=0 ⇒ ranDry} 从此没有第二道运行时闸，这是裁决）。
     */
    private static void validateFuzzyDrain(java.util.List<FuzzySlot> slots,
            WideKeyCounter supply, WideKeyCounter demand,
            @Nullable java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> materialPlan,
            @Nullable Map<IPatternDetails, WideKeyCounter> envelopes) {
        var slack = new HashMap<AEKey, PlanAmount>();
        // 排水次序按样板规范串定序（collectFuzzySlots 里已定死）：配给表每格记在哪一枚、记几枚
        // 只由表内容决定，不随身份哈希序翻（台账 #143 T2／#135 第 2 条同一把尺）。
        for (var slot : slots) {
            long multiplier = slot.input().multiplier();
            if (multiplier <= 0L) {
                continue;
            }
            PlanAmount needUnits = slot.times().multiply(PlanAmount.of(multiplier));
            for (var candidate : slot.ranked()) {
                if (needUnits.signum() <= 0L) {
                    break;
                }
                if (candidate.amount() <= 0L) {
                    continue;
                }
                PlanAmount available = slackOf(slack, supply, demand, candidate.key())
                        .add(ownReachClaim(slot, candidate.key()));
                PlanAmount perTemplate = PlanAmount.of(candidate.amount());
                PlanAmount fundable = available.divide(perTemplate);
                PlanAmount taken = fundable.compareTo(needUnits) < 0 ? fundable : needUnits;
                if (taken.signum() <= 0L) {
                    continue;
                }
                slack.put(candidate.key(), available.subtract(taken.multiply(perTemplate)));
                needUnits = needUnits.subtract(taken);
                if (materialPlan != null) {
                    // 份数直接以宽域入账：这里先窄化成 long 就等于把这张槽付掉的那一截
                    // 截给天花板看，执行侧照这份数取料时会少算掉越界的那部分份额
                    bill(materialPlan, slot.source(), candidate.key(),
                            taken.multiply(perTemplate));
                }
            }
            if (materialPlan != null) {
                // M1：把"这一格执行侧真会伸手、而排水按规划供给一次都没付到"的那一族记进配给表。
                // 排水的 bill 只盖 taken>0 的候选键；现场量为 0 的那一格其键根本不进表，于是
                // ParallelPlanReservation#hasFamilyEntry 为假——回补那条臂（quotaExact slot!=null）
                // 交 null 能伸手，取料点那条臂（quotaExact slot==null）却把没在册的族读成份额 0 夹死。
                // 补记之后两条臂读同一份真份额，不需要放开第二道夹子。口径不是与
                // ParallelCraftingCpuLogic#mintSubmitAllocation 同源——mint 那趟只在排水没付满
                // （unfunded>0）时把未付的那一截记到 reported，而规划表这一趟不判付没付满（A3 摘除后
                // 照发），所以这一笔
                // 是整槽份额（槽倍率 × 伸手候选每发量 × 宽域发数）：该族在本格的排水实吃恒为 0
                // （这正是它不在册的前提，0 份进不了表），记的是"执行侧若向它伸手，至多整格"的
                // 封套，不是顺位实吃的那一截。reach 键走取料点同一把尺（声明顺位 ∩ 规划宇宙，
                // payableReachCandidate），首位付不到就退下一候选、整槽都付不到就不记这一格；
                // 份数走宽域、按 selfRepaying 决定进不进表。
                // 封套与本格拆分并存 ⇒ 该格在表上被两族各写一份；这一笔只是许可上界、不是计划
                // 真从这一族取走的量，故另记一本 envelopes、执行侧
                // hole= 的 need 减数从这里逐键相减（随契约同一条穿壳路出门），首位族规划供给为 0 时表
                // 照发——由 MaterialClosureGateTest 的 reachKeyEnvelopeShipsWhenPrimaryFamilyUnsupplied
                // 与执行侧 ParallelEnvelopeHoleReadoutTest 钉住。这一族每发真要吃的那一截另有一腿：
                // ParallelCraftingCpuLogic#billReachSlotDemand 是提交边界的读数腿（纯读数不改判据）；
                // 规划闭合侧的同一笔在 chargeReachSlotDemand（有生产路线的一族宽窄一律记、
                // 没有生产路线的封套形状仍只在越出 long 时记；封套本身照旧不进实记分子）。
                billReachKeySlot(materialPlan, envelopes, slot);
            }
        }
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#ownreachclaim-本格伸手记账数回加 —— 台账 212
    private static PlanAmount ownReachClaim(FuzzySlot slot, AEKey key) {
        var reach = slot.reachCandidate();
        if (reach == null || slot.reachRequired().signum() <= 0L) {
            return PlanAmount.ZERO;
        }
        return reach.key().equals(key) ? slot.reachRequired() : PlanAmount.ZERO;
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#collectfuzzyslots-三型不记与宁缺不假同源 —— 台账 214/146
    private static java.util.List<FuzzySlot> collectFuzzySlots(GraphPlanningState state,
            CraftGraph graph, Set<IPatternDetails> ringMembers, WideKeyCounter supply) {
        var patternsBySource = graph.patternsBySource();
        var slots = new ArrayList<FuzzySlot>();
        var craftTimes = state.craftsWide();
        // 收集照旧走这份账自己的遍历序：这一次排水的先后完全由下面那次排序定死，
        // 来序只是稳定排序的起点，不参与任何判据
        for (IPatternDetails source : craftTimes.keys()) {
            PlanAmount times = craftTimes.getAmount(source);
            if (source == null || times.signum() <= 0 || ringMembers.contains(source)) {
                continue;
            }
            var pattern = patternsBySource.get(source);
            if (pattern == null) {
                continue;
            }
            var rawInputs = source.getInputs();
            for (int index = 0; index < pattern.inputs().size(); index++) {
                var input = pattern.inputs().get(index);
                if (input.mode() != GraphInput.Mode.NORMAL || input.extractCandidates() == null) {
                    continue;
                }
                var ranked = rankedCandidates(input, graph);
                var rawInput = rawInputs != null && index >= 0 && index < rawInputs.length
                        ? rawInputs[index] : null;
                var reach = payableReachCandidate(rawInput, ranked);
                PlanAmount required = PlanAmount.ZERO;
                if (reach != null && reach.key() != null && reach.amount() > 0L
                        && input.multiplier() > 0L
                        && !SlotAccount.selfRepaying(rawInput, reach.key())) {
                    var shot = mul2(input.multiplier(), reach.amount()).multiply(times);
                    if (chargesReachDemand(graph, supply, ranked, reach.key(), shot)) {
                        required = shot;
                    }
                }
                slots.add(new FuzzySlot(source, times, index, input, ranked,
                        orderKey(source, pattern, ranked), craftTimes.registrationSlot(source),
                        reach, required));
            }
        }
        // crafts 是身份哈希图：按 entrySet 顺序贪心排水会让同一张表这次放行、下次拒掉，
        // 实机复现一次判一次。改按样板规范串定序，配给表只由表内容决定。
        // 规范串自己会并列——两张 definition 相同、输入输出与候选表逐字相同的样板在这一档
        // 拆不开，剩下的先后就由那份哈希遍历序决定（同一趟账里两次采样都能翻）。尾键因此接成
        // （规范串，槽下标，这张样板进这本份数账的注册槽号）：后两档都不是新量尺——槽下标是这份
        // 声明里既有的位置，注册槽号是 {@code WideCraftCounter.registered} 那趟登记序
        // （与 {@code describeNegative}／{@code orderedLongView} 同一把尺），它跨会话稳的口径与
        // 边界照 {@code WideCraftCounter} 类抬头：graph 路由 {@code ProviderPatternOrder} 定死，
        // AE2 树路那条腿仍随会话翻（闸门只在 graph 路上跑）。
        slots.sort(Comparator.comparing(FuzzySlot::orderKey)
                .thenComparingInt(FuzzySlot::index)
                .thenComparingInt(FuzzySlot::registrationSlot));
        return slots;
    }

        // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#chargesreachdemand-每发实吃记账档位 —— 台账 214/196
    private static boolean chargesReachDemand(CraftGraph graph, WideKeyCounter supply,
            List<GraphInput.Candidate> ranked, AEKey reachKey, PlanAmount shot) {
        if (!graph.producersByOutput().getOrDefault(reachKey, List.of()).isEmpty()) {
            // 图上有生产路线：同本体族里有别枚带来源 ⇒ 执行侧第一触就捞得到，这一格记不进 demand。
            if (sameClassSiblingSourced(graph, supply, ranked, reachKey)) {
                return false;
            }
            return true;
        }
        // 图上没有生产路线。{@code shot.isFast()} 为真＝这一枚塞得进 long（窄域快路）；
        // {@code hasSource}＝网络诚实存量或这份计划已入账的供给腿里有来路。
        boolean hasSource = graph.supplyAmount(reachKey).signum() > 0
                || supply.getAmount(reachKey).signum() > 0;
        // 越出 long（!isFast）且账上没来路的这一档不记进 demand：守的是 121/123/126/196 的封套契约
        // （许可上界不是量级，与"塞得进 long"不同型）。判据本体逐字不变。
        return !shot.isFast() && hasSource;
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#sameclasssiblingsourced-同本体族带来源即不记需求 —— 台账 46/146
    private static boolean sameClassSiblingSourced(CraftGraph graph, WideKeyCounter supply,
            List<GraphInput.Candidate> ranked, AEKey reachKey) {
        var family = reachKey.getPrimaryKey();
        for (var candidate : ranked) {
            if (candidate == null || candidate.key() == null || candidate.amount() <= 0L
                    || candidate.key().equals(reachKey)
                    || !family.equals(candidate.key().getPrimaryKey())) {
                continue;
            }
            if (supply.getAmount(candidate.key()).signum() > 0
                    || graph.supplyAmount(candidate.key()).signum() > 0) {
                return true;
            }
        }
        return false;
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#chargereachslotdemand-伸手实吃进需求宇宙与存量腿 —— 台账 212/36
    private static void chargeReachSlotDemand(GraphPlanningState state, CraftGraph graph,
            java.util.List<FuzzySlot> slots, WideKeyCounter supply, WideKeyCounter demand) {
        if (slots.isEmpty()) {
            return;
        }
        var credited = new HashMap<AEKey, PlanAmount>();
        for (var slot : slots) {
            var reach = slot.reachCandidate();
            var required = slot.reachRequired();
            if (reach == null || required.signum() <= 0L) {
                continue;   // 不可得/模具/非法/不记账的那一档：供需两侧逐字走旧路
            }
            demand.add(reach.key(), required);
            if (reach.key().equals(graph.target()) || credited.containsKey(reach.key())) {
                continue;
            }
            var headroom = graph.supplyAmount(reach.key())
                    .subtract(state.used().getAmount(reach.key()));
            credited.put(reach.key(), headroom.max(PlanAmount.ZERO));
            if (headroom.signum() > 0) {
                supply.add(reach.key(), headroom);
            }
        }
    }

    /**
     * 定键槽（非模糊）的配给：全额记在它自己的计费键上。
     *
     * <p>原样退回的模具槽（RETURNED / CONSUMED_RETURNING）<b>不进这张表</b>：它每发都要把那份
     * 起步量过一遍池子又退回来，按份数封顶会把它自己夹死，执行侧因此对它走结构判据。
     */
    private static void billPlannedSlots(GraphPattern pattern, PlanAmount times,
            @Nullable java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> materialPlan) {
        if (materialPlan == null || times.signum() <= 0) {
            return;
        }
        for (var input : pattern.inputs()) {
            // 落表的就是这两档：FINITE_USE 与不带候选的 NORMAL（都是定键槽）。模糊 NORMAL 槽不在这
            // 张表里（由排水那趟按顺位实吃另记）。判据与旧写法逐字等价，只是把同一个乘式留一份。
            boolean biddable = input.mode() == GraphInput.Mode.FINITE_USE
                    || (input.mode() == GraphInput.Mode.NORMAL && input.extractCandidates() == null);
            if (biddable) {
                bill(materialPlan, pattern.source(), input.key(),
                        // 定键槽的份数＝每发量 × 槽倍率 × 宽域发数：发数越界时这份数本就
                        // 可以 >long，表是配给的唯一上游，在这里窄化就再也补不回来
                        mul3(input.amountPerCraft(), input.multiplier(), times));
            }
        }
    }

    private static void bill(java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> materialPlan,
            IPatternDetails source, AEKey key, PlanAmount units) {
        if (key == null || units.signum() <= 0) {
            return;
        }
        // 逐笔宽域复加，绝不饱和：同一键被这张样板的多个槽、多个候选各记一笔，合计没有上界。
        // 老口径这里是 merge(..., saturatingAdd)——顶到天花板就是把自己配到的那一截抹掉。
        materialPlan.computeIfAbsent(source, ignored -> new WideKeyCounter()).add(key, units);
    }

        // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#billreachkeyslot-顺位伸手候选封套补记 —— 台账 194/199
    private static void billReachKeySlot(
            java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> materialPlan,
            @Nullable Map<IPatternDetails, WideKeyCounter> envelopes, FuzzySlot slot) {
        IPatternDetails source = slot.source();
        var rawInputs = source == null ? null : source.getInputs();
        if (rawInputs == null || slot.index() < 0 || slot.index() >= rawInputs.length) {
            return;
        }
        IPatternDetails.IInput rawInput = rawInputs[slot.index()];
        GraphInput.Candidate reach = slot.reachCandidate();
        if (reach == null || SlotAccount.selfRepaying(rawInput, reach.key())) {
            // 整槽都付不到 ⇒ 这一格不进配给表。记它等于凭空立一笔执行侧永远兑不了的账：
            // ParallelPlanReservation#quotaExact 的伸手口径只因 hasFamilyEntry 为真就不交 null，
            // 回补腿于是照着网络里不存在的数目逐次 got=0 ⇒ ranDry（台账 #194 的卡死那一格）。
            return;
        }
        AEKey reachKey = reach.key();
        // 这一族在这张样板的配给表里已有任一条目（含排水本格刚记的那一笔）：族已在册、两臂读
        // 同一真份额，无需补记。这正是"回落/多候选已付"与"现场量为 0 从没付"的分界。
        WideKeyCounter billed = materialPlan.get(source);
        if (billed != null && SlotAccount.hasFamilyEntry(billed, reachKey)) {
            return;
        }
        // 每发单位数＝槽倍率 × 宇宙内伸手那一枚的每发件数，与 billRawSlots 同一把尺（宽域精确、
        // 不留饱和中间值）；非法输入（倍率或每发件数非正）交回 0，这一格因此不 bill。
        PlanAmount envelope = PlanAmount
                .of(SlotAccount.unitsPerFiringExact(slot.input().multiplier(), reach.amount()))
                .multiply(slot.times());
        if (envelope.signum() <= 0) {
            return;
        }
        bill(materialPlan, source, reachKey, envelope);
        // 同一笔另记封套账（同一份值、同一个出口）：执行侧 hole= 的 need 从这里逐键相减，
        // 只核排水实记；bill 对非正增量不落账，所以这一本必然是表那一本的子账
        // （envelope.signum() 在本行前已由上面 <=0 早退夹成正，旧写法多写的 >0 判据恒真，摘掉）。
        if (envelopes != null) {
            envelopes.computeIfAbsent(source, ignored -> new WideKeyCounter())
                    .add(reachKey, envelope);
        }
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#payablereachcandidate-声明顺位交回宇宙内那一枚 —— 台账 199 与提交 2527e4e
    /**
     * 这一格执行侧真伸手的那一枚：沿 {@code rawInput} 的<b>声明顺位</b>走，第一枚能在候选宇宙
     * （{@link #rankedCandidates}）里落到可付键的即胜出。
     *
     * <p>宇宙已由 {@link SlotAccount#reachUniverse} 按声明顺位定死，件数取"先伸手那一份声明
     * 自己的每发件数"（首位那份、不去重），与编译器 {@code resolveReachCandidate} 同源；这里
     * 只交回首位那份的落点，不再另写一遍件数取值。首位付不到就退下一候选、整槽都付不到就不记这一格。
     */
    private static @Nullable GraphInput.Candidate payableReachCandidate(
            @Nullable IPatternDetails.IInput rawInput, List<GraphInput.Candidate> ranked) {
        var candidates = rawInput == null ? null : rawInput.getPossibleInputs();
        if (candidates == null) {
            return null;
        }
        for (GenericStack candidate : candidates) {
            if (!SlotAccount.isValidCandidate(candidate)) {
                continue;
            }
            var payable = payableUniverseCandidate(ranked, candidate.what());
            if (payable != null) {
                return payable;
            }
        }
        return null;
    }

    /**
     * 声明的这一枚候选，在该格的规划宇宙里真付得到的是<b>哪一枚</b>：精确键在册就是它（正向臂，
     * 键与件数逐字不变）；精确键被 {@code isValid} 挡下、同族另有一枚在册时，交回宇宙内那一枚。
     *
     * <p>族这一档仍是判据的一部分（同类变体在同一格里本就是取料点的退路，按精确键判会把
     * "声明候选自己被挡、同族变体却被收下"误判成付不到 ⇒ 摘掉封套 ⇒ 取料点那条臂
     * （{@code ParallelPlanReservation#quotaExact slot==null}）把没在册的族读成份额 0 夹死——
     * M1 已钉过的形状）；改的只是<b>落点</b>：封套的键与每发件数一律取宇宙内真付得到的那一枚
     * （件数由 {@link SlotAccount#reachUniverse} 按声明顺位定死，不再是候选宇宙里最省的那份）。
     */
    private static @Nullable GraphInput.Candidate payableUniverseCandidate(
            List<GraphInput.Candidate> ranked, AEKey declaredKey) {
        var family = declaredKey.getPrimaryKey();
        GraphInput.Candidate sameFamily = null;
        for (var candidate : ranked) {
            if (candidate == null
                    || !SlotAccount.isValidCandidate(candidate.key(), candidate.amount())) {
                continue;
            }
            if (candidate.key().equals(declaredKey)) {
                return candidate;
            }
            if (sameFamily == null && family.equals(candidate.key().getPrimaryKey())) {
                sameFamily = candidate;
            }
        }
        return sameFamily;
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#billrawslots-环成员按首位候选计费 —— 实机 ate=63
    private static void billRawSlots(IPatternDetails source, PlanAmount times,
            @Nullable java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> materialPlan) {
        if (materialPlan == null || times.signum() <= 0 || source.getInputs() == null) {
            return;
        }
        for (var slot : source.getInputs()) {
            var first = SlotAccount.firstCandidate(slot);
            if (first == null || SlotAccount.selfRepaying(slot, first)) {
                continue;
            }
            // 同 billPlannedSlots：模具槽已排除，计费发数就是全部发数。每发单位数也不留
            // 饱和中间值——老口径借 SlotAccount.unitsPerFiring 的 saturatingMultiply，
            // 槽倍率 × 模板量自己就超界的这一格在配给表上少写一截，执行侧照表取料必然少取。
            // 非法输入（倍率/每发件数非正）交回 0：这一槽不 bill。
            var unit = SlotAccount.unitsPerFiringExact(slot, first);
            if (unit.signum() <= 0L) {
                continue;
            }
            bill(materialPlan, source, first.what(), PlanAmount.of(unit).multiply(times));
        }
    }

    /**
     * 内容规范串（槽排序的第一档）：同一张表每次解出来的槽序一致，不含实例身份。
     * 两张 definition 相同、输入输出与候选表逐字相同的样板在这一档仍并列，剩下的先后由
     * {@link #validateFuzzyDrain} 那次排序的尾两档（槽下标、注册槽号）定死。
     */
    private static String orderKey(IPatternDetails source, GraphPattern pattern,
            List<GraphInput.Candidate> ranked) {
        var builder = new StringBuilder(64);
        builder.append(source.getDefinition()).append('|')
                .append(pattern.outputKey()).append('@').append(pattern.outputPerCraft());
        for (var input : pattern.inputs()) {
            builder.append('|').append(input.mode()).append(':').append(input.key())
                    .append('#').append(input.amountPerCraft()).append('x').append(input.multiplier());
        }
        for (var candidate : ranked) {
            builder.append('/').append(candidate.key()).append('=')
                    .append(candidate.amount());
        }
        return builder.toString();
    }

    /**
     * 一个待排水的模糊槽：整批发射数按 ranked 顺位扣（发数可超 {@code long}）。
     *
     * <p>{@code reachCandidate}＝收集时用现成的 {@link #payableReachCandidate}（声明顺位 ∩
     * 规划宇宙）定死的"这一格执行侧真伸手的那一枚"；{@code reachRequired}＝这一枚的每发实吃
     * （槽倍率 × 每发件数 × 宽域发数）进需求宇宙的那一笔——非正即"本账不记"（记不记的尺在
     * {@link #chargesReachDemand}；幽灵/模具/非法输入三型宁缺不假，纪律与 {@link #billReachKeySlot} 同源）。
     */
    private record FuzzySlot(IPatternDetails source, PlanAmount times, int index,
            GraphInput input, List<GraphInput.Candidate> ranked, String orderKey,
            int registrationSlot, @Nullable GraphInput.Candidate reachCandidate,
            PlanAmount reachRequired) {
    }

    /**
     * 该槽真实可取的键与模板量：与执行侧取料同一套可达性——按候选顺位展开各自的
     * 同物品类变体（{@code graph.classVariants()}，AE2 取料按物品本体把同类变体一并递回），
     * 只留 {@code validVariants} 通过的键；一个键被多个候选覆盖时取最省的模板量。
     *
     * <p>这份宇宙同时是配给表的宇宙：表里出现过的每个键，执行侧必须真能伸手要——
     * {@code ParallelBatchExtraction} 的候选展开必须与此同集，否则表会配到取不到的键上。
     * 少了这套展开会把执行侧真能取的变体判成无解（假拒），拿 {@code validVariants}
     * 整集当退路又会把没通过的键记进池子（少收费）。
     */
    private static List<GraphInput.Candidate> rankedCandidates(GraphInput input, CraftGraph graph) {
        var valid = input.validVariants();
        var declared = input.extractCandidates();
        if (valid == null || declared == null) {
            return List.of();
        }
        // 选枚一次都不在这里重写：整段走 {@link SlotAccount#reachUniverse}——外层声明顺位、内层
        // {@code graph.classVariants} 的同族清单、{@code input.validVariants} 收下哪几枚，件数取
        // "先伸手那一份声明自己的每发件数"。这与编译器 {@code resolveReachCandidate} 同一把尺，
        // 也不再是候选宇宙里的最省那份——老口径 {@code acceptVariant} 取 min，会把每发 2 枚的一格
        // 配成 1 枚：闭式解按 2 枚排产 30、表只配 15，尾段那一格照表取料必然 ranDry（台账 #194 那一型）。
        var possible = new GenericStack[declared.size()];
        for (int i = 0; i < declared.size(); i++) {
            var candidate = declared.get(i);
            possible[i] = candidate == null ? null : new GenericStack(candidate.key(), candidate.amount());
        }
        var reach = SlotAccount.reachUniverse(possible, graph.classVariants()::get, valid::contains);
        var ranked = new ArrayList<GraphInput.Candidate>(reach.size());
        for (var entry : reach) {
            ranked.add(new GraphInput.Candidate(entry.key(), entry.amountPerFiring()));
        }
        return ranked;
    }

    private static PlanAmount slackOf(Map<AEKey, PlanAmount> slack, WideKeyCounter supply,
            WideKeyCounter demand, AEKey key) {
        return slack.computeIfAbsent(key, ignored -> {
            PlanAmount free = supply.getAmount(key).subtract(demand.getAmount(key));
            return free.signum() > 0 ? free : PlanAmount.ZERO;
        });
    }

    /**
     * 只给诊断：对毛账（每个输入槽按第一候选计需求）发现供不应求的键，逐槽点名
     * "毛账要 X、闸门实际收 Y、收在哪个计费键上"——模具槽只收起步量、模糊槽改按
     * 整族收费、环成员只按净流量入账，这三类豁免都让闸门口径比执行侧宽松。
     *
     * <p>仅在闸门通过而毛账有洞时调用，不参与校验本身。
     */
    public static String explainHoles(GraphPlanningState state, CraftGraph graph,
            java.util.List<AEKey> holeKeys) {
        if (state == null || graph == null || holeKeys == null || holeKeys.isEmpty()) {
            return "";
        }
        var wanted = new java.util.LinkedHashSet<AEKey>(holeKeys);
        var patternsBySource = graph.patternsBySource();
        var ringMembers = collectRingMembers(graph);
        var supply = new WideKeyCounter();
        var demand = new WideKeyCounter();
        var grossNeed = new WideKeyCounter();
        var exempt = new HashMap<AEKey, List<String>>();
        var covers = new HashMap<AEKey, List<String>>();
        var probes = new ArrayList<FuzzyCover>();
        // 诊断口径与判据口径必须同源：这里也读宽账，否则毛账报的洞与实际收费的洞不是同一份数。
        // 遍历也走注册槽号序（台账 #143 T2）：{@code exempt}／{@code covers} 每个键下那几行的
        // 先后、以及 {@code probes} 的先后，全由"先摊开哪张样板"决定；留着哈希序，同一份洞两次
        // 会话会给出逐字不同的诊断行——而 {@code explainHoles} 的返回串就是要被人拿去对账的那一行。
        var craftTimes = state.craftsWide();
        for (IPatternDetails source : craftTimes.keysInRegistrationOrder()) {
            if (source == null) {
                continue;
            }
            PlanAmount times = craftTimes.getAmount(source);
            if (times.signum() <= 0) {
                continue;
            }
            var pattern = patternsBySource.get(source);
            if (ringMembers.contains(source)) {
                continue;
            }
            if (pattern == null) {
                continue;
            }
            accumulate(pattern, times, supply, demand);
            explainSlots(pattern, times, wanted, grossNeed, exempt, probes);
        }
        addRingNetFlows(graph, craftTimes, supply, demand);
        addToSupply(supply, state.used());
        addToSupply(supply, state.emitted());
        for (var key : state.ringExtractions().keys()) {
            supply.add(key, state.ringExtractions().getAmount(key));
        }
        addToSupply(supply, state.missing());
        for (var probe : probes) {
            note(covers, probe.grossKey(), describeCover(probe, supply, demand));
        }
        var described = new StringBuilder("holes=[");
        for (AEKey key : wanted) {
            PlanAmount gateDemand = demand.getAmount(key);
            PlanAmount gateSupply = supply.getAmount(key);
            PlanAmount gross = grossNeed.getAmount(key);
            if (described.length() > 6) {
                described.append(", ");
            }
            described.append(key)
                    .append(" gateNeed=").append(gateDemand)
                    .append(" grossNeed=").append(gross)
                    .append(" gateSupply=").append(gateSupply)
                    .append(" exempt=").append(describeLines(exempt.get(key)))
                    .append(" cover=").append(describeLines(covers.get(key)));
        }
        return described.append(']').toString();
    }

    /** 逐候选对齐执行侧顺位规则：有没有一个键单独盖得住整槽，还是只有整族加起来才够。 */
    private static String describeCover(FuzzyCover probe, WideKeyCounter supply, WideKeyCounter demand) {
        var line = new StringBuilder(probe.definition()).append("#slot").append(probe.slotIndex())
                .append(" times=").append(probe.times()).append(" billed=").append(probe.billed())
                .append(" rank=[");
        String firstCover = null;
        int printed = 0;
        for (var candidate : probe.candidates()) {
            if (candidate == null || candidate.amount() <= 0L) {
                continue;
            }
            PlanAmount need = WideMath.mul(candidate.amount(), probe.multiplier())
                    .multiply(probe.times());
            PlanAmount slack = supply.getAmount(candidate.key())
                    .subtract(demand.getAmount(candidate.key()));
            if (slack.signum() < 0) {
                slack = PlanAmount.ZERO;
            }
            boolean covers = slack.compareTo(need) >= 0;
            if (covers && firstCover == null) {
                firstCover = candidate.key().toString();
            }
            if (printed++ < 3) {
                if (line.length() > 0 && line.charAt(line.length() - 1) != '[') {
                    line.append(", ");
                }
                line.append(candidate.key()).append('@').append(candidate.amount())
                        .append(" need=").append(need).append(" slack=").append(slack)
                        .append(covers ? " OK" : " NO");
            }
        }
        if (printed > 3) {
            line.append(", ...(").append(printed).append(')');
        }
        return line.append(']').append(" firstCover=").append(firstCover == null ? "none" : firstCover)
                .toString();
    }

    /** 模糊槽的整族收费点：等供需累计完再判"哪个候选单独盖得住"。 */
    private record FuzzyCover(String definition, int slotIndex, AEKey grossKey, AEKey billed,
            long multiplier, PlanAmount times, List<GraphInput.Candidate> candidates) {
    }

    /** 逐槽把毛账与闸门收费对齐，只对 holeKeys 里的键记账。 */
    private static void explainSlots(GraphPattern pattern, PlanAmount times, java.util.Set<AEKey> wanted,
            WideKeyCounter grossNeed, Map<AEKey, List<String>> exempt, List<FuzzyCover> probes) {
        var slots = graphSlots(pattern.source());
        var inputs = pattern.inputs();
        for (int index = 0; index < inputs.size(); index++) {
            GraphInput input = inputs.get(index);
            IPatternDetails.IInput slot = index < slots.size() ? slots.get(index) : null;
            AEKey grossKey = grossKeyOf(slot);
            PlanAmount grossAmount = grossAmountOf(slot, times);
            if (grossKey != null && wanted.contains(grossKey) && grossAmount.signum() > 0) {
                grossNeed.add(grossKey, grossAmount);
            }
            AEKey billed = input.key();
            if (!wanted.contains(grossKey) && !wanted.contains(billed)) {
                continue;
            }
            String reason = null;
            switch (input.mode()) {
                case RETURNED, CONSUMED_RETURNING -> reason = "startup-only once="
                        + mul2(input.amountPerCraft(), input.multiplier());
                case FINITE_USE -> reason = null;
                case NORMAL -> {
                    if (input.extractCandidates() != null) {
                        reason = "fuzzy-slot family-only";
                        if (grossKey != null) {
                            probes.add(new FuzzyCover(pattern.source().getDefinition().toString(),
                                    index, grossKey, billed, input.multiplier(), times,
                                    input.extractCandidates()));
                        }
                    }
                }
            }
            if (reason == null) {
                continue;
            }
            note(exempt, grossKey == null ? billed : grossKey,
                    pattern.source().getDefinition() + "#slot" + index
                            + " mode=" + input.mode() + " billed=" + billed
                            + " gross=" + grossAmount + " " + reason
                            + (input.validVariants() == null ? "" : " variants=" + input.validVariants().size()));
        }
    }

    private static void note(Map<AEKey, List<String>> exempt, AEKey key, String line) {
        if (key == null) {
            return;
        }
        var lines = exempt.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (lines.size() < 12) {
            lines.add(line);
        }
    }

    private static String describeLines(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "[]";
        }
        var described = new StringBuilder("[");
        for (int index = 0; index < Math.min(4, lines.size()); index++) {
            if (index > 0) {
                described.append(" | ");
            }
            described.append(lines.get(index));
        }
        if (lines.size() > 4) {
            described.append(" | ...(").append(lines.size()).append(")");
        }
        return described.append(']').toString();
    }

    private static java.util.List<IPatternDetails.IInput> graphSlots(@Nullable IPatternDetails source) {
        var slots = source == null ? null : source.getInputs();
        return slots == null ? java.util.List.of() : java.util.List.of(slots);
    }

    private static @Nullable AEKey grossKeyOf(@Nullable IPatternDetails.IInput slot) {
        var first = SlotAccount.firstCandidate(slot);
        return first == null ? null : first.what();
    }

    private static PlanAmount grossAmountOf(@Nullable IPatternDetails.IInput slot, PlanAmount times) {
        var first = SlotAccount.firstCandidate(slot);
        if (first == null || times.signum() <= 0) {
            return PlanAmount.ZERO;
        }
        // 两步乘（先「每份 × 槽倍率」，再乘发数）两步都不留饱和中间值：毛账与判据读同一把
        // 尺子才对得上账，第一步一饱和报出来的就是天花板而不是真值
        var unit = SlotAccount.unitsPerFiringExact(slot, first);
        if (unit.signum() <= 0L) {
            return PlanAmount.ZERO;
        }
        return PlanAmount.of(unit).multiply(times);
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#addtosupply-判定只走宽域版本 —— 实机 lava 1.6e19
    private static void addToSupply(WideKeyCounter target, WideKeyCounter source) {
        for (var key : source.keys()) {
            target.add(key, source.getAmount(key));
        }
    }

    /** 全部环宏的成员样板集合——逐样板累计不承载它们，由宏的净流量入账。 */
    private static Set<IPatternDetails> collectRingMembers(CraftGraph graph) {
        var members = new HashSet<IPatternDetails>();
        for (var macros : graph.ringMacrosByEntry().values()) {
            for (var macro : macros) {
                members.addAll(macro.executionRatio().keySet());
            }
        }
        return members;
    }

    /**
     * 按宏实际交付的发射向量（{@code crafts} 里的 per-member times）计净流量：
     * 正值是该环交给沙盒的料，负值是它要吃掉、必须由 used 或旁人产出覆盖的料。
     * 同一个 SCC 会按不同入口键登记成多个宏（共享同一份 memberFlows），
     * 按 memberFlows 的引用身份去重，一个环只入账一次。
     */
    private static void addRingNetFlows(CraftGraph graph, WideCraftCounter crafts,
            WideKeyCounter supply, WideKeyCounter demand) {
        var countedFlows = new java.util.IdentityHashMap<Object, Boolean>();
        for (var macros : graph.ringMacrosByEntry().values()) {
            for (var macro : macros) {
                if (countedFlows.put(macro.memberFlows(), Boolean.TRUE) != null) {
                    continue;
                }
                var firings = new java.util.LinkedHashMap<IPatternDetails, PlanAmount>();
                for (var entry : macro.executionRatio().entrySet()) {
                    PlanAmount times = crafts.getAmount(entry.getKey());
                    if (times.signum() > 0) {
                        firings.put(entry.getKey(), times);
                    }
                }
                if (firings.isEmpty()) {
                    continue;
                }
                for (var flow : macro.netFlowOf(firings).entrySet()) {
                    int sign = flow.getValue().signum();
                    if (sign > 0) {
                        supply.add(flow.getKey(), PlanAmount.of(flow.getValue()));
                    } else if (sign < 0) {
                        demand.add(flow.getKey(), PlanAmount.of(flow.getValue().negate()));
                    }
                }
            }
        }
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#accumulate-发数取宽域真值 —— 数字 8e17 假通过
    private static void accumulate(GraphPattern pattern, PlanAmount times,
            WideKeyCounter supply, WideKeyCounter demand) {
        // long 快路径：三数乘积在 long 域内精确时零分配；溢出才回落宽路径。
        // 饱和方向与出口窄化一致（向上饱和），校验只朝更松的方向偏离，不误报。
        for (var input : pattern.inputs()) {
            switch (input.mode()) {
                case FINITE_USE, NORMAL -> {
                    if (input.mode() == GraphInput.Mode.NORMAL && input.extractCandidates() == null) {
                        // 定键腿（FINITE_USE 与不带候选的 NORMAL 共用同一句 demand.add，此处只留一份）。
                        demand.add(input.key(),
                                mul3(input.amountPerCraft(), input.multiplier(), times));
                        // FINITE_USE 的退料供给腿并进同一趟（旧写法是对 inputs() 的第二遍遍历）：
                        // 它只依赖 mode()==FINITE_USE，与本趟写进 supply/demand 的键互不相干。
                        if (input.mode() == GraphInput.Mode.FINITE_USE) {
                            supply.add(input.remainderKey(), PlanAmount.of(input.multiplier()).multiply(times));
                        }
                    }
                }
                case RETURNED, CONSUMED_RETURNING -> {
                    // 批量发射只有首次真实提取，其余由闭式补偿覆盖——所以这一腿<b>不乘发数</b>，
                    // 单项就是「每发量 × 槽倍率」自己那一乘。lava 这类自喂催化物在 >long 的订单里
                    // 这一乘本身就越界（每发 3e12 枚 × 倍率 3e9 = 9e21 枚）。老口径走
                    // WideMath.wideMul 的饱和值：demand 上凭空立起一格天花板，守恒式于是报出
                    // short 恰好等于 Long.MAX_VALUE 的假缺口（实机 2026-09-24 13:50 minecraft:lava，
                    // 而 mul3 那一条腿已在上一步修过，剩下的饱和腿就是这里）。
                    demand.add(input.key(), mul2(input.amountPerCraft(), input.multiplier()));
                    if (input.mode() == GraphInput.Mode.RETURNED) {
                        supply.add(input.remainderKey(), input.multiplier());
                    }
                }
            }
        }
        boolean hasSelfFeeding = pattern.hasSelfFeedingInput();
        Set<AEKey> corrected = null;
        for (var out : pattern.allOutputs()) {
            if (!hasSelfFeeding) {
                supply.add(out.what(), PlanAmount.of(out.amount()).multiply(times));
                continue;
            }
            PlanAmount amount = PlanAmount.of(out.amount()).multiply(times);
            PlanAmount consumedPerCraft = GraphPatternFlows.selfConsumedPerCraft(pattern, out.what());
            if (consumedPerCraft.signum() > 0) {
                if (corrected == null) {
                    corrected = new HashSet<>();
                }
                if (corrected.add(out.what())) {
                    PlanAmount produced = GraphPatternFlows.outputPerCraftOf(pattern, out.what()).multiply(times);
                    PlanAmount consumed = consumedPerCraft.multiply(times.subtract(PlanAmount.ONE));
                    amount = produced.subtract(consumed);
                } else {
                    amount = PlanAmount.ZERO;
                }
            }
            supply.add(out.what(), amount);
        }
    }

    /**
     * 每发量 × 槽倍率 × 宽域发数。发数还在 long 域时逐字走旧的 long 快路径
     * （含逐级饱和），只有真超界的发数才整程宽域精确——闸门判据因此不因份数饱和而失真。
     */
    private static PlanAmount mul3(long a, long b, PlanAmount times) {
        if (times.isFast()) {
            return mul3(a, b, times.fastValue());
        }
        return PlanAmount.of(a).multiply(PlanAmount.of(b)).multiply(times);
    }

    // 理由与台账出处见 MATERIAL_CLOSURE_RULES.md#mul3-三数乘交回精确宽乘 —— 数字 1e13 那档
    private static PlanAmount mul3(long a, long b, long c) {
        long ab = WideMath.wideMul(a, b);
        if (ab == Long.MAX_VALUE || ab == Long.MIN_VALUE) {
            return PlanAmount.of(a).multiply(PlanAmount.of(b)).multiply(PlanAmount.of(c));
        }
        long product = WideMath.wideMul(ab, c);
        if (product == Long.MAX_VALUE || product == Long.MIN_VALUE) {
            return PlanAmount.of(ab).multiply(PlanAmount.of(c));
        }
        return PlanAmount.of(product);
    }

    /**
     * 两数乘积：与 {@link #mul3(long, long, long)} 同一把尺子——long 域内零分配，
     * 一旦越界就交回<b>精确</b>宽乘，绝不把饱和值当量用。
     *
     * <p>回流/自喂槽（RETURNED / CONSUMED_RETURNING）的起步量、环成员槽的每发单位数都是
     * "不乘发数的单项"：发数再怎么放大也盖不住这一乘自己就越界的事实。拿
     * {@code WideMath.wideMul} 的天花板当那份数，供需两侧都不再是计划里那个数。
     */
    private static PlanAmount mul2(long a, long b) {
        long product = WideMath.wideMul(a, b);
        if (product == Long.MAX_VALUE || product == Long.MIN_VALUE) {
            return PlanAmount.of(a).multiply(PlanAmount.of(b));
        }
        return PlanAmount.of(product);
    }

}
