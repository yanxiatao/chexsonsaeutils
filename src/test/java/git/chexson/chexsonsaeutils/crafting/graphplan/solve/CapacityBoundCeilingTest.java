package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.networking.crafting.CalculationStrategy;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乐观上界的天花板必须落在<b>请求域</b>上，不能落在 {@code 2^62} 上。
 *
 * <p>请求量至今仍然是 {@code long}（9.22e18 合法），而旧天花板是 4.61e18。掐点在
 * {@code CRAFT_LESS} 那圈二分：它只试 {@code testAmount < requestedAmount &&
 * testAmount <= rootCap} 的量，整份请求只有在 {@code rootCap >= requestedAmount} 的快路里
 * 才会被原样试一次。于是"网络里明摆着 6e18 枚金、每发 1 金换 1 铁"这张配得平的图，
 * 最多只会被排到 4.61e18 发——<b>静默少产</b>，连缺料都不报。
 *
 * <p>这条链上 {@code MAX_AMOUNT}（2^52）早就由 wideAmountSolver 开关放行，天花板没跟着放行，
 * 就是用户要的"上限不是 long 上限"的反面。判据只看行为面：不许拒、不许缺料、
 * 份数与用量必须正好等于请求。
 */
class CapacityBoundCeilingTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);

    /** 6e18 枚：合法的 {@code long} 请求，但大于旧天花板 {@code 1L << 62 = 4.61e18}。 */
    private static final long REQUEST_6E18 = 6_000_000_000_000_000_000L;

    /** 1e18 枚铁：请求本身合法（{@code long}），放大 10 倍后用料越出 {@code long}。 */
    private static final long REQUEST_1E18 = 1_000_000_000_000_000_000L;
    private static final java.math.BigInteger TRUTH_1_5E19 = new java.math.BigInteger("15000000000000000000");

    @Test
    void craftLessRouteMustNotQuietlyStopAtTheOldCeiling() {
        var producer = new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
        var graph = new CraftGraph(IRON,
                Map.of(IRON, List.of(producer)),
                Map.of(producer.source(), producer),
                List.of(producer.source()),
                Map.of(), Map.of(), Map.of(),
                Map.<AEKey, Long>of(GOLD, REQUEST_6E18),
                java.util.Set.of(), Map.of(), false, null, 1, 1);

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_6E18);

        assertFalse(result.hasDeclined(), () -> "6e18 枚请求被上界天花板挡在门外: "
                + result.declineReason());
        var state = result.state();
        assertTrue(state.missingItems().isEmpty(),
                () -> "网络里就有 6e18 枚金，不许报任何缺料: " + state.missingItems());
        assertEquals(REQUEST_6E18, (long) state.crafts().get(producer.source()),
                "份数必须等于请求：每发 1 枚铁 ⇒ 6e18 发。少一分就是静默少产");
        assertEquals(REQUEST_6E18, state.usedItems().get(GOLD),
                "用量必须等于请求：每发 1 枚金");
    }

    /**
     * 沙盒抽走多少，{@code used} 账就必须记多少<b>真值</b>——哪怕这一格的起步量本身 &gt; long。
     *
     * <p>旧实现是 {@code unmodified.getLong − working.getLong}：两个读数都走<b>饱和主表</b>。
     * 只要 working 还在溢出区（主表恒为 {@code Long.MAX_VALUE}），这一笔就报 {@code 0}；
     * 抽到刚好跌破天花板之后，才开始用 {@code Long.MAX_VALUE − working} 倒推。
     * 于是从真值 1.5e19 枚里抽走 {@code Long.MAX_VALUE} 枚，账上只剩
     * {@code 3446744073709551607} 枚——差的那 5.78e18 枚不是没抽，是<b>记丢了</b>。
     * 这本账正是守恒闸门与配给表的供给侧（{@code addToSupply(supply, state.used())}），
     * 少记一截就把配得平的图判成"材料不闭合"整条拒掉。
     */
    @Test
    void usedLedgerRecordsTheWideDrawInsteadOfTheSaturatedDifference() {
        var state = new GraphPlanningState(Map.<AEKey, Long>of(GOLD, Long.MAX_VALUE), Map.of(),
                git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.of(
                        Map.of(GOLD, git.chexson.chexsonsaeutils.bigint.PlanAmount
                                .of(TRUTH_1_5E19))));

        // 先抽 1e18 枚：working 仍在溢出区，旧口径这里恒报 0——这一笔抽取整个隐身
        assertEquals(REQUEST_1E18, state.extract(GOLD, REQUEST_1E18, false),
                "真值 1.5e19 枚起步，抽 1e18 枚必须给满");
        assertEquals(java.math.BigInteger.valueOf(REQUEST_1E18), state.used().getExact(GOLD),
                "working 还在溢出区也要当场记真值：旧口径两个饱和读数一减恒为 0");

        // 再抽 Long.MAX 枚：累计 1.1e19 枚，working 跌出溢出区，旧口径从这里开始把账记丢
        assertEquals(Long.MAX_VALUE, state.extract(GOLD, Long.MAX_VALUE, false),
                "沙盒里还剩 1.4e19 枚，整笔 Long.MAX 枚都该给得出");
        assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).add(
                        java.math.BigInteger.valueOf(REQUEST_1E18)),
                state.used().getExact(GOLD),
                "used 必须等于两次抽取之和（1.0e18 + 9.22e18）：饱和视图倒推会少记 5.78e18 枚");
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
            return AEItemKey.of(new ItemStack(Items.PAPER));
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
