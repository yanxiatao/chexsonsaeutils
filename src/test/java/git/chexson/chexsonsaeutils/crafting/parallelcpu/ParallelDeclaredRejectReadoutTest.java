package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两格<b>读数诚实</b>用例（实机那一跑只有 {@code declaredReject=99} 一个聚合数、四型同影，
 * 上一轮全靠"点名"才定性成功）：
 *
 * <ul>
 *   <li>R1：{@code declaredReject=} 那一格要说得出是谁（样板）、哪台机器、在哪个出口被拒；</li>
 *   <li>R2：{@code src=…<-ready} 那个标签会说谎——它只读池子、每槽只看候选表<b>第一个键</b>
 *       的 {@code >0}，不读欠账遮着的那一截、不读网络、不读每发件数与份数。改成
 *       {@code poolFirst>0} 之后，"池里首个候选有余量"与"这一格能开工"不再是同一句话。</li>
 * </ul>
 */
final class ParallelDeclaredRejectReadoutTest {

    private static final long SESSION_QUOTA = 42_662_400_000L;
    private static final AEKey SHINING = AEItemKey.of(Items.NETHERITE_BLOCK);
    private static final AEKey CELL = AEItemKey.of(Items.CHEST);
    /** 每发要 1000 枚、池里只有 1 枚：一发也开不了，旧标签却印成 {@code ready}。 */
    private static final long UNITS_PER_FIRING = 1000L;

    private static final String CELL_A = "minecraft:barrel@UselessBigIntegerDeclaredHost"
            + "#throttled#overworld@1,2,3";
    private static final String CELL_B = "minecraft:barrel@UselessBigIntegerDeclaredHost"
            + "#capacity#overworld@1,2,3";
    private static final String CELL_C = "minecraft:barrel@UselessBigIntegerDeclaredHost"
            + "#admit-null#overworld@4,5,6";
    /** 拒收出口的两档之一（{@code commit()} 回 false 且<b>没</b>吃料；另一档 {@code -tookInput}）。 */
    private static final String CELL_D = "minecraft:barrel@UselessBigIntegerDeclaredHost"
            + "#commit-false-noTake#overworld@4,5,6";

    /** R1：一格点名格逐字带出样板身份、机器标识与出口。 */
    @Test
    void declaredRejectCellNamesPatternMachineAndExit() {
        var cells = new LinkedHashMap<String, Long>();
        cells.put(CELL_B, 99L);

        assertEquals("[" + CELL_B + "=99]",
                ParallelCraftingCpuLogic.StallProbe.describeDeclaredRejects(cells, 0L),
                "declaredReject 那一格要说得出是谁、在哪个出口被拒");
    }

    /** R1：四格封顶，印不下的合成一格 {@code 更多=N}（不把"看到的这几格"冒充全部）。 */
    @Test
    void declaredRejectCellsAreCappedAtFourWithHonestOverflow() {
        var cells = new LinkedHashMap<String, Long>();
        cells.put(CELL_A, 3L);
        cells.put(CELL_B, 41L);
        cells.put(CELL_C, 55L);
        cells.put(CELL_D, 2L);
        cells.put("minecraft:barrel@OtherHost#capacity#overworld@7,8,9", 1L);

        assertEquals("[" + CELL_A + "=3/" + CELL_B + "=41/" + CELL_C + "=55/" + CELL_D + "=2/更多=1]",
                ParallelCraftingCpuLogic.StallProbe.describeDeclaredRejects(cells, 0L));
        assertEquals("[" + CELL_A + "=3/" + CELL_B + "=41/" + CELL_C + "=55/" + CELL_D + "=2/更多=11]",
                ParallelCraftingCpuLogic.StallProbe.describeDeclaredRejects(cells, 10L));
        assertEquals("-", ParallelCraftingCpuLogic.StallProbe.describeDeclaredRejects(Map.of(), 0L),
                "一笔没记就是短横，不许凭空点名谁");
    }

    /**
     * F2：{@code declaredReject=} 这一格是<b>一个不带空白的 token</b>——聚合数与点名格用 {@code /} 连。
     *
     * <p>旧实现红在这一格印成 {@code declaredReject=%d %s}：中间那格空格让按空白切 token 的下游
     * 把"一个数 + 一串点名格"切成两段。
     */
    @Test
    void declaredRejectReadoutMergesCountAndCellsIntoOneToken() {
        var cells = new LinkedHashMap<String, Long>();
        cells.put(CELL_B, 41L);

        assertEquals(" declaredReject=99/[" + CELL_B + "=41]",
                ParallelCraftingCpuLogic.StallProbe.describeDeclaredRejectReadout(99L, cells, 0L),
                "数与点名格合成一格：中间不许有空白");
        assertEquals(" declaredReject=99/-",
                ParallelCraftingCpuLogic.StallProbe.describeDeclaredRejectReadout(99L, Map.of(), 0L),
                "一笔没记时那一半仍是短横，整串照样不含空白");
    }


    /**
     * R2：池里首个候选 {@code >0} 却一发也开不了工（每发要 1000 枚、只有 1 枚）——
     * 那一格只能说"首个候选>0"，旧标签 {@code ready} 在这里说谎。
     */
    @Test
    void blockedInputLabelSaysPoolFirstInsteadOfReady() {
        var consumer = consumingPattern();
        var producer = producerNeedingCells();
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(consumer, 1L);
        times.put(producer, 1L);
        var job = jobWithRation(times);

        var rendered = ParallelCraftingCpuLogic.StallProbe.formatQuotaBlindspots(
                job, Map.of(CELL, 1L), null);

        assertTrue(rendered.contains("#1<-poolFirst>0]"),
                "池里首个候选>0 才是这一格真正读得出的东西：" + rendered);
        assertFalse(rendered.contains("ready"),
                "旧措辞会被读成\"这一格能开工\"，而它每发要 1000 枚：" + rendered);
    }

    private static ParallelExecutingCraftingJob jobWithRation(Map<IPatternDetails, Long> times) {
        var consumerKey = times.keySet().iterator().next();
        var share = new WideKeyCounter();
        share.add(SHINING, SESSION_QUOTA);
        var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        material.put(consumerKey, share);
        var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                1L, Map.of(), material, null, null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
        return job;
    }

    private static FakePattern consumingPattern() {
        return new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                new GenericStack[]{new GenericStack(CELL, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(SHINING, 1L), 1L)});
    }

    private static FakePattern producerNeedingCells() {
        return new FakePattern(AEItemKey.of(Items.IRON_BLOCK),
                new GenericStack[]{new GenericStack(SHINING, 1L)},
                new IPatternDetails.IInput[]{new FakeInput(new GenericStack(CELL, 1L), UNITS_PER_FIRING)});
    }

    private record FakePattern(AEItemKey definition, GenericStack[] outputs,
            IPatternDetails.IInput[] slots) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(outputs);
        }
    }

    private record FakeInput(GenericStack candidate, long multiplier) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[]{candidate};
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return candidate != null && candidate.what().equals(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
