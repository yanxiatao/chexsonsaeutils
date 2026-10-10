package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import com.mojang.serialization.MapCodec;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环种留底打在<b>取料点</b>上：配给那条早退也要吃这一份扣减。
 *
 * <p>实机（lane 48739137，{@code ae2:creative_storage_cell x 10000}）那一跑的形状：
 * 规划侧 {@code [chexsonsaeutils-retention] …template: seed=1 projected=1 kept} 真把种留在了池子里，
 * 执行侧却没人扣住它——锻台样板在种键上<b>有配给</b>（{@code quotaBlind=[… quota=479992]}），
 * 于是走 {@code ParallelPlanReservation#usableFor} 的 {@code min(池内=2, 配给=479992)=2} 那条早退，
 * 两枚种一次被抽走（{@code taken=3}＝增殖腿 1 发 + 锻台腿 2 发，{@code ann=2 ret=2}＝增殖只跑过 1 发），
 * 池 1 + 产 2 − 吃 3 = 0，增殖腿从此 {@code 每发=1,池内=0,网络sim=0} 永久饿死。
 *
 * <p>本文件的判据只有两条，都钉在"失败发生在哪一步"：
 * <ol>
 *   <li>配给份额还很足、但环自己欠着 5 发没跑 ⇒ 旁人这一轮对种键的可花量是 <b>0</b>
 *       （夹后 {@code spendable = 1 − reserve(增殖腿剩余) < 1}），{@code prepare} 一发也推不出去；
 *       增殖样板自己照常能推（豁免名单就是 {@code RingSeedLedger} 交出的那个环分量成员）。</li>
 *   <li>环跑完（剩余发数 0）⇒ 这一格自动没有 ⇒ 同一份薄池、同一张旁人样板立刻能拿到那颗种。
 *       这一条防的是把夹子写成恒夹。</li>
 * </ol>
 *
 * <p>自循环环之外还有 {@code RingSeedLedger} 类注释写明的<b>链式环</b>（A 吃 K 出 L、B 吃 L 出 K）：
 * 那里没有任何样板把物料还给自己，{@code refundsKey(B, K)} 为真而 {@code inputAmount(B, K)=0}，
 * 拿"还回 K"当基准就得到 {@code owed(K)=0} ⇒ 整族不进账 ⇒ 那颗种在取料点上照样被旁人一次抽干。
 * 下面"链式判据"那七条钉的就是这一型：基准是 <b>K 所在环分量自己对 K 的剩余需求</b>
 * （{@code owed(K) = Σ 分量成员每发吃掉 K 的量 × 它的剩余发数}），豁免名单就是那个分量的成员。
 *
 * <p>链式判据 4/5/6 钉的是<b>净吃种那一档</b>（{@code 0 < ret < need}：A 欠 5 发、B 只欠 4 发，
 * 这一环本来就吃掉那一枚种，缺口由规划期起步提取补）：账本那侧<b>不再有"分量还不还得回来"的数值资格
 * 那一格</b>（旧口径 {@code ret >= need} 在这一档整键撤账 ⇒ 池里那枚种在环转起来之前被旁人一发抽干；
 * 降成 {@code ret > 0} 之后那一支按 SCC 定义不可达，连同恒 0 的读数 {@code keysNotRepaying} 一起删了），
 * 留底<b>取真实缺口</b> {@code owed − handedBack}；{@code ret >= need} 那一档仍是整份剩余需求。
 * 判据 5/6 是反向守门，钉的是"还种那一腿发数归零 ⇒ 整张被 {@code RingSeedLedger#rebuild} 筛出图外
 * ⇒ 这一枚键与吃它的样板不再同分量 ⇒ 不出账"那一档筛图。判据 7 钉的是在册名单与留底
 * <b>随发数每轮现算</b>（条数不变也要入册）。
 *
 * <p>下面"宽域那一档"那三条钉的是 {@code > long} 的订单：留底那两笔复加与账本的筛图都在宽域上做。
 * 份数越出 {@code long} 不是免掉留底的条件（否则量级一大就不守种），而<b>净吃种</b>
 * 那一型（{@code 0 < ret < need}）在宽域里<b>照样在册</b>、留底只锁真实缺口 {@code need - ret}
 * （旧口径整键撤账 ⇒ 那一枚种在环转起来之前被旁人抽干）。
 *
 * <p>下面"义务那一档"那两条钉的是<b>申报之后只剩挂账</b>的那一张：申报那一刻
 * {@code progress.consumeFirings(acceptedFirings)} 已把这 {@code k} 发从剩余发数里扣掉，而
 * {@code job.batchObligations.incur(details, accepted - 1)} 挂下的那 {@code k-1} 发材料将来仍由同一张
 * 样板从池子里取（{@code ParallelBatchExtraction#payDeclaredObligation} → {@code usableFor}）。
 * 留底基准与账本筛图用的发数因此是<b>剩余发数 + 还没交割的义务发数</b>
 * （{@code RingSeedLedger#outstandingFirings} 那一句）：只看剩余发数会把这一张整张筛出 SCC 图外，
 * 它既不进旁人的 {@code owed}（旁人夹得不够），也不进成员名单（它自己失去豁免、回来付欠账时被
 * 同族留底夹成 0 ⇒ 已收产物的欠账永远交割不掉）。反向守门那条钉的是<b>交割干净之后</b>两头都要出局。
 *
 * <p>下面"同族两个精确键那一档"那三条钉的是<b>族是查表口径、记账与豁免按精确键各自的分量交</b>：
 * 分量口径从前按 {@code seed.getPrimaryKey()} 建档，同族两个精确键（同一物品的两个带组件变体）并到
 * 同一张族格上时，每发消耗走的是 {@code Math::max} 而不是相加，两个不同分量的成员名单也被并成
 * <b>一份</b>免扣名单。两样都是少算：①一张样板一发同时吃同族两枚键各 1 枚、两枚各自过资格时，
 * 族格上只留下 1 枚那份 ⇒ 旁人从池里多抽走第二枚；②A 分量的成员伸手拿 B 分量那份口粮时也被整体放行
 * ⇒ B 能被 A 抽干。留底因此按精确键一份份记（族条目名下 {@code seedKeys()} 一条一枚），
 * 夹池子时把 {@code self} <b>不在其中的那些分量</b>复加。守门那条（只有一枚过资格）钉的是这一份复加
 * 不许把没过资格的那一格也算进去——写成恒夹就把还能推的那一发一起锁死了。
 *
 * <p>接缝是真接缝：账本由 {@link ParallelPlanReservation#build} 现算（分量口径取作业自己的
 * {@code job.ringSeeds.seedDemandsByFamily()}），取料点走 {@link ParallelBatchExtraction#prepare}
 * 的holder 段与单发回落段两条腿。夹具沿用 {@code ParallelFiringsPlanOwnsTotalTest} 的
 * {@code FakePattern} 口径，本文件不起第二套。
 *
 * <p>下面"折发数与网络回补那两条腿"四条钉的是<b>留底不止长在池子取料口上</b>：
 * {@code usableFor} 守得住"从池子里取"，守不住"按发数申报"（
 * {@code ParallelCraftingCpuLogic#clampByRemainingQuota}——宿主按机器容量自己定发数，这条腿压根
 * 不经过取料点）与"按缺口回补"（{@code ParallelBatchExtraction#refillObligationBatch}——旁人申报批
 * 那 {@code k-1} 截料经网络搬进本 lane 池子）。实机 {@code 1e11} 那一跑（lane {@code 96d7b67a}，
 * {@code ae2:creative_storage_cell x 100000000000}）就是这么死的：留种判决行是好的
 * （{@code template: seed=1 projected=1 kept}）、取料点上的留底读数也是对的，种还是在环转起来
 * 之前被别的样板按这两条腿吃完，增殖腿从此 {@code 每发=1,池内=0,网络sim=0}。两条腿读的都是留底那
 * <b>一个</b>读口（{@code ParallelPlanReservation#spendableAfterRingSeedReserve}），
 * {@code owed} 与上面那一批用例同源，这里不另算一截。
 *
 * <p>下面"折发数那条腿上的三处"那四条钉的是这一条腿自己算式里的三处口径分叉：
 * ①旧算法把<b>各枚精确键</b>的可花量按族<b>相加</b>再除族每发合计——读口交回的每一枚都已经扣过
 * <b>整族</b> {@code owed}，复加就是同一截留底扣 N 次，而那道除法还让同族富余那一枚去补被扣空那一枚
 * 的亏空（{@code (0+50)/2 = 25} 发，可 A 那一格在取料点上就是 0 枚）⇒ 改成按精确键各除各的每发量、
 * 商取最小，与 {@code usableFor} 一格一键一次读数同尺；②读池直接拿声明键读现量、没过池账口径
 * （{@code ParallelBatchExtraction#exactStored}）——宿主自报清单里那一份实例与池子里那一份
 * {@code equals} 相等而主键引用不同（第三方自定义键那一型），按引用分格的主表读出来恒 0 ⇒
 * 这一条腿恒夹；③留底那一刀排在 {@code quotaExact != null} 之后 ⇒ 不在配给表里／原样退回的模具槽
 * 那一型压根不读留底，而取料点是没有配额口径照样夹 ⇒ 留底拆出来独立施夹（与网络回补那条腿同形状）。
 * 反向那三半同样钉着：族两枚键各 10 枚而 owed 只有 5 枚这一档改前改后同一个数（族可互换那一口径
 * 交出的"7 发"物理上付不出第 6 发的 A，不成立），环分量的成员自己走这条腿不被夹，
 * 环跑完 {@code owed} 归零后同一份薄池照常放行。
 */
final class ParallelRingSeedReserveAtExtractionTest {

    /** 环种：增殖样板每发吃 1 枚、产 2 枚（净 +1），旁人吃掉就不回来。 */
    private static final AEItemKey SEED = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    /** 旁人的产物：不回种、也不喂给环上的任何待办 producer。 */
    private static final AEItemKey OUTSIDE_PRODUCT = AEItemKey.of(Items.BARREL);

    /** 环成员：一发吃 1 枚种、产 2 枚种。 */
    private static final FakePattern RING_DOUBLER = new FakePattern(
            AEItemKey.of(Items.SMITHING_TABLE),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(SEED, 2L)));

    /** 外面吃的：一发吃 1 枚种、产别的键，种键上有配给（本 bug 的形状：配给走早退那条）。 */
    private static final FakePattern OUTSIDE_CONSUMER = new FakePattern(
            AEItemKey.of(Items.ANVIL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

    /** 旁人配给里的份额：远大于池里那一枚，为的是把"夹到 0"这件事钉在留底那一格、不是配额用尽。 */
    private static final long OUTSIDE_QUOTA = 479994L;
    /** 环成员自己的配给份额（实机两腿都在配给表里）。 */
    private static final long RING_QUOTA = 959986L;
    /** 增殖腿还没跑的发数：留底按"剩余发数 × 每发吃掉几枚"记。 */
    private static final long RING_REMAINING_FIRINGS = 5L;

    // ---------- 链式环那一档：A 吃 K 出 L、B 吃 L 出 K，没有任何样板把物料还给自己 ----------

    /** 链上的中转那枚 L：只有 A 产它、只有 B 吃它。 */
    private static final AEItemKey LINK = AEItemKey.of(Items.REDSTONE);

    /** 链式环 A：每发吃 1 枚种 K、产 1 枚中转 L。它自己不还 K——旧口径 owed(K)=0 就死在这一格。 */
    private static final FakePattern CHAIN_EMITTER = new FakePattern(
            AEItemKey.of(Items.REPEATER),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(LINK, 1L)));

    /** 链式环 B：每发吃 1 枚中转 L、产回 1 枚种 K（{@code inputAmount(B, K) = 0} 的那一张）。 */
    private static final FakePattern CHAIN_REFUNDER = new FakePattern(
            AEItemKey.of(Items.COMPARATOR),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(LINK, 1L)}, 1L, null, Set.of(LINK))},
            List.of(new GenericStack(SEED, 1L)));

    /** 链两侧各欠 2 发 ⇒ 分量对 K 的剩余需求 = 2 × 1 = 2 枚，比池里那 1 枚多。 */
    private static final long CHAIN_EMITTER_FIRINGS = 2L;
    private static final long CHAIN_REFUNDER_FIRINGS = 2L;
    private static final long CHAIN_OUTSIDE_FIRINGS = 3L;

    // ---------- 宽域那一档：> long 的订单也要守种，净吃种那一型照样在册 ----------

    /** 越出 {@code long} 的剩余发数（2^64）：用户要的那一档"真正的无上限"落在这一量级上。 */
    private static final BigInteger OVER_LONG_FIRINGS = new BigInteger("18446744073709551616");

    /** 净吃种那一腿：一发吃 2 枚种、只还 1 枚。它欠的发数越大，这一环净吃掉的那一枚越多。 */
    private static final FakePattern NET_CONSUMER = new FakePattern(
            AEItemKey.of(Items.BREWING_STAND),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 2L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(SEED, 1L)));

    /** 补种那一腿：一发吃 1 枚种、还 3 枚。发数悬殊时它补不上 {@link #NET_CONSUMER} 那份净消耗。 */
    private static final FakePattern OVER_REFUNDER = new FakePattern(
            AEItemKey.of(Items.CAULDRON),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(SEED, 3L)));

    /** {@code long} 域顶点那一档用的腿：一发吃 2 枚还 2 枚（净 0），留底靠发数被推到 {@code 2×long} 域顶点。 */
    private static final FakePattern SEED_KEEPER = new FakePattern(
            AEItemKey.of(Items.DROPPER),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 2L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(SEED, 2L)));

    // ---------- 义务那一档：申报把剩余发数扣干之后，还没交割的那 k-1 发仍是这一张要吃掉的量 ----------

    /** 欠账那一张的产物：谁也不吃它，免得把旁人 {@link #OUTSIDE_CONSUMER} 也拽进同一个分量。 */
    private static final AEItemKey DEBT_PRODUCT = AEItemKey.of(Items.HOPPER);

    /**
     * 欠账那一张：一发吃 1 枚种、还 1 枚种（自己就是种键那一环的一环）。
     * 申报那一刻 {@code consumeFirings} 把它的剩余发数扣成 0，义务账上还挂着 {@code k-1} 发的材料要从池里取。
     */
    private static final FakePattern DECLARED_DEBTOR = new FakePattern(
            AEItemKey.of(Items.SMOKER),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(SEED, 1L), new GenericStack(DEBT_PRODUCT, 1L)));

    /** 申报了 4 发、原型只结清 1 发 ⇒ 义务账上挂着 3 发没交割。 */
    private static final long DECLARED_OBLIGATION_FIRINGS = 3L;

    // ---------- 同族两个精确键那一档：族是查表口径、记账与豁免按精确键各自的分量交 ----------

    /**
     * 同一件物品的两个<b>精确</b>变体（带组件）：两枚是 {@code AEKey} 上两个不同的键，
     * {@code getPrimaryKey()} 上是同一个物品本体族——正是"族格上只留下 max、另一枚不记账"那一档的形状。
     */
    private static final AEItemKey TWIN_VARIANT_A = namedVariant(Items.IRON_INGOT, "twin-seed-a");
    private static final AEItemKey TWIN_VARIANT_B = namedVariant(Items.IRON_INGOT, "twin-seed-b");

    /**
     * 一发同时吃同族<b>两枚</b>精确键各 1 枚、各还 1 枚：两枚各自都被这张还在图里的样板吃掉，
     * 各自在同一个分量里。族留底必须是两枚之和（{@code 2 × 发数}），取最大就少算另一枚那一份。
     */
    private static final FakePattern TWIN_SEED_DOUPLER = new FakePattern(
            AEItemKey.of(Items.LOOM),
            new FakeInput[]{
                    new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)},
                            1L, null, Set.of(TWIN_VARIANT_A)),
                    new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)},
                            1L, null, Set.of(TWIN_VARIANT_B))},
            List.of(new GenericStack(TWIN_VARIANT_A, 1L), new GenericStack(TWIN_VARIANT_B, 1L)));

    /** 这一档的旁人：一发吃 1 枚 {@link #TWIN_VARIANT_A}，种键上有充足配给（走配给早退那一档的形状）。 */
    private static final FakePattern TWIN_OUTSIDE_CONSUMER = new FakePattern(
            AEItemKey.of(Items.GRINDSTONE),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)}, 1L, null,
                    Set.of(TWIN_VARIANT_A))},
            List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

    /** 同族两枚精确键各欠的发数：族留底合计 {@code 2 × (1 + 1) = 4} 枚，旧口径只留下 {@code 2} 枚。 */
    private static final long TWIN_DOUPLER_FIRINGS = 2L;
    private static final long TWIN_OUTSIDE_FIRINGS = 3L;
    /** 这一档的池量：3 枚 &lt; 4 枚族留底 ⇒ 旁人对种键的可花量是 0；旧口径交出 3−2=1。 */
    private static final long TWIN_POOL_AMOUNT = 3L;

    // ---------- 同族两个分量那一档：豁免名单按分量交，A 的成员不免扣 B 那份口粮 ----------

    /**
     * 分量 A 那一张：一发吃 1 枚 {@link #TWIN_VARIANT_A}（还 2 枚，净正在 A 上）<b>又吃 1 枚 B</b>。
     * 它吃 B 的那一格落在 B 那个分量之外 ⇒ 对 B 的账不计，可它照样是把 B 往外抽的那一张。
     */
    private static final FakePattern TWIN_COMPONENT_A = new FakePattern(
            AEItemKey.of(Items.STONECUTTER),
            new FakeInput[]{
                    new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)},
                            1L, null, Set.of(TWIN_VARIANT_A)),
                    new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)},
                            1L, null, Set.of(TWIN_VARIANT_B))},
            List.of(new GenericStack(TWIN_VARIANT_A, 2L)));

    /** 分量 B 那一张：一发吃 1 枚 B、还 1 枚 B（自己就是 B 那一环的一环，与 A 那一张不成环）。 */
    private static final FakePattern TWIN_COMPONENT_B = new FakePattern(
            AEItemKey.of(Items.CARTOGRAPHY_TABLE),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)}, 1L, null,
                    Set.of(TWIN_VARIANT_B))},
            List.of(new GenericStack(TWIN_VARIANT_B, 1L)));

    /** A 分量欠 2 发（族留底 2 枚）、B 分量欠 1 发（族留底 1 枚）：两侧都不是零，互相都该扣到对方那一份。 */
    private static final long COMPONENT_A_FIRINGS = 2L;
    private static final long COMPONENT_B_FIRINGS = 1L;

    // ---------- 折发数那条腿上的三处：族合计贴亏空、读池没过池账、无配额那一档漏夹 ----------

    /**
     * 一发各吃同族<b>两枚</b>键一枚的旁人：A 那一枚才是环欠着的种，B 那一枚是同族的富余变体。
     * 族合计那道除法拿 B 的可花量去补 A 的亏空，就是这一档的用力点。
     */
    private static final FakePattern TWIN_OUTSIDE_BOTH_KEYS = new FakePattern(
            AEItemKey.of(Items.FLETCHING_TABLE),
            new FakeInput[]{
                    new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)}, 1L, null,
                            Set.of(TWIN_VARIANT_B)),
                    new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)}, 1L, null,
                            Set.of(TWIN_VARIANT_A))},
            List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

    /** 环欠着的那一枚（A）池量＝族留底：50 枚全在环的口粮里，可花 0。 */
    private static final long RESERVED_SEED_POOL = 50L;
    /** 同族另一枚（B）池量：100 枚，旧口径拿它替 A 贴账折出 {@code (0+50)/2 = 25} 发。 */
    private static final long RICH_VARIANT_POOL = 100L;
    /** 环欠 50 发×每发 1 枚 A（没有任何样板还 B ⇒ owed 只记在 A 那一格上）。 */
    private static final long TWIN_RESERVE_FIRINGS = 50L;
    /** 宿主自己报上来的发数：远大于两枚键各自折得出的发数。 */
    private static final long TWIN_FOLD_REQUESTED = 40L;

    /** 不在配给表里的旁人：一发吃 1 枚种、产别的键（{@code quotaExact} 交 {@code null} 那一型）。 */
    private static final FakePattern UNRATIONED_OUTSIDE_CONSUMER = new FakePattern(
            AEItemKey.of(Items.BLAST_FURNACE),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
            List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

    /**
     * 第三方自定义键那一档的种：主键是值对象 ⇒ 声明键与池内实例 {@code equals} 相等而分格不同。
     * 形状照 {@code ParallelCustomKeyPoolRulerTest}（Applied Mekanistics 那一型），本文件不起第二套口径。
     */
    private static final AEKey CHEM_SEED_REPORTED = new SeedChemicalKey();
    /** 池子里真躺着的那一份实例。 */
    private static final AEKey CHEM_SEED_IN_POOL = new SeedChemicalKey();

    /** 自定义键那一档的环成员：一发吃 1 枚声明键、产 2 枚（自己就是那一环的一环）。 */
    private static final FakePattern CHEM_RING = new FakePattern(
            AEItemKey.of(Items.BEACON),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(CHEM_SEED_REPORTED, 1L)}, 1L, null,
                    Set.of(CHEM_SEED_REPORTED))},
            List.of(new GenericStack(CHEM_SEED_REPORTED, 2L)));

    /** 自定义键那一档的旁人：一发吃 1 枚<b>声明键那一份实例</b>，种键上有充足配给。 */
    private static final FakePattern CHEM_OUTSIDE_CONSUMER = new FakePattern(
            AEItemKey.of(Items.CAMPFIRE),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(CHEM_SEED_REPORTED, 1L)}, 1L, null,
                    Set.of(CHEM_SEED_REPORTED))},
            List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

    /** 环欠 5 发×每发 1 枚：薄池读成 0 就是恒夹，读到 100 枚才有那 95 枚可花。 */
    private static final long CHEM_RESERVE_FIRINGS = 5L;

    /**
     * 判据 1：种键上配额充足而环还欠着发数 ⇒ 旁人可花量 0、一发推不出去，环自己照常推得出。
     */
    @Test
    void ringSeedReserveClampsTheOtherConsumerOnTheQuotaFastPath() {
        var job = newJob(RING_REMAINING_FIRINGS, 3L);
        var reservation = reservationOf(job);

        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER, seedSlot(OUTSIDE_CONSUMER)),
                "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
        // 旧口径这一格是 min(池内=1, 配给=479994)=1 ⇒ 一发被推出去、那颗种就此回不来
        assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                seedSlot(OUTSIDE_CONSUMER), false),
                "环自己还欠 5 发×每发 1 枚：池里那枚种在环转起来之前不许被旁人抽走");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                        new KeyCounter(), new KeyCounter(), reservation),
                "可花量 0 ⇒ 这一轮旁人一发也推不出去（holder 段与单发回落段都不许绕过留底）");

        assertEquals(1L, reservation.usableFor(SEED, 1L, RING_DOUBLER, 1L,
                seedSlot(RING_DOUBLER), false),
                "还回这一枚种的环成员自己不受留底这一格扣，否则环永远起步不了");
        var ringBatch = ParallelBatchExtraction.prepare(RING_DOUBLER, pool(), null,
                RING_REMAINING_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(ringBatch, "池里那颗种得留给环自己起步：增殖腿照常能推");
        assertEquals(1L, ringBatch.executions(), "池里只一枚种、每发吃一枚 ⇒ 本轮推 1 发");
    }

    /**
     * 判据 2：环跑完 ⇒ 留底那一格自动没有 ⇒ 同一份薄池、同一张旁人样板立刻拿到那颗种。
     */
    @Test
    void reserveDisappearsOnceTheRingHasNoFiringsLeft() {
        var job = newJob(0L, 3L);
        var reservation = reservationOf(job);

        assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "增殖腿已无剩余发数：没有环要留的那一截了，旁人照旧按份额取");
        var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(batch, "环跑完了还把种锁着，就是把夹子写成了恒夹");
        assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
    }

    /**
     * 单发让路那一刀在自有并行 CPU 这一条路上<b>没有独有的覆盖面</b>：它挡的那一格由留底原样挡着。
     *
     * <p>两头都要有数，才算"吞掉"而不是"没人在场"：
     * <ol>
     *   <li>旧那一刀真会跳的那一档（池里只剩那一枚种、契约留底 1 枚）——不用它，
     *       {@code usableFor} 把这一键夹到 0，{@code prepare} 整批发不出去；</li>
     *   <li>旧那一刀<b>放行</b>的那一档（池里两枚、它只算一发）——留底照样夹到 0。
     *       这一档就是实机 {@code taken=3} 那一次的形状：旁人一批吃两枚，单发口径看不见第二枚，
     *       旧那一刀当时点头放行，种还是被一次抽干。</li>
     * </ol>
     *
     * <p>反向那一半也钉住：环成员在两把尺上同判放行（它自己还回这一枚种），
     * 删掉让路那一刀不会把环自己挡在料外。
     */
    @Test
    void singleFiringYieldAddsNothingOnTopOfTheSeedReserve() {
        var job = newJob(RING_REMAINING_FIRINGS, 3L);
        var reservation = reservationOf(job);
        var contractReserve = contractReserve(1L);

        // 档①：旧那一刀真跳的那一档
        var thinPool = pool();
        assertTrue(RecursiveTaskOrdering.hasPendingProducer(job.tasks, contractReserve),
                "前提读数：增殖腿还欠 5 发，让路判据的总闸在这一档是开着的");
        assertTrue(RecursiveTaskOrdering.shouldDeferConsumer(
                        OUTSIDE_CONSUMER, contractReserve, thinPool, job.tasks),
                "前提读数：单发让路在池里只剩一枚种这一档确实要跳这一张旁人样板（它挡的就是这一格）");
        assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "同一档留底的读数：环欠 5 发×每发 1 枚，比池里那 1 枚多 ⇒ 旁人对种键的可花量是 0");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, thinPool, null, 3L,
                        new KeyCounter(), new KeyCounter(), reservation),
                "让路那一刀挡的东西，取料点上的留底原样挡着：这一路上它不是唯一那把尺");

        // 档②：旧那一刀放行、而实机真被抽干的那一档（一批吃两枚）
        var twoSeedPool = poolWith(SEED, 2L);
        assertFalse(RecursiveTaskOrdering.shouldDeferConsumer(
                        OUTSIDE_CONSUMER, contractReserve, twoSeedPool, job.tasks),
                "单发口径只算一发：2-1=1 还在留底之上 ⇒ 旧那一刀在这里放行，看不见这一批要吃的第二枚");
        assertEquals(0L, reservation.usableFor(SEED, 2L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "同一档留底的读数：它按整批发数夹，两枚全在环的口粮里");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, twoSeedPool, null, 3L,
                        new KeyCounter(), new KeyCounter(), reservation),
                "旧那一刀漏掉的正是这一次：留底把整批挡下，池里那两枚一枚也不许旁人动");

        // 反向：环成员在两把尺上同判放行——删让路那一刀不会把环自己挡在料外
        assertFalse(RecursiveTaskOrdering.shouldDeferConsumer(
                        RING_DOUBLER, contractReserve, thinPool, job.tasks),
                "增殖样板自己还回这一枚种：让路判据一路放行");
        var ringBatch = ParallelBatchExtraction.prepare(RING_DOUBLER, thinPool, null,
                RING_REMAINING_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(ringBatch, "留底的豁免名单就是环分量成员：它照常拿得到那颗种");
        assertEquals(1L, ringBatch.executions(), "池里只一枚种、每发吃一枚 ⇒ 本轮推 1 发");
    }

    /**
     * 链式判据 1（这一刀的来路）：A 吃 K 出 L、B 吃 L 出 K，两侧都还欠着发数 ⇒
     * 分量对 K 的剩余需求 2 枚 &gt; 池里那 1 枚 ⇒ 旁人 C 对 K 的可花量是 <b>0</b>、一发推不出去。
     */
    @Test
    void chainedRingReserveClampsTheOutsiderAlthoughNobodyRefundsTheSeedToItself() {
        var job = chainJob(CHAIN_EMITTER_FIRINGS, CHAIN_REFUNDER_FIRINGS, CHAIN_OUTSIDE_FIRINGS);
        var reservation = reservationOf(job);

        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                        seedSlot(OUTSIDE_CONSUMER)),
                "前提读数：C 在种键上份额足得很——下面那个 0 不是配额用尽，是留底扣的");
        assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "A 还欠 2 发×每发 1 枚 K：链上没有任何样板把 K 还给自己，这一截照样是环自己的口粮");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                        CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation),
                "可花量 0 ⇒ 这一轮 C 一发也推不出去（holder 段与单发回落段都不许绕过留底）");
    }

    /**
     * 链式判据 2（反向钉，防过夹）：把 A 的剩余发数设 0 ⇒ 分量对 K 的需求为 0
     * ⇒ 同一份薄池里 C 立刻拿到那颗 K。
     */
    @Test
    void chainedRingReserveDisappearsOnceTheEmitterHasNoFiringsLeft() {
        var job = chainJob(0L, CHAIN_REFUNDER_FIRINGS, CHAIN_OUTSIDE_FIRINGS);
        var reservation = reservationOf(job);

        assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "A 已无剩余发数：分量再没有要留给自己的那一截 K，C 照旧按份额取");
        var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(batch, "环已经转不动了还把 K 锁着，就是把夹子写成了恒夹");
        assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
    }

    /**
     * 链式判据 3：分量成员自己照常拿得到料——A 拿 K 起步、B 拿 L 转回 K，
     * 不许出现"A 被 B 的需求夹住、B 等 A 的 L"互等死锁。
     */
    @Test
    void chainedRingMembersStillReachTheirOwnSeed() {
        var job = chainJob(CHAIN_EMITTER_FIRINGS, CHAIN_REFUNDER_FIRINGS, CHAIN_OUTSIDE_FIRINGS);
        var reservation = reservationOf(job, Map.of(SEED, 1L, LINK, 1L));

        assertEquals(1L, reservation.usableFor(SEED, 1L, CHAIN_EMITTER, 1L,
                        seedSlot(CHAIN_EMITTER), false),
                "A 是 K 所在分量的成员：留底不许扣它，否则环永远起步不了");
        var emitterBatch = ParallelBatchExtraction.prepare(CHAIN_EMITTER, poolWith(SEED, 1L), null,
                CHAIN_EMITTER_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(emitterBatch, "A 拿不到那颗种，就没人产 L，B 那份 K 永远回不来");
        assertEquals(1L, emitterBatch.executions(), "池里只一枚 K、每发吃一枚 ⇒ 本轮推 1 发");

        var refunderBatch = ParallelBatchExtraction.prepare(CHAIN_REFUNDER, poolWith(LINK, 1L), null,
                CHAIN_REFUNDER_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(refunderBatch, "B 也在同一个分量里：分量自己那份 L 留底不许把它夹成 A 等 B 的 K、B 等 A 的 L");
        assertEquals(1L, refunderBatch.executions(), "池里只一枚 L、每发吃一枚 ⇒ 本轮推 1 发");
    }

    /**
     * 链式判据 4（这一档此前没人守）：链式环<b>本来就净吃那一枚种</b>——A 欠 5 发吃 K、
     * B 欠 4 发还 K ⇒ {@code owed=5 > handedBack=4}，缺口由规划期起步提取补。
     *
     * <p>旧资格判据 {@code ret >= need} 把这一档<b>整键撤账</b> ⇒ 这一族不产留底 ⇒ 池里那枚 K
     * 在环转起来之前被旁人一发送走，而这条环本来还能再转 4 轮跑完（永久饿死）。
     *
     * <p>新口径两格都要有读数：账本那侧不再有数值资格那一格（还它的 B 还在图里 ⇒ K 与吃它的 A
     * 同分量 ⇒ 在册）；留底<b>取真实缺口</b> {@code owed − handedBack = 5 − 4 = 1}，
     * 不是整份剩余需求 5——所以池里 3 枚时旁人还能拿走 {@code 3 - 1 = 2} 枚，而池里那唯一的一枚
     * 一发也拿不走。环成员自己不受这一格扣（那一枚精确键的分量名单里写着 A 与 B）。
     */
    @Test
    void netConsumingChainedRingReservesTheRealGapAndNotTheWholeDemand() {
        var job = chainJob(5L, 4L, CHAIN_OUTSIDE_FIRINGS);
        var reservation = reservationOf(job);

        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                        seedSlot(OUTSIDE_CONSUMER)),
                "前提读数：C 在种键上份额足得很——下面那个 0 不是配额用尽，是留底扣的");
        assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "A 欠 5 发、B 只欠 4 发：这一环本来就净吃那一枚种，池里那 1 枚旁人一发也拿不走");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                        CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation),
                "可花量 0 ⇒ 这一轮 C 一发也推不出去（环优先跑，跑完环再放行）");

        assertTrue(job.ringSeeds.seedDemandsByFamily().containsKey(SEED.getPrimaryKey()),
                "还种的 B 还欠 4 发、还在图里 ⇒ K 与吃它的 A 同分量 ⇒ 这一族必须在册"
                        + "（旧口径按 owed>handedBack 在这里整键撤账）");
        assertEquals(2L, reservationOf(job, Map.of(SEED, 3L)).usableFor(SEED, 3L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "留底取的是真实缺口 need−ret = 5−4 = 1，不是整份剩余需求 5：池里 3 枚时 C 还能拿走 2 枚");

        assertEquals(1L, reservation.usableFor(SEED, 1L, CHAIN_EMITTER, 1L,
                        seedSlot(CHAIN_EMITTER), false),
                "环成员自己不受这一格扣：那一枚 K 就在 A→L→B→K 里循环");
    }

    /**
     * 链式判据 5（守门，防"还种腿一发不欠也照锁"）：还种那一腿 B 一发都不欠 ⇒ 它被 {@code rebuild}
     * 的筛图整张出图 ⇒ 没有任何还在图里的样板把 K 还回来 ⇒ K 与吃它的 A <b>不成分量</b> ⇒
     * 这一族不进账 ⇒ 同一份薄池旁人照常取走。
     *
     * <p><b>这一条钉的是筛图那一档，不是一道数值对账</b>：账本里"分量一枚都交不出来（{@code ret = 0}）
     * 就撤账"那一支按 SCC 定义<b>不可达</b>（键与吃它的样板同分量 ⇒ 分量内必有一张还在图里、
     * 发数 > 0 的样板还它），已随恒 0 的读数 {@code keysNotRepaying} 一起删掉。
     */
    @Test
    void chainedRingHandingBackNothingKeepsTheSeedOutOfTheLedger() {
        var job = chainJob(CHAIN_EMITTER_FIRINGS, 0L, CHAIN_OUTSIDE_FIRINGS);

        assertFalse(job.ringSeeds.seedDemandsByFamily().containsKey(SEED.getPrimaryKey()),
                "还种的 B 一发不欠 ⇒ 整张被筛出图外 ⇒ K 与吃它的 A 不再同分量，这一档不许当环种");
        var reservation = reservationOf(job);
        assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "还不回来一粒的族不产留底：薄池里那枚 K 照常放给旁人");
        var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(batch, "分量一枚都交不出来还把种锁着，就是把夹子写成了恒夹");
        assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
    }

    /**
     * 链式判据 6（"环跑完自动放行"打在净吃种这一档上，反向守门防恒夹）：吃种的 A 跑到 0 发
     * ⇒ 它整张被筛出图外 ⇒ K 出分量、{@code owed} 那笔复加也归零 ⇒ 留底没有 ⇒
     * 同一份薄池里旁人立刻拿到那枚 K。
     *
     * <p>本条在旧口径（{@code ret >= need} 撤账、留底取整份 {@code owed}）下同样为绿：
     * 它是<b>防恒夹</b>的守门判据，不是这一刀的回归网。
     */
    @Test
    void netConsumingChainedRingReleasesTheSeedOnceTheEmitterIsDone() {
        var job = chainJob(0L, 4L, CHAIN_OUTSIDE_FIRINGS);
        var reservation = reservationOf(job);

        assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "A 的 5 发跑到 0：它出图、分量对 K 的剩余需求复加成 0 ⇒ 缺口那一截也没有 ⇒ 放行旁人");
        var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(batch, "环已经跑完了还把 K 锁着，就是把夹子写成了恒夹");
        assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
    }

    /**
     * 链式判据 7（在册名单与留底<b>随发数每轮现算</b>）：任务<b>条数</b>一动不动（B 始终留在表里，
     * 只是剩余发数从 0 抬到 4）⇒ "这一枚精确键在不在册"必须跟着发数变。
     *
     * <p>旧那一格按<b>任务表条数</b>缓存重建（{@code RingSeedLedger} 里那一格本刀已删），册子滞后到
     * "有人出表"那一趟，
     * 而取料点上的留底复加读的是本 tick 的发数 ⇒ {@code 5/0} 跑到 {@code 5/4} 该入册的那一档没人补。
     */
    @Test
    void seedEligibilityFollowsTheFiringsWithinOneTaskCount() {
        var job = chainJob(5L, 0L, CHAIN_OUTSIDE_FIRINGS);
        var before = reservationOf(job);
        assertEquals(1L, before.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "前提读数：B 一发都不欠 ⇒ 它被筛出图外 ⇒ K 与 A 不成分量、这一族不在册，旁人照常取走");

        // 条数不变（三张样板都还在表里），只把 B 的剩余发数经任务表现成的写口抬到 4
        job.tasks.get(CHAIN_REFUNDER).addFirings(PlanAmount.of(4L));

        var after = reservationOf(job);
        assertEquals(0L, after.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "5/4 这一档该入册就入册：按条数缓存的话这里还得等有人出表才夹得住");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                        CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), after),
                "册子跟着发数走 ⇒ 这一轮 C 一发也推不出去");
    }

    /**
     * 宽域判据 1（这一刀的来路）：环成员的剩余发数越出 {@code long}（{@code 2^64}）⇒
     * 留底照旧把池里那颗种扣光，旁人对种键的可花量是 <b>0</b>、{@code prepare} 一发推不出去。
     *
     * <p>旧口径是"任一格越界就 {@code trustworthy=false} ⇒ 整族不进账"：量级一大那颗种的留底直接消失，
     * {@code 9b76b76} 修掉的病原样回来。这一条在 {@code a559fd7} 上必须先红。
     */
    @Test
    void overLongRingFiringsStillReserveTheirSeed() {
        var job = ringJobWithWideFirings(RING_DOUBLER, OVER_LONG_FIRINGS);
        var reservation = reservationOf(job);

        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                        seedSlot(OUTSIDE_CONSUMER)),
                "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
        assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "增殖腿还欠 2^64 发×每发 1 枚：份数越出 long 不是免掉留底的条件");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                        new KeyCounter(), new KeyCounter(), reservation),
                "可花量 0 ⇒ >long 的订单上这一轮旁人也一发推不出去");
    }

    /**
     * 宽域判据 2（口径改过的那一条）：环在宽域里 {@code handedBack < owed}（净吃种）⇒ 这一族
     * <b>照样在册</b>（账本那侧没有数值资格那一格，还它的腿还在图里就入账），
     * 留底取真实缺口 {@code owed - handedBack} 那一截。
     *
     * <p>旧口径在这里把键整键撤账（"还不回来就不算种"）⇒ 这一族不产留底 ⇒ 薄池里那枚种被旁人
     * 一发抽干。{@code 0 < handedBack < owed} 那一档正是 {@code cf74bd4} 删掉"单发让路"之后没人守的形状。
     *
     * <p>两侧复加必须在宽域上做：本例 {@code owed = 2×2^64 + 1×1 = 2^65+1}、
     * {@code handedBack = 1×2^64 + 3×1 = 2^64+3} ⇒ 缺口 {@code 2^64 - 2} 这一截比任何塞得进
     * {@code long} 的池量都大，所以旁人对种键的可花量是 0；分量成员自己在豁免名单里，
     * 照常拿得到起步那一枚。
     */
    @Test
    void wideDomainNetConsumingRingReservesTheRealGap() {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(NET_CONSUMER, wideProgress(OVER_LONG_FIRINGS));
        job.tasks.put(OVER_REFUNDER, progress(1L));
        job.tasks.put(OUTSIDE_CONSUMER, progress(3L));
        MaterialAllocationFixtures.assign(job, NET_CONSUMER, ration(RING_QUOTA));
        MaterialAllocationFixtures.assign(job, OVER_REFUNDER, ration(RING_QUOTA));
        MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));

        // 宽域真值：owed = 2×2^64 + 1×1 = 2^65+1，handedBack = 1×2^64 + 3×1 = 2^64+3 ⇒ 环净吃种
        assertTrue(job.ringSeeds.seedDemandsByFamily().containsKey(SEED.getPrimaryKey()),
                "还种的腿还在图里 ⇒ 这一枚键与吃它的样板同分量 ⇒ 必须在册"
                        + "（旧口径按 owed>handedBack 在这里整键撤账）");
        var reservation = reservationOf(job);
        assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "留底取的是缺口 owed−handedBack = 2^64−2 那一截：比任何 long 池量都大，旁人一枚也拿不走");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                new KeyCounter(), new KeyCounter(), reservation), "这一轮旁人一发也推不出去");
        assertEquals(1L, reservation.usableFor(SEED, 1L, NET_CONSUMER, 1L,
                        seedSlot(NET_CONSUMER), false),
                "净吃种那一腿自己是分量成员：这一格不许把它夹在自己的口粮上");
    }

    /**
     * 宽域判据 3（{@code long} 域顶点那一档的真话）：一发吃 2 枚、欠 {@code Long.MAX_VALUE} 发
     * ⇒ 留底是 {@code 2×Long.MAX_VALUE} 那一档，<b>比任何塞得进 long 的池量都大</b> ⇒
     * 这一键照样产留底（不是"越界就不产"），旁人可花量 0、分量成员自己照常拿得到。
     *
     * <p>饱和镜像与宽域两种口径在这一条上对任何池量都交出同一个 0（差不出），能吃出差别的是
     * 上一条用例那一截<b>缺口</b>读数（镜像把 {@code owed} 与 {@code handedBack} 同时顶到
     * {@code Long.MAX_VALUE}，缺口就读不出来了）；这一条钉的是"整族不进账"那一档与"恒夹"那一档。
     */
    @Test
    void longMaxFiringsTierYieldsAReserveBiggerThanAnyPool() {
        var job = ringJobWithWideFirings(SEED_KEEPER, BigInteger.valueOf(Long.MAX_VALUE));
        var reservation = reservationOf(job);

        assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "每发 2 枚 × 欠 long 域顶点那么多发：这一档产的是大于任何池量的留底，不是不产留底");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                new KeyCounter(), new KeyCounter(), reservation));
        assertEquals(1L, reservation.usableFor(SEED, 1L, SEED_KEEPER, 1L, seedSlot(SEED_KEEPER), false),
                "分量成员自己在豁免名单里：这一格不是恒夹");
    }

    /**
     * 义务判据 1（这一刀的来路，证伪输入）：增殖腿 C 欠 5 发、欠账那一张 A 剩余发数 0 而义务账上挂着
     * 3 发 ⇒ 留底基准是"剩余 + 未交割义务"那一档（{@code RingSeedLedger#outstandingFirings}）。
     *
     * <p>旧口径把 A 整张筛出 SCC 图外（只看 {@code exactRemaining()}），两头一起漏：
     * A 不在豁免名单 ⇒ 池里那枚种对它夹成 0 ⇒ 它已收产物的欠账永远交割不掉（停滞）；
     * A 那 3 发的种量也不进 {@code owed} ⇒ 旁人只被 C 那 5 枚夹住，池里 6 枚时还能再拿走 1 枚。
     */
    @Test
    void declaredObligationFiringsCountIntoTheSeedReserveAndKeepTheDebtorExempt() {
        var job = debtorJob(RING_REMAINING_FIRINGS, 0L, DECLARED_OBLIGATION_FIRINGS);
        var thinPool = reservationOf(job, Map.of(SEED, 1L));

        assertEquals(1L, thinPool.usableFor(SEED, 1L, DECLARED_DEBTOR, 1L,
                        seedSlot(DECLARED_DEBTOR), false),
                "它自己就是种键那一环的成员：挂着 3 发欠账要付，留底不许把它夹成 0");
        var paying = ParallelBatchExtraction.prepare(DECLARED_DEBTOR, pool(), null,
                DECLARED_OBLIGATION_FIRINGS, new KeyCounter(), new KeyCounter(), thinPool);
        assertNotNull(paying, "欠账交割那一腿拿不到料，已收产物的欠账就永远交割不掉（停滞）");
        assertEquals(1L, paying.executions(), "池里只一枚种、每发吃一枚 ⇒ 这一轮交割 1 发");

        // owed 要连 A 那 3 发一起算：5+3=8 枚 > 池里 6 枚；旧口径只夹 5 枚 ⇒ 旁人仍拿走 1 枚
        var sixPool = reservationOf(job, Map.of(SEED, 6L));
        assertEquals(0L, sixPool.usableFor(SEED, 6L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "分量对种键的剩余需求是 C 的 5 发加 A 挂账那 3 发：旁人一枚也不许动");
        assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, poolWith(SEED, 6L), null,
                        3L, new KeyCounter(), new KeyCounter(), sixPool),
                "可花量 0 ⇒ 这一轮旁人一发也推不出去（欠账那一份口粮留得住）");
    }

    /**
     * 义务判据 2（反向守门，防"把义务账写成永挂"）：欠账全部交割完（挂账 0、剩余 0）⇒
     * 这一张既不在 {@code owed} 也不在成员名单里。
     *
     * <p>第一档把增殖腿留着（C 欠 5 发）：池里 6 枚时旁人还能拿走 6−5=1 枚，交割掉的那 3 发不许再计一次。
     * 第二档整条环都跑完且欠账清光 ⇒ 这一族没有留底 ⇒ 同一份薄池旁人照常取走。
     */
    @Test
    void settledObligationsLeaveTheDebtorOutOfBothTheReserveAndTheMemberList() {
        var job = debtorJob(RING_REMAINING_FIRINGS, 0L, DECLARED_OBLIGATION_FIRINGS);
        job.batchObligations.settle(DECLARED_DEBTOR, DECLARED_OBLIGATION_FIRINGS);
        var sixPool = reservationOf(job, Map.of(SEED, 6L));

        assertFalse(job.ringSeeds.seedDemandsByFamily().get(SEED.getPrimaryKey())
                        .members().contains(DECLARED_DEBTOR),
                "挂账已清的这一张不在分量成员名单里：义务账不许写成永挂");
        assertEquals(1L, sixPool.usableFor(SEED, 6L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "owed 只剩增殖腿那 5 发：交割干净的 3 发不再计一次，旁人还能拿走 6−5=1 枚");

        var finished = reservationOf(debtorJob(0L, 0L, 0L), Map.of(SEED, 1L));
        assertEquals(1L, finished.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                        seedSlot(OUTSIDE_CONSUMER), false),
                "整条环跑完且欠账清光 ⇒ 这一族没有留底 ⇒ 同一份薄池旁人照常取走");
        var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                new KeyCounter(), new KeyCounter(), finished);
        assertNotNull(batch, "环已经没有了还把种锁着，就是把夹子写成了恒夹");
        assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
    }

    /**
     * 同族两键判据 1（这一刀的来路）：一张样板一发同时吃同族<b>两枚精确键</b>各 1 枚、各还 1 枚，
     * 两枚各自过资格 ⇒ 族留底是 {@code (1+1) × 2 发 = 4} 枚，池里那 3 枚全在环的口粮里 ⇒
     * 旁人对种键的可花量是 <b>0</b>、一发推不出去。
     *
     * <p>旧口径把分量口径按族建档、同槽/同族的每发消耗 {@code Math::max} 并账 ⇒ 族格上只留下
     * {@code 1 × 2 发 = 2} 枚 ⇒ 同一档交出 {@code 3 − 2 = 1} 枚，那第二枚正是旁人从池里多抽走的。
     */
    @Test
    void twinSeedKeysOfOneFamilySumIntoTheFamilyReserve() {
        var job = twinJob(TWIN_DOUPLER_FIRINGS, TWIN_OUTSIDE_FIRINGS);
        var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, TWIN_POOL_AMOUNT));

        assertEquals(2, job.ringSeeds.seedDemandsByFamily()
                        .get(Items.IRON_INGOT).seedKeys().size(),
                "前提读数：同族两枚精确键各自过资格、各记一份分量口径——并成一族就只剩一份");
        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(TWIN_VARIANT_A, TWIN_OUTSIDE_CONSUMER,
                        slot(TWIN_OUTSIDE_CONSUMER, 0)),
                "前提读数：旁人这一族的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
        // 旧口径（族格 Math::max 并账）这一格交回 3−2=1：第二枚的那一份没人留
        assertEquals(0L, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                        TWIN_OUTSIDE_CONSUMER, 1L, slot(TWIN_OUTSIDE_CONSUMER, 0), false),
                "同族两枚键各欠 2 发×每发 1 枚：族留底是两枚之和 4，不是取最大的 2；池里那 3 枚一枚也不许旁人动");
        assertNull(ParallelBatchExtraction.prepare(TWIN_OUTSIDE_CONSUMER,
                        poolWith(TWIN_VARIANT_A, TWIN_POOL_AMOUNT), null, TWIN_OUTSIDE_FIRINGS,
                        new KeyCounter(), new KeyCounter(), reservation),
                "可花量 0 ⇒ 这一轮旁人一发也推不出去（holder 段与单发回落段都不许绕过留底）");

        // 环成员自己在两枚键上都免扣：族条目下两份分量名单都写着它
        assertEquals(TWIN_POOL_AMOUNT, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                        TWIN_SEED_DOUPLER, 1L, slot(TWIN_SEED_DOUPLER, 0), false),
                "它就在自己那一枚的分量成员名单里：A 那一份口粮不许扣它");
        assertEquals(TWIN_POOL_AMOUNT, reservation.usableFor(TWIN_VARIANT_B, TWIN_POOL_AMOUNT,
                        TWIN_SEED_DOUPLER, 1L, slot(TWIN_SEED_DOUPLER, 1), false),
                "同族第二枚那一格也是它自己要吃的：并账把豁免写成族一份时这里照样放行");
    }

    /**
     * 同族两键判据 2（守门，防把"族求和"写成恒夹）：同族两枚键里<b>只有 A 在册</b>——
     * {@link #TWIN_COMPONENT_A} 吃 A 也吃 B，可这个作业里没有任何样板把 B 还回来 ⇒ 图上没有指向 B
     * 那一个节点的边 ⇒ B 与吃它的样板<b>不成分量</b> ⇒ B 进不了账。
     * 族留底因此只计 A 那一枚的 {@code 1 × 2 发 = 2} 枚，B 那一格的消耗不进账 ⇒
     * 池里 3 枚时旁人照常拿走 {@code 3 − 2 = 1} 枚。
     *
     * <p>这一条挡的是反过来的那一刀：把族下"这一张样板吃掉的同族全部消耗"整个求和（连不进账的
     * 那一枚也算进去）⇒ 留底变成 4 枚 ⇒ 同一档夹成 0，把还能推的那一发也一起锁死。
     */
    @Test
    void onlyTheQualifiedSeedKeyOfTheFamilyCountsIntoItsReserve() {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(TWIN_COMPONENT_A, progress(TWIN_DOUPLER_FIRINGS));
        job.tasks.put(TWIN_OUTSIDE_CONSUMER, progress(TWIN_OUTSIDE_FIRINGS));
        MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_A, rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
        MaterialAllocationFixtures.assign(job, TWIN_OUTSIDE_CONSUMER, rationOf(OUTSIDE_QUOTA, TWIN_VARIANT_A));

        var demands = job.ringSeeds.seedDemandsByFamily().get(Items.IRON_INGOT);
        assertEquals(1, demands.seedKeys().size(),
                "没过资格那一枚（B 在这一趟没有还它的样板）不进账：族下只留 A 那一份分量口径");
        assertEquals(TWIN_VARIANT_A, demands.seedKeys().get(0).seed(),
                "在册的就是过资格那一枚精确键，不是它的族、也不是同族另一枚");

        var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, TWIN_POOL_AMOUNT));
        assertEquals(1L, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                        TWIN_OUTSIDE_CONSUMER, 1L, slot(TWIN_OUTSIDE_CONSUMER, 0), false),
                "owed 只剩过资格那一枚的 2 枚：没资格那一格不许跟着复加，旁人还能拿走 3−2=1 枚");
        var batch = ParallelBatchExtraction.prepare(TWIN_OUTSIDE_CONSUMER,
                poolWith(TWIN_VARIANT_A, TWIN_POOL_AMOUNT), null, TWIN_OUTSIDE_FIRINGS,
                new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(batch, "环只欠 2 枚而池里有 3 枚：这一发还能推，写成恒夹就是把它锁死");
        assertEquals(1L, batch.executions(), "可花量 1 枚、每发吃一枚 ⇒ 本批推 1 发");
    }

    /**
     * 同族两分量判据（豁免按分量交）：同族两枚键各在自己的分量里（A 那一张还 B 那一张），
     * 两侧都还欠着发数 ⇒ 每一侧伸手拿<b>对面那一份口粮</b>都要先扣对面的留底。
     *
     * <p>旧口径把两个分量的成员并成<b>一份</b>族免扣名单 ⇒ A 的成员在 B 的那一枚上也被整体放行，
     * B 那份口粮能被 A 抽干（薄池那一档旧码交出整池 1 枚，留底读数应是 0）。
     *
     * <p>反向那一半同样钉住并写明族口径的代价：族是<b>查表</b>口径（与 {@code shareOf} 同族），
     * B 的成员在自己那一枚上免扣 B 那一份，可它伸手拿同族任一变体照样要先扣 A 分量那 2 枚。
     */
    @Test
    void twinComponentsExemptOnlyTheirOwnMemberList() {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(TWIN_COMPONENT_A, progress(COMPONENT_A_FIRINGS));
        job.tasks.put(TWIN_COMPONENT_B, progress(COMPONENT_B_FIRINGS));
        MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_A, rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
        MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_B, rationOf(RING_QUOTA, TWIN_VARIANT_B));
        var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                TWIN_VARIANT_B, TWIN_POOL_AMOUNT));

        assertEquals(2, job.ringSeeds.seedDemandsByFamily().get(Items.IRON_INGOT).seedKeys().size(),
                "前提读数：同族两个分量各记一份留底（A 欠 2 枚、B 欠 1 枚）");
        assertEquals(2L, reservation.usableFor(TWIN_VARIANT_B, TWIN_POOL_AMOUNT,
                        TWIN_COMPONENT_A, 1L, slot(TWIN_COMPONENT_A, 1), false),
                "A 分量的成员伸手拿 B 那一份口粮：旧码并成一份免扣名单时交回整池 3 枚，这里先扣 B 分量那 1 枚");
        assertEquals(0L, reservation.usableFor(TWIN_VARIANT_B, 1L,
                        TWIN_COMPONENT_A, 1L, slot(TWIN_COMPONENT_A, 1), false),
                "薄池那一档：池里就是 B 分量欠着的那一枚时，A 的成员一枚也不许动（旧码放行整池）");
        assertEquals(2L, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                        TWIN_COMPONENT_A, 1L, slot(TWIN_COMPONENT_A, 0), false),
                "它在自己那一枚上免扣 A 那一份，只剩对面 B 分量那 1 枚要扣");
        assertEquals(1L, reservation.usableFor(TWIN_VARIANT_B, TWIN_POOL_AMOUNT,
                        TWIN_COMPONENT_B, 1L, slot(TWIN_COMPONENT_B, 0), false),
                "族是查表口径：B 的成员免扣 B 那一份，可它拿同族任一变体照样先扣 A 分量那 2 枚");
    }

    /**
     * 折发数那条腿（NeoEco 快路径的请求侧与宿主申报那条）也读同一份留底：份额足得很、
     * 环还欠着 5 发 ⇒ 旁人对种键的可花量是 <b>0</b> ⇒ 宿主自己报上来的 3 发一发也折不出。
     *
     * <p>取料点那把尺（{@code usableFor}）折的是<b>池子现量</b>，这一条腿折的是<b>发数</b>——
     * 它压根不经过取料点，所以"每发吃一枚种"这一维过去只受配额夹：实机 {@code 1e11} 那一跑里
     * {@code 每发=1,池内=0,网络sim=0} 那一行说的正是这一条腿把种申报走了。
     */
    @Test
    void theFiringFoldOnTheFastPathAlsoReadsTheSeedReserve() {
        var job = newJob(RING_REMAINING_FIRINGS, 3L);
        var reservation = reservationOf(job);
        var pool = pool();
        var sample = new KeyCounter[]{eats(SEED, 1L)};

        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                        seedSlot(OUTSIDE_CONSUMER)),
                "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
        assertEquals(0L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                        reservation, OUTSIDE_CONSUMER, sample, 3L, pool),
                "环欠 5 发×每发 1 枚 &gt; 池里那 1 枚 ⇒ 可花量 0 ⇒ 这一趟对种键一发也申报不出去");
        assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                        reservation, OUTSIDE_CONSUMER, sample, 3L),
                "交不出池子读数那一档照旧只按配额折：这一刀只长在看得见池子的那条腿上");
    }

    /**
     * 反向守门（同一条腿）：环分量的成员自己走折发数这条路时照常拿得到种。
     *
     * <p>豁免那一档必须写成"这一条腿对它不成立"，不是"扣 0 枚"——按后者这里会被池里那一枚种折成
     * 一发（3 → 1），增殖腿从此每 tick 只推进一发，链式环里 A 等 B 的 K、B 等 A 的 L 还会互等死锁。
     */
    @Test
    void theFiringFoldLeavesTheRingMemberOwnSeedAlone() {
        var job = newJob(RING_REMAINING_FIRINGS, 3L);
        var reservation = reservationOf(job);
        var pool = pool();

        assertEquals(RING_QUOTA, reservation.quotaFor(SEED, RING_DOUBLER, seedSlot(RING_DOUBLER)),
                "前提读数：增殖腿在种键上也有份额，这一档不是没有配额口径");
        assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation, RING_DOUBLER,
                        new KeyCounter[]{eats(SEED, 1L)}, 3L, pool),
                "它是 K 那一环的成员：这一条腿不许拿池里那一枚去夹它自己的发数（豁免写成夹就是新闸）");
    }

    /**
     * 环跑完自动放行（同一条腿）：种键的 {@code owed} 归零 ⇒ 这一族整个不出账 ⇒
     * 同一份薄池、同一张旁人样板的 3 发照旧折得出来。这一条防的是把这一刀写成恒夹。
     */
    @Test
    void theFiringFoldReleasesTheSeedOnceTheRingHasNoDemandLeft() {
        var job = newJob(0L, 3L);
        var reservation = reservationOf(job);

        assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation, OUTSIDE_CONSUMER,
                        new KeyCounter[]{eats(SEED, 1L)}, 3L, pool()),
                "增殖腿一发不欠 ⇒ 没有要留给环的那一截 ⇒ 旁人的 3 发照常折得出");
    }

    /**
     * A（同一截留底被扣 N 次）：这一族只有 A 那一枚在册（没有任何样板还 {@link #TWIN_VARIANT_B}），
     * 环欠的 {@code 1 × 50} 枚全记在 A 那一格上。读口对<b>每一枚</b>键交回的都是"这一枚扣掉<b>整族</b>
     * {@code owed}"之后的读数，旧口径把两枚的可花量<b>相加</b>再除族每发合计 ⇒ 同一截留底扣两遍，
     * 而那道除法顺手让 B 那 100 枚去补 A 那 50 枚的亏空：一发各吃 A、B 一枚的旁人折出
     * {@code (0+50)/2 = 25} 发，可它伸手那一格 A 在取料点上本来就是 <b>0</b> 枚。
     *
     * <p>改后按精确键各除各的每发量、商取最小 ⇒ 0 发。这一条红在改前那一句的 25。
     */
    @Test
    void theFiringFoldDoesNotLetTheRicherKeyOfAFamilyPayTheReservedOnesDeficit() {
        var job = twinBothKeysJob(TWIN_RESERVE_FIRINGS);
        var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, RESERVED_SEED_POOL,
                TWIN_VARIANT_B, RICH_VARIANT_POOL));
        var pool = twinPool(RESERVED_SEED_POOL, RICH_VARIANT_POOL);
        // 槽序正是这一档的用力点：富余那一枚在前、被扣空那一枚在后，族合计才补得上
        var sample = new KeyCounter[]{eats(TWIN_VARIANT_B, 1L), eats(TWIN_VARIANT_A, 1L)};

        assertEquals(1, job.ringSeeds.seedDemandsByFamily().get(Items.IRON_INGOT).seedKeys().size(),
                "前提读数：这一族只有 A 那一枚在册（没有样板还 B）⇒ owed 记在 A 那一格上");
        assertEquals(0L, reservation.usableFor(TWIN_VARIANT_A, RESERVED_SEED_POOL,
                        TWIN_OUTSIDE_BOTH_KEYS, 1L, slot(TWIN_OUTSIDE_BOTH_KEYS, 1), false),
                "前提读数：取料点那把尺上 A 那一枚可花 0——50 枚全在环的口粮里");
        assertEquals(50L, reservation.usableFor(TWIN_VARIANT_B, RICH_VARIANT_POOL,
                        TWIN_OUTSIDE_BOTH_KEYS, 1L, slot(TWIN_OUTSIDE_BOTH_KEYS, 0), false),
                "前提读数：同族富余那一枚可花 50，那是替别人扣过一遍 owed 的读数，不是多出来的发数");
        assertEquals(0L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                        reservation, TWIN_OUTSIDE_BOTH_KEYS, sample, TWIN_FOLD_REQUESTED, pool),
                "族里富余那一枚不许替被扣空那一枚贴亏空：按精确键各除各的每发量，A 那一格折不出第 1 发");
    }

    /**
     * 同一刀的反向守门（改前改后同一个数，钉"不许把还能推的那几发折成 0"）：族两枚键各 10 枚而
     * {@code owed} 只有 5 枚时，取料点对<b>每一枚</b>键都交出可花 5 枚 ⇒ 这一张旁人物理上就是只能跑 5 发。
     *
     * <p>复核给的"过夹那一型"（族真可花 15 ⇒ 应折 7 发）取的是<b>族可互换</b>口径，而留底按精确键记账
     * （{@code f8679c6}）：跑 7 发要的是 7 枚 A 加 7 枚 B，A 那一枚被环扣着 5 枚就只剩 5 枚可走
     * ⇒ 第 6 发起就付不出 A。这一档不是这一刀的回归网，钉的是这一刀不越位。
     */
    @Test
    void theFiringFoldOnABalancedTwinFamilyStaysOnThePoolLegsOwnReading() {
        var job = twinBothKeysJob(5L);
        var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, 10L, TWIN_VARIANT_B, 10L));
        var pool = twinPool(10L, 10L);

        assertEquals(5L, reservation.usableFor(TWIN_VARIANT_A, 10L, TWIN_OUTSIDE_BOTH_KEYS, 1L,
                        slot(TWIN_OUTSIDE_BOTH_KEYS, 1), false),
                "前提读数：取料点给 A 那一枚可花 10−5=5 枚");
        assertEquals(5L, reservation.usableFor(TWIN_VARIANT_B, 10L, TWIN_OUTSIDE_BOTH_KEYS, 1L,
                        slot(TWIN_OUTSIDE_BOTH_KEYS, 0), false),
                "前提读数：B 那一枚同样 5 枚 ⇒ 两把尺在这一档给同一个数");
        assertEquals(5L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                        reservation, TWIN_OUTSIDE_BOTH_KEYS,
                        new KeyCounter[]{eats(TWIN_VARIANT_B, 1L), eats(TWIN_VARIANT_A, 1L)},
                        TWIN_FOLD_REQUESTED, pool),
                "各除各的每发量之后还是 5 发：这一刀只把 25 那一型夹住，不把还能推的那几发一起锁死");
    }

    /**
     * B（读池没过池账口径）：宿主自报清单与样本槽里那一枚键，跟池子里那一份<b>不是同一个实例</b>。
     * 第三方自定义键把主键存成值对象（从 NBT／网络包重建出来的那一型）时，
     * {@code KeyCounter} 的主表按 {@code getPrimaryKey()} 的<b>引用</b>分格 ⇒ 拿声明键读池恒读 0，
     * 留底那一刀于是把<b>自己的池子现量</b>读成空 ⇒ NeoEco 这一条腿<b>恒夹</b>（池里明明躺着 100 枚）。
     *
     * <p>红在改前那一句：折出 0 发。改后读池走 {@code ParallelBatchExtraction#exactStored}
     * （先把声明键换成池账真装着的那一枚，复用现成解析、不写第二份）⇒ 与取料点读到同一格。
     */
    @Test
    void theFiringFoldReadsThePoolLedgerInstanceOfAThirdPartyKey() {
        var job = chemicalSeedJob(CHEM_RESERVE_FIRINGS);
        var reservation = reservationOf(job, Map.of(CHEM_SEED_REPORTED, 100L));
        var pool = new ListCraftingInventory(ignored -> {
        });
        pool.insert(CHEM_SEED_IN_POOL, 100L, Actionable.MODULATE);

        assertEquals(CHEM_SEED_REPORTED, CHEM_SEED_IN_POOL, "夹具前提：按 equals 就是同一枚键");
        assertTrue(CHEM_SEED_REPORTED.getPrimaryKey() != CHEM_SEED_IN_POOL.getPrimaryKey(),
                "夹具前提：主键是两个引用（值对象主键的自定义键就长这一型）");
        assertEquals(0L, pool.list.get(CHEM_SEED_REPORTED),
                "夹具前提：主表按引用分格，声明键落在另一个桶里 ⇒ 旧口径读到的现量恒 0");
        assertEquals(100L, pool.list.get(CHEM_SEED_IN_POOL),
                "夹具前提：100 枚躺在池子那一份实例的分格里");
        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(CHEM_SEED_REPORTED, CHEM_OUTSIDE_CONSUMER,
                        seedSlot(CHEM_OUTSIDE_CONSUMER)),
                "前提读数：这一张在种键上有配额口径——旧口径确实走到了留底那一刀");
        assertEquals(95L, reservation.usableFor(CHEM_SEED_REPORTED, 100L, CHEM_OUTSIDE_CONSUMER, 1L,
                        seedSlot(CHEM_OUTSIDE_CONSUMER), false),
                "前提读数：取料点那把尺读同一格给的是 100−5=95，不是 0");
        assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation,
                        CHEM_OUTSIDE_CONSUMER, new KeyCounter[]{eats(CHEM_SEED_REPORTED, 1L)}, 3L, pool),
                "读池先过池账口径 ⇒ 池里那 100 枚看得见，扣掉环欠的 5 枚照常折得出 3 发（改前恒夹成 0）");
    }

    /**
     * C（无配额那一档漏夹，与取料点不对称）：这张旁人<b>压根不在配给表里</b> ⇒ {@code quotaExact}
     * 交 {@code null}（"没有口径"，不是"份额用完"那个 0）。旧口径把留底那一刀排在配额那一刀之后、
     * {@code null} 就 {@code continue} ⇒ 这一型<b>不读留底</b>，3 发原样折出去；而取料点
     * {@link ParallelPlanReservation#usableFor} 是没有配额口径照样夹——两把尺对同一件事两个答案。
     *
     * <p>红在改前那一句的 3。改后留底独立施夹（与网络回补那条腿同一形状）⇒ 0 发；
     * 同档反向那一半钉的是"环一发不欠 ⇒ 放行"，这一刀不是恒夹。
     */
    @Test
    void theFiringFoldReadsTheSeedReserveEvenWithoutAnyQuotaBasis() {
        var job = jobWithUnrationedOutsider(RING_REMAINING_FIRINGS);
        var reservation = reservationOf(job);

        assertNull(reservation.quotaExact(SEED, UNRATIONED_OUTSIDE_CONSUMER,
                        seedSlot(UNRATIONED_OUTSIDE_CONSUMER)),
                "前提读数：这一张没有配额口径（quotaExact 交 null，不是份额用完那个 0）");
        assertEquals(0L, reservation.usableFor(SEED, 1L, UNRATIONED_OUTSIDE_CONSUMER, 1L,
                        seedSlot(UNRATIONED_OUTSIDE_CONSUMER), false),
                "同一档取料点那把尺：没有配额口径照样夹，可花量 0");
        assertEquals(0L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation,
                        UNRATIONED_OUTSIDE_CONSUMER, new KeyCounter[]{eats(SEED, 1L)}, 3L, pool()),
                "留底这一刀从配额那一刀里拆出来独立施夹：这一型也读得到 owed=5 ⇒ 一发也申报不出去");

        var finished = jobWithUnrationedOutsider(0L);
        assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservationOf(finished),
                        UNRATIONED_OUTSIDE_CONSUMER, new KeyCounter[]{eats(SEED, 1L)}, 3L, pool()),
                "环跑完 ⇒ 这一族不出账 ⇒ 同一张没配额的旁人照常折得 3 发（这一刀不是恒夹）");
    }

    /**
     * 网络回补那条腿也读同一份留底：环欠着 5 发而池里空空 ⇒ 旁人这一批要吃的 3 枚种
     * <b>净增可花量是 0</b> ⇒ 一枚也不许经网络搬进本 lane 池子。
     *
     * <p>这一条腿过去只减"池内已存"与"族剩配额"，压根不读留底——旁人<b>申报批</b>那
     * {@code k-1} 发的料就是从这条腿绕开池子那把尺搬进来的（实机 {@code 1e11} 那一跑）。
     */
    @Test
    void theNetworkRefillLegAlsoReadsTheSeedReserve() {
        var job = newJob(RING_REMAINING_FIRINGS, 3L);
        var reservation = reservationOf(job);
        var pool = new ListCraftingInventory(ignored -> {
        });
        var network = new SeedNetwork(SEED, 5L);

        assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                        seedSlot(OUTSIDE_CONSUMER)),
                "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
        assertEquals(0L, ParallelBatchExtraction.refillObligationBatch(OUTSIDE_CONSUMER, pool, null,
                        3L, reservation, network),
                "环欠 5 发×每发 1 枚 ⇒ 这一趟搬进来的净增可花量是 0 ⇒ 一颗种也不许经网络绕开留底");
        assertEquals(BigInteger.valueOf(5L), network.remaining(),
                "没伸手就是没伸手：网络那五枚一枚不少（搬进本 lane 池子就等于搬走了）");
        assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(pool, SEED),
                "本张的池子也没多出那一截");
    }

    /**
     * 同一条腿的两档反向守门（防恒夹）：臂 A 环分量的成员自己走这条腿回补照常搬得进
     * （豁免写成"按池子现量夹"就是把增殖腿扣在自己的起步量上）；
     * 臂 B 环跑完 ⇒ {@code owed} 归零、这一族整个不出账 ⇒ 同一笔回补照旧放行旁人。
     */
    @Test
    void theNetworkRefillLegNeitherClampsTheRingNorStaysClampedForever() {
        var memberPool = new ListCraftingInventory(ignored -> {
        });
        var memberNetwork = new SeedNetwork(SEED, 5L);
        assertEquals(3L, ParallelBatchExtraction.refillObligationBatch(RING_DOUBLER, memberPool, null,
                        3L, reservationOf(newJob(RING_REMAINING_FIRINGS, 3L)), memberNetwork),
                "臂 A：增殖腿给自己回补那 3 发的料——这一条腿对它不成立，不许夹成 0");
        assertEquals(BigInteger.valueOf(2L), memberNetwork.remaining(),
                "臂 A：只走出本张那三枚，环还要留给旁人的那一截本来就不存在");

        var outsiderPool = new ListCraftingInventory(ignored -> {
        });
        var outsiderNetwork = new SeedNetwork(SEED, 5L);
        assertEquals(3L, ParallelBatchExtraction.refillObligationBatch(OUTSIDE_CONSUMER, outsiderPool,
                        null, 3L, reservationOf(newJob(0L, 3L)), outsiderNetwork),
                "臂 B：环一发不欠 ⇒ 这一族不留底 ⇒ 同一笔回补照常放给旁人");
    }

    // ==================================================================================
    // 夹具
    // ==================================================================================

    /** 每 tick 现算的那本账：任务表 + 池子读数 + 两本配给账 + 作业自己的环种账。 */
    private static ParallelPlanReservation reservationOf(ParallelExecutingCraftingJob job) {
        return reservationOf(job, Map.of(SEED, 1L));
    }

    /** 池子快照按档位给：链式判据 3 那一档里 L 也在池内，占料那条分支要看得见同一份读数。 */
    private static ParallelPlanReservation reservationOf(
            ParallelExecutingCraftingJob job, Map<AEKey, Long> poolSnapshot
    ) {
        return ParallelPlanReservation.build(job.tasks.entrySet(), poolSnapshot,
                job.materialAllocation, Set.of(),
                job.ringSeeds.seedDemandsByFamily(), job.batchObligations);
    }

    /** 链式环的账：A 与 B 都在任务表里，C 在种键上有充足配给（走配给早退那一档的形状）。 */
    private static ParallelExecutingCraftingJob chainJob(
            long emitterFirings, long refunderFirings, long outsideFirings
    ) {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(CHAIN_EMITTER, progress(emitterFirings));
        job.tasks.put(CHAIN_REFUNDER, progress(refunderFirings));
        job.tasks.put(OUTSIDE_CONSUMER, progress(outsideFirings));
        MaterialAllocationFixtures.assign(job, CHAIN_EMITTER, ration(RING_QUOTA));
        MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
        return job;
    }

    /** 环成员与旁人都在配给表里在册（实机 {@code alloc=[…left=959985…]} 两腿合计的那一份）。 */
    private static ParallelExecutingCraftingJob newJob(long ringFirings, long outsideFirings) {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(RING_DOUBLER, progress(ringFirings));
        job.tasks.put(OUTSIDE_CONSUMER, progress(outsideFirings));
        MaterialAllocationFixtures.assign(job, RING_DOUBLER, ration(RING_QUOTA));
        MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
        return job;
    }

    /**
     * 申报挂账那一档的账：增殖腿 C、欠账那一张 A、一张有充足配给的旁人 C'，三张都在配给表里在册。
     * A 的剩余发数与义务账上的挂账发数各由入参给（后者走义务账现成的 {@code incur}，不写第二本账）。
     */
    private static ParallelExecutingCraftingJob debtorJob(
            long doublerFirings, long debtorFirings, long debtorPendingFirings
    ) {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(RING_DOUBLER, progress(doublerFirings));
        job.tasks.put(DECLARED_DEBTOR, progress(debtorFirings));
        job.tasks.put(OUTSIDE_CONSUMER, progress(3L));
        MaterialAllocationFixtures.assign(job, RING_DOUBLER, ration(RING_QUOTA));
        MaterialAllocationFixtures.assign(job, DECLARED_DEBTOR, ration(RING_QUOTA));
        MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
        job.batchObligations.incur(DECLARED_DEBTOR, BigInteger.valueOf(debtorPendingFirings));
        return job;
    }

    /**
     * 自循环环 + 一张有充足配给的旁人，环成员的剩余发数走任务表现成的宽域写口
     * （{@code TaskProgress#addFirings}）：越界自动升侧值，不绕开任务表自己塞字段。
     */
    private static ParallelExecutingCraftingJob ringJobWithWideFirings(
            FakePattern ringMember, BigInteger firings
    ) {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(ringMember, wideProgress(firings));
        job.tasks.put(OUTSIDE_CONSUMER, progress(3L));
        MaterialAllocationFixtures.assign(job, ringMember, ration(RING_QUOTA));
        MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
        return job;
    }

    /** 宽域剩余发数：只经 {@code addFirings} 这一道写口进任务表。 */
    private static ParallelExecutingCraftingJob.TaskProgress wideProgress(BigInteger firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(firings));
        return progress;
    }

    private static WideKeyCounter ration(long quota) {
        var assigned = new WideKeyCounter();
        assigned.add(SEED, quota);
        return assigned;
    }

    /** 同族那一档的配给：按传进来的每一枚精确键各记一份份额（族商是把它们加起来判的）。 */
    private static WideKeyCounter rationOf(long quota, AEKey... keys) {
        var assigned = new WideKeyCounter();
        for (AEKey key : keys) {
            assigned.add(key, quota);
        }
        return assigned;
    }

    /** 同族两个精确键那一档的账：吃两枚键的环成员 + 一枚有充足配给的旁人（只吃 A 那一枚）。 */
    private static ParallelExecutingCraftingJob twinJob(long doublerFirings, long outsideFirings) {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(TWIN_SEED_DOUPLER, progress(doublerFirings));
        job.tasks.put(TWIN_OUTSIDE_CONSUMER, progress(outsideFirings));
        MaterialAllocationFixtures.assign(job, TWIN_SEED_DOUPLER,
                rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
        MaterialAllocationFixtures.assign(job, TWIN_OUTSIDE_CONSUMER, rationOf(OUTSIDE_QUOTA, TWIN_VARIANT_A));
        return job;
    }

    /**
     * 同一件物品的一个<b>带组件变体</b>：与同物品的另一个变体是两个不同的精确键、同一个物品本体族——
     * "族格上只留下 max"那一档就长在这一对键上。
     */
    private static AEItemKey namedVariant(net.minecraft.world.item.Item item, String name) {
        var stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
    }

    /**
     * 折发数那条腿"A 那一档"的账：环成员 {@link #TWIN_COMPONENT_A} 欠 A 那一枚（B 没有样板还它、
     * 不进账），旁人 {@link #TWIN_OUTSIDE_BOTH_KEYS} 一发各吃 A、B 一枚，只在 A 那一枚上有配给
     * （族是查表口径 ⇒ B 那一格同样有配额口径，配额那一刀夹不住它）。
     */
    private static ParallelExecutingCraftingJob twinBothKeysJob(long ringFirings) {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(TWIN_COMPONENT_A, progress(ringFirings));
        job.tasks.put(TWIN_OUTSIDE_BOTH_KEYS, progress(TWIN_FOLD_REQUESTED));
        MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_A,
                rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
        MaterialAllocationFixtures.assign(job, TWIN_OUTSIDE_BOTH_KEYS, rationOf(OUTSIDE_QUOTA, TWIN_VARIANT_A));
        return job;
    }

    /** 增殖腿与一张有配额的旁人，再加一张<b>不进配给表</b>的旁人（{@code quotaExact} 交 null 那一型）。 */
    private static ParallelExecutingCraftingJob jobWithUnrationedOutsider(long ringFirings) {
        var job = newJob(ringFirings, 3L);
        job.tasks.put(UNRATIONED_OUTSIDE_CONSUMER, progress(3L));
        return job;
    }

    /** 自定义键那一档的账：环成员欠着声明键那枚种，旁人在同一枚键上有充足配给。 */
    private static ParallelExecutingCraftingJob chemicalSeedJob(long ringFirings) {
        var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
        job.tasks.put(CHEM_RING, progress(ringFirings));
        job.tasks.put(CHEM_OUTSIDE_CONSUMER, progress(3L));
        MaterialAllocationFixtures.assign(job, CHEM_RING, rationOf(RING_QUOTA, CHEM_SEED_REPORTED));
        MaterialAllocationFixtures.assign(job, CHEM_OUTSIDE_CONSUMER, rationOf(OUTSIDE_QUOTA, CHEM_SEED_REPORTED));
        return job;
    }

    /** 同族两枚键各放 {@code amountA}／{@code amountB} 枚的池子（A 是环欠着的那一枚）。 */
    private static ListCraftingInventory twinPool(long amountA, long amountB) {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(TWIN_VARIANT_A, amountA, Actionable.MODULATE);
        inventory.insert(TWIN_VARIANT_B, amountB, Actionable.MODULATE);
        return inventory;
    }

    /**
     * 契约／NBT 那份内部物表（{@code job.dyeableRecursiveInternalItems}）：单发让路那一刀喂的就是它，
     * 记的是规划期为这一颗种起步预留的枚数——与留底那把尺读的"环分量自己还没跑完的那一截"是两份口径。
     */
    private static KeyCounter contractReserve(long seedAmount) {
        var internalItems = new KeyCounter();
        internalItems.add(SEED, seedAmount);
        return internalItems;
    }

    /** 一发样本这一格吃 {@code perFiring} 枚：折发数那条腿（{@code perCraftInputs}）读的就是这一份。 */
    private static KeyCounter eats(AEKey key, long perFiring) {
        var sample = new KeyCounter();
        sample.add(key, perFiring);
        return sample;
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    /** 池子里只躺着那一枚种：正是要留底的那一格。 */
    private static ListCraftingInventory pool() {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(SEED, 1L, Actionable.MODULATE);
        return inventory;
    }

    /** 薄池通用形状：只放 {@code amount} 枚这一个键，留底一夹就见底。 */
    private static ListCraftingInventory poolWith(AEKey key, long amount) {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(key, amount, Actionable.MODULATE);
        return inventory;
    }

    /**
     * 网络回补通道：网络上只躺着 {@code key} 那一枚键，要多少给多少。
     *
     * <p>这一档钉的是"这一刀伸不伸手"，不是网络交不交得出——剩下的枚数由 {@link #remaining()}
     * 读数自证（没伸手就一枚不少）。
     */
    private static final class SeedNetwork implements ParallelBatchExtraction.ObligationRefill {

        private final AEKey key;
        private long remaining;

        private SeedNetwork(AEKey key, long available) {
            this.key = key;
            this.remaining = available;
        }

        @Override
        public long pull(@Nullable AEKey what, long wanted) {
            if (what == null || !what.equals(key) || remaining <= 0L) {
                return 0L;
            }
            long got = Math.min(wanted, remaining);
            remaining -= got;
            return got;
        }

        /** 网络上还剩几枚（纯读数）：这一刀没伸手就要在这里自证"一枚不少"。 */
        private BigInteger remaining() {
            return BigInteger.valueOf(remaining);
        }
    }

    private static IPatternDetails.IInput seedSlot(IPatternDetails pattern) {
        return pattern.getInputs()[0];
    }

    /**
     * 自定义键类型：与 {@code ParallelCustomKeyPoolRulerTest} 同一形状（codec 那一格本文件用不到，
     * 也不碰 {@code AEKeyType#getRawId()}），为的是让主键成为<b>值对象</b>。
     */
    private static final class SeedChemicalType extends AEKeyType {
        private SeedChemicalType() {
            super(ResourceLocation.fromNamespaceAndPath("testmek", "chemical"),
                    SeedChemicalKey.class, Component.literal("testmek:chemical"));
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            return null;
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return new SeedChemicalKey();
        }
    }

    private static final AEKeyType SEED_CHEM_TYPE = new SeedChemicalType();

    /**
     * 第三方自定义键（Mekanism 那一型）：{@code equals} 只比资源本体 ⇒ 两份实例认量同一枚，
     * 而 {@code getPrimaryKey()} 交回<b>各自新建的值对象</b> ⇒ AE2 那本按引用分格的主表把它们
     * 记进两个不同的桶。折发数那条腿伸手的键正是"声明那一份"，池子里躺着的是"另一份"。
     */
    private static final class SeedChemicalKey extends AEKey {
        private final ResourceLocation primary =
                ResourceLocation.fromNamespaceAndPath("mekanism", "redstone");

        @Override
        public AEKeyType getType() {
            return SEED_CHEM_TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag(HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            tag.putString("id", primary.toString());
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return primary;
        }

        @Override
        public ResourceLocation getId() {
            return primary;
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal("mekanism:redstone");
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return false;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            return Objects.equals(primary, ((SeedChemicalKey) other).primary);
        }

        @Override
        public int hashCode() {
            return Objects.hash(primary);
        }

        @Override
        public String toString() {
            return "SeedChemicalKey{stack=" + primary + "}";
        }
    }

    /** 同族那一档一张样板有两格（两枚精确键各一格）：点名要伸的是哪一格。 */
    private static IPatternDetails.IInput slot(IPatternDetails pattern, int index) {
        return pattern.getInputs()[index];
    }

    private record FakeInput(GenericStack[] candidates, long multiplier,
            @Nullable AEKey remainder, Set<AEKey> accepts) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return accepts.contains(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainder;
        }
    }

    private record FakePattern(AEItemKey definition, IPatternDetails.IInput[] slots,
            List<GenericStack> outputs) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots.clone();
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }
    }

    private static ICraftingPlan emptyPlan() {
        return new ICraftingPlan() {
            private final KeyCounter empty = new KeyCounter();

            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return empty;
            }

            @Override
            public KeyCounter emittedItems() {
                return empty;
            }

            @Override
            public KeyCounter missingItems() {
                return empty;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return Map.of();
            }
        };
    }
}
