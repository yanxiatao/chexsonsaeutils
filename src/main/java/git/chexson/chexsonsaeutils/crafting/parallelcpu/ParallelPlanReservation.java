package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.ClampedMath;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.RingSeedLedger;
import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * 执行侧取料的归属账本：模糊槽吃哪个键几份由规划定版，不在执行期按现场存量重挑。
 *
 * <p><b>主判据是提交期配给，且配额是硬上限</b>：那趟共用水池排水已经逐格写下"这一格
 * 哪个键几份"（{@link ParallelExecutingCraftingJob#materialAllocation}），账本按实际提走量销账，
 * 每张样板在一键上只剩自己那份配额可取，<b>份额之外一概不放</b>。模糊槽允许规划混取，
 * 但混取几份是规划定死的数量——定了 [128 橡木, 128 金合欢] 就只能消耗这些，
 * 不会因为现场有 256 枚橡木就改吃 256 枚橡木。份额取完接着吃首位键，正是尾段单键消费者
 * {@code stored=2} 的来路（实机 {@code charged_certus} 那格：规划把 526 份拆给未充能水晶，
 * 执行侧一路吃充能）。
 *
 * <p>唯一的例外是<b>原样退回的模具槽</b>：它整批只占一发起步量、每发从池子里过一遍，
 * 按份数销账会立刻归零，所以配给表里根本没有它，判据退到结构侧放行。
 *
 * <p><b>没有配给可照单执行时（续传作业）</b>才退到结构判据，按同一份顺位口径重算两笔量：
 * 无退路槽要吃的"占料"，与全部槽按第一候选计费的"认领"。只有 {@code 还没派发的产出 −}
 * 这笔认领真正剩下的量，才可以放给可退料的槽吃；整笔产出当余量放走，等于把同一份料许诺两次。
 * （实机形态：{@code ae2:quartz_glass} 计划总量与总需求只差 6，提交期从网络取的 20,936 份
 * 被可退料的槽整批 ecoPush 出去，尾段 {@code short=20926 stored=0} 而 {@code soloReady=0/15}。）
 *
 * <p>占锁的只有<b>候选表里只有一条路</b>的槽：这一格除首位外没有别的键，它退不出去，
 * 那份现场料就是它的。判据刻意<b>不</b>问"别的键现在有没有量"——问过就会随供给漂移：
 * 那格 6,387,262,868 的家族槽（{@code variants=3}）在同族量从账上消失的下一刻就成了锁主，
 * 封顶当场对它失效，正好把尾段单键消费者仅剩的几百份预留吃掉（实机 {@code exclusive=456 stored=2}）。
 * 反过来，把真有退路的槽算进占料也会出事：计划在该键上的毛账缺口变成一笔永远兑不了的锁，
 * 谁也取不到料，任务表整体起步不了（{@code blocked=[soloReady=0/108]}）。
 *
 * <p><b>不要改成"每 tick 逐槽排水按配给计费"</b>：试过，23:xx 那次实机把计划中段饿死——
 * 池子里堆着 637.9 亿 {@code ae2:silicon} 与 331.2 亿 {@code ae2:quartz_glass}，
 * 排水让只有一条路的槽先把整池认领光，多候选槽配给归零，于是
 * {@code noCand=10348 resClamp=3791 pushed=0}，剩余 689 亿发不前进；同一张表按本账本
 * 能跑到只剩 2,368 发。配给是<b>整张表一次性</b>的归属判定，不能当每 tick 的口粮分配。
 *
 * <h3>两本配给账是宽域账，饱和只发生在一处</h3>
 * {@code planMaterialPlan}（配给）与 {@code planMaterialEaten}（已吃）都是逐笔复加的账：
 * 前者按槽加、后者按批加，单笔 {@code ≤ long} 而合计没有上界。老口径两本都<b>饱和合并</b>，
 * 于是"配给顶到 {@code Long.MAX_VALUE} − 已吃也顶到 {@code Long.MAX_VALUE}"会算出
 * <b>{@code 0} 份额</b>，还能推的批次被这道硬封顶判成配额用尽（尾段 {@code pushed=0} 的来路之一）。
 * 现在两本账走 {@link WideKeyCounter}、族合计走 {@link PlanAmount}，<b>内部精确</b>；
 * 判据要 {@code long}，所以只在 {@link #remainingQuota} 一处按"宁高估"饱和
 * （{@link Ae2Amounts#toLong}）——与产出缓冲、三条流水账同一口径。
 *
 * <h3>配给是"每人能花多少"，环种留底是"这一截还不许任何人花"</h3>
 * 配给那条早退（{@link #usableFor} 的 {@code quota >= 0} 分支）只封顶份额，从不问"这一族里有没有
 * 一截是环自己还没跑完那一份的口粮"。规划侧的 {@code kept} 只把种留在纸面与池子里，执行侧没人扣它：
 * 一张有配给的旁人样板能在环转起来之前一次把池里两颗种全抽走（实机 {@code alloc=[…template
 * taken=3 ann=2 ret=2 stored=0]} 与 {@code backoffBlind=[…每发=1,池内=0,网络sim=0]} 那一对账）。
 * 所以这本账在 {@code build} 里按作业现算的环种账记一本 {@link #ringSeedReserves}，取料点先把池子现量
 * 削成可花量，<b>配给分支与占料分支读同一份</b>；基准是"<b>该种键所在环分量</b>自己还没跑完的那一截"
 * ——分量归属、每发消耗与每发还回只在 {@link RingSeedLedger} 那一处算，留底这边只做"剩余发数 ×
 * 每发量"的两笔复加，豁免名单就是那个分量的成员。记账按<b>精确种键</b>一份份记、查表按<b>物品本体族</b>
 * （与 {@link #shareOf} 同一族口径），族条目把名下各精确键的留底<b>复加</b>：一条环里同族两个键各欠一发时，
 * 并成一族取最大就少算第二枚——那一枚正是旁人从池里多抽走的。同一枚键上的两笔复加还决定留底
 * <b>取哪一截</b>：分量还得上一整份（{@code handedBack >= owed}）时锁整份剩余需求，本来就净吃种
 * （{@code 0 < handedBack < owed}）时只锁真实缺口——那一档从前被"产出不少于消耗才算种"整键撤账，
 * 池里那一枚在环转起来之前就被旁人一发抽干。
 * 这一笔复加<b>全程在宽域上做</b>：它与上面那两本 {@code long} 域<b>锁</b>账（占料锁、首位认领）不同，
 * 判据只问"这份留底大得过池子现量吗"，从不把宽值物化成 {@code long} 读数，所以 {@code > long}
 * 的订单照样守种——那一档正是并行 CPU 要能记、能跑、能如实报数的档，没有"越界就整族不进账"可言。
 * 产出那本<b>贷方账</b>（量）与此同一口径：发数读 {@code exactRemaining()} 的宽域真值，
 * 越界那一档照样整笔进账（台账 #217），"锁不锁"才留给 {@code long} 域那两本账与那一道窄化出口。
 */
final class ParallelPlanReservation {

    /** 贷方账的窄化判据：顶到这道天花板的读数在 {@code long} 域锁算式里与"越界"同形（哨兵不是量）。 */
    private static final PlanAmount LONG_MAX_AMOUNT = PlanAmount.of(Long.MAX_VALUE);

    /** 只有一条路的槽按首位键合计要多少：它们的现场料不能被改道吃掉。 */
    private final Map<AEKey, Long> firstCandidateNeed;
    /** 全部槽（含可退料的）按第一候选计费的认领量：未派发产出要先抵它，剩下的才放给可退料的槽吃。 */
    private final Map<AEKey, Long> firstCandidateDemand;
    /**
     * 产出这本<b>贷方账</b>——"这一键计划还会产几枚"，<b>量</b>的唯一出口，按精确键合计、
     * 全程 {@link PlanAmount} 宽域真值。
     *
     * <p><b>它只答"有多少"，不答"锁不锁"</b>：下不下占料悲观锁另有出口（
     * {@link #unrepresentableProduction} ＋ {@link #productionHasNoTrustworthyReading}），
     * 两件事分家（台账 #217 第 2 条）。老口径把两件事写成<b>一次撤销</b>——
     * {@link #dropAsUntrustworthy} 既删条目又记名单，于是"这一笔装不进 {@code long}"顺带把该键的
     * 计划产出整笔抹掉：{@link #reservedForExclusiveConsumers} 的 {@code releaseable} 读成 {@code 0}
     * ⇒ {@code reserved} 等于整份锁 ⇒ 这一键对每个消费者扣到底 ⇒ 下游把这一格读成"没人会产"
     * （{@code left} 是宽域真值而 {@code toMake=0} 同时成立那一型）。名单现在只撤锁，一笔量都不撤。
     *
     * <p>发数读 {@link ParallelExecutingCraftingJob.TaskProgress#exactRemaining()} 的宽域真值，
     * <b>不</b>问"装不装得进 {@code long}"：剩余 {@code 1e19} 发、每发 1 枚的那一截照样入账——
     * "能不能塞进 long"从来不是这一笔产出<b>存不存在</b>的判据（同族定版 #106／#100／#52／#98
     * 同一条法：内部精确宽域，饱和只发生在 AE2 边界出口）。
     *
     * <p>这本账仍不是 {@code planEmit} 那本快照的镜像，但<b>理由换了</b>（任务 #219 条目⑥改了口）：
     * {@link ParallelExecutingCraftingJob#accountPlannedEmit} 现在是 {@code WideKeyCounter}——
     * 按<b>精确键</b>分格（同族变体各记一格）、写入侧交宽域真值，越界那一格不再恒读
     * {@code Long.MAX_VALUE} 那道哨兵，所以它<b>当得起量</b>了；两本账的分工照旧是"这本按消费者
     * 与引用净算贷方、那本是提交瞬间全表的产量快照"，不是同一本的两次登记。
     * 本刀不碰这里的算式，动的只有这段话术。
     */
    private final Map<AEKey, PlanAmount> pendingProduction;
    /** 池子现量读数，只用于 {@link #canAvoidThisKey} 判退路。 */
    private final Map<AEKey, Long> pool;
    /**
     * <b>锁</b>的唯一出口：产出这本贷方账<b>窄化到 {@code long} 就读成天花板哨兵</b>的那几个键——
     * 只对它们说"这一键不下占料悲观锁"，<b>不</b>说"这一键没有产出"。
     *
     * <p>它和两本<b>负债</b>账（{@code firstCandidateNeed}、{@code firstCandidateDemand}）的撤账名单
     * 同<b>方向</b>（都是放松锁）、不同<b>动作</b>：负债账撤条目就是放松那道锁（条目本身即锁），
     * 产出账的条目是<b>贷方量</b>，撤了就等于把"会产多少"抹成 0——那正是本刀分家掉的那一次撤销。
     * 所以这里 {@code add} 而不 {@code remove}：名单进、账不动。
     *
     * <p>读它的是两处 {@code long} 域算式，两处必须同判：{@link #usableFor} 的
     * {@code !productionUntrusted} 那一臂（名单里的键交回池子现量，与 {@link #representableFirings}
     * 交回 0 即整条累加跳过、{@code units == Long.MAX_VALUE} 即 {@code continue} 同向——都是
     * <b>少一道悲观锁</b>，都不是少一笔算量），以及 {@link #pendingProductionForLock} 那一口窄化读数
     * （{@code canAvoidThisKey} 与 {@code reservedForExclusiveConsumers} 共用它）。
     *
     * <p>{@link #canAvoidThisKey} 也不把名单里的键算成退路供给：多算一份供给会把"可退料"判成成立，
     * 反而给来伸手的槽<b>加上</b>一道锁，与"没有可信口径就少一道悲观锁"反向。
     */
    private final Set<AEKey> unrepresentableProduction;
    /** 与作业共享的实时材料账；成功消费无需重建 reservation 即可减少剩余配额。 */
    private final MaterialAllocation materialAllocation;
    /**
     * 规划侧认下的<b>无限源</b>键的<b>物品本体族</b>：岩浆的同族变体不能只放过精确键。
     *
     * <p>族内任何键既不受配额封顶、也不被占料锁预留（{@link #usableFor} 一条早退、
     * {@link #remainingQuota} 交回"没有配额口径"）——它们表达的是"没有数值上界"，
     * 在 {@code long} 域里夹出来的任何数都是假数。
     */
    private final Set<Object> unboundedFamilies;
    /**
     * 环种留底：环<b>分量自己还没跑完</b>的那一截种子，按物品本体族<b>查表</b>、按精确种键<b>记账</b>。
     *
     * <p>规划侧的 {@code kept}（{@code RetentionApplier}）只在纸面把种留住，执行侧从前没有一格
     * 扣过它：配给那条早退（{@link #usableFor}）只说"每人能花多少"，没说"环自己还没跑的那一份
     * 不许别人花"。实机 {@code ae2:creative_storage_cell} 那一跑（lane 48739137）就是这么死的——
     * 锻台样板在种键上有配给 ⇒ 走 {@code min(池内=2, 配给=479992)=2} 那一条早退，两枚种一次抽走，
     * 池 1 + 产 2 − 吃 3 = 0，增殖腿从此永久饿死（{@code 每发=1,池内=0,网络sim=0}）。
     *
     * <p>这一本不是第四道闸门，是<b>同一把尺上的一个扣减</b>：池子现量先削成可花量，
     * 配给分支与占料分支读的都是<b>同一份</b> {@code spendable}，不留"配给那条早退绕过留底"的口子。
     * 同一句读口（{@link #spendableAfterRingSeedReserve}）也打在<b>折发数</b>与<b>网络回补</b>那两条
     * 不经过 {@link #usableFor} 的腿上：那两条按发数申报、按缺口搬料，池子那把尺对它们不存在，
     * 于是种在环转起来之前就被别的样板从这两条腿吃完（实机 {@code 1e11} 那一跑）。
     *
     * <p>族集合只取作业现算的环种账（{@code ParallelExecutingCraftingJob#ringSeeds}）里那几个种键的族，
     * 不是全部键；"哪一张属于这个环"与"它一发吃掉<b>哪一枚</b>几枚"一律读
     * {@link RingSeedLedger#seedDemandsByFamily()}——那份 SCC 与"键属于哪个族"的对应都只在那里算一次，
     * 本类不另写一份分量语义，也不在夹子这边第二次分组（链式环里吃中转那枚的 B 与吃种的 A 同属一个分量，
     * 都不许被这一格扣）。
     * <b>族是查表口径、精确种键是记账口径</b>：伸手拿同族任一变体都要先扣这一族的留底，
     * 而这一族的留底是族下<b>各精确键 owed 之和</b>（并成一族取最大就少算另一枚那一份）；
     * 豁免名单按<b>分量</b>交（并成一份族名单时 A 分量的成员免扣 B 分量那份口粮 ⇒ B 被 A 抽干）。
     * 复加用的发数是"这张还要从池子里吃几发"= 任务表剩余发数 + 义务账还没交割的发数
     * （{@link RingSeedLedger#outstandingFirings} 那一句）：只看剩余会把申报后只剩欠账的那一张整张
     * 漏掉——它既不在 {@code owed}（旁人夹得不够），也不在豁免名单（它自己付欠账时被同族留底夹死）。
     *
     * <p>留底的<b>那一截</b>按同一枚精确键上的两笔复加取：分量还得上一整份（{@code handedBack >= owed}）
     * 就是<b>整份剩余需求</b> {@code owed}；分量本来就净吃种（{@code 0 < handedBack < owed}，链式环
     * A 欠 5 发吃 K、B 欠 4 发还 K 那一型）就是<b>真实缺口</b> {@code owed - handedBack}——旧口径在
     * 这一档整键撤账 ⇒ 不产留底 ⇒ 池里那一枚在环转起来之前被旁人一发抽干。两档都只锁"环自己还要
     * 花掉的那一截"，环成员在豁免名单里取得走，跑到 {@code owed} 归零时复加成 0、自动放行旁人。
     * 为此账本随每发消耗一起交出<b>每发还回几枚</b>（{@code RingSeedLedger.SeedKeyDemand#perFiringReturn}），
     * 并且<b>每次读都现派生</b>（旧的按任务表条数缓存那一格已删）：在册名单读的是筛图那一句发数，
     * 与本方法这两笔复加同一趟。
     *
     * <p><b>复加全程在宽域上做，没有"越界就整族不留底"那一档</b>：{@code > long} 的订单正是这一本
     * 要守住的那一档——发数装不进 {@code long} 时留底消失，等于量级一大就不守种，
     * 与 {@code 9b76b76} 修掉的病原样同形。留底<b>从不把宽值物化成 {@code long}</b>：
     * 判据只问 {@code owed > stored} 一件事（见 {@link RingSeedReserve#clampPool}），
     * 所以这一格里没有"拿 {@code Long.MAX_VALUE} 那道哨兵当量乘"的可能——
     * 与本类 {@link #representableFirings} 相反的<em>方向</em>恰好是同一件事：那两本 {@code long} 域
     * <b>锁</b>账（占料锁／首位认领）只能装 {@code long}，装不下就不下那道悲观锁；
     * 这一本装得下，就没有"装不下"这一档（产出那本贷方账同走宽域出口，见
     * {@link #productionFirings}——台账 #217 之前它借的是那把锁尺，于是越界的样板整个不进账）。
     */
    private final Map<Object, RingSeedReserve> ringSeedReserves;

    /**
     * 一枚<b>精确种键</b>在<b>它所在分量</b>里的留底条目——族条目下记账的最小那一份。
     *
     * @param amount          这个分量自己还要花掉的枚数（宽域真值）：分量还得上一整份时是<b>整份剩余
     *                        需求</b>，本来就净吃种时是<b>真实缺口</b> {@code owed - handedBack}
     *                        （取法见 {@link #buildRingSeedReserve}）——不在 {@code exemptConsumers}
     *                        里的消费者一概先从池子现量里扣掉它
     * @param exemptConsumers 不许被<b>这一份</b>扣的消费者——就是这一枚种键所在<b>环分量的成员</b>
     *                        （名单由 {@link RingSeedLedger.SeedKeyDemand} 交出，本类不再判一遍；
     *                        链式环里吃中转那枚的 B 与吃种的 A 同属一个分量，都不许被这一格扣）
     */
    private record SeedKeyReserve(PlanAmount amount, Set<IPatternDetails> exemptConsumers) {
    }

    /**
     * 一个族的环种留底条目：族下每个在册的精确种键各一份 {@link SeedKeyReserve}，各自带自己的分量名单。
     *
     * @param entries 该族名下各精确种键的分量留底，至少一条
     */
    private record RingSeedReserve(List<SeedKeyReserve> entries) {

        /**
         * 池子现量削掉这份留底之后的可花量（宽域进、宽域出）。
         *
         * <p>{@code self} 只对<b>它不在其中的那些分量</b>付这一笔：它是某个分量的成员就免扣那一份，
         * 别的分量照样扣——把族下各分量的名单并成一份，就等于让 A 分量的成员免扣 B 那份口粮。
         *
         * <p><b>它在这一族每一份分量的成员名单里时交回 {@code null}</b>（＝没有要扣它的那一截，
         * 调用方一概不夹）：豁免不是"扣 0 枚"而是"这一条腿对它不成立"，
         * 否则下游拿可花量折发数时会把它自己的池子现量当成一道新夹子（增殖腿因此起步不了、
         * 链式环互等死锁）。
         *
         * <p>族内各键走 {@link PlanAmount#add} <b>复加</b>（不是取最大）：同族两个精确键各欠一发时
         * 取最大只夹住第一枚，第二枚正是实机那一次旁人从池里多抽走的。
         *
         * <p>只做<b>一次</b>宽窄比较、从不把宽值窄化成读数再当量：留底不小于池量就是 0，
         * 交回的数恒 ≤ 传进来的 {@code stored}，所以这里没有饱和出口，也就没有"哨兵冒充量"的那条来路。
         */
        private @Nullable BigInteger clampPool(BigInteger stored, IPatternDetails self) {
            PlanAmount owed = PlanAmount.ZERO;
            boolean locksAnything = false;
            for (SeedKeyReserve entry : entries) {
                if (!entry.exemptConsumers().contains(self)) {
                    owed = owed.add(entry.amount());
                    locksAnything = true;
                }
            }
            if (!locksAnything) {
                // 这一张就写在这一族每一份分量的成员名单里：它是环自己，没有要留给旁人的那一截
                return null;
            }
            var locked = owed.exactValue();
            return stored.compareTo(locked) <= 0 ? BigInteger.ZERO : stored.subtract(locked);
        }
    }

    private ParallelPlanReservation(
            Map<AEKey, Long> firstCandidateNeed,
            Map<AEKey, Long> firstCandidateDemand,
            Map<AEKey, PlanAmount> pendingProduction,
            Set<AEKey> unrepresentableProduction,
            Map<AEKey, Long> pool,
            MaterialAllocation materialAllocation,
            Set<Object> unboundedFamilies,
            Map<Object, RingSeedReserve> ringSeedReserves
    ) {
        this.firstCandidateNeed = firstCandidateNeed;
        this.firstCandidateDemand = firstCandidateDemand;
        this.pendingProduction = pendingProduction;
        this.unrepresentableProduction = unrepresentableProduction;
        this.pool = pool;
        this.materialAllocation = materialAllocation;
        this.unboundedFamilies = unboundedFamilies;
        this.ringSeedReserves = ringSeedReserves;
    }

    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool,
            MaterialAllocation materialAllocation
    ) {
        return build(tasks, pool, materialAllocation, Set.of());
    }

    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool,
            MaterialAllocation materialAllocation,
            Set<AEKey> unboundedKeys
    ) {
        return build(tasks, pool, materialAllocation, unboundedKeys, null);
    }

    /**
     * @param ringSeedDemands 本作业<b>现算</b>的环种分量口径（{@code ParallelExecutingCraftingJob#ringSeeds}
     *                        的 {@code seedDemandsByFamily()}）：只有这里在册的族才留底，一个都不多夹。
     *                        传 {@code null} 即这一趟不下留底（无环种口径的老调用点）。
     */
    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool,
            MaterialAllocation materialAllocation,
            Set<AEKey> unboundedKeys,
            @Nullable Map<Object, RingSeedLedger.SeedDemand> ringSeedDemands
    ) {
        return build(tasks, pool, materialAllocation, unboundedKeys,
                ringSeedDemands, null);
    }

    /**
     * @param obligations 作业那本 {@link BatchObligationLedger}：留底复加的发数要带上"这张样板还挂在
     *                    欠账上的发数"（申报那一刻 {@code consumeFirings} 已把 {@code k} 发从剩余里扣干，
     *                    剩下 {@code k-1} 发的材料仍由同一张样板从池子里取）。传 {@code null} 即这一趟
     *                    只看任务表剩余发数（没有义务账口径的老调用点）。
     */
    static ParallelPlanReservation build(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            Map<AEKey, Long> pool,
            MaterialAllocation materialAllocation,
            Set<AEKey> unboundedKeys,
            @Nullable Map<Object, RingSeedLedger.SeedDemand> ringSeedDemands,
            @Nullable BatchObligationLedger obligations
    ) {
        // 任务表物化一次：这本账要按同一份序遍历三趟（产出、首位认领、环种留底），
        // 留底那一趟只按环种账交出的分量口径复加剩余发数。
        List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> taskEntries =
                new ArrayList<>();
        for (var task : tasks) {
            taskEntries.add(task);
        }
        var need = new HashMap<AEKey, Long>();
        var demand = new HashMap<AEKey, Long>();
        // 贷方账（量）：宽域精确，条目只进不出——"这一笔装不进 long"不是把量抹成 0 的理由
        var production = new HashMap<AEKey, PlanAmount>();
        // 逐键的"撤账名单"：多笔复加一旦越出 long，该键在这一本账里就再没有可信口径
        // （与 {@link #accumulateFirstCandidateNeed} 里"单项越界不记账"同形状、同方向）。
        // 名单要跟着整趟 build 走：撤账之后晚来的任务再复加一笔，记回去的就是半截账——
        // 半截账照样是哨兵冒充量的那条来路。
        var revokedNeed = new HashSet<AEKey>();
        var revokedDemand = new HashSet<AEKey>();
        // 产出这本账的名单不是"撤了就完事"：它是贷方，撤条目会收紧锁，所以名单要交进
        // usableFor 参与判据（见 {@link #unrepresentableProduction}）。它同样是 build 内的局部量，
        // 每 tick 随这份账本重建，不跨 tick。
        var unrepresentableProduction = new HashSet<AEKey>();
        // 第一趟＝贷方账（量）：发数走 {@code exactRemaining()} 的宽域出口，
        // <b>不</b>经第二趟那把只能吃 long 的锁尺——剩余发数越出 long 的样板照样把它计划产的
        // 那一截记进账上（台账 #217：那一档从前在这里整笔不进账，下游读成"没人会产"）。
        for (var task : taskEntries) {
            IPatternDetails details = task.getKey();
            PlanAmount firings = productionFirings(task.getValue());
            if (details == null || firings.signum() <= 0L) {
                continue;
            }
            accumulateProduction(production, unrepresentableProduction, details, firings);
        }
        // 第二趟＝两本 long 域<b>锁</b>账（占料锁、首位认领）：发数读 {@link #representableFirings}，
        // 装不进 long 就整条不下锁（少一道悲观锁，不是少一笔算量）；这一趟的读数与算式一字未动。
        for (var task : taskEntries) {
            IPatternDetails details = task.getKey();
            long firings = representableFirings(task.getValue());
            if (details == null || firings <= 0L) {
                continue;
            }
            accumulateFirstCandidateNeed(need, revokedNeed, demand, revokedDemand,
                    details, firings);
        }
        Set<Object> unboundedFamilies = new HashSet<>();
        if (unboundedKeys != null) {
            for (AEKey key : unboundedKeys) {
                if (key != null) {
                    unboundedFamilies.add(key.getPrimaryKey());
                }
            }
        }
        return new ParallelPlanReservation(need, demand, production, unrepresentableProduction,
                pool, materialAllocation, unboundedFamilies,
                buildRingSeedReserve(taskEntries, ringSeedDemands, obligations));
    }

    /**
     * 环种留底：逐<b>物品本体族</b>交出"该族名下每一枚精确种键<b>所在分量</b>还没跑完的那一截种子
     * 还要花掉几枚"，以及每份各自不许被扣的消费者名单（就是那一个分量的成员）。
     *
     * <p>算法两句：先按分量成员复加两笔宽域账
     * {@code owed = Σ_{P ∈ K 所在环分量} P 每发吃掉的 K 量 × P 还要吃的发数}、
     * {@code handedBack = Σ 同一批 P 每发还回的 K 量 × 同一批发数}（这里的 {@code K} 是<b>精确种键</b>）；
     * 再按这两笔的大小取<b>留底那一截</b>——{@code handedBack >= owed} 时是整份 {@code owed}，
     * 分量本来就净吃种时是真实缺口 {@code owed - handedBack}（口径见方法体内那一格注释）。
     * 族条目＝族下各 {@code K} 的这些分量并列，夹池子时按伸手那张样板<b>不在其中的那些分量</b>复加。
     * "哪个分量、里面哪几张、每发吃掉哪一枚几枚、每发还回哪一枚几枚"与"键属于哪个族"一律读
     * {@link RingSeedLedger#seedDemandsByFamily()}——那份 SCC 与那份分组只在账本里算一次，
     * 本类只做复加；在这里再写一份"还回 K"／"产出喂给待办 producer"／"按键合族"的形状，
     * 就是第二把分量尺。
     *
     * <p>"还要吃的发数"= 任务表 {@code exactRemaining()} + {@link BatchObligationLedger} 上
     * <b>还没交割的义务发数</b>，算式只有 {@link RingSeedLedger#outstandingFirings} 那一句：
     * 申报腿在 {@code ParallelCraftingCpuLogic} 申报那一刻就把这 {@code k} 发从剩余里扣干
     * （{@code progress.consumeFirings(acceptedFirings)}），而剩下 {@code k-1} 发的材料将来仍由同一张
     * 样板从池子里取（{@code ParallelBatchExtraction#payDeclaredObligation} → 本类 {@link #usableFor}）。
     * 只看剩余发数会两头漏：旁人 {@code owed} 少算这一截（夹得不够），这一张自己也出不了豁免名单
     * （回来付欠账时被同族留底夹死，产物欠账永远交割不掉）。
     *
     * <p>基准从"还回 K 的那几张"换成"K 所在分量自己"，为的是<b>链式环</b>（A 吃 K 出 L、
     * B 吃 L 出 K）：那种形状里没有任何样板把物料还给自己，{@code refundsKey(B, K)} 为真而
     * {@code inputAmount(B, K) = 0} ⇒ 按"还回 K"算出来 {@code owed(K) = 0} ⇒ 整族不进账 ⇒
     * 那颗 K 在取料点上照样被旁人一次抽干。自循环环下两个口径退化成同一个数
     * （那张既还回 K 又吃 K），实机 {@code ae2:creative_storage_cell} 那一档一分不变。
     *
     * <p>发数读 {@link ParallelExecutingCraftingJob.TaskProgress#exactRemaining()} 的<b>宽域真值</b>
     * 再加义务账那份，复加走 {@link WideMath#mul(long, PlanAmount)} + {@link PlanAmount#add}：
     * 发数越出 {@code long} 时这一笔照样成立，{@code > long} 的订单不再"量级一大就不守种"。
     * 不是 {@code value} 那份饱和镜像——镜像越界时它是哨兵，拿哨兵当量乘就成了谁也取不动的墙
     * （与 {@link #representableFirings} 同一条理由，只是这一本尺子宽到根本不用窄化）。
     *
     * <p>族是<b>查表与算尺</b>的口径（与 {@link #shareOf}/{@link #remainingQuota} 同一族），族内任一变体
     * 伸手都要先扣这一族的留底；<b>记账与豁免按精确种键各自的分量交</b>：环成员吃的是自己那一枚就只免扣
     * 自己那一份，同族别的分量那份口粮照扣——把两个分量的成员并成一份名单，A 的成员就能把 B 抽干。
     *
     * <p><b>在册名单与留底看的是同一趟的数</b>：账本每次读都现派生（{@link RingSeedLedger
     * #seedDemandsByFamily()}，旧的"按任务表条数缓存重建"那一格已删），所以"这一枚精确键在不在册"判的
     * 就是本 tick 的筛图（{@code outstandingFirings <= 0} 的样板整张出图），与本方法复加用的发数同一趟
     * ——两侧都是"剩余发数 + 未交割义务发数"的宽域读数。这一点是留底<b>取哪一截</b>成立的前提：
     * {@code owed} 与 {@code handedBack} 的大小决定这一枚键锁整份还是只锁缺口，而它们乘的正是筛图那句
     * 用的同一批发数；册子慢一步就会出现"账本按 {@code 5/0} 把 B 留在图外、复加却按 {@code 5/4} 夹"
     * 这种两头不对的账。
     *
     * <p>它天然只在"该精确键所在分量还有待办的成员"时存在：某一张跑完了、欠账也交割清了，它的
     * {@code outstandingFirings} {@code signum() <= 0}，这一档在复加里直接跳过；分量里每张都跑到 0
     * （{@code owed = 0}）时这一枚键不出条目，族下每一枚都跑完时这一族整个不进账。条目本身并不因此出表——
     * {@code ParallelCraftingCpuLogic} 只在 {@code taskMayLeaveTable} 那一档才 {@code remove}，
     * 跑完仍挂声明批欠账的照样留在表里，撤的是这里这一档复加，不是任务表的条目。
     */
    private static Map<Object, RingSeedReserve> buildRingSeedReserve(
            List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> tasks,
            @Nullable Map<Object, RingSeedLedger.SeedDemand> ringSeedDemands,
            @Nullable BatchObligationLedger obligations
    ) {
        if (ringSeedDemands == null || ringSeedDemands.isEmpty() || tasks.isEmpty()) {
            return Map.of();
        }
        // 还要吃的发数按样板现取：复加只问"这一张还要从池子里吃几发"，那张的份数账归任务表与义务账
        var remainingByPattern = new HashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>(Math.max(16, tasks.size() * 2));
        for (var task : tasks) {
            if (task.getKey() != null) {
                remainingByPattern.put(task.getKey(), task.getValue());
            }
        }
        var reserves = new HashMap<Object, RingSeedReserve>();
        for (var demand : ringSeedDemands.entrySet()) {
            // 族下每个精确种键各记一份分量留底：族条目只是它们的名单，取最大就少算另一枚那一份
            var entries = new ArrayList<SeedKeyReserve>();
            for (var seedKey : demand.getValue().seedKeys()) {
                PlanAmount owed = PlanAmount.ZERO;
                PlanAmount handedBack = PlanAmount.ZERO;
                // 一笔发数同时喂两笔复加：分量成员对这一枚精确键"每发吃几枚"与"每发还几枚"读的是
                // 同一趟发数，留底取哪一截才与账本那侧的筛图同向（账本每次读都现派生）
                for (var member : seedKey.members()) {
                    long perFiringConsume = seedKey.perFiringConsume().getOrDefault(member, 0L);
                    long perFiringReturn = seedKey.perFiringReturn().getOrDefault(member, 0L);
                    if (perFiringConsume <= 0L && perFiringReturn <= 0L) {
                        continue;
                    }
                    var progress = remainingByPattern.get(member);
                    if (progress == null) {
                        continue;
                    }
                    PlanAmount firings = RingSeedLedger.outstandingFirings(progress.exactRemaining(),
                            obligations == null ? null : obligations.pendingFiringsWide(member));
                    if (firings.signum() <= 0L) {
                        // 这一张既跑完也没有待交割的欠账：分量里别的张还欠着也轮不到扣它那份
                        continue;
                    }
                    // 宽域复加：没有"这一笔装不进 long 就整族撤账"那一档（见抬头）
                    if (perFiringConsume > 0L) {
                        owed = owed.add(WideMath.mul(perFiringConsume, firings));
                    }
                    if (perFiringReturn > 0L) {
                        handedBack = handedBack.add(WideMath.mul(perFiringReturn, firings));
                    }
                }
                if (owed.signum() <= 0L) {
                    // 这个分量里没一张还欠着发数：没有要留给自己的那一截，这一枚键不进账
                    continue;
                }
                // 留底取哪一截（口径：环优先跑，跑完整条环再放行旁人；用户 2026-09-30 定版）：
                //   handedBack >= owed ⇒ 分量自己还得上一整份 ⇒ 留底就是整份剩余需求 owed
                //     （这一档口径一字未动）；
                //   0 < handedBack < owed ⇒ 这一环本来就净吃那一枚种（缺口由规划期起步提取补）
                //     ⇒ 只锁真实缺口 owed − handedBack。那一枚种在 A→L→B→K 里循环、环成员自己在
                //     豁免名单里取走它，所以环能一路跑到 owed 归零，之后这一格复加成 0、自动放行旁人。
                // 这一格只是把账本交出的两笔同域复加比个大小，不重算分量归属（分量只在 RingSeedLedger 算）。
                PlanAmount reserve = handedBack.compareTo(owed) >= 0 ? owed : owed.subtract(handedBack);
                entries.add(new SeedKeyReserve(reserve, seedKey.members()));
            }
            if (entries.isEmpty()) {
                // 族下每一枚精确键的分量都跑完了：这一族整个不进账
                continue;
            }
            reserves.put(demand.getKey(), new RingSeedReserve(List.copyOf(entries)));
        }
        return reserves;
    }

    /**
     * 两本 long 域<b>锁</b>账（占料锁、首位认领）只吃得下<b>装得进 long 的发数</b>。
     *
     * <p><b>它只管"锁不锁"，不管"有多少"</b>：交回 0 的含义只有"这一张这一趟不下占料悲观锁"，
     * <b>不是</b>"这张样板不产任何东西"。产出那笔<b>量</b>走 {@link #productionFirings}；
     * {@code build} 里那两趟各读各的出口（台账 #217 第 2 条分家的就是这两句）。
     *
     * <p>读的是 {@link ParallelExecutingCraftingJob.TaskProgress#exactRemaining()} 的宽域真值，
     * 而不是 {@code value} 那份饱和镜像：镜像越界时它就是 {@code Long.MAX_VALUE} 那道<b>哨兵</b>，
     * 拿它乘"每发吃几枚"就把锁乘成一堵墙——实机 2026-09-25 19:08 那一跑正是
     * {@code rem=Long.MAX locked=[minecraft:lava stored=9223372036854775807 usable=0]}：
     * 池子里躺着 9.2E 枚 lava 而谁也取不动，{@code noCand=3154}。
     *
     * <p>算不出可信口径时<b>不锁</b>（交回 0 即整条累加跳过）：宁可少一道悲观预留，
     * 也不能把"锁主自己都要分几万轮才吃得下"的那一截当成一笔当场可兑现的债权。
     */
    private static long representableFirings(
            @Nullable ParallelExecutingCraftingJob.TaskProgress progress
    ) {
        if (progress == null) {
            return 0L;
        }
        var exact = progress.exactRemaining().exactValue();
        return exact.compareTo(WideCeilings.LONG_ACCOUNT) > 0 ? 0L : exact.longValue();
    }

    /**
     * 产出这本<b>贷方账</b>（量）的发数出口：{@code exactRemaining()} 的<b>宽域真值原样交出</b>，
     * 不做"能不能塞进 {@code long}"这一问——那一问属于锁那一腿（{@link #representableFirings}）。
     *
     * <p>把"装不进 long"当成"这一笔产出存不存在"的判据，就是台账 #217 那一格：剩余发数
     * {@code > long} 的样板在贷方账上整个不进账，下游把这一键读成"没人会产"，而同一行的缺口
     * {@code left} 是宽域真值（实机 {@code toMake=0} 与 {@code left=2.27e20} 同时成立）。
     * 量级一大就不记账，从来不是这一族定版（#106／#100／#52／#98）的口径。
     *
     * <p>这里不新增任何折算、上限或豁免：交回的就是任务表那本现成的宽域读数；
     * 乘算在 {@link WideMath#mul(long, PlanAmount)} 上全程精确，窄化只发生在把量交给
     * {@code long} 域锁算式的那一道出口（{@link #pendingProductionForLock}）。
     */
    private static PlanAmount productionFirings(
            @Nullable ParallelExecutingCraftingJob.TaskProgress progress
    ) {
        return progress == null ? PlanAmount.ZERO : progress.exactRemaining();
    }

    /** 这一键是否属于规划认下的无限源：按<b>物品本体族</b>判，同族变体一起豁免。 */
    boolean isUnboundedSupply(@Nullable AEKey key) {
        return key != null && !unboundedFamilies.isEmpty()
                && unboundedFamilies.contains(key.getPrimaryKey());
    }

    /**
     * 这个键还能被 {@code self} 的这批取走多少。
     *
     * <p>提交期配给在时<b>只按配额封顶</b>：{@code self} 在这一键上能取的就是
     * "规划配给它、还没取走"的那一截，一份额外都不给。规划定版了这一格哪个键几份，
     * 执行侧就照单取——份额取完还想接着吃首位键，就是尾段单键消费者 {@code stored=2}
     * 的来路。原样退回的模具槽不在配给里（见类注释），它走结构判据。
     *
     * <p>没有配给（续传作业）才退到结构判据：按占料扣——能兑现的只有"还没派发的产出里没被
     * 任何槽按第一候选认领的那截"，加上 {@code self} 自己这一批要走的量；且要这一格
     * <b>改得了道</b>才生效（现场落得下别的键，或候选表里别的键在计划供给里有量）。
     * 两条判据不同时叠：占料锁按"别人要多少"预留，配给按"别人配了多少"封顶，叠在一起
     * 同一份需求被扣两遍，夹出来的就是 {@code soloReady=0/108}。
     *
     * <p><b>两条分支读的是同一份可花量</b>：池子现量先被环种留底（{@link #ringSeedReserves}）削成
     * {@code spendable}，配给那条早退与占料那条都从它起算。留底只说"这一截是环自己还没跑完那一份
     * 的口粮"，与"每人能花多少"是两件事——只按配给夹，环转起来之前种子就被旁人一次抽干了
     * （见 {@link #ringSeedReserves}）。
     *
     * <p>这一份扣减<b>不止长在本方法的取料口上</b>：同一句 {@link #spendableAfterRingSeedReserve}
     * 也被折发数那条腿（{@code ParallelCraftingCpuLogic#clampByRemainingQuota}）与网络回补那条腿
     * （{@code ParallelBatchExtraction#refillObligationBatch}）读——那两条腿按发数与按缺口伸手，
     * 本来就不经过本方法，只守"从池子里取"这一句守不住（实机 {@code 1e11} 那一跑：
     * 规划侧 {@code kept} 的判决行是好的、留底读数也是对的，种还是被别的样板按发数申报走吃完了）。
     *
     * @param selfFirings 本次批量上限，用来把 {@code self} 自己的需求从占料里还回去
     * @param slot {@code self} 的哪一格在向这个键伸手；为 null 时只按现场视角判要不要夹
     */
    long usableFor(AEKey key, long stored, @Nullable IPatternDetails self, long selfFirings,
            @Nullable IPatternDetails.IInput slot, boolean fallbackOnSite) {
        if (stored <= 0L) {
            return 0L;
        }
        // 无限源没有数值上界：配额与占料两道夹子给它夹出来的都是假数（实机
        // locked=[minecraft:lava stored=9223372036854775807 usable=0] 即此）。
        if (isUnboundedSupply(key)) {
            return stored;
        }
        // 环种留底：先扣掉环自己还没跑完的那一截，两条分支共用这一份 spendable
        // （唯一读口 spendableAfterRingSeedReserve；交回 null = 这一枚键对这一张没有要扣的留底）
        var clampedPool = spendableAfterRingSeedReserve(key, BigInteger.valueOf(stored), self);
        long spendable = clampedPool == null ? stored : clampedPool.longValue();
        // 有配额口径就硬封顶：判据与 quotaFor 同源一条，不再两处各写一遍
        // （两处口径分叉过一回，写的是同一格的账对不上）。
        long quota = quotaFor(key, self, slot);
        if (quota >= 0L) {
            return Math.min(spendable, quota);
        }
        long usable = spendable;
        long locked = firstCandidateNeed.getOrDefault(key, 0L);
        // 产出这本贷方账放不下可信口径 ⇒ 这一键<b>不下悲观锁</b>（不是"少一笔贷方"把锁收紧）：
        // 见 {@link #unrepresentableProduction}。此处只处理未来计划的 long 投影；
        // 已声明批次对池内实物的预留由义务账单独按宽域现量计算。
        boolean productionUntrusted = productionHasNoTrustworthyReading(key);
        if (locked > 0L && !productionUntrusted
                && (fallbackOnSite || canAvoidThisKey(slot, key))) {
            usable = saturatingSubtract(usable,
                    reservedForExclusiveConsumers(key, locked, self, selfFirings));
        }
        return Math.max(0L, usable);
    }

    /**
     * 环种留底的<b>唯一读口</b>：这一张样板对这一枚键、在 {@code stored} 这份现量里，
     * 扣掉<b>分量自己还没跑完</b>的那一截之后<b>实际还能花几枚</b>。
     *
     * <p>三处调用点读的都是这<b>一句</b>，没有第二把尺：取料点（{@link #usableFor} 的配给早退与占料
     * 分支）、折发数那条腿（{@code ParallelCraftingCpuLogic#clampByRemainingQuota}——宿主按机器容量
     * 自己定发数，那一维过去只受配额夹）、网络回补那条腿
     * （{@code ParallelBatchExtraction#refillObligationBatch} 的 {@code room}——旁人申报批那
     * {@code k-1} 截料经网络搬进本 lane 池子）。分量归属、每发消耗/还回与豁免名单一律来自
     * {@link RingSeedLedger#seedDemandsByFamily()}，这里只把"伸手那张不在其中的那些分量"复加成一截，
     * 再从现量里扣掉它——<b>同一份 {@code owed}，三条腿一个字都不另算</b>。
     *
     * <p>交回 {@code null} ＝ <b>这一枚键对这一张没有要扣的留底</b>，调用方对这一格一概不夹，三档：
     * 这一族不在账上（族下没有一枚种键的分量还有待办的成员）；{@code self} 认不出来——判不了豁免就
     * <b>不扣</b>，与 {@link #representableFirings} "算不出可信口径就少一道悲观预留"同方向，
     * 不是把它当旁人夹死；{@code self} 就写在这一族<b>每一份</b>分量的成员名单里
     * （环分量的成员自己不许被这三条腿夹，否则增殖腿起步不了、链式环还会互等死锁）。
     * 豁免这一档不是"扣 0 枚"：交回 {@code stored} 会让下游把池子现量当成一道<b>新</b>夹子
     * （折发数那条腿会把增殖腿自己折到只剩池里那一发）。
     *
     * <p>{@code self} 只在<b>某一些</b>分量的名单里时照样扣它不在其中的那几份
     * （{@link RingSeedReserve#clampPool}）：它是 A 分量的成员就去领自己那份口粮，
     * 可它伸手拿 B 分量留底的那一枚时它就是旁人。
     *
     * <p><b>宽域进、宽域出，出口没有饱和</b>：交回的数恒 ≤ 传进来的 {@code stored}
     * （只做一次 {@code owed} 与现量的宽窄比较），所以这里没有"拿 {@code Long.MAX_VALUE} 那道哨兵
     * 当量"的可能。窄化留给调用方各自那一维：{@link #usableFor} 的现量本来就是 {@code long}，
     * 折发数与回补两条腿在 {@link BigInteger} 上做商与差。
     */
    @Nullable
    BigInteger spendableAfterRingSeedReserve(@Nullable AEKey key, @Nullable BigInteger stored,
            @Nullable IPatternDetails self) {
        if (key == null || stored == null || self == null || ringSeedReserves.isEmpty()) {
            return null;
        }
        var reserve = ringSeedReserves.get(key.getPrimaryKey());
        if (reserve == null) {
            return null;
        }
        return reserve.clampPool(stored, self);
    }

    /**
     * <b>锁那一腿的唯一出口</b>：这一键的产出量窄化到 {@code long} 就读成天花板哨兵 ⇒ 对它
     * <b>不下悲观锁</b>。它<b>只</b>回答"锁不锁"——"还会产几枚"另有出口
     * （{@link #pendingProductionExact}），两句话在老口径里是同一句（{@link #dropAsUntrustworthy}
     * 撤条目顺带把量抹成 0），台账 #217 第 2 条分家的就是这两句。
     *
     * <p>同一本贷方账从此有两个读点，各自只答一件事：{@link #pendingProductionForLock}
     * 交 {@code long} 域锁算式用得上的读数（名单里的键算 0 供给），{@link #pendingProductionExact}
     * 交宽域真值。名单本身一处判据（{@link #mergeProduction} 里那一道 {@code >=} 天花板），
     * 只用于未来产量的 long 域锁算式，不能据此推断池内现货或网络中是否有材料。
     */
    boolean productionHasNoTrustworthyReading(@Nullable AEKey key) {
        return key != null && unrepresentableProduction.contains(key);
    }

    /**
     * <b>纯读数</b>：这本产出贷方账里"窄化读数撞上天花板 ⇒ {@link #usableFor} 对它不下悲观锁"的键数
     * （{@link #unrepresentableProduction} 的大小）。
     *
     * <p>这一放开与"本来就不该锁"在实机是<b>同一个读数</b>：{@code usableNow == stored}
     * ⇒ {@code ParallelBatchExtraction} 的 {@code probe.resClamp} 不记 ⇒ 日志分不出两型。
     * 名单本来就是每 tick {@code build} 现算的 build 内局部量，这里只是把它读出来：
     * 不新增第二本账、不跨 tick 状态。
     * <b>不参与任何判据</b>——不得据此改判据、拒绝、早退。
     */
    int untrustedProductionKeyCount() {
        return unrepresentableProduction.size();
    }

    /**
     * 该样板在这一键上还没销完的配额（按物品本体族合计），不含槽级豁免。
     *
     * <p>返回负数表示<b>没有配额口径</b>（这张样板不在表里）——
     * 调用方此时不该夹，夹了就是把没有归属的样板整批饿死。
     * 取料侧要的是带上槽级豁免的 {@link #quotaFor}，别直接用它夹模具槽。
     *
     * <p><b>这一行是这两本配给账唯一的对外饱和出口</b>：份额本身可以是 {@code > long} 的宽域量
     * （配给按槽复加、已吃按批复加），而下游 {@code Math.min(stored, quota)} 与
     * {@code 份额 / 每发合计} 都只能吃 {@code long} ⇒ 在这里按"宁高估"口径钉到
     * {@link Long#MAX_VALUE}。绝不在账本里饱和：账本一顶天花板，"配给 − 已吃"就会算成
     * {@code 0} 份额，还能推的批次被误判成配额用尽。
     *
     * <p><b>要拿配额再减去一个同样可能越界的量</b>（回补侧要减池子里的族现量）的调用方，
     * 走 {@link #quotaExact}：那条判据不能在这道天花板上做差。
     */
    long remainingQuota(IPatternDetails self, AEKey key) {
        // 无限键交回 -1（没有配额口径），不是 0：份额这一概念对它本就不成立，
        // 夹成 0 就是把"还能推几发"永远判死。
        PlanAmount mine = quotaExact(key, self, null);
        return mine == null ? -1L : Ae2Amounts.toLong(mine.max(PlanAmount.ZERO));
    }

    /**
     * 这一格在这一键上还有多少<b>族</b>配给的<b>真值</b>；返回 {@code null} 表示
     * <b>没有配额口径</b>（不夹）。
     *
     * <p>判据与 {@link #quotaFor} 逐字同一条（含"原样退回的模具槽不在配给里"那一格豁免），
     * 只少最后那道"宁高估"饱和：份额是宽域量，调用方要拿它<b>减去另一个宽域量</b>（池子里已经
     * 躺着的那一截）才能知道还能补几枚。两个天花板读数相减得 {@code 0} 就是把哨兵当了量，
     * 还能补的那一截被判成配额用尽（{@code > long} 的一发在带配额的样板上永远开不了工）。
     * 饱和留给唯一一处出口（{@link #remainingQuota}），或夹完再由调用方窄化一次喂给 AE2 通道。
     */
    @Nullable
    PlanAmount quotaExact(@Nullable AEKey key, @Nullable IPatternDetails self,
            @Nullable IPatternDetails.IInput slot
    ) {
        if (key == null || self == null || (slot != null && SlotAccount.selfRepaying(slot, key))) {
            return null;
        }
        if (isUnboundedSupply(key)) {
            return null;
        }
        // 只在<b>带槽的伸手口径</b>（回补/申报/取料点真正伸手那一格，{@code slot != null}）上，把
        // "这一族在这本配给表里压根没有条目"（表非空、却没这一族：这是"从没许过这一族"，不是
        // "许过且已花光"）读成"没有配额口径"——交 {@code null} ⇒ 不夹 ⇒ 让回补能伸手。
        // 这一问句只有 {@link #hasQuotaBasis} 一个入口（族条目那一判 {@code SlotAccount#hasFamilyEntry}
        // 从此只活在那一处，不在这里复写第二份）。<b>整本不在表里</b>那一型（{@code assigned == null}
        // 或空表）照旧不在这里放开，仍走下面的 {@link #remainingShare}；{@link #remainingQuota} 那份
        // {@code slot == null} 的 {@code long} 剩余份额读数也照旧交回销账份额——
        // "定料之后不许退到没配给的键"那一道夹子不在这一刀的放开范围内（方向纪律：只少回补那一道夹）。
        if (slot != null && materialAllocation.hasPatternQuota(self) && !hasQuotaBasis(self, key)) {
            return null;
        }
        return remainingShare(self, key);
    }

    /**
     * 这一格在这一键上还有多少<b>族</b>配额可花；返回负数表示<b>没有配额口径</b>（不夹）。
     *
     * <p>判据与 {@link #usableFor} 完全一致：原样退回的模具槽不在配给里，也就不受配额封顶。
     * 批量提取要拿这个数除以"本批在该族上每发合计吃几枚"——份额是<b>整族一份</b>，
     * 同一批里每个槽、每个候选都从同一份里扣，不能各按整份配额花两遍。
     */
    long quotaFor(@Nullable AEKey key, @Nullable IPatternDetails self,
            @Nullable IPatternDetails.IInput slot) {
        if (key == null || self == null || (slot != null && SlotAccount.selfRepaying(slot, key))) {
            return -1L;
        }
        return remainingQuota(self, key);
    }

    /**
     * 这一族在这张样板的配给表里<b>确有口径</b>（{@code planMaterialPlan} 里有同族的条目，<b>含 0 值</b>）。
     *
     * <p>请求侧折发数（{@code ParallelCraftingCpuLogic#dispatchFiringsInsideQuota}）只对此类键施夹；
     * 返回 {@code false} 涵盖：样板整本不在表里、这一族压根没被配给（原样退回的模具槽／自偿还／
     * 无限源同此）、或键／样板为空。读的是与 {@link #quotaExact} <b>同源</b>的族合计
     * （{@link #shareOf}），不写第二道封顶：它只回答"这一族有没有配额口径"，具体份额仍交 quotaExact。
     *
     * <p>为什么这一问句<b>不许带份额的正负号</b>：配给表里"这一族在册、但份额已被销到 0"与
     * "表里根本没这一族"是两件事（{@code MaterialAllocation#mintShare} 保住
     * 0 值条目也在册，本类 {@link #remainingShare} 靠 {@code isEmpty()} 判"整本不在表里"）。
     * 按老写法 {@code shareOf(...).signum() > 0} 判，前者被判成"没口径" ⇒ 请求侧放过、不折发数，
     * 而 {@code ParallelCraftingCpuLogic#attach} 那边照样按 {@link #quotaExact} 交回的 {@code 0}
     * 夹死 ⇒ 物理多拿的那一截两不相接：配额账不进、{@code over=} 不亮（假信号），池子对别的 lane 虚高。
     * 份额真值仍只由 {@link #quotaExact} 一处交，这里不改口算份额。
     *
     * <p>为什么请求侧要单列这一步：{@link #quotaExact} 对"样板在表里、这一族不在"交回的是
     * {@code 0}（份额用尽的形状），而不是 {@code null}（没有口径）。在样本槽上这一型该硬夹
     * （定料之后不许退到没配给的键），但在盲发那一号上——宿主申报里样本看不见、配给表也没这一族的键——
     * 把它当"用完"夹成 0，就是把快路径静默永久关掉（与"配额真用尽"逐字同形的假信号）。
     */
    boolean hasQuotaBasis(@Nullable IPatternDetails self, @Nullable AEKey key) {
        return materialAllocation.hasQuotaBasis(self, key);
    }

    /** {@code self} 在这一键上还没取走的配给；这张样板没有配给（或压根不吃饭）时返回 null。 */
    private @Nullable PlanAmount remainingShare(IPatternDetails self, AEKey key) {
        return materialAllocation.remainingFamilyQuota(self, key);
    }

    /**
     * 份额按<b>物品本体族</b>合计（忽略组件/变体差异），合计走<b>宽域</b>。
     *
     * <p>配给可能把这一格记在同物品的某个组件变体键上，执行侧伸手的是另一个变体——
     * 逐精确键查表等于把一族拆成两笔，"实耗比份额多 1 枚"就是这么来的
     * （实机 {@code glass ate=1199245818 share=1199245817}）。族内合计仍是整槽那一份数，
     * 不会放到别人的份额上去。
     *
     * <p>老口径这一族的合计走 {@code saturatingAdd}：一族几个变体各自 ≤long、合计 >long 时
     * 整份被顶到天花板，与同样顶到天花板的已吃账做差就成了 {@code 0}。这里返回
     * {@link PlanAmount}，饱和留给 {@link #remainingQuota} 那一道出口。
     */
    static PlanAmount shareOf(@Nullable WideKeyCounter amounts, AEKey key) {
        if (key == null || amounts == null || amounts.isEmpty()) {
            return PlanAmount.ZERO;
        }
        var family = key.getPrimaryKey();
        PlanAmount total = PlanAmount.ZERO;
        for (AEKey candidate : amounts.keys()) {
            if (candidate == null || !family.equals(candidate.getPrimaryKey())) {
                continue;
            }
            PlanAmount value = amounts.getAmount(candidate);
            if (value == null || value.signum() <= 0) {
                continue;
            }
            total = total.add(value);
        }
        return total;
    }

    private static void accumulateProduction(
            Map<AEKey, PlanAmount> production, Set<AEKey> unrepresentableProduction,
            IPatternDetails details, PlanAmount firings
    ) {
        var outputs = details.getOutputs();
        if (outputs == null) {
            return;
        }
        for (GenericStack output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0L) {
                continue;
            }
            // 每发件数 × 宽域发数：这一乘全程精确，不做"能不能塞进 long"这一问
            // （发数出口见 {@link #productionFirings}；老口径在这里用
            // {@code SlotAccount.saturatingMultiply}，越界那一档交回的就是哨兵而不是量）
            mergeProduction(production, unrepresentableProduction, output.what(),
                    WideMath.mul(output.amount(), firings));
        }
    }

    /**
     * 贷方账的入账：<b>量一律记，锁另外判</b>——台账 #217 第 2 条要的分家就在这一句。
     *
     * <p><b>量这一腿</b>：合并后的宽域真值原样写进 {@code production}，任何一档都不撤条目。
     * 老口径 {@link #dropAsUntrustworthy} 把"这笔在 {@code long} 域里读不出可信数"写成
     * "这一键没有产出"：{@code releaseable = 产出 − 别人认领} 于是读成 0 ⇒ {@code reserved}
     * 等于整份锁 ⇒ 这一键对每个消费者扣到底——下游正是从这里把该键读成"没人会产"
     * （{@code left} 是宽域真值而 {@code toMake=0} 同时成立那一格）。
     *
     * <p><b>锁这一腿</b>：合并后的量窄化到 {@code long} 就是天花板哨兵的那些键（{@code >=}，
     * 含恰好顶格那一档——{@code long} 出口分不出"恰等于天花板"与"越界"，两个都是同一道读数）
     * 记进 {@link #unrepresentableProduction}：{@link #usableFor} 对它<b>不下</b>悲观锁、
     * {@link #pendingProductionForLock} 对它<b>不算</b>退路供给，两个方向都是"少一道悲观锁"，
     * 与 {@link #representableFirings} 交回 0、{@link #accumulateFirstCandidateNeed} 里
     * {@code units == Long.MAX_VALUE} 即 {@code continue} 同向。一笔量都不因此消失。
     *
     * <p>顶格的两型（这一笔本身顶格／几笔都装得进 long 而合计顶格）各点名一处、动作只有一个
     * {@code add}：老口径在这两型上写的是两套相反动作（{@code stored == null} 时
     * {@link #merge} 直接 {@code put}，单笔哨兵照样进贷方；多笔撞顶却撤条目），同一本账一笔算
     * 可信、一笔算不可信；宽域复加之后两型落在<b>同一个宽松读数</b>上。
     *
     * <p><b>两个出口从此各在各的行</b>（台账 #217 第 2 条要求点名到位）：
     * <ul>
     *   <li><b>量</b>：{@link #pendingProductionExact}——发数出口 {@link #productionFirings}
     *       × 每发件数在宽域上乘（{@code build} 第一趟那趟宽域累加），
     *       {@code mergeProduction} 里顶格第一／第二型那两支都只记名单、不动这一本账；</li>
     *   <li><b>锁</b>：{@link #pendingProductionForLock} 交 {@code long} 域锁算式用的窄化
     *       读数，读点是 {@link #canAvoidThisKey} 与 {@link #reservedForExclusiveConsumers}
     *       两处；名单出口 {@link #productionHasNoTrustworthyReading}；
     *       发数出口 {@link #representableFirings}（{@code build} 第二趟那趟窄域锁账）与
     *       {@link #accumulateFirstCandidateNeed}／{@link #ownFirstCandidateNeed} 里
     *       {@code units == Long.MAX_VALUE} 那两支都只撤锁。</li>
     * </ul>
     */
    private static void mergeProduction(Map<AEKey, PlanAmount> production,
            Set<AEKey> unrepresentableProduction, AEKey key, PlanAmount credit
    ) {
        if (credit == null || credit.signum() <= 0L) {
            return;
        }
        PlanAmount stored = production.get(key);
        if (stored == null) {
            production.put(key, credit);
            // 顶格第一型：这一笔本身就撞上天花板（老口径 `amount == Long.MAX_VALUE` 那一支）
            if (credit.compareTo(LONG_MAX_AMOUNT) >= 0) {
                unrepresentableProduction.add(key);
            }
            return;
        }
        PlanAmount merged = stored.add(credit);
        production.put(key, merged);
        // 顶格第二型：几笔都装得进 long、合计撞上天花板（老口径 merge 越界那一支）
        if (merged.compareTo(LONG_MAX_AMOUNT) >= 0) {
            unrepresentableProduction.add(key);
        }
    }

    /**
     * <b>锁那一腿</b>读这一键的贷方量：窄化到 {@code long} 之后才能进
     * {@link #canAvoidThisKey} 的退路判据与 {@link #reservedForExclusiveConsumers} 的减法。
     *
     * <p>名单里的键交回 {@code 0}——这一句说的只是"这一键在 {@code long} 域里没有可信的
     * 锁读数"，<b>不是</b>"这一键没有产出"：量恒在 {@link #pendingProductionExact} 那本账上，
     * 两个出口读同一本账（台账 #217 第 2 条）。把名单里的键算成一份供给会把"可退料"判成成立，
     * 反而给来伸手的槽加上一道锁，方向反了。
     *
     * <p>不在名单里的键，其量恒 {@code < long}（{@link #mergeProduction} 同一道 {@code >=} 判据），
     * 所以这里的窄化对它们恒等——正常（{@code <= long}）订单的占料锁读数与算式一个数都没动。
     */
    private long pendingProductionForLock(@Nullable AEKey key) {
        if (key == null || unrepresentableProduction.contains(key)) {
            return 0L;
        }
        return Ae2Amounts.toLong(pendingProduction.getOrDefault(key, PlanAmount.ZERO));
    }

    /**
     * <b>纯读数</b>（量的唯一出口）：这本贷方账在这一键上的<b>宽域真值</b>——
     * 计划还要产几枚就是几枚，<b>不</b>因"塞不进 {@code long}"或"占料锁撤了"而少一笔。
     *
     * <p>它是 {@link #pendingProductionForLock}（锁那一腿的窄化读数）的分家对象：同一个事实
     * 各有出口，撤锁的理由从此进不了量的账。不参与任何判据，只交原文。
     */
    PlanAmount pendingProductionExact(@Nullable AEKey key) {
        if (key == null) {
            return PlanAmount.ZERO;
        }
        return pendingProduction.getOrDefault(key, PlanAmount.ZERO);
    }

    /**
     * 两本 {@code long} 域<b>负债</b>账给不出可信口径时的出口：撤掉该键条目并记进名单。
     *
     * <p><b>这一句只属于负债账</b>（占料锁、首位认领）：条目本身就是那道悲观锁，撤条目＝放松锁，
     * 名单保证撤过的键不被晚来的半截账记回（半截账照样是哨兵冒充量）。
     *
     * <p>产出那本<b>贷方</b>账从此不走这一句——它的条目是"还会产几枚"，撤掉就是把量抹成 0
     * （台账 #217 第 2 条分家掉的那一次撤销）；贷方账的"锁不可信"只记名单、不动账，
     * 见 {@link #mergeProduction} 与 {@link #pendingProductionForLock}。
     */
    private static void dropAsUntrustworthy(Map<AEKey, Long> map, Set<AEKey> untrusted, AEKey key) {
        map.remove(key);
        untrusted.add(key);
    }

    private static void accumulateFirstCandidateNeed(
            Map<AEKey, Long> need, Set<AEKey> revokedNeed,
            Map<AEKey, Long> demand, Set<AEKey> revokedDemand,
            IPatternDetails details, long firings
    ) {
        var slots = details.getInputs();
        if (slots == null) {
            return;
        }
        for (IPatternDetails.IInput slot : slots) {
            GenericStack first = SlotAccount.firstCandidate(slot);
            if (first == null) {
                continue;
            }
            long units = SlotAccount.chargedUnits(slot, first, firings);
            if (isSaturatingSentinel(units)) {
                // 撤的是这一格的锁（占料锁与首位认领两条累加都不进），量在另一个出口，见 helper
                continue;
            }
            merge(demand, revokedDemand, first.what(), units);
            if (!hasSiblingKey(slot, first.what())) {
                merge(need, revokedNeed, first.what(), units);
            }
        }
    }

    /** 这一格是不是只有一条路：候选表里除该键外没有别的键（结构判定，不问当下有没有量）。 */
    private static boolean hasSiblingKey(@Nullable IPatternDetails.IInput slot, AEKey key) {
        return SlotAccount.hasAlternative(slot, key, ignored -> 1L);
    }

    /** 这一槽改不改得了道：候选表里除该键外，还有键在计划供给（未派发产出∪池子）里有量。 */
    private boolean canAvoidThisKey(@Nullable IPatternDetails.IInput slot, AEKey key) {
        return SlotAccount.hasAlternative(slot, key, candidate -> ClampedMath.addUp(
                pendingProductionForLock(candidate),
                pool.getOrDefault(candidate, 0L)));
    }

    /** 别人唯一吃得下的这一格要留在池子里的量：先拿未派发产出抵，再还回锁主自己那份。 */
    private long reservedForExclusiveConsumers(AEKey key, long locked,
            @Nullable IPatternDetails self, long selfFirings) {
        long ownClaim = ownFirstCandidateNeed(self, selfFirings, key, false);
        long claimedByOthers = saturatingSubtract(
                firstCandidateDemand.getOrDefault(key, 0L), ownClaim);
        long releaseable = saturatingSubtract(
                pendingProductionForLock(key), claimedByOthers);
        long reserved = saturatingSubtract(locked, releaseable);
        return saturatingSubtract(reserved,
                ownFirstCandidateNeed(self, selfFirings, key, true));
    }

    /**
     * {@code self} 自己按同一口径要走的量，不能算成别人的账。
     *
     * @param lockedOnly true 只数无退路的槽（归还占料锁），false 数全部首位候选为该键的槽（归还认领）
     */
    private long ownFirstCandidateNeed(
            @Nullable IPatternDetails self, long firings, AEKey key, boolean lockedOnly
    ) {
        if (self == null || firings <= 0L || self.getInputs() == null) {
            return 0L;
        }
        long total = 0L;
        for (IPatternDetails.IInput slot : self.getInputs()) {
            GenericStack first = SlotAccount.firstCandidate(slot);
            if (first == null || !key.equals(first.what())) {
                continue;
            }
            if (lockedOnly && hasSiblingKey(slot, key)) {
                continue;
            }
            long units = SlotAccount.chargedUnits(slot, first, firings);
            if (isSaturatingSentinel(units)) {
                // 归还账也是锁那一腿：同一张样板的贷方量在 pendingProductionExact 那个出口一分不少
                continue;
            }
            total = ClampedMath.addUp(total, units);
        }
        return total;
    }

    /**
     * {@link SlotAccount#chargedUnits} 顶到 {@code Long.MAX_VALUE} 就是<b>饱和哨兵不是量</b>——
     * 按每发件数 × 剩余发数已经装不进 {@code long} 的占料，等于把整池同键料判成"谁也取不动"
     * （实机 19:08 那一跑）。
     *
     * <p>这一句只有这里一份写法，两个读点共用：{@link #accumulateFirstCandidateNeed}
     * （占料锁与首位认领两条累加都不进）与 {@link #ownFirstCandidateNeed}（归还账，同样属锁那一腿）。
     * 撤的都是<b>锁</b>；同一张样板计划产的那几枚是另一本账，由 {@link #productionFirings} 那一趟
     * 按宽域真值入账，一个数都不因为这里跳过而消失（台账 #217 第 2 条：撤锁的理由不许顺带撤量）。
     */
    private static boolean isSaturatingSentinel(long units) {
        return units == Long.MAX_VALUE;
    }

    /**
     * 两本 {@code long} 域<b>锁</b>账（占料锁、首位认领）的逐键合并记账：放不下可信口径就不记账。
     *
     * <p>单项越界早有豁免（{@link #accumulateFirstCandidateNeed} 里 {@code units ==
     * Long.MAX_VALUE} 那一支），但<b>多笔都装得进 long、合计装不进</b>时老口径照样钉成
     * {@code Long.MAX_VALUE}——账本本身就成了哨兵，下游 {@link #usableFor} 拿池子读数去减它，
     * 该键对每位消费者恒 {@code usable=0}（实机 {@code locked=[minecraft:lava
     * stored=9223372036854775807 usable=0]}、池子 9.2E 枚谁也取不动、欠账永冻）。
     *
     * <p>撞顶就<b>撤销该键在这一本账里的条目</b>并记进 {@code revoked}：两本<b>负债</b>账
     * （占料锁、首位认领）撤账与单项豁免同方向——宁可少一道悲观预留，也不把"锁主自己几万轮
     * 才吃得下"的那一截当当场可兑现的债权；名单保证撤过的键不再被晚来的半截账重新记上
     * （半截账又是假口径）。<b>产出</b>那本是贷方、是<b>量</b>，同一个动作在它身上会<b>收紧</b>锁
     * 还把量抹成 0，所以它一个字都不走这一句：宽域复加、条目只进不出，见 {@link #mergeProduction}。
     */
    private static void merge(Map<AEKey, Long> map, Set<AEKey> revoked, AEKey key, long amount) {
        if (revoked.contains(key)) {
            return;
        }
        Long stored = map.get(key);
        if (stored == null) {
            map.put(key, amount);
            return;
        }
        if (amount > 0L && stored > Long.MAX_VALUE - amount) {
            dropAsUntrustworthy(map, revoked, key);
            return;
        }
        map.put(key, stored + amount);
    }

    private static long saturatingSubtract(long left, long right) {
        return right <= 0L ? left : Math.max(0L, left - right);
    }

}
