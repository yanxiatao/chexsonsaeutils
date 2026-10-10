package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第六刀第一处判据：规划沙盒的<b>起步那一眼</b>必须吃得到无限磁盘的 BigInteger 真值。
 *
 * <p>七张账本换 {@link git.chexson.chexsonsaeutils.bigint.WideKeyCounter} 之后，
 * 沙盒本身已经装得下越界量；卡住的是种子——{@code cacheFuzzy} 从网络那份 {@code long}
 * 库存写进来，饱和读数 {@code Long.MAX_VALUE} 就成了这一格的天花板，计划于是永远不敢申报
 * &gt; long 的用量（它只会算成缺料）。本类只看这一件事：接上 {@link PlanningSupplyTruth}
 * 之后，同一键能连续取走的总量等于真值，而不是等于天花板。
 *
 * <p>不新增读数口子：判据全走 {@code extract} 的行为面（取多少给多少 / 只剩零头），
 * 因为下游求解器本来就是拿它当"池子里还有多少"用。
 */
class GraphPlanningStateSupplyTruthTest {

    private static final AEKey GLASS = AEItemKey.of(Items.GLASS);

    /** 真值 3e19 枚：网络读数在这里被 AE2 的 {@code long} 夹成 9.22e18。 */
    private static final BigInteger TRUTH = new BigInteger("30000000000000000000");
    /** 单笔 9e18 枚：装得进 {@code long}，所以三笔之和（2.7e19）才越界。 */
    private static final long CHUNK = 9_000_000_000_000_000_000L;

    private static PlanningSupplyTruth truthOf(BigInteger supply) {
        return PlanningSupplyTruth.of(Map.of(GLASS, PlanAmount.of(supply)));
    }

    /** 接上真值：连取三笔 9e18 全给满，第四笔才交出剩下的 3e18。 */
    @Test
    void sandboxSeedReadsTheWideSupplyTruth() {
        var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(),
                truthOf(TRUTH));

        for (int round = 1; round <= 3; round++) {
            assertEquals(CHUNK, state.extract(GLASS, CHUNK, false),
                    "第 " + round + " 笔：真值 3e19 枚足够连付三笔 9e18");
        }
        assertEquals(TRUTH.subtract(BigInteger.valueOf(3L).multiply(BigInteger.valueOf(CHUNK)))
                        .longValueExact(),
                state.extract(GLASS, CHUNK, false),
                "第四笔只剩 3e18：沙盒记得住的是真值，不是网络的天花板");
    }

    /** 没有真值就是今日口径，一字不变：起步量顶在 {@code Long.MAX_VALUE}，第二笔就只剩零头。 */
    @Test
    void withoutTruthTheSeedIsExactlyTheLongReading() {
        var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(),
                PlanningSupplyTruth.EMPTY);

        assertEquals(CHUNK, state.extract(GLASS, CHUNK, false));
        assertEquals(Long.MAX_VALUE - CHUNK, state.extract(GLASS, CHUNK, false),
                "对照段：老口径的起步量就是饱和读数，多出来的 2e19 枚根本不存在");
    }

    /**
     * 撤销必须把<b>宽域</b>起步量原样还回来：回滚写的是窄值就等于把真值抹成零头，
     * 而一次规划里 attempt 靠 {@code undoTo} 反复回档，这比种子本身更容易丢账。
     */
    @Test
    void undoRestoresTheWideSeedExactly() {
        var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(),
                truthOf(TRUTH));

        var mark = state.journal();
        assertEquals(CHUNK, state.extract(GLASS, CHUNK, false));
        state.undoTo(mark);

        for (int round = 1; round <= 3; round++) {
            assertEquals(CHUNK, state.extract(GLASS, CHUNK, false),
                    "回档后第 " + round + " 笔仍该给满：宽值必须逐位回到 seed 上");
        }
        assertEquals(TRUTH.subtract(BigInteger.valueOf(3L).multiply(BigInteger.valueOf(CHUNK)))
                        .longValueExact(),
                state.extract(GLASS, CHUNK, false));
    }

    @Test
    void availabilityReadDoesNotSeedOrJournalAndTracksWideUndo() {
        var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(), truthOf(TRUTH));
        int keyId = state.idOf(GLASS);
        int mark = state.journal();
        assertEquals(TRUTH, state.availableExactByIdWide(GLASS, keyId).exactValue());
        assertEquals(mark, state.journal(), "剪枝读数不应触发懒缓存或写入日志");
        assertEquals(CHUNK, state.extract(GLASS, CHUNK, false));
        assertEquals(TRUTH.subtract(BigInteger.valueOf(CHUNK)),
                state.availableExactByIdWide(GLASS, keyId).exactValue());
        state.undoTo(mark);
        assertEquals(TRUTH, state.availableExactByIdWide(GLASS, keyId).exactValue());
        state.ignore(GLASS);
        assertEquals(PlanAmount.ZERO, state.availableExactByIdWide(GLASS, keyId));
    }

    @Test
    void availabilityExcludesRetainedSeedsAndIncludesLaterInsertions() {
        var state = new GraphPlanningState(Map.of(GLASS, 20L), Map.of(GLASS, java.util.List.of(GLASS)));
        int keyId = state.idOf(GLASS);
        state.holdBackRetainedSeedsWide(Map.of(GLASS, PlanAmount.of(7L)));
        assertEquals(13L, state.availableExactByIdWide(GLASS, keyId).fastValue());
        assertEquals(13L, state.extract(GLASS, 100L, false));
        assertEquals(PlanAmount.ZERO, state.availableExactByIdWide(GLASS, keyId));
        state.insert(GLASS, 5L);
        assertEquals(5L, state.availableExactByIdWide(GLASS, keyId).fastValue());
    }
}
