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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账 #143 T2 —— 闸门的<b>点名</b>次序不许吃哈希序。
 *
 * <p>两处读数都是"先点哪个由登记序决定，而登记序来自遍历序"：
 * <ul>
 * <li>{@code explainHoles} 的 {@code exempt=}／{@code cover=} 每键下那几行的先后，取的是
 * {@code state.craftsWide().keys()} 的哈希遍历序；</li>
 * <li>守恒判决串（{@code material closure violated: <键>}）点的是 {@code demand} 登记序里第一个
 * 不平的键，同一本遍历序喂出来的。</li>
 * </ul>
 * 两处都改按 {@code WideCraftCounter} 的注册槽号扫（与 {@code describeNegative} 同一把尺）。
 * 判据：并列档的点名次序在两次相反来序下逐字相同，且钉值是"注册序靠前的那一张先被点"。
 *
 * <p>来序怎么摆：替身样板自带 {@code hashCode} 读数（份数账那条），身份哈希那条不可指定，只能
 * 换一对新实例重掷并从 {@code keySet()} 读回实际序比对。两份读数都只用来摆夹具，不参与期望值。
 */
class MaterialClosureGateDiagnosticOrderTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);

    /**
     * {@code explainHoles}：两张样板各给同一个洞记一行豁免＋一条 cover，两行的先后由遍历序决定。
     */
    @Test
    void holeDiagnosisNamesPatternsInRegistrationOrder() {
        var agrees = describeHole(true);
        var opposes = describeHole(false);

        assertEquals(agrees.text(), opposes.text(),
                () -> "同一份洞，只换了份数账的哈希遍历序，诊断行就换了点名次序："
                        + "来序相同=" + agrees.text() + "，相反=" + opposes.text());
        int paperExempt = agrees.text().indexOf("minecraft:paper#slot0");
        int repeaterExempt = agrees.text().indexOf("minecraft:repeater#slot0");
        assertTrue(paperExempt >= 0 && repeaterExempt >= 0,
                () -> "夹具前提：两张样板都得真给这个洞记上豁免行，否则这条读数压根没跑：" + agrees.text());
        assertTrue(paperExempt < repeaterExempt,
                () -> "豁免行按注册槽号序点（先登记的那张在前）：" + agrees.text());
        assertTrue(agrees.coversFirstAheadSecond(),
                () -> "cover 那一串同样按注册槽号序点：" + agrees.text());
    }

    /**
     * 守恒判决：两种料各差一截时，判决串点的是登记序里第一个不平的键。
     */
    @Test
    void imbalanceVerdictNamesRegistrationFirstShortKey() {
        var agrees = imbalanceVerdict(true);
        var opposes = imbalanceVerdict(false);

        assertTrue(agrees.contains("violated: minecraft:gold_ingot"),
                () -> "先登记的样板吃金：判决必须点金，不是按哈希序先撞见谁就点谁：" + agrees);
        assertEquals(agrees, opposes,
                () -> "同一份失衡账，只换了份数账的哈希遍历序，判决就换了点名的料：" + opposes);
    }

    // ---- explainHoles 夹具 ----

    /** 两次来序各跑一趟 {@code explainHoles}。 */
    private static HoleReading describeHole(boolean wantHashOrderFollowsRegistration) {
        for (int attempt = 0; attempt < 256; attempt++) {
            var first = new Details(Items.PAPER, attempt * 2 + 1);
            var second = new Details(Items.REPEATER, attempt * 2 + 2);
            var state = new GraphPlanningState(Map.of(GLASS, 0L, SNOW, 0L), Map.of());
            state.addCrafting(first, 2L);
            state.addCrafting(second, 2L);
            if (hashOrderFollowsRegistration(state, first, second) != wantHashOrderFollowsRegistration) {
                continue;
            }
            var text = MaterialClosureGate.explainHoles(state,
                    twoPatternGraph(fuzzyPattern(first), fuzzyPattern(second)), List.of(GLASS));
            var coversFrom = text.indexOf("cover=[");
            assertTrue(coversFrom >= 0, () -> "夹具前提：这一洞得有 cover 读数：" + text);
            return new HoleReading(text, text.indexOf("minecraft:paper#slot0", coversFrom)
                    < text.indexOf("minecraft:repeater#slot0", coversFrom));
        }
        throw new AssertionError("夹具前提没造出来：摆不出"
                + (wantHashOrderFollowsRegistration ? "与注册序相同" : "与注册序相反")
                + "的份数账哈希遍历序（256 次尝试）。");
    }

    /** {@code explainHoles} 的读数：整串诊断行，加"cover 里注册序靠前的那张在前"这一判。 */
    private record HoleReading(String text, boolean coversFirstAheadSecond) {
    }

    // ---- 判决点名夹具 ----

    /** 两种定键料各差一截的账：两次来序各跑一趟 {@code validate}，读判决串。 */
    private static String imbalanceVerdict(boolean wantHashOrderFollowsRegistration) {
        for (int attempt = 0; attempt < 256; attempt++) {
            var goldEater = new Details(Items.PAPER, attempt * 2 + 1);
            var diamondEater = new Details(Items.REPEATER, attempt * 2 + 2);
            var state = new GraphPlanningState(Map.of(GOLD, 0L, DIAMOND, 0L), Map.of());
            state.addCrafting(goldEater, 2L);
            state.addCrafting(diamondEater, 2L);
            if (hashOrderFollowsRegistration(state, goldEater, diamondEater)
                    != wantHashOrderFollowsRegistration) {
                continue;
            }
            var violation = MaterialClosureGate.validate(state,
                    twoPatternGraph(exactInputPattern(goldEater, GOLD),
                            exactInputPattern(diamondEater, DIAMOND)),
                    4L);
            assertNotNull(violation, () -> "夹具前提：两种料都没配一份，判决必须报不平");
            return violation;
        }
        throw new AssertionError("夹具前提没造出来：摆不出"
                + (wantHashOrderFollowsRegistration ? "与注册序相同" : "与注册序相反")
                + "的份数账哈希遍历序（256 次尝试）。");
    }

    // ---- 共用夹具 ----

    /** 份数账（哈希图）的遍历序是不是"先登记的那张在前"。只用来摆夹具，不参与任何期望值。 */
    private static boolean hashOrderFollowsRegistration(GraphPlanningState state,
            IPatternDetails first, IPatternDetails second) {
        var iterated = new ArrayList<>(state.craftsWide().keys());
        assertEquals(2, iterated.size(), () -> "夹具前提：两张样板都得在册：" + iterated);
        return iterated.get(0) == first;
    }

    private static CraftGraph twoPatternGraph(GraphPattern first, GraphPattern second) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(first, second)),
                Map.of(first.source(), first, second.source(), second),
                List.of(first.source(), second.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GLASS, 3L, SNOW, 3L, GOLD, 0L, DIAMOND, 0L),
                Set.of(),
                Map.of(), false, null, 1, 1);
    }

    /** IRON×1 ← 模糊槽（候选：玻璃／雪块，各每份 1 枚）。 */
    private static GraphPattern fuzzyPattern(Details source) {
        return new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 1L, 1L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(GLASS, 1L),
                                new GraphInput.Candidate(SNOW, 1L)),
                        Set.of(GLASS, SNOW), false)));
    }

    /** IRON×1 ← 定键槽（每发一枚那一号料）。 */
    private static GraphPattern exactInputPattern(Details source, AEKey input) {
        return new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(input, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    /**
     * 样板替身：原始候选表恒为 [玻璃, 雪块]（毛账与 cover 的键就取这一位），输出 IRON×1，
     * {@code hashCode} 由夹具给定——那是份数账哈希遍历序的唯一变量。
     */
    private static final class Details implements IPatternDetails {
        private final AEItemKey definition;
        private final int hash;

        private Details(Item item, int hash) {
            this.definition = AEItemKey.of(new ItemStack(item));
            this.hash = hash;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new Slot(new GenericStack[] {
                    new GenericStack(GLASS, 1L), new GenericStack(SNOW, 1L)}) };
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
    private record Slot(GenericStack[] candidates) implements IPatternDetails.IInput {
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
