package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环种账本：只按任务表拓扑算"哪些键靠环自己转回来、每发起步量多少"，喂给排序用。
 *
 * <p>起步那份种子由规划期多算一发给出来（RetentionApplier），执行侧要做的只是让它
 * 在被别人抽走之前先转起来——不扣着任何人。
 */
class RingSeedLedgerTest {

    private static final AEKey SEED = AEItemKey.of(Items.NETHERITE_SCRAP);
    private static final AEKey ORE = AEItemKey.of(Items.IRON_ORE);
    private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey RIVAL_OUT = AEItemKey.of(Items.COAL_BLOCK);

    private static FakePattern of(Item definitionItem, FakeInput slot, GenericStack... outputs) {
        return FakePattern.colored(-1, AEItemKey.of(new ItemStack(definitionItem)),
                List.of(outputs), slot);
    }

    private static FakePattern rival() {
        return of(Items.BOOK, FakeInput.stack(SEED, 1L), new GenericStack(RIVAL_OUT, 1L));
    }

    private static FakePattern selfFeedingRing() {
        return of(Items.FLOWER_POT, FakeInput.stack(SEED, 1L),
                new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
    }

    @Test
    void starterAmountIsPerFiringConsumption() {
        FakePattern ring = selfFeedingRing();
        Map<IPatternDetails, Long> tasks = new HashMap<>();
        tasks.put(ring, 7L);
        tasks.put(rival(), 3L);
        var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

        assertEquals(1L, ledger.seedAmounts().get(SEED),
                "起步份数按一发吃掉该键的量计，不是整场用量");
    }

    /** 账本交给 RecursiveTaskOrdering 之后，环生产者才排在旁人之前。 */
    @Test
    void derivedSeedsPutRingProducersFirst() {
        FakePattern ring = selfFeedingRing();
        FakePattern rival = rival();
        Map<IPatternDetails, Long> tasks = new HashMap<>();
        tasks.put(rival, 3L);
        tasks.put(ring, 7L);
        var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

        var ordered = RecursiveTaskOrdering.orderedEntries(tasks, ledger.seedAmounts());

        assertSame(ring, ordered.get(0).getKey(), "环生产者没有排到旁人之前");
    }

    /** 环任务转完之后账本自动失效，不再把任何键标成要留种的。 */
    @Test
    void seedAccountDropsWithTheRingTask() {
        FakePattern ring = selfFeedingRing();
        FakePattern rival = rival();
        Map<IPatternDetails, Long> tasks = new HashMap<>();
        tasks.put(ring, 7L);
        tasks.put(rival, 3L);
        var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));
        assertEquals(1L, ledger.seedAmounts().get(SEED));

        tasks.remove(ring);

        assertTrue(ledger.seedAmounts().isEmpty(), "环已经转完了，账本还记着种子");
    }

    /** 链式环（A 吃 SEED 出 ORE、B 吃 ORE 回 SEED）里没有任何样板把物料还给自己。 */
    @Test
    void chainRingLinksBothHaveStarterAmounts() {
        FakePattern emitsLink = of(Items.BARRIER, FakeInput.stack(SEED, 1L),
                new GenericStack(ORE, 1L), new GenericStack(PRODUCT, 1L));
        FakePattern consumesLink = of(Items.BRUSH, FakeInput.stack(ORE, 1L),
                new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
        FakePattern rival = rival();
        Map<IPatternDetails, Long> tasks = new HashMap<>();
        tasks.put(emitsLink, 4L);
        tasks.put(consumesLink, 4L);
        tasks.put(rival, 2L);
        var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

        assertEquals(1L, ledger.seedAmounts().get(SEED));
        assertEquals(1L, ledger.seedAmounts().get(ORE), "链上中转的那一枚也要有起步量");

        var ordered = RecursiveTaskOrdering.orderedEntries(tasks, ledger.seedAmounts());
        assertSame(rival, ordered.get(ordered.size() - 1).getKey(),
                "旁人在环转起来之前就可以抢料");
    }

    /**
     * 每发吃掉得比还回来的多（本例 {@code owed=2×3=6}、{@code handedBack=1×3=3}）：这一环本来就净吃
     * 那一枚种，缺口由规划期起步提取补——按"环优先跑，跑完环再放行"的口径它<b>照样是环种、照样进账</b>。
     *
     * <p>旧口径"分量内产出不少于消耗才算有回炉能力"在这里整键撤账 ⇒ 取料点不产留底 ⇒ 池里那颗种
     * 在环转起来之前被旁人一发抽干。册子按<b>精确种键</b>各交两笔每发量（吃几枚、还几枚），
     * <b>留底取哪一截</b>由取料点按同一趟的复加现算（见 {@code ParallelPlanReservation#buildRingSeedReserve}）。
     */
    @Test
    void netConsumingKeyIsStillASeedAndHandsOutBothPerFiringLegs() {
        FakePattern netConsumer = of(Items.CALIBRATED_SCULK_SENSOR, FakeInput.stack(SEED, 2L),
                new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
        Map<IPatternDetails, Long> tasks = new HashMap<>();
        tasks.put(netConsumer, 3L);
        var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

        assertEquals(2L, ledger.seedAmounts().get(SEED),
                "owed>handedBack 那一档照样进账：起步量仍按一发吃掉该键的量计");
        var demand = ledger.seedDemandsByFamily().get(SEED.getPrimaryKey());
        assertNotNull(demand, "吃它的那一张同时还在还它（一发还 1 枚）⇒ 它与自己同分量 ⇒ 必须在册"
                + "（旧口径按 owed>handedBack 在这里整键撤账）");
        assertEquals(1, demand.seedKeys().size(), "这一族下只有 SEED 那一枚精确键在册");
        var seedKey = demand.seedKeys().get(0);
        assertEquals(SEED, seedKey.seed(), "在册的是那一枚精确键本身，不是它的族");
        assertEquals(Map.of(netConsumer, 2L), seedKey.perFiringConsume(),
                "每发吃掉几枚：留底那笔复加的负债侧");
        assertEquals(Map.of(netConsumer, 1L), seedKey.perFiringReturn(),
                "每发还回几枚：与负债侧同一趟、同一批发数，取料点按两笔大小取留底那一截");
    }

    /** 同槽多候选是互斥选择：起步量按一份计，不能被算成两枚。 */
    @Test
    void mutuallyExclusiveCandidatesCountOneSeed() {
        FakePattern ring = of(Items.CARTOGRAPHY_TABLE, FakeInput.multi(1L, SEED, SEED),
                new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
        Map<IPatternDetails, Long> tasks = new HashMap<>();
        tasks.put(ring, 5L);
        var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

        assertEquals(1L, ledger.seedAmounts().get(SEED));
    }
}
