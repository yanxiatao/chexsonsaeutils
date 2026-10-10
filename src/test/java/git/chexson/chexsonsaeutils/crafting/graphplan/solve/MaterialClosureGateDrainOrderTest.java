package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 台账 #143 T1 —— 守恒闸门槽排序的<b>并列档</b>自测。
 *
 * <p>{@code MaterialClosureGate#validateFuzzyDrain} 把待排水的模糊槽按样板规范串定序，但两张
 * definition 相同、输入输出与候选表逐字相同的样板在这一档<b>并列</b>，稳定排序于是原样保留
 * 来序＝{@code state.craftsWide().keys()} 的哈希遍历序（{@code IPatternDetails#hashCode} 一路
 * 取到 {@code Item} 的身份哈希）。尾键因此接成（规范串，槽下标，这张样板进份数账的注册槽号）。
 *
 * <p>判据：同一张表，两次的哈希来序相反 ⇒ 排水记账与配给表份额<b>逐字相同</b>，且等于独立手算表
 * （注册序靠前的那张先吃顺位第一候选的玻璃 3 枚；另一张退到雪块 3 枚并补整槽封套）。
 * 未修的树上这条红：相反那一趟先把第二张排进顺位，两行的份额对调。
 *
 * <h3>夹具怎么把"哈希来序"摆成要的形状</h3>
 * 替身样板自带一个 {@code hashCode} 读数（＝这份账的哈希遍历序的唯一变量），注册序恒定为
 * "先造的这张在前"；每次尝试换一对新的读数、造一本新的份数账，再从账本自己的遍历序里<b>读回</b>
 * 实际来序与注册序比对。这份读数只用来摆夹具，不参与任何期望值——期望是一张独立手算的份额表。
 */
class MaterialClosureGateDrainOrderTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);

    /** 手算表：槽倍率 1 × 每份 1 枚 × 3 发 = 整槽 3 枚；两张样板共用同一个池子（玻璃 3、雪块 3）。 */
    private static final String EXPECTED_DRAIN_PROFILE = "G3S0|G3S3";

    @Test
    void identicallyDeclaredFuzzySlotsDrainInRegistrationOrder() {
        var agrees = drainProfile(true);
        var opposes = drainProfile(false);

        assertEquals(EXPECTED_DRAIN_PROFILE, agrees.profile(),
                () -> "注册序靠前的那张先吃顺位第一候选（玻璃 3 枚），另一张退到雪块并补整槽封套："
                        + agrees.profile());
        assertEquals(agrees.profile(), opposes.profile(),
                () -> "同一张表，只换了份数账的哈希遍历序，排水记账就对调了＝槽排序的并列档留在哈希序上。"
                        + "来序与注册序相同=" + agrees.profile() + "，相反=" + opposes.profile());
    }

    /**
     * 造一本两张逐字同声明样板的份数账，按要的来序（与注册序相同／相反）摆好后跑一趟闸门。
     *
     * <p>256 次还没摆出来就是夹具前提没成立，直接红，不许把没跑到的形状当证据。
     */
    private static Profile drainProfile(boolean wantHashOrderFollowsRegistration) {
        for (int attempt = 0; attempt < 256; attempt++) {
            var first = new FuzzyDetails(Items.REPEATER, attempt * 2 + 1);
            var second = new FuzzyDetails(Items.REPEATER, attempt * 2 + 2);
            var state = new GraphPlanningState(Map.of(GLASS, 3L, SNOW, 3L), Map.of());
            state.addCrafting(first, 3L);
            state.addCrafting(second, 3L);
            if (hashOrderFollowsRegistration(state, first, second) != wantHashOrderFollowsRegistration) {
                continue;
            }
            state.extract(GLASS, 3L, false);
            state.extract(SNOW, 3L, false);
            var graph = twoPatternGraph(fuzzyPattern(first), fuzzyPattern(second));
            var violation = MaterialClosureGate.validate(state, graph, 6L);
            assertNull(violation, () -> "夹具前提：两侧账都配得平，闸门不该拒：" + violation);
            var table = MaterialClosureGate.buildMaterialPlan(state, graph, 6L).assigned();
            return new Profile(row(table, first) + "|" + row(table, second));
        }
        throw new AssertionError("夹具前提没造出来：摆不出"
                + (wantHashOrderFollowsRegistration ? "与注册序相同" : "与注册序相反")
                + "的份数账哈希遍历序（256 次尝试）。");
    }

    /** 排水后的配给表按注册序摊平成一串读数：每张样板在这个键上吃了／封到几枚。 */
    private static String row(Map<IPatternDetails, WideKeyCounter> table, IPatternDetails source) {
        var task = table.get(source);
        assertNotNull(task, () -> "这张样板的配给条目没下发：表=" + table);
        return "G" + task.getLong(GLASS) + "S" + task.getLong(SNOW);
    }

    /** 份数账（哈希图）的遍历序是不是"先登记的那张在前"。只用来摆夹具，不参与任何期望值。 */
    private static boolean hashOrderFollowsRegistration(GraphPlanningState state,
            IPatternDetails first, IPatternDetails second) {
        var iterated = new ArrayList<>(state.craftsWide().keys());
        assertEquals(2, iterated.size(), () -> "夹具前提：两张样板都得在册：" + iterated);
        return iterated.get(0) == first;
    }

    /** 一次排水的读数：按注册序摊平的配给表。 */
    private record Profile(String profile) {
    }

    private static CraftGraph twoPatternGraph(GraphPattern first, GraphPattern second) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(first, second)),
                Map.of(first.source(), first, second.source(), second),
                List.of(first.source(), second.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GLASS, 3L, SNOW, 3L),
                Set.of(),
                Map.of(), false, null, 1, 1);
    }

    /** IRON×1 ← 模糊槽（顺位：玻璃每份 1，雪块每份 1），两张样板这一份声明逐字相同。 */
    private static GraphPattern fuzzyPattern(FuzzyDetails source) {
        return new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 1L, 1L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(GLASS, 1L),
                                new GraphInput.Candidate(SNOW, 1L)),
                        Set.of(GLASS, SNOW), false)));
    }

    /**
     * 样板替身：原始候选表恒为 [玻璃, 雪块]（首位即 {@code billReachKeySlot} 的 reach 键），
     * 输出 IRON×1，{@code hashCode} 由夹具给定——那是这份账的哈希遍历序的唯一变量。
     */
    private static final class FuzzyDetails implements IPatternDetails {
        private final AEItemKey definition;
        private final int hash;

        private FuzzyDetails(Item item, int hash) {
            this.definition = AEItemKey.of(new ItemStack(item));
            this.hash = hash;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new FuzzySlotInput(
                    new GenericStack[] {new GenericStack(GLASS, 1L), new GenericStack(SNOW, 1L)}) };
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(IRON, 1L));
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    /** 原始模糊槽：候选按数组序、倍率 1、无退料（非模具槽）。 */
    private record FuzzySlotInput(GenericStack[] candidates) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
            return true;
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
