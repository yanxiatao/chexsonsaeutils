package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2d B4 —— 守恒闸门自测：账目与 crafts 不一致的失衡形态必须被拦下
 * （闭式符号错误的后果是「缺料计划判可行」），一致账目必须放行。测试直接
 * 构造 state 与 graph，不经过 solve（solve 侧口径由等价对拍锁）。
 */
class MaterialClosureGateTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey SAND = AEItemKey.of(Items.SAND);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
    /** 实机判决里那条自喂槽的键（{@code material closure violated: minecraft:lava}）。 */
    private static final AEKey LAVA = appeng.api.stacks.AEFluidKey.of(
            net.minecraft.world.level.material.Fluids.LAVA);
    private static final AEKey WATER = appeng.api.stacks.AEFluidKey.of(
            net.minecraft.world.level.material.Fluids.WATER);
    /**
     * 幽灵候选（实机 {@code mekansismsun:artificial_sun_port} 那一枚的形状）：网络 0 枚、图上没有生产者、
     * 该槽 {@code isValid} 不收它 ⇒ 它<b>只</b>出现在 AE2 原始候选表首位，规划宇宙里根本没有它。
     */
    private static final AEKey GHOST = AEItemKey.of(Items.STRUCTURE_VOID);
    /** 备选候选：规划宇宙里有供给，取料点顺位退到它才真付得到。 */
    private static final AEKey PAID = AEItemKey.of(Items.EMERALD);
    /**
     * 同族（同一物品本体）两枚变体：#199 回炉那一型的夹具——{@code isValid} 只收全新那一枚，
     * 声明顺位首位却是被挡下的重损镐，两枚每发件数还不一样（6 枚 vs 2 枚）。
     */
    private static final AEKey WORN = wornKey(50);
    private static final AEKey PRISTINE = wornKey(0);

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    /** 同一物品本体的两个键：带 damage 组件的镐，受损程度不同就是不同的 AEKey（同族不同枚）。 */
    private static AEItemKey wornKey(int damage) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }

    /** IRON×1 ← GOLD×2 的单样板图。 */
    private static CraftGraph graph(GraphPattern pattern) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GOLD, 100L),
                java.util.Set.of(),
                Map.of(), false, null, 1, 1);
    }

    private static GraphPattern ironFromGoldPattern() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, 2L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    @Test
    void balancedLedgerPasses() {
        var pattern = ironFromGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(pattern.source(), 10);
        state.extract(GOLD, 20, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
        assertNull(violation, () -> "平衡账目不应被拦：" + violation);
    }

    /** 每发吃 3e12 枚金：两张样板各 3.1e6 发 ⇒ 该键需求 1.8e19，远超 long 上限。 */
    private static GraphPattern hugeGoldPattern() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, 3_000_000_000_000L, 1L,
                        GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    private static CraftGraph twoPatternGraph(GraphPattern first, GraphPattern second) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(first, second)),
                Map.of(first.source(), first, second.source(), second),
                List.of(first.source(), second.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GOLD, 100L),
                java.util.Set.of(),
                Map.of(), false, null, 1, 1);
    }

    /**
     * 实机形态：请求 {@code ae2:creative_storage_cell x 1000000} 把 lava 需求顶到 1.6e19。
     * 求解侧每个站点只能按 long 记缺料，但缺料账本本身是宽域的（{@code WideKeyCounter}）——
     * 两笔天花板合计 1.8e19，正好把守恒式配平。闸门必须读宽域那本账：读 narrow 的
     * {@code missingItems()} 会把缺口截成一个 long，"账不平"就成了假象，整条规划被拒、
     * 连计划页面都进不去。
     */
    @Test
    void wideMissingLedgerClosesTheConservationCheckBeyondLong() {
        var first = hugeGoldPattern();
        var second = hugeGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(first.source(), 3_000_000L);
        state.addCrafting(second.source(), 3_000_000L);
        // 两个站点各自把缺料记到 long 天花板：AE2 边界只能表达到这里，账本内部是宽域。
        state.addMissing(GOLD, Long.MAX_VALUE);
        state.addMissing(GOLD, Long.MAX_VALUE);

        String violation = MaterialClosureGate.validate(
                state, twoPatternGraph(first, second), 6_000_000L);
        assertNull(violation, () -> "两笔天花板缺料合计已覆盖需求，闸门不该拒：" + violation);
    }

    /**
     * 需求项本身超 long、而发数装得进 long（实机大订单的常态）：三数乘积必须在<b>宽域</b>上算。
     *
     * <p>旧口径在这种形状下走 long 快路径的饱和乘算，每格都被钉成 {@code Long.MAX_VALUE} 再相加
     * ⇒ 供需两侧都不再是计划里那份数。钉得越早的那一侧就是被凭空造出来的缺口，规模越大越深：
     * 同一张样板小订单算得出、大订单报"材料不足"，而 {@code missing=0} 又说求解自己认为账是平的。
     */
    @Test
    void beyondLongDemandTermIsBilledOnItsExactValueNotTheCeiling() {
        var pattern = hugeGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        // 每发 3e12 枚 × 一千万发 = 3e19 枚，发数本身在 long 域内。
        state.addCrafting(pattern.source(), 10_000_000L);
        state.addMissing(GOLD, 1L);

        var shortage = new java.util.LinkedHashMap<AEKey, git.chexson.chexsonsaeutils.bigint.PlanAmount>();
        String violation = MaterialClosureGate.validate(
                state, graph(pattern), 10_000_000L, shortage);

        assertNotNull(violation, "真需求 3e19 只配了 1 枚，该拒");
        assertEquals(new java.math.BigInteger("29999999999999999999"),
                shortage.get(GOLD).exactValue(), "缺口账本身也要按真值记，不只判决文本");
        assertTrue(violation.contains("demand=30000000000000000000"), () -> violation);
        assertTrue(violation.contains("short=29999999999999999999"),
                () -> "缺口按真值算，不是天花板相减：" + violation);
    }

    /** 自喂催化槽（CONSUMED_RETURNING）：起步那一眼 = 每发量 × 槽倍率，本身就 > long。 */
    private static GraphPattern lavaSelfFeedingPattern() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(LAVA, 3_000_000_000_000L, 3_000_000_000L,
                        GraphInput.Mode.CONSUMED_RETURNING, null, null, null, false)));
    }

    /** 模具槽（RETURNED，投多少原样退多少）：同一条起步量，计费键与归还键都是它自己。 */
    private static GraphPattern waterMouldPattern() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(WATER, 5_000_000_000_000L, 4_000_000_000L,
                        GraphInput.Mode.RETURNED, WATER, null, null, false)));
    }

    /**
     * 实机 2026-09-24 13:50 的第一条判决证据：{@code short=9223372036854775807} <b>恰好</b>
     * 是 {@code Long.MAX_VALUE}，而 demand − supply 也正是这个数 ⇒ 需求侧还剩一条把单项钉成
     * 天花板的饱和腿，且它不在 {@code mul3} 上。
     *
     * <p>这条腿就是回流/自喂槽的<b>起步量</b>：整批只有首发射真实提取，所以它压根不乘发数，
     * 只算「每发量 × 槽倍率」。lava 这类自喂催化物在 {@code > long} 的计划里，这一乘自己就撞顶
     * （每发 3e12 枚 × 倍率 3e9 = 9e21 枚）。钉成天花板的这一项既不是需求也不是供给，
     * 而是凭空立在 demand 上的一格"看起来刚好差一个 long"的假缺口。
     */
    @Test
    void beyondLongSelfFeedingStartupIsBilledOnItsExactValueNotTheCeiling() {
        var pattern = lavaSelfFeedingPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(pattern.source(), 2L);

        var shortage = new java.util.LinkedHashMap<AEKey,
                git.chexson.chexsonsaeutils.bigint.PlanAmount>();
        String violation = MaterialClosureGate.validate(state, graph(pattern), 2L, shortage);

        assertNotNull(violation, "9e21 枚 lava 一枚没配，该拒");
        assertEquals(new java.math.BigInteger("9000000000000000000000"),
                shortage.get(LAVA).exactValue(), "自喂槽的起步量按真值入账，缺口账也按真值报");
        assertTrue(violation.contains("demand=9000000000000000000000"), () -> violation);
        assertTrue(violation.contains("short=9000000000000000000000"),
                () -> "缺口不能是天花板相减：" + violation);
        assertFalse(violation.contains("9223372036854775807"),
                () -> "判决里出现 Long.MAX_VALUE 就是那条饱和腿的指纹：" + violation);
    }

    /** 同一条尺子打在模具槽（RETURNED）上：起步量 2e22 枚，退回来的只有倍率那一份。 */
    @Test
    void beyondLongReturnedMouldStartupIsBilledOnItsExactValueNotTheCeiling() {
        var pattern = waterMouldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(pattern.source(), 2L);

        var shortage = new java.util.LinkedHashMap<AEKey,
                git.chexson.chexsonsaeutils.bigint.PlanAmount>();
        String violation = MaterialClosureGate.validate(state, graph(pattern), 2L, shortage);

        assertNotNull(violation, "2e22 枚水只有 4e9 枚退得回来，该拒");
        // demand = 5e12 × 4e9 = 2e22，supply = multiplier = 4e9 ⇒ short = 1.9999999999996e22
        assertEquals(new java.math.BigInteger("19999999999996000000000"),
                shortage.get(WATER).exactValue(), () -> violation);
        assertFalse(violation.contains("9223372036854775807"),
                () -> "模具槽那一眼也不能被钉成天花板：" + violation);
    }

    /**
     * 同一类截断也存在于"本作业产出"那本账：产料是逐笔累加的，两笔天花板合计 1.8e19，
     * narrow 视图会把它读回一个 long，于是需求侧又"假不平"。
     * （"已从网络提走"那本账是峰值语义，单键不可能越过一次 long 提取，故不在此构造。）
     */
    @Test
    void wideEmittedLedgerClosesTheConservationCheckBeyondLong() {        var first = hugeGoldPattern();
        var second = hugeGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(first.source(), 3_000_000L);
        state.addCrafting(second.source(), 3_000_000L);
        state.emitItems(GOLD, Long.MAX_VALUE);
        state.emitItems(GOLD, Long.MAX_VALUE);

        String violation = MaterialClosureGate.validate(
                state, twoPatternGraph(first, second), 6_000_000L);
        assertNull(violation, () -> "两笔天花板产出合计已覆盖需求，闸门不该拒：" + violation);
    }

    /** 反例：宽域合计仍盖不住需求，那就是真算料错误，必须照拦。 */
    @Test
    void shortWideMissingIsStillCaught() {
        var first = hugeGoldPattern();
        var second = hugeGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(first.source(), 3_100_000L);
        state.addCrafting(second.source(), 3_100_000L);
        state.addMissing(GOLD, 1_000L);
        state.addMissing(GOLD, 1_000L);

        assertNotNull(MaterialClosureGate.validate(
                        state, twoPatternGraph(first, second), 6_000_000L),
                "宽域缺料盖不住需求就是算料错误，不能放行");
    }

    @Test
    void underExtractedInputIsCaught() {
        // 输入消耗漏记形态：crafts 全量计 demand，used 只有一半——
        // 「缺料计划判可行」正是这个形态。
        var pattern = ironFromGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(pattern.source(), 10);
        state.extract(GOLD, 10, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
        assertNotNull(violation, "输入漏记必须被闸门拦下");
        assertTrue(violation.contains("gold_ingot"), violation);
    }

    @Test
    void outputShortfallAgainstFinalIsCaught() {
        // 产出凭空缩水形态：crafts 只有 5 次，final 却要 10——
        // supply(IRON)=5 < demand(IRON)=10。
        var pattern = ironFromGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(pattern.source(), 5);
        state.extract(GOLD, 10, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
        assertNotNull(violation, "产出缩水必须被闸门拦下");
        assertTrue(violation.contains("iron_ingot"), violation);
    }

    @Test
    void siblingComponentSupplyCannotSatisfyExactDemand() {
        var firstStack = new ItemStack(Items.AMETHYST_SHARD);
        firstStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("version-1"));
        var first = AEItemKey.of(firstStack);
        var secondStack = new ItemStack(Items.AMETHYST_SHARD);
        secondStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("version-2"));
        var second = AEItemKey.of(secondStack);
        Map<appeng.api.stacks.AEKey, List<appeng.api.stacks.AEKey>> variants =
                Map.of(first, List.of(first, second), second, List.of(first, second));
        var graph = new CraftGraph(second,
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(first, 1L), java.util.Set.of(), variants,
                false, null, 0, 0);
        var state = new GraphPlanningState(Map.of(first, 1L), variants);
        state.extract(first, 1L, false);

        String violation = MaterialClosureGate.validate(state, graph, 1L);

        assertNotNull(violation, "同一物品的其它组件版本不能满足精确需求");
        assertTrue(violation.contains("supply=0"), violation);
        assertTrue(violation.contains("demand=1"), violation);
    }

    /** 靠前候选扣不完退到次候选能补齐时，排水判据必须放行，不能一刀切拒绝模糊槽。 */
    @Test
    void fuzzySlotSpillingToNextCandidatePasses() {
        var pattern = ironFromFuzzySlot();
        var state = new GraphPlanningState(Map.of(GLASS, 400L, SAND, 60L), Map.of());
        state.addCrafting(pattern.source(), 100);
        state.extract(GLASS, 400, false);
        state.extract(SAND, 60, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 100);
        assertNull(violation, () -> "退路候选补得齐整批时不应被拦：" + violation);
    }

    /**
     * 实机 22:11 假拒形态：crafting_pattern 的模糊槽 multiplier=3，候选全是每份 1
     * 的玻璃族键，族内份数与需求逐字相等。一发在 AE2 的 {@code prepareSingleFiring}
     * 里跨候选续取，所以按"单键盖住整数发"收费会拒掉可执行的表——而末级算法也规划
     * 不出时 DECLINE 是终局，作业直接起不来。
     */
    @Test
    void fuzzySlotSplitsOneFiringAcrossCandidates() {
        var pattern = ironFromThreeUnitFuzzySlot();
        var state = new GraphPlanningState(Map.of(GLASS, 5L, SAND, 1L), Map.of());
        state.addCrafting(pattern.source(), 2);
        state.extract(GLASS, 5, false);
        state.extract(SAND, 1, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 2);
        assertNull(violation, () -> "份数恰好盖住需求时不应被拦：" + violation);
    }

    /**
     * 同槽跨候选回落不是算料错误（2026-09-19 21:58 实机定口径）：首选族整槽付不完时，
     * 排水把差的那几份记到同槽下一个候选键上，两键相加正好等于整槽应吃——实机那条槽
     * {@code refinedstorage:silicon=2570673696 + ae2:silicon=480 == firingNeed}，且 CPU 现场
     * 真有那 480 枚。这张表必须照常下发：扣表会把计划打回提交期重算，尾段反而更长。
     * 判决只留 WARN 行点名"哪张样板靠回落过关"。
     */
    @Test
    void payerFamilyPaidBySameSlotFallbackStillShipsTable() {
        var pattern = ironFromGlassOrSandFuzzy(3L);
        var glassProducer = glassFromSnowPattern();
        var state = new GraphPlanningState(Map.of(GLASS, 5L, SNOW, 1L), Map.of());
        state.addCrafting(pattern.source(), 2);
        state.extract(GLASS, 5, false);
        state.extract(SNOW, 1, false);

        var table = MaterialClosureGate.buildMaterialPlan(
                state, glassFuzzyGraph(pattern, glassProducer), 2L).assigned();

        var task = table.get(pattern.source());
        assertNotNull(task, "跨候选回落的整槽表不能扣下");
        // 账本换 WideKeyCounter 后的等价读法（本用例量级远未越界，窄读数即真值）
        assertEquals(6L, task.getLong(GLASS) + task.getLong(SNOW),
                () -> "整槽 3x2 枚必须由同槽候选合计付清, table=" + task);
    }

    /**
     * {@code billReachKeySlot} 的数值口径（台账 #114 的第一条钉）：封套记的是<b>整槽份额</b>。
     *
     * <p>形状：原始候选顺位 [玻璃首位, 雪块备选]，规划 ranked 把备选排在前先付清
     * （实机 lane c1fbde62 的"首位候选从没被排水付到"）。排水实记只有雪块 2 枚——玻璃族
     * 在本格的顺位实吃恒为 0（这正是它不进册的前提）；补记落下整槽份额
     * 槽倍率 1 × 每发 1 枚 × 2 发 = 2 枚。同槽两族并存、合计 4 枚 = 2× 槽需，这是 0fbec2b
     * 定版的封套口径（"执行侧若向首位族伸手，至多整格"），不是排水拆分。
     *
     * <p>本用例走 {@code buildMaterialPlan} 真入口逐键断言份数：谁把它收窄成"排水实吃"（=0）、
     * 改成"族已在册也重写"、或干脆不补记，这里就红。既有的两条相关用例守的是别的东西——
     * {@code ParallelDeclaredBatchObligationTest} 的 M3 两型（{@code planBalanceNamesReachKeys...}/
     * {@code planBalanceOmitsReachKeys...}）只断言日志字符串形状，且表是手搓的、根本不经过这一笔；
     * M1 端到端臂只断言"份额为正且两臂相等"，不钉这个数到底是几。
     */
    @Test
    void reachKeyEnvelopeShipsWholeSlotUnits() {
        var source = new ReachDetails();
        var pattern = reachEnvelopePattern(source);
        var table = MaterialClosureGate.buildMaterialPlan(
                reachEnvelopeState(source, 10L), reachEnvelopeGraph(pattern), 2L).assigned();

        var task = table.get(source);
        assertNotNull(task, "玻璃有 10 枚计划供给消化封套，表不该被扣下");
        assertEquals(2L, task.getLong(SNOW), "排水实付的那 2 枚记在备选键上");
        assertEquals(2L, task.getLong(GLASS),
                "补记按整槽份额（1×1×2 枚）入账——该族在本格的顺位实吃是 0，不是这一笔的数");
        assertEquals(4L, task.getLong(SNOW) + task.getLong(GLASS),
                () -> "封套与实记同格并存：该格合计 2× 槽需是定版形状, table=" + task);
    }

    /**
     * 封套不算排水实记（本刀的核心）。
     *
     * <p>改前的期望是"该族计划供给盖不住封套那一笔 ⇒ 整张配给表被判成白卷"——这条期望钉的正是
     * <b>缺陷形状</b>而非需求：封套记的是"执行侧若向首位族伸手，至多整格"的<b>许可上界</b>，
     * 不是计划真从这一族取走的量。首位族规划供给为 0 是常态，把封套当实取就会让整张配给表
     * 被顶回去 ⇒ 执行侧退回提交期自算那一臂（两臂宇宙不同 ⇒ 假洞 {@code hole=960}）。
     * 这是规模相关的假缺口。
     *
     * <p>改后期望：首位族规划供给为 0 时表照发、封套份额逐字不变（仍是整槽 1×1×2=2 枚）。
     * 供给恰好 2 枚时下发内容逐字相同——封套记不记进实记都不改<b>表本身</b>，只改"是否
     * 因它交白卷"。
     */
    @Test
    void reachKeyEnvelopeShipsWhenPrimaryFamilyUnsupplied() {
        var starved = new ReachDetails();
        var starvedGraph = reachEnvelopeGraph(reachEnvelopePattern(starved));
        assertNull(MaterialClosureGate.validate(reachEnvelopeState(starved, 0L), starvedGraph, 2L),
                "整槽由备选付满时守恒闸门闭合——封套的超配不进守恒判据");
        var table = MaterialClosureGate.buildMaterialPlan(
                reachEnvelopeState(starved, 0L), starvedGraph, 2L).assigned();
        var starvedTask = table.get(starved);
        assertNotNull(starvedTask,
                () -> "首位族规划供给为 0：封套不进需求 ⇒ 表照发（旧口径把整表顶成白卷）：" + table);
        assertEquals(2L, starvedTask.getLong(GLASS),
                "封套份额＝整槽 1×1×2 枚，逐字不变：记在表里给执行侧当上界，只是不算排水实记");
        assertEquals(2L, starvedTask.getLong(SNOW), "排水实付的 2 枚照记在备选键上");

        var covered = new ReachDetails();
        var coveredTask = MaterialClosureGate.buildMaterialPlan(
                reachEnvelopeState(covered, 2L), reachEnvelopeGraph(reachEnvelopePattern(covered)), 2L)
                .assigned().get(covered);
        assertNotNull(coveredTask, "该族供给恰好盖得住封套那一笔时表必须下发");
        assertEquals(2L, coveredTask.getLong(GLASS), "下发的那一笔仍是整槽份额，与上一例逐字相同");
        assertEquals(2L, coveredTask.getLong(SNOW), "排水实记不受这一崖口影响");
    }

    /**
     * "封套不算排水实记"的第二条独立数值钉（期望来自手算 oracle，不用被测乘式重算）。
     *
     * <p>手算表（每份几、几发、该记多少）：
     * <pre>
     *   槽倍率 = 3   每模板量 = 2 枚   发数 = 5
     *   整槽需求 = 3 × 5 = 15 模板 = 15 × 2 = 30 枚
     *   排水实付(备选 SNOW) = 15 模板 × 每模板 2 枚 = 30 枚   （玻璃现场量为 0，付不到）
     *   封套(首位 GLASS)   = 每发 2 枚 × 槽倍率 3 × 5 发     = 30 枚（=整槽，许可上界）
     *   SNOW 计划供给 = 40 枚(used)   GLASS 计划供给 = 0 枚
     *   ⇒ 排水实记（剔掉封套那一本后）只剩 SNOW 30 ≤ 40 ⇒ 表照发；GLASS 份额 30、SNOW 份额 30
     * </pre>
     * 未修主代码上：零来路的封套 GLASS 30 被塞进 demand、计划供给 0 ⇒ 守恒判据把这一格判成平
     * 不过 ⇒ {@code Map.of()} ⇒ {@code table.get(source)} 为 null，本用例 {@code assertNotNull} 直接红。
     */
    @Test
    void envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied() {
        var source = new ReachDetails(new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L));
        var state = envelopeStarvedState(source);

        var task = MaterialClosureGate.buildMaterialPlan(
                state, envelopeStarvedGraph(source), 5L).assigned().get(source);
        assertNotNull(task, "首位族计划供给 0：封套不进需求 ⇒ 表照发");
        assertEquals(30L, task.getLong(GLASS), "封套＝整槽 2×3×5=30 枚（许可上界）");
        assertEquals(30L, task.getLong(SNOW), "排水实付＝15 模板 × 每模板 2 枚 = 30 枚");
        assertEquals(60L, task.getLong(SNOW) + task.getLong(GLASS),
                () -> "封套与实记同格并存：合计 2× 槽需 30 枚是封套口径, table=" + task);
    }

    /**
     * 封套那一本要<b>随契约一起下发</b>（本刀的另一半）：执行侧 {@code hole=} 拿它当被减数，
     * 不减就把"整格许可上界"当"计划要吃却没来源的料"报成假洞。
     *
     * <p>夹具与期望都沿用 {@code envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied} 那张手算表
     * （槽倍率 3 × 每模板 2 枚 × 5 发 = 整槽 30 枚；备选 SNOW 付满 30 枚、首位 GLASS 实吃 0）：
     * <pre>
     *   assigned（下发的整本）: GLASS = 30（封套）  SNOW = 30（排水实记）
     *   envelopes（封套另本）  : GLASS = 30          SNOW = 不落账
     *   相减剩下的实记          : GLASS = 0           SNOW = 30
     * </pre>
     * 两头各钉一刀：① 这本交空 ⇒ 执行侧那侧就把上界当实记（假洞 {@code hole=30}，还按大小
     * 排在真洞前面）；② 顺手把封套从<b>下发表</b>里也摘掉 ⇒ 执行侧退回 {@code noBasisReach}
     * 那一型（首位族现场有量却夹成 0）。所以两本都要逐字对。
     */
    @Test
    void envelopeBookShipsAsSeparateLedgerWithoutTouchingTheTable() {
        var source = new ReachDetails(new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L));
        var bill = MaterialClosureGate.buildMaterialPlan(
                envelopeStarvedState(source), envelopeStarvedGraph(source), 5L);

        var shipped = bill.assigned().get(source);
        var booked = bill.envelopes().get(source);
        assertNotNull(shipped, "下发的整本里封套那一笔不许被摘掉");
        assertNotNull(booked, "封套那一本必须随同一趟账出门（这本账是 hole 的被减数）");
        assertEquals(30L, booked.getLong(GLASS), "同一笔值：与下发表里那一笔逐字相同");
        assertEquals(shipped.getLong(GLASS), booked.getLong(GLASS),
                () -> "封套在下发表与封套本是同一笔，不许两处各算一份, 表=" + shipped);
        assertEquals(0L, booked.getLong(SNOW),
                "排水实记不进封套本：减多了就把真洞也抹平（cover 与 hole 都会少核一笔）");
        assertEquals(30L, shipped.getLong(SNOW), "下发表的排水实记逐字不变");
    }

    /** 封套手算夹具的槽形：原始候选顺位 [玻璃@2 首位, 雪块@2 备选]，规划 ranked 备选在前。 */
    private static GraphPattern envelopeStarvedPattern(IPatternDetails source) {
        return new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 2L, 3L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(SNOW, 2L),
                                new GraphInput.Candidate(GLASS, 2L)),
                        java.util.Set.of(GLASS, SNOW), false)));
    }

    /** 封套手算夹具的图。 */
    private static CraftGraph envelopeStarvedGraph(IPatternDetails source) {
        var pattern = envelopeStarvedPattern(source);
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GLASS, 0L, SNOW, 40L),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
    }

    /** 封套手算夹具的账：5 发全跑，雪块提 40 枚，玻璃计划供给 0（不提取）。 */
    private static GraphPlanningState envelopeStarvedState(IPatternDetails source) {
        var state = new GraphPlanningState(Map.of(GLASS, 0L, SNOW, 40L), Map.of());
        state.addCrafting(source, 5L);
        state.extract(SNOW, 40L, false);
        return state;
    }

    /**
     * 计费键必须是执行侧真付得到的那一枚（台账 #194，实机 {@code ae2:creative_storage_cell x 1e11}
     * 尾段卡死那一格）。
     *
     * <p>实机那一格的读数：{@code slot#0[... 每发=1, 池内=0, 配额=2374101614985, 可付发=0]} 配
     * {@code 回补受阻[... got=0_why=ranDry_网络sim=0_同族键数=0_同族[]下界]}，同窗规划侧
     * {@code Graph planning wide supply truth: supply={}} 且全场零条 deferred shortage。
     * 那一枚配额就出自 {@code billReachKeySlot}：reach 键无条件取 AE2 原始候选表<b>首位</b>，
     * 不复核这一格真付得到付不到 ⇒ 网络里根本没有的键被记上一整槽天量 ⇒ 执行侧
     * {@code quotaExact}（{@code ParallelPlanReservation}:690-694）因 {@code hasFamilyEntry}
     * 为真而不交 null ⇒ 回补腿（{@code ParallelBatchExtraction}:1743）永远要补
     * {@code 2.37e12} 枚不存在的料，逐次 {@code got=0 ⇒ ranDry}（约 :1794/:1832）。
     *
     * <p>手算表（槽倍率 1 × 每模板 1 枚 × 4 发 = 整槽 4 枚；候选宇宙只收 {@code PAID}）：
     * <pre>
     *   原始候选顺位 = [GHOST@1 首位, PAID@1 备选]，isValid 只收 PAID
     *   规划 ranked  = [PAID@1]                    ⇒ GHOST 不在配给表的宇宙里
     *   排水实付(PAID) = 4 枚（used 4）  GHOST 网络 0 枚、无生产者
     *   ⇒ 期望：PAID 份额 4、GHOST 一枚都不进 assigned、也不进 envelopes
     * </pre>
     * GHOST 不在册 ⇒ {@code hasFamilyEntry} 为假 ⇒ 伸手口径（{@code slot != null}）对它交
     * {@code null}（"无配额口径"，不夹），而不是交一个执行侧永远凑不齐的天量。这条是本用例的
     * 下游推论（{@code ParallelPlanReservation} 是包级私有类，gate 级用例造不出它），
     * 所以这里把它的<b>前件</b>逐字钉死：这本账非空、且没有 GHOST 这一族。
     *
     * <p>未修主代码上应交出 {@code task.getLong(GHOST) == 4}（整槽封套记在幽灵键上）⇒ 红。
     */
    @Test
    void ghostFirstCandidateNeverEntersTheQuotaBook() {
        var source = ReachDetails.accepting(java.util.Set.of(PAID),
                new GenericStack(GHOST, 1L), new GenericStack(PAID, 1L));
        var pattern = ghostReachPattern(source);
        var state = ghostReachState(source);
        String violation = MaterialClosureGate.validate(state, ghostReachGraph(pattern), 4L);
        assertNull(violation, () -> "整槽由付得到的那一枚付满，守恒闸门该放行：" + violation);

        var bill = MaterialClosureGate.buildMaterialPlan(state, ghostReachGraph(pattern), 4L);
        var task = bill.assigned().get(source);
        assertNotNull(task, () -> "这一格付得到料，配给表不该交白卷：" + bill.assigned());
        assertEquals(4L, task.getLong(PAID), "排水实付的 4 枚照记在付得到的那一枚上");
        assertEquals(0L, task.getLong(GHOST),
                () -> "付不到的首位候选一枚都不许进配给表（它是回补腿那句 ranDry 的来路）, 表=" + task);
        assertFalse(hasFamilyEntry(task, GHOST),
                () -> "quotaExact 的前件：这本账非空却没这一族 ⇒ 伸手口径对它交 null, 表=" + task);
        var envelope = bill.envelopes().get(source);
        assertTrue(envelope == null || envelope.getLong(GHOST) == 0L,
                () -> "封套本同样不许给幽灵键记一笔, 封套=" + envelope);
    }

    /**
     * 首位付不到时按顺位<b>退到下一候选</b>计费，而不是摘掉这一笔（台账 #194 的另一半）。
     *
     * <p>沿用 {@link #envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied} 那张手算表
     * （槽倍率 3 × 每模板 2 枚 × 5 发 = 整槽 30 枚，备选 SNOW 付满 30、玻璃实吃 0），只在
     * 原始候选表<b>开头</b>多塞一枚付不到的幽灵：宇宙（候选 × {@code isValid}）仍然收玻璃，
     * 所以封套必须原样落在玻璃上＝30 枚，幽灵键 0 枚。
     *
     * <p>未修主代码上 reach 键是无条件的首位＝GHOST（每模板 1 枚）⇒ 封套记成 {@code 3×1×5=15}
     * 枚幽灵、玻璃那一笔根本没记 ⇒ 两条断言一起红。
     */
    @Test
    void reachKeyFallsBackToNextPayableCandidateWhenFirstIsGhost() {
        var source = ReachDetails.accepting(java.util.Set.of(GLASS, SNOW),
                new GenericStack(GHOST, 1L), new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L));

        var bill = MaterialClosureGate.buildMaterialPlan(
                envelopeStarvedState(source), envelopeStarvedGraph(source), 5L);
        var task = bill.assigned().get(source);
        assertNotNull(task, () -> "退到的那一枚付得到 ⇒ 表照发：" + bill.assigned());
        assertEquals(0L, task.getLong(GHOST),
                () -> "退让不许把幽灵键留在账上, 表=" + task);
        assertEquals(30L, task.getLong(GLASS),
                () -> "封套落在顺位退到的那一枚：2×3×5=30 枚, 表=" + task);
        assertEquals(30L, task.getLong(SNOW), "排水实记不受退让影响");
        var envelope = bill.envelopes().get(source);
        assertNotNull(envelope, "封套那一本仍要随同一趟账出门（hole= 的被减数）");
        assertEquals(30L, envelope.getLong(GLASS), "封套逐字不变，只是换了它记在哪一枚上");
    }

    /** 幽灵首位候选夹具的槽形：候选宇宙只收 PAID（GHOST 被 isValid 挡在规划宇宙外）。 */
    private static GraphPattern ghostReachPattern(IPatternDetails source) {
        return new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(PAID, 1L, 1L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(PAID, 1L)),
                        java.util.Set.of(PAID), false)));
    }

    /**
     * 族判据不覆盖"同族在册、声明精确键被 {@code isValid} 挡下"那一型（台账 #199 回炉第一刀）。
     *
     * <p>{@code 2527e4e} 换的是"整族都不在宇宙里"的幽灵键，落点仍是<b>声明那枚</b>的键与模板量；
     * 而宇宙判据只到族（同族有任一枚就算付得到）。同族里只有一枚在册、{@code reach} 恰是被挡下的
     * 那一枚时，封套记在挡下的那枚键与它的模板量上——执行侧顺位真伸手的是宇宙内那一枚，
     * 每发件数还不一样 ⇒ 照样记出一笔花不到的量（同一个形状，只是换了挡住的位置）。
     *
     * <p>手算表（槽倍率 3 × 5 发 ＝ 15 模板整格）：
     * <pre>
     *   原始候选顺位 = [重损镐@6 首位（isValid 不收）, 全新镐@2（宇宙内）, 雪块@2（备选）]
     *   规划 ranked  = [雪块@2, 全新镐@2]        ⇒ 这一族在宇宙里只有全新镐，每发 2 枚
     *   排水实付(SNOW) = 15 模板 × 2 枚           = 30 枚（计划供给 40 付满整格）
     *   封套  期望 = 倍率 3 × 每发 2 枚 × 5 发    = 30 枚，记在<b>全新镐</b>那一枚上
     *   HEAD 实得 = 记在<b>重损镐</b>上、按它自己那一份 6 枚 = 3×6×5 = 90 枚
     * </pre>
     * 未修主代码上 {@code task.getLong(PRISTINE)} 交 0（那一枚压根没被记）、
     * {@code task.getLong(WORN)} 交 90 ⇒ 三条断言一起红。
     */
    @Test
    void envelopeIsBilledOnTheInUniverseVariantNotTheBlockedDeclaredSibling() {
        var source = ReachDetails.accepting(java.util.Set.of(SNOW, PRISTINE),
                new GenericStack(WORN, 6L), new GenericStack(PRISTINE, 2L),
                new GenericStack(SNOW, 2L));

        var bill = MaterialClosureGate.buildMaterialPlan(
                sameFamilyEnvelopeState(source), sameFamilyEnvelopeGraph(source), 5L);
        var task = bill.assigned().get(source);
        assertNotNull(task, () -> "整格由雪块付满 ⇒ 表照发，不该交白卷：" + bill.assigned());
        assertEquals(30L, task.getLong(SNOW), "排水实记逐字不变（3×5 模板 × 每发 2 枚）");
        assertEquals(30L, task.getLong(PRISTINE),
                () -> "封套必须落在宇宙内真付得到的那一枚、按它那一份每发件数（3×2×5=30）, 表=" + task);
        assertEquals(0L, task.getLong(WORN),
                () -> "被 isValid 挡下的那一枚一枚都不许进配给表（它就是回补腿 got=0 ⇒ ranDry 的来路）, 表="
                        + task);
        var envelope = bill.envelopes().get(source);
        assertNotNull(envelope, "封套那一本仍要随同一趟账出门（hole= 的被减数）");
        assertEquals(30L, envelope.getLong(PRISTINE), "封套本与下发表同一笔、同一个键");
        assertEquals(0L, envelope.getLong(WORN), "封套本也不许给挡下的那一枚记一笔");
    }

    /**
     * 正向臂（同一把尺的另一侧）：首位声明候选<b>本来就在宇宙内</b>时，键与件数逐字不变。
     *
     * <p>与反向臂同一份宇宙、同一串乘式，只把被挡下的那一枚从首位撤掉 ⇒ 封套仍落在
     * 全新镐 30 枚、雪块实记仍 30 枚。这一例在改前改后都必须绿：换尺不许动已经对的那一型。
     */
    @Test
    void envelopeStaysOnTheSameNumbersWhenTheDeclaredFirstCandidateIsTheUniverseOne() {
        var source = ReachDetails.accepting(java.util.Set.of(SNOW, PRISTINE),
                new GenericStack(PRISTINE, 2L), new GenericStack(SNOW, 2L));

        var bill = MaterialClosureGate.buildMaterialPlan(
                sameFamilyEnvelopeState(source), sameFamilyEnvelopeGraph(source), 5L);
        var task = bill.assigned().get(source);
        assertNotNull(task, () -> "正向臂前提：表照发：" + bill.assigned());
        assertEquals(30L, task.getLong(PRISTINE), "首位就在宇宙内 ⇒ 封套的键与量逐字不变");
        assertEquals(30L, task.getLong(SNOW), "排水实记逐字不变");
        assertEquals(0L, task.getLong(WORN), "这一型里重损镐从未被声明，表上不该凭空多它一族");
    }

    /** 同族两枚夹具的图：雪块计划供给 40、镐的两枚变体都是 0（这一族只剩封套那一笔）。 */
    private static CraftGraph sameFamilyEnvelopeGraph(IPatternDetails source) {
        var pattern = new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(WORN, 6L, 3L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(SNOW, 2L),
                                new GraphInput.Candidate(PRISTINE, 2L)),
                        java.util.Set.of(SNOW, PRISTINE), false)));
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(SNOW, 40L, WORN, 0L, PRISTINE, 0L),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
    }

    /** 同族两枚夹具的账：5 发全跑，只从雪块提 40 枚（镐那一族计划供给 0）。 */
    private static GraphPlanningState sameFamilyEnvelopeState(IPatternDetails source) {
        var state = new GraphPlanningState(Map.of(SNOW, 40L, WORN, 0L, PRISTINE, 0L), Map.of());
        state.addCrafting(source, 5L);
        state.extract(SNOW, 40L, false);
        return state;
    }

    /** 幽灵首位候选夹具的图：GHOST 网络 0 枚、图上没有它的生产者。 */
    private static CraftGraph ghostReachGraph(GraphPattern pattern) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GHOST, 0L, PAID, 4L),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
    }

    /** 幽灵首位候选夹具的账：4 发全跑，只从 PAID 提 4 枚。 */
    private static GraphPlanningState ghostReachState(IPatternDetails source) {
        var state = new GraphPlanningState(Map.of(GHOST, 0L, PAID, 4L), Map.of());
        state.addCrafting(source, 4L);
        state.extract(PAID, 4L, false);
        return state;
    }

    /**
     * 与 {@code ParallelPlanReservation#hasFamilyEntry} 同一口径（物品本体族、含 0 值条目）：
     * 这里是"伸手口径有没有配额基准"的前件读数，不是第二道判据。
     */
    private static boolean hasFamilyEntry(WideKeyCounter amounts, AEKey key) {
        var family = key.getPrimaryKey();
        for (AEKey candidate : amounts.keys()) {
            if (candidate != null && family.equals(candidate.getPrimaryKey())) {
                return true;
            }
        }
        return false;
    }


    /**
     * 条目①（任务 #219）在<b>窄量级</b>上的同一型：排水<b>实记</b>由 {@code emitted} 那一腿盖住时，
     * 表必须照发。
     *
     * <p>手算表：单候选模糊槽（候选只有玻璃），每发 1 枚 × 槽倍率 1 × 2 发 = 2 枚，全额由<b>排水
     * 实付</b>记在玻璃键上（该族已被本格排水付到、族已在册，故不补封套）。玻璃的计划供给只来自
     * {@code emitted}（2 枚）。
     *
     * <p><b>这一臂原来钉的是两套宇宙并存的假白卷</b>：旧 cover 自己拼腿（只认「自产 + used +
     * 环种子」、不认 {@code emitted}），于是同一趟账里闸门判平的表在出口被判"超配"、整张扣下
     * ——执行侧退回提交期自算那一臂。并成一套账后这里读到的就是 {@code 2 >= 2}：判决照旧是
     * "超配才扣表"，只是这一判用的同一把尺不再凭空造缺口（越界那一型见
     * {@link #supplyOnlyInEmittedLegBeyondLongStillShipsTheTable}）。
     */
    @Test
    void drainClaimFundedByEmittedLegShipsTable() {
        var source = new FakeDetails();
        var pattern = new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 1L, 1L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(GLASS, 1L)),
                        java.util.Set.of(GLASS), false)));
        var state = new GraphPlanningState(Map.of(GLASS, 0L), Map.of());
        state.addCrafting(source, 2L);
        state.emitItems(GLASS, 2L);   // 计划自产那一腿：emitted 就是这份表的来路
        assertNull(MaterialClosureGate.validate(state, graph(pattern), 2L),
                "守恒/排水由 emitted 盖住 ⇒ 闸门放行，才轮到 cover 核排水实记");

        var bill = MaterialClosureGate.buildMaterialPlan(state, graph(pattern), 2L);
        var shipped = bill.assigned().get(source);
        assertNotNull(shipped, () -> "排水实记由 emitted 腿盖住（2 >= 2），cover 不许凭空判超配交白卷："
                + bill);
        assertEquals(2L, shipped.getLong(GLASS), "下发的就是排水实记那 2 枚");
        assertTrue(bill.envelopes().isEmpty(),
                () -> "该族已被本格排水付到、族已在册 ⇒ 不补封套，封套本照旧交空：" + bill.envelopes());
    }

    /** 封套用例的槽形：规划 ranked [雪块, 玻璃]（备选在前），原始候选 [玻璃, 雪块]。 */
    private static GraphPattern reachEnvelopePattern(IPatternDetails source) {
        return new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 1L, 1L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(SNOW, 1L),
                                new GraphInput.Candidate(GLASS, 1L)),
                        java.util.Set.of(GLASS, SNOW), false)));
    }

    /** 封套用例的图：目标 IRON，雪块与玻璃都在候选宇宙内。 */
    private static CraftGraph reachEnvelopeGraph(GraphPattern pattern) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GLASS, 10L, SNOW, 10L),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
    }

    /** 封套用例的账：2 发全跑，雪块提 10 枚，玻璃提 {@code reachUsed} 枚（0 即"现场量为 0"）。 */
    private static GraphPlanningState reachEnvelopeState(IPatternDetails source, long reachUsed) {
        var state = new GraphPlanningState(Map.of(GLASS, reachUsed, SNOW, 10L), Map.of());
        state.addCrafting(source, 2L);
        state.extract(SNOW, 10L, false);
        if (reachUsed > 0L) {
            state.extract(GLASS, reachUsed, false);
        }
        return state;
    }

    /**
     * 封套用例的样板替身：{@code getInputs()} 必须交回<b>真的</b> AE2 候选表——
     * 既有 {@link FakeDetails} 的 {@code getInputs()} 是空数组，{@code billReachKeySlot}
     * 对早退，这一笔在旧的 gate 级用例里从未被跑到（台账 #114 第 3 点的根因）。
     */
    private static final class ReachDetails implements IPatternDetails {
        private final GenericStack[] rawCandidates;
        /** 该槽 {@code isValid} 真收的键；{@code null}＝全收（沿用既有封套夹具的声明形状）。 */
        private final @Nullable java.util.Set<AEKey> validInputs;

        /** 默认原始候选顺位 [玻璃@1 首位, 雪块@1 备选]，全收。 */
        ReachDetails() {
            this(new GenericStack(GLASS, 1L), new GenericStack(SNOW, 1L));
        }

        /** 自定义原始候选表（首位即 {@code billReachKeySlot} 的 reach 键），{@code isValid} 全收。 */
        ReachDetails(GenericStack... rawCandidates) {
            this(rawCandidates, null);
        }

        /** 自定义原始候选表 + {@code isValid} 真收的键集：收不到的候选不该被计费。 */
        static ReachDetails accepting(java.util.Set<AEKey> validInputs, GenericStack... rawCandidates) {
            return new ReachDetails(rawCandidates, validInputs);
        }

        private ReachDetails(GenericStack[] rawCandidates, @Nullable java.util.Set<AEKey> validInputs) {
            this.rawCandidates = rawCandidates;
            this.validInputs = validInputs;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new ReachInput(validInputs, rawCandidates) };
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return List.of(new GenericStack(IRON, 1L));
        }

        @Override
        public AEItemKey getDefinition() {
            return key(Items.REPEATER);
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    /** 原始模糊槽：顺位按给定数组、倍率 1、无退还键（非模具槽）；{@code validInputs} 为 null 时全收。 */
    private static final class ReachInput implements IPatternDetails.IInput {
        private final GenericStack[] candidates;
        private final @Nullable java.util.Set<AEKey> validInputs;

        private ReachInput(@Nullable java.util.Set<AEKey> validInputs, GenericStack... candidates) {
            this.candidates = candidates;
            this.validInputs = validInputs;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates;
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return validInputs == null || validInputs.contains(input);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    /**
     * 规划出口的配给表是执行侧配额账的<b>唯一上游</b>，它自己必须先留得住宽域：
     * 同一张样板两个定键槽各记 9e18 枚（单笔都装得进 {@code long}），合计 1.8e19 枚越界。
     *
     * <p>老口径这张表是 {@code Map<AEKey, Long>}，逐笔合并走饱和加 ⇒ 表上写下的是
     * {@link Long#MAX_VALUE}，执行侧照这份数取料就凭空少掉 8.78e18 枚配额（第五刀的断头）。
     * 现在整张表是 {@code WideKeyCounter}：账上记真值，AE2 那份 {@code long} 读数仍是饱和哨兵，
     * 但没人拿它当配额口径。
     */
    @Test
    void plannedMaterialQuotaAboveLongShipsAsExactValue() {
        var miner = goldMinePattern();
        var consumer = twoGoldSlotPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        // 金由自产样板盖住：每发 Long.MAX 枚 × 3 发 = 2.77e19 枚，配给配得出 1.8e19 枚
        state.addCrafting(miner.source(), 3L);
        // 每槽每发 3e12 枚 × 3e6 发 = 9e18 一枚一笔，两笔同键复加才是越界的那一步
        state.addCrafting(consumer.source(), 3_000_000L);

        var table = MaterialClosureGate.buildMaterialPlan(
                state, twoPatternGraph(miner, consumer), 3_000_000L).assigned();

        var share = table.get(consumer.source());
        assertNotNull(share, () -> "两个槽各 9e18 枚的配给表不该被判超配: 表大小=" + table.size());
        assertEquals(new java.math.BigInteger("18000000000000000000"), share.getExact(GOLD),
                "合计 1.8e19 枚必须如实入账：这张表就是执行侧配给账的被减数");
        assertEquals(Long.MAX_VALUE, share.getLong(GOLD),
                "AE2 的 long 读数仍是向上饱和的哨兵值（窄视图口径一字未动）");
        assertEquals(Long.MAX_VALUE, legacySaturatedBill(9_000_000_000_000_000_000L),
                "对照：老口径的 merge(key, 本次份数, 饱和加) 在同一串输入下只能写下天花板");
    }

    /** 老口径 {@code bill} 的合并式（{@code merge(key, units, saturatingAdd)}），只做对照断言。 */
    private static long legacySaturatedBill(long slotUnits) {
        return slotUnits > Long.MAX_VALUE - slotUnits ? Long.MAX_VALUE : slotUnits + slotUnits;
    }

    /**
     * 条目①（任务 #219）主臂：一枚键的供给<b>只落在 {@code emitted} 那一腿</b>、且合计越出
     * {@code long} 时，配给表必须照发——不再被 cover 自己拼的那套缺腿宇宙判成超配、交白卷。
     *
     * <p>夹具（同键复加才跨得过天花板，单张样板/单笔都顶不过 {@code long}）：消费者每发吃
     * {@code 3e12} 枚金 × {@code 4e6} 发 ⇒ 表上这一格 {@code 1.2e19} 枚；金的来路只有两笔各
     * {@code 6e18} 枚的 {@code emitted}。闸门那一趟读得到 {@code emitted}（守恒式平），HEAD 的
     * cover 腿清单里没有这一条 ⇒ 供给读 {@code 0}、{@code 1.2e19} 的实记被判"超配"、整张表退回
     * 白卷（旧出口是 {@code MaterialPlanBill.noTable()}，它随 A5 一起摘掉了）——执行侧因此没有
     * 可照单的配给，退回提交期自算那一臂（两臂宇宙不同＝假洞入口）。
     */
    @Test
    void supplyOnlyInEmittedLegBeyondLongStillShipsTheTable() {
        var consumer = goldBuyerPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        // 份数 3e12 × 4e6 = 1.2e19 枚：配给表上这一格越出 long
        state.addCrafting(consumer.source(), 4_000_000L);
        // 金的来路只在这两条 emitted 腿里：单笔 6e18 塞得进 long，两笔同键复加才越界
        state.emitItemsWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));
        state.emitItemsWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));
        assertEquals(new java.math.BigInteger("12000000000000000000"),
                state.emitted().getAmount(GOLD).exactValue(),
                "夹具前提：emitted 那一腿合计 1.2e19 枚（>long 的真值，不是天花板哨兵）");

        assertNull(MaterialClosureGate.validate(state, graph(consumer), 4_000_000L),
                "夹具前提：闸门按同一本账判这单是平的");

        var bill = MaterialClosureGate.buildMaterialPlan(state, graph(consumer), 4_000_000L);
        var shipped = bill.assigned().get(consumer.source());
        assertNotNull(shipped, () -> "供给只落在 emitted 腿上的配平表不许交白卷："
                + bill.assigned());
        assertEquals(new java.math.BigInteger("12000000000000000000"), shipped.getExact(GOLD),
                "下发的份数逐字是表上那一格——cover 读的就是闸门同一本账");
    }

    /**
     * 条目①同一臂换一条腿：供给只落在 {@code missing}（模拟轮缺口，守恒式的供给侧平衡项）时，
     * 表同样照发。钉的是"cover 不再自己挑腿"，不是"emitted 那一格特殊"。
     */
    @Test
    void supplyOnlyInMissingLedgerBeyondLongStillShipsTheTable() {
        var consumer = goldBuyerPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(consumer.source(), 4_000_000L);
        // 两笔各 6e18（单笔塞得进 long），同键复加 1.2e19 才越界
        state.addMissingWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));
        state.addMissingWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));

        assertNull(MaterialClosureGate.validate(state, graph(consumer), 4_000_000L),
                "夹具前提：缺料那一腿盖住了需求，闸门判平");

        var bill = MaterialClosureGate.buildMaterialPlan(state, graph(consumer), 4_000_000L);
        var shipped = bill.assigned().get(consumer.source());
        assertNotNull(shipped, () -> "供给只落在 missing 腿上的配平表不许交白卷："
                + bill.assigned());
        assertEquals(new java.math.BigInteger("12000000000000000000"), shipped.getExact(GOLD),
                "下发的份数逐字是表上那一格");
    }

    /** IRON×1 ← 每发 {@code 3e12} 枚金的定键槽（条目①那两臂的被计费样板）。 */
    private static GraphPattern goldBuyerPattern() {
        return new GraphPattern(new FakeDetails(), IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, 3_000_000_000_000L, 1L,
                        GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    /**
     * 金矿样板：无输入，每发产 {@code Long.MAX_VALUE} 枚金（只为把配给的来源顶住）。
     * 必须用带真产出的替身——闸门供给原账的自产腿读 {@code GraphPattern.allOutputs}
     * （任务 #219 条目①定下"供给腿只在闸门那一趟数一次"），替身不带产出的话
     * 供给那一格就真的是 0。
     */
    private static GraphPattern goldMinePattern() {
        return new GraphPattern(new MineDetails(Items.GOLD_ORE, GOLD, Long.MAX_VALUE),
                GOLD, Long.MAX_VALUE,
                List.of(new GenericStack(GOLD, Long.MAX_VALUE)), List.of());
    }

    /** 消费样板：两个定键槽各每发吃 3e12 枚金（同一键两笔，合计才越界）。 */
    private static GraphPattern twoGoldSlotPattern() {
        var slot = new GraphInput(GOLD, 3_000_000_000_000L, 1L,
                GraphInput.Mode.NORMAL, null, null, null, false);
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)), List.of(slot, slot));
    }

    /** IRON×1 ← 模糊槽（multiplier=mult，候选：玻璃@1 → 雪块@1）。 */
    private static GraphPattern ironFromGlassOrSandFuzzy(long mult) {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 1L, mult, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(GLASS, 1L),
                                new GraphInput.Candidate(SNOW, 1L)),
                        java.util.Set.of(GLASS, SNOW), false)));
    }

    /** SNOW×1 → GLASS×1：让玻璃族成为图上可自产族（首选族整槽规则的 owns 判据）。 */
    private static GraphPattern glassFromSnowPattern() {
        return new GraphPattern(new FakeDetails(), GLASS, 1,
                List.of(new GenericStack(GLASS, 1L)),
                List.of(new GraphInput(SNOW, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    /** 以 IRON 为目标、可挂玻璃生产者的双样板图。 */
    private static CraftGraph glassFuzzyGraph(GraphPattern consumer,
            @Nullable GraphPattern glassProducer) {
        var producers = new java.util.HashMap<appeng.api.stacks.AEKey,
                List<GraphPattern>>();
        producers.put(IRON, List.of(consumer));
        var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
        bySource.put(consumer.source(), consumer);
        var order = new java.util.ArrayList<IPatternDetails>();
        order.add(consumer.source());
        if (glassProducer != null) {
            producers.put(GLASS, List.of(glassProducer));
            bySource.put(glassProducer.source(), glassProducer);
            order.add(glassProducer.source());
        }
        return new CraftGraph(IRON,
                producers, Map.copyOf(bySource), order,
                Map.of(), Map.of(), Map.of(),
                Map.of(GLASS, 6L, SNOW, 10L),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
    }

    /** IRON×1 ← 模糊槽（顺位：玻璃每发 8，沙子每发 1）。 */
    private static GraphPattern ironFromFuzzySlot() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 8L, 1L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(GLASS, 8L), new GraphInput.Candidate(SAND, 1L)),
                        java.util.Set.of(GLASS, SAND), false)));
    }

    /** IRON×1 ← 模糊槽（multiplier=3，玻璃与沙子都是每份 1 的候选）。 */
    private static GraphPattern ironFromThreeUnitFuzzySlot() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 1L, 3L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(GLASS, 1L), new GraphInput.Candidate(SAND, 1L)),
                        java.util.Set.of(GLASS, SAND), false)));
    }

    /** 轻量样板替身：definition 只需要唯一身份。 */
    private static final class FakeDetails implements IPatternDetails {
        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return List.of();
        }

        @Override
        public AEItemKey getDefinition() {
            return key(Items.PAPER);
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    /** 带<b>真产出</b>的样板替身：配给来源核对读的是 AE2 那份 {@code getOutputs()}。 */
    private static final class MineDetails implements IPatternDetails {
        private final AEItemKey definition;
        private final GenericStack output;

        private MineDetails(net.minecraft.world.item.Item item, AEKey what, long amount) {
            this.definition = key(item);
            this.output = new GenericStack(what, amount);
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return List.of(output);
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
