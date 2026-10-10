package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封套那一本随契约下发之后，执行侧两格读数的口径（台账 #123，{@code fc7d3d5} 搬家的另一半）。
 *
 * <p>缺陷形状：{@code MaterialClosureGate#billReachKeySlot} 给模糊槽<b>首位候选</b>补记的那一笔是
 * <b>整槽份额的许可上界</b>（"执行侧若向这一族伸手，至多吃掉整格"），它照样写在下发的配给表里。
 * {@code fc7d3d5} 只把它从规划侧的排水实记里摘出来，没随契约下发 ⇒ 执行侧
 * {@code billPlannedMaterialHoles} 拿<b>整本</b>当 need ⇒ 首位族那一档 {@code hole=} 恰等于封套全额
 * （还随发数放大），并且 {@code describePlanBalance} 按 {@code hole} 从大到小排前 8 ⇒ 假洞排在
 * 真洞前面、把真洞挤出可见区。
 *
 * <h3>手算 oracle（每份几、几发、该报多少，全部独立于被测乘式）</h3>
 * <pre>
 *   槽倍率 3   雪块每模板量 2 枚   发数 5
 *   <b>声明</b>那枚玻璃模板每发 6 枚（AE2 原始候选表首位）；<b>宇宙内</b>那一枚每发 2 枚
 *   整槽需求        = 3 × 5 = 15 模板 = 15 × 2 = 30 枚
 *   排水实记(SNOW)  = 15 模板 × 2 枚                    = 30 枚   （备选付满整槽）
 *   封套(GLASS)     = 倍率 3 × 宇宙内每发 2 枚 × 5 发     = 30 枚   （=整槽，许可上界）
 *   每发真要吃(GLASS) = 倍率 3 × 声明每发 6 枚 × 5 发      = 90 枚   （取料点那一侧的需求）
 *   ⇒ 封套上界 30 ≠ 每发真要吃 90：<b>两型洞在数上分得开</b>（旧夹具两者恒等，
 *     "拿整本当 need"这一回归与真需求洞同数，三例里都分不出来——台账 #199 回炉第二刀补的就是这一档）
 *   规划侧供给      = SNOW 40 枚(used) / GLASS 0 枚      ⇒ 排水实记（剔掉封套那一本后）SNOW 30 ≤ 40 ⇒ 表照发
 *   下发的整本      = {GLASS 30, SNOW 30}   封套本 = {GLASS 30}   实记 = 整本 − 封套 = {SNOW 30}
 *   —— 执行侧供给腿（任务表自产 + usedItems）：自产只有 IRON 1×5=5 枚，与这两族无关
 *     A 例 usedItems={SNOW 40} ⇒ 实记 30 ≤ 40 ⇒ 排水实记那一腿无洞；玻璃那一族三样都没有
 *                ⇒ 取料点每发 18 枚 × 5 发 = 90 枚另立一笔（#196）
 *     B 例 usedItems={SNOW 10} ⇒ 实记 30 > 10 ⇒ 雪块 hole=20，加上玻璃 hole=90 ⇒ unbalanced=2/1
 * </pre>
 *
 * <p>三条用例都走<b>真接线</b>：表与封套由真闸门 {@code buildMaterialPlan} 产出 → 装进真
 * {@link RecursiveCraftingPlan} 契约 → {@code describePlanBalance}（内部就是真
 * {@code adoptPlannedMaterialPlan} + 真 {@code billPlannedMaterialHoles} + 真排序打印）。
 *
 * <h3>台账 #196 改口的地方（读这一段再读下面两条 hole 用例）</h3>
 * 本类原来还把"封套那一族整条不许进 {@code hole=}"当成判据（旧 A/B 两例）。{@code 196} 用实机
 * {@code ae2:creative_storage_cell x 1e11} 尾段卡死那一格证明：那一族既没有排水实记、现场也没有
 * 任何存量/生产者、又不在无限源清单里时，"整条不许进"就是让必然卡死的一格从缺口判据里隐身
 * （{@code holes} 空 ⇒ 提交边界打 {@code ok keys=} ⇒ 照样下单）。现在减式一字未动——封套照样不进
 * {@code need} 分子（{@code 121/123/126} 的假超配/白卷不许重演）——但封套那一族的<b>每发真要吃的量</b>
 * 另走 {@code ParallelCraftingCpuLogic#billReachSlotDemand} 进同一只 {@code holes} 口袋。
 * 于是 A/B 两例报的是<b>两个不同来路的洞</b>：雪块那一格是排水实记的洞，玻璃那一格是取料点
 * {@code 每发件数 18 × 5 发 = 90} 枚的需求洞；两者都是真洞，按大小排序并列可见，不再有一个被抹掉。
 *
 * <h3>台账 #199 回炉第二刀补的两档</h3>
 * <ul>
 * <li><b>两型洞在数上分得开</b>：旧夹具里封套额（30）与"每发 × 发数"（30）恒等，"拿整本当 need"
 * 这一回归报出的数与真需求洞<b>同数</b>，三例谁都分不出来（{@code f6c7b9e} 把旧 A 例的
 * {@code assertFalse(line.contains("hole="))} 改窄成"雪块那一族不许有 hole="，方向不是放松，但代价
 * 就是这一档看不见）。夹具改成声明每发 6 枚 / 宇宙内每发 2 枚 ⇒ 封套 30、每发需求 90。
 * 两把牙各自只红自己：抹掉每发需求那一腿 ⇒ 红在 A/B 的 {@code GLASS hole=90}；
 * 拿整本当 need ⇒ 红在 {@link #envelopeOnAFamilyThatDrainAlreadyPaidNeverEntersTheHole}
 * 那一臂（雪块一族同时有排水实记与别张样板的封套，整本当 need 就给雪块立一笔洞）。</li>
 * <li><b>{@code holes} 的必含格</b>：{@code blocked=}／{@code 回补受阻} 点名的键即使按大小排不进
 * 前 8 也必须成行（读现成的 {@code StallProbe#blockedNamedKeys} 那一份名单，不新开第二本账）——
 * 由 {@link #keyNamedByBlockedCellGetsARowEvenWhenItLosesTheOrdering} 钉住；同一例里"没点名就不
 * 出现"那一臂证明这一行真是必含格给的，不是可见窗口给的。</li>
 * </ul>
 */
final class ParallelEnvelopeHoleReadoutTest {

    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    /** "雪块一族已有实记、别张样板又给它记封套"那一型的第二族。 */
    private static final AEItemKey COPPER = AEItemKey.of(Items.COPPER_INGOT);

    /** 手算表里的三个乘数。 */
    private static final long UNITS_PER_TEMPLATE = 2L;
    private static final long SLOT_MULTIPLIER = 3L;
    private static final long FIRINGS = 5L;
    /**
     * 玻璃在 <b>AE2 原始候选表</b>上声明的每发件数：宇宙内那一枚每发 2 枚，声明首位这一枚 6 枚。
     * 封套（许可上界）按宇宙内那枚记，取料点每发真要吃的按声明那枚记 ⇒ 两个数分得开（#199）。
     */
    private static final long DECLARED_GLASS_UNITS = 6L;
    /** 整槽份额＝排水实记＝封套＝30 枚（倍率 3 × 宇宙内每发 2 × 5 发）。 */
    private static final long WHOLE_SLOT_UNITS = 30L;
    /** 取料点那一侧每发真要吃的：倍率 3 × 声明每发 6 × 5 发 = 90 枚。 */
    private static final long REACH_DEMAND_UNITS = 90L;
    /** 规划侧 cover 看到的雪块供给（A 例执行侧也用它 ⇒ 无洞）。 */
    private static final long SNOW_PLAN_SUPPLY = 40L;
    /** B 例：现场只到账 10 枚 ⇒ 实记真洞 30 − 10 = 20 枚。 */
    private static final long SNOW_ARRIVED = 10L;
    private static final long REAL_HOLE_UNITS = 20L;

    /**
     * A 例：计划供给盖得住排水实记 ⇒ <b>排水实记那一腿</b>一个洞都不许有；封套那一族不许顶破这一腿
     * （拿整本当 need 就是把许可上界当欠账）。
     *
     * <p>台账 #196 改口后这一例报的是<b>另一条腿</b>的洞：玻璃那一族没有排水实记、现场没有存量、
     * 图上没有生产者、也不在无限源清单里 ⇒ 取料点每发真要吃的 18 枚 × 5 发 = 90 枚按
     * {@code billReachSlotDemand} 如实进同一只 {@code holes} 口袋（旧口径把这一格整条减光，
     * 提交边界因此打 {@code ok keys=}，实机就是这一格尾段卡死）。
     * 两条腿各钉各的数：雪块 {@code hole=} 一个字不许有（封套不许进 need 分子），玻璃 {@code hole=90}
     * 一个字不许少（那一族的每发需求不许隐身）、且不许是封套那 30 枚（许可上界不许当欠账）。
     */
    @Test
    void envelopeNeverEntersTheDrainClaimLegWhileItsOwnUnsourcedDemandEntersTheHole() {
        var source = new EnvelopeDetails();
        var bill = shippedBill(source);
        var plan = planOf(source, bill, SNOW_PLAN_SUPPLY);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(" src=plan"),
                () -> "读的是照单采用那一臂（自算臂压根没有封套可减）：" + line);
        assertFalse(line.contains(SNOW + " hole="),
                () -> "雪块排水实记 30 枚 ≤ 执行侧供给 40 枚：这一腿一个洞都不该有，"
                        + "出现雪块的 hole= 就是又拿整本当 need 了。整行=" + line);
        assertTrue(line.contains(GLASS + " hole=" + REACH_DEMAND_UNITS),
                () -> "玻璃那一族三样都没有（无实记/无存量/非无限源）⇒ 取料点每发 18 枚 × 5 发 = 90 枚"
                        + "必须进缺口，不再被整条减光。整行=" + line);
        assertFalse(line.contains(GLASS + " hole=" + WHOLE_SLOT_UNITS),
                () -> "封套那一笔 30 枚（倍率 3 × 宇宙内每发 2 × 5 发）是许可上界：把它报成 hole 就是"
                        + "又拿整本当 need——这一档的数与每发需求 90 枚分得开（#199 补的牙）。整行=" + line);
        assertFalse(line.contains(" ok keys="),
                () -> "缺口非空就不许走 ok 出口（ok keys= 是分母不是判决）：" + line);
        // 穿壳路：两本都进了作业，且下发表那一笔逐字不变
        assertEquals(WHOLE_SLOT_UNITS, MaterialAllocationFixtures.assignedSnapshot(job).get(source).getLong(GLASS),
                () -> "封套照样在下发的表里（不许摘掉）：" + line);
        assertEquals(WHOLE_SLOT_UNITS, MaterialAllocationFixtures.envelopeSnapshot(job).get(source).getLong(GLASS),
                () -> "封套那一本必须穿过契约进作业，hole 才有东西可减：" + line);
        assertEquals(0L, MaterialAllocationFixtures.envelopeSnapshot(job).get(source).getLong(SNOW),
                () -> "排水实记不进封套本（同一趟账的两本账各记各的）：" + line);
    }

    /**
     * B 例：排水<b>实记</b>真缺 20 枚时照旧报（{@code 121/123/126} 那一腿一字未动），
     * 封套那一族的<b>每发需求</b>另报 30 枚（{@code 196} 新增那一腿）——两个洞各有各的来路，
     * 谁也不许把谁抹掉。
     *
     * <p>期望逐字来自类注释那张手算表：雪块实记 30 枚 > 到账 10 枚 ⇒ {@code hole=20}；
     * 玻璃每发 18 枚 × 5 发 = 90 枚、供给 0 ⇒ {@code hole=90}；{@code unbalanced=2/1}
     * （两个洞 / 一个产出键）。按洞大小排序 ⇒ 玻璃(90) 在雪块(20) 前面。
     */
    @Test
    void drainClaimHoleAndUnsourcedEnvelopeDemandBothPrintWithTheirOwnNumbers() {
        var source = new EnvelopeDetails();
        var plan = planOf(source, shippedBill(source), SNOW_ARRIVED);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(SNOW + " hole=" + REAL_HOLE_UNITS),
                () -> "真洞不许被这一刀顺带抹掉：雪块实记 30 枚 > 到账 10 枚 ⇒ hole=20。整行=" + line);
        assertTrue(line.contains(GLASS + " hole=" + REACH_DEMAND_UNITS),
                () -> "玻璃那一族的每发需求（18 枚 × 5 发）必须另立一笔，不许从缺口里隐身：" + line);
        assertTrue(line.contains(" unbalanced=2/"),
                () -> "两条腿各报一格：" + line);
        // 排序位：按洞大小排，玻璃 90 > 雪块 20 ⇒ 紧跟 unbalanced= 的是玻璃
        assertTrue(line.contains(" unbalanced=2/1 " + GLASS + " hole=90"),
                () -> "洞大的排前面（这一档读数口径与 #126 逐字相同，只是洞的来路多了一条）：" + line);
    }

    /**
     * 配额夹<b>照旧读整本</b>（含封套）：首位族在现场有量时可以吃满那一格的 30 枚。
     *
     * <p>这一条钉的是"不许顺手把封套从配额上摘掉"——{@code hole=} 不算它，不等于它不存在：
     * {@code 0fbec2b} 定版的用途就是"运行期现场有量而规划宇宙为 0 时允许它吃满这一格"。
     * 期望同样出自手算表：{@code GLASS} 份额 30、{@code SNOW} 份额 30（减法不许漏到配额上）。
     */
    @Test
    void quotaClampStillReadsTheWholeBookIncludingTheEnvelope() {
        var source = new EnvelopeDetails();
        var plan = planOf(source, shippedBill(source), SNOW_PLAN_SUPPLY);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var slot = source.getInputs()[0];

        var envelopeQuota = reservation.quotaExact(GLASS, source, slot);
        assertNotNull(envelopeQuota,
                () -> "封套那一族在配给表里就是配额口径，hole 不算它也不能把它夹成没口径");
        assertEquals(BigInteger.valueOf(WHOLE_SLOT_UNITS), envelopeQuota.exactValue(),
                "现场有量时这一族可花到整槽那一份＝30 枚（封套存在的全部理由）");
        assertEquals(WHOLE_SLOT_UNITS, reservation.remainingQuota(source, GLASS),
                "不带槽的销账口径读同一份：两臂不许分叉");
        assertEquals(WHOLE_SLOT_UNITS, reservation.usableFor(GLASS, 100L, source, FIRINGS, slot, false),
                "池子里躺着 100 枚也只放封套那 30 枚：上界就是上界，不是没有上界");
        assertEquals(WHOLE_SLOT_UNITS, reservation.remainingQuota(source, SNOW),
                () -> "排水实记那一族的配额原样：hole 侧的减法不许漏进配额账");
    }

    /**
     * 封套落在"这一族在别张样板已经有排水实记"上时，缺口判据必须一个洞都不立。
     * 这一臂是<b>"拿封套整本当 need"那一型回归的专属牙</b>（台账 #199 回炉第二刀）：A/B 两例里
     * 封套额与每发需求已经被拆成两个数（30 / 90），而雪块这一族的洞<b>只可能</b>由"整本当 need"
     * 造出来——抹掉每发需求那一腿碰不到它（雪块的取料点需求本就被"同族有实记"挡住），
     * 摘掉必含格那一档也碰不到它（这里压根没有洞可点名）。
     *
     * <p>手算表（两张样板各 5 发、槽倍率 3、每模板 2 枚 ⇒ 每格整槽 30 枚）：
     * <pre>
     *   样板甲：声明 [雪块@2]，宇宙 [雪块@2]，计划供给雪块 30 ⇒ 排水实记 雪块 30 枚、族已在册不补封套
     *   样板乙：声明 [雪块@2 首位, 铜@2]，宇宙 [铜@2, 雪块@2] ⇒ 排水付满铜 30 枚，
     *          取料点按声明顺位真伸手的是雪块 ⇒ 乙张记封套 雪块 30 枚
     *   执行侧供给（usedItems）= 雪块 30 + 铜 30
     *   实记 = 整本 − 封套 = {雪块 30, 铜 30} ⇒ 两笔都被盖住 ⇒ 一个洞都不许有
     *   —— 拿整本当 need：雪块 need = 30 + 30 = 60 &gt; 30 ⇒ 凭空给雪块立一笔洞
     * </pre>
     */
    @Test
    void envelopeOnAFamilyThatDrainAlreadyPaidNeverEntersTheHole() {
        var paid = new PaidFamilyDetails(Items.REPEATER,
                new GenericStack(SNOW, UNITS_PER_TEMPLATE));
        var sibling = new PaidFamilyDetails(Items.COMPARATOR,
                new GenericStack(SNOW, UNITS_PER_TEMPLATE), new GenericStack(COPPER, UNITS_PER_TEMPLATE));
        var bill = paidFamilyBill(paid, sibling);

        // 夹具前提（不是被判据）：雪块一族在甲张有实记、在乙张只有封套——少了这一对，下面那条
        // assertFalse 就是天然绿，"整本当 need"这一型根本没被量到
        var paidRow = bill.assigned().get(paid);
        assertNotNull(paidRow, () -> "样板甲的表必须下发，否则这一臂不经过减式, 整本=" + bill.assigned());
        assertEquals(WHOLE_SLOT_UNITS, paidRow.getLong(SNOW), "甲张排水实记：雪块 30 枚");
        var paidEnvelope = bill.envelopes().get(paid);
        assertTrue(paidEnvelope == null || paidEnvelope.getLong(SNOW) == 0L,
                () -> "族已在册就不补封套（甲张这一本不许同时有实记与封套）, 封套=" + paidEnvelope);
        var siblingRow = bill.assigned().get(sibling);
        assertNotNull(siblingRow, () -> "样板乙的表必须下发, 整本=" + bill.assigned());
        assertEquals(WHOLE_SLOT_UNITS, siblingRow.getLong(COPPER), "乙张排水实记：铜块 30 枚");
        assertEquals(WHOLE_SLOT_UNITS, siblingRow.getLong(SNOW), "乙张封套：雪块 30 枚（许可上界）");
        assertEquals(WHOLE_SLOT_UNITS, bill.envelopes().get(sibling).getLong(SNOW),
                () -> "雪块那一族的封套确实记在乙张这一本上：被减数不是空的，这一臂才有东西可减");

        var used = new KeyCounter();
        used.add(SNOW, WHOLE_SLOT_UNITS);
        used.add(COPPER, WHOLE_SLOT_UNITS);
        var plan = planOf(List.of(paid, sibling), bill, used);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(" src=plan"), () -> "读的是照单采用那一臂：" + line);
        assertFalse(line.contains(SNOW + " hole="),
                () -> "雪块实记 30 枚被现场 30 枚盖住，乙张那一笔只是许可上界：出现雪块的 hole= 就是"
                        + "又拿整本当 need（#199 这一型在旧夹具里与真需求洞同数，谁也分不出来）。整行=" + line);
        assertFalse(line.contains(COPPER + " hole="),
                () -> "铜块实记同样被现场盖住：" + line);
    }

    /**
     * {@code holes} 的<b>必含格</b>：本窗 {@code blocked=}／{@code 回补受阻} 点名的键，即使按大小
     * 排不进前 8 也必须成行（台账 #199 回炉第二刀，与 {@code alloc=} 同一口径、同一只渲染器）。
     *
     * <p>夹具：一张样板的配给表上九个键各欠一笔（90、80、…、10 枚），封套本为空 ⇒ 九个洞都出自
     * 排水实记那一腿。可见区封顶前 8 ⇒ 最小的那一枚（青金石 {@code hole=10}）自然被挤出去。
     * 两臂一起钉："没点名就不出现"证明下面那一行真是必含格给的、不是窗口给的；点名之后必须逐字成行，
     * 且用的还是同一串格名（{@code hole}/{@code uncoveredNeed}/{@code want}）。
     */
    @Test
    void keyNamedByBlockedCellGetsARowEvenWhenItLosesTheOrdering() {
        var source = new EnvelopeDetails();
        var namedKey = AEItemKey.of(Items.LAPIS_LAZULI);
        AEItemKey[] holes = {GLASS, SNOW, AEItemKey.of(Items.GOLD_INGOT), COPPER,
                AEItemKey.of(Items.DIAMOND), AEItemKey.of(Items.EMERALD),
                AEItemKey.of(Items.REDSTONE), AEItemKey.of(Items.COAL), namedKey};
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        var row = new WideKeyCounter();
        for (int i = 0; i < holes.length; i++) {
            row.add(holes[i], 90L - 10L * i);   // 90、80、…、10：点名的那一枚排最后
        }
        assigned.put(source, row);
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(source, FIRINGS);
        var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                Map.of(), assigned, null, null, Set.of(),
                new IdentityHashMap<IPatternDetails, WideKeyCounter>());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var unnamed = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertTrue(unnamed.contains(" unbalanced=9/"),
                () -> "九个洞都在账上（可见区封顶前 8 是分母那一档，不裁账）：" + unnamed);
        assertFalse(unnamed.contains(namedKey + " hole="),
                () -> "前提：这一枚按大小确实排不进前 8（否则下面那条判据没有牙）：" + unnamed);

        var named = ParallelCraftingCpuLogic.describePlanBalance(job, plan, List.of(namedKey));
        assertTrue(named.contains(namedKey + " hole=10 "),
                () -> "被 blocked=／回补受阻 点名的键，排序落后面也必须成行（同一只渲染器、同一串格名）："
                        + named);
        assertTrue(named.contains(" unbalanced=9/"),
                () -> "必含格只补行，不改判决也不改缺口计数：" + named);
    }

    /** 真闸门那一趟账：下发的整本 {GLASS 30, SNOW 30} + 封套本 {GLASS 30}。 */
    private static MaterialClosureGate.MaterialPlanBill shippedBill(EnvelopeDetails source) {
        var pattern = new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                        GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(SNOW, UNITS_PER_TEMPLATE),
                                new GraphInput.Candidate(GLASS, UNITS_PER_TEMPLATE)),
                        Set.of(GLASS, SNOW), false)));
        var graph = new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GLASS, 0L, SNOW, SNOW_PLAN_SUPPLY),
                Set.of(), Map.of(), false, null, 1, 1);
        var state = new GraphPlanningState(
                Map.of(GLASS, 0L, SNOW, SNOW_PLAN_SUPPLY), Map.of());
        state.addCrafting(source, FIRINGS);
        // 玻璃计划供给 0：不提取 ⇒ 该族在排水里实吃恒 0，只剩封套那一笔
        state.extract(SNOW, SNOW_PLAN_SUPPLY, false);

        var bill = MaterialClosureGate.buildMaterialPlan(state, graph, FIRINGS);
        // 先钉夹具本身：白卷（表空）时执行侧走自算臂，下面所有断言都会失去落点
        var shipped = bill.assigned().get(source);
        assertNotNull(shipped, () -> "夹具必须真下发这张表，否则 hole 那条臂根本不经过这一刀");
        assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(GLASS), "下发表的封套那一笔");
        assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(SNOW), "下发表的排水实记那一笔");
        return bill;
    }

    /**
     * 把闸门那两本账装进真契约：{@code usedItems} 是执行侧供给里"从网络取到"那一腿，
     * 由调用方按 A/B 两例给（40 枚 ⇒ 无洞；10 枚 ⇒ 雪块真洞 20 枚）。
     */
    private static RecursiveCraftingPlan planOf(
            EnvelopeDetails source, MaterialClosureGate.MaterialPlanBill bill, long snowUsed) {
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(source, FIRINGS);
        var used = new KeyCounter();
        used.add(SNOW, snowUsed);
        var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                used, new KeyCounter(), new KeyCounter(), times);
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        assigned.putAll(bill.assigned());
        var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        envelopes.putAll(bill.envelopes());
        return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                Map.of(), assigned, null, null, Set.of(), envelopes);
    }

    /**
     * "同一族在甲张有排水实记、在乙张只有封套"那一型真闸门那一趟账。
     *
     * <p>甲张：声明与宇宙都是 [雪块@2]、计划供给 30 ⇒ 排水付满雪块 30、族已在册 ⇒ 不补封套。
     * 乙张：声明 [雪块@2 首位, 铜@2]、宇宙 [铜@2, 雪块@2] ⇒ 排水按宇宙顺位付满铜 30，
     * 取料点按声明顺位真伸手的是雪块 ⇒ 补封套雪块 30。两张各 5 发、槽倍率 3。
     */
    private static MaterialClosureGate.MaterialPlanBill paidFamilyBill(
            IPatternDetails paid, IPatternDetails sibling) {
        var paidPattern = new GraphPattern(paid, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(SNOW, UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                        GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(SNOW, UNITS_PER_TEMPLATE)),
                        Set.of(SNOW), false)));
        var siblingPattern = new GraphPattern(sibling, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(SNOW, UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                        GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(COPPER, UNITS_PER_TEMPLATE),
                                new GraphInput.Candidate(SNOW, UNITS_PER_TEMPLATE)),
                        Set.of(SNOW, COPPER), false)));
        var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
        bySource.put(paid, paidPattern);
        bySource.put(sibling, siblingPattern);
        var graph = new CraftGraph(IRON,
                Map.of(IRON, List.of(paidPattern, siblingPattern)),
                bySource,
                List.of(paid, sibling),
                Map.of(), Map.of(), Map.of(),
                Map.of(SNOW, WHOLE_SLOT_UNITS, COPPER, WHOLE_SLOT_UNITS),
                Set.of(), Map.of(), false, null, 1, 1);
        var state = new GraphPlanningState(
                Map.of(SNOW, WHOLE_SLOT_UNITS, COPPER, WHOLE_SLOT_UNITS), Map.of());
        state.addCrafting(paid, FIRINGS);
        state.addCrafting(sibling, FIRINGS);
        state.extract(SNOW, WHOLE_SLOT_UNITS, false);
        state.extract(COPPER, WHOLE_SLOT_UNITS, false);
        return MaterialClosureGate.buildMaterialPlan(state, graph, FIRINGS);
    }

    /** 两张样板装进同一份契约：任务表各 {@value #FIRINGS} 发，执行侧供给的存量那一腿由调用方给。 */
    private static RecursiveCraftingPlan planOf(List<IPatternDetails> sources,
            MaterialClosureGate.MaterialPlanBill bill, KeyCounter used) {
        var times = new LinkedHashMap<IPatternDetails, Long>();
        for (var source : sources) {
            times.put(source, FIRINGS);
        }
        var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                used, new KeyCounter(), new KeyCounter(), times);
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        assigned.putAll(bill.assigned());
        var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        envelopes.putAll(bill.envelopes());
        return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                Map.of(), assigned, null, null, Set.of(), envelopes);
    }

    /**
     * 两型夹具的样板替身：definition 由入参定（两张样板必须彼此可分），原始候选顺位由入参给。
     * 槽倍率 3、无退还键（非模具槽），与 {@link EnvelopeDetails} 同形。
     */
    private static final class PaidFamilyDetails implements IPatternDetails {
        private final AEItemKey definition;
        private final GenericStack[] rawCandidates;

        private PaidFamilyDetails(net.minecraft.world.item.Item definition,
                GenericStack... rawCandidates) {
            this.definition = AEItemKey.of(definition);
            this.rawCandidates = rawCandidates;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new EnvelopeInput(rawCandidates) };
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(IRON, 1L));
        }

        @Override
        public AEItemKey getDefinition() {
            return this.definition;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    /**
     * 真候选表的样板替身：原始顺位 [玻璃@6 首位, 雪块@2 备选]、槽倍率 3、无退还键（非模具槽）。
     * {@code getInputs()} 必须交回<b>真的</b>候选表，否则 {@code billReachKeySlot} 对早退。
     * 玻璃声明那枚每发 6 枚而规划宇宙那枚每发 2 枚，是"封套上界 ≠ 每发真要吃量"这一档的夹具本体。
     */
    private static final class EnvelopeDetails implements IPatternDetails {

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new EnvelopeInput(
                    new GenericStack(GLASS, DECLARED_GLASS_UNITS),
                    new GenericStack(SNOW, UNITS_PER_TEMPLATE)) };
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(IRON, 1L));
        }

        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(Items.REPEATER);
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    /** 模糊原始槽：倍率 3、两个候选、无退还键。 */
    private static final class EnvelopeInput implements IPatternDetails.IInput {
        private final GenericStack[] candidates;

        private EnvelopeInput(GenericStack... candidates) {
            this.candidates = candidates;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates;
        }

        @Override
        public long getMultiplier() {
            return SLOT_MULTIPLIER;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return true;
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
