package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
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
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部分提交那一层壳（台账 #124）：{@code NativeJobSubmissionPlan} 只转 4 个 dyeable 字段，
 * 配给表与封套本两本账在这一层之后一起消失。
 *
 * <p>这一腿<b>活着</b>：并行 CPU 的提交链 {@code ParallelCraftingCpuLogic#trySubmitPartialJob}
 * 把 {@code createNativeSubmissionPlan(plan)} 的产物原样交给 {@code trySubmitJob}，后者拿它建作业、
 * 并把它交给 {@code logSubmitBoundary} → {@code describePlanBalance} → {@code
 * adoptPlannedMaterialPlan}。壳不是 {@link RecursiveCraftingPlan}，两本 default 方法交空表 ⇒
 * {@code #44}（配给表唯一化：规划出口 mint、执行侧只照单取）在这一腿被绕开，提交期又自算第二份归属，
 * {@code hole=} 那一格也失去封套可减（回到 {@code #123} 的假洞形状）。
 *
 * <h3>手算 oracle（沿用台账 #123 那一张表）</h3>
 * <pre>
 *   槽倍率 3   每模板量 2 枚   发数 5   ⇒ 整槽 = 15 模板 × 2 枚 = 30 枚
 *   下发的整本 = {GLASS 30, SNOW 30}   封套本 = {GLASS 30}   排水实记 = 整本 − 封套 = {SNOW 30}
 *   壳把 usedItems() 交空（部分提交腿不在这里预抽整段料）⇒ 执行侧供给只剩任务表自产 IRON
 *   ⇒ 穿壳之后该报：src=plan（照单采用）、SNOW hole=30（排水实记的真洞照报）、
 *     GLASS hole=30（每发 6 枚 × 5 发，这一族无实记/无存量/非无限源 ⇒ #196 另立的那一笔）
 * </pre>
 *
 * <p>夹具走真闸门（{@link MaterialClosureGate#buildMaterialPlan}）产两本账，装进真契约，再套真壳；
 * 断言读的是作业里那两本账的<b>逐字</b>读数与提交边界那一行的读数。
 */
final class ParallelPartialSubmitContractShellTest {

    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    private static final long UNITS_PER_TEMPLATE = 2L;
    private static final long SLOT_MULTIPLIER = 3L;
    private static final long FIRINGS = 5L;
    /** 整槽份额＝排水实记＝封套＝30 枚。 */
    private static final long WHOLE_SLOT_UNITS = 30L;
    /** 规划侧 cover 看到的雪块供给（闸门那趟宇宙的供给读数）。 */
    private static final long SNOW_PLAN_SUPPLY = 40L;

    /** 穿壳之后两本账仍在册、份额逐字不变，且提交边界读的是照单采用那一臂。 */
    @Test
    void submissionShellCarriesBothMaterialBooks() {
        var source = new ShellDetails();
        var bill = shippedBill(source);
        var plan = planOf(source, bill);
        var shell = CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan);

        var job = new ParallelExecutingCraftingJob(shell, ignored -> {
        }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, shell);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, shell);

        assertTrue(line.contains(" src=plan"),
                () -> "两本账在壳后一起消失时，提交期会自算第二份归属（src=submit）——"
                        + "那正是 #44 定版要绕开的形状。整行=" + line);
        var shippedAssigned = MaterialAllocationFixtures.assignedSnapshot(job).get(source);
        assertNotNull(shippedAssigned,
                () -> "配给表穿不过这一层壳（作业里没这张样板的账）：" + line);
        assertEquals(WHOLE_SLOT_UNITS, shippedAssigned.getLong(GLASS),
                () -> "封套那一笔在下发的表里逐字不许变（摘掉它就把首位族现场有量那一型夹回 0）：" + line);
        assertEquals(WHOLE_SLOT_UNITS, shippedAssigned.getLong(SNOW),
                () -> "排水实记那一笔逐字不许变：" + line);
        var shippedEnvelope = MaterialAllocationFixtures.envelopeSnapshot(job).get(source);
        assertNotNull(shippedEnvelope,
                () -> "封套那一本没随契约穿过这一层壳 ⇒ hole 那格没有东西可减（回到 #123 的假洞形状）："
                        + line);
        assertEquals(WHOLE_SLOT_UNITS, shippedEnvelope.getLong(GLASS),
                () -> "封套本逐字不许变：" + line);
        assertEquals(0L, shippedEnvelope.getLong(SNOW),
                () -> "排水实记不进封套本（同一趟账的两本账各记各的）：" + line);
    }

    /**
     * 穿壳之后 {@code hole=} 那一格仍是"实记腿只算排水实记"的口径：雪块真洞照报，封套那一笔
     * 仍旧不是它的减数。台账 #196 之后玻璃那一族另外立一笔——这一族在穿壳后的供给里既没有实记、
     * 也没有存量、还不在无限源里，取料点每发真要吃的 6 枚 × 5 发 = 30 枚不许再从缺口里隐身。
     */
    @Test
    void submissionShellKeepsBothHoleLegsReportingWhatTheyReallyOwe() {
        var source = new ShellDetails();
        var plan = planOf(source, shippedBill(source));
        var shell = CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan);
        var job = new ParallelExecutingCraftingJob(shell, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, shell);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, shell);

        assertTrue(line.contains(SNOW + " hole=" + WHOLE_SLOT_UNITS),
                () -> "雪块排水实记 30 枚、壳后供给为 0 ⇒ 真洞 30 枚照报。整行=" + line);
        assertTrue(line.contains(GLASS + " hole=" + WHOLE_SLOT_UNITS),
                () -> "玻璃那一族的每发需求（6 枚 × 5 发 = 30 枚）必须进缺口——旧口径把这一格整条"
                        + "减光，提交边界因此打 ok keys=，实机正是这一格尾段 ranDry。整行=" + line);
        assertTrue(line.contains(" unbalanced=2/"),
                () -> "两条腿各报一格（实记腿雪块 30 枚 + 伸手腿玻璃 30 枚）：" + line);
        assertFalse(line.contains(" ok keys="),
                () -> "缺口非空就不许走 ok 出口：" + line);
    }

    /** 真闸门那一趟账：下发的整本 {GLASS 30, SNOW 30} + 封套本 {GLASS 30}。 */
    private static MaterialClosureGate.MaterialPlanBill shippedBill(ShellDetails source) {
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
        assertNotNull(shipped, () -> "夹具必须真下发这张表，否则这一腿压根不经过这一刀");
        assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(GLASS), "下发表的封套那一笔");
        assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(SNOW), "下发表的排水实记那一笔");
        return bill;
    }

    /** 把闸门那两本账装进真契约（{@code usedItems} 空：部分提交腿不在提交这一步预抽整段料）。 */
    private static ICraftingPlan planOf(
            ShellDetails source, MaterialClosureGate.MaterialPlanBill bill) {
        Map<IPatternDetails, Long> times = new LinkedHashMap<>();
        times.put(source, FIRINGS);
        var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        assigned.putAll(bill.assigned());
        var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        envelopes.putAll(bill.envelopes());
        return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                Map.of(), assigned, null, null, Set.of(), envelopes);
    }

    /** 真候选表的样板替身：原始顺位 [玻璃@2 首位, 雪块@2 备选]、槽倍率 3、无退还键（非模具槽）。 */
    private static final class ShellDetails implements IPatternDetails {

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new ShellInput(
                    new GenericStack(GLASS, UNITS_PER_TEMPLATE),
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
    private static final class ShellInput implements IPatternDetails.IInput {
        private final GenericStack[] candidates;

        private ShellInput(GenericStack... candidates) {
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
        public @org.jetbrains.annotations.Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
