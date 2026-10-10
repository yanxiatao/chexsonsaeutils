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
 * 封套那一族的<b>取料点需求</b>进缺口判据（台账 #196）：实机 {@code ae2:creative_storage_cell x 1e11}
 * 尾段卡死那一格。
 *
 * <h3>缺陷</h3>
 * {@code MaterialClosureGate#billReachKeySlot} 给"排水按规划供给一次都没付到、而取料点按声明顺位真会
 * 伸手"的那一族补记<b>整槽份额的封套</b>；执行侧 {@code billPlannedMaterialHoles} 的 need
 * 逐键<b>剔掉封套那一本</b>（{@code 121/123/126} 定过的口径：封套是许可上界，塞进实取会把
 * 配给表读成假超配）。减到最后，<b>这一族每发真要吃的量整条从
 * 缺口判据里隐身</b>：{@code holes} 空 ⇒ {@code describePlanBalance} 打 {@code ok keys=}（那是分母不是判决）
 * ⇒ 照样下单 ⇒ 执行侧每发吃 1 枚、池内与网络都是 0 ⇒ 回补腿逐次 {@code got=0 ⇒ ranDry} ⇒ 整条 lane 卡死。
 *
 * <h3>手算 oracle（全部独立于被测乘式）</h3>
 * <pre>
 *   槽倍率 3   首位候选 GHOST <b>声明</b>每模板 3e18 枚 / <b>宇宙内</b>那一枚每模板 1e18 枚
 *   备选 SNOW 每模板 1 枚   发数 4
 *   整格需求        = 3 × 4 = 12 模板
 *   排水实记(SNOW)  = 12 模板 × 每模板 1 枚                = 12 枚（SNOW 计划供给 40，付满整格）
 *   封套(GHOST)     = 每发件数(3×1e18=3e18) × 4 发          = 1.2e19 枚（许可上界，按宇宙内那枚记）
 *   每发真要吃(GHOST) = 倍率 3 × 声明每发 3e18 × 4 发         = 3.6e19 枚（取料点那一侧的需求）
 *   ⇒ 封套上界 1.2e19 ≠ 每发真要吃 3.6e19：<b>"拿整本当 need"与"每发需求被抹掉"两型分得开</b>
 *     （台账 #199 回炉第二刀：旧夹具这两个数恒等，回归与真洞同数）
 *   排水实记（剔封套后剩下的）= SNOW 12 ≤ 计划供给 40 ⇒ 表照发
 *   —— 执行侧供给腿 = 任务表自产 IRON 4 枚 + usedItems SNOW 40 枚，GHOST 一族都没有
 *   ⇒ 反向臂：GHOST hole=36000000000000000000 uncoveredNeed=36000000000000000000，不许再打 ok keys=
 * </pre>
 *
 * <p>三条用例都走<b>真接线</b>：表与封套由真闸门 {@code buildMaterialPlan} 产出 → 装进真
 * {@link RecursiveCraftingPlan} 契约 → {@code describePlanBalance}（内部就是真
 * {@code adoptPlannedMaterialPlan} + 真 {@code billPlannedMaterialHoles} + 真排序打印）。
 */
final class ParallelEnvelopeDemandHoleTest {

    private static final AEItemKey GHOST = AEItemKey.of(Items.STRUCTURE_VOID);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    private static final long SLOT_MULTIPLIER = 3L;
    private static final long FIRINGS = 4L;
    /** 首位候选<b>声明</b>的每模板量：3e18 枚，配上倍率 3 与 4 发就把这一格的需求顶出 {@code long}。 */
    private static final long GHOST_UNITS_PER_TEMPLATE = 3_000_000_000_000_000_000L;
    /** 同一枚候选在<b>规划宇宙</b>里的每模板量：1e18 枚——封套按这一枚记（#199 第一刀的落点）。 */
    private static final long GHOST_UNIVERSE_UNITS = 1_000_000_000_000_000_000L;
    /** 手算：封套＝倍率 3 × 宇宙内每发 1e18 × 4 发 = 1.2e19 枚（许可上界）。 */
    private static final BigInteger ENVELOPE_UNITS = new BigInteger("12000000000000000000");
    /** 手算：取料点每发件数 3×3e18=9e18 × 4 发 = 3.6e19 枚（每发真要吃，与封套分得开）。 */
    private static final BigInteger GHOST_SLOT_DEMAND =
            new BigInteger("36000000000000000000");
    /** 手算：排水实付 SNOW = 12 模板 × 每模板 1 枚。 */
    private static final long SNOW_BILLED_UNITS = 12L;
    /** SNOW 一族的计划供给（也是执行侧 usedItems 那一腿）。 */
    private static final long SNOW_PLAN_SUPPLY = 40L;

    /**
     * 反向臂：这一格只被记了封套、该族在任务表里没有生产者、网络存量为 0、也不在无限源清单里。
     *
     * <p>未修主代码上 {@code describePlanBalance} 交出 {@code ok keys=1}（{@code holes} 空的出口），
     * 3.6e19 枚的每发需求一个字都不进缺口判据 ⇒ 下面三条断言一起红。
     */
    @Test
    void envelopeOnlyFamilyBeyondLongEntersTheHoleInsteadOfOkKeys() {
        var source = new EnvelopeDemandDetails();
        var bill = shippedBill(source);
        var plan = planOf(source, bill, snowUsedOnly(), Set.of());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(" src=plan"), () -> "读的是照单采用那一臂：" + line);
        assertTrue(line.contains(GHOST + " hole=" + GHOST_SLOT_DEMAND),
                () -> "这一格每发真要吃的 9e18 枚 × 4 发 = 3.6e19 枚必须进缺口，且不许被 long 天花板截短。"
                        + " 整行=" + line);
        assertTrue(line.contains(" uncoveredNeed=" + GHOST_SLOT_DEMAND),
                () -> "want 侧的整段需求也必须是宽域真值：" + line);
        assertFalse(line.contains(" ok keys="),
                () -> "封套那一族隐身时打出的 ok keys= 是分母不是判决，必然卡死的单不许再报成配平：" + line);
        assertTrue(line.contains(" unbalanced=1/"), () -> "缺口只这一格：" + line);
        // 排水实记那一腿不许被这一刀带出第二个洞：SNOW 实付 12 枚 ≤ 现场到账 40 枚
        assertFalse(line.contains(SNOW + " hole="), () -> "雪块实记已被供给盖住：" + line);
        // 配额夹照旧读整本（封套那一笔照样在下发表里，摘掉它就把现场有量那一型夹回 0）
        assertEquals(ENVELOPE_UNITS, MaterialAllocationFixtures.assignedSnapshot(job).get(source).getExact(GHOST),
                () -> "下发的封套那一笔逐字不变（1.2e19 枚＝宇宙内那枚的每发件数 × 4 发）：" + line);
    }

    /**
     * 正向臂①：同族有现场存量 ⇒ 照旧放行、照旧闭得上。
     *
     * <p>与反向臂同一份表、同一串乘式，只把 {@code usedItems} 多给 5 枚 GHOST——顺位退路的判据只问
     * "这一族在现场拿不拿得到"，不问拿到的够不够整格（够不够由上面那一趟排水实记的 need 核）。
     * 期望逐字：整行 {@code ok keys=}，且封套那一笔 1.2e19 枚仍在下发的表里。
     */
    @Test
    void envelopeFamilyWithNetworkStockStillClosesTheBalanceLine() {
        var source = new EnvelopeDemandDetails();
        var bill = shippedBill(source);
        var used = new KeyCounter();
        used.add(SNOW, SNOW_PLAN_SUPPLY);
        used.add(GHOST, 5L);
        var plan = planOf(source, bill, used, Set.of());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(" ok keys="),
                () -> "同族有存量＝退路成立，这一格不许被报成缺料：" + line);
        assertFalse(line.contains("hole="), () -> "一个洞都不该有：" + line);
        assertEquals(ENVELOPE_UNITS, MaterialAllocationFixtures.assignedSnapshot(job).get(source).getExact(GHOST),
                () -> "放行是靠退路成立，不是靠把封套从配额账上摘掉：" + line);
    }

    /**
     * 正向臂②：同族在无限源清单里 ⇒ 照旧放行。
     *
     * <p>同一份表、同一串乘式，只把 GHOST 认成无限源（计划随契约带下来的那份键集）。期望逐字：
     * {@code ok keys=}，且 1.2e19 枚的封套那一笔一字不动。
     */
    @Test
    void envelopeFamilyFromUnboundedSourceStaysOutOfTheHole() {
        var source = new EnvelopeDemandDetails();
        var bill = shippedBill(source);
        var plan = planOf(source, bill, snowUsedOnly(), Set.of(GHOST));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(" ok keys="),
                () -> "这一族本就不该向网络抽它：" + line);
        assertFalse(line.contains("hole="), () -> "无限源不许被立成欠账：" + line);
        assertEquals(1, job.unboundedSupplyKeys.size(), () -> "夹具前提：这一枚确实在清单里：" + line);
    }

    /**
     * 正向臂③：同族已经有<b>排水实记</b> ⇒ 同一笔欠账只许报一遍。
     *
     * <p>手算（30 枚那一档，装得进 {@code long}，好把"报两遍"这一档单独钉死）：
     * <pre>
     *   assigned{GHOST} = 60   envelopes{GHOST} = 30   ⇒ 排水实记 = 60 − 30 = 30 枚
     *   执行侧供给 = 空 ⇒ 实记那一腿报 GHOST hole=30（uncoveredNeed=30）
     *   封套那一腿（每发件数 2×3=6 × 5 发 = 30 枚）必须被"同族有实记"挡住 ⇒ 总洞仍是 30，不是 60
     * </pre>
     * 少了这一道挡，同一格就被算成两笔欠账（{@code hole=60}），正是顺位悲观报毛账的形状。
     */
    @Test
    void familyAlreadyBilledByDrainIsNotBilledTwice() {
        var source = new SmallEnvelopeDetails();
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        var assignedRow = new WideKeyCounter();
        assignedRow.add(GHOST, new BigInteger("60"));
        assigned.put(source, assignedRow);
        var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        var envelopeRow = new WideKeyCounter();
        envelopeRow.add(GHOST, 30L);
        envelopes.put(source, envelopeRow);

        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(source, 5L);
        var base = new CraftingPlan(new GenericStack(IRON, 5L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), 5L,
                Map.of(), assigned, null, null, Set.of(), envelopes);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(GHOST + " hole=30 "),
                () -> "排水实记那一腿报 30 枚，封套那一腿不许再加 30 枚。整行=" + line);
        assertFalse(line.contains(GHOST + " hole=60"), () -> "同一格报两遍＝毛账：" + line);
        assertTrue(line.contains(" unbalanced=1/"), () -> "缺口只这一格：" + line);
    }

    /** 执行侧供给的存量那一腿：雪块按计划到账 40 枚。 */
    private static KeyCounter snowUsedOnly() {
        var used = new KeyCounter();
        used.add(SNOW, SNOW_PLAN_SUPPLY);
        return used;
    }

    /** 真闸门那一趟账：下发的整本 {GHOST 1.2e19（封套）, SNOW 12（排水实记）} + 封套本 {GHOST 1.2e19}。 */
    private static MaterialClosureGate.MaterialPlanBill shippedBill(EnvelopeDemandDetails source) {
        var pattern = new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GHOST, GHOST_UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                        GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(SNOW, 1L),
                                new GraphInput.Candidate(GHOST, GHOST_UNIVERSE_UNITS)),
                        Set.of(GHOST, SNOW), false)));
        var graph = new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GHOST, 0L, SNOW, SNOW_PLAN_SUPPLY),
                Set.of(), Map.of(), false, null, 1, 1);
        var state = new GraphPlanningState(Map.of(GHOST, 0L, SNOW, SNOW_PLAN_SUPPLY), Map.of());
        state.addCrafting(source, FIRINGS);
        // 首族计划供给 0：不提取 ⇒ 该族在本格的排水实吃恒 0，只剩封套那一笔
        state.extract(SNOW, SNOW_PLAN_SUPPLY, false);

        var bill = MaterialClosureGate.buildMaterialPlan(state, graph, FIRINGS);
        var shipped = bill.assigned().get(source);
        assertNotNull(shipped, () -> "夹具前提：闸门必须把这张表发出去（白卷时执行侧走自算臂，"
                + "下面所有断言都不再经过这一刀）");
        assertEquals(ENVELOPE_UNITS, shipped.getExact(GHOST),
                () -> "夹具前提：封套那一笔＝宇宙内那枚的每发件数 3e18 × 4 发 = 1.2e19 枚, 表=" + shipped);
        assertEquals(SNOW_BILLED_UNITS, shipped.getLong(SNOW),
                () -> "夹具前提：整格由备选那一枚付满 12 枚, 表=" + shipped);
        assertEquals(ENVELOPE_UNITS, bill.envelopes().get(source).getExact(GHOST),
                "同一笔值另记进封套本");
        return bill;
    }

    /** 把闸门那两本账装进真契约；{@code used} 与 {@code unbounded} 由三条用例各自给。 */
    private static RecursiveCraftingPlan planOf(EnvelopeDemandDetails source,
            MaterialClosureGate.MaterialPlanBill bill, KeyCounter used, Set<AEKey> unbounded) {
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(source, FIRINGS);
        var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                used, new KeyCounter(), new KeyCounter(), times);
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        assigned.putAll(bill.assigned());
        var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        envelopes.putAll(bill.envelopes());
        return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                Map.of(), assigned, null, null, unbounded, envelopes);
    }

    /**
     * 反向臂/正向臂①②的样板：原始候选顺位 [GHOST@3e18 首位, SNOW@1 备选]、槽倍率 3、无退还键。
     * {@code getInputs()} 必须交回<b>真的</b>候选表，否则 {@code billReachKeySlot} 对早退。
     */
    private static final class EnvelopeDemandDetails implements IPatternDetails {

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new EnvelopeInput(
                    new GenericStack(GHOST, GHOST_UNITS_PER_TEMPLATE),
                    new GenericStack(SNOW, 1L)) };
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

    /** 正向臂③的样板：候选每模板 2 枚、倍率 3、5 发 ⇒ 这一格每发真要吃的量＝ 6 × 5 = 30 枚。 */
    private static final class SmallEnvelopeDetails implements IPatternDetails {

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new EnvelopeInput(
                    new GenericStack(GHOST, 2L), new GenericStack(SNOW, 2L)) };
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

    /** 模糊原始槽：倍率 3、两个候选、无退还键（非模具槽）。 */
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
