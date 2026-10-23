package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.depgraph.DependencyGraphs;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 环种账本：只按当前未完成的样板表派生"哪些键要靠环自己转回来、每发的起步量是多少"。
 *
 * <p>起步那份种子的量是规划期算好的（{@code RetentionApplier} 多跑一发补齐缺口，
 * 折进 {@code usedItems} 一次提到 CPU 库存）。本账本对外交两样东西，都来自<b>同一次</b>派生：
 * {@link #seedAmounts()} 喂给 {@link RecursiveTaskOrdering} 决定**先推谁**——环生产者排在旁人之前；
 * {@link #seedDemandsByFamily()} 交出<b>每个在册的精确种键各自所在分量的成员集合、它们一发吃掉
 * 那一枚几枚、一发又还回那一枚几枚</b>，
 * 供取料点上的环种留底（{@code ParallelPlanReservation}）复加"分量自己还没跑完的那一截"。
 * 分量归属只在这里算一次：那份 SCC 就是环的形状，别处再写一遍"还回 K"／"喂给待办 producer"
 * 就成了第二把尺。
 *
 * <p>分量口径<b>按精确种键建档</b>，只在最后一步按物品本体族分组交出去：同族几个精确键各记自己那一份，
 * 谁也不并谁。按族合过一次就已经少算——一条环里同族两个键（同一物品的无组件与带组件两个变体各成一条环、
 * 或一张样板一发同时吃同族两个精确键）并成一份时，族格上只剩两者里最大的那一枚，
 * 旁人从此能从池子里多取走少算的那一截；两个不同分量的成员被并成一份名单时，
 * A 分量的成员还能免扣 B 分量那份口粮，B 能被 A 抽干。族是取料点那把尺的<b>查表口径</b>，
 * 不是<b>记账口径</b>。
 *
 * <p>环的判据是任务表自身的拓扑：把"样板↔物料键"连成二部图（样板按产出指向
 * 它还回来的键、键按消耗指向吃它的样板），一个强连通分量就是一条环。单发自我
 * 回炉的样板只是一个特例——链式环（A 吃 K 出 L、B 吃 L 出 K）里没有任何样板把
 * 物料还给自己。
 *
 * <p>资格那一格只看<b>这一枚键被分量里一张还没跑完的样板吃掉</b>（纯拓扑，判据在 {@link #collectSeed}），
 * <b>不再问"分量还不还得回来"</b>：旧口径"分量内产出不少于消耗（{@code ret >= need}）才算种"把净吃种
 * 那一档（链式环 A 吃 K 出 L 欠 5 发、B 吃 L 出 K 欠 4 发）<b>整键撤账</b> ⇒ 取料点不产留底 ⇒ 池里那枚 K
 * 在环转起来之前被旁人一发抽干、这条本来还能再转 4 轮的环永久饿死（用户 2026-09-29 定版
 * "环优先跑，跑完环再放行"）。那一档本来就靠规划期起步提取补那一枚缺口。
 * 把它降成 {@code ret > 0} 之后剩下的那一格<b>不可达</b>（键与吃它的样板同分量 ⇒ 分量内必有一张还在图里
 * 的样板还它，而图里每张发数都 > 0），连同恒 0 的读数 {@code keysNotRepaying} 一起删了，理由见
 * {@link #collectSeed} 抬头。
 * <b>留底取哪一截</b>也不在这里定：取料点上那两笔宽域复加（{@code owed}＝分量还要吃掉的枚数、
 * {@code handedBack}＝同一批发数还得回来的枚数）谁大决定——{@code handedBack >= owed} 那一档仍是整份
 * 剩余需求，{@code 0 < handedBack < owed} 只锁真实缺口（{@code ParallelPlanReservation
 * #buildRingSeedReserve}），所以本账本把分量成员对种键的<b>每发消耗</b>与<b>每发还回</b>一起交出去。
 *
 * <p><b>发数一律走宽域</b>：筛图与取料点那两笔复加读的都是
 * {@link #outstandingFirings(PlanAmount, PlanAmount)} 那份合成口径——任务表交
 * {@code ParallelExecutingCraftingJob.TaskProgress#exactRemaining()} 的<b>宽域真值</b>，义务账交
 * {@code BatchObligationLedger#pendingFirings}（申报那一刻 {@code consumeFirings} 已把 {@code k} 发
 * 从剩余里扣干，剩下那 {@code k-1} 发的材料仍由同一张样板从池子里取，筛图（在册与否）不许看不见它）。
 * 不是 {@code value} 那份饱和镜像：镜像在 {@code > long} 的订单上把留底那两笔复加同时顶到
 * {@code Long.MAX_VALUE}，那截"到底还差几枚"就读不出来了。
 *
 * <p>完整样板集合已证明无环时，其任意子图也无环，可复用空环种结论；新增样板会撤销证明。
 * 有环或尚未证明的图，本账本的<b>在册名单随发数每轮现算</b>（{@link #seedAmounts()}／{@link #seedDemandsByFamily()} 每次读
 * 都重派生一次，不按任务表条数缓存）：资格读的是筛图那一句（{@code outstandingFirings <= 0} 的样板整张
 * 出图，它那一枚键跟着出分量），缓存会让"5/4 这一档跑到 4/4 该入册"等成"有人出表才入册"，
 * 而取料点上的留底复加读的是本 tick 的发数 ⇒ 两侧不会在同一趟上对账。
 *
 * <p>未命中无环证明时，每趟<b>重算</b>的是这四格：谁还在册（筛图）、分量归属（SCC）、资格（这一枚键被分量里哪张还没跑完的
 * 样板吃掉）、每发量×发数的复加。该路径<b>跨读复用</b>的一格是每张样板<b>自己</b>那两半每发量表
 * （{@link #perFiringTablesByPattern}）：它们只由样板的 definition 与输入/输出槽决定、与发数无关，
 * 是这趟派生里唯一"发数变了它也不变"的那一截，于是每 tick 那两趟读点不再各算一遍槽位。
 *
 * <p>刻意不读规划期挂在计划实例上的任何字段：计划在提交链上
 * 会被第三方重写成裸 {@code CraftingPlan}（实机为 useless_mod 的
 * {@code SmartDoublingPlans.rewriteForSubmission}），壳上的契约与见证全丢，而"这几发物料会不会
 * 自己转回来"是任务表自己的拓扑，续传后同样重算得出来。
 */
public final class RingSeedLedger<P> {

    private final Map<IPatternDetails, P> remainingExecutionsByPattern;
    /** 剩余发数的<b>宽域真值</b>读口：筛图与取料点那两笔复加只在宽域上做，不吃饱和镜像。 */
    private final Function<P, PlanAmount> remainingExecutions;
    /**
     * 义务账上<b>还没交割的发数</b>读口（按样板，宽域真值）：申报那一刻 {@code consumeFirings} 已把这
     * {@code k} 发从剩余里扣掉，而剩下 {@code k-1} 发的材料仍要由同一张样板从池子里取走
     * （{@code ParallelBatchExtraction#payDeclaredObligation}）。缺省恒 {@code ZERO}。
     */
    private final Function<IPatternDetails, PlanAmount> pendingObligationFirings;

    /**
     * 每张样板<b>自己</b>那两半每发量表（消耗与还回）的 memo：本账唯一跨读复用的一格。
     *
     * <h3>为什么这一格可以 memo，而派生结果不行</h3>
     * 两半表只由 {@code getInputs()}/{@code getOutputs()}/{@code getRemainingKey()} 三份读数算出，
     * 与发数无关；{@link #rebuild} 里其余四格（筛图在册、SCC 分量归属、资格、每发量×发数的复加）
     * 每一格都吃发数，而发数在 tick 内也会变（申报那一刻就 {@code consumeFirings}），
     * 所以那四格照旧每次读现算——memo 掉整派生就是拿旧发数当现值用，正是 {@code 4dbeef7} 删掉的病。
     *
     * <h3>失效边界（为什么运行期不会读到陈旧的一发量）</h3>
     * 一张样板的一发消耗／还回由它的 definition 与槽位定，本仓与 AE2 的样板实现在运行期都不改这两样：
     * AE2 四条原生样板（{@code AECraftingPattern}/{@code AEProcessingPattern}/
     * {@code AESmithingTablePattern}/{@code AEStonecuttingPattern}）与本仓
     * {@code CustomProcessingPattern}/{@code MatterMassPatternDetails}
     * 都在构造期把槽算成 {@code final} 字段（物质团那一份是首次读时懒建、建完就定死，
     * 而首次读正是本账本第一趟 {@link #rebuild} 干的事）。<b>换规则就换实例</b>而不是改槽位：
     * 染色样板替换与 {@code ReplacementAwarePattern}（槽位重算）交出的都是重建的那一张新
     * {@code IPatternDetails}，任务表里的键跟着换 ⇒ 新键自己算一趟，旧键随它自己那份槽位记账。
     * 本 memo 又只是 {@code ParallelExecutingCraftingJob#ringSeeds} 这份作业级账本的字段，
     * 作业结束随它一起消失 ⇒ 不存在跨作业、跨存读的陈旧读数。
     *
     * <h3>只按 key 查，从不遍历</h3>
     * 同 {@code ParallelExecutingCraftingJob#rationPatternsInExecutionOrder} 里那张 {@code rankByPattern}
     * 的纪律：这张表的哈希不参与任何次序（本仓被 {@code System.identityHashCode} 的遍历序坑过多次，
     * 并列项次序跨会话翻、点名换人）。<b>不许</b>换成 {@link java.util.IdentityHashMap}，
     * 也不许在这里加任何"按遍历序取第一"的写法。
     * 出册的样板留一份读数在表里，上界是本作业任务表见过的样板数。
     */
    private final Map<IPatternDetails, PerFiringTables> perFiringTablesByPattern = new HashMap<>();

    /**
     * 一张样板的两半每发量表。两半随 memo 交出的是<b>同一份实例</b>，下游（{@link #rebuild} 与
     * {@link #collectSeed}）只读不写——不许在这里换成 {@code Map.copyOf}：那会换掉
     * {@code HashMap} 的遍历序，而 {@link #registerKeys} 编出的节点下标就吃这个序。
     */
    private record PerFiringTables(Map<AEKey, Long> consumes, Map<AEKey, Long> returns) {
    }

    /** 环种键 → 一发的起步量：只作排序输入，不作提取门禁。 */
    private KeyCounter seedAmounts = new KeyCounter();
    /**
     * 环种<b>族</b>（{@code AEKey#getPrimaryKey()}）→ 该族下各<b>精确种键</b>的分量口径。
     *
     * <p>与 {@link #seedAmounts()} 同一次派生、同一批键，一个都不多：只有被<b>分量里一张还没跑完的
     * 样板</b>吃掉的那一枚种键才有分量口径。取料点上的留底读它，不在那里第二次算分量，也不在那里
     * 第二次合族。
     */
    private Map<Object, SeedDemand> seedDemandsByFamily = Map.of();

    /**
     * 一颗<b>精确种键</b>在<b>它自己所在分量</b>里的口径——留底记账的最小那一份，同族谁也不并谁。
     *
     * @param seed              这一份口径记在哪一枚<b>精确</b>键上（含组件／变体，不是物品本体族）
     * @param members           分量里的样板集合：它们都是这条环上的一环，取料点上的留底不许扣它们
     *                          （链式环里 {@code B} 吃的是 L 不是 K，可它照样是 K 那一环的成员）
     * @param perFiringConsume  分量里每张样板<b>一发</b>吃掉 {@code seed} 那一枚几枚：留底按"这张还要
     *                          吃的发数（剩余发数 + 义务账上还没交割的发数，见 {@link #outstandingFirings}）×
     *                          这里那份"复加。本表只交每发量，发数归任务表与义务账那两把现成的尺
     * @param perFiringReturn   分量里每张样板<b>一发</b>还回 {@code seed} 那一枚几枚：与
     *                          {@code perFiringConsume} <b>同一把尺、同一趟发数、同一份记账规则</b>
     *                          （按精确键各自建档、同族谁也不并谁）。取料点按这两笔的大小决定留底
     *                          <b>取哪一截</b>——分量还得上一整份时取整份剩余需求，本来就净吃种时
     *                          只取真实缺口；两笔合账规则分过叉的那天，"还得上一整份"与"本来就净吃种"
     *                          两档就会在同一族上各说各话
     */
    public record SeedKeyDemand(AEKey seed,
            Set<IPatternDetails> members,
            Map<IPatternDetails, Long> perFiringConsume,
            Map<IPatternDetails, Long> perFiringReturn) {
    }

    /**
     * 一个<b>物品本体族</b>的口径：族下每个过资格的精确种键一份 {@link SeedKeyDemand}。
     *
     * <p>族只是取料点查表的分组（那一把尺按族算，与 {@code ParallelPlanReservation#shareOf} 同口径），
     * <b>不合并</b>族内各键的账：留底要的是"该族下各精确键 owed 之和"，写成取最大就少算一截。
     *
     * @param seedKeys 族下各精确种键各自的分量口径，至少一条
     */
    public record SeedDemand(List<SeedKeyDemand> seedKeys) {

        /**
         * 族下<b>各分量成员的并集</b>：纯读数（"这一族里哪些样板是环成员"），不参与任何判据——
         * 留底的豁免一律按 {@link #seedKeys()} 里<b>那一个分量</b>的成员名单走，
         * 并集会免扣掉旁人的分量（A 分量成员去抽干 B 那份口粮的来路）。
         */
        public Set<IPatternDetails> members() {
            Set<IPatternDetails> union = new LinkedHashSet<>();
            for (SeedKeyDemand seedKey : seedKeys) {
                union.addAll(seedKey.members());
            }
            return Set.copyOf(union);
        }
    }

    /** 派生计数器：只在 rebuild 里赋值，供停滞日志指认"为什么一个环种都没派生出来"。 */
    private int diagPatterns;
    private int diagKeys;
    private int diagComponents;
    private int diagMultiPatternComponents;
    private int diagMaxPatternComponent;
    private int diagSeeds;

    public RingSeedLedger(Map<IPatternDetails, P> remainingExecutionsByPattern,
            Function<P, PlanAmount> remainingExecutions) {
        this(remainingExecutionsByPattern, remainingExecutions, ignored -> PlanAmount.ZERO);
    }

    public RingSeedLedger(Map<IPatternDetails, P> remainingExecutionsByPattern,
            Function<P, PlanAmount> remainingExecutions,
            Function<IPatternDetails, PlanAmount> pendingObligationFirings) {
        this.remainingExecutionsByPattern = remainingExecutionsByPattern;
        this.remainingExecutions = remainingExecutions;
        this.pendingObligationFirings = pendingObligationFirings == null
                ? ignored -> PlanAmount.ZERO : pendingObligationFirings;
    }

    /**
     * "这一张还要从池子里吃掉几发"的<b>唯一算式</b>：任务表的剩余发数 + 义务账上还没交割的发数。
     *
     * <p>本账本的筛图那侧（{@link #rebuild}）读它，取料点上的留底复加
     * （{@code ParallelPlanReservation#buildRingSeedReserve}）也读它：两侧只在同一句上带义务，
     * 才不会长成"一边算欠账一边不算"的第二把尺。任一侧给 {@code null} 即当 0 发。
     */
    public static PlanAmount outstandingFirings(@Nullable PlanAmount remainingFirings,
            @Nullable PlanAmount pendingObligationFirings) {
        PlanAmount remaining = remainingFirings == null ? PlanAmount.ZERO : remainingFirings;
        PlanAmount owed = pendingObligationFirings == null ? PlanAmount.ZERO : pendingObligationFirings;
        return remaining.add(owed);
    }

    /**
     * 环种键与起步量：与 {@code RecursiveTaskOrdering} 的 internalItems 同一口径。
     *
     * <p>除完整图已证明无环的情形外，<b>每次读都现派生</b>（不再有"按任务表条数缓存"那一格）：在册名单读的是 {@link #rebuild}
     * 筛图那一句的发数（发数归零的整张出图），而发数每 tick 都在减，
     * 缓存会让册子滞后于取料点上那份按本 tick 发数复加的留底。
     * 现派生的是筛图/SCC/资格/复加那四格，每张样板的两半每发表从 {@link #perFiringTablesByPattern}
     * 领同一份（它与发数无关，见那份字段的抬头）。
     */
    public KeyCounter seedAmounts() {
        rebuild();
        return seedAmounts;
    }

    /**
     * 环种<b>族</b>（{@code AEKey#getPrimaryKey()}）→ 该族下<b>各精确种键</b>各自的分量口径。
     *
     * <p>键集合与 {@link #seedAmounts()} 完全一致：只有被<b>分量里一张还没跑完的样板</b>吃掉的那一枚
     * 种键才有分量口径，一个都不多留。族只是查表的分组：<b>族内各精确键各记一份，不并账</b>——
     * 同族两个键（两个变体各成一条环、或一张样板一发同时吃同族两个精确键）并成一份时，
     * 分量名单会被合成一份免扣名单、每发消耗会只留下两者里最大的那一枚，两样都是<b>少算</b>：
     * 旁人能从池子里多取走少掉的那一截，A 分量的成员还能免扣 B 分量那份口粮。
     * "同槽多候选是任选其一"那一档的取最大不在这里，在 {@link #perFiringConsumes} 的槽内合并。
     *
     * <p>除完整图已证明无环的情形外，<b>每次读都现派生</b>：册上"这一枚精确键在不在册"那一格读的是 {@link #rebuild} 的筛图——
     * {@code outstandingFirings <= 0} 的样板整张出图，它那一枚键跟着出分量（还它的那一张一发不欠时，
     * 这一枚键与吃它的那一张就不再同分量，见 {@link #collectSeed} 抬头）。发数每 tick 都在减而任务条数
     * 可以一动不动——按条数缓存会让 {@code 5/4} 跑到 {@code 4/4} 该入册的那一档等成"有人出表才入册"，
     * 与留底复加读的不是同一趟的数。
     */
    public Map<Object, SeedDemand> seedDemandsByFamily() {
        rebuild();
        return seedDemandsByFamily;
    }

    /** 已证明无环的完整样板集合；删任务或改变发数不会令其子图产生环。 */
    private Set<IPatternDetails> provenAcyclicPatterns = Set.of();

    private void rebuild() {
        rebuild(false);
    }

    private void rebuild(boolean diagnosticSnapshot) {
        if (!provenAcyclicPatterns.containsAll(remainingExecutionsByPattern.keySet())) {
            provenAcyclicPatterns = Set.of();
        }
        if (!diagnosticSnapshot && !provenAcyclicPatterns.isEmpty()) {
            seedAmounts = new KeyCounter();
            seedDemandsByFamily = Map.of();
            return;
        }
        // 三张表按节点下标对齐（patterns/consumes/returns）：发数只在下面这一句用，不再随行带一份。
        // 两半每发表从 memo 领（第一趟才真算），筛图与后面的 SCC／资格/复加照旧每趟现算
        List<IPatternDetails> patterns = new ArrayList<>();
        List<Map<AEKey, Long>> consumes = new ArrayList<>();
        List<Map<AEKey, Long>> returns = new ArrayList<>();
        for (var entry : remainingExecutionsByPattern.entrySet()) {
            IPatternDetails details = entry.getKey();
            P progress = entry.getValue();
            if (details == null || progress == null || details.getInputs() == null) {
                continue;
            }
            // 筛图口径是"还要从池子里吃几发"= 剩余发数 + 还没交割的义务发数：
            // 申报那一刻 consumeFirings 已把这 k 发扣干，剩下 k-1 发的材料仍由这张样板从池里取，
            // 把它整张筛出 SCC 图外就两头漏（旁人 owed 夹不够、它自己失去豁免付不了欠账）。
            PlanAmount exactRemaining = remainingExecutions.apply(progress);
            if (exactRemaining == null) {
                continue;
            }
            PlanAmount remaining = outstandingFirings(
                    exactRemaining, pendingObligationFirings.apply(details));
            if (remaining.signum() <= 0L) {
                continue;
            }
            patterns.add(details);
            var tables = perFiringTables(details);
            consumes.add(tables.consumes());
            returns.add(tables.returns());
        }

        var nextSeedAmounts = new KeyCounter();
        // 分量口径按<b>精确种键</b>建档（不是按族）：同族两个键并成一份就少算一截，见类抬头
        var nextKeyMembers = new LinkedHashMap<AEKey, Set<IPatternDetails>>();
        var nextKeyConsume = new LinkedHashMap<AEKey, Map<IPatternDetails, Long>>();
        // 还回那一笔与消耗那一笔同一趟派生、同一份按精确键建档的规则（取料点按这两笔定留底哪一截）
        var nextKeyReturn = new LinkedHashMap<AEKey, Map<IPatternDetails, Long>>();
        int patternCount = patterns.size();
        Map<AEKey, Integer> keyNodes = new HashMap<>();
        for (int p = 0; p < patternCount; p++) {
            registerKeys(keyNodes, patternCount, consumes.get(p));
            registerKeys(keyNodes, patternCount, returns.get(p));
        }
        diagPatterns = patternCount;
        diagKeys = keyNodes.size();
        diagComponents = 0;
        diagMultiPatternComponents = 0;
        diagMaxPatternComponent = 0;
        if (!keyNodes.isEmpty()) {
            List<LinkedHashSet<Integer>> successors = new ArrayList<>(patternCount + keyNodes.size());
            for (int node = 0; node < patternCount + keyNodes.size(); node++) {
                successors.add(new LinkedHashSet<>());
            }
            for (int p = 0; p < patternCount; p++) {
                for (AEKey key : returns.get(p).keySet()) {
                    successors.get(p).add(keyNodes.get(key));
                }
                for (AEKey key : consumes.get(p).keySet()) {
                    successors.get(keyNodes.get(key)).add(p);
                }
            }
            List<List<Integer>> components =
                    DependencyGraphs.stronglyConnectedComponents(successors, successors.size());
            diagComponents = components.size();
            int[] componentOf = new int[patternCount + keyNodes.size()];
            for (int component = 0; component < components.size(); component++) {
                for (int node : components.get(component)) {
                    componentOf[node] = component;
                }
            }
            for (List<Integer> component : components) {
                int patternsInComponent = 0;
                for (int node : component) {
                    if (node < patternCount) {
                        patternsInComponent++;
                    }
                }
                if (patternsInComponent >= 2) {
                    diagMultiPatternComponents++;
                }
                diagMaxPatternComponent = Math.max(diagMaxPatternComponent, patternsInComponent);
                collectSeed(component, componentOf, consumes, returns,
                        keyNodes, patternCount, nextSeedAmounts,
                        patterns, nextKeyMembers, nextKeyConsume, nextKeyReturn);
            }
        }
        // 只有全体在册任务均进入本次证明、且每个 SCC 都是单节点时才能复用。
        // 不能把“当前活跃子图无环”当成完整任务集无环：零发任务随后可能恢复或挂上义务。
        if (patternCount == remainingExecutionsByPattern.size()
                && (keyNodes.isEmpty() || diagComponents == patternCount + keyNodes.size())) {
            provenAcyclicPatterns = Set.copyOf(patterns);
        }
        this.seedAmounts = nextSeedAmounts;
        this.diagSeeds = nextSeedAmounts.size();
        // 精确种键 → 物品本体族：分组只在这一处做（留底那把尺按族查表），族内各键各记一份、不并账
        var nextDemands = new LinkedHashMap<Object, List<SeedKeyDemand>>();
        for (var entry : nextKeyMembers.entrySet()) {
            AEKey seed = entry.getKey();
            nextDemands.computeIfAbsent(seed.getPrimaryKey(), ignored -> new ArrayList<>())
                    .add(new SeedKeyDemand(seed, Set.copyOf(entry.getValue()),
                            Map.copyOf(nextKeyConsume.get(seed)),
                            Map.copyOf(nextKeyReturn.get(seed))));
        }
        var nextDemandsByFamily = new LinkedHashMap<Object, SeedDemand>();
        for (var entry : nextDemands.entrySet()) {
            nextDemandsByFamily.put(entry.getKey(), new SeedDemand(List.copyOf(entry.getValue())));
        }
        this.seedDemandsByFamily = Map.copyOf(nextDemandsByFamily);
    }

    /** 一行读出本账本的派生过程：图规模、成环情况与最终环种键数。 */
    public String derivationReport() {
        // 诊断必须展示本次任务与发数筛图的真实规模，不展示被复用证明的旧计数。
        rebuild(true);
        var described = new StringBuilder(" ringDiag=[patterns=").append(diagPatterns)
                .append(", keys=").append(diagKeys)
                .append(", scc=").append(diagComponents)
                .append(", scc2plus=").append(diagMultiPatternComponents)
                .append(", maxSccPatterns=").append(diagMaxPatternComponent)
                .append(", seeds=").append(diagSeeds).append(']');
        return described.toString();
    }

    /**
     * 分量内逐<b>精确键</b>入账：这一枚键被<b>分量里一张还没跑完的样板</b>吃掉就算环种，
     * 起步量记的是分量里最大的一发消耗量；过了资格的那一枚随它自己的分量名单、每发消耗、每发还回
     * 一起交给 {@link #recordDemand}（同族两个键就是两份，不并）。
     *
     * <p><b>账本里不再有任何"分量还不还得回来"的数值对账</b>（这一刀删掉的）：从前那一格是
     * {@code ret >= need}，它把"分量内还不完全回来"的键整键撤账——链式环 A 吃 K 出 L 欠 5 发、
     * B 吃 L 出 K 欠 4 发那一型本来就净吃那一枚种（缺口由规划期起步提取补），撤账 ⇒ 取料点不产留底
     * ⇒ 池里那枚 K 在环转起来之前被旁人一发抽干（用户 2026-09-29 定版"环优先跑，跑完环再放行"）。
     * 资格降到 {@code ret > 0} 之后那一格<b>不可达</b>，按 SCC 的定义：候选键与吃它的样板同在一个
     * 分量 ⇒ 分量内必有一张<b>还在图里</b>的样板把这一枚键还回来（图上指向键节点的边只有"还回"那一条），
     * 而 {@link #rebuild} 的筛图已经把"剩余 + 未交割义务 ≤ 0 发"的样板整张筛出图外 ⇒ 那张还它的样板
     * 发数恒 > 0 ⇒ {@code ret > 0} 恒成立，随它的读数 {@code keysNotRepaying} 恒 0。
     * 实证：把那一支换成抛异常跑全量 715 条，一条也没撞上去。
     *
     * <p><b>这里不判"留底取哪一截"</b>：{@code ret >= need} 与 {@code 0 < ret < need} 两档的留底金额
     * 由取料点按<b>同一趟</b>的两笔宽域复加现算（{@code ParallelPlanReservation#buildRingSeedReserve}
     * 里的 {@code owed} 与 {@code handedBack}，两笔乘的都是"每发量 × 还要吃的发数
     * （剩余 + 未交割义务，{@link #outstandingFirings}）"，全程宽域
     * （{@link WideMath#mul(long, PlanAmount)} + {@link PlanAmount#add}）：这一档一旦留 {@code long}
     * 饱和，两侧就同时撞在 {@code Long.MAX_VALUE} 上，那截"到底还差几枚"就读不出来了（见类抬头）。
     * 每发量本身留在 {@code long} 域，但<b>是真夹顶的 {@code long}</b>（{@link #addSaturating}：
     * 单项与累加都饱和）——这一格只要允许回绕成负数，取料点那侧的 {@code > 0L} 判据就会把整张样板
     * 从 {@code owed} 里抹掉，见 {@link #perFiringConsumes} 抬头。
     *
     * <p>资格本身仍是<b>发数敏感</b>的（读的是 {@link #rebuild} 筛图那一句），所以册子每次读都现派生
     * （见 {@link #seedDemandsByFamily()}）：两侧读的必须是同一趟的发数。
     */
    private void collectSeed(
            List<Integer> component,
            int[] componentOf,
            List<Map<AEKey, Long>> consumes,
            List<Map<AEKey, Long>> returns,
            Map<AEKey, Integer> keyNodes,
            int patternCount,
            KeyCounter nextSeedAmounts,
            List<IPatternDetails> patterns,
            Map<AEKey, Set<IPatternDetails>> demandMembers,
            Map<AEKey, Map<IPatternDetails, Long>> demandConsume,
            Map<AEKey, Map<IPatternDetails, Long>> demandReturn
    ) {
        // 候选键就是"分量里哪几枚键被在册样板吃掉"：键集合与那份每发最大消耗同一次收。
        // 资格不再问分量还得不得回来（方法抬头那一句：那一支不可达，连同恒 0 的读数一起删了）
        Map<AEKey, Long> perFiring = new HashMap<>();
        for (int node : component) {
            if (node >= patternCount) {
                continue;
            }
            for (var entry : consumes.get(node).entrySet()) {
                if (componentOf[keyNodes.get(entry.getKey())] != componentOf[node]) {
                    continue;
                }
                perFiring.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        for (var entry : perFiring.entrySet()) {
            AEKey key = entry.getKey();
            long seed = entry.getValue();
            if (nextSeedAmounts.get(key) < seed) {
                nextSeedAmounts.set(key, seed);
            }
            recordDemand(key, component, patterns, consumes, returns, patternCount,
                    demandMembers, demandConsume, demandReturn);
        }
    }

    /**
     * 把"这一枚精确种键归哪个分量、分量里哪几张、它们一发各吃掉<b>这一枚</b>几枚、一发又各还回
     * <b>这一枚</b>几枚"随种键一起交出去。
     *
     * <p>分量成员是<b>整条环</b>，不是"会还这一枚键的那几张"：链式环里 B 吃 L 出 K，
     * 它对 K 的每发消耗是 0、照样算 K 那一环的成员——留底的豁免名单缺了它，
     * 就等于把 B 当成旁人夹在自己的口粮上（A 等 B 的 K、B 等 A 的 L 互等）。
     *
     * <p><b>建档的键是 {@code seed} 那一枚精确键，不是它的族</b>。按族建档时两个形状都会少算：
     * ①同族两个精确键各自过资格（一张样板一发同时吃同族两个变体，或两个变体各成一条环）时，
     * 族格上只留下两者里最大的那份每发消耗，另两枚从此不计 ⇒ 旁人能从池里多取走那一截；
     * ②两个不同分量的成员被并成同一份名单 ⇒ A 分量的成员免扣 B 分量那份口粮，B 能被 A 抽干。
     * 每一枚精确键只属于一个分量（{@code keyNodes} 与 {@code componentOf} 都是按精确键建的节点），
     * 所以这里每张样板对同一枚种键只写一次；"同槽多候选是任选其一"那一档的取最大在
     * {@link #perFiringConsumes} 的槽内合并里已经做完，跨键再取最大就是把两枚真消耗并成一枚。
     *
     * <p>每发还回那一笔与每发消耗<b>同一把尺、同一份记账规则</b>（按精确种键各自建档、成员是整条分量、
     * 槽内合并在 {@code perFiring*} 那两把自己做完）：取料点按这两笔的大小决定留底<b>取哪一截</b>，
     * 两笔的口径分过叉的那天，"还得上一整份"与"本来就净吃种"两档就会在同一族上各说各话。
     */
    private static void recordDemand(
            AEKey seed,
            List<Integer> component,
            List<IPatternDetails> patterns,
            List<Map<AEKey, Long>> consumes,
            List<Map<AEKey, Long>> returns,
            int patternCount,
            Map<AEKey, Set<IPatternDetails>> demandMembers,
            Map<AEKey, Map<IPatternDetails, Long>> demandConsume,
            Map<AEKey, Map<IPatternDetails, Long>> demandReturn
    ) {
        Set<IPatternDetails> members =
                demandMembers.computeIfAbsent(seed, ignored -> new LinkedHashSet<>());
        Map<IPatternDetails, Long> perFiringConsume =
                demandConsume.computeIfAbsent(seed, ignored -> new HashMap<>());
        Map<IPatternDetails, Long> perFiringReturn =
                demandReturn.computeIfAbsent(seed, ignored -> new HashMap<>());
        for (int node : component) {
            if (node >= patternCount) {
                continue;
            }
            IPatternDetails pattern = patterns.get(node);
            members.add(pattern);
            Long consumed = consumes.get(node).get(seed);
            if (consumed != null && consumed > 0L) {
                // 同一个 equals 类在任务表里可以有两个身份（见 rationPatternsInExecutionOrder 抬头），
                // 于是同一枚种键能对同一张样板复加两笔——这一笔相加照旧夹顶：
                // 两半表各自夹过顶之后，这里 Long::sum 还能把 MAX 加回负数，读数反号那一型就白挡了
                perFiringConsume.merge(pattern, consumed, RingSeedLedger::addSaturating);
            }
            Long returned = returns.get(node).get(seed);
            if (returned != null && returned > 0L) {
                perFiringReturn.merge(pattern, returned, RingSeedLedger::addSaturating);
            }
        }
    }

    private static void registerKeys(Map<AEKey, Integer> keyNodes, int patternCount, Map<AEKey, Long> amounts) {
        for (AEKey key : amounts.keySet()) {
            if (!keyNodes.containsKey(key)) {
                keyNodes.put(key, patternCount + keyNodes.size());
            }
        }
    }

    /**
     * 取这一张的两半每发表：本作业第一趟真算，之后每一趟从 memo 领同一份。
     *
     * <p>只按 key 查这一句（见 {@link #perFiringTablesByPattern} 抬头），不遍历那张表。
     */
    private PerFiringTables perFiringTables(IPatternDetails details) {
        var cached = perFiringTablesByPattern.get(details);
        if (cached != null) {
            return cached;
        }
        var computed = new PerFiringTables(perFiringConsumes(details), perFiringReturns(details));
        perFiringTablesByPattern.put(details, computed);
        return computed;
    }

    /**
     * 一发吃掉的量：同槽多候选是"任选其一"，按该键在槽内的最大模板量乘槽倍数。
     *
     * <p><b>这一句就是全仓唯一的"一发吃掉该键几枚"</b>：本账本的分量口径与
     * {@code RecursiveTaskOrdering#inputAmount}（单发让路那一侧的同一问句）都读它，
     * 两份同形状实现只在饱和点上分过叉——这里逐槽饱和、那里逐槽宽域相加后再窄化一次，
     * 对非负模板量与非负槽倍数（AE2 样板的全部实际取值）两者恒等：
     * 每一项都非负时"逐项夹顶再夹顶相加"与"宽域相加后夹顶"都是 {@code min(真值, long 域顶点)}。
     * 留一份实现的那天，就是两侧对不同尺的那天。
     *
     * <p><b>逐槽那一步的相加是真夹顶</b>（{@link #addSaturating}，不是 {@code Long::sum}）：
     * 上面那一句恒等式只在"相加也夹顶"时成立，而读数变负不是"难看"而是<b>判据反号</b>——
     * 取料点上的留底复加按 {@code perFiringConsume > 0L} 决定这一张进不进 {@code owed}
     * （{@code ParallelPlanReservation#buildRingSeedReserve}），一笔回绕成负数的消耗会把这一张整张
     * 从 {@code owed} 里抹掉；{@code owed} 就此少算，少到 0 时这一枚精确键整个不出账 ⇒ 留底消失 ⇒
     * 池里那颗种被旁人一发抽干，正是本账本要挡的那一型。夹顶走的是反方向：{@code owed} 顶到宽域上，
     * 留底只会<b>多</b>夹，多出来的那一截由取料点上的夹池子那一句收住。
     */
    static Map<AEKey, Long> perFiringConsumes(IPatternDetails details) {
        Map<AEKey, Long> consumed = new HashMap<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            if (input == null || input.getMultiplier() <= 0L || input.getPossibleInputs() == null) {
                continue;
            }
            Map<AEKey, Long> slotUnits = null;
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate == null || candidate.what() == null || candidate.amount() <= 0L) {
                    continue;
                }
                if (slotUnits == null) {
                    slotUnits = new HashMap<>();
                }
                slotUnits.merge(candidate.what(), candidate.amount(), Math::max);
            }
            if (slotUnits == null) {
                continue;
            }
            for (var entry : slotUnits.entrySet()) {
                consumed.merge(entry.getKey(),
                        multiplySaturating(input.getMultiplier(), entry.getValue()),
                        RingSeedLedger::addSaturating);
            }
        }
        return consumed;
    }

    /**
     * 一发还回来的量：产出清单加上容器回炉（每个模板还一只容器）。
     *
     * <p>与 {@link #perFiringConsumes} <b>同一份夹顶规则</b>（逐槽相加走 {@link #addSaturating}）：
     * 这两笔在取料点上要比大小决定留底取哪一截，只把消耗那一半夹顶、让还回那一半会回绕，
     * 两档判据就会在同一枚键上各说各话（见 {@link SeedKeyDemand} 的 {@code perFiringReturn} 那一格）。
     */
    private static Map<AEKey, Long> perFiringReturns(IPatternDetails details) {
        Map<AEKey, Long> returned = new HashMap<>();
        List<GenericStack> outputs = details.getOutputs();
        if (outputs != null) {
            for (GenericStack output : outputs) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    returned.merge(output.what(), output.amount(), RingSeedLedger::addSaturating);
                }
            }
        }
        if (details.getInputs() == null) {
            return returned;
        }
        for (IPatternDetails.IInput input : details.getInputs()) {
            if (input == null || input.getMultiplier() <= 0L || input.getPossibleInputs() == null) {
                continue;
            }
            Map<AEKey, Long> slotContainers = null;
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate == null || candidate.what() == null) {
                    continue;
                }
                AEKey container = input.getRemainingKey(candidate.what());
                if (container == null) {
                    continue;
                }
                if (slotContainers == null) {
                    slotContainers = new HashMap<>();
                }
                slotContainers.merge(container, input.getMultiplier(), Math::max);
            }
            if (slotContainers != null) {
                for (var entry : slotContainers.entrySet()) {
                    returned.merge(entry.getKey(), entry.getValue(), RingSeedLedger::addSaturating);
                }
            }
        }
        return returned;
    }

    private static long multiplySaturating(long left, long right) {
        return left > Long.MAX_VALUE / Math.max(1L, right) ? Long.MAX_VALUE : left * right;
    }

    /**
     * 非负项的夹顶相加：与 {@link #multiplySaturating} 配成一对，读数只会到顶、不会翻负。
     *
     * <p>本账本两半每发表里的每一项都来自"正的槽倍数 × 正的模板量"或"正的产出量"（调用点各自把
     * {@code <= 0} 的槽与栈筛掉了），所以只需向 {@code Long.MAX_VALUE} 一侧夹顶。
     */
    private static long addSaturating(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
